"""Selected-window OCR and selected-process ASR. No global desktop/audio fallback."""
import argparse
import base64
import ctypes as C
from ctypes import wintypes as W
import hashlib
import io
import json
import os
import queue
from pathlib import Path
import subprocess
import sys
import threading
import time
import unicodedata
from live_asr import RollingRecognition, LocalDecoder

MAX_FRAME = 65536

class CaptureError(Exception):
    pass

def error(code):
    raise CaptureError(code)

def validate_roi(value):
    if not isinstance(value, list) or len(value) != 4:
        error('INVALID_ROI')
    if any(isinstance(x, bool) or not isinstance(x, (int, float)) for x in value):
        error('INVALID_ROI')
    x, y, width, height = value
    if not (0 <= x < 1 and 0 <= y < 1 and 0 < width <= 1 and 0 < height <= 1 and x + width <= 1 and y + height <= 1):
        error('INVALID_ROI')
    return value

def normalize(text):
    normalized = ' '.join(unicodedata.normalize('NFC', text).split())
    if len(normalized) > 512:
        error('TEXT_TOO_LONG')
    return normalized

def verify_asr_model(directory):
    """Reject incomplete/tampered local packs before executing native inference."""
    try:
        root = Path(__file__).resolve().parents[2]
        catalog = json.loads((root / 'model-manager/quality-catalog.json').read_text(encoding='utf-8'))
        manifest = json.loads((directory / 'manifest.json').read_text(encoding='utf-8'))
        pack = next(p for p in catalog['packs'] if p['name'] == directory.name and 'whisper' in p['name'])
        if manifest['repo'] != pack['repo'] or manifest['revision'] != pack['revision']:
            error('MODEL_CORRUPT')
        records = manifest['artifacts']
        if len(records) != len(pack['files']) or {r['file'] for r in records} != set(pack['files']):
            error('MODEL_CORRUPT')
        for record in records:
            file = directory / record['file']
            if file.is_symlink() or directory.resolve() not in file.resolve().parents or file.stat().st_size != record['bytes']:
                error('MODEL_CORRUPT')
            digest = hashlib.sha256()
            with file.open('rb') as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b''):
                    digest.update(block)
            if digest.hexdigest() != record['sha256']:
                error('MODEL_CORRUPT')
    except CaptureError:
        raise
    except Exception:
        error('MODEL_CORRUPT')

class Gate:
    """Exact dedup only: changed negation, numbers and names remain eligible."""
    def __init__(self):
        self.last_text = ''
        self.last_signature = None
        self.last_time = 0
    def changed(self, image):
        signature = hashlib.sha256(image.resize((128, 32)).convert('L').tobytes()).digest()
        now = time.monotonic()
        changed = signature != self.last_signature or now - self.last_time >= 2
        self.last_signature = signature
        if changed:
            self.last_time = now
        return changed
    def accept(self, text):
        text = normalize(text)
        if not text:
            self.last_text = ''
            return ''
        if text == self.last_text:
            return ''
        self.last_text = text
        return text

def identity(request):
    pid, hwnd, ticks = request.get('pid'), request.get('hwnd'), request.get('startTicks')
    if any(isinstance(v, bool) or not isinstance(v, int) or v <= 0 for v in (pid, hwnd, ticks)):
        error('INVALID_SOURCE')
    user = C.WinDLL('user32', use_last_error=True)
    kernel = C.WinDLL('kernel32', use_last_error=True)
    user.GetWindowThreadProcessId.argtypes = [W.HWND, C.POINTER(W.DWORD)]
    owner = W.DWORD()
    user.GetWindowThreadProcessId(W.HWND(hwnd), C.byref(owner))
    if owner.value != pid or not user.IsWindow(W.HWND(hwnd)):
        error('SOURCE_CHANGED')
    kernel.OpenProcess.argtypes = [W.DWORD, W.BOOL, W.DWORD]
    kernel.OpenProcess.restype = W.HANDLE
    handle = kernel.OpenProcess(0x1000, False, pid)
    if not handle:
        error('INPUT_DENIED')
    values = [W.FILETIME() for _ in range(4)]
    kernel.GetProcessTimes.argtypes = [W.HANDLE] + [C.POINTER(W.FILETIME)] * 4
    kernel.CloseHandle.argtypes = [W.HANDLE]
    try:
        if not kernel.GetProcessTimes(handle, *(C.byref(v) for v in values)):
            error('INPUT_DENIED')
        created = (values[0].dwHighDateTime << 32) | values[0].dwLowDateTime
        if created + 504911232000000000 != ticks:
            error('SOURCE_CHANGED')
    finally:
        kernel.CloseHandle(handle)
    if user.IsIconic(W.HWND(hwnd)):
        error('SOURCE_MINIMIZED')
    return pid, hwnd

