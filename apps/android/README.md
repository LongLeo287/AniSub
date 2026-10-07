# AniSub Android — on-device AI narration for AniBox

Standalone Android TV companion (`com.anisub.runtime`, minSdk 23, targetSdk 34, Java/Views, no
AndroidX). AniBox binds to `AniSubService` and sends the active Vietnamese subtitles; AniSub
speaks them with an **on-device AI voice** (sherpa-onnx + Piper/VITS `vi_VN-vais1000-medium`) or,
for the legacy test mode, with the installed system TTS voice. Shipping wire contract: protocol
**major 1 / minor 1**, see [docs/android-addon-contract.md](../../docs/android-addon-contract.md).

- The APK carries only the native engine (`libsherpa-onnx-jni.so`, armeabi-v7a + arm64-v8a).
  The voice pack (64.0 MB) is downloaded on first use after an explicit consent dialog (size and
  license shown), verified file by file with SHA-256, installed atomically into app-private
  storage, and re-verified before every native load. After that it works offline.
- No capture, no microphone, no translation, no cloud TTS, no paid API. Subtitle text is never
  logged. Network is used only for the consented voice-pack download.
- Trust: callers are authorized at runtime (sending UID -> package `com.anibox.tv` -> same signing
  certificate). No install-time permission is required, so installing AniBox before AniSub works.
  Debug builds of AniSub (only) also accept `com.anibox.tv.debug` with the same signer check, so
  debug-signed pairs can be tested end to end. Debug and release APKs are signed v1 + v2.

## Layout

| Path | Role |
|---|---|
| `AniSubService` | Messenger host, session gate, cue queue, mode routing (system / ai) |
| `CallerPolicy`, `OpenRules`, `Capabilities` | pure contract rules (JVM-tested) |
| `RuntimeHost` | process-wide composition: catalog, model store, pack manager, engine |
| `SettingsActivity` | TV settings: status, download/update/delete, voice, rate, "Nghe thử", about |
| `ai/NarrationPipeline` | one inference worker, up to 2 utterances ahead, PCM writer, generation guards |
| `ai/TextSplitter`, `ai/RatePolicy` | lossless splitting; rate x1.00-x1.15 auto when the cue end is known |
| `ai/SherpaSynthesizer`, `ai/AudioTrackSink`, `ai/AiSpeechEngine` | sherpa-onnx adapter, AudioTrack output, async load/unload |
| `voice/VoiceCatalog`, `voice/VoicePackManager`, `voice/HttpsSource` | pinned catalog, verified download/install, HTTPS allowlist |
| `models/` | pre-existing atomic model store, integrity verifier and leases |
| `com/k2fsa/sherpa/onnx/` | trimmed JNI binding derived from sherpa-onnx java-api (Apache-2.0) |
| `assets/voice-catalog.json` | reviewed pack catalog: files, sizes, SHA-256, release URLs |
| `native-artifacts.json` | pinned native archive and per-ABI `.so` digests |

## Build

Requirements: JDK 17+ and an Android SDK (platform 36, build-tools 35). Any Gradle 8.11 wrapper
works; the owner's machine borrows AniBox's wrapper read-only. Paths below are placeholders.

```powershell
$env:JAVA_HOME='<path-to-jdk>'
$env:ANDROID_HOME='<path-to-android-sdk>'
# 1. Fetch and verify the pinned native libraries (once; writes native/jniLibs, Git-ignored)
powershell -ExecutionPolicy Bypass -File tools/fetch-native.ps1
# 2. Tests, debug APK, lint, unsigned release APK
& '<path-to>/gradlew.bat' -p . testDebugUnitTest assembleDebug lintDebug assembleRelease --offline
# Emulator-only x86 build (never for release):
& '<path-to>/gradlew.bat' -p . assembleDebug -PemulatorAbi=x86
```

`preBuild` refuses to package any `.so` whose size/SHA-256 differs from `native-artifacts.json`.
The release build is **unsigned**; the owner signs it with the AniBox release key (never committed).

Legacy dependency-free gate (unchanged baseline, 4,296 checks):

```powershell
& "$env:JAVA_HOME/bin/javac.exe" -d app/build/gate-tests app/src/main/java/com/anisub/runtime/SessionGate.java app/src/main/java/com/anisub/runtime/PayloadRules.java app/src/main/java/com/anisub/runtime/RecentCueIds.java tests/SessionGateTest.java
& "$env:JAVA_HOME/bin/java.exe" -cp app/build/gate-tests com.anisub.runtime.SessionGateTest
```

## Release (owner)

1. Sign `app/build/outputs/apk/release/app-release-unsigned.apk` with the AniBox release key,
   **v1 + v2** (Android 6 / minSdk 23 cannot install v2-only APKs; AniBox verifies readable signers):
   `apksigner sign --ks <AniBox keystore> --v1-signing-enabled true --v2-signing-enabled true --out AniSub.apk app-release-unsigned.apk`,
   then `apksigner verify --verbose AniSub.apk` must report v1 and v2 true. (Alternatively a local,
   Git-ignored `keystore.properties` makes Gradle sign the release with v1 + v2.)
2. `node tools/make-release-manifest.mjs AniSub.apk --notes "..."` writes `anisub.json`
   (versionCode, versionName, apk URL, sha256, signerSha256).
3. Publish `AniSub.apk` + `anisub.json` on a GitHub release of `LongLeo287/AniSub`.
4. Voice pack: `node tools/build-voice-pack.mjs <extracted vits-piper-vi_VN-vais1000-medium>` produces
   the 11 `vais1000-*` assets; upload them to the release tagged **`voices-v1`** (URLs are pinned in
   `assets/voice-catalog.json`). Details: [docs/voices.md](../../docs/voices.md).

## Evidence (2026-10-07)

- Source + JVM: 72 JUnit tests pass (contract fields, OPEN modes, caller trust, catalog pinning,
  SHA-256 rejection, resume/retry/cancel, failed update keeps the old version, re-verification
  before load, corrupt-pack re-download/delete, crash recovery, debug-pair trust, v1+v2 signing
  config, text splitting, cancel/obsolete-PCM guards); stable over 3 reruns. Legacy SessionGate
  gate: 4,296 checks pass. Node suite 30/30. `lintDebug`: 0 errors, 0 warnings. An independent
  read-only review found 2 P1 + 14 P2 issues; both P1s and the actionable P2s are fixed.
- Host (Windows x86-64, sherpa-onnx 1.13.8 win-x64 JNI, same Java binding and production pipeline
  classes): install from the real catalog hashes, re-verify (63 MB SHA-256 in ~60 ms), native load
  0.6-1.1 s, warm RTF 0.067-0.076 (1 thread) / 0.043-0.056 (2 threads), first PCM p50 112-197 ms
  for <=200-character cues, stop during synthesis discards the result with no events. This is desktop evidence, **not** TV evidence.
- Not yet measured: emulator (the only AVD was in use by the owner) and FPT P650 (4x Cortex-A35):
  RTF, first-audio latency, PSS, thermal behaviour and listening quality are **hardware-pending**.
  Expect roughly 8-12x slower per core than the desktop figure; if P650 RTF exceeds the 0.7 target,
  report it rather than claiming realtime.
- APK (stripped, compressed .so): debug 18.4 MB, unsigned release 17.2 MB for armeabi-v7a +
  arm64-v8a (per ABI in the release APK: arm64-v8a 9.0 MB, armeabi-v7a 8.1 MB compressed).

Rollback: uninstall AniSub (its private data, including the voice pack, goes with it); AniBox keeps
playing normally and its optional narration stays off.
