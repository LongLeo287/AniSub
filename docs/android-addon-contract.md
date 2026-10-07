# AniSub Android addon contract — AniBox handoff

Date: 2026-10-07. Owner: AniSub runtime (Claude, reassigned by the owner 07-10: "Làm full AniSub
rồi tích hợp vào AniBox"). **Shipping contract: protocol major 1, minor 1** (additive to the
earlier private major 1 test). The major 2 draft further below is FUTURE work, not implemented.
See [System Design](superpowers/specs/2026-10-07-anisub-runtime-design.md).

## 1. Transport and caller trust (unchanged wire, new trust rule)

- Package/service: `com.anisub.runtime/.AniSubService`, explicit bind, Messenger `what=1`,
  Bundle string `payload` (JSON, <=16,384 UTF-16 units). Every request needs a live `replyTo`.
- **No install-time permission is required.** Real users install AniBox FIRST and AniSub later
  (AniBox auto-download or Downloader). Android grants a custom permission only at the requester's
  install time, and only if it was already defined, so `com.anisub.runtime.BIND` would never be
  granted to AniBox in that order. The service and the settings activity therefore do NOT declare
  `android:permission`. The permission stays defined (signature level) but is unused.
- Authorization is per message, before any Bundle decode: kernel `Message.sendingUid` -> the UID
  must own package `com.anibox.tv` -> `PackageManager.checkSignatures(com.anibox.tv, AniSub)` must
  be `SIGNATURE_MATCH`. Anything else is dropped silently (`CallerPolicy`, tested in `ContractTest`).
  Debug builds of AniSub only (`BuildConfig.DEBUG`) also accept `com.anibox.tv.debug`, with the
  same signer check, for end-to-end testing of debug-signed pairs. Release builds stay exact.
- APKs are signed with both v1 (JAR) and v2 schemes (Android 6 cannot install v2-only APKs).
- Users never need a key. The owner signs both release APKs with the same release key once;
  Android verifies the shared signer at runtime. AniBox should also verify AniSub's signer before
  binding, and the release manifest (section 5) before installing.
- HELLO `{type:"HELLO",major:1}` first; `minor` may be sent and is ignored. Binder death or a new
  peer retires the session; the client restores its ducking and reconnects with a new session ID.

## 2. CAPABILITIES (reply to HELLO / GET_CAPABILITIES, pushed again on state changes)

