"""Deterministic external-host WAV fixture; no AI or user media."""
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
parser.add_argument("--session", required=True)
parser.add_argument("--model")
parser.add_argument("--tts")
args = parser.parse_args()
folder = Path(__file__).resolve().parents[2] / "work/speech-session" / args.session
folder.mkdir(parents=True)
for line in sys.stdin:
    request = json.loads(line)
    result = dict(id=request["id"], ok=True, latencyMs=0)
    operation, text = request["operation"], request["text"]
    if operation == "voice-catalog":
        result.update(defaultVoice="Minh Quân", voices=[dict(name="Minh Quân", gender="male", region="north", description="Fixture")])
    elif operation == "synthesize":
        if text == "SLOW":
            time.sleep(0.5)
        if text == "VERY_SLOW":
            time.sleep(5.3)
        if text == "FAIL":
            result.update(ok=False, error="FIXTURE_FAIL")
        else:
            path = folder / (uuid.uuid4().hex + ".wav")
            with wave.open(str(path), "wb") as output:
                output.setparams((1, 2, 24000, 0, "NONE", "not compressed"))
                frames = 48000 if text == "LONG_AUDIO" else 14400
                output.writeframes(b"".join(struct.pack("<h", int(300 * math.sin(index * 2 * math.pi * 440 / 24000))) for index in range(frames)))
            result.update(audioPath=str(path), audioDurationMs=frames / 24)
    elif operation == "release":
        path = Path(text)
        assert path.parent == folder
        path.unlink(missing_ok=True)
    elif operation == "translate":
        if text == "POLICY_DELAY":
            time.sleep(0.5)
        policy = request.get("contentPolicy") or {"profile": "general", "terms": []}
        result["text"] = policy["profile"] + ":" + (policy["terms"][0]["target"] if policy["terms"] else text)
    elif operation == "validate-policy":
        if (request.get("contentPolicy") or {}).get("profile") == "technology":
            time.sleep(0.3)
        sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "providers/translation"))
        from content_policy import validate_policy
        try:
            validate_policy(request.get("contentPolicy"))
        except ValueError:
            result.update(ok=False, error="ValueError")
    print(json.dumps(result), flush=True)
