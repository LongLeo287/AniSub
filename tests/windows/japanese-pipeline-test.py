"""Approved local movie slice, transient PCM/text, aggregate evidence only."""
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'providers/capture'))
sys.path.insert(0, str(ROOT / 'providers/translation'))
from windows_capture_worker import verify_asr_model
from windows_worker import LocalProvider
import numpy as np
import soundfile as sf
from faster_whisper import WhisperModel

os.environ['HF_HUB_OFFLINE'] = '1'
asr_dir = ROOT / 'models/whisper-small-536b066'
verify_asr_model(asr_dir)
config = json.loads((ROOT / 'work/narration-session.json').read_text(encoding='utf-8'))
audio_bytes = subprocess.run(['ffmpeg', '-v', 'error', '-ss', '260', '-i', config['video'], '-t', '8', '-vn', '-ac', '1', '-ar', '16000', '-f', 'f32le', 'pipe:1'], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=True, timeout=30).stdout
samples = np.frombuffer(audio_bytes, dtype='<f4')
tts = ROOT / 'models/vieneu-turbo-61b85e3'
if not (tts / 'manifest.json').is_file():
    tts = ROOT / 'models/vieneu-nano-aba295e'
provider = LocalProvider(ROOT / 'models/opus-en-vi-989c9fb', tts)
started = time.perf_counter()
try:
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        asr = WhisperModel(str(asr_dir), device='cpu', compute_type='int8', cpu_threads=2, local_files_only=True)
        segments, info = asr.transcribe(samples, language='ja', task='translate', beam_size=1, vad_filter=True, condition_on_previous_text=False)
        english = ' '.join(segment.text for segment in segments).strip()
        assert english and info.language == 'ja'
        vietnamese = provider.execute('translate', english)['text']
        assert vietnamese.strip()
        voice = provider.voice_catalog()['defaultVoice']
        wav = provider.execute('synthesize', vietnamese, voice_name=voice)
        output, rate = sf.read(wav['audioPath'])
        assert np.isfinite(output).all() and len(output) > rate / 10
        provider.execute('release', wav['audioPath'])
    report = {'status': 'PASS', 'sourceLanguage': 'ja', 'intermediateLanguage': 'en', 'outputLanguage': 'vi', 'inputSeconds': round(len(samples)/16000, 3), 'ttsProfile': 'Turbo' if provider.turbo else 'Nano', 'sampleRate': rate, 'outputSeconds': round(len(output)/rate, 3), 'totalMs': round((time.perf_counter()-started)*1000), 'qualityScope': 'Nonempty recognition/translation and finite waveform, not human semantic or listening grade'}
    (ROOT / 'work/japanese-pipeline-evidence.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report))
finally:
    provider.close()
