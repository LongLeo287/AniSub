# Live ASR loss/latency fix — 2026-10-07

Baseline: standalone workspace has no Git baseline/remote. Existing running host is
left untouched; source changes apply after explicit user restart. AniBox is forbidden.

Evidence: user screenshot shows 48 started utterances and 22 output drops. Current
capture slices 4 s non-overlapping; live output admits only two jobs with 5 s expiry
and waits for speech completion before beginning next translation/synthesis.

Allowed: providers/capture/windows_capture_worker.py, new bounded live-ASR helper,
tests/capture; ExternalMedia.cs, tests/windows/external-*; CaptureBridge.cs,
CapturePanel.cs, ExternalHost.cs, tests/windows/ExternalFixtureTests.cs,
tests/windows/make-live-asr-fixture.ps1, docs/live-asr-fix-*.md, docs/roadmap.md.
No model/engine changes, downloads, cloud, Chrome changes, capture-permission changes,
live process replacement, AniBox edits, publish or Git operations.

Execution refinement: existing RTX3060/CUDA/cuDNN libraries verified. Existing pinned
ASR model may use CUDA after real fixture success, with explicit CPU fallback/backend
metrics; no new native library/dependency. User explicitly approved closing/reopening
only AniSub after tests. Do not resume capture automatically or touch YouTube playback.

Acceptance: overlapping recognition does not blindly duplicate shared text; English
transcription uses transcribe; bounded context and language cache reset on source/mode
change. Live scheduling prepares one next utterance during playback, exposes overload,
and never silently discards admitted jobs at the old 5 s deadline. Finite queue overload
must be explicit, not an unbounded backlog. Preserve browser expiry and stale-generation
guards. Tests cover boundary/repeated speech, overload, cancellation and prefetch;
compile Windows host. Content-free timing is preferred; no raw audio/text logs.

Rollback: reverse only this packet's patch set, preserving all other work; retain
existing model packs. Do not reset workspace or kill current user session.
Limit: near-realtime requires whole-pipeline throughput; no promise of zero delay,
perfect translation, no-loss indefinitely, or verified YouTube quality without live test.
