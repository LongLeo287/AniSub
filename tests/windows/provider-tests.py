"""Explicit synthetic-input integration test; real pinned models, no user's media."""
import importlib.util
import json
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("windows_worker", ROOT / "providers/translation/windows_worker.py")
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)
provider = worker.LocalProvider(ROOT / "models/opus-en-vi-989c9fb", ROOT / "models/vieneu-nano-aba295e")
report = {}
started = time.perf_counter()
provider.execute("probe", "")
report["loadMs"] = round((time.perf_counter() - started) * 1000, 2)
samples = ["Hello, welcome to AniSub.", "We are watching a video together.", "Please pause the video."]
results = []
for sample in samples:
    started = time.perf_counter()
    result = provider.execute("translate", sample)
    elapsed = (time.perf_counter() - started) * 1000
    assert result["text"] and result["text"] != sample
    results.append({"syntheticInput": sample, "translation": result["text"], "latencyMs": round(elapsed, 2)})
report["translations"] = results
started = time.perf_counter()
speech = provider.execute("synthesize", results[0]["translation"])
speech_ms = (time.perf_counter() - started) * 1000
import numpy as np
import soundfile as sf
audio, rate = sf.read(speech["audioPath"])
assert rate == 24000 and len(audio) > 2400 and np.isfinite(audio).all()
rms = float(np.sqrt(np.mean(audio ** 2)))
assert rms > 0.001
duration = len(audio) / rate
report["speech"] = {"audioPath": speech["audioPath"], "generationMs": round(speech_ms, 2),
                    "durationSeconds": round(duration, 3), "rtf": round(speech_ms / 1000 / duration, 3), "rms": rms}
for operation, text in [("translate", "word " * 300), ("synthesize", "a" * 513)]:
    try:
        provider.execute(operation, text)
        raise AssertionError("Missing length guard")
    except ValueError:
        pass
report["status"] = "PASS real translation, speech waveform, text bounds"
destination = ROOT / "work/windows-provider-evidence.json"
destination.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(report, ensure_ascii=True), flush=True)
