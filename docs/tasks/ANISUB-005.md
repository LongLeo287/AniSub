# ANISUB-005 — voice/model manager, 2026-10-08

Baseline: `f0b77f5` (AniSub main, clean intake, v0.3.0 / Messenger 1.2).
Isolated branch/worktree: `codex/anisub-005`, `AniSub-wt/C-voice005`.
Historical WIP: `8fcf6a3` on `wip/anisub-manager`, explicitly uncompiled; inspect and reuse selectively,
never merge it wholesale. Existing R-translate worktree and both repositories' main checkouts stay untouched.

## Owners and pipeline

Owner-requested routing: Astra xhigh designs the catalog/legacy-wire contract; Sol high owns Android
implementation; Luna high independently reviews. Root integrates, validates and reports. VOICE-002
is independent Luna-high primary-source research, documentation only.

## Acceptance

- Reviewed bounded bundled catalog plus validated GitHub-hosted catalog-v1 update via Android
  DownloadManager with caller INTERNET permission (owner authorized 08-10 after on-device failure).
  Failed/malformed updates retain the last good catalog.
- Preserve Messenger major 1 / minor 2, all legacy capability meanings, caller/signature checks,
  session/revision invalidation and old OPEN requests. Add optional voiceId and metadata only.
  Legacy engine android-system-tts must still report translation=false and multiSpeaker=false.
- TV voice manager: actual gender/accent/license/size/state; download consent, remove/enable/default/
  preview; never remove or disable the last usable default-language voice. Vietnamese auto-download
  remains; English/new voices are optional.
- Cake piper-pgl-v4: only approved sid2 Quang Huy and sid0 Ngoc Lan, fp32; one model resident.
  Source/consent uncertainty disclosed. Do not publish assets or invent hashes/URLs/performance.
- Local-only Android system voices remain explicitly system voices, not AI. Unknown gender must
  not become verified metadata merely from an estimate.
- Per-voice rate 0.8–1.3, bounded pitch/volume, expressiveness load settings, pause controls,
  language priority, storage controls, status/license/about. Preserve active model leases and
  last-known-good files; stale downloads/prepares never promote obsolete state.
- Pure-Java Sonic reuse requires source/license attribution and before/after APK/ABI evidence;
  no additional native dependency. No version bump, signing, release or push.

