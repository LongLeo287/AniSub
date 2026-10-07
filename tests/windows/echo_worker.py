"""Wire-only UTF-8 fixture. Not an AI provider."""
import json
import sys
for line in sys.stdin:
    request = json.loads(line)
    print(json.dumps({"id": request["id"], "ok": True, "text": request["text"], "latencyMs": 0}, ensure_ascii=False), flush=True)