def capture_window(hwnd, roi):
    """PrintWindow into isolated DIB; never GetDC(NULL)/screen BitBlt."""
    from PIL import Image
    user, gdi = C.WinDLL('user32'), C.WinDLL('gdi32')
    user.GetWindowRect.argtypes = [W.HWND, C.POINTER(W.RECT)]
    rect = W.RECT()
    if not user.GetWindowRect(W.HWND(hwnd), C.byref(rect)):
        error('INPUT_DENIED')
    width, height = rect.right - rect.left, rect.bottom - rect.top
    if width < 16 or height < 16 or width * height > 16777216:
        error('CAPTURE_SIZE_UNSUPPORTED')
    class Header(C.Structure):
        _fields_ = [('size', W.DWORD), ('width', W.LONG), ('height', W.LONG), ('planes', W.WORD), ('bits', W.WORD), ('compression', W.DWORD), ('image', W.DWORD), ('x', W.LONG), ('y', W.LONG), ('used', W.DWORD), ('important', W.DWORD)]
    user.GetWindowDC.argtypes = [W.HWND]; user.GetWindowDC.restype = W.HDC
    user.ReleaseDC.argtypes = [W.HWND, W.HDC]
    gdi.CreateCompatibleDC.argtypes = [W.HDC]; gdi.CreateCompatibleDC.restype = W.HDC
    gdi.CreateDIBSection.argtypes = [W.HDC, C.POINTER(Header), W.UINT, C.POINTER(C.c_void_p), W.HANDLE, W.DWORD]; gdi.CreateDIBSection.restype = W.HBITMAP
    gdi.SelectObject.argtypes = [W.HDC, W.HGDIOBJ]; gdi.SelectObject.restype = W.HGDIOBJ
    gdi.DeleteObject.argtypes = [W.HGDIOBJ]; gdi.DeleteDC.argtypes = [W.HDC]
    user.PrintWindow.argtypes = [W.HWND, W.HDC, W.UINT]
    screen = user.GetWindowDC(W.HWND(hwnd))
    dc = gdi.CreateCompatibleDC(screen)
    bits = C.c_void_p()
    header = Header(C.sizeof(Header), width, -height, 1, 32, 0, width * height * 4, 0, 0, 0, 0)
    bitmap = gdi.CreateDIBSection(dc, C.byref(header), 0, C.byref(bits), None, 0)
    if not bitmap or not bits.value:
        if dc: gdi.DeleteDC(dc)
        if screen: user.ReleaseDC(W.HWND(hwnd), screen)
        error('INPUT_DENIED')
    previous = gdi.SelectObject(dc, bitmap)
    try:
        if not user.PrintWindow(W.HWND(hwnd), dc, 2):
            error('CAPTURE_UNSUPPORTED')
        image = Image.frombytes('RGB', (width, height), C.string_at(bits, width * height * 4), 'raw', 'BGRX')
        x, y, w, h = roi
        image = image.crop((int(x * width), int(y * height), int((x + w) * width), int((y + h) * height)))
        if image.width > 2048:
            image.thumbnail((2048, 1024))
        return image
    finally:
        gdi.SelectObject(dc, previous); gdi.DeleteObject(bitmap); gdi.DeleteDC(dc); user.ReleaseDC(W.HWND(hwnd), screen)

