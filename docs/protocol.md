# AniSub semantic protocol draft v1

Status: proposed semantic contract. No AIDL or interoperable implementation of this semantic
draft exists yet. A separate authenticated Android Messenger major 1 test transport exists;
its version number does not establish wire compatibility with this draft.
The historical Java sketch uses v0 and must not be considered wire-compatible with this design.

2026-10-07: protocol/src/index.mjs validates an in-process Node fixture subset. OPEN/SNAPSHOT/
PLAYBACK/INPUT_CHANGED/CLOSE abbreviate semantic messages; there is no production schema,
Binder mapping, authenticated transport or cross-process clock negotiation. Optional identity
fields can be omitted by fixtures, but future wire implementations must finalize required fields.

## Current Android private transport versus next contract

The current `apps/android/` companion implements test-only Messenger (`what=1`, Bundle string
`payload`, maximum 16,384 UTF-16 units). It requires exact `com.anibox.tv` caller identity and a
matching signing certificate, checks caller UID before reading the Bundle, and uses the signature
permission `com.anisub.runtime.BIND`. HELLO major 1 and a live reply Messenger precede OPEN.
The only speech mode is explicit `systemTest:true`, language `vi`, with an installed voice that
reports no network requirement. CAPABILITIES declares `aiVoice:false`, `translation:false` and
`multiSpeaker:false`; readiness is not a real-device offline-quality guarantee.

Its bounds are 16 cues/snapshot, 512 UTF-16 units/text, 80/id, 8 pending jobs and a 5-second future
horizon. Unknown cue end remains -1. Actual utterance callbacks echo session/revision/cueId.
Do not use the larger semantic limits below for this transport. See
[Android test companion](../apps/android/README.md) for current behavior.

[Android addon contract](android-addon-contract.md) and
[runtime design](superpowers/specs/2026-10-07-anisub-runtime-design.md) define the next draft.
**v2 is not implemented or negotiated today.** Model/settings commands and AI voices must not be
silently added to the test-only major 1 interpretation. Claude owns the AniBox addon; AniSub owns
the runtime implementation. The semantic sections below remain a design baseline, not production
SDK/AIDL or current Messenger field documentation.

## Handshake and session

Client advertises protocol major/minor, capabilities and payload budgets. Runtime responds with
selected compatible version, supported input/translation/speech modes, provider readiness and
limits. A major mismatch refuses the session. Unknown optional fields are ignored only where
documented; unknown enum values return UNSUPPORTED rather than silently defaulting.

SessionDescriptor: opaque sessionId, client-generated revision, episodeRef, sourceRef, inputMode,
selectedTextTrackRef, original/targetLanguage, settings/consent flags. Revision authority is the
client for playback/input changes; the runtime echoes it and never independently reuses it.
Each message has sessionId, revision and a strictly increasing sequence number within its direction.
Client→runtime commands and runtime→client callbacks use independent counters, starting at 1 for
each new session. Validate ordering only within the corresponding direction. Duplicate sequence
numbers are ignored; out-of-order control messages are rejected/resynchronized. Do not compare
a callback counter with a command counter. New session ids reset both counters.

## Messages

| Message | Fields and meaning |
|---|---|
| OpenSession | descriptor + negotiated settings + initial clock anchor |
| AniCueSnapshot | full active list; empty clears; input availability is separate |
| AniCue | cueId, originalText, optional derivedText, language, provenance, observedAtMediaMs, optional start/endMediaMs, confidence, origin DIRECT/OCR/ASR |
| PlaybackEvent | PLAY, PAUSE, SEEK, STOP, EPISODE_CHANGE, SOURCE_CHANGE, PLAYBACK_SPEED; media position, speed, monotonic anchor |
| InputChanged | selected track/input mode and new revision, full empty or current snapshot |
| ClockAnchor | positionMediaMs, clientMonotonicMs, playing, speed; transport maps monotonic clock domains |
| SpeechEvent | STARTED/FINISHED, speechId, sessionId, revision, finishReason COMPLETED/CANCELLED/FAILED |
| AniSubError | structured code, session/revision, recoverable flag; redacted diagnostic id |
| CloseSession | idempotent cancellation and release acknowledgement |

SEEK/PAUSE/STOP/speed/input changes increment revision before accepting new work. Source/episode
replacement closes the old session and opens a fresh one, with transition metadata. PLAY resumes
from a fresh anchor; it does not replay retired segments. Track disable cancels speech and clears
text; unrelated audio/video track changes do not invalidate subtitles.

Speech must pair accepted STARTED with one terminal FINISHED. Keep an active-speech ledger keyed
by (sessionId, revision, speechId). Reject stale STARTED and inference/cue results. A terminal
FINISHED for an already accepted ledger entry may close that exact entry once even after revision
retirement; it must never finish or alter a newer speech. On invalidation/disconnect the client
locally finalizes active entries and restores audio; later duplicate terminal callbacks are ignored.
This is the sole terminal-cleanup exception to normal session/revision callback filtering.
Client may locally release ducking
on disconnect without waiting for a callback; late replies cannot re-enable it. Control processing
has priority over inference submissions. No source URL, HTTP headers or client credentials in DTOs.

Limits: 32 cues, 4096 UTF-16 units/cue, 256 KiB total message. Validate serialized size as well as
field counts. Image/audio use a separately negotiated bounded streaming/shared-memory input port;
never attach unbounded raw media to Binder cue parcels. Capture is absent until permission/cost
tests exist. Binder calls return quickly; service queues work and replies asynchronously.

## Android production transport plan

Explicit binding to a known package/service; verify caller/service signing identities against a
configured trust policy. Exported service policy must support separately installed clients without
assuming same-signature permissions work for differently signed APKs. The current test companion
deliberately admits only same-certificate AniBox; a differently signed Claude build cannot bind
under that policy. Coordinate signing identity/trust policy without copying private keys or
weakening permissions for convenience. A broader production caller policy requires its own tests.

Use Parcelable mappings generated/maintained in transport, keeping core DTOs free of Android APIs.
Handle Binder death, unbind, service restart and callback delivery on the owner executor. Persist
settings, not live session state. Reconnection requires renegotiation and a new current snapshot.
Fixture tests must verify malformed/oversized parcels, unknown versions and unauthorized callers.

## Error codes

UNAVAILABLE, PROTOCOL_MISMATCH, UNAUTHORIZED, INPUT_DENIED, UNSUPPORTED, MODEL_MISSING,
MODEL_CORRUPT, PROVIDER_FAILED, TIMEOUT, BACKPRESSURE, DISCONNECTED, CANCELLED.
Cancellation is an expected terminal state, not a prompt to retry obsolete work.

## Windows private worker content policy

This is not an Android Binder/protocol-v1 field. Optional `contentPolicy` and the
non-mutating `validate-policy` operation are described in content-policy.md.
Replies declare sentence-glossary mode and `contextSupported:false`; profiles do
not imply contextual translation. Policy changes use existing generation guards.
