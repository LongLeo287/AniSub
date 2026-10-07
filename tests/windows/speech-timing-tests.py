"""Real local waveform/lease tests; synthetic text only, no content logs."""
import importlib.util
import json
from pathlib import Path
import time

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("worker", ROOT / "providers/translation/windows_worker.py")
worker = importlib.util.module_from_spec(spec); spec.loader.exec_module(worker)
provider = worker.LocalProvider(ROOT / "models/opus-en-vi-989c9fb", ROOT / "models/vieneu-nano-aba295e")
report = {}
try:
    started = time.perf_counter()
    provider.execute("probe-voice", "")
    assert provider.mt is None, "Voice-only prep unexpectedly loaded MT"
    report["voiceLoadMs"] = round((time.perf_counter() - started) * 1000, 2)
    started = time.perf_counter()
    speech = provider.execute("synthesize", "Xin chào. Chúng ta đang kiểm tra giọng đọc tiếng Việt.", 1200)
    report["generationMs"] = round((time.perf_counter() - started) * 1000, 2)
    import numpy as np
    import soundfile as sf
    audio, rate = sf.read(speech["audioPath"])
    assert rate == 24000 and np.isfinite(audio).all() and len(audio) > 2400
    assert 1 <= speech["tempo"] <= 1.35 and speech["steps"] == 16
    assert abs(speech["audioDurationMs"] - len(audio) / 24) < 0.1
    assert abs(audio[0]) < 1e-5 and abs(audio[-1]) < 1e-5
    # Short requested slot must not truncate a complete long utterance to fit.
    assert speech["audioDurationMs"] > 1200
    report["timing"] = {key: speech[key] for key in ["audioDurationMs", "naturalDurationMs", "tempo", "steps"]}
    leased = Path(speech["audioPath"])
    provider.audio.extend([provider.output / (str(index) + ".wav") for index in range(15)])
    try:
        provider.execute("synthesize", "Xin chào.")
        raise AssertionError("Lease cap not enforced")
    except BufferError:
        pass
    assert leased.exists(), "Active lease got silently evicted"
    provider.execute("release", str(leased))
    assert not leased.exists()
    try:
        provider.execute("release", str(ROOT / "tests/fixtures/windows-demo.srt"))
        raise AssertionError("Foreign file release accepted")
    except ValueError:
        pass
    report["status"] = "PASS voice-only prepare,16 steps,bounded full-wave tempo,edge fade,leases and safe release"
finally:
    provider.close()
(ROOT / "work/speech-timing-evidence.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
print(json.dumps(report), flush=True)
