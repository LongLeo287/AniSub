# AniSub roadmap

2026-10-08 ANISUB-005 ([task](tasks/ANISUB-005.md)): **in progress**, isolated
`codex/anisub-005` branch from `f0b77f5`. Catalog/voice manager and additive Messenger 1.2
selection are being implemented with strict older-AniBox compatibility and one-model residency
gates. Intake evidence: 119 Android JUnit tests, 4,296 legacy admission checks, 34 Node tests,
debug build/lint pass. These are baseline results, not validation of the new implementation.
No AniBox changes, release, signing, commit or push in this task.

ANISUB-005 corrective checkpoint (2026-10-08): 134 Android JUnit tests pass (15 added vs intake),
debug build passes, lint 0 errors / 1 warning, legacy admission 4,296 and Node 34 tests pass.
Old-version cleanup protects LKG; catalog error recovery and voice admission tests added.
This was an intermediate checkpoint, not whole-task acceptance.

ANISUB-005 next checkpoint: 142 JUnit tests pass; bounded optional voice metadata/schema and
TV voice manager + AI reading controls implemented. API-34 offline D-pad/BACK smoke covers
inventory, actions, reading menus and safe download confirmation. ARM debug APK 37,091,130
bytes (+36,001 vs intake); six native entries unchanged. Catalog/storage/residency full matrix,
Cake publication availability, independent review and physical voice quality remain pending.
No AniBox client behavior is claimed for stored priority/duck preferences. Final wording rebuild
also passes; latest ARM debug APK is 37,181,719 bytes (+126,590 vs intake). See task evidence.

2026-10-08 VOICE-002 ([research](research/VOICE-002-2026-10-08.md)): primary-source
documentation complete; no additional clean-provenance male/southern/central voice below
approximately 80 MB admitted. Cake sid0/sid2 are the already owner-approved candidates;
synthetic voice-source rights/consent remain undisclosed. Consented recording and Piper
fine-tuning plan documented. No model download, training or P650 benchmark performed.

2026-10-07 ANISUB-004 ([task](tasks/ANISUB-004.md)): protocol **major 1 / minor 2** — softsub
translated on the TV (ML Kit Translate, consented models) into the voice language (vi/en), English
Piper voice pack `en-ljspeech-medium` (release `voices-en-v1` staged, not published), cues sent ahead
as a timeline with 90 s lookahead pre-translation. Evidence: 105 JUnit, gate 4,296, Node 34, lint
clean, emulator sessions on time (see apps/android/README.md). P650 hardware pending.

2026-10-07 ANISUB-AI-001 (owner reassigned AniSub to Claude; [task](tasks/ANISUB-AI-001.md)):
Android on-device AI narration implemented as the shipping **major 1 / minor 1** contract
(sherpa-onnx 1.13.8 + Piper `vi_VN-vais1000-medium`, consented verified voice-pack download,
TV settings screen, runtime same-signer trust without install-time permission). Evidence: 65 JUnit
tests, legacy 4,296-check gate, Node 30 tests, debug/lint/unsigned release builds, host x86-64 RTF
0.056-0.076. Emulator and P650 hardware measurements are pending. Major 2 remains future work.

Standalone Android M1: written System Design approved by user on 2026-10-07.
[Implementation plan](superpowers/plans/2026-10-07-android-m1.md) drafted/self-reviewed;
plan review/execution confirmation pending. No new AI code/native/model installation from this
planning stage. Tasks remain unchecked until implementation and evidence pass.

Current ownership (2026-10-07): build AniSub standalone; Claude owns the AniBox addon. No further
AniBox edits are part of this task. The separate Android companion already implements private
same-signature Messenger major 1 and system-TTS testing, not AI inference. See
[Android README](../apps/android/README.md),
[runtime design](superpowers/specs/2026-10-07-anisub-runtime-design.md) and
[addon contract](android-addon-contract.md). v2 remains a draft, not implemented.

Next Android milestones are staged: (1) verified Vietnamese AI voice, bounded model management
and negotiated settings; (2) contextual translation/terminology; (3) permissioned OCR/ASR fallback;
(4) verified optional voice/language packs and multi-speaker experiments. These are pending gates,
not a declaration of complete realtime, regional-voice or dubbing support.

2026-10-07 live-ASR corrective slice: rolling recognition/boundary context, bounded
clause assembly, live utterance retention with explicit backpressure, next-wave synthesis
during playback and compact audio-loss counters. Host compilation, 40 WPF scheduler
checks and 30 core tests pass. See live-asr-fix-validation.md for final ASR measurements
and rollout limits; this is not whole-YouTube realtime/semantic-quality acceptance.

External desktop implementation 2026-10-07: real no-player Windows host, direct Chrome companion
and native broker, selected-window OCR including local Vietnamese Latin model, continuous
selected-process ASR capture + Whisper Small, real JP→EN→VI→Turbo 48k sample, 25 regional presets.
See external-desktop.md and full-desktop-validation.md. This supersedes the earlier source-only
selector slice, not its historical evidence. Chrome installation smoke, every-game graphics,
speaker-separated dubbing, portable release and signed automatic updates remain pending.

