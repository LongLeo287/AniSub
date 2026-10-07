# Live captured-audio fix — 2026-10-07

This is a Windows prototype fix, not protocol-v1 or AniBox/Android changes.
User screenshot: 48 started phrases, 22 scheduler drops/refusals in old host.
No claim that each counter equals a missing sentence or that original English was graded.

## Changes

- Rolling local ASR with shared audio and word timestamps, bounded overlap matching,
  delayed boundary-word commitment, short recognition prompt and cautious language lock.
  English uses transcription rather than Whisper's English translation task.
- Existing pinned Small model uses existing RTX3060 CUDA/float16 libraries when usable,
  with one bounded CPU/int8 fallback after constructor or lazy-decode failure. Backend
  is visible; no driver, native library or model installation and no OS PATH change.
- A bounded clause assembler gives Marian more than a single fragment where possible;
  retaining context is a latency trade-off, not free quality improvement.
- Live output retains admitted phrases instead of applying the old 5-second expiry.
  It prepares one next waveform while the current waveform plays. Stop/source/voice
  invalidation still retires results; browser media-time expiry remains unchanged.
- Eight waiting text jobs, at most one in-flight job, one prepared and one active WAV.
  CapturePanel holds one rejected result and retries rather than throwing it away.
  Continuous audio still has two waiting chunks: sustained overload can lose audio.
- Compact UI now exposes audio loss, recognition time and queue/refusal counters.
  Text/audio are not logged. No capture permissions, model downloads or cloud fallback.

## Validation

- Windows full host source compilation passed without launching a second pipe host.
- Actual WPF scheduling fixture: 40/40 checks, including concurrent prepare/playback,
  retained >5-second live phrase, queue refusal/retry, and prepared-wave cancellation.
- Node syntax: 10 modules; existing core contracts/regressions: 30/30 passed.
- Independent review: 14/14 pure ASR boundary, gap, phrase-buffer and GPU fallback tests.
  All four reviewed source findings corrected before rollout.
- Full capture suite: 33/33 tests, including prior synthetic OCR/process-isolation checks.
- Real own English SAPI fixture: 15.94 s; GPU rolling 2 s+0.75 s overlap warm inference
  875 ms total, maximum window 125 ms, RTF 0.055; fixture WER 0, both negations and
  repeated 'yes' preserved. First phrase emitted at 2 s of audio in this fixture;
  unfinished clauses can wait two further audio seconds. This is not full pipeline latency.
- CPU rolling 2 s on the same fixture was RTF 1.184: do not promise CPU realtime on
  this configuration. GPU removes the measured ASR bottleneck, not all possible bottlenecks.
  No user YouTube audio was captured by tests.

User-authorized rollout: old host PID26832 closed gracefully; new host PID17372
opened from final source. YouTube playback untouched. No capture auto-start: user
must choose the source, ASR/English and explicitly start capture again. Live video
quality/latency remains pending that test; source compilation is not live acceptance.

## Limits / rollout

Finite buffering cannot promise all words during indefinite overload. Keeping accepted
speech trades more delay for fewer deadline losses; the app cannot pause external YouTube.
Overlap timestamp/token reconciliation is heuristic and may still fail on drift,
rapid repetition or changed recognition. Marian/Turbo remain the installed models.
Synthetic speech does not establish semantic correctness on the user's YouTube video.
Full streaming PCM TTS, human translation scoring, p95 latency and long-video soak remain
pending. Restart is required; selecting/consenting capture remains a user action.
Rollback is limited to this packet's changed files; preserve models and unrelated source.
