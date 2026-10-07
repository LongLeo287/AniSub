# AniSub standalone runtime — System Design

Date: 2026-10-07. Status: user-approved design; not implemented production API.
User approved the written spec with “ok làm đi”. Next: review the
[M1 implementation plan](../plans/2026-10-07-android-m1.md) before source execution.
This is the next-stage design referenced by [canonical system design](../../system-design.md).
[Addon contract](../../android-addon-contract.md) supplies the matching client-facing draft.
Historical protocol-v1 fixture and Android private major-1 test remain separate.

## 1. Product brief and success

AniSub is an optional local AI runtime for video subtitles, translation and narration. AniBox
addon is owned by Claude; this work changes only AniSub. Install AniSub APK separately, bind
from a cooperating client, choose/download a compatible voice pack once, then operate offline.
No mandatory server, no model in AniBox, no replacement of existing dubbed/narrated editions.
Windows background app remains supported; Chrome is a thin companion to that Windows host.
Android does not depend on Windows. Shared behavior means conformance fixtures, not shared binaries.

Defaults: Vietnamese narration, direct subtitle input, automatic selection only among installed,
verified compatible voices. User may choose quality/device profiles and optional language packs.
Male/female and North/Central/South filters show only verified voice metadata; unknown stays unknown.
Foreign-language text pronunciation requires model support; Vietnamese TTS is not a translator.
Auto downloading during first playback is prohibited: show pack size/license and obtain consent first.

Narration overlays a selected voice and permits client-owned whole-track ducking. Multi-speaker
dubbing is a later feature requiring speaker attribution and suitable voices. Neither implies
lip sync or removal of original dialogue while preserving music/effects. Decreasing the player's
track volume decreases every sound in that track; the original file is never rewritten.

## 2. Existing evidence versus new work

| Surface | Existing state | Not established |
|---|---|---|
| Windows | WPF/Python local providers, subtitle/capture prototypes; existing validation documents | Production packaging, universal capture, contextual translation quality |
| Node | No-model protocol/core conformance harness | Production shared core or audible output |
| Android | Java bound service, private Messenger major 1, same-signer trust, explicit system-TTS test | AI engine, model manager, translation, OCR/ASR, AIDL |
| AniBox | Earlier optional integration work lives outside this task | Claude's new addon delivery or hardware acceptance |

Android details are grounded in [current companion README](../../../apps/android/README.md).
Prior tests are historical evidence, not freshly rerun here. Source/build/emulator/hardware evidence
must remain distinct. Android minSdk 23 is an app target, not proof an AI provider works on API 23.

## 3. Architecture choice and trade-offs

Chosen: native Android Java orchestration plus a replaceable speech adapter/JNI backend. Evaluate
sherpa-onnx/Piper Vietnamese first. JNI/library payload belongs in AniSub APK, model data outside it.
This offers an Android path but adds ABI payload and native-crash risk; measured packaging is required.
Alternative custom VieNeu ONNX integration may improve voice quality but adds tokenizer/frontend/
codec and graph-compatibility work. Retain as a later provider, not presumed sherpa-compatible.
Alternative Windows-assisted TV inference reduces Android load but requires a running second device
and network; excluded from baseline because user chose a local AniSub APK.