Source/voice slice implemented and tested (2026-10-07): read-only Auto/manual window selection
and 11 installed North/South male/female preset controls reaching real synthesis. Selection
is not yet an external-media input adapter; Central voice, universal multilingual pronunciation
and captured-app narration are pending. See source-voice-validation.md for bounded tests,
independent review and explicit limitations.

| Phase | Status | Deliverable and acceptance |
|---|---|---|
| 0: design/workspace | Done: documents and folders | ownership, protocol draft, explicit implementation status; AniBox hooks removed |
| Research snapshot 2026-10-06 | Done: static source/document review only | sheet corpus screened; selected code/license findings and conditional shortlist in research/README.md; no engine acceptance implied |
| 1: portability/contracts | Partial: Node fixture + independent Android Java test host | bounded DTO/version/negative fixtures; private Messenger is not shared semantic protocol interop; production core/provider choice pending |
| 2: standalone fake runtime | Implemented/tested no-model subset; independent review pending | direct cue → fake translation → virtual speech and lifecycle/backpressure regressions run in Node; no real output/transport/AniBox dependency |
| 3: model-manager | Partial: Windows pinned installer | two explicit approved packs, complete artifact/digest verification + atomic install; corrupt/missing-manifest checks. Production leases/storage/OOM/repair pending |
| 3b: model update lifecycle | Pending | signed catalog, compatibility gates, immutable versions, staged promotion/new-session leases and rollback fixtures; no arbitrary code updates |
| 4: direct-text providers | Partial: real Windows CPU sample passed | Marian EN→VI + VieNeu Nano pinned, three synthetic translations + real WAV and process/UI smoke; representative quality, memory and p95 gates pending |
| 5: Android APK/service | Partial: major 1.2 AI narration (sherpa-onnx, on-device ML Kit translation, vi/en voices) + system-TTS test; hardware pending | 4,296 Java assertions, debug build/lint pass as recorded in Android README; AI/model manager, production settings/transport and hardware voice/resource acceptance pending; no AIDL yet |
| 6: OCR/ASR fallback | Partial: real Windows selected-input adapters | continuous process-only capture, VI/EN OCR, Whisper Small; synthetic source isolation and approved JP sample pass; capture/quality matrix pending |
| 7: AniBox addon | Client work delegated by user to Claude | AniSub supplies documented versioned contract; current major 1 test is separate from planned v2; addon/runtime compatibility and device voice/ducking evidence remain distinct |
| Windows product host | Implemented prototype; not packaged production | WPF player/SRT/VTT/overlay/controls, offline worker, revision guard and WAV ducking; 9 assertions + real player/AI smoke passed; codecs/long-video/package gates pending |
| Chrome companion | Implemented source and broker; installed-browser smoke pending | explicit main-frame TextTrack VI/EN, real Windows broker/AI pipe smoke, permission/privacy/lifecycle fixtures; no universal-site claim |
| Realtime narration/dubbing | Partial: preset narration from direct cues | short-waveform RTF measured, output requested and paused/duck restored; no acoustic latency/long-video realtime/ASR/multi-speaker/lip-sync claim |

Sync007 implemented/tested: bounded prefetch/pre-open, complete-line optional video holds,
full-wave atempo<=1.35,16-step Nano, voice-only preparation, leases and smooth ducking.
Evidence:9 parser +20 timeline/ramp +16 actual async fixture +30 existing Node assertions;
real AI smoke, first90s movie segment and final-source >60s re-test through former252s fault
region pass. Earlier fault's exact cause is unproven; whole-film/human quality remains pending.
See narration-sync-validation.md.

2026-10-07 real-film narration slice: added explicit Vietnamese-subtitle session launcher
(`Narrate-Slime.cmd` / `Start-Narration.ps1`) and AppWindow start method. Uses selected embedded
SubRip track 4 (362 cues), VI→VI/no translation. A separate Japanese-only stream-copy cache
is needed because original MKV defaults to Vietnamese audio and WPF has no audio-track selector.
Original media unchanged; this is not general automatic embedded-track integration or full-film
quality acceptance. Launch execution evidence is recorded in narration-session-validation.md.

Windows fixture executes phase-2 coordination semantics; a separate Android test companion now
exists, while shared production portability and real AI-provider acceptance remain open.
A fake provider validates coordination and cannot
establish real recognition/translation/voice quality or realtime performance. No phase is marked
complete solely because its class names exist. Commit/push/release and remote repo creation have
not occurred. Host/device evidence is recorded separately.

2026-10-07: Windows manual content profiles and request-scoped, source-gated terminology are implemented; see content-policy.md. Contextual translation, game dialogue/HUD classification and end-to-end semantic quality remain pending. Console/controller work is excluded. See content-policy-validation.md for current test evidence.

Prioritize Android direct-text Vietnamese narration/model lifecycle now; preserve the existing
Windows prototype and Chrome companion without claiming Android parity. Contextual translation,
captured-media fallback and multi-speaker dubbing follow measured provider/device acceptance.
Prepare the AniSub contract for Claude; do not implement or modify the AniBox addon here. Performance and
package-size budgets are proposed gates, never completed features. Model freshness is evaluated
without sacrificing known-good stability, resource quotas or an active video's deadlines.
