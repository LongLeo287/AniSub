# External desktop evidence — 2026-10-07

No Git/branch in AniSub local workspace; no commit, push, release or AniBox changes.

## Changed-file inventory

New Windows host: ExternalHost.cs, ExternalMedia.cs, CaptureBridge.cs, CapturePanel.cs,
ChromeSetupPanel.cs, Start-Background.ps1, Start-AniSub-Background.cmd and Start-AniSub-Player.cmd.
Modified default Start-AniSub.cmd to launch background host; TranslationBridge/VoiceSelection
and windows_worker.py support model-specific voice catalog and verified Turbo loading.
New capture folder worker/WinRT OCR/process_audio/latin_ocr/README; new Turbo adapter;
quality-catalog.json and quality/OCR installers. Chrome companion and native broker folders
now contain their runnable scripts/manifest/launcher/registration controls and READMEs.
Focused tests added under tests/chrome, tests/native-messaging, tests/capture and tests/windows;
external fixtures include future-cue, live-deadline, retry and session regressions.
README, .gitignore, canonical design/file-map/roadmap and evidence/task-packet docs updated.
Machine-local models, partial staging and redacted evidence remain under ignored models/work.

## Implemented and run

- Windows external host, no embedded player; compact settings toggle, manual/foreground
  recommendations, explicit selected-source capture and bounded real MT/TTS orchestration.
- Chrome MV3 direct TextTracks companion + native binary framing/allowlist + named pipe.
  Installed Chrome registration/smoke has NOT been performed. New UI registration executes
  only on a user click with an exact extension ID; no registry write during tests.
- Actual process-specific WASAPI capture, continuous same-client chunk production while ASR
  runs; queue two chunks and explicit overflow counts. Own test tone excluded from other PID.
- Windows OCR synthetic window passed; separate local EasyOCR Vietnamese synthetic text passed.
  VI/EN OCR Auto is a heuristic with manual override, not universal language identification.
- Verified Turbo/codec/Whisper and OCR weights installed; old Nano/Marian preserved.
- Approved Japanese movie 8-second segment → English recognition → Vietnamese translation →
  finite 48 kHz Turbo output passed (aggregate-only evidence in work/japanese-pipeline-evidence.json).
  The earlier 245/250-second windows produced no VAD speech; 260-second window was nonempty.
  This is not a semantic translation grade or whole-film benchmark.
- Real Central male/female Turbo output passed; 25 preset metadata includes all three regions
  and both genders. Human accent/pronunciation listening remains pending.
- Real native framing → actual Windows pipe → real Turbo output start/finish passed, 29 frames.
  No Chrome registration required for this isolated smoke; not installed-browser evidence.

## Reviewed fixes

Independent specialists identified and root fixed: stale tab close/status crossing sessions;
obsolete native port reconnect callbacks; queue-full cues permanently marked seen; long-session
512-cue shutdown; language change without revision; ASR capture gaps and VI→EN→VI roundtrip;
stale live results after inference; ASR repeated-line suppression; early future caption display;
voice-change browser disconnection. Monotonic live deadline is checked after awaits and before
output; expired WAV leases are released. Actual broker smoke additionally exposed a per-frame
pipe availability race, fixed by retry within the same 3-second deadline, not unbounded retry.

Focused tests: Chrome 18; native messaging 19; external async fixture 21; pipe identity/frame
8; quality installer 18; capture/Latin OCR 19; legacy Node 30, Windows parser/lifecycle 12,
voice metadata/bridge 14 passed in root reruns. Prior sync timeline20/Dispatcher16 acceptance
is preserved and rerun separately. Actual Central waveform and JP pipeline checks passed.
Synthetic fixtures do not establish real game compatibility, subtitle accuracy or voice quality.

New Latin OCR model closes the earlier VI Windows-language limitation without changing OS
language packs. Uses existing EasyOCR1.7.2, pinned publisher-release model MD5 plus local SHA256,
exact artifact bounds and weights-only loading; no implicit downloads. Windows OCR still has
en-US only; Japanese OCR without its installed language remains unavailable. Source references:
[EasyOCR official usage](https://github.com/JaidedAI/EasyOCR),
[pinned model definitions](https://github.com/JaidedAI/EasyOCR/blob/v1.7.2/easyocr/config.py).
Publisher checksums and upstream license representations are not a signed update catalog or
independent voice/training-data rights audit. Portable redistribution remains a release gate.

## Not accepted yet

Every-site/game capture; protected graphics/DRM; Android/AniBox; true multi-speaker dubbing/lip-sync;
process-game audio ducking; signed automatic model updates; packaged small portable runtime;
human pronunciation/translation score; all-video continuous latency/memory soak.

Two interrupted quality-model stages (~367 MiB) remain unpromoted. Exact cleanup was blocked
by execution policy; no bypass or deletion of user assets was attempted. Old approved movie
cache and original media are unchanged.
