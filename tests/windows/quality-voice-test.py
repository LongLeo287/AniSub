"""Real regional/mixed-text Turbo output, aggregate only; not an accent grade."""
import contextlib
import io
import json
from pathlib import Path
import sys
import time

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'providers/translation'))
from windows_worker import LocalProvider
import numpy as np
import soundfile as sf

provider=LocalProvider(ROOT/'models/opus-en-vi-989c9fb',ROOT/'models/vieneu-turbo-61b85e3')
try:
    catalog=provider.voice_catalog()
    assert len(catalog['voices'])==25 and catalog['voiceProfile']=='Turbo'
    for region in ('north','central','south'):
        assert {v['gender'] for v in catalog['voices'] if v['region']==region}=={'male','female'}
    rows=[]
    with contextlib.redirect_stdout(io.StringIO()),contextlib.redirect_stderr(io.StringIO()):
        for voice in ('Quang Sơn','Ngọc Trân'):
            start=time.perf_counter()
            result=provider.execute('synthesize','Xin chào. Video trên Windows có tên tiếng Anh là Open World.',voice_name=voice)
            audio,rate=sf.read(result['audioPath'])
            assert rate==48000 and np.isfinite(audio).all() and np.sqrt(np.mean(audio*audio))>.001
            assert result['voiceName']==voice
            rows.append({'voice':voice,'seconds':round(len(audio)/rate,3),'ms':round((time.perf_counter()-start)*1000)})
            provider.execute('release',result['audioPath'])
    report={'status':'PASS','catalogCount':25,'regionalGenderCoverage':True,'voices':rows,'qualityScope':'Routing/finite waveform only, accents and pronunciation need listening'}
    (ROOT/'work/quality-voice-evidence.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=True))
finally:
    provider.close()
