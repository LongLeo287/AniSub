# Selected source capture

`windows_capture_worker.py` is a serial JSON-lines subprocess, one request at a time,
64 KiB frame limit. Each `ocr` / `asr` requires `consent:true`, selected `pid`, numeric
`hwnd` and .NET UTC process `startTicks`. HWND ownership and process creation identity
are verified before and after inference. `stop` resets dedup; the owning host invalidates
session/revision immediately and kills its worker if a running request must be aborted.

OCR uses PrintWindow into an isolated selected-window DIB, crops a normalized `roi`
([x,y,width,height], default [.05,.65,.9,.3]) and Windows OCR in memory through
`windows_ocr.ps1`. No global desktop capture or raw image/audio file is written.
Maximum 2 FPS admission, 16 megapixel source, crop downscaled to 2048 pixels wide,
unchanged-image gate with 2-second retry, exact NFC text dedup. Blank OCR resets text
dedup; changed words/numbers/negation are never fuzzy-suppressed. Accelerated/protected
windows may render black or deny PrintWindow: this is not universal game capture.

OCR languages are OS capability, not automatic download. This machine currently has
en-US only; Vietnamese/Japanese requests return OCR_LANGUAGE_UNAVAILABLE, never quietly
claim those languages. A separate reviewed multilingual OCR pack is still required.

ASR uses `process_audio.py`: Windows application-loopback include-selected-process-tree
API, Windows build >=20348, never global/endpoint loopback. Child processes are included
(important for browser rendering); browser tab-only audio requires the companion route.
Continuous capture runs on one background thread, independently of ASR inference.
The selected chunk duration is 1..8 seconds, default 4, one transient PCM accumulator
plus a maximum two queued chunks and the active inference chunk. Overflow discards
the oldest pending chunk and reports cumulative `droppedChunks`; losses are never hidden.
Switching sources/input or stop tears down the capture client and clears pending PCM.
`--asr-model PATH` loads a preinstalled faster-whisper compatible local CPU int8 pack;
no implicit Hub download. Non-Vietnamese Whisper translate outputs English, with original detected
language recorded separately, for EN→VI downstream translation. Explicit Vietnamese uses
transcription and retains Vietnamese; Auto that detects Vietnamese reruns that same PCM
in transcription mode (extra inference and possible language-detection mistakes remain).
This is delayed chunk
recognition, not verified subtitle timestamps or low-latency streaming ASR.

Requests: `probe`, `status`, `stop`, `ocr`, `asr`; responses echo `id` and return
`ok,text,language,sourceLanguage?,origin,observedMs` or typed `error`. Probe reports OS
API availability, not a successful protected-app capture or recognition-quality guarantee.
Do not log returned text; host queues and revision guards remain mandatory.

Platform reference: [Microsoft application loopback sample](https://github.com/microsoft/Windows-classic-samples/tree/main/Samples/ApplicationLoopback).
