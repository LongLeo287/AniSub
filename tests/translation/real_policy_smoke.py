"""Own synthetic sentence only; installed offline model, no capture/download/TTS."""
import json
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'providers/translation'))
from windows_worker import LocalProvider
root = Path(__file__).resolve().parents[2]
provider = LocalProvider(root / 'models/opus-en-vi-989c9fb', root / 'models/vieneu-nano-aba295e')
try:
    source = 'The graphics card does not support ray tracing.'
    baseline = provider.execute('translate', source)
    assert baseline['text'] and baseline['contextSupported'] is False
    policy = {'profile': 'technology', 'terms': [{'source': 'graphics card', 'target': 'GPU', 'aliases': ['thẻ đồ họa', 'card đồ họa', 'cạc đồ họa']}]}
    corrected = provider.execute('translate', source, content_policy=policy)
    again = provider.execute('translate', source)
    assert again == baseline and len(provider.cache) == 2
    assert corrected['glossaryApplied'] + corrected['glossaryUnresolved'] == 1
    print(json.dumps({'baseline': baseline, 'withPolicy': corrected, 'cacheIsolated': True}, ensure_ascii=True))
finally:
    provider.close()
