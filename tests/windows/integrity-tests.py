import importlib.util
import json
from pathlib import Path
import tempfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("worker", ROOT / "providers/translation/windows_worker.py")
worker = importlib.util.module_from_spec(spec); spec.loader.exec_module(worker)
revision = "989c9fb9ec63987901022baf0182dcec3e149be6"
with tempfile.TemporaryDirectory(prefix="anisub-integrity-") as directory:
    folder = Path(directory)
    (folder / "manifest.json").write_text(json.dumps({"repo": "Helsinki-NLP/opus-mt-en-vi", "revision": revision, "artifacts": []}), encoding="utf-8")
    try:
        worker.verify_pack(folder, revision)
        raise AssertionError("Empty manifest accepted")
    except ValueError:
        pass
worker.verify_pack(ROOT / "models/opus-en-vi-989c9fb", revision)
worker.verify_pack(ROOT / "models/vieneu-nano-aba295e", "aba295eb96a6fa6003ebe417cc1f2802a7adc1dc")
print("PASS omitted manifest rejected and both complete installed packs verified")
