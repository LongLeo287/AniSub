# AniSub realtime video product design

Status: proposed product direction, 2026-10-06; no running implementation or measured latency.
[System design](system-design.md) and [protocol](protocol.md) remain canonical. This document
narrows priorities, not transport semantics or the choice of core language.

## Product scope and modes

AniSub is a lightweight video-language companion, not a general AI assistant, media catalog,
downloader or video editor. Prioritize Vietnamese output; additional language pairs need separate
quality evidence. Offer three explicit modes:

| Mode | Behavior | Initial priority |
|---|---|---|
| Translated captions | Preserve original cues; show translated text alongside or instead | First usable product |
| Narration / thuyết minh | Read translated dialogue in a selected neutral voice; original audio remains with optional ducking | Second |
| Live dubbing / lồng tiếng | Experimental timing-aware spoken dialogue, with optional speaker mappings | Later, qualified devices |

Dubbing does not promise lip synchronization, reliable diarization, isolated dialogue replacement,
voice cloning or actor-voice identity. Removing original voices while preserving music/effects
requires another separately evaluated pipeline. Do not label ordinary TTS-over-video as that feature.
Narration reads translated dialogue, not generated plot summaries; preserve meaning and speaker
labels where known. Speaker mappings and cloning require explicit authorization, never inference
that a visible face grants voice-use consent.

Direct text is the default input. An empty active snapshot does not mean a missing subtitle track.
OCR for burned-in text and ASR for authorized audio are opt-in alternative paths, not automatic
responses to slow translation. Partial ASR text may appear provisionally; synthesize only stable
segments to avoid speaking words later retracted. Preserve original text, timings and provenance.

## Three surfaces, thin clients

```text
AniBox future integration kit ── Binder ── AniSub Android APK/service
Windows companion UI ── local IPC ── AniSub Windows runtime
Chrome MV3 extension ── Native Messaging broker ── Windows runtime
                              common semantic protocol / provider ports
```

“AniBox plugin” means a small future client adapter/settings surface, not bundling AI engines into
AniBox or promising a dynamic plugin loader. A separately installed AniSub APK hosts supported
providers. There is no AniBox player wiring in this phase. API 23/TV viability is pending device
and ABI tests; unavailable inference remains visible and does not force cloud or desktop/LAN use.

The Windows app manages models, profiles and sessions, with a tray/status UI and standalone test
player first. An external player must cooperate with an adapter to provide its media clock and
controls; arbitrary screen/audio capture is not equivalent to a reliable playback timeline.
Capture-only operation later needs a distinct limited-sync profile. No compulsory cloud/server,
open LAN listener or desktop-to-TV connection is required.

The Chrome extension owns permitted page/video adapters, clock observation, caption overlay and
consent UI; the Windows companion owns inference, model storage and initially speech output.
Each adapter reports its exact supported site/player behavior. Inaccessible cross-origin frames,
hidden tracks and protected content are unsupported rather than scraped around restrictions.
Page script messages, cue text and navigation are untrusted inputs. Scope sessions to one chosen
tab/video; playback and other tabs continue normally when the runtime is absent.

