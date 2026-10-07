"""Run with existing AniSub Python, synthetic own content only."""
import ctypes
import io
import os
from pathlib import Path
import subprocess
import sys
import time
import threading
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'providers/capture'))
from windows_capture_worker import Gate, Worker, AudioStream, CaptureError, validate_roi, normalize, identity, capture_window

class ContractTests(unittest.TestCase):
    def test_continuous_capture_during_slow_inference(self):
        def fake(pid, seconds, on_chunk, stop_event):
            index = 0
            while not stop_event.wait(.02):
                on_chunk(index); index += 1
        stream = AudioStream(1, 1, fake)
        try:
            first = stream.take()
            time.sleep(.18)  # Stand-in for slow native decoding: capture never pauses.
            second = stream.take()
            self.assertGreater(second, first + 1)
            self.assertGreater(stream.dropped, 0)
            self.assertLessEqual(stream.queue.qsize(), 2)
        finally: stream.close()
        self.assertFalse(stream.thread.is_alive())
    def test_vietnamese_asr_preserves_vietnamese(self):
        from types import SimpleNamespace
        from unittest.mock import patch
        class FakeAsr:
            def __init__(self): self.tasks = []
            def transcribe(self, audio, **kwargs):
                self.tasks.append(kwargs['task'])
                return [SimpleNamespace(text='Xin chào.')], SimpleNamespace(language='vi')
        for language, tasks in [('vi', ['transcribe']), ('auto', ['transcribe'])]:
            worker = Worker(str(ROOT / 'models')); worker.asr = FakeAsr()
            worker.stream = SimpleNamespace(seconds=2, dropped=3, take=lambda: [0] * 32000)
            with patch('windows_capture_worker.identity', return_value=(1, 2)):
                response = worker.recognize({'language': language}, 1)
            self.assertEqual(response['language'], 'vi')
            self.assertEqual(response['text'], 'Xin chào.')
            self.assertEqual(worker.asr.tasks, tasks)
            self.assertEqual(response['droppedChunks'], 3)
            with patch('windows_capture_worker.identity', return_value=(1, 2)):
                repeated = worker.recognize({'language': language}, 1)
            self.assertEqual(repeated['text'], 'Xin chào.', 'Independent ASR chunks may repeat a legitimate utterance')
    def test_roi_bounds(self):
        for value in (None, [], [0, 0, 2, 1], [0, 0, float('nan'), 1], [True, 0, 1, 1], [-1, 0, 1, 1]):
            with self.assertRaises(CaptureError): validate_roi(value)
        self.assertEqual(validate_roi([0, .65, 1, .35]), [0, .65, 1, .35])
    def test_dedup_preserves_meaning_and_blank(self):
        gate = Gate()
        self.assertEqual(gate.accept('Tôi không đi'), 'Tôi không đi')
        self.assertEqual(gate.accept('Tôi không đi'), '')
        self.assertEqual(gate.accept('Tôi đi'), 'Tôi đi')
        gate.accept('')
        self.assertEqual(gate.accept('Tôi đi'), 'Tôi đi')
    def test_normalization(self):
        self.assertEqual(normalize('a\n b'), 'a b')
        with self.assertRaises(CaptureError) as ctx: normalize('a' * 900)
        self.assertEqual(str(ctx.exception), 'TEXT_TOO_LONG')
    def test_consent_gate(self):
        with self.assertRaises(CaptureError) as ctx: Worker().handle({'operation': 'ocr', 'pid': 1})
        self.assertEqual(str(ctx.exception), 'INPUT_DENIED')
    def test_invalid_source(self):
        with self.assertRaises(CaptureError): identity({'pid': 1, 'hwnd': 1, 'startTicks': 1})
    def test_status_stop(self):
        worker = Worker(); worker.source = (1, 2, 3)
        self.assertTrue(worker.handle({'operation': 'status'})['capturing'])
        self.assertFalse(worker.handle({'operation': 'stop'})['capturing'])
    def test_probe(self):
        result = Worker().handle({'operation': 'probe'})
        self.assertTrue(result['ocr'])
        self.assertIn('en-US', result['ocrLanguages'])
        self.assertFalse(result['asr'])
    def test_ocr_in_memory(self):
        import base64
        from PIL import Image, ImageDraw, ImageFont
        image = Image.new('RGB', (900, 150), 'black')
        ImageDraw.Draw(image).text((20, 20), 'Hello AniSub 123', fill='white', font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf', 55))
        data = io.BytesIO(); image.save(data, 'PNG')
        result = Worker().ocr_call({'image': base64.b64encode(data.getvalue()).decode(), 'language': 'en'})
        self.assertTrue(result['ok'], result)
        self.assertIn('AniSub', result['text'])
    def test_missing_vietnamese_ocr_language(self):
        import base64
        from PIL import Image
        data = io.BytesIO(); Image.new('RGB', (50, 50)).save(data, 'PNG')
        result = Worker().ocr_call({'image': base64.b64encode(data.getvalue()).decode(), 'language': 'vi'})
        self.assertFalse(result['ok'])
        self.assertEqual(result['error'], 'OCR_LANGUAGE_UNAVAILABLE')
    def test_actual_selected_window(self):
        import tkinter as tk
        window = tk.Tk(); window.title('AniSub synthetic OCR fixture'); window.geometry('900x180')
        tk.Label(window, text='Hello AniSub 123', bg='black', fg='white', font=('Arial', 40)).pack(fill='both', expand=True)
        window.update()
        try:
            hwnd = ctypes.windll.user32.GetParent(window.winfo_id()) or window.winfo_id()
            image = capture_window(hwnd, [0, 0, 1, 1])
            self.assertGreater(image.width, 800)
            import base64
            data = io.BytesIO(); image.save(data, 'PNG')
            result = Worker().ocr_call({'image': base64.b64encode(data.getvalue()).decode(), 'language': 'en'})
            self.assertTrue(result['ok'], result)
            self.assertIn('AniSub', result['text'])
        finally:
            window.destroy()
    def test_process_loopback_own_pid(self):
        from process_audio import capture_process
        import numpy as np
        audio = capture_process(os.getpid(), 1)
        self.assertEqual(len(audio), 16000)
        self.assertTrue(np.isfinite(audio).all())
    def test_native_continuous_chunks(self):
        from process_audio import capture_process
        import numpy as np
        import sounddevice as sd
        tone = np.sin(np.arange(48000 * 4) * (2 * np.pi * 440 / 48000)).astype(np.float32) * .1
        sd.play(tone, 48000)
        stream = AudioStream(os.getpid(), 1, capture_process)
        try:
            first, second = stream.take(), stream.take()
            self.assertEqual(len(first), 16000)
            self.assertEqual(len(second), 16000)
            self.assertGreater(float(np.abs(first).max()), .01)
            self.assertGreater(float(np.abs(second).max()), .01)
        finally: stream.close(); sd.stop()
    def test_selected_process_audio_not_other(self):
        import sounddevice as sd
        import numpy as np
        from process_audio import capture_process
        tone = np.sin(np.arange(48000 * 3) * (2 * np.pi * 440 / 48000)).astype(np.float32) * .1
        sd.play(tone, 48000)
        try:
            audio = capture_process(os.getpid(), 1)
            self.assertGreater(float(np.abs(audio).max()), .01)
        finally: sd.stop()
        child = subprocess.Popen([sys.executable, '-c', 'import numpy as n,sounddevice as s,time; s.play(n.sin(n.arange(48000*5)*2*n.pi*440/48000).astype(n.float32)*.1,48000);time.sleep(5)'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            # Parent process-tree includes children by design; test the OTHER direction:
            # select the silent child's PID while only this parent plays a tone.
            child.terminate(); child.wait(timeout=3)
            silent = subprocess.Popen([sys.executable, '-c', 'import time;time.sleep(5)'])
            try:
                sd.play(tone, 48000)
                audio = capture_process(silent.pid, 1)
                self.assertLess(float(np.abs(audio).max()), .001)
            finally: sd.stop(); silent.terminate(); silent.wait(timeout=3)
        finally:
            if child.poll() is None: child.terminate(); child.wait(timeout=3)

if __name__ == '__main__': unittest.main(verbosity=2)
