# VOICE-002 — Voice asset research and Cake candidate handoff

**Status:** research delivered; approved Cake data assets and catalog published; P650/quality gates remain open.  
**Date:** 2026-10-08  
**Detailed research:** [VOICE-002 dated report](../research/VOICE-002-2026-10-08.md)  
**Task packet:** [ANISUB-005](../tasks/ANISUB-005.md)

## Acceptance and disposition

- [x] Compare candidate voice data, licenses, size and provenance using dated sources.
- [x] Record Cake as an owner-approved technical candidate with explicit unresolved synthetic teacher-source rights/consent; do not call it clean-provenance.
- [x] Prepare a reproducible offline builder with pinned hashes/sizes for all 11 verified staged files.
- [x] Generate an ignored data pack and catalog-v1 candidate preserving existing packs and admitting only Cake sid0 Ngọc Lan and sid2 Quang Huy.
- [x] Run the focused builder checks (3 passed).
- [x] Root published data-only `voices-vi-cake-v1` and `catalog-v1`, verified public assets/hashes; latest APK release remains v0.3.1.
- [x] Reviewed catalog bundled in isolated debug source; both approved speakers generated PCM on API34 x86 and passed real two-APK Binder smoke. No APK published.
- [ ] Measure native compatibility, audio quality, memory, cold load and realtime factor on target hardware, including P650. No such evidence exists yet.
- [ ] Resolve or explicitly accept the teacher-source identity, rights and consent uncertainty before making a clean-provenance claim.

## Prepared artifacts

- Builder: `apps/android/tools/build-cake-voice-pack.mjs`
- Focused tests: `apps/android/tools/build-cake-voice-pack.test.mjs`
- Published assets: `apps/android/build/cake-lf/voices-vi-cake-v1/` — 11 files, 77,983,146 bytes total; tokens normalized to LF before native admission.
- Published catalog: `apps/android/build/cake-lf/catalog-v1/catalog.json` — schemaVersion 1, catalogVersion 2, 3 packs / 4 voices including the new Cake pack.

[Cake data release](https://github.com/LongLeo287/AniSub/releases/tag/voices-vi-cake-v1) and
[catalog release](https://github.com/LongLeo287/AniSub/releases/tag/catalog-v1).
Catalog SHA-256: `ac12f19e02bb11434ef4f8eb5fb155da9ad26631d345790e35eea02a3d0b26c0`.
Native tokenizer rejected the original CRLF token file; the reproducible builder now emits the
968-byte LF file, SHA-256 `e8a50ae0c75612d18cfcf3f90800dca79f961e3ed97f9574f3535194469187fd`.
Runtime preflight prevents that native process-exit class. Owner-approved INTERNET permission now
allows DownloadManager; the real probe fetched all 11 Cake files with exact lengths/hashes, and
catalog refresh passed. Probe verification is distinct from fresh Cake installation and P650 quality.

The builder verifies exact inputs and pin values before copying. Model weights are labeled MIT, the
sherpa-onnx conversion tokens Apache-2.0 and espeak-ng-data GPL-3.0-or-later. Cake's card reports
synthetic training audio distilled from OmniVoice/VoxCPM2 voice-cloning teachers; the source
speaker identities, rights and consent are not established there. Package size and metadata do
not establish runtime compatibility, quality or P650 performance.
