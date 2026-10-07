# Real Vietnamese-subtitle narration session — 2026-10-07

User requested narration of their Slime episode with Japanese audio and Vietnamese subtitles.
Input is not subtitle-free; no ASR or additional translation/model download is needed.

Chosen source: embedded stream 4, SubRip, 362 timing cues and 2269 Vietnamese-specific
diacritics in the decoded text; stream 3 ASS has no Vietnamese-specific diacritics. Language
selection is confirmed for this explicit file, not a universal automatic detector.

The original Japanese audio is stream 1, but Vietnamese stream 2 has default disposition.
WPF prototype lacks audio-track selection. Therefore create ONE separate local cache in
`work/slime-japanese-playback.mkv`, copy video + Japanese audio without re-encoding; verified
HEVC + FLAC/jpn, no other audio/sub tracks, 2,885,459,176 bytes (~2.69 GiB). Original unchanged.
Selected SRT is `work/slime-vietnamese-track4.srt`, 31,421 bytes. Cache is ignored local data,
not a distributable film or product payload; delete explicitly when no longer wanted.

Added source:
- `apps/desktop/windows/App.cs`: guarded real-cue start and text-free diagnostics.
- `apps/desktop/windows/Start-Narration.ps1`: explicit local session config, visible WPF player,
  model preparation, VI→VI playback, one-second counter/position readiness file.
- `Narrate-Slime.cmd`: reopen this configured episode in narration mode from the beginning.
- `docs/narration-task-packet.md`, this evidence and roadmap entry.
- `work/narration-session.json`: ignored local paths/position, no model/subtitle text in logs.

Tests: .NET source compilation + parser/lifecycle 9/9 pass. Real session execution checks
player clock and accepted speech starts from actual imported Vietnamese cues. Readiness path:
`work/narration-ready.txt` (counts/position/playing only). Failures report exception type, not text.
Independent source review is separate from execution evidence.

Observed live session: process 42016, responsive WPF window; readiness recorded
`cues=362 positionMs=18703 playing=True speechStarts=8 speechFinishes=8 closed=False`.
These are actual output requests, not synthetic cue injection or physical speaker measurement.
Player intentionally remains open for the user. Independent review caught canceled startup tasks
being reported as READY; launcher now checks IsCanceled before success and reports CANCELLED.

Historical sync006 limitations (superseded by [sync007](narration-sync-validation.md)):
completed-cue generation, no speech time-stretch/prefetch; expiry or next cue
stops old narration, so short/fast dialogue can be cut or skipped. No physical hearing/voice quality
score; no assertion all 362 cues will be narrated. The prototype probe loads MT too despite
translation being disabled; voice-only preparation and proper player track selection are future
optimizations. A cached playback file is an explicit prototype workaround, not product architecture.

Rollback: close this task's player; remove only the above additions/revert launch method. Cache
and extracted subtitle can be removed after closing the player, preserving the original media.
Do not stop unrelated PowerShell/Python processes or delete AniBox/model/user folders.
