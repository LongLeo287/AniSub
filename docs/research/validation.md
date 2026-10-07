# Research validation — 2026-10-06

Root checks and independent read-only review completed:

- CSV parse: 1,693 source inventory rows; 126 lexical candidate rows.
- Local relative Markdown links: resolve across the AniSub workspace.
- Report consistency: scope distinguishes metadata scan, selected code inspections, docs claims
  and proposed decisions. Supplemental upstreams and unverified host/model gates are explicit.
- Independent upstream spot-check: pinned audio.cpp VieNeu port documentation confirms the
  streaming/CAM++ gap. Reviewer did not re-audit every upstream or run any engine.
- Architecture: preserved direct text first, client clock/output policy, revision guards,
  terminal-only old speech cleanup and separate model/dependency licenses.
- AniBox read-only status check: clean on feat/anisub-integration at
  fae9e87d07adb0a8c6739e32152f9a31b77934cc. No AniBox edits in this research task.
- AniSub remains a local design workspace without .git; no commit/push/remote creation.

Build/tests: not run; changes are research/design/CSV documents, with no executable runtime
project introduced. No inference, benchmark, emulator or physical-device evidence exists.
No engine installation, weights download, capture or sheet mutation performed.

Changed existing docs: README.md, docs/system-design.md, docs/roadmap.md. Added research files:
README.md, task-packet.md, two CSV snapshots, three domain reports and this validation record.
Rollback is removal of these research additions plus reverting the small linked design/roadmap
paragraphs; leave the prior scaffold and reference sketch intact.