class Worker:
    def __init__(self, asr_model=None):
        self.gate = Gate()
        self.source = None
        self.next_frame = 0
        self.asr_model = asr_model
        self.asr = None
        self.stream = None
        self.recognition = RollingRecognition()
        self.recognition_key = None
        self.ps = str(Path(os.environ.get('SystemRoot', 'C:/Windows')) / 'System32/WindowsPowerShell/v1.0/powershell.exe')
        self.helper = str(Path(__file__).with_name('windows_ocr.ps1'))
    def ocr_call(self, request=None):
        cmd = [self.ps, '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', self.helper]
        if request is None:
            cmd.append('-Probe')
        result = subprocess.run(cmd, input=None if request is None else json.dumps(request), capture_output=True, text=True, encoding='utf-8', timeout=12, creationflags=0x08000000)
        try:
            return json.loads(result.stdout.strip())
        except (ValueError, TypeError):
            error('OCR_PROVIDER_FAILED')
    def handle(self, request):
        op = request.get('op', request.get('operation'))
        if op == 'probe':
            ocr = self.ocr_call()
            return {'ok': True, 'ocr': bool(ocr.get('ok')), 'ocrLanguages': ocr.get('languages', []), 'asr': bool(self.asr_model and Path(self.asr_model).is_dir()), 'processAudio': sys.getwindowsversion().build >= 20348}
        if op in ('stop', 'status'):
            if op == 'stop': self.stop_stream(); self.source = None; self.gate = Gate()
            return {'ok': True, 'capturing': self.source is not None, 'droppedChunks': self.stream.dropped if self.stream else 0}
        if op not in ('ocr', 'asr'):
            error('UNSUPPORTED')
        if request.get('consent') is not True:
            error('INPUT_DENIED')
        pid, hwnd = identity(request)
        source = (pid, hwnd, request['startTicks'])
        if source != self.source:
            self.stop_stream()
            self.source = source; self.gate = Gate()
        if op == 'ocr':
            self.stop_stream()
            now = time.monotonic()
            if now < self.next_frame:
                error('BACKPRESSURE')
            self.next_frame = now + .5
            roi = validate_roi(request.get('roi', [.05, .65, .9, .3]))
            image = capture_window(hwnd, roi)
            if not self.gate.changed(image):
                return {'ok': True, 'text': '', 'origin': 'ocr', 'unchanged': True}
            data = io.BytesIO(); image.save(data, format='PNG')
            language = request.get('language', 'en')
            if language == 'auto':
                from latin_ocr import LatinOcr, detect_language
                if not hasattr(self, 'latin'):
                    self.latin = LatinOcr()
                text = normalize(self.latin.read(image))
                identity(request)
                return {'ok': True, 'text': self.gate.accept(text), 'language': detect_language(text), 'origin': 'ocr', 'languageDetection': 'VI_EN_HEURISTIC'}
            if language not in ('en', 'vi', 'ja', 'ko', 'zh'):
                error('UNSUPPORTED_LANGUAGE')
            if language == 'vi':
                from latin_ocr import LatinOcr
                if not hasattr(self, 'latin'):
                    self.latin = LatinOcr()
                result = {'ok': True, 'text': self.latin.read(image)}
            else:
                result = self.ocr_call({'image': base64.b64encode(data.getvalue()).decode('ascii'), 'language': language})
            if not result.get('ok'): error(result.get('error', 'OCR_PROVIDER_FAILED'))
            identity(request)
            return {'ok': True, 'text': self.gate.accept(result.get('text', '')), 'language': language, 'origin': 'ocr', 'observedMs': int(time.monotonic() * 1000)}
        return self.recognize(request, pid)
    def recognize(self, request, pid):
        if not self.asr_model or not Path(self.asr_model).is_dir(): error('MODEL_MISSING')
        from process_audio import capture_process
        if self.asr is None:
            verify_asr_model(Path(self.asr_model))
        duration = request.get('durationMs', int(request.get('seconds', 2) * 1000))
        if isinstance(duration, bool) or not isinstance(duration, int) or not 1000 <= duration <= 8000:
            error('INVALID_DURATION')
        source_language = request.get('language', 'auto')
        if source_language not in ('auto', 'ja', 'en', 'vi', 'ko', 'zh', 'th'): error('UNSUPPORTED_LANGUAGE')
        # Initialize before admitting capture; model warm-up must not fill and
        # silently overflow the bounded queue while no decoder can consume it.
        if self.asr is None:
            self.asr = LocalDecoder(self.asr_model)
        recognition_key = (self.source, pid, source_language, duration)
        if self.recognition_key != recognition_key:
            if self.stream is not None and self.recognition_key is not None:
                self.stop_stream()
            self.recognition.reset()
        if self.stream is None or self.stream.seconds != duration / 1000:
            self.stop_stream()
            self.stream = AudioStream(pid, duration / 1000, capture_process)
        self.recognition_key = recognition_key
        if hasattr(self.stream, 'take_packet'):
            audio, sequence, captured_ms = self.stream.take_packet()
        else:
            audio, sequence, captured_ms = self.stream.take(), None, int(time.monotonic() * 1000)
        audio, window_start, window_end = self.recognition.window(audio, sequence)
        identity(request)
        inference_start = time.monotonic()
        detected = self.recognition.language if source_language == 'auto' else source_language
        # Auto begins with transcription, preserving EN/VI. Other languages use
        # Whisper's English translation because downstream supports only EN -> VI.
        task = 'translate' if detected not in (None, 'en', 'vi') else 'transcribe'
        options = dict(language=detected, task=task, beam_size=1, vad_filter=True,
                       word_timestamps=True, condition_on_previous_text=False,
                       initial_prompt=self.recognition.prompt or None)
        segments, info = self.asr.transcribe(audio, **options)
        segments = list(segments)
        actual_language = detected or info.language
        if source_language == 'auto' and detected is None:
            self.recognition.observe_language(info.language, getattr(info, 'language_probability', 0))
            if actual_language not in ('en', 'vi'):
                options.update(language=actual_language, task='translate', initial_prompt=None)
                segments, info = self.asr.transcribe(audio, **options)
                segments = list(segments)
        committed_text = self.recognition.commit(segments, window_start, window_end)
        text = normalize(self.recognition.utterance(committed_text, window_end))
        output_language = 'vi' if actual_language == 'vi' else 'en'
        identity(request)
        return {'ok': True, 'text': text, 'language': output_language, 'sourceLanguage': actual_language,
                'origin': 'asr', 'observedMs': int(time.monotonic() * 1000),
                'capturedMs': captured_ms, 'inferenceMs': int((time.monotonic() - inference_start) * 1000),
                'audioMs': int((window_end - window_start) * 1000),
                'backend': getattr(self.asr, 'backend', 'unknown'),
                'queuedChunks': self.stream.queue.qsize() if hasattr(self.stream, 'queue') else 0,
                'droppedChunks': self.stream.dropped}
    def stop_stream(self):
        if self.stream is not None:
            self.stream.close(); self.stream = None
        self.recognition.reset()
        self.recognition_key = None

