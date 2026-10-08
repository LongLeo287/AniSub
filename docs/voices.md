# AniSub voice packs (Android)

Packs are model DATA, downloaded after install (the default Vietnamese pack automatically at the first
run, others on the user's request) through Android's DownloadManager; the APK never contains model data
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

## en-ljspeech-medium, version 1 (release tag `voices-en-v1`, ANISUB-004)

| Item | Value |
|---|---|
| Voice | Piper `en_US-ljspeech-medium`, single speaker (female, US English), 22,050 Hz, VITS, espeak voice `en` |
| Engine | sherpa-onnx 1.13.8, same as the Vietnamese pack |
| Source | `https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-ljspeech-medium.tar.bz2` (sha256 `3dfb4b759d8be032a4903a9538d128b0fda2a06ab1de6cbc2d93a97e2dd83dba`) |
| Download | 11 files, 64,400,101 bytes (model 63,531,507) |
| Model license | MIT (rhasspy/piper-voices repository) |
| Training data | LJ Speech Dataset — public domain (https://keithito.com/LJ-Speech-Dataset/); trained **from scratch** by Bryce Beattie (MODEL_CARD), so no Lessac/Blizzard lineage |
| Phonemizer data | the SAME 8 espeak-ng-data files as `voices-v1`, byte-identical (SHA-256 equal) |

Why this voice: `en_US-lessac-*` uses the research-only Blizzard 2013 Lessac data; `amy` and
`libritts_r` are fine-tuned from lessac; `hfc_*` is CC BY-NC-SA. `ljspeech` (public domain data,
trained from scratch) is the most permissive medium-quality US English voice; `kristin` (LibriVox,
public domain) is the alternative.

Shared espeak data: sherpa-onnx initialises espeak-ng once per process with the first data path.
The app therefore copies the verified espeak files of a pack into one app-owned directory
(`files/espeak-ng-data`, `EspeakData`) and always passes that; both packs ship the same files, so
either pack serves both languages and deleting/updating a pack never pulls the data from under the
engine. Emulator check: vi then en, and en then vi, in one process (both orders) speak correctly.

Emulator (x86, NOT P650): en pack install from the staged files (verify + atomic install + native
smoke test "Hello.") 1.5 s; engine load 1.1 s; RTF 0.074-0.078 with 2 threads.

## Publishing `voices-en-v1` (owner/root) — PENDING, nothing uploaded

```powershell
# Staged already (git-ignored): dist/voices-en-v1/ljspeech-* (11 files). To rebuild them:
node apps/android/tools/build-voice-pack.mjs <dir>/vits-piper-en_US-ljspeech-medium dist/voices-en-v1 --pack en --catalog apps/android/app/src/main/assets/voice-catalog.json
gh release create voices-en-v1 --repo LongLeo287/AniSub --title "Voice packs: English v1" --notes "Piper en_US-ljspeech-medium for AniSub (see docs/voices.md)" dist/voices-en-v1/ljspeech-*
```

Upload exactly the 11 `ljspeech-*` files (list, sizes and SHA-256 in `dist/voices-en-v1/PUBLISH-PENDING.md`
and pinned in `assets/voice-catalog.json`). Until they are uploaded the English download in AniSub
0.3.0 fails with NETWORK (HTTP 404) and the Vietnamese pack is unaffected.

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

## Cake `vi-cake-piper-pgl-v4`, version 1 (candidate `voices-vi-cake-v1`)

Prepared from the owner-verified flat staging set with `apps/android/tools/build-cake-voice-pack.mjs`.
The script pins all 11 input sizes and SHA-256 values and rejects missing, additional or modified
files. The reproducible outputs are ignored under `apps/android/build/voices-vi-cake-v1/` and
`apps/android/build/catalog-v1/catalog.json`; this catalog is a candidate, not the APK-pinned
catalog and not yet a reachable release. The root task owns the data-only release publication.

| Item | Value |
|---|---|
| Voice | Cake `piper-pgl-v4`, Piper/VITS, 22,050 Hz; approved voices only: sid0 Ngọc Lan (female, north) and sid2 Quang Huy (male, north) |
| Source model | CakeByVPBank `piper-pgl-v4-vi_VN-version39_epoch39`; source-card weights claim MIT |
| Download | 11 files, 77,983,307 bytes total: model 77,101,122; tokens 1,129; model card 13,919; eight eSpeak files 867,137 bytes |
| Model license | MIT for weights as stated by the Cake model card; this does not establish rights to the teacher voices or synthetic training data |
| Conversion tokens | Apache-2.0, attributed to the sherpa-onnx conversion |
| Phonemizer data | Eight-file Vietnamese/English espeak-ng-data subset, GPL-3.0-or-later |
| Candidate release | `voices-vi-cake-v1`; URLs use the exact filenames in the ignored candidate asset directory and remain unavailable until root publishes them |
| Catalog candidate | `catalogVersion: 2`, existing packs retained unchanged, new pack `vi-cake-piper-pgl-v4` version `1` |

The source card says the training audio is synthetic and distilled from OmniVoice and VoxCPM2
voice-cloning teachers. It does not establish the source speakers' identities, rights or consent.
That limitation is stated in the candidate catalog attribution; it remains unresolved. Only sid0
and sid2 are admitted here; sid1, sid3 and sid4 are excluded. The 77.1 MB model size says nothing
about total package size, P650 memory, realtime performance or voice-quality acceptance. Those
remain separate gates. No APK catalog change or app bundling is part of this candidate build.

To reproduce the candidate outputs from the verified staged directory:

```powershell
node apps/android/tools/build-cake-voice-pack.mjs <flat-stage-dir> apps/android/build/voices-vi-cake-v1 apps/android/app/src/main/assets/voice-catalog.json
node --test apps/android/tools/build-cake-voice-pack.test.mjs
```

Before publication, the root task should confirm the final 11-file directory against the pinned
manifest in the builder and create only the data release tagged `voices-vi-cake-v1`. Until then,
the catalog's release URLs intentionally do not resolve.
