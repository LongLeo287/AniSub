# Fake providers — implemented fixture only

src/index.mjs exports VirtualScheduler, FakeTranslator and FakeTts. Configure latency/failure/
ignored cancellation to exercise race conditions without engines. Translation adds a synthetic
marker; TTS returns virtual duration, never PCM or audible voice. No OCR/ASR/capture/model loads.
Virtual timing is a deterministic test input, not a latency benchmark.
