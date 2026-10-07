"""Real broker framing/pipe/AI output; no Chrome registration or user tab involved."""
import io
from pathlib import Path
import sys
import time
import json
import ctypes

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'apps/desktop/native-messaging'))
from native_host import read_frame,write_frame,validate_message,pipe_request,validate_origin

origin='chrome-extension://'+'a'*32+'/'
assert validate_origin(origin,[origin])
sequence=0
def request(kind,**fields):
    global sequence
    sequence+=1
    message=dict(type=kind,session='real-broker-fixture',revision=1,sequence=sequence,**fields)
    wire=io.BytesIO();write_frame(wire,message);wire.seek(0)
    return pipe_request(validate_message(read_frame(wire)))

response=request('snapshot',positionMs=0,playing=True,speed=1,language='vi',cues=[dict(startMs=0,endMs=30000,text='Xin chào. Đây là giọng thuyết minh tiếng Việt.')])
assert response['ok'],str(response.get('error'))+' win32='+str(ctypes.get_last_error())
deadline=time.monotonic()+30
heard=False
try:
    while time.monotonic()<deadline:
        time.sleep(.2);response=request('status')
        assert response['ok'],response.get('error')
        if response['speaking']:
            heard=True
        elif heard:
            break
    assert heard,'No actual output-start observed'
    assert not response['speaking'],'Accepted utterance did not finish'
    assert request('close')['ok']
    print(json.dumps({'status':'PASS','scope':'Native framing + Windows named pipe + real Turbo speech playback started/finished; Chrome not installed','frames':sequence}))
finally:
    request('close')
