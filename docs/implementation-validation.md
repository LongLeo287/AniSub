# ANISUB-IMPL-004 validation — 2026-10-07

Outcome: standalone dependency-free no-model Node runtime and Windows playback simulator run.
Not a GUI app, AI engine, audible speech implementation, browser/Android integration or release.

## Evidence

- Existing Node v22.22.3; no dependency installation needed.
- node scripts/check.mjs: PASS, ten modules; explicit roots exclude reference/ and engines.
- node scripts/test.mjs: PASS, 30 tests, 0 failed/cancelled/skipped.
- node apps/desktop/harness/demo.mjs: PASS, ten events, eight accepted commands, zero rejected;
  closed session with zero outstanding jobs/waiting speech/active speech/active cues.
- Contract coverage: immutable owned DTOs; UTF-16 and UTF-8 serialized bounds; invalid numeric/
  JSON/identity/major/minor/sequence inputs; direct-only capability.
- Lifecycle coverage: virtual STARTED/FINISHED pairing; pause/seek/stop/speed/track/source/episode,
  close idempotence, reconnect ids/counters, future/expired cues, uncooperative late replies,
  bounded jobs/backlog, retry after admission pressure and reentrant callback STOP/close.
- Root fixed same-revision close rejection, stale timer ownership, callback ordering, disabled
  track admission, OPEN sequence, descriptor checks and effective five-second output horizon.

Independent code review: **pending**, not waived as passing. Specialist stopped on workspace
credit exhaustion; no agent retry. Root reviewed and added bounded regression tests. Previous
design review does not establish independent implementation review.

## File manifest

New executable files:

- package.json
- scripts/check.mjs; scripts/test.mjs
- protocol/src/index.mjs
- core/src/runtime.mjs
- providers/fake/src/index.mjs
- apps/desktop/harness/playback-simulator.mjs; apps/desktop/harness/demo.mjs
- tests/contract/protocol.test.mjs
- tests/integration/runtime.test.mjs; tests/integration/regressions.test.mjs

New docs: docs/implementation-task-packet.md, docs/windows-harness.md, this record,
providers/fake/README.md. Updated docs: README.md, docs/system-design.md, docs/file-map.md,
docs/protocol.md, docs/roadmap.md, apps/desktop/README.md and core/README.md.

## Limitations, boundary and rollback

Virtual time/audio is not realtime/device evidence. No Android build/emulator/hardware tests,
production transport/schema, model manager/updates/leases, GUI, captured media, real engines,
PCM queue or speaker latency exists. Phase 1 production language/Android portability remains open.
No AniBox edits, model download, network service, capture, installer or Git/remote mutation.
AniSub remains a local workspace without Git/branch. Latest read-only AniBox observation:
clean on main at a742daf9a8a1e4c769b6e21b55d9b0b96712d619, different from the historical
task baseline. This task did not checkout, reset, commit or edit AniBox; preserve its current state.

Rollback: remove only the executable/doc additions listed here and revert this task's specific
documentation paragraphs; preserve research/reference/scaffold. No deletion was performed.
Next acceptance: independent code review when available, then portability/profile validation and
a Windows direct-text host. Real provider adoption requires separate artifact/license/device gates.
