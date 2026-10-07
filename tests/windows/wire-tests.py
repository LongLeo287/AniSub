import json
from pathlib import Path
import os
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
environment = dict(os.environ, PYTHONIOENCODING="utf-8", HF_HUB_OFFLINE="1", TRANSFORMERS_OFFLINE="1")
child = subprocess.Popen([sys.executable, "-u", str(ROOT / "providers/translation/windows_worker.py"),
                          "--model", str(ROOT / "models/opus-en-vi-989c9fb"),
                          "--tts", str(ROOT / "models/vieneu-nano-aba295e")],
                         stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                         encoding="utf-8", env=environment)
try:
    for request_id, operation, text in [(1, "probe", ""), (2, "translate", "Hello. This is a test of offline video translation."), (3, "synthesize", "Xin chào bạn.")]:
        child.stdin.write(json.dumps(dict(id=request_id, operation=operation, text=text)) + "\n")
        child.stdin.flush()
        line = child.stdout.readline()
        print(repr(line.encode("unicode_escape")), flush=True)
        reply = json.loads(line)
        assert reply["id"] == request_id and reply["ok"], reply
        if operation == "synthesize":
            assert Path(reply["audioPath"]).is_file()
finally:
    child.stdin.close()
    child.wait(timeout=5)
print("PASS persistent UTF8 worker correlation and graceful exit")
