# AniSub working rules

Read README.md and docs/system-design.md before implementation. docs/file-map.md defines
module ownership; docs/protocol.md defines the wire contract. Update docs/roadmap.md with evidence.

AniSub is independent of AniBox. Do not edit AniBox, bind its player, or copy its settings,
database, tokens, provider URLs or credentials into AniSub unless specifically requested.
reference/ is historical material, excluded from compilation and product dependencies.

Write a task packet with allowed files, non-goals, acceptance and rollback before non-trivial
changes. Use a scoped implementer and independent reviewer; root owns integration and validation.
Keep runtime engine work separate from architecture claims. No commit/push/release unless requested.

No model downloads, engine installation or capture permission changes in the design phase.
All queues and storage need explicit limits. Invalidate obsolete sessions/revisions before
accepting async results. Never log subtitle content, raw capture or credentials by default.
