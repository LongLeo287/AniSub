"""Explicit opt-in pinned model installation; no inference or remote model code."""
import hashlib
import json
import os
from pathlib import Path
import sys
from huggingface_hub import HfApi, hf_hub_download

ROOT = Path(__file__).resolve().parents[2]
PACKS = [
    ("Helsinki-NLP/opus-mt-en-vi", "989c9fb9ec63987901022baf0182dcec3e149be6",
     "opus-en-vi-989c9fb", ["README.md", "config.json", "generation_config.json",
     "pytorch_model.bin", "source.spm", "target.spm", "tokenizer_config.json", "vocab.json"]),
    ("pnnbao-ump/VieNeu-TTS-v3-Nano", "aba295eb96a6fa6003ebe417cc1f2802a7adc1dc",
     "vieneu-nano-aba295e", ["README.md", "config.json", "constants.npz", "text_encoder.onnx",
     "duration_predictor.onnx", "vector_estimator.onnx", "codec_decoder.onnx"]),
]

def install():
    os.environ["HF_HUB_DISABLE_PROGRESS_BARS"] = "1"
    api = HfApi(token=False)
    for repo, revision, name, files in PACKS:
        final = ROOT / "models" / name
        if (final / "manifest.json").is_file():
            manifest = json.loads((final / "manifest.json").read_text(encoding="utf-8"))
            listed = [item["file"] for item in manifest["artifacts"]]
            if manifest["repo"] != repo or manifest["revision"] != revision or len(listed) != len(files) or set(listed) != set(files):
                raise RuntimeError("Existing manifest invalid; explicit repair required")
            for item in manifest["artifacts"]:
                path = final / item["file"]
                if path.resolve().parent != final.resolve() or path.stat().st_size != item["bytes"]:
                    raise RuntimeError("Existing artifact invalid; explicit repair required")
                digest = hashlib.sha256()
                with path.open("rb") as source:
                    for block in iter(lambda: source.read(1024 * 1024), b""):
                        digest.update(block)
                if digest.hexdigest() != item["sha256"]:
                    raise RuntimeError("Existing artifact corrupt; explicit repair required")
            print(json.dumps({"pack": name, "status": "already-installed"}), flush=True)
            continue
        stage = ROOT / "work" / "model-staging" / name
        stage.mkdir(parents=True, exist_ok=True)
        metadata = api.model_info(repo, revision=revision, files_metadata=True)
        expected = {entry.rfilename: entry for entry in metadata.siblings}
        total = sum(expected[name].size for name in files)
        if total > 700 * 1024 * 1024:
            raise RuntimeError("Model pack exceeds approved prototype budget")
        records = []
        for filename in files:
            item = expected[filename]
            path = Path(hf_hub_download(repo, filename, revision=revision, token=False,
                                      local_dir=stage))
            digest = hashlib.sha256()
            git_digest = hashlib.sha1()
            git_digest.update(("blob " + str(path.stat().st_size) + "\0").encode())
            with path.open("rb") as source:
                for chunk in iter(lambda: source.read(1024 * 1024), b""):
                    digest.update(chunk); git_digest.update(chunk)
            if path.stat().st_size != item.size:
                raise RuntimeError("Artifact size mismatch")
            if item.lfs:
                if digest.hexdigest() != item.lfs.sha256:
                    raise RuntimeError("Artifact digest mismatch")
            elif git_digest.hexdigest() != item.blob_id:
                raise RuntimeError("Git blob mismatch")
            records.append({"file": filename, "bytes": item.size, "sha256": digest.hexdigest()})
            print(json.dumps({"pack": name, "file": filename, "verifiedBytes": item.size}), flush=True)
        manifest = {"repo": repo, "revision": revision, "license": "Apache-2.0",
                    "artifacts": records, "bytes": total, "scope": "Windows prototype"}
        (stage / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
        final.parent.mkdir(parents=True, exist_ok=True)
        if final.exists():
            raise RuntimeError("Refuse to overwrite an existing model directory")
        stage.rename(final)
        print(json.dumps({"pack": name, "status": "verified-installed", "bytes": total}), flush=True)

if __name__ == "__main__":
    try:
        install()
    except Exception as exc:
        print(json.dumps({"status": "failed", "errorType": type(exc).__name__}), flush=True)
        sys.exit(1)
