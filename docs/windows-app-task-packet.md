# ANISUB-WINDOWS-005 — native first usable slice

2026-10-07. Scope: standalone Windows UI with actual local video playback, SRT/VTT import,
original/translated overlay, controls/seek/speed/offset, provider readiness, bounded async
translation port and system speech with explicit language checks. No AniBox/Chrome changes.
Existing Node fixture/core and prior research are preserved; no Git/commit/release.

Initial platform decision: .NET Framework/WPF + System.Speech using installed Windows
PowerShell 5.1, avoiding a bundled browser or new GUI framework. Codec support must be measured.
Two installed voices are English only; do not call them Vietnamese narration. No cloud default.
User explicitly approved offline model downloads and real AI execution this turn. Windows
prototype now uses local Marian EN→VI and VieNeu Nano preset WAV, not an English system voice.
Imported translated subtitles are not claimed to be generated translation.

Ownership: specialist owns apps/desktop/windows/{App.cs,Start-AniSub.ps1} and
tests/windows/{smoke.ps1,app-tests.ps1}. Root owns providers/translation/windows_worker.py,
apps/desktop/windows/TranslationBridge.cs, launcher, fixtures, docs and validation.
No engine download/installation, registry/permissions/capture change before user choice.
Read-only reviewer later if quota allows; bounded root fallback on quota failure.

Acceptance: UI compiles/opens; synthetic local MP4 proves MediaOpened/position advancement,
pause/seek/speed; subtitle timestamp/parser and malformed/oversize/overlap cases; current cue
overlay; async controls do not wait on provider; revision guards stop stale results/audio;
real Windows speech output API tested separately; absent Vietnamese voice/model stays explicit.
An AI-generated VI end-to-end pass is not claimed without actual model/audio evidence.

Rollback: remove only new app/provider/test/fixture/docs files; revert this task's documentation
updates. Preserve Node tests, reference, research and unrelated user files.
