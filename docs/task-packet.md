# ANISUB-DESIGN-001

Date: 2026-10-06. User direction: build AniSub architecture first; no AniBox attachment.
AniBox baseline: fae9e87d07adb0a8c6739e32152f9a31b77934cc, feat/anisub-integration.
At intake, only our prior PlayerActivity hooks + anisub package/tests/document were dirty.
The earlier turn observed untracked m.m3u8/v.m3u8; they were already absent from git status at
this intake. This packet did not operate on those paths and makes no claim about their provenance
or disposition outside this turn.

Allowed: new D:/SEOSONA AI/AniSub docs/module README files; remove our six PlayerActivity additions;
move our prior untracked package/tests/document to AniSub/reference/anibox-integration-draft.
Forbidden: unrelated AniBox changes, source/engine edits, native/model payloads, installations,
capture/permission changes, remote Git creation, commit/push/release.

Acceptance: standalone workspace, one canonical system design, clear module/file ownership,
versioned protocol proposal, staged evidence-backed roadmap, retained prior sketch, clean AniBox
tracked diff. Verification: filesystem/link inventory, git diff/status and independent design review.
No build claimed: new workspace contains documentation/reference only; no executable runtime yet.

Rollback: restore retained draft to its original AniBox paths and reapply only the six recorded hooks
if a later authorized integration needs them. This packet moved only its previous AniSub draft;
it did not delete files. Next packet implements
phase 1 portability/contracts then phase 2 fake runtime.
