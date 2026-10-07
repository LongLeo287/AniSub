# ANISUB-DESIGN-003

2026-10-06. User priorities: lightweight, fast realtime video translation/dubbing/narration;
AniBox plugin, Windows application, Chrome extension; independently updatable AI models.
Baseline: local design v0.1, no Git/build/runtime. AniBox integration remains deferred.

Allowed: docs/realtime-product-design.md (specialist), canonical docs/README links, file-map,
roadmap, new scaffold README files (root). Reviewer read-only. No AniBox edits, engines/models,
installer/registry/browser changes, downloads or remote writes. No capture/permissions granted.

Acceptance: three surfaces with thin-client ownership; no mandatory server; direct text first;
bounded realtime policies and honest latency targets; narration vs dubbing tradeoffs; trusted,
compatible model update/rollback without swapping live sessions; Chrome primary-source caveats;
folder ownership, rollout priorities and explicit planned status. Semantic protocol preserved.

Rollback: remove added docs/scaffold READMEs and revert only this task's design/link/map/roadmap
paragraphs. Preserve reference/ and all prior research. Validation is docs consistency and links,
not build/runtime/device evidence. Root integrates; no commit/push/release.

Validation: independent read-only reviewer found no material blocking inconsistency; local
Markdown links pass. AniBox worktree remains clean on feat/anisub-integration, baseline
fae9e87d07adb0a8c6739e32152f9a31b77934cc. No build/tests were run for these documentation-only
changes; no engine/model/installer/capture evidence is implied. New product design and four
scaffold README files exist; canonical system-design, README, file-map and roadmap were updated.
