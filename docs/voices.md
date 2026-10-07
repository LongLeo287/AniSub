# AniSub voice packs (Android)

Packs are model DATA, downloaded on first use after consent; the APK never contains model data
and a pack can never carry code (`.so`, `.dex`, scripts are rejected by the manifest validator).
The catalog is pinned inside the APK (`apps/android/app/src/main/assets/voice-catalog.json`):
every file has a size, SHA-256 and HTTPS URL on an allowlisted host. Changing a pack requires a
new APK release with a reviewed catalog.

## vi-vais1000-medium, version 1 (release tag `voices-v1`)

| Item | Value |
|---|---|
| Voice | Piper `vi_VN-vais1000-medium`, single speaker, 22,050 Hz, VITS |
| Engine | sherpa-onnx 1.13.8 (`OfflineTts`, VITS config, espeak-ng phonemizer) |
| Source | `https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-vi_VN-vais1000-medium.tar.bz2` (sha256 `fa1367710767d36ed5cf13b4a449e20c35ffd12791c2e47c2e64142bfa55551a`) |
| Download | 11 files, 64,017,617 bytes (model 63,149,198) |
| Model license | MIT (rhasspy/piper-voices repository) |
| Training data | VAIS-1000 Vietnamese Speech Synthesis Corpus, CC BY 4.0 (attribution in the app's About screen) — https://ieee-dataport.org/documents/vais-1000-vietnamese-speech-synthesis-corpus |
| Base checkpoint | fine-tuned from Piper `en_US-lessac` (medium). The Lessac/Blizzard 2013 data behind that base voice has its own research-oriented terms; flagged for owner review before any commercial use. AniBox/AniSub are free, non-commercial apps. |
| Phonemizer data | espeak-ng-data subset (phontab, phonindex, phondata, intonations, vi_dict, en_dict, lang/aav/vi, lang/gmw/en), GPL-3.0-or-later |

Why this voice: of the three upstream Vietnamese Piper voices, `25hours_single-low` has an
**unknown** dataset license and `vivos-x_low` is **CC BY-NC-SA 4.0**; `vais1000-medium` is the only
one with a permissive data license, and it is the higher-quality (medium, 22 kHz) model.

Trimmed phonemizer data: the full espeak-ng-data (392 files, ~18 MB) was reduced to the 8 files
Vietnamese text actually reads. With noise disabled (deterministic synthesis) the output for 7
mixed Vietnamese/name sentences is bit-identical to the full data set. `en_dict` is required:
without it espeak logs a missing dictionary and phrase timing changes.

Precision choice: the int8 variant (21.6 MB download) was measured **2.7x slower** than fp32 on
x86-64 (RTF 0.18 vs 0.067, 1 thread) because VITS is convolution-heavy; fp32 was chosen for
latency. int8 remains a candidate if P650 (Cortex-A35) measurements favour it.

Host measurements (Windows x86-64 desktop, NOT TV evidence): native load 0.6-1.1 s, warm RTF
0.067-0.076 (1 thread) / 0.042-0.056 (2 threads), first PCM p50 135-197 ms for <=200-character
cues. Emulator and P650 numbers are pending; the app uses 2 inference threads on 4-core devices.

## Publishing `voices-v1` (owner/root)

```powershell
# Extract the verified upstream archive, then:
node apps/android/tools/build-voice-pack.mjs <dir>/vits-piper-vi_VN-vais1000-medium dist/voices-v1
gh release create voices-v1 --repo LongLeo287/AniSub --title "Voice packs v1" --notes "Piper vi_VN-vais1000-medium for AniSub (see docs/voices.md)" dist/voices-v1/vais1000-*
```

Upload exactly the 11 `vais1000-*` files; the URLs and digests are already pinned in the catalog.
Upstream fallback: sherpa-onnx publishes this voice only as a `.tar.bz2` archive and Hugging Face
hosts a different (unconverted) ONNX file, so no byte-identical per-file upstream mirror exists.
The catalog therefore lists the AniSub release URL only; a second allowlisted mirror (for example
a Hugging Face repo with the same files) can be added later as an extra `urls` entry.
