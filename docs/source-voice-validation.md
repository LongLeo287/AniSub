# Windows source and voice slice

Historical first slice. External adapters, 25 Turbo regional voices and Vietnamese OCR now
supersede these old availability limits; read full-desktop-validation.md for current evidence.

Scope: source selection and real installed Vietnamese preset selection. This does not yet connect an external application's media clock/subtitles/audio to narration. The existing local player remains the working narration test harness.

Read-only discovery identifies visible application windows. Auto is a source recommendation, not proof that a window is playing video. A Chrome/Edge window is not a selected tab. Selection does not start capture; no game injection, browser installation, registry changes or model downloads are performed.

The installed Nano preset metadata declares 11 voices with North/South regions and male/female genders. Central is unavailable in this installed pack. Region labels are publisher metadata, not independently graded accents. Keep Minh Quân as default. Foreign words are preserved rather than blindly respelled; waveform generation is not pronunciation acceptance. Human listening across Vietnamese diacritics, English code-switching and proper names remains required.

Next external-media acceptance: consented source-specific input adapter, stable clock or explicitly delayed live mode, source disappearance/PID reuse invalidation, bounded OCR/ASR, and original audio restoration. Dubbing with speaker attribution/original-dialogue isolation is separate from fixed-voice narration.

Root verification: 16 source-selection assertions; 14 voice metadata/filter/real bridge
assertions; 12 parser/lifecycle assertions (including external-source/local-prefetch boundary);
20 timeline/duck assertions; 16 real Dispatcher/MediaPlayer synthetic-fixture assertions;
30 existing Node assertions passed. Existing UTF-8/real MT/TTS bridge regression passed.
Two real mixed-language synthetic phrases generated finite 24 kHz audio with the requested
Minh Quân and Ái Hân presets; four invalid preset inputs were rejected before inference.
Waveforms were released, not retained as user content. No pronunciation grade is implied.
Final real Windows UI smoke passed MediaOpened/play/pause/seek/speed, EN→VI overlay and
Vietnamese WAV output with two starts/two finishes. This is a local synthetic video test,
not external application narration, whole-film acceptance or acoustic listening evidence.

Independent read-only review approved after root fixed the external-source boundary: binding
an external window retires local narration, disables local prefetch/caption updates and blocks
local Play until the source is cleared or an explicit local video is opened. Dedicated tests
cover the inactive state, not a full external-capture pipeline or in-flight engine soak test.

Cold combined model preparation took about 61 seconds in one concurrent test run. This is not
a clean latency benchmark; background workload and duplicate test preparation influenced it.
Startup/resident-memory profiling and avoiding competing model preparation remain production gates.
