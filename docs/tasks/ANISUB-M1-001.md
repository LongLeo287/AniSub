# ANISUB-M1-001 — Android runtime execution

Date: 2026-10-07. User approved written System Design, implementation plan and specialist/reviewer execution.
Plan: docs/superpowers/plans/2026-10-07-android-m1.md. Scope: standalone AniSub; Claude owns addon.
Baseline: no .git/branch/SHA in AniSub. Existing sources copied to work/sdd/android-m1/baseline;
app build.gradle SHA256 9F3EF666D9C6AB39C1C9962020CCD23E8ADAF98ADFF8176E88626E58B3698BDD;
manifest A2B19578E836BAB41FAB454E647473B4F79FD95A7DA31B059D679F59ED0F24B9.
Existing debug APK: 35,344 bytes; B1080579F7279FD6C301C64153A94EE7F7D3FD096D235E73749CB5305E9C188D;
no native dependencies/models. Fresh baseline Node syntax check10 modules,30 tests pass; Android
assembleDebug/lintDebug succeeds (up-to-date). Prior lint warnings remain disclosed, not newly clean.

Allowed: exact Android/protocol/fixtures/evidence/doc files in Tasks1–8 of approved plan; own
work/sdd/android-m1 artifacts and model/native download staging in AniSub only. Expand file set
only with a documented root ruling preserving the approved boundaries. No arbitrary model code.
Forbidden: AniBox edits; Windows/Chrome source/model modifications; user-app restart/capture;
secrets/private keys; Git init/commit/push, release signing/publication; unknown emulator/device changes.

Acceptance: each task test-first implementation, scoped independent review; final build/lint/Node
regression, isolated Binder/UI tests, real offline PCM and per-ABI measurement. Physical TV voice,
RTF/PSS/thermal/30-minute acceptance remain hardware-pending unless measured; no invented pass.
Keep legacy v1 test distinct from v2. Native/model license/digest gate precedes catalog promotion.
Progress: work/sdd/android-m1/progress.md, task briefs/reports/review packages. Do not discard
ledger while no Git history exists. Root alone integrates and updates evidence-backed roadmap.

Rollback: retain baseline; stop/uninstall only task-owned runtime/harness on isolated test emulator;
restore only original files changed by this packet, remove only this packet's new files after
explicit target review. Do not clean models/workspace/user data wholesale. No deletion authorized now.
