"""Bounded rolling PCM and timestamp ownership; never deduplicate by text alone."""
import math
import os
from pathlib import Path


class LocalDecoder:
    """Reuse installed CUDA DLLs only; CPU fallback has an explicit backend label."""
    def __init__(self, model_path, device='auto', cpu_threads=None):
        from faster_whisper import WhisperModel
        self.factory = WhisperModel
        self.path = str(model_path)
        self.threads = cpu_threads or min(4, os.cpu_count() or 2)
        self.dll_directory = None
        self.backend = 'cpu-int8'
        if device != 'cpu':
            try:
                import importlib.util
                import ctranslate2
                spec = importlib.util.find_spec('torch')
                if os.name == 'nt' and spec and spec.submodule_search_locations:
                    library = Path(next(iter(spec.submodule_search_locations))) / 'lib'
                    if library.is_dir():
                        self.dll_directory = os.add_dll_directory(str(library))
                        # CTranslate2 resolves CUDA dependencies using LoadLibrary.
                        # This changes this worker's environment only, never OS PATH.
                        os.environ['PATH'] = str(library) + os.pathsep + os.environ.get('PATH', '')
                if ctranslate2.get_cuda_device_count() > 0:
                    self.model = self.factory(self.path, device='cuda', compute_type='float16', num_workers=1, local_files_only=True)
                    self.backend = 'cuda-float16'
                    return
            except (RuntimeError, OSError, ImportError):
                self.backend = 'cpu-int8-fallback'
        self.model = self.cpu()

    def cpu(self):
        return self.factory(self.path, device='cpu', compute_type='int8', cpu_threads=self.threads, num_workers=1, local_files_only=True)

    def transcribe(self, audio, **options):
        try:
            segments, info = self.model.transcribe(audio, **options)
            return list(segments), info
        except (RuntimeError, OSError):
            if self.backend != 'cuda-float16':
                raise
            # Native CUDA loading can be lazy until the first decode.
            self.model = self.cpu()
            self.backend = 'cpu-int8-fallback'
            segments, info = self.model.transcribe(audio, **options)
            return list(segments), info


class RollingRecognition:
    SAMPLE_RATE = 16000
    OVERLAP_SECONDS = .75
    HOLD_SECONDS = .25

    def __init__(self):
        self.reset()

    def reset(self):
        self.tail = None
        self.end = 0.0
        self.committed = 0.0
        self.sequence = None
        self.prompt = ''
        self.language = None
        self.candidate = None
        self.votes = 0
        self.words = []
        self.pending = ''
        self.pending_start = None
        self.gap = False

    def window(self, audio, sequence=None):
        import numpy as np
        # An overload gap must not stitch unrelated samples or reuse context.
        if sequence is not None and self.sequence is not None and sequence != self.sequence + 1:
            self.gap = True
            self.tail = None
            self.prompt = ''
            self.words = []
            self.end += max(0, sequence - self.sequence - 1) * len(audio) / self.SAMPLE_RATE
            self.committed = self.end
        self.sequence = sequence
        prefix = self.tail
        start = self.end - (len(prefix) / self.SAMPLE_RATE if prefix is not None else 0)
        combined = np.concatenate((prefix, audio)) if prefix is not None else np.asarray(audio)
        self.end += len(audio) / self.SAMPLE_RATE
        self.tail = np.asarray(audio[-int(self.SAMPLE_RATE * self.OVERLAP_SECONDS):]).copy()
        return combined, start, self.end

    def observe_language(self, language, probability):
        # Two agreeing confident windows before locking short-window detection.
        if language not in ('en', 'vi', 'ja', 'ko', 'zh', 'th') or not isinstance(probability, (int, float)) or not math.isfinite(probability) or probability < .8:
            self.candidate, self.votes = None, 0
            return
        if language == self.candidate:
            self.votes += 1
        else:
            self.candidate, self.votes = language, 1
        if self.votes >= 2:
            self.language = language

    def commit(self, segments, start, end):
        horizon = max(self.committed, end - self.HOLD_SECONDS)
        pieces = []
        emitted = []
        matched = set()
        for segment in segments:
            words = getattr(segment, 'words', None)
            if words:
                for word in words:
                    word_end = start + float(word.end)
                    word_start = start + float(getattr(word, 'start', word.end))
                    token = word.word.strip().casefold().strip('.,!?;:')
                    # Reconcile timestamp jitter only in actual shared PCM. Each
                    # prior word can match once; repeated speech in new time survives.
                    prior = next((i for i, (old_token, old_end) in enumerate(self.words)
                                  if i not in matched and old_token == token
                                  and abs(old_end - word_end) <= .2
                                  and word_start <= self.committed), None)
                    if prior is not None:
                        matched.add(prior)
                        continue
                    if self.committed - .15 < word_end <= horizon:
                        pieces.append(word.word)
                        emitted.append((token, word_end))
            else:
                # Compatibility for engines without words: segments cannot be
                # divided safely; emit whole intersecting segment, without text dedup.
                segment_end = start + float(getattr(segment, 'end', end - start))
                if segment_end > self.committed + .015:
                    pieces.append(segment.text)
        self.committed = horizon
        self.words = (self.words + emitted)[-32:]
        text = ''.join(pieces) if any(getattr(s, 'words', None) for s in segments) else ' '.join(pieces)
        text = ' '.join(text.split())
        if text:
            self.prompt = (self.prompt + ' ' + text)[-240:]
        return text

    def utterance(self, text, end):
        """Retain unfinished clause for at most two further audio seconds / 180 chars."""
        if len(text) > 512:
            raise ValueError('TEXT_TOO_LONG')
        if self.gap:
            self.gap = False
            if self.pending:
                value = self.pending
                self.pending, self.pending_start = text, end if text else None
                return value
        if text:
            if self.pending and len(self.pending) + len(text) + 1 > 512:
                value = self.pending
                self.pending, self.pending_start = text, end
                return value
            if self.pending_start is None:
                self.pending_start = end
            self.pending = (self.pending + ' ' + text).strip()
        if not self.pending:
            return ''
        if self.pending.endswith(('.', '!', '?', '。', '！', '？')) or len(self.pending) >= 180 or end - self.pending_start >= 2:
            value = self.pending
            self.pending, self.pending_start = '', None
            return value
        return ''
