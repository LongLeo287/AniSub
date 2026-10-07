"""Non-AI deterministic WAV/delay/failure fixture for coordinator state tests."""
import argparse
import json
import math
from pathlib import Path
import struct
import sys
import time
import uuid
import wave
parser = argparse.ArgumentParser()
parser.add_argument("--session", required=True); parser.add_argument("--model"); parser.add_argument("--tts")
args = parser.parse_args()
folder = Path(__file__).resolve().parents[2] / "work/speech-session" / args.session
folder.mkdir(parents=True)
for line in sys.stdin:
    request = json.loads(line); result = dict(id=request["id"], ok=True, latencyMs=0)
    operation, text = request["operation"], request["text"]
    if operation == "synthesize":
        if text == "SLOW": time.sleep(0.5)
        if text == "FAIL": result.update(ok=False, error="FIXTURE_FAIL")
        else:
            path = folder / (uuid.uuid4().hex + ".wav")
            with wave.open(str(path), "wb") as output:
                output.setparams((1, 2, 24000, 0, "NONE", "not compressed"))
                output.writeframes(b"".join(struct.pack("<h", int(300 * math.sin(index * 2 * math.pi * 440 / 24000))) for index in range(14400)))
            result.update(audioPath=str(path), audioDurationMs=600)
    elif operation == "release":
        path = Path(text)
        assert path.parent == folder
        path.unlink(missing_ok=True)
    elif operation == "translate": result["text"] = text
    print(json.dumps(result), flush=True)
