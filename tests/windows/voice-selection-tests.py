"""Real preset selection and metadata bounds; no subtitle/audio content logs."""
import contextlib
import importlib.util
import json
from pathlib import Path
import time

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("worker", ROOT / "providers/translation/windows_worker.py")
worker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(worker)
provider = worker.LocalProvider(ROOT / "models/opus-en-vi-989c9fb", ROOT / "models/vieneu-nano-aba295e")
report = {"status": "PASS", "voices": []}
try:
    catalog = provider.execute("voice-catalog", "")
    assert provider.voice is None and provider.mt is None
    assert catalog["defaultVoice"] == "Minh Quân"
    assert len(catalog["voices"]) == 11
    assert {v["region"] for v in catalog["voices"]} == {"north", "south"}
    assert {v["gender"] for v in catalog["voices"]} == {"male", "female"}
    assert all(set(v) == {"name", "gender", "region", "description"} for v in catalog["voices"])
    assert len(json.dumps(catalog, ensure_ascii=False).encode("utf-8")) < 8192
    # Third-party diagnostics can contain phonemes: suppress all model output.
    with contextlib.redirect_stdout(worker.DiscardDiagnostics()), contextlib.redirect_stderr(worker.DiscardDiagnostics()):
        provider.execute("probe-voice", "")
        actual_infer = provider.voice.infer
        observed = []
        def tracked_infer(text, **kwargs):
            observed.append(kwargs["voice"])
            return actual_infer(text, **kwargs)
        provider.voice.infer = tracked_infer
        import numpy as np
        import soundfile as sf
        for name in ("Minh Quân", "Ái Hân"):
            started = time.perf_counter()
            result = provider.execute("synthesize", "Xin chào. Video trên Windows có tên tiếng Anh là Open World.", voice_name=name)
            audio, rate = sf.read(result["audioPath"])
            assert observed[-1] == name == result["voiceName"]
            assert rate == 24000 and 2400 < len(audio) <= 720000
            assert np.isfinite(audio).all() and np.sqrt(np.mean(audio * audio)) > 0.001
            assert result["tempo"] == 1
            report["voices"].append({"preset": name, "durationMs": result["audioDurationMs"], "generationMs": round((time.perf_counter()-started)*1000, 2)})
            provider.execute("release", result["audioPath"])
        for invalid in ("", "NOT_A_PRESET", 123, "x" * 81):
            before = len(observed)
            try:
                provider.execute("synthesize", "Xin chào.", voice_name=invalid)
                raise AssertionError("Invalid preset accepted")
            except ValueError:
                pass
            assert len(observed) == before and not provider.audio
    report["catalogCount"] = len(catalog["voices"])
    report["invalidSelectionsRejected"] = 4
    report["qualityScope"] = "Waveform and preset routing only; no universal pronunciation or human listening grade"
finally:
    provider.close()
(ROOT / "work/voice-selection-evidence.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(report, ensure_ascii=False))
