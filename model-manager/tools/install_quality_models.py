"""Explicit quality-pack installation. Immutable revisions, bounded disk, no inference."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import uuid
import urllib.request
from concurrent.futures import ThreadPoolExecutor

RANGED = False


def range_download(repo, revision, filename, stage, expected_bytes):
    """Optional bounded CDN workaround: 4 requests, <=32 MiB resident data, 2 attempts."""
    path = stage / str(safe_file(filename))
    block_bytes = 8 * 1024**2
    url = "https://huggingface.co/{}/resolve/{}/{}?download=true".format(repo, revision, filename)
    def block(start):
        end = min(expected_bytes, start + block_bytes) - 1
        for attempt in range(2):
            try:
                request = urllib.request.Request(url, headers={"Range": "bytes={}-{}".format(start, end)})
                with urllib.request.urlopen(request, timeout=30) as response:
                    if response.status != 206 or response.headers.get("Content-Range") != "bytes {}-{}/{}".format(start, end, expected_bytes):
                        raise ValueError("Range response identity mismatch")
                    data = response.read(end - start + 2)
                if len(data) != end - start + 1:
                    raise ValueError("Range response size mismatch")
                return start, data
            except (TimeoutError, OSError):
                if attempt:
                    raise
    with path.open("xb") as output, ThreadPoolExecutor(max_workers=4) as pool:
        # map is consumed in submission order; only 4 futures may hold finished payloads.
        starts = iter(range(0, expected_bytes, block_bytes))
        while True:
            batch = list(next(starts, None) for _ in range(4))
            batch = [start for start in batch if start is not None]
            if not batch:
                break
            for future in [pool.submit(block, start) for start in batch]:
                start, data = future.result()
                output.seek(start)
                output.write(data)
            del data, future
    return path


def download(repo, revision, filename, stage, expected_bytes):
    if RANGED and expected_bytes >= 16 * 1024**2:
        return range_download(repo, revision, filename, stage, expected_bytes)
    from huggingface_hub import hf_hub_download
    # local_dir downloads to the stage, not a second full HF model-cache copy.
    path = Path(hf_hub_download(repo, filename, revision=revision, token=False, local_dir=stage))
    if path.stat().st_size > expected_bytes:
        raise ValueError("Artifact exceeds declared size")
    return path

ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "model-manager" / "quality-catalog.json"


def safe_file(name):
    path = PurePosixPath(name)
    if not name or name.endswith("/") or str(path) != name or path.is_absolute() or ".." in path.parts or "\\" in name or ":" in name:
        raise ValueError("Unsafe artifact")
    return path


def digest(path, git=False):
    hasher = hashlib.sha1() if git else hashlib.sha256()
    if git:
        hasher.update(("blob " + str(path.stat().st_size) + "\0").encode())
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(block)
    return hasher.hexdigest()


def resolve(catalog):
    from huggingface_hub import HfApi
    result = []
    api = HfApi(token=False)
    for pack in catalog["packs"]:
        if not re.fullmatch(r"[a-z0-9-]+", pack["name"]) or not re.fullmatch(r"[a-f0-9]{40}", pack["revision"]):
            raise ValueError("Unpinned pack")
        info = api.model_info(pack["repo"], revision=pack["revision"], files_metadata=True)
        if info.sha != pack["revision"]:
            raise ValueError("Revision mismatch")
        entries = {entry.rfilename: entry for entry in info.siblings}
        records = []
        for filename in pack["files"]:
            safe_file(filename)
            entry = entries[filename]
            if not isinstance(entry.size, int) or entry.size < 0:
                raise ValueError("Missing artifact size")
            upstream_digest = entry.lfs.sha256 if entry.lfs else entry.blob_id
            if not isinstance(upstream_digest, str) or not re.fullmatch(r"[a-f0-9]{64}" if entry.lfs else r"[a-f0-9]{40}", upstream_digest):
                raise ValueError("Missing immutable artifact digest")
            records.append({"file": filename, "bytes": entry.size,
                            "upstreamDigest": upstream_digest,
                            "digestKind": "sha256" if entry.lfs else "git-sha1"})
        result.append(dict(pack, artifacts=records, bytes=sum(x["bytes"] for x in records)))
    if sum(pack["bytes"] for pack in result) > min(catalog["maximumNewBytes"], 2 * 1024**3):
        raise ValueError("Download budget exceeded")
    return result


def verify(directory, pack):
    if directory.is_symlink():
        raise ValueError("Model directory must not be a link")
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("repo") != pack["repo"] or manifest.get("revision") != pack["revision"]:
        raise ValueError("Installed identity mismatch")
    records = manifest.get("artifacts", [])
    if len(records) != len(pack["files"]) or {x["file"] for x in records} != set(pack["files"]):
        raise ValueError("Installed artifact set mismatch")
    expected = {x["file"]: x for x in pack["artifacts"]}
    for record in records:
        path = directory / str(safe_file(record["file"]))
        meta = expected[record["file"]]
        if path.is_symlink() or directory.resolve() not in path.resolve().parents:
            raise ValueError("Artifact escaped model directory")
        if path.stat().st_size != meta["bytes"] or record["bytes"] != meta["bytes"]:
            raise ValueError("Installed size mismatch")
        if digest(path) != record["sha256"] or digest(path, meta["digestKind"] == "git-sha1") != meta["upstreamDigest"]:
            raise ValueError("Installed digest mismatch")


def install(pack, root=ROOT, resume_stage=None):
    final = root / "models" / pack["name"]
    if final.exists():
        verify(final, pack)
        return "already-installed"
    stages = root / "work" / "model-staging"
    stages.mkdir(parents=True, exist_ok=True)
    # local_dir stages one copy. Reserve margin before any bytes.
    if shutil.disk_usage(stages).free < pack["bytes"] + 256 * 1024**2:
        raise ValueError("Insufficient reserved disk")
    if resume_stage:
        stage = Path(resume_stage)
        if stage.is_symlink() or stage.resolve().parent != stages.resolve() or not re.fullmatch(re.escape(pack["name"]) + r"-[a-f0-9]{32}", stage.name):
            raise ValueError("Resume stage identity invalid")
        if not stage.is_dir():
            raise ValueError("Resume stage missing")
    else:
        stage = stages / (pack["name"] + "-" + uuid.uuid4().hex)
        stage.mkdir()
    records = []
    try:
        for item in pack["artifacts"]:
            path = stage / str(safe_file(item["file"]))
            path.parent.mkdir(parents=True, exist_ok=True)
            reusable = path.is_file() and not path.is_symlink() and path.stat().st_size == item["bytes"] and digest(path, item["digestKind"] == "git-sha1") == item["upstreamDigest"]
            if not reusable:
                if path.exists():
                    raise ValueError("Invalid existing stage artifact; refuse overwrite")
                fetched = download(pack["repo"], pack["revision"], item["file"], stage, item["bytes"])
                if fetched.resolve() != path.resolve():
                    raise ValueError("Downloader escaped reviewed path")
            written = path.stat().st_size
            if written != item["bytes"] or digest(path, item["digestKind"] == "git-sha1") != item["upstreamDigest"]:
                raise ValueError("Downloaded artifact integrity failure")
            records.append({"file": item["file"], "bytes": written, "sha256": digest(path)})
            print(json.dumps({"pack": pack["name"], "verifiedBytes": written}), flush=True)
        manifest = {key: pack[key] for key in ("repo", "revision", "license", "bytes")}
        manifest["artifacts"] = records
        (stage / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
        verify(stage, pack)
        final.parent.mkdir(parents=True, exist_ok=True)
        if final.exists():
            raise ValueError("Concurrent install exists; refuse overwrite")
        stage.rename(final)
        return "verified-installed"
    except Exception:
        # Remove only this freshly created exact GUID stage; never a shared models directory.
        if not resume_stage and stage.resolve().parent == stages.resolve() and re.fullmatch(re.escape(pack["name"]) + r"-[a-f0-9]{32}", stage.name):
            shutil.rmtree(stage)
        raise


def main():
    global RANGED
    os.environ["HF_HUB_DISABLE_PROGRESS_BARS"] = "1"
    os.environ["HF_XET_CHUNK_CACHE_SIZE_BYTES"] = "0"
    parser = argparse.ArgumentParser()
    parser.add_argument("--install", action="store_true", help="explicit download consent")
    parser.add_argument("--pack", choices=("vieneu-turbo-61b85e3", "moss-codec-ceff0d0", "whisper-small-536b066"), help="install one explicitly selected pack")
    parser.add_argument("--ranged", action="store_true", help="bounded 4-request CDN workaround")
    parser.add_argument("--resume-stage", help="explicit exact GUID stage to revalidate and complete; requires --pack")
    args = parser.parse_args()
    RANGED = args.ranged
    if args.resume_stage and not args.pack:
        raise ValueError("Resume requires explicit pack")
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    voices = ROOT / catalog["voiceAssets"]["file"]
    if digest(voices) != catalog["voiceAssets"]["sha256"]:
        raise ValueError("Reviewed SDK voice asset changed")
    packs = resolve(catalog)
    if args.pack:
        packs = [pack for pack in packs if pack["name"] == args.pack]
    print(json.dumps({"totalBytes": sum(x["bytes"] for x in packs), "packs": [{"name": p["name"], "bytes": p["bytes"]} for p in packs]}), flush=True)
    if args.install:
        needed = sum(p["bytes"] for p in packs if not (ROOT / "models" / p["name"]).exists())
        if shutil.disk_usage(ROOT).free < needed + 256 * 1024**2:
            raise ValueError("Insufficient aggregate reserved disk")
        for pack in packs:
            print(json.dumps({"pack": pack["name"], "status": install(pack, resume_stage=args.resume_stage)}), flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(json.dumps({"status": "failed", "errorType": type(exc).__name__}), flush=True)
        raise SystemExit(1)