| Field | Type | Meaning |
|---|---|---|
| `type`, `major`, `minor`, `protocolMajor` | "CAPABILITIES", 1, 1, 1 | `minor:1` = this contract |
| `directText` | true | direct subtitle text input only |
| `tts` | bool | a local offline Vietnamese **system** voice is installed (mode "system") |
| `offline` | bool | `tts` or `aiVoice` |
| `aiVoice` | bool | true ONLY when a verified voice pack is installed and READY |
| `translation`, `multiSpeaker` | false | not offered |
| `engine`, `state` | "android-system-tts", string | legacy system-voice fields, kept |
| `voicePack.state` | NONE / DOWNLOADING / VERIFYING / READY / ERROR | pack lifecycle |
| `voicePack.id`, `name`, `version` | string | installed pack, or the catalog pack that would be downloaded |
| `voicePack.sizeBytes` | number | download size (64,017,617 bytes for `vi-vais1000-medium` v1) |
| `voicePack.updateAvailable`, `license` | bool, string | newer catalog version exists; license summary |
| `voicePack.voices` | `[{id,name}]` | voices of a READY pack (empty otherwise) |
| `voicePack.doneBytes` | number | only while DOWNLOADING |
| `voicePack.error` | string | NO_SPACE / NETWORK / CORRUPT / INCOMPATIBLE / STORAGE / IN_USE (a READY pack may carry the last failed update's error) |
| `aiEngine` | `{name:"sherpa-onnx", version:"1.13.8", state}` | IDLE / LOADING / READY / FAILED residency |
| `modes` | `["system"?, "ai"?]` | OPEN modes usable right now |
| `rate` | `{min:0.8, max:1.3, default:1.0}` | allowed OPEN `rate` |
| `runtime` | `{versionName, versionCode}` | installed AniSub APK |

The service re-sends CAPABILITIES when the pack or engine state changes (not on every progress
tick). A client may also send `{type:"GET_CAPABILITIES",major:1}` at any time after HELLO.

## 3. Session commands

Session commands still need `session`, `revision`, `seq`, `positionMs`, `speed`, `language`.

**OPEN** gains:

| Field | Rule |
|---|---|
| `mode` | `"system"` (default when absent) or `"ai"`; other strings -> ERROR `UNSUPPORTED`; non-string -> `MALFORMED` |
| `systemTest` | `true` still required for mode "system" (legacy behaviour unchanged) |
| `rate` | optional number 0.8-1.3 (default: AniSub setting, initially 1.0); out of range or non-number -> `MALFORMED` |
| `voice` | optional pack voice id; unknown -> `UNSUPPORTED` |

- mode "system": `systemTest:true`, language `vi` and an installed offline Vietnamese system
  voice, else `UNAVAILABLE`. `rate`/`voice` are ignored in this mode.
- mode "ai": language `vi` (else `UNSUPPORTED`) and `voicePack.state=="READY"`, else ERROR
  **`VOICE_PACK_MISSING`**. There is never a fallback from AI to the system voice.
- OPEN ai starts loading the engine asynchronously (full SHA-256 re-verification, native load,
  warm-up: about 1 s on a desktop CPU; P650 pending). Cues received meanwhile queue (max 8);
  known-expired ones are reported `EXPIRED`. If loading fails, queued cues get ERROR
  `PROVIDER_FAILED` (or `VOICE_PACK_MISSING` when the pack is corrupt) plus one session-level ERROR.
  A new OPEN retries the load. The engine unloads after 60 s without an AI session.
- CUES, PLAY, PAUSE, SEEK, STOP, EPISODE_CHANGE, SOURCE_CHANGE, PLAYBACK_SPEED, CLOSE: unchanged.
  CUES carry <=16 `{id,text,startMs,endMs,role}`, text <=512, `endMs=-1` unknown, annotation
  skipped. In AI mode, cues without any letter or digit (e.g. "♪♪") are skipped.

**Speech events are unchanged**: `STARTED` (first PCM handed to AudioTrack), `FINISHED` (with
`code:"CANCELLED"` / `"FAILED"` when applicable), `ERROR` with `code` (`EXPIRED`, `BACKPRESSURE`,
`OUTSIDE_HORIZON`, `PROVIDER_FAILED`, `TIMEOUT`, ...), all with `session` / `revision` / `cueId`.
AniBox keeps ducking the whole track on STARTED and restoring on FINISHED / ERROR / disconnect.

AI behaviour: one inference worker; up to 2 queued cues prepared ahead of the active one; PCM via
AudioTrack (USAGE_MEDIA / CONTENT_TYPE_SPEECH), no audio-focus request. PAUSE / SEEK / STOP /
EPISODE / SOURCE / CLOSE retire everything; obsolete PCM never starts. Long text is split at
punctuation or word boundaries without losing characters. When `endMs` is known the speed is
auto-adjusted within x1.00-x1.15 of the user rate (never slower than it); unknown end uses the user
rate. Watchdogs: 10 s without progress inside the pipeline, plus a per-cue cap (expected duration
x2 + 15 s, at most 120 s).

## 4. Settings screen (launch from AniBox)

`new Intent("com.anisub.runtime.action.SETTINGS").setPackage("com.anisub.runtime")` opens the TV
settings: pack status, Download (consent dialog with size + license) / Cancel / Update / Delete,
voice choice when a pack has several voices, speech rate (0.8-1.3, used when OPEN omits `rate`),
"Nghe thử" preview, AniBox compatibility line and about/licenses. It is exported without a
permission on purpose (same install-order reason); it exposes no data and acts only on on-screen
confirmation. BACK closes it. The app also has LAUNCHER + LEANBACK_LAUNCHER entries.

## 5. Release manifest for AniBox's installer

`apps/android/tools/make-release-manifest.mjs` writes `anisub.json` next to the signed APK:
`{schemaVersion:1, versionCode, versionName, apk:"https://github.com/LongLeo287/AniSub/releases/latest/download/AniSub.apk",
sha256, signerSha256, notes}`. AniBox must verify the downloaded APK's SHA-256 and that its signer
SHA-256 equals both the manifest value and AniBox's own signer before installing.

## 6. Legacy notes

The previous private major 1 test remains callable unchanged (absent `mode` = system test). This
v1 is NOT the Node semantic protocol draft v1. No AIDL interface exists.

## FUTURE (not implemented): proposed production major 2 — envelope and negotiation

Same explicit service, permission and Messenger framing. JSON payload <=16,384 UTF-16 units
and serialized Bundle <=64 KiB. Raw image/audio is forbidden in this text transport.
HELLO supplies major=2, minor=0, requestId (<=80 units); reply HELLO_ACK selects 2.0 and limits.
Unknown major fails PROTOCOL_MISMATCH, never downgrades AI OPEN into system-TTS test.
Client may separately opt into major-1 test; it is not an automatic fallback.

After HELLO, management commands carry major/type/requestId. Playback commands additionally carry
sessionId/revision/seq. Server responses echo requestId for command acknowledgement and use an
independent eventSeq for session callbacks. IDs <=80 units; revisions/sequence integers in
0..9,007,199,254,740,991, seq starts at 1 and strictly increases; revision never decreases.
Duplicates are ignored without repeating side effects. Gaps are permitted; lower seq rejected.
Failure returns ERROR with code/recoverable and identifiers available; no raw-content message.

Control and cue requests receive ACK after validation/admission, not after speech completion.
Async download/prepare use operationId and progress, never a 5-second blocking Binder call.
16 pending management requests per peer; timeout 5 seconds for ACK; one foreground peer/session.
Unknown command/enum -> UNSUPPORTED; unknown optional keys ignored, required keys strictly typed.
No arbitrary extras become executable behavior. Full schema/negative fixtures are M1 prerequisites
to marking this draft stable. Current implementation rejects major 2.
Catalog/voice pages contain at most 16 entries plus nextPageToken (null at end, <=80 units otherwise).
Byte/unit limits still apply: server returns fewer entries to fit. M1 registry supports at most 64
installed versions and CAPABILITIES at most one ready model in M1; full inventory uses paged LIST_MODELS.
Tokens are peer-scoped opaque read cursors, not URLs/paths. Expired cursor -> STALE; restart listing.

## Proposed commands and result DTOs

| Command | Required body beyond envelope | Result |
|---|---|---|
| GET_CAPABILITIES | none | CAPABILITIES, immutable snapshot |
| GET_SETTINGS | none | SETTINGS with settingsRevision |
| SET_SETTINGS | expectedSettingsRevision, complete settings object | SETTINGS or SETTINGS_CONFLICT |
| LIST_MODELS | optional pageToken, pageSize 1..16 (default 16) | catalog page and installed states |
| LIST_VOICES | installed modelId/version, optional pageToken, pageSize 1..16 | verified VoiceDescriptor page |
| DOWNLOAD_MODEL | catalog modelId/version, consent=true | ACK operationId then MODEL_PROGRESS/STATE |
| CANCEL_DOWNLOAD | operationId | ACK and terminal MODEL_STATE |
| REMOVE_MODEL | installed modelId/version | ACK or MODEL_IN_USE |
| PREPARE_MODEL | modelId/version | ACK operationId then MODEL_STATE |
| OPEN | session descriptor and clock anchor | ACK or unsupported/not-ready error |
| CUE_SNAPSHOT | full active cues | ACK; processing is asynchronous |
| TIMELINE_BATCH | future timed cues within 5 seconds, <=16 | ACK if lookahead negotiated |
| CLOCK_ANCHOR | clock anchor | ACK; runtime may coalesce latest anchor |
| PLAY/PAUSE/SEEK/STOP/PLAYBACK_SPEED | clock anchor | ACK; invalidation semantics below |
| EPISODE_CHANGE/SOURCE_CHANGE/CLOSE | old session identity | ACK closes old; fresh OPEN required |

Session descriptor: opaque episodeRef/sourceRef/trackRef (<=80 units, no URL), inputMode=DIRECT_TEXT,
trackAvailability=TEXT_TRACK, originalLanguage, targetLanguage, settingsRevision, modelId/version,
voiceId, speechEnabled and clock `{positionMs,sampledAtElapsedMs,playing,speed}`. M1 supports vi->vi
only; original/target language mismatch is UNSUPPORTED_TRANSLATION, not silent passthrough.
Position and monotonic time are nonnegative safe integers; speed 0.5..2.0. Model must be READY.
No model automatically loads/downloads because a peer submitted OPEN.

Cue: `{cueId,originalText,language,observedAtMediaMs,startMs,endMs,origin,role,trackRef}`;
origin=DIRECT in M1, role=DIALOGUE/ANNOTATION/UNKNOWN. startMs/endMs null when genuinely unknown,
known end >= known start. Distinguish from legacy v1's end=-1. All timing values nonnegative safe
integers when not null; text <=512 UTF-16 units, batch <=16, IDs <=80. TIMELINE_BATCH requires
known start/end and does not clear active snapshots. Empty CUE_SNAPSHOT clears pending active
caption-derived work, not accepted speech or explicitly supplied future timeline.

Settings: `{voiceMode:"AUTO"|"EXPLICIT",modelId,modelVersion,voiceId,targetLanguage:"vi",
rateMode:"AUTO"|"NATURAL",deliveryMode:"LIVE_BOUNDED"}`. AUTO may leave selection IDs null and
resolves only installed compatible packs; EXPLICIT requires all IDs. No selection means MODEL_MISSING.
Desired accent/gender filters belong to available VoiceDescriptor data, not invented model capabilities.
M1 has no dubbing/capture/profile setting. Caller cannot change download quota or capture permissions
through these commands. AniBox-owned ducking preferences remain client-side.

CAPABILITIES: protocolMajor/minor, readiness state, limits, supportedInputs, translationPairs,
speechLanguages, aiVoice, offline, multiSpeaker, lookahead, clientHoldSupported=false, engine and
installed compatible model versions. Capability flags describe actual prepared support;
catalog candidates are separate metadata. No readiness claim just because native library loads.
VoiceDescriptor: id/modelId/version/language/displayName plus accent/gender (UNKNOWN allowed),
verifiedMetadata boolean and supported rate/output mode. No personal identity inference.

Model states: NOT_INSTALLED -> DOWNLOADING -> VERIFYING -> INSTALLED -> PREPARING -> READY;
failures ERROR with reason, previous usable installed version preserved; cancel -> NOT_INSTALLED
for new staging only. Incompatible catalog entry is INCOMPATIBLE. MODEL_PROGRESS reports operationId,
bytesTransferred/expectedBytes, no URL/local filesystem path. State changes can be queried after reconnect.
INSTALLED is not READY; model selection and active session lease are separate.
Idle unload after 60 seconds without lease transitions READY -> INSTALLED, publishes MODEL_STATE
and updates CAPABILITIES. Client repeats PREPARE_MODEL before OPEN. OPEN checks READY/acquires
lease atomically; a race returns UNAVAILABLE, never silently reloads. Preparing another model while
the resident model is leased returns MODEL_IN_USE; otherwise old model becomes INSTALLED first.
Prepare deadline is 60 seconds; failure/timeout never publishes late READY.

## Proposed playback/speech semantics

PAUSE/SEEK/STOP/PLAYBACK_SPEED increment client revision before submission. Track/settings changes
close/reopen a session in M1. PLAY uses current revision plus new anchor. Source/episode changes
close/reopen; never keep old queued text across them. Controls have priority over queued synthesis.
Clock anchors every 1 second while playing and immediately on control. While playing, no fresh anchor for 2 seconds
retires all unstarted work/output and reports CLOCK_STALE; only already-started audio may finish.
Recovery requires a fresh handshake/session; a late anchor cannot revive stale queued audio.
Paused/stopped state has no periodic heartbeat requirement; PLAY with fresh control anchor resumes
current revision normally. All immediate control anchors still pass timestamp-age validation.

SPEECH_STARTED: sessionId/revision/eventSeq/speechId/cueId/segmentIndex/modelVersion.
SPEECH_FINISHED: same identity plus COMPLETED/CANCELLED/FAILED reason, exactly once after accepted
STARTED. REJECTED_CUE reports cueId/reason even when no speech starts. Diagnostics report timing
deficit, not a promise that missing/expired words were spoken. An unstarted cancel gets no STARTED.

Client maintains ledger by session/revision/speechId. Terminal callbacks may finalize only matching
old accepted entries, never newer speech. Disconnect locally finalizes all and restores volume.
Client chooses whole-track ducking; AniSub does not manipulate AniBox audio controls. PCM-only
ducking availability versus encoded passthrough must be handled honestly by the addon.

Errors: PROTOCOL_MISMATCH, UNAUTHORIZED, MALFORMED, UNSUPPORTED, UNSUPPORTED_TRANSLATION,
UNAVAILABLE, MODEL_MISSING, MODEL_CORRUPT, MODEL_INCOMPATIBLE, MODEL_IN_USE, SETTINGS_CONFLICT,
STALE, CLOCK_STALE, EXPIRED, OUTSIDE_HORIZON, BACKPRESSURE, PROVIDER_FAILED, TIMEOUT,
DISCONNECTED, CANCELLED. Permission rejection before decode may disconnect without callback.

## Claude integration acceptance

Keep existing Thuyết Minh/Lồng Tiếng choices unchanged; AniSub is an additional option.
Show runtime absent/untrusted/initializing/model missing/ready/failed distinctly. Render settings
only from supported capabilities and verified voices, not a hard-coded list of every region/gender.
Do not ship default enabled system-TTS as AI. Contract test via fake runtime before real engine;
test Binder death, delayed callbacks, seek/pause, duplicate cues and volume restoration independently.
No original-content modification, source credentials or model weights in addon.
Coordinate signer identity without copying private keys. This document is a local handoff, not a
message to Claude or confirmation Claude has implemented/accepted it.
