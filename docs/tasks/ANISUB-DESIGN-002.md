# ANISUB-DESIGN-002 — standalone runtime design

Date: 2026-10-07. User: build AniSub system design; Claude owns AniBox addon.

Baseline: local AniSub workspace has no .git directory, therefore no baseline SHA or branch.
Existing Windows prototype and Android major-1 system-TTS test companion are retained.
Read README, system-design, protocol, file-map, roadmap and Android service/manifest first.

Allowed files: README.md; docs/system-design.md; docs/protocol.md; docs/file-map.md;
docs/roadmap.md; docs/android-addon-contract.md; this packet;
docs/superpowers/specs/2026-10-07-anisub-runtime-design.md.
Root writes spec/contract/packet/canonical design. Scoped specialist reconciles other documents;
independent read-only reviewer checks consistency, existing source and scope.

Forbidden: any AniBox edit; runtime source changes; model/native downloads or installations;
capture permission changes; running/restarting user apps; signing, commit, push or release.

Acceptance: concrete ownership/folder map; current-versus-proposed status; bounded queues,
storage and cancellation; model licensing/update gates; draft Claude contract; phased measurable
acceptance; no claim that AI runtime is already implemented. Check document links and independent
review. No new build/test success is claimed from a documentation-only change.

Rollback: remove the three new design/contract/packet documents and revert only this packet's
document edits. Existing applications, models and evidence are unaffected.
Stage: design documentation; written-spec approval precedes implementation planning, which
itself needs review and execution-method selection. No implementation approval inferred.

Validation 2026-10-07: 35 local Markdown links checked, zero missing; no TODO/TBD/FIXME
placeholders in the eight scoped documents. Independent reviewer checked private v1 against
Android source and identified clock/idle-model ambiguities; root corrected them in spec/contract.
Final review: no remaining blocking findings. This is design consistency only, not a runtime test.
Changed files: the eight allowed documents above. No build rerun, model/native download, permission
change, code edit, signing or release. Existing Windows app and AniBox remain untouched.
