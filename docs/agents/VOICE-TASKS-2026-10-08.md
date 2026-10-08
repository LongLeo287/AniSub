# Voice tasks — root evidence ledger, 2026-10-08

## Outcome

VOICE-002 research and authorized data publication delivered. ANISUB-005 and ANIBOX-VOICE-001
source are implemented in isolated worktrees, not accepted as completely finished. Main checkouts
and Claude's emulator-5554 are untouched; root used only emulator-5564. No commits/source pushes,
APK publication, version bump or new native dependency.

## Permission decision (supersedes the earlier blocked checkpoint)

The original handoff requires DownloadManager with no INTERNET permission. Actual API34 Android
DownloadProvider rejects enqueue: `Permission Denial ... requires android.permission.INTERNET`.
Owner explicitly answered "ok làm đi" to the request to add INTERNET. Root added only that permission,
preserving DownloadManager transport, SHA integrity, HTTPS-only application policy and disabled
datatransport backend discovery. INTERNET is app-wide and enables dependency clients too; it is not
a per-host firewall or a guarantee of zero outbound dependency traffic. Future catalogs require a reviewed SHA pin update because
DownloadManager hides redirect hops; arbitrary unreviewed snapshots are rejected.

## Changed areas

- AniSub: bounded catalog/registry/defaults, settings and local system voices, per-voice PCM effects,
  optional Messenger metadata/preferences, shared residency and lease-safe storage controls,
  tokenizer preflight, catalog/Cake reproducible builder, license/research records and regression tests.
- AniBox: bounded optional inventory/parser/selection/picker, per-kind-language persisted selection,
  request voice ID and reading preferences, automatic subtitle-priority policy and real Binder test.
- System pitch classification is explicitly a local estimate, not verified gender or accent.

## Tests and runtime evidence

- AniSub Android: 159 JUnit tests, zero failures/errors/skips; debug build and lint pass (0 errors, 3 warnings).
- Final ARM debug APK: 37,111,460 bytes versus baseline 37,055,129 (+56,331 bytes);
  all six native entries retain the baseline names and SHA-256, with no added ABI/library.
- AniBox Android: 3,495 JUnit tests, zero failures/errors, one existing skip; debug build/lint pass.
- Legacy dependency-free gate: 4,296 checks. Node: syntax 11 modules, 34 tests. Cake builder: 3 tests.
- API34 x86 native smoke: Cake sid0 17,664 samples and sid2 17,586 samples, both 22,050 Hz;
  no native process death with corrected tokens. This is not a listening-quality benchmark.
- Real two-APK instrumentation: `installedCakeVoicesCrossRealBinderWithoutLegacyBootstrapPack`,
  OK (one test), rerun after INTERNET change in 8.592 seconds. Production AniBox builder opened,
  narrated and closed both Cake IDs.
- DownloadManager public Cake probe: 11/11 files verified, 77,983,146 bytes in 100,922 ms;
  every exact file length and SHA-256 matched. One TLS record error recovered with the system's
  existing retry; no TLS/integrity bypass. Probe removed its staging copies after verification.
- Production default VAIS auto-download completed, verified, installed and produced 11,008 PCM
  samples at 22,050 Hz. Catalog update via real TV confirmation downloaded the pinned public asset,
  reported "Danh mục đã là bản mới nhất", restored opener focus and deleted its temporary file.
- TV D-pad: catalog/voice-manager entry, safe Cancel confirmation focus, BACK opener restoration,
  and guarded system TTS installer entry verified. Comprehensive picker/system-pitch UI remains pending.
- Full independent review remains partial after Luna workspace quota failure; its three reported
  findings were corrected by root. No failed agent was retried and no final approval is claimed.

## Published data only

Owner explicitly approved catalog and Cake assets. `voices-vi-cake-v1` contains 11 files,
77,983,146 bytes. `catalog-v1/catalog.json` is 16,592 UTF-8 bytes; SHA-256
`ac12f19e02bb11434ef4f8eb5fb155da9ad26631d345790e35eea02a3d0b26c0` verified by public download.
Latest APK release remained v0.3.1. Approved speakers only: Ngoc Lan sid0 and Quang Huy sid2.
MIT weights do not establish synthetic teacher/source speaker consent or clean provenance.

## Limitations and rollback

P650/Android6 hardware, peak inference memory, cold load, realtime factor, perceptual quality,
system pitch action runtime and comprehensive picker UI remain unverified. Public catalog/Cake
downloads now pass with the owner's explicitly approved permission change. Probe verification does
not represent a fresh Cake install; native installed Cake and production VAIS install are separate evidence.
Cleanup inventory is scoped: obsolete ignored CRLF candidate directories and own emulator staging
copies were identified, but the deletion tool call was rejected before execution. They remain;
no cleanup success is claimed. Corrected published assets and verified installed models are retained.
Rollback only this task's uncommitted isolated diffs; preserve unrelated changes/main workspaces.
Data releases are external and intentional; do not remove them without new owner direction.
