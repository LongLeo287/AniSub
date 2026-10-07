# Windows no-model harness

This milestone implements a dependency-free **test runtime**, not a Windows GUI application,
AI engine, audible voice generator or production transport. Node >=22 is required. No npm
installation, model download or network service is needed. Android reuse remains unvalidated;
the Java reference sketch is not compiled or imported.

Run from the AniSub folder:

```powershell
node scripts/check.mjs
node scripts/test.mjs
node apps/desktop/harness/demo.mjs
```

Equivalent package scripts: npm run check, npm test, npm run demo. They do not install packages.
The check/test entrypoints explicitly limit their file roots so reference/ never enters a build.

## Modules and responsibilities

- protocol/src/: validated immutable in-process messages, negotiated semantic major version.
- core/src/: coordinator, client revision guards, bounded pending work and virtual output lifecycle.
- providers/fake/src/: deterministic scheduler and fake translation/synthesis for race injection.
- apps/desktop/harness/: playback simulator and finite redacted diagnostic scenario.
- tests/contract/ and tests/integration/: validation, scheduling, cancellation and queue regressions.

The client owns session/revision/command sequence. Callback sequence is independent. Providers
never control playback or increase revisions. Empty snapshots clear active cues, not input-mode
capabilities. OCR/ASR and real AI providers are unavailable in this milestone.

Virtual scheduler time is deterministic fixture time, **not a realtime benchmark**. Translation
is a synthetic transform; speech emits virtual STARTED/FINISHED. No PCM, speaker output or audio
ducking occurs. Future real output must wait for actual output acknowledgement before STARTED.
The fixture's Node API abbreviates wire message names; JSON/Binder/native transport interop is
not implemented. No cross-process trust or clock-domain mapping is established by this harness.

## Acceptance and coverage

Contract tests should reject malformed/oversized messages, unsupported modes/version and invalid
sequences/revisions without changing current state. Integration tests should cover normal output,
future start/known expiry, unknown cue bounds, pause/seek/stop/speed/input changes, snapshot clears,
late uncooperative providers, failure cleanup, bounded work and reconnect with a fresh session.

Tests must distinguish cancellation of a current operation from suppressing obsolete results;
uncooperative retired work still consumes the outstanding-job budget until settled. A result
from an earlier revision cannot create a new speech event. An active old speech is closed only
once, and terminal cleanup cannot affect a newer speech. Diagnostics contain counts, not cue text.

## Still pending

Android/Windows production language choice; real asynchronous engine threads and PCM backpressure;
model leases/update/install/security; Windows tray/overlay/player adapter; Chrome extension/native
host; Binder/AIDL; OCR/ASR/capture; translation/voice quality and hardware latency measurements.
This implementation must not be presented as passing these separate gates.

Root validation 2026-10-07: 30 contract/integration tests pass; syntax check passes for ten
modules; diagnostic demo closes with zero outstanding jobs, waiting/active speech or active cues.
Node 22.22.3 used. Independent implementation review remains pending: specialist agent exhausted
workspace credits; root completed bounded fixes and regressions without retrying another agent.
The earlier independent design review is not an independent review of this code.

Fixture limits: ids remembered for at most 1024 sessions per runtime instance, then recreate
the host fixture; scheduler's finite microtask flushing is tailored to fake providers. Real threads,
hung-engine timeout/recovery, model leases and physical audio-stop latency need separate tests.
