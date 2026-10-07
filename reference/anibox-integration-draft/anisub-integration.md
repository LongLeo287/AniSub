# AniSub integration v0

AniBox owns playback, provider provenance, subtitle display and audio policy. The separate AniSub
APK/runtime owns translation, OCR, ASR and speech engines/models. The engine-free `anisub/` package
is an opt-in transport boundary, not a second player coordinator. No AI/native dependency is added.

## Current source wiring

`PlayerActivity.onCues → AniSubPlayerBridge → AniSubCueAdapter → Media3CueSource → AniSubClient`

The host adds only lifecycle/cue hooks. The bridge forwards PLAY, PAUSE, SEEK, STOP,
EPISODE_CHANGE, SOURCE_CHANGE and PLAYBACK_SPEED through the connection port. Default settings
are disabled and the shipped connection is disconnected; nothing binds to a service or speaks.
MPV has an unsupported `MpvCueSource` placeholder; Embed remains outside this slice.

Direct text subtitles come first. Media3 supplies active text snapshots, including empty clear
snapshots; bitmap cues are skipped. Start time is the observed media position, end is explicitly
unknown because `CueGroup` does not expose cue duration. Language comes from the selected text
track; provenance is provider plus local source index, with no source URLs/headers sent.
The port accepts at most 32 cues of 4096 characters each. It does not queue historical cues.

Every source attachment gets an opaque session id. Seek, pause, stop and speed changes invalidate
the speech timeline revision; replies must echo the current session/revision. Session release and
disconnect finish any active speech callback. `speechStarted`/`speechFinished` are available for a
later audio-ducking policy; this slice does not change volume or acquire audio focus.

## Roadmap and boundary

1. Define versioned Binder/AIDL parcel schemas and a trusted AniSub package/signature policy;
   negotiate capabilities, bounded asynchronous sends, callback dispatch on the Media3 owner thread,
   Binder death handling and explicit reconnect/session recovery. No Activity-thread Binder waits.
2. Add opt-in settings/consent and direct-subtitle session integration against a real AniSub service;
   validate seeks, source/episode switches, subtitle disabling, disconnects and stale speech.
3. Connect the speech callbacks to the existing AniBox audio policy, preserving the original
   volume/focus state and restoring it on pause, seek, release and Binder death.
4. Implement and verify MPV text/timing extraction behind `CueSource` without merging playback engines.
5. Add OCR fallback for hard/bitmap subtitles and ASR fallback only when direct text is unavailable,
   inside AniSub, with separate capture permission, capability, device-cost and latency gates.

OCR, Whisper, SenseVoice, VieNeu-TTS, OmniVoice, audio.cpp, Supertonic and model runtime payloads
belong to AniSub's repository/runtime. This scaffold has no capture path and advertises no OCR/ASR
support. APK-service interoperability and real speech/ducking remain unimplemented.

Validation requires contract/JVM tests, debug build/lint and direct Media3 emulator smoke. API-34
evidence is separate from physical P650/API-23/HDMI/Bluetooth behavior. Rollback is removal of the
five host hooks plus this isolated package/tests/document; existing playback policy is unchanged.
