# ANISUB-NARRATION-006 — start real Vietnamese subtitle narration

2026-10-07. User authorizes opening their local Japanese-audio/Vietnamese-sub MKV and
starting Vietnamese narration. No ASR/translation/download needed. No AniBox changes.
AniSub has no Git baseline; preserve existing source, model assets and original media.

Allowed: App.cs launch method, separate narration launcher, tests/docs and ignored session
config + extracted Vietnamese SRT. Original media is immutable. Its Vietnamese audio is default,
so make one bounded (<4 GiB) separate Japanese-only stream-copy playback cache in AniSub/work,
no transcoding, to ensure the Windows player cannot accidentally select Vietnamese source audio.
Implementer owns App.cs only; root launcher/config/extraction/validation; reviewer read-only.

Acceptance: choose Vietnamese embedded track by measured text evidence; parse bounds; prepare
offline model; load original video + real cues with VI→VI, translation disabled; start playback
and observe at least one actual speech output request without dumping cue text. Pause/close
keeps existing revision/output cleanup. Expose counts only in session-ready evidence.

Rollback: close only task-owned player process, remove new launcher/config/extracted subtitle,
revert scoped launch method/doc additions. Preserve source movie, models and unrelated files.
No claim of full-episode quality, every cue spoken, or physical speaker hearing. Current prototype
clips speech on cue expiry/new cue; state limitation rather than rebuilding scheduler in this task.
