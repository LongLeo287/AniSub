# ANISUB-SYNC-007 — fix late, skipped, cut-off and abrupt narration

2026-10-07. User requests implementation/model improvement after hearing dropped/truncated,
late Vietnamese narration and hard in/out on actual movie. AniSub no Git baseline; inventory
current source/models and preserve movie/AniBox. Existing JP-only playback cache + VN SRT used.

Root cause: cue-key changes/expiry cancel active speech; generation starts only after cue appears;
latest-pending overwrite loses dialogue; volume jumps. Fix coordination before choosing new model.

Allowed: App.cs, new Windows narration coordinator/pure timeline helpers/tests; TranslationBridge.cs,
windows_worker.py and root evidence/launcher/docs. Implementer owns App.cs + coordinator/timeline
and C# tests; root owns bridge/worker/Python tests/launcher/docs. Reviewer read-only.
No AniBox/model download/cloud/source movie mutation, no new remux, no forced process-wide kill.

Contract: one persistent inference job; <=6 ready/future clips, <=12s media lookahead, <=16 leased
WAVs, explicit release after playback/discard. WAVs never evicted while leased. Return AudioDurationMs.
RequestAsync(op,text,targetMs=0) adds target budget for synthesis; release op text = owned WAV path;
probe-voice prepares TTS only. Worker 16 steps instead of8, bounded atempo speed-up <=1.35,
short edge fade, no waveform truncation or silent text shortening. All errors/counters redacted.

Coordinator separates caption display from output ownership. Pre-generate/pre-open clips and
start on media clock; don't cancel accepted speech when subtitles change/clear/expire. Complete
utterance naturally. Controls/source/input changes still cancel safely. Fade original volume
~150ms down /~350ms up with smooth ramp, and fade output on explicit cancellation. Restore exact
baseline, prevent stale events from releasing a newer duck lease. Respect offset/playback speed.

Default explicit UI 'read complete / sync wait' mode: if prepared clip isn't ready or preceding
speech overruns, hold video at current boundary while speech continues; resume only for same
revision/user-playing state. Show sync-wait status. This trades short pauses for no lost dialogue,
not zero-latency/lip-sync. Hold timeout bounded; provider failure pauses with visible error, not
silent fallback. Optional realtime mode may skip obsolete items only with visible drop counters.

Acceptance: synthetic/fake-clock late inference/cue expiry/overlap/seek/pause/offset/speed/EOF,
bounded queue/audio release, smooth duck ramps and no speech cut at caption transition. Existing
9 parser tests +30 Node tests +real model waveform/bridge tests. Real movie counter test >=60s,
measure lateness/held time/start/complete/cancel/drop/error; do not claim human audio-quality score.
Before restart close ONLY verified AniSub narration process. Restart new session for user.
Rollback scoped source/docs; preserve old immutable models, original/cache video and VN SRT.
