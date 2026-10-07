# Module and file map

External host additions: `ExternalHost.cs`/`ExternalMedia.cs` own no-player local UI, bounded
cue/live recognition scheduling and pipe/session controls; `CaptureBridge.cs`/`CapturePanel.cs`
own explicit selected-source capture and stale-result guards; `ChromeSetupPanel.cs` is user-click
registration UX. `providers/capture/` owns Windows ROI OCR/process-only WASAPI/Whisper and local
Latin OCR. `providers/tts/turbo_local.py` adapts reviewed SDK local-only loading. Chrome companion
and native-messaging folders now contain source/tests, not only plans. Quality/OCR installers
are pinned manual pack installers, not signed automatic model updates.

The module-class table below is the production plan; not every listed class exists.
Each module owns its tests; shared integration fixtures live under tests/. No engine code in core/.

## Implemented no-model fixture, 2026-10-07

- protocol/src/index.mjs: immutable bounded DTO validation and major negotiation.
- core/src/runtime.mjs: serial command admission, media clock, async fake pipeline, revision guards,
  queue limits, virtual speech lifecycle and redacted diagnostics.
- providers/fake/src/index.mjs: VirtualScheduler, FakeTranslator and FakeTts.
- apps/desktop/harness/playback-simulator.mjs and demo.mjs: cooperating synthetic client.
- tests/contract/protocol.test.mjs and tests/integration/{runtime,regressions}.test.mjs.
- package.json and scripts/{check,test}.mjs: dependency-free check/test/demo entrypoints.

These ES modules run on Node; there is no production transport, GUI or audible speech. Virtual
callbacks represent fake output, not timing evidence. No AIDL/JSON-schema interop is claimed.

## Production module plan

Android đã có project Java/Gradle thử nghiệm độc lập; không đồng nghĩa các class production
bên dưới đã tồn tại. [Thiết kế runtime](superpowers/specs/2026-10-07-anisub-runtime-design.md)
và [hợp đồng addon](android-addon-contract.md) phân biệt phần major 1 đang chạy với v2 dự thảo.
Claude sở hữu addon trong repo AniBox; phần việc này chỉ sửa AniSub, không mang engine vào client.

| Folder | Planned files | Responsibility |
|---|---|---|
| protocol/src/ | AniCue, SessionDescriptor, PlaybackEvent, SpeechEvent, AniSubError, Capabilities, Settings, ClockAnchor | bounded DTOs without host APIs |
| protocol/schema/ | protocol-v1.schema.json, compatibility.md | serialization fixtures, version negotiation |
| core/src/session/ | SessionManager, SessionState, GenerationGuard | lifecycle, revisions, cancellation |
| core/src/input/ | InputRouter, DirectCueSource, ImageInputPort, AudioInputPort | select authorized input mode |
| core/src/cues/ | CueNormalizer, CueDeduplicator, CueTimeline | original/derived text and timing |
| core/src/pipeline/ | PipelineCoordinator, WorkQueue, TranslationStage | bounded asynchronous processing |
| core/src/speech/ | SpeechScheduler, SpeechOutputPort, SpeechLifecycle | expiry, media clock, callback pairing |
| core/src/providers/ | ProviderPort, OcrProvider, AsrProvider, TranslationProvider, TtsProvider | engine-neutral interfaces |
| core/src/policy/ | CapabilityPolicy, ResourceBudget, PrivacyPolicy | explicit routing/resource decisions |
| providers/fake/ | FakeOcrProvider, FakeAsrProvider, FakeTranslator, FakeTtsProvider | deterministic no-model pipeline |
| providers/ocr/ | adapter and README per selected engine | image recognition implementation |
| providers/asr/ | adapter and README per selected engine | audio recognition implementation |
| providers/translation/ | adapter and README per selected engine | local/optional online translation |
| providers/tts/ | adapter and README per selected engine | synthesis and output handoff |
| model-manager/src/ | ModelManifest, ModelRegistry, DownloadManager, IntegrityVerifier, ModelStore, LoadLease, CacheQuota | model supply and residency |
| model-manager/src/updates/ | CatalogVerifier, CompatibilityGate, StagedInstall, PromotionPolicy, RollbackStore | trusted catalog, immutable bundles, safe new-session upgrades |
| clients/anibox-plugin/ | AniSubClient, AniSubConnection, CueSource, Media3CueSource, MpvCueSource, SpeechCallbackBridge | planned thin SDK/reference only; Claude owns AniBox addon; no engines or AniBox edits here |
| clients/chrome-extension/ | ContentCueAdapter, PlaybackObserver, OverlayView, ConsentPanel, BackgroundBridge, CaptureHost | planned MV3 companion; host permissions/transport/capture gated |
| apps/android/app/ | ServiceHost, ConsentController, CapabilityScreen, ModelScreen | service and user settings |
| apps/android/transport/ | BinderConnection, CallerVerifier, ParcelableMapper, DeathRecipient | trusted Android IPC |
| apps/android/aidl/ | IAniSubService.aidl, IAniSubCallback.aidl, parcel declarations | stable wire contract, later phase |
| apps/desktop/host/ | DesktopRuntime, ConfigurationStore, ProviderHost | Windows runtime composition |
| apps/desktop/harness/ | PlaybackSimulator, FixtureRunner, DiagnosticReport | runtime verification without AniBox |
| apps/desktop/native-messaging/ | NativeHostManifest, FramedMessageHost, OriginVerifier, RuntimePipeClient | planned Chrome bridge to existing Windows runtime, not a model host per message |
| tests/fixtures/ | cues, clock sequences, provider replies | redacted reproducible inputs |
| tests/contract/ | protocol, lifecycle and conformance suites | behavior shared across hosts |
| tests/integration/ | fake runtime, IPC host tests | assembly and cleanup |
| tests/performance/ | latency, queue, memory, model load benchmarks | device-specific evidence |

