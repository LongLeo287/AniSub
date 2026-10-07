# Narration synchronization fix — 2026-10-07

User reproduction: Vietnamese text with Japanese audio was read late, missing or cut off at
subtitle transition, and original-audio volume changed abruptly. No source movie/AniBox edits.

## Diagnosis and implemented changes

Old host only synthesized active cues, overwrote pending cues with newest text, and stopped
accepted audio whenever the caption key/expiry changed. Model replacement alone cannot fix this.

- New `NarrationCoordinator.cs` separates visible captions from speech ownership. Prefetch and
  pre-open at most 6 clips in a 12-second media window; one serialized inference/release pump.
- Model remains pinned VieNeu Nano (no download/switch). Changed synthesis from 8 to16 steps;
  this changes sampling quality settings, not a verified human listening score. Voice-only prepare
  avoids loading Marian/Torch when reading existing Vietnamese text.
- Fit full generated WAV with pitch-preserving FFmpeg atempo speed-up <=1.35; never truncate
  text or waveform just to meet the slot. MediaPlayer follows selected video playback speed.
- Default visible `Read complete / sync wait` mode: if a phrase extends past the next cue or a
  due clip isn't ready, hold the video clock until narration can continue. Bound each hold to10s;
  show/pause on errors, do not silently discard dialogue. Optional realtime mode counts drops.
  Complete-line mode trades brief video waits for complete phrases; not zero-latency/lip-sync.
- Caption clear/change/end no longer cancels accepted output. Pause/seek/source/settings/close
  explicitly retire it; cancel fade60ms; video duck attack150ms/release350ms, exact baseline.
  EOF stops new admission and lets already accepted speech finish.
- WAV leases: max16, explicit release, no eviction while buffered/playing, parent/worker-owned
  cleanup retained. Millisecond waveform-edge fades reduce clicks. Current global quota admission
  reserves room for a16-clip session; not an atomic multi-process disk-quota implementation.
- Reviewer fixes: hold/play ordering, releasing post-fault/stale synth replies, stale output-failure
  callbacks, failure volume restoration/terminal accounting, preparation drain, revalidate admission
  after asynchronous release/EOF. Errors now include redacted errorCode/faulted flags.

## Test plan and actual evidence

| Area | Test and result | Bound of claim |
|---|---|---|
| Parser + window lifecycle | existing Windows **9/9 PASS** | no audio-quality evidence |
| Fake-clock timeline/ramp | **20/20 PASS** | helper predicates/ramp, not async coordinator alone |
| Actual coordinator async state | **16/16 PASS**, real Dispatcher + MediaPlayer, synthetic WAV worker | expiry/overlap, EOF admission + full active completion, duck restoration, stale inference after pause, no auto-resume, disabling held mode, provider failure; not AI quality |
| Real model/tempo/leases | speech-timing-tests PASS | voice-only load1154ms; complete2.630s waveform becomes1.959s at1.35; generation773ms; non-silent finite24kHz, 16steps, fades, cap/foreign release verified |
| Real text timing sample | first24 VN cues, zero model failures | median534ms/max1130ms generation; 3 longer-than-slot phrases, max638ms/total1513ms overrun demonstrate need for sync-wait |
| Real text second region | ten cues >=245s, zero model failures | median795ms/max2001ms generation, one overrun355ms; not a whole-film score |
| Persistent bridge | UTF8 + real probe/translate/synthesis PASS | model data/inference local, no cloud |
| Original Node coordination | **30/30 PASS**, syntax10modules PASS | existing fake runtime unchanged |
| Actual WPF AI smoke | PASS MediaOpened/play/pause/seek/speed + EN→VI overlay +WAV; starts2/finishes2 | startup test now primes audio before Play; pause test waits450ms for smooth restore; no physical hearing assertion |
| Actual movie first region | 90s media, starts33/completed33; cancelled0/drops0/errors0; max scheduled lateness5ms; held248ms | actual VN cues, no synthetic injection; output request timestamps, not acoustic measurement |
| Final-source repeated region | from245s through309.755s media: starts10/completed9/one active; cancelled0/failed0/drops0/errors0; scheduled lateMax0ms, held200ms, buffered3 | passed >60s across former252s failure region; PID10836 left open; does not prove whole-film stability |

Later final-source snapshot through349.429s media (~104s from selected start): starts21 /
completed21, cancelled0 /failed0 /drops0 /errors0, scheduled lateMax8ms, held547ms total /
max single hold346ms. Window responsive and intentionally left open for the user.

The initial long-running host later faulted around252s (after62 completed phrases). Direct TTS
tests of that region succeeded; the old process lacked detailed failure diagnostics, so its exact
cause cannot be retrospectively established. Do NOT claim the above first90s proves the whole
movie. Final source adds detailed redacted error status and passed >60s through that region; live
readiness is `work/narration-ready.txt`, correlated with processId, not stale prior-session text.
Final root also reran16 actual coordinator assertions after the post-release EOF admission guard.
Independent reviewer ran15 of the actual fixture assertions before the extra EOF-error assertion,
and verified the other source ownership fixes; their final remaining admission guard is now applied.

## Changed source / rollback

Updated App.cs, TranslationBridge.cs, windows_worker.py, Start-Narration.ps1; new coordinator,
narration-sync-tests.ps1, CoordinatorFixtureTests.cs/coordinator-fixture-tests.ps1,
sync-fixture-worker.py, speech-timing-tests.py, real-cue-timing.py, task packet and this report.
README/architecture/file-map/roadmap describe the new optional Windows policy. Local session config
resumes near245s for the repeated failure-region test; media/cache/model data are unchanged.

Close only the task-owned player before rollback; restore this scoped source/docs or disable
new complete-line mode (returns realtime behavior with visible drops). No Git/branch/push/release.
Still pending: full-episode stress, human listening/phoneme quality, acoustic/video onset alignment,
better phrase grouping and production audio-track selection. No claim every subtitle is dialogue.
