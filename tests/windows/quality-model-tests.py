"""Offline integrity/rollback tests; no inference or network."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("quality", ROOT / "model-manager/tools/install_quality_models.py")
quality = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(quality)


class QualityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.data = b"reviewed synthetic weights"
        self.pack = {"name": "test-pack", "repo": "fixture/model", "revision": "a" * 40,
                     "license": "MIT", "files": ["nested/weights.bin"], "bytes": len(self.data),
                     "artifacts": [{"file": "nested/weights.bin", "bytes": len(self.data),
                                    "digestKind": "sha256", "upstreamDigest": hashlib.sha256(self.data).hexdigest()}]}
    def tearDown(self):
        self.temp.cleanup()

    def install(self, data=None):
        def fake_download(repo, revision, filename, stage, expected_bytes):
            path = stage / filename
            path.write_bytes(self.data if data is None else data)
            return path
        with patch.object(quality, "download", side_effect=fake_download):
            return quality.install(self.pack, self.root)

    def test_atomic_verified_install(self):
        self.assertEqual(self.install(), "verified-installed")
        quality.verify(self.root / "models/test-pack", self.pack)
        self.assertEqual(list((self.root / "work/model-staging").iterdir()), [])

    def test_no_download_when_valid_installed(self):
        self.install()
        with patch.object(quality, "download", side_effect=AssertionError("network forbidden")):
            self.assertEqual(quality.install(self.pack, self.root), "already-installed")

    def test_corrupt_installed_not_overwritten(self):
        self.install()
        path = self.root / "models/test-pack/nested/weights.bin"
        path.write_bytes(b"bad")
        with self.assertRaises(ValueError):
            self.install()
        self.assertEqual(path.read_bytes(), b"bad")

    def test_oversize_download_rolls_back(self):
        with self.assertRaises(ValueError):
            self.install(self.data + b"x")
        self.assertFalse((self.root / "models/test-pack").exists())
        self.assertEqual(list((self.root / "work/model-staging").iterdir()), [])

    def test_short_download_rolls_back(self):
        with self.assertRaises(ValueError):
            self.install(self.data[:-1])
        self.assertFalse((self.root / "models/test-pack").exists())

    def test_wrong_digest_rolls_back(self):
        with self.assertRaises(ValueError):
            self.install(b"x" * len(self.data))

    def test_unsafe_names(self):
        for name in ("", "../x", "/x", "x\\y", "C:/x", "./x", "x/", "x//y"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                quality.safe_file(name)

    def test_git_blob_integrity(self):
        self.pack["artifacts"][0]["digestKind"] = "git-sha1"
        self.pack["artifacts"][0]["upstreamDigest"] = hashlib.sha1(b"blob " + str(len(self.data)).encode() + b"\0" + self.data).hexdigest()
        self.install()
        quality.verify(self.root / "models/test-pack", self.pack)

    def test_disk_reservation(self):
        with patch.object(quality.shutil, "disk_usage", return_value=type("Space", (), {"free": 1})()), self.assertRaises(ValueError):
            self.install()

    def test_manifest_forgery_cannot_replace_upstream_digest(self):
        self.install()
        final = self.root / "models/test-pack"
        data = b"x" * len(self.data)
        (final / "nested/weights.bin").write_bytes(data)
        manifest = json.loads((final / "manifest.json").read_text())
        manifest["artifacts"][0]["sha256"] = hashlib.sha256(data).hexdigest()
        (final / "manifest.json").write_text(json.dumps(manifest))
        with self.assertRaises(ValueError):
            quality.verify(final, self.pack)

    def test_existing_unknown_directory_preserved(self):
        final = self.root / "models/test-pack"
        final.mkdir(parents=True)
        (final / "user.txt").write_text("keep")
        with self.assertRaises(FileNotFoundError):
            self.install()
        self.assertTrue((final / "user.txt").is_file())

    def test_only_declared_artifacts_accepted(self):
        self.install()
        final = self.root / "models/test-pack"
        manifest = json.loads((final / "manifest.json").read_text())
        manifest["artifacts"].append(manifest["artifacts"][0])
        (final / "manifest.json").write_text(json.dumps(manifest))
        with self.assertRaises(ValueError):
            quality.verify(final, self.pack)

    def test_model_link_rejected(self):
        self.install()
        with patch.object(Path, "is_symlink", return_value=True), self.assertRaises(ValueError):
            quality.verify(self.root / "models/test-pack", self.pack)

    def test_range_transfer_identity_and_length(self):
        class Reply(io.BytesIO):
            status = 206
            headers = {"Content-Range": "bytes 0-25/26"}
        (self.root / "nested").mkdir()
        with patch.object(quality.urllib.request, "urlopen", return_value=Reply(self.data)):
            path = quality.range_download("fixture/model", "a" * 40, "nested/weights.bin", self.root, 26)
        self.assertEqual(path.read_bytes(), self.data)

    def test_range_response_cannot_ignore_requested_bounds(self):
        class Reply(io.BytesIO):
            status = 200
            headers = {}
        (self.root / "nested").mkdir()
        with patch.object(quality.urllib.request, "urlopen", return_value=Reply(self.data)), self.assertRaises(ValueError):
            quality.range_download("fixture/model", "a" * 40, "nested/weights.bin", self.root, 26)

    def test_explicit_resume_revalidates_and_reuses(self):
        stage = self.root / "work/model-staging" / ("test-pack-" + "b" * 32)
        (stage / "nested").mkdir(parents=True)
        (stage / "nested/weights.bin").write_bytes(self.data)
        with patch.object(quality, "download", side_effect=AssertionError("must reuse")):
            self.assertEqual(quality.install(self.pack, self.root, stage), "verified-installed")

    def test_corrupt_resume_preserved_for_explicit_repair(self):
        stage = self.root / "work/model-staging" / ("test-pack-" + "b" * 32)
        (stage / "nested").mkdir(parents=True)
        path = stage / "nested/weights.bin"
        path.write_bytes(b"bad")
        with self.assertRaises(ValueError):
            quality.install(self.pack, self.root, stage)
        self.assertEqual(path.read_bytes(), b"bad")

    def test_resume_outside_staging_rejected(self):
        with self.assertRaises(ValueError):
            quality.install(self.pack, self.root, self.root)


if __name__ == "__main__":
    unittest.main()