Primary references checked 2026-10-07:
[sherpa Android build](https://k2-fsa.github.io/sherpa/onnx/android/build-sherpa-onnx.html),
[Vietnamese TTS candidates](https://k2-fsa.github.io/sherpa/onnx/tts/all/Vietnamese/index.html),
[Android bound services](https://developer.android.com/develop/background-work/services/bound-services).
These establish candidate/platform paths, not AniSub size, speed, quality or voice licenses.
Exact native release, artifact digests, individual voice licenses and benchmark winner are promotion
gates during M1, not unspecified production defaults. If no candidate passes, AI remains unavailable.

```text
AniBox addon (Claude)                    Windows app / Chrome companion
  cues + media clock + controls               permitted text/audio/image
             |                                           |
   authenticated Binder                         existing local host bridge
             |                                           |
 Android transport host                   Windows orchestration host
             |                                           |
 session -> input routing -> normalization -> translation -> speech scheduler
                                                            |
                      verified model leases -> provider -> audio output
                                                            |
                         speech lifecycle / errors / metrics -> client
```

Client owns player, selected subtitle track, source provenance, clock, D-pad/UI, ducking and
capture permissions. Runtime owns inference, model storage and output lifecycle. No catalog,
stream URL, HTTP headers, playback credentials or AniBox database enters the runtime.

## 4. File and folder ownership

Existing root folders are preserved. New Android packages below are proposed, not created here.
Avoid a new cross-platform C++ coordinator or copying Python into Android for M1.

```text
AniSub/
  docs/                         canonical decisions, contracts, evidence, tasks
  protocol/                     shared semantic fixtures, not Android executable code
  core/                         existing Node conformance harness
  providers/                    existing Windows adapters; engine research
  model-manager/                manifest conventions and existing Windows installers
  clients/                      thin clients; Claude owns AniBox product integration
  apps/android/app/src/main/java/com/anisub/runtime/
    AniSubService.java           compose delegates; no inference on Binder/main thread
    transport/                  WireCodec, CallerVerifier, PeerConnection, RequestRouter
    protocol/                   bounded DTOs, major negotiation, compatibility rules
    session/                    SessionCoordinator, RevisionGuard, MediaClock
    cues/                       CueNormalizer, CueTimeline, CueDeduplicator
    speech/                     SpeechScheduler, SpeechLedger, PcmOutput, RatePolicy
    providers/                  SpeechProvider interface, SherpaSpeechProvider adapter
    models/                     Manifest, Registry, Downloader, Verifier, Store, LoadLease
    settings/                   SettingsStore, SettingsValidator, CapabilitySnapshot
    ui/                         device status, models, voice selection, local test controls
  apps/android/tests/           pure logic and contract tests
  apps/android/app/src/androidTest/  Binder, AudioTrack, lifecycle and device fixtures
  apps/desktop/                 existing independent Windows host
  tests/fixtures/               common clock/cancellation/input sequences
  tests/performance/            redacted reports per device/backend/model
```

Host composes ports. Session/scheduling never imports engine implementation. Provider receives
text/language/voice/rate plus cancellation token and verified lease; returns PCM metadata/chunks
or a bounded full utterance, declaring which. No provider chooses capture mode or changes player.
M1 has one inference worker and one serial control executor; downloader is separate and suspended
while playback is active by default. Audio output uses a bounded dedicated writer, not UI callbacks.
Do not claim PCM streaming when backend only returns a completed waveform.

## 5. Input, context and terminology

Direct text first: client declares TEXT_TRACK, BITMAP_TRACK, BURNED_IN, NONE or UNKNOWN availability.
An empty cue snapshot is not evidence of no subtitle track. No automatic audio/image capture from
that event. Fallback selection requires availability, supported provider and explicit consent.
Only one recognition path supplies speech at a time to avoid direct/OCR/ASR duplicates.

Cues preserve original text, track, origin, language, timing certainty and role. Upper-screen
dialogue is valid. Skip explicitly classified annotations; UNKNOWN is admitted in M1 and exposed
as uncertain, not heuristically discarded by location or brackets. Client should retain stable
cue IDs across layout updates and dialogue/note changes. Runtime normalizes Unicode without
inventing missing timings or erasing repeated spoken words/negations.

M2 introduces a shared, versioned terminology library and bounded session context: 8 preceding
utterances, at most 8,192 UTF-16 units, volatile; source/episode/language change clears context.
Term records have canonical form, aliases, language pair, domain, provenance, confidence and user
override. A word with competing domain meanings cannot be globally substituted. Global user rules
are optional; automatic domain suggestion must not silently select a conflicting sense.
No mandatory manual profile. Translation provider must report context support; current Windows
sentence-glossary mode remains explicitly non-contextual. Evaluate names, pronouns, negations,
technical terms and mixed languages with human-reviewed references, not just string matching.

## 6. Sessions, clocks and cancellation

One foreground peer and one playback session in M1. Commands validated before admission. Each
async stage carries (peer generation, sessionId, revision, cueId, speechId where assigned).
Check guards at submission, inference completion, output start and callback delivery.
SEEK, PAUSE, STOP, speed/input/settings changes invalidate obsolete work. Episode/source change
closes old session and requires a new random ID. Reconnect opens a new session; never replay history.

Android clock anchors use SystemClock.elapsedRealtime in both processes on the same device/boot:
positionMs, sampledAtElapsedMs, playing, speed. Do not compare UTC or process-local Stopwatch clocks.
Client sends anchor every 1 second while playing and immediately on controls; reject anchors over
2 seconds old or from the future by more than 100 ms. While playing, after 2 seconds without a fresh anchor,
CLOCK_STALE closes admission and every unstarted output, invalidates/discards queued, prepared and
in-flight unstarted work, and allows only already-started audio to finish. Report CLOCK_STALE;
recovery requires a fresh handshake/session, never a late anchor reviving retired work.
Paused/stopped sessions have no periodic anchor requirement; PLAY carries a fresh validated anchor
and may resume the current revision. Immediate control-anchor age validation still always applies.
Windows own clock adapter must pass equivalent tests; it does not reuse Android elapsed values.

Deduplicate by cue ID within revision, with 512 recent IDs and bounded horizon. Resume after an
interruption needs a current snapshot and explicit new speech decision; never silently replay all
retired cues. Cancellation need not interrupt native inference: discard-only providers may finish,
but retired results cannot speak. Native crash disconnects service; client restores volume and
keeps normal playback. Do not automatically rebind/reload in an infinite loop.

## 7. Scheduling, rate and audio policy

M1 default is LIVE_BOUNDED: video continues; future synthesis requires client-provided timed cues,
not merely onCues active text. Distinguish active snapshots from optional TIMELINE_BATCH prefetch.
Without lookahead, first-audio latency cannot be hidden. Known-expired unstarted cues are rejected
with reasons/counters. Unknown ends remain unknown. Once audio starts, complete the phrase unless
user control, disconnect or failure invalidates it; caption clearing alone does not cut it off.
Unknown starts in active snapshots become immediately eligible at the validated observation clock,
without rewriting original timing as a known cue onset. Unknown-start cues cannot enter lookahead.

Budget: 8 pending cues, one in-flight synthesis, two prepared utterances including active output,
5-second future admission horizon. Each full waveform is capped at 30 seconds and 8 MiB; combined
prepared PCM cap 16 MiB. Overflow returns BACKPRESSURE with affected cue IDs; never silently lose
accepted dialogue. Long cues are split at punctuation into stable segments preserving text order.
No claim of all sentences retained if speech duration persistently exceeds available media time.

Rate auto-adjust is bounded 0.95–1.15 relative to provider natural speed, with change at phrase
boundaries only. Target known cue duration including player speed; if impossible within the bound,
report timing deficit instead of extreme acceleration/cutting. Provider must validate rate support;
no unsupported time-stretch approximation advertised as natural speech. Unknown end uses natural rate.

READ_COMPLETE is an optional later negotiated client-owned hold policy; runtime cannot pause video
unilaterally. Client must explicitly advertise hold/resume and consent before it is enabled.
M1 does not offer speaker-separated dubbing or promise READ_COMPLETE for arbitrary external apps.

PCM output belongs to AniSub; whole-track ducking belongs to client. M1 uses media/speech attributes
and coordinated no-exclusive-focus policy, matching current test behavior; no competing GAIN request.
STARTED means first PCM submitted to actual output, not job submission; output latency is reported
separately and acoustic timing requires measurement. Pair each accepted STARTED with one FINISHED.
Client finalizes its own speech ledger/ducking on disconnect; stale terminal callback may close only
its exact old entry. Fade target 80 ms in/180 ms out; actual perceptual quality requires device test.

## 8. Resource limits and reliability

| Resource | M1 bound / behavior |
|---|---|
| IPC | 16,384 UTF-16 units and 64 KiB marshalled Bundle; reject either violation |
| Cue batch | 16 cues, 512 UTF-16 units/text, IDs 80 units; long-text segmentation explicit |
| Admission | 8 data jobs; separate 8 control jobs; full control lane disconnects safely |
| Requests | 16 pending management requests/peer; 5-second acknowledgement timeout |
| Downloads | one active, 2 pending; management operation asynchronous with progress |
| Session replay guard | 256 retired IDs per service lifetime; require service reset when exhausted |
| Installed data | default 1 GiB quota, user may raise to 4 GiB after storage check |
| Temporary data | counted within quota; reserve incoming expanded size + 128 MiB free disk |
| Provider residency | one speech model in M1; complete pipeline admission required in M2/M3 |
| Model prepare | 60-second deadline; late initialization results discarded; explicit retry required |
| Retries | transient download: at most 2 retries; engine init once until explicit retry |

Control priority applies between short work items, not an unbounded native call on the serial
executor. CLOCK_ANCHOR may coalesce; SEEK/STOP/CLOSE never coalesce away. Bounds are enforceable
design limits, not performance measurements. Watchdog uses declared maximum utterance duration
plus 5 seconds, capped at 35 seconds. Idle models unload after 60 seconds without an active lease.
Unload transitions READY -> INSTALLED with MODEL_STATE and refreshed capabilities. Client must
PREPARE_MODEL again before OPEN; readiness checks/lease acquisition are atomic to avoid races.
Only one model may be READY in M1. Preparing a different model returns MODEL_IN_USE while the
resident model is leased; otherwise unload old -> INSTALLED, then prepare new. No hidden eviction
of a live session. Prepare timeout quarantines late results rather than publishing READY.

Service stays bound during client playback. Model-download UI must provide visible progress,
cancellation and compliant Android background-work behavior; do not rely on an indefinitely running
hidden service. No boot auto-start/capture. Cold start exposes INITIALIZING/MODEL_MISSING, not ready.

## 9. Models, voices, updates and licensing

Manifest fields: schemaVersion, id/version/task/languages, provider ID/API range, native ABI/API
compatibility, upstream immutable revision, licenses for every asset, file relative paths, bytes,
SHA-256, expanded bytes, sample rate, speaker metadata, required frontend/tokenizer assets,
measured device class/resident-memory envelope and reviewed quality status.
Gender/accent labels require provenance and human review. License failure blocks catalog promotion.

M1 catalog is reviewed data packaged with the app. Users download by catalog ID, never arbitrary
URL/Hub repository/code. HTTPS allowlisted immutable artifact URLs; validate redirects/length/digest.
Extraction rejects absolute paths, traversal, symlinks, duplicate entries and archive expansion
above manifest bounds. Download .part -> verify -> compatibility smoke -> atomic installed version.
No partial directory is READY. Cancel preserves usable versions; resumptions verify ETag/version and
rehash full artifacts. Startup removes only identified orphan staging entries, never arbitrary folders.

Store models and settings in AniSub app-private storage. Models are immutable version directories;
registry promotion is transactional. Active sessions pin leases. Updates activate only for new
sessions. Keep last-known-good plus selected candidate within quota; pinned versions never evicted.
Removal of active pack is denied. Settings persist references/revisions, never live speech sessions.
No native .so, executable, Python code or remote model code can arrive disguised as model data.

M4 remote freshness: opt-in daily idle catalog check, manual download by default. Automatic remote
catalog acceptance requires signed metadata, expiry/version checks, trusted keys and rotation tests
before enabling. Offline APK-bundled catalog remains usable without online checks. Compatibility
or license changes cannot silently replace a working voice. Frequent upstream releases do not mean
frequent unreviewed production updates.

## 10. IPC, trust, settings and Claude boundary

Keep Messenger for M1; add explicitly negotiated major 2, never reinterpret major-1 systemTest as AI.
Use [draft contract](../../android-addon-contract.md) for command groups, DTOs and limits. AIDL may
replace serialization later only behind compatibility tests; Binder transport does not require AIDL.

M1 retains signature permission plus UID/package verification, one authenticated callback binder,
and explicit service binding. Production APKs must be signed under coordinated release identity.
Do not copy/export private signing keys to Claude. If independently signed clients are required,
design user-approved certificate trust separately; do not weaken permission checks as a shortcut.
Verify sender before Bundle decode and service signer on the client before bind.

AniSub stores canonical engine/model/voice settings; client may render negotiated settings controls.
SET_SETTINGS uses expectedSettingsRevision and atomic validation; conflicts return current revision.
Active settings changes require session invalidation/new OPEN so old inference cannot leak. Runtime
settings omit AniBox audio/player preferences. Addon shows installed/missing/incompatible/model-ready
states truthfully and does not offer absent region/voice choices. Default Auto requires no profile.

## 11. Privacy and observability

Only settings, catalog, model registry and bounded diagnostic counters persist by default. Subtitle,
image/audio and context are volatile; no raw content in logs/errors. Result cache disabled by default;
opt-in cache maximum 32 MiB, keyed by text/language/provider/model/voice/context-policy digest.
Diagnostic export needs consent and redaction. No silent cloud translation or capture fallback.
Counters distinguish unsupported input, stale result, expired cue, backpressure, timing deficit and
provider failure. Record cold/warm load, first PCM, first output submission, queue wait, RTF, memory
and duration percentiles with model/device identity but no content.

## 12. Delivery milestones and acceptance

This document defines overall boundaries; only M1 becomes the next implementation plan.
M2–M4 each need their own focused design and evidence before completion is claimed.

| Milestone | Deliverable | Required gate |
|---|---|---|
| M1 | Android VI AI narration, bounded model manager, device UI, major-2 contract | Real local PCM; download corruption/rollback; lifecycle/IPC; per-ABI size and hardware benchmark |
| M2 | Context-aware translation and shared terminology | Explicit language pairs/context capability; human-reviewed mixed-domain corpus; pipeline memory/deadlines |
| M3 | Consented OCR/ASR for cooperating inputs | Track absence routing; permissions/protected-content limits; annotated recognition/dedup quality |
| M4 | Additional languages/voices, signed catalog updates, experimental dubbing | Per-voice licensing/accent review; key/expiry tests; speaker attribution and measured quality |

M1 acceptance tests: major mismatch/malformed/oversize/untrusted caller; duplicate/out-of-order
sequences; pause/seek/speed/source changes during native inference; Binder death; stale output and
terminal pairing; 2,000-cue session; rejected/expired cues visible; settings CAS; download cancel,
partial install, corruption, malicious archive, disk full, active lease and rollback.

M1 performance *targets*, not promises: stripped per-ABI compressed APK <=30 MiB, installed base
<=80 MiB excluding models; standard VI data <=100 MiB including frontend; warm p95 synthesis RTF
<=0.7; known-lookahead start deviation p95 <=250 ms; no-lookahead p95 first submitted output <=1.5 s
for <=120-character cues; additional runtime PSS <=300 MiB on nominated 2 GiB physical TV Box.
Count all excluded/failed cases separately. If targets fail, report measured limits and do not mark
that device/profile realtime-ready. High-quality packs have separately disclosed budgets.

Measure 200 representative Vietnamese/mixed-name utterances, 30-minute video soak, cold/warm
trials and thermal degradation. Human listens for clarity, omitted words, regions and rate changes.
Emulator checks IPC/UI only; ARM64 and ARMv7 physical devices, P650/Android 6 remain hardware gates.
No size or performance target can be relaxed silently to call a phase complete.

## 13. Review, rollout and rollback

Design self-review: ownership, bounds, version distinction, privacy, cancellation, update policy
and acceptance are explicit. Native release/model promotion is a deliberate measured M1 decision,
not a pending placeholder in the wire contract. Written design is user-approved; implementation
plan needs review/execution confirmation. No product code or model installation in this stage.
Root integrates specialist implementation and independent read-only review per AGENTS.md.

Keep legacy major-1 test disabled by default and available for contract regression. Roll out M1
behind explicit AI enablement, unavailable if model/provider gates fail. Runtime disconnect cannot
stop original playback. Uninstalling AniSub removes its private data; uninstall/download deletion
requires clear user action. No signing/release/push authorized. Claude receives the contract document,
not a claim that major 2 is usable today.
