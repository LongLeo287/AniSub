# Windows prototype validation — 2026-10-07

Outcome: real standalone Windows vertical slice works with explicitly approved offline models.
AniBox untouched by this task; final read-only check shows clean `main` at
`f25a4507d20b7ca852e026265d5f078a905145f0`. Earlier observed baseline was
`a742daf9a8a1e4c769b6e21b55d9b0b96712d619`; checkout advanced externally during this task,
and no checkout/reset/commit was performed here.
AniSub is a separate local folder without Git/branch/remote; no commit/push/release.

## Actual checks

- .NET Framework C# compilation under Windows PowerShell 5.1: passes. Source compiled at launch;
  this is not a packaged executable/release build.
- Windows parser/lifecycle: **9/9** assertions pass.
- Existing Node fake-runtime suites: **30/30** pass; syntax: **10** modules pass, excludes reference.
- Real model test: three synthetic EN→VI translations nonempty and different from English;
  Vietnamese WAV 24 kHz, finite/non-silent samples and text bounds pass.
- Latest recorded sample: model preparation **11,114.67 ms**; translation **93.28 / 95.60 /
  95.71 ms**. Short narration generation **293.26 ms**, audio **1.39 s**, **RTF 0.211**.
  Measurements vary: another bridge run translation **200.76 ms**, speech **488.99 ms**.
  Synthetic single-run timing, not p50/p95, acoustic latency or video-level realtime guarantee.
- Persistent Python wire: probe/translate/synthesize/correlation/graceful exit passes.
- C# bridge: exact accented Vietnamese UTF-8 round-trip regression plus **real**
  probe/translation/synthesis passes. Echo fixture is wire-only and not counted as AI.
- Integrity: omitted artifact set rejected; complete installed packs rehashed successfully;
  reinstall detection verifies actual files before declaring already installed.
- Latest full WPF smoke, actual 15 s synthetic H.264/AAC MP4: **PASS MediaOpened/play/pause/
  seek/speed; real EN→VI overlay and Vietnamese WAV output playback; speech starts=1,
  finishes=1**. Pause stops output and restores previous media volume. No acoustic hearing claim.

## Review and fixes

Independent read-only review found UTF-8 stdin corruption, incomplete manifest validation,
unbounded diagnostic/line buffers, persistent speech files, ignored translation opt-in and
incorrect volume restoration. Fixed with explicit UTF-8 writer; required file set/containment;
capped reader/discard sink; normal + parent-owned kill cleanup and global cache quota;
explicit EN translation requirement; captured pre-duck volume. Cue expiry checks reject results
even between UI timer ticks. Model selection/license statements are not a distribution audit.
Independent bounded re-review of the updated source confirmed these findings addressed and
found no serious blocker. Reviewer did not independently run model/WPF tests; execution evidence
above comes from root integration checks and the scoped Windows implementer.

## Changed files in this task

- New: root `.gitignore`, `Start-AniSub.cmd`.
- New: `apps/desktop/windows/{App.cs,TranslationBridge.cs,Start-AniSub.ps1,Run-AniSub.ps1}`.
- New: `providers/translation/windows_worker.py`, `model-manager/tools/install_windows_models.py`.
- New: `tests/windows/{app-tests.ps1,smoke.ps1,provider-tests.py,wire-tests.py,bridge-tests.ps1,
  echo_worker.py,integrity-tests.py}`; `tests/fixtures/windows-demo.srt`;
  `scripts/make-windows-demo.ps1`.
- New: `docs/{windows-app-task-packet.md,adr-001-windows-native-prototype.md,windows-app.md,
  windows-app-validation.md}`.
- Updated: `README.md`, `docs/{system-design.md,file-map.md,roadmap.md}`.
- Local ignored assets: two model bundles/manifest/readmes, Python wheel target, local Python
  configuration, synthetic MP4, test JSON + synthetic WAV. Not source/release payload.

## Remaining gates / rollback

Python environment is reused read-only; wheel additions are isolated in AniSub/work. Not portable
or optimized package size. EN→VI only, Nano preview/preset/full-utterance, no speech time-stretch,
no prefetch, no human listening grade. Unknown codecs/long files/memory/deadline/drop rates require
real video tests. No Chrome/Android/AniBox/ASR/OCR/auto model updates. Signed catalogs, update
leases/rollback and packaging remain pending; no broad completion claim.

Rollback: close app, remove only this task's listed new files and restore these documentation
edits. Optional model/work cleanup must target explicit AniSub directories after preserving
wanted evidence; never AniBox, Python environment or unrelated user data.
