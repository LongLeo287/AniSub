# ADR 001: Native Windows direct-subtitle prototype first

2026-10-07; accepted for this prototype, not final cross-platform architecture.

Context: user wants the fastest usable/testable standalone AniSub with real offline translation
and Vietnamese narration, before AniBox integration. Windows has .NET Framework/WPF and an
existing Python AI environment. No installed Vietnamese system voice exists.

Decision: source-compile a small WPF shell using Windows PowerShell 5.1; use MediaElement for
local video and MediaPlayer for generated WAV. One persistent Python worker loads pinned local
Marian EN→VI and VieNeu Nano ONNX preset models. Explicitly prepare once, then request only
current direct subtitle cues. Keep one active + latest pending job and reject obsolete replies.
Pause/seek/source/speed changes stop output immediately; native inference is discard-only until
it returns. Provider failures leave video playback intact. No cloud, listener port or auto capture.

Alternatives: Chrome MV3 adds site-specific subtitle/capture/permission work; Android AniBox
requires player/service/device integration; bundled Electron adds a browser package. Native
Windows is easiest to test on this host. A packaged native executable remains future work.

Consequences: WPF codec support is limited by Windows; Python/Torch remain substantial runtime
dependencies even though the GUI is small. Nano is a preview completed-utterance model, not
frame streaming. The internal bridge is not shared protocol-v1. Production needs long-video
tests, packaged isolated runtime, provider separation, deadlines/prefetch and model update gates.