Allowed: apps/android/** owning runtime/settings/voice/ai/catalog/tests/tools, protocol/** schemas,
tests/fixtures/android-v1/**, protocol/addon docs, THIRD_PARTY_NOTICES.md, README files,
docs/tasks/ANISUB-005.md, docs/roadmap.md and scoped validation/research reports.
Forbidden: all AniBox edits, Windows/Chrome modules, signing keys/.local, unrelated update checker,
release manifests/version changes, unapproved voice packs/native additions.

## Gates and rollback

Design approval before source integration. Focused catalog hostility/default-voice/removal/OPEN/
legacy-capability tests, full Android JUnit + dependency-free contract gate, Node/schema tests,
debug build/lint, size/ABI comparison, disposable emulator D-pad/BACK/voice-state smoke. Real model
quality/P650 memory/RTF remains separate hardware evidence. Runtime/cross-app compatibility uses
read-only old AniBox parser evidence, never changes that client to make a broken reply pass.

Rollback: remove only this isolated task's diff; main remains untouched. No commit/push authorized.

## Tracker

- [x] Clean baseline and historical WIP inventory.
- [x] Contract/catalog design approved (independent read-only architecture review).
- [x] Scoped implementation and regression tests (159 Android JUnit tests; public download checks pass).
- [ ] Independent read-only review.
- [ ] Root build/contract/lint/size/emulator gates.
- [x] VOICE-002 dated primary-source research and optional training plan (documentation only).
- [x] Published catalog/voice assets (owner authorized data-only releases; public hashes verified).
- [ ] P650 physical hardware evidence.

### Current root checkpoint — 2026-10-08

Source, Android build/lint and 158 JUnit tests pass; legacy gate 4,296 checks, Node 34 tests and
Cake builder 3 tests pass. Both admitted Cake speakers produced non-empty PCM on API34 x86;
the real AniBox-to-AniSub Binder test completed both explicit voice IDs (OK, one instrumentation
test). This is not P650, listening-quality or realtime-factor evidence. Independent review is
partial: Luna reported three findings, all corrected by root, then stopped on workspace quota;
no final independent approval exists and no failed agent was retried.

**Historical blocker, owner authorization received subsequently:** DownloadManager requires the caller's INTERNET permission.
API34 DownloadProvider rejects enqueue with `SecurityException`, explicitly naming
`android.permission.INTERNET`. This affects catalog refresh and voice downloads, not only Cake.
The handoff forbids this permission, so root did not change the manifest or silently relax the
boundary at that checkpoint. Owner has now approved the permission; actual download validation follows.
The pinned catalog is bundled and manually staged verified Cake works; that does not prove downloads.

Data-only releases `voices-vi-cake-v1` and `catalog-v1` are published; no APK, version bump,
commit or source push. Detailed evidence: `../agents/VOICE-TASKS-2026-10-08.md`.

Subsequent owner-approved INTERNET correction: final build/lint and 159 tests pass. On emulator-5564,
DownloadManager fetched and hash-verified all 11 Cake files; production VAIS auto-install/smoke passed.
Catalog refresh reported the already-current snapshot correctly and restored D-pad focus. Both Cake
IDs passed the real AniBox Binder test again. This closes the download permission blocker, not the
remaining independent-review/P650/comprehensive-UI acceptance gates.

### Cake asset-builder checkpoint (2026-10-08)

Added a strict, offline Cake asset builder and three focused Node checks in
`apps/android/tools/build-cake-voice-pack.mjs` and its `.test.mjs`. It accepts exactly the 11
owner-verified flat staged inputs and checks each pinned size/SHA-256 before copying. The ignored
output is `apps/android/build/voices-vi-cake-v1/` (11 files, 77,983,307 bytes); the ignored candidate
catalog is `apps/android/build/catalog-v1/catalog.json` (schema 1, catalogVersion 2, existing packs
preserved, one new Cake pack with only sid0 Ngọc Lan and sid2 Quang Huy). URLs are pinned to the
requested `voices-vi-cake-v1` release filenames but are not yet reachable. Model-card disclosure
of synthetic OmniVoice/VoxCPM2 teacher data and unresolved source-speaker rights/consent is included
in the catalog attribution. Weights are labeled MIT, conversion tokens Apache-2.0, and espeak-ng-data
GPL-3.0-or-later; these labels do not resolve the speaker-provenance issue. No APK assets or Java
files were changed in this asset-builder subtask.

Evidence: builder completed and `node --test apps/android/tools/build-cake-voice-pack.test.mjs`
passed 3/3. Root-owned publication and app catalog bundling remain pending; no Gradle/device or
P650 evidence was produced here.

## Intake evidence

### Root metadata and TV UI checkpoint (2026-10-08)

Implemented bounded optional AI gender/accent/enabled/default metadata and a separate local
`systemVoices` inventory, with mandatory legacy fields preserved. Added optional `voiceId`
schema/documentation without changing Messenger 1.2. TV manager now provides voice information,
preview/default/enable/download/delete and per-voice rate/pitch/volume. AI reading controls expose
style, sentence gap and 0–1,000 ms extra silence, snapshotted for the next preview/session.
System preview utterances have unique IDs; obsolete terminal events and queued UI callbacks
cannot release a newer preview. This callback path still needs a stress test.

Latest root gate: **142 JUnit tests, 0 failures/errors; debug ARM/x86 builds pass; lint
0 errors / 1 pre-existing ApplySharedPref warning; legacy gate 4,296 checks; Node 34 tests;
git diff --check pass.** ARM debug APK 37,091,130 bytes (+36,001 vs intake), six native entries
byte-identical to intake. Final wording-only change to the rate row is verified in the next
build; these measurements are not release evidence. Final wording build also passes tests,
assemble and lint; its ARM debug APK is 37,181,719 bytes (+126,590 vs intake). Debug APK size
varies across rebuilds, so the latest concrete artifact, not the earlier delta, is the handoff.

Disposable `Codex_Followups_API34` / emulator-5564: installed x86 debug build; D-pad reaches
the voice inventory and actions; BACK returns from rate selection to voice actions, then to
inventory; reading subdialog BACK returns to reading menu. Download consent initially focuses
Huy/Cancel. XML/screenshot evidence is under ignored `apps/android/build/evidence/voice005/`.
UI checks were offline, with no AI pack installed. System-preview action was exercised without
a crash, but audible output/quality and callback stress are not established. No P650 evidence.
Other emulator-5554 and all AniBox workspaces remain untouched.

Remaining: complete catalog/voice/storage and residency acceptance matrix; priority/duck settings
need an honest client contract before claiming AniBox behavior; admitted Cake candidate must not
receive invented publication URLs; independent reviewer is unavailable after workspace-credit
failure. No agent retry/model substitution. Whole task stays WIP; no commit/push/release.

### Interrupted implementation checkpoint (2026-10-08)

**Root corrective checkpoint, subsequent run:** restored superseded-version cleanup with explicit
last-known-good protection; leased models remain protected by ModelStore removal. Added tests
for LKG retention through update/restart, relative-path rejection, malformed/stale catalog
completion and rename rollback, voice selection/aliases/kind/language, and residency ownership.
Catalog admission now releases its single-flight slot on validation or persistence failure.
Replaced `List.sort` with `Collections.sort` for API 23. Current source builds successfully:
**134 JUnit tests, 0 failures/errors; debug assemble pass; lint 0 errors / 1 warning;
4,296 legacy gate checks; Node syntax + 34 tests pass; git diff --check pass.** ARM debug APK
37,143,900 bytes (+88,771 vs intake). This checkpoint supersedes the two-red-test status below,
but does not complete the UI, schema/metadata, independent review or device gates.

The independent reviewer stopped with a workspace-credit error; the agent team is no longer
running. Do not retry failed agents or substitute models contrary to the owner's routing.
Implementation remains uncommitted WIP, not accepted or safe to integrate. Root fixed the
Android-only compile error from `JSONObject.valueToString` using the public JSONArray encoder.
The next root gate compiled source but **119 JUnit tests ran with 2 failures**:
`VoicePackManagerTest.previousVersionKeptUntilRestartAfterNativeUse` and
`VoicePackManagerTest.failedUpdateKeepsPreviousVersionUsable` (version cleanup expectations).
The gate stopped at tests; APK/lint/emulator results for this WIP are not established.

At this earlier checkpoint the UI/schema and two regressions were still incomplete. They have
since progressed as documented above; the complete contract/storage/catalog/residency matrix
and independent review remain required. Baseline evidence below is not current WIP acceptance.

Approved contract: Messenger remains 1.2. Legacy capability fields keep their exact meanings
(including strict older AniBox parser fixtures); optional metadata never exceeds the existing
16,384 UTF-16-unit reply bound. `voiceId` is optional; null is absent, conflicting aliases are
MALFORMED, wrong-kind/language or disabled IDs are UNSUPPORTED; a known absent AI pack remains
VOICE_PACK_MISSING. AI never falls back silently to system TTS. Defaults are separate by kind
and language. Voice/model/style are snapshotted per OPEN/preview.

Remote catalog: single-flight/generation, GitHub-owned fixed endpoint, validated before atomic
last-good replacement, strictly increasing catalog version, immutable existing pack/version
facts, 16 packs / 64 total voices / 64 files per pack / 512 MiB per pack. Existing Vietnamese
bootstrap remains pinned; additions require consent. Catalog apply waits for physical engine
unload and idle session/preview/download state. Registry removal rechecks compatible, verified,
enabled AI replacements; system voices never satisfy the AI-default deletion guard.

Review gates cover legacy capability combinations; OPEN alias/type/kind/language/default cases;
relative registered/LKG/leased paths; hostile catalog and failed persistence; one-resident-model
races across preview, OPEN and download verification; stale SEEK/CLOSE/Binder/prepare results.

2026-10-08 root baseline: Android debug build, lint and **119 JUnit tests** pass (0 failures,
errors or skips); dependency-free gate **4,296 checks**; Node syntax check and **34 tests** pass.
Standard ARM debug APK: **37,055,129 bytes**. Native entries: arm64-v8a 3 files /
41,549,960 expanded bytes; armeabi-v7a 3 files / 28,655,728 expanded bytes. The baseline APK
is retained only under ignored `apps/android/build/evidence/voice005/` for the final delta.

Read-only architecture review identified two additional P0 regression gates in allowed scope:
one residency arbiter must also guard download smoke tests; `ModelStore.reclaimOrphan` must
reject relative paths or canonicalize before every registered/LKG/lease comparison. Neither
finding is considered fixed until source and regression tests pass independent review.

VOICE-002 report: `docs/research/VOICE-002-2026-10-08.md`. No clean-provenance, deployable
male/southern/central model under approximately 80 MB was found. Cake sid0/sid2 remain the
owner-approved technical candidates, not proof of source-speaker consent or P650 performance.