Dependency direction:

```text
apps → core → protocol
apps → providers → core ports + protocol
apps → model-manager → protocol metadata
clients → transport adapters → protocol (no provider/core implementation imports)
core → injected ModelAccessPort (implemented by model-manager)
```

Core does not import apps/providers implementations. Hosts compose dependency injection.
Chrome content scripts route through extension background bridge, never directly to native host.
Client clock/ducking and negotiated speech-output owner remain outside provider engines.
Model-manager does not choose an input mode. Protocol has no dependency on the other modules.
reference/anibox-integration-draft/ retains the previous Java sketch and tests; it is excluded
from every future build and is not the canonical protocol. Its protocol v0 needs reconciliation
with protocol.md before reuse. A private Node harness and a separate Android Gradle test project
now exist; neither establishes a shared production binary or Android AI portability.

## Implemented Android test companion (2026-10-07)

- `apps/android/app/src/main/java/com/anisub/runtime/AniSubService.java`: bounded Messenger
  major 1 host, signature permission plus sender UID/package/signature checks before Bundle reads.
- `SessionGate.java`, `PayloadRules.java`, `RecentCueIds.java`: admission, bounds, closed-session
  replay prevention and rolling cue deduplication.
- `SpeechEngine.java`, `SystemTestSpeechEngine.java`: actual system-TTS utterance callbacks,
  explicit installed offline Vietnamese voice only; no AI model/native payload or network fallback.
- `CapabilityActivity.java`: test capability/limitation screen, not production model settings.
- `apps/android/tests/SessionGateTest.java`: dependency-free Java admission/bounds regression suite.
- `apps/android/app/build.gradle` and manifest: standalone minSdk 23 / targetSdk 34 companion,
  `com.anisub.runtime/.AniSubService`; no AIDL, translation, OCR, ASR or capture permission.

This private transport is not canonical semantic protocol v1. No model manager, downloadable
Android voice catalog, regional/gender selection or v2 handler exists yet. See
[Android README](../apps/android/README.md) for build/test evidence and limitations; real-device
voice quality, RAM/thermal performance and Android 6/P650 acceptance remain pending.

## Implemented Windows vertical slice (2026-10-07)

- `apps/desktop/windows/App.cs`: WPF local player, parser/overlays, revision/output ownership,
  explicit translation/speech consent, playback controls and ducking.
- `TranslationBridge.cs`: bounded persistent offline subprocess; UTF-8/correlation/timeout,
  parent-owned cleanup. Internal prototype JSON-lines, not protocol-v1 IPC.
- `Start-AniSub.ps1`, `Run-AniSub.ps1`, root `Start-AniSub.cmd`: source-compiled Windows entry.
- `providers/translation/windows_worker.py`: temporary combined MT/TTS worker composition;
  preset-only VieNeu Nano and CPU Marian. Future extraction into separate provider adapters.
- `model-manager/tools/install_windows_models.py`: explicit pinned download, digest validation,
  atomic directory install; not signed catalog/update/promotion/lease machinery.
- `tests/windows/`: parser, provider waveform, subprocess wire and player smoke tests.
- `models/` and `work/`: ignored machine-local assets, evidence and bounded speech cache.

Sync007: `NarrationCoordinator.cs` owns bounded media-clock prefetch, accepted utterance lifetime,
read-complete hold/resume, file leases and ducking ramps; App.cs retains player controls/caption UI.
Worker supports voice-only prepare, explicit WAV release and bounded full-wave timing adaptation.
Windows complete-line policy is optional host behavior, not Android/protocol-v1 interop acceptance.

Source/voice slice: `SourceSelection.cs` owns bounded read-only window discovery, candidate
recommendation and explicit identity-checked selection; no capture or video verification.
`VoiceSelection.cs` owns installed-preset region/gender filters; bridge/worker pass validated
preset names to real inference. App.cs owns source/local-harness exclusion and voice revision
invalidation. The installed Nano catalog has North/South only, no Central preset.

Content policy: `providers/translation/content_policy.py` owns bounded glossary
validation and source-gated output canonicalization. `TranslationBridge.cs` owns
the optional private worker DTO. `ExternalMedia.cs` owns validated policy changes,
scheduler locking and output invalidation. `ExternalHost.cs` owns session-only
manual profile/term controls. See content-policy.md; no contextual-model claim.