Chrome requires a registered native host and `nativeMessaging`; content scripts communicate via
extension contexts, not directly with the host. The initial broker carries only bounded text,
clock/control and status messages, using AniSub's stricter 256 KiB message limit. Validate sender,
extension origin, session and schema; allowlist extension IDs. Registration is a future installer
step, not performed here. [Chrome native messaging](https://developer.chrome.com/docs/extensions/develop/concepts/native-messaging).

Do not send sustained PCM, video or model payloads through Native Messaging. A future optional
media-input channel needs its own throughput, local authentication, consent, quota and lifecycle
spike before enabling browser OCR/ASR. No localhost server is currently specified or enabled.

MV3 workers are transient and cannot own a durable scheduler/model process; reconnect negotiates
a new session and current snapshot, never historical speech. Persist settings, not raw content or
live inference state. [Service-worker migration](https://developer.chrome.com/docs/extensions/develop/migrate/to-service-workers).
Browser capture is user-invoked and can change tab audio playback; any later capture/offscreen path
must test original-audio restoration, closure and disconnect. It is not an always-on permission.
[tabCapture](https://developer.chrome.com/docs/extensions/reference/api/tabCapture).

## Realtime without runaway work

Small shell, optional model packs and lazy provider preparation define lightweight packaging.
Measure installed/download size, resident memory, startup and energy separately; quantization alone
is not proof of acceptable accuracy or speed. Device profiles bound residency and concurrency.
If a full ASR→translation→TTS pipeline exceeds a device budget, refuse that profile or offer
captions-only; repeated loading is not presumed cheaper than concurrent residency.

Provisional warm-run goals on a declared reference device: direct-cue arrival to translated result
p95 ≤500 ms; stable text ready to first audible narration p95 ≤800 ms; PAUSE/SEEK/STOP to silent
output p95 ≤100 ms. These are proposed benchmark gates, not measured promises. ASR reporting must
include audio chunk/endpoint wait and stable-segment delay, not just inference time. Report first
PCM separately from audible output, cold-load latency separately from warm-run latency, and full
pipeline RTF plus queue growth on continuous video. Hardware and input quality qualify every claim.

Retain existing caps: eight pending jobs, two speech segments, five-second queued media horizon,
32 cues/snapshot and 4096 UTF-16 units/cue. Speech also expires at its known cue boundary; no
unbounded catch-up narration. No known end means conservative scheduling, not an invented duration.
Prioritize controls, reject retired results and never block the media player on inference.

When overloaded, cancel/drop obsolete work, skip late speech, show captions-only/degraded status,
and optionally reduce consented OCR sampling within a declared bound. Switching input mode,
language, provider/model or cloud policy requires explicit user choice and session renegotiation;
“adaptive” does not mean secretly replacing an engine. Never alter playback speed or delay video
without a separately accepted client feature. Live streams therefore have inherently delayed
translation; prerecorded permitted cue lookahead can help only within bounded scheduling limits.

## Continuously updated models, stable sessions

Separate application, executable provider adapter, model artifact and catalog versions. “Continuous
updates” means a maintained compatible catalog, not automatic newest-model activation.

1. Offer manual checks; optional background checks use bounded frequency/network budget. Cache
   the last verified catalog offline; report stale/expired metadata rather than accepting an
   unsigned replacement. Metadata checking and downloading model artifacts are separate consents.
2. Verify signed metadata with a shipped trusted key/key-rotation policy, freshness/version checks
   and bounded schema. Record every asset's digest, size, source/license, language capabilities,
   backend/version range, host/ABI and resource requirements. A hash alone is not publisher trust.
3. Show size, license, compatibility and expected profile impact before download. Use bounded
   resumable staging, storage reservation, digest verification and path-safe extraction. Never
   execute model-provided scripts or implicitly fetch missing tokenizer/codec dependencies.
4. Validate the complete asset graph and a bounded prepare/smoke fixture; atomically publish the
   immutable installation. Keep one quota-budgeted known-good rollback version. Failure leaves
   the current version untouched; cleanup never removes leased artifacts.
5. Pin a session's provider/artifact graph until close. Activate an accepted update next session;
   an urgent security block safely cancels affected sessions with an explanation. User-approved
   rollback may select a trusted compatible prior artifact, never silently downgrade trust metadata.

Provider executables/DLLs/JARs and extension JavaScript/WASM use reviewed application updates, not
the model-data channel. Chrome's remote-code rules distinguish data from remotely loaded executable
code; treating downloaded code as a “model plugin” does not remove that boundary.
[Remote-hosted-code guidance](https://developer.chrome.com/docs/extensions/develop/migrate/remote-hosted-code).

## Planned structure and acceptance

Add planned ownership under `clients/anibox-plugin/` (future thin kit), `clients/chrome-extension/`
(`content/`, `background/`, `ui/`, later `offscreen/`) and `apps/desktop/native-messaging/`
(bounded broker). Keep model catalog/trust/update/install/session-pinning services in
`model-manager/`; engine adapters remain in `providers/`, scheduling in `core/`.
Names are planning references, not implemented classes or installers.

Delivery order: portability/fake-runtime contracts → Windows direct-text captions → narration →
Chrome direct-text companion → separately validated Android/AniBox adapter → consented OCR/ASR
and qualified live dubbing. Windows and Chrome work must not require AniBox changes.

Acceptance requires bounded soak tests; stale-callback and output-stop fixtures; model-update
tamper/interruption/incompatibility/rollback tests; disconnect/worker/tab-navigation cleanup;
reproducible per-device size/memory/latency/quality reports and privacy review. No runtime build,
benchmark, installer, capture, Chrome Store approval or hardware support is established by this
document. Read-only review and document consistency are the current validation scope.
