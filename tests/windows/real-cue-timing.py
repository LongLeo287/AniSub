"""Authorized real subtitle timings; persist aggregate counts only, no source text/audio."""
import importlib.util
import argparse
import json
from pathlib import Path
import re
import statistics
import time

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("worker", ROOT / "providers/translation/windows_worker.py")
worker = importlib.util.module_from_spec(spec); spec.loader.exec_module(worker)
provider = worker.LocalProvider(ROOT / "models/opus-en-vi-989c9fb", ROOT / "models/vieneu-nano-aba295e")

def milliseconds(value):
    hours, minutes, seconds = value.replace(",", ".").split(":")
    return (int(hours) * 3600 + int(minutes) * 60 + float(seconds)) * 1000

text = (ROOT / "work/slime-vietnamese-track4.srt").read_text(encoding="utf-8-sig")
cues = []
for block in re.split(r"\n\s*\n", text.replace("\r", "")):
    match = re.search(r"(\d\d:\d\d:\d\d,\d{3}) --> (\d\d:\d\d:\d\d,\d{3})\n(.+)", block, re.S)
    if match:
        cues.append((milliseconds(match[1]), milliseconds(match[2]), re.sub(r"<[^>]+>", "", match[3]).strip()))
parser = argparse.ArgumentParser(); parser.add_argument("--start-ms", type=float, default=0); parser.add_argument("--count", type=int, default=24)
args = parser.parse_args()
selection = [(index, cue) for index, cue in enumerate(cues) if cue[0] >= args.start_ms][:min(40, max(1,args.count))]
report = {"inputCues": len(cues), "samples": len(selection), "startMs": args.start_ms, "settings": "Nano16steps; atempo<=1.35; real VN text, no log/persistence"}
generation = []; overruns = []; failed = 0; failures = []
try:
    provider.execute("probe-voice", "")
    for index, (start, end, cue) in selection:
        next_start = cues[index + 1][0] if index + 1 < len(cues) else end
        budget = max(100, min(end, next_start) - start)
        begun = time.perf_counter()
        try:
            reply = provider.execute("synthesize", cue, min(30000, budget))
            generation.append((time.perf_counter() - begun) * 1000)
            overruns.append(max(0, reply["audioDurationMs"] - budget))
            provider.execute("release", reply["audioPath"])
        except Exception as error:
            failed += 1
            failures.append({"cueIndex": index, "errorType": type(error).__name__, "characters": len(cue), "startMs": start, "endMs": end})
finally:
    provider.close()
report.update(failed=failed, failures=failures, generationMedianMs=round(statistics.median(generation), 2) if generation else None,
              generationMaxMs=round(max(generation), 2) if generation else None, longerThanSlot=sum(x > 50 for x in overruns),
              overrunMaxMs=round(max(overruns), 2) if overruns else None, overrunTotalMs=round(sum(overruns), 2))
(ROOT / ("work/real-cue-timing-" + str(int(args.start_ms)) + ".json")).write_text(json.dumps(report, indent=2), encoding="utf-8")
print(json.dumps(report), flush=True)
assert failed == 0
