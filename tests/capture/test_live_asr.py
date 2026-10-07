"""Synthetic timestamp fixtures only; no microphone or user application capture."""
from pathlib import Path
import sys
from types import SimpleNamespace as Item
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'providers/capture'))
from live_asr import RollingRecognition, LocalDecoder
from windows_capture_worker import Worker


def segment(*words):
    return Item(words=[Item(word=text, end=end) for text, end in words], text='unused')


class LiveRecognitionTests(unittest.TestCase):
    def test_cuda_lazy_decode_failure_falls_back_with_explicit_backend(self):
        from unittest.mock import patch
        import os
        calls = []
        class Model:
            def __init__(self, device): self.device = device
            def transcribe(self, audio, **options):
                def segments():
                    if self.device == 'cuda': raise RuntimeError('missing DLL')
                    yield Item(text='fixture')
                return segments(), Item(language='en')
        def factory(path, **options):
            calls.append(options['device'])
            return Model(options['device'])
        with patch.dict(os.environ, {}, clear=False), patch('faster_whisper.WhisperModel', side_effect=factory), patch('ctranslate2.get_cuda_device_count', return_value=1):
            decoder = LocalDecoder('local-only-fixture')
            segments, _ = decoder.transcribe([0], language='en')
            self.assertEqual(decoder.backend, 'cpu-int8-fallback')
            self.assertEqual(calls, ['cuda', 'cpu'])
            self.assertEqual(len(segments), 1)
            if decoder.dll_directory: decoder.dll_directory.close()

    def test_cuda_constructor_failure_and_cpu_failure_do_not_retry_gpu(self):
        from unittest.mock import patch
        import os
        calls = []
        class Cpu:
            def transcribe(self, *args, **kwargs): raise RuntimeError('CPU failure')
        def factory(path, **options):
            calls.append(options['device'])
            if options['device'] == 'cuda': raise OSError('CUDA DLL unavailable')
            return Cpu()
        with patch.dict(os.environ, {}, clear=False), patch('faster_whisper.WhisperModel', side_effect=factory), patch('ctranslate2.get_cuda_device_count', return_value=1):
            decoder = LocalDecoder('local-only-fixture')
            self.assertEqual(decoder.backend, 'cpu-int8-fallback')
            for _ in range(2):
                with self.assertRaises(RuntimeError): decoder.transcribe([0], language='en')
            self.assertEqual(calls, ['cuda', 'cpu'])
            if decoder.dll_directory: decoder.dll_directory.close()

    def test_boundary_word_delayed_then_owned_once(self):
        state = RollingRecognition()
        audio, start, end = state.window([0] * 32000, 1)
        self.assertEqual(state.commit([segment((' This', .4), (' boundary', 1.9))], start, end), 'This')
        audio, start, end = state.window([0] * 32000, 2)
        self.assertEqual(len(audio), 44000)
        self.assertEqual(state.commit([segment((' This', .1), (' boundary', .65), (' survives', 1.5))], start, end), 'boundary survives')

    def test_repeated_speech_has_distinct_time_not_text_dedup(self):
        state = RollingRecognition()
        _, start, end = state.window([0] * 32000, 1)
        self.assertEqual(state.commit([segment((' yes', .5), (' yes', 1.2))], start, end), 'yes yes')
        _, start, end = state.window([0] * 32000, 2)
        self.assertEqual(state.commit([segment((' yes', .1), (' yes', 1.2))], start, end), 'yes')

    def test_gap_does_not_stitch_audio_and_clears_prompt(self):
        state = RollingRecognition()
        state.window([0] * 32000, 1)
        state.prompt = 'previous'
        audio, start, end = state.window([0] * 32000, 4)
        self.assertEqual(len(audio), 32000)
        self.assertEqual(start, 6)
        self.assertEqual(state.prompt, '')

    def test_context_and_buffer_bounded(self):
        state = RollingRecognition()
        for index in range(1, 100):
            _, start, end = state.window([0] * 32000, index)
            state.commit([segment((' a' * 200, 1))], start, end)
        self.assertLessEqual(len(state.prompt), 240)
        self.assertEqual(len(state.tail), 12000)

    def test_language_only_locks_after_two_confident_votes(self):
        state = RollingRecognition()
        state.observe_language('en', .95)
        self.assertIsNone(state.language)
        state.observe_language('vi', .4)
        state.observe_language('en', .95)
        self.assertIsNone(state.language)
        state.observe_language('en', .9)
        self.assertEqual(state.language, 'en')
        state.reset()
        self.assertIsNone(state.language)
        self.assertEqual(state.prompt, '')

    def test_english_task_transcribe_and_metadata(self):
        from unittest.mock import patch
        requests = []
        class Asr:
            def transcribe(self, audio, **kwargs):
                requests.append(kwargs)
                return [segment((' hello.', .4))], Item(language='en', language_probability=.99)
        worker = Worker(str(Path(__file__).parent))
        worker.asr = Asr()
        worker.stream = Item(seconds=2, dropped=0, take=lambda: [0] * 32000)
        with patch('windows_capture_worker.identity', return_value=(1, 2)):
            response = worker.recognize({'language': 'en'}, 1)
        self.assertEqual(requests[0]['task'], 'transcribe')
        self.assertTrue(requests[0]['word_timestamps'])
        self.assertEqual(response['text'], 'hello.')
        self.assertEqual(response['audioMs'], 2000)
        self.assertGreaterEqual(response['inferenceMs'], 0)
        self.assertEqual(response['queuedChunks'], 0)

    def test_non_english_auto_output_truthful_english(self):
        from unittest.mock import patch
        requests = []
        class Asr:
            def transcribe(self, audio, **kwargs):
                requests.append(kwargs)
                return [segment((' translated', .4))], Item(language='ja', language_probability=.99)
        worker = Worker(str(Path(__file__).parent)); worker.asr = Asr()
        worker.stream = Item(seconds=2, dropped=0, take=lambda: [0] * 32000)
        with patch('windows_capture_worker.identity', return_value=(1, 2)):
            response = worker.recognize({'language': 'auto'}, 1)
        self.assertEqual([r['task'] for r in requests], ['transcribe', 'translate'])
        self.assertEqual(response['language'], 'en')

    def test_shifted_overlap_word_not_duplicated_and_held_word_retained(self):
        state = RollingRecognition()
        state.committed = 1.75
        state.words = [('first', 1.7)]
        words = [Item(word=' first', start=.3, end=.57), Item(word=' held', start=.4, end=.48), Item(word=' first', start=1, end=1.4)]
        # Window starts1.25: duplicate drifts1.70->1.82; held1.9->1.73;
        # genuine repeated 'first' at2.65 must not be removed.
        text = state.commit([Item(words=words)], 1.25, 4)
        self.assertEqual(text, 'held first')

    def test_phrase_assembler_bounded_and_reset(self):
        state = RollingRecognition()
        self.assertEqual(state.utterance('You should not', 2), '')
        self.assertEqual(state.utterance('buy this.', 4), 'You should not buy this.')
        self.assertEqual(state.utterance('unfinished', 6), '')
        self.assertEqual(state.utterance('', 8), 'unfinished')
        state.utterance('old', 12); state.reset()
        self.assertEqual(state.pending, '')

    def test_gap_flushes_retained_clause_separately(self):
        state = RollingRecognition()
        state.window([0] * 32000, 1)
        self.assertEqual(state.utterance('retained clause', 2), '')
        state.window([0] * 32000, 4)
        self.assertEqual(state.utterance('new clause.', 8), 'retained clause')
        self.assertEqual(state.utterance('', 10), 'new clause.')

    def test_assembler_size_bound_preserves_order_and_rejects_oversize(self):
        state = RollingRecognition()
        self.assertEqual(state.utterance('a' * 179, 2), '')
        self.assertEqual(state.utterance('b' * 400, 4), 'a' * 179)
        self.assertEqual(state.utterance('', 6), 'b' * 400)
        with self.assertRaises(ValueError): state.utterance('x' * 513, 8)

    def test_request_language_change_resets_audio_context(self):
        from unittest.mock import patch
        class Asr:
            def transcribe(self, audio, **kwargs):
                self.options = kwargs
                return [segment((' mới.', .5))], Item(language='vi', language_probability=.99)
        worker = Worker(str(Path(__file__).parent)); worker.asr = Asr()
        worker.recognition_key = (None, 1, 'en', 2000)
        worker.recognition.prompt = 'old English'
        worker.recognition.pending = 'old phrase'
        worker.recognition.language = 'en'
        old = Item(seconds=2, dropped=0, closed=False)
        old.close = lambda: setattr(old, 'closed', True)
        worker.stream = old
        fresh = Item(seconds=2, dropped=0, take=lambda: [0] * 32000)
        with patch('windows_capture_worker.identity', return_value=(1, 2)), patch('windows_capture_worker.AudioStream', return_value=fresh):
            response = worker.recognize({'language': 'vi'}, 1)
        self.assertTrue(old.closed)
        self.assertIsNone(worker.asr.options['initial_prompt'])
        self.assertEqual(response['language'], 'vi')
        self.assertEqual(response['text'], 'mới.')


if __name__ == '__main__':
    unittest.main(verbosity=2)
