# AniSub Android — on-device AI narration for AniBox

Standalone Android TV companion (`com.anisub.runtime`, minSdk 23, targetSdk 34, Java/Views; the
app's own code uses no AndroidX, ML Kit brings it). AniBox binds to `AniSubService` and sends a
softsub's cues; AniSub speaks them with an **on-device AI voice** (sherpa-onnx + Piper/VITS
`vi_VN-vais1000-medium`, or `en_US-ljspeech-medium` for voice language English) or, for the legacy
test mode, with the installed system TTS voice. Cues in another language are **translated on the
TV** (Google ML Kit Translate) into the voice language, ahead of time when AniBox sends the whole
subtitle file. Shipping wire contract: protocol **major 1 / minor 2**, see
[docs/android-addon-contract.md](../../docs/android-addon-contract.md) section 3b.

- The APK carries only the native engine (`libsherpa-onnx-jni.so`, armeabi-v7a + arm64-v8a).
  The voice pack (64.0 MB) is downloaded on first use after an explicit consent dialog (size and
  license shown), verified file by file with SHA-256, installed atomically into app-private
  storage, and re-verified before every native load. After that it works offline.
- ML Kit Translate 17.0.3 + bundled language-id 17.0.6 are in the APK (closed source, owner's
  choice 07-10-2026); translation models (~30 MB download each, 45-65 MB installed) are fetched by
  ML Kit from Google through the system DownloadManager only after consent in settings, and can be
  deleted. ML Kit's telemetry backend and start-up providers are removed from the manifest.
- No capture, no microphone, no cloud translation or TTS, no paid API. Subtitle text is never
  logged. Network is used only for consented voice-pack / translation-model downloads (ML Kit
  itself also contacts Google's Firebase Installations / Remote Config endpoints for model
  management; see the ANISUB-004 evidence below).
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
| `voice/VoiceCatalog`, `voice/VoicePackManager`, `voice/HttpsSource` | pinned catalog (vi + en packs), one verified download/install manager per pack, HTTPS allowlist |
| `voice/EspeakData` | one shared, hash-checked espeak-ng data dir for all packs (espeak keeps its first path per process) |
| `translate/TranslationScheduler`, `translate/CueTimeline` | lookahead pre-translation (90 s), bounded cache; per-session timeline of cues sent ahead |
| `translate/MlKitTranslation`, `translate/LanguageTags`, `translate/TextCleaner` | ML Kit adapter (models, translate, language-id), BCP-47 -> ML Kit codes, ASS/HTML cleanup |
| `src/debug/` | DEBUG ONLY: `DebugHarnessActivity` (emulator harness) and a local pack source; absent from release |
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
   `assets/voice-catalog.json`). English pack: `--pack en` produces the 11 `ljspeech-*` assets for the
   release **`voices-en-v1`** (staged in `dist/voices-en-v1`, PUBLISH-PENDING). Details: [docs/voices.md](../../docs/voices.md).

## Evidence ANISUB-004 (2026-10-07, AniSub 0.3.0, protocol 1.2)

- JVM: 105 JUnit tests pass (72 before): OPEN fixture cases `tests/fixtures/android-v1/open-cases.json`
  (voiceLang, translation pairs, missing models, und, minor-1 compatibility), CAPABILITIES minor 2
  and its fixture, scheduler with a fake translator (urgent-first, 90 s lookahead, cache, generation
  discard, fatal model error, bounds), timeline (order, seek, bounds), language tags/cleaner, per-pack
  managers on one store, shared espeak data. Legacy gate 4,296 checks; Node 34/34. `lintDebug` clean.
- APK (unsigned release, armeabi-v7a + arm64-v8a): 17,162,608 -> 32,053,264 bytes (+14.89 MB, +87 %). ML Kit
  native: arm64 `libtranslate_jni.so` +6.78 MB compressed (16.36 MB on install), armeabi-v7a +5.99 MB
  (11.61 MB); language-id +0.79 MB libs + 0.32 MB model; dex/resources about +0.8 MB after R8
  shrinking (turned on in this change, no obfuscation; without R8 the APK would be 34.2 MB).
- Emulator AniBox_P650 (x86 Android TV 11 with GMS; NOT P650 hardware), x86 builds signed with the
  AniBox release key: ML Kit model download via DownloadManager (vi 7.9 s, ja 8.9 s); en->vi cold
  309 ms then mean 69 ms/cue (max 95); ja->vi mean 144 ms (max 240); vi->en 69 ms; ja->en 80 ms.
  English pack side-loaded through the debug local source: verify + install + native smoke 1.5 s;
  engine load vi 0.9 s / en 1.1 s; RTF 0.074-0.091 (2 threads). Real service sessions with the whole
  file sent ahead as timeline batches: en->vi 20/20 cues started within 9 ms of their start;
  und(ja)->vi detected `ja`, 10/10 within 12 ms; vi->en (English voice) 10/10 within 9 ms; ko->vi
  refused with TRANSLATE_MODEL_MISSING {language:"ko", missing:["ko"]}. Release (R8) build: settings
  render, "Dịch thử" en->vi 395 ms, English "Nghe thử" speaks. Test data (English pack, ML Kit vi/ja
  models, side-loaded files) was removed afterwards; the owner's Vietnamese pack was kept.
- Not measured: P650 hardware (ML Kit speed on Cortex-A35, memory with translator + TTS resident),
  boxes without Google Play services or with a disabled download provider, listening quality.

## Evidence (2026-10-07, 0.2.0)

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
