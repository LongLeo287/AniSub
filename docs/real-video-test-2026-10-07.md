# Real local MKV test — 2026-10-07

User-authorized input: episode file in `D:\ANIME\Library\Tensei Shitara Slime Datta Ken\SS2\raw`.
The original file was opened read-only; no remux, subtitle write, transcode or model download.
No dialogue/subtitle text is persisted in this report. AniBox/product source unchanged.

## Test plan and coverage

| Area | Type / case | Result and scope |
|---|---|---|
| Container/streams | ffprobe metadata | 1423.104 s, 2,994,954,002 bytes; 3840x2160 HEVC; FLAC stereo Japanese 48 kHz and Vietnamese 44.1 kHz |
| Text-track availability | Decode each embedded track to SRT in memory; count timings without logging text | Track 3 ASS: 470 cues; track 4 SubRip: 362 cues. Both nonempty; languages not tagged, completeness/translation quality not graded |
| Demux/decode | FFmpeg 10 s video + Japanese-audio sample starting 180 s | Exit 0; sample decodes, not whole-episode corruption validation |
| Windows host playback | Existing no-provider WPF smoke on original MKV | PASS MediaOpened/play/pause/seek/speed; caption-only; speech starts=0 finishes=0. Measures media-clock behavior, not visual frame quality or physical hearing |
| Embedded subtitle ingestion by AniSub | Product capability audit | Missing: prototype imports sidecar SRT/VTT, does not automatically enumerate/extract embedded tracks |
| No-subtitle ASR pipeline | Product capability audit / negative coverage | Not implemented; this file also is not genuinely subtitle-free. Playback pass is NOT speech-recognition/translation/narration evidence |

The user supplied a genuine useful test file, but its embedded tracks contradict the initial
no-sub assumption. No synthetic English cue was injected into this test. Earlier synthetic
AI tests are separate evidence and do not establish Japanese movie translation quality.

## Next test gates

1. Explicitly implement/authorize embedded-track selection and bounded extraction; then use real
   text cues and their actual language. Current translation provider is EN→VI only.
2. For no-subtitle behavior, explicitly select Japanese audio and suppress direct tracks in a test
   session (without altering the original file), or select another truly subtitle-free input.
3. ASR and Japanese translation capabilities require new implementation/model choices, timed
   Japanese dialogue benchmarks, no-speech/music/hallucination cases, seek cancellation and
   bounded audio buffers. No automatic cloud fallback or native-model download was performed here.

Rollback: remove this report only. No source media/product code changes to roll back.