class AudioStream:
    """One continuous selected-process capture, two chunks maximum awaiting ASR."""
    def __init__(self, pid, seconds, capture):
        self.seconds = seconds
        self.queue = queue.Queue(maxsize=2)
        self.stop = threading.Event()
        self.dropped = 0
        self.failure = None
        self.sequence = 0
        def run():
            try:
                capture(pid, seconds, on_chunk=self.admit, stop_event=self.stop)
            except Exception as exc:
                self.failure = str(exc) if str(exc).startswith('PROCESS_AUDIO_') else 'PROCESS_AUDIO_UNAVAILABLE'
        self.thread = threading.Thread(target=run, name='AniSub-selected-audio', daemon=True)
        self.thread.start()
    def admit(self, audio):
        if self.stop.is_set(): return
        self.sequence += 1
        packet = (audio, self.sequence, int(time.monotonic() * 1000))
        try:
            self.queue.put_nowait(packet)
        except queue.Full:
            try: self.queue.get_nowait(); self.dropped += 1
            except queue.Empty: pass
            self.queue.put_nowait(packet)
    def take(self):
        return self.take_packet()[0]
    def take_packet(self):
        deadline = time.monotonic() + self.seconds + 6
        while time.monotonic() < deadline:
            if self.failure: error(self.failure)
            if self.stop.is_set(): error('CANCELLED')
            try: return self.queue.get(timeout=.1)
            except queue.Empty: pass
        error('TIMEOUT')
    def close(self):
        self.stop.set()
        self.thread.join(timeout=6)
        if self.thread.is_alive(): error('PROCESS_AUDIO_TIMEOUT')
        while not self.queue.empty():
            try: self.queue.get_nowait()
            except queue.Empty: break

def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--asr-model'); args = parser.parse_args()
    worker = Worker(args.asr_model)
    while True:
        line = sys.stdin.buffer.readline(MAX_FRAME + 1)
        if not line: break
        if len(line) > MAX_FRAME:
            print(json.dumps({'ok': False, 'error': 'FRAME_TOO_LARGE'}), flush=True)
            break
        request = {}
        try:
            request = json.loads(line)
            if not isinstance(request, dict): error('INVALID_REQUEST')
            response = worker.handle(request)
        except CaptureError as exc:
            response = {'ok': False, 'error': str(exc)}
        except subprocess.TimeoutExpired:
            response = {'ok': False, 'error': 'TIMEOUT'}
        except RuntimeError as exc:
            code = str(exc)
            response = {'ok': False, 'error': code if code in ('PROCESS_AUDIO_UNAVAILABLE', 'PROCESS_AUDIO_UNSUPPORTED', 'PROCESS_AUDIO_TIMEOUT') else 'CAPTURE_PROVIDER_FAILED'}
        except Exception:
            response = {'ok': False, 'error': 'CAPTURE_PROVIDER_FAILED'}
        if isinstance(request, dict): response['id'] = request.get('id')
        print(json.dumps(response, ensure_ascii=False), flush=True)
    worker.stop_stream()

if __name__ == '__main__':
    main()
