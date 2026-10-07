# ANISUB-IMPL-004 — no-model Windows harness

Baseline 2026-10-06: local AniSub design v0.2, no Git, executable implementation or builds.
AniBox clean baseline fae9e87d07adb0a8c6739e32152f9a31b77934cc; do not modify it.

Acceptance: executable dependency-free Node Windows harness, immutable validated protocol
objects, bounded single-session coordinator, asynchronous deterministic fake translation/speech,
clock/expiry/start scheduling, controls retiring revisions, stale result rejection, exactly-once
speech terminal cleanup, backpressure, close/reconnect cleanup and redacted diagnostic demo.
Tests cover negative validation and lifecycle races as well as normal pipeline. Fake speech
means virtual output events, not generated or audible audio. No inference/performance claim.

Ownership: specialist implements only core/src/**, protocol/src/**, providers/fake/src/**,
tests/{contract,integration}/**. Root owns package.json, scripts/**, apps/desktop/harness/**, documentation,
validation and integration fixes. Independent reviewer read-only after implementation. No agents
split overlapping files. Existing reference Java is excluded, never imported.

No installs/downloads, models, network listeners, capture, AniBox/client changes, IPC registration,
Git initialization/commit/push/release. Node harness uses existing runtime and built-in node:test;
does not select production core language or establish Android portability. Phase 1 remains partial.

Budgets: protocol 32 cues/4096 UTF-16 units/256 KiB; processing 8 outstanding jobs including
uncooperative retired jobs; 2 waiting speech segments and 5-sec media horizon; histories bounded.
Preserve original text/timing; no fabricated unknown cue bounds; session ids cannot be reused.

Rollback: remove this task's newly introduced executable files/package and restore only touched
documentation. Preserve all research/reference/scaffold files. No unrelated cleanup.

Completion evidence 2026-10-07: docs/implementation-validation.md. Root completed integration
fixes and regression cases after the specialist exhausted workspace credits. Thirty tests,
syntax gate and finite diagnostic demo pass; independent implementation review remains pending.
No model/engine/platform integration is implied by the completed fixture subset.
