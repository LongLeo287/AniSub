# Optional offline quality profiles

2026-10-07. Separate immutable data packs; Nano and current EN→VI packs remain untouched.
No engine source, remote Python execution, global pip installation or cloud speech fallback.
The reviewed catalog is `model-manager/quality-catalog.json`; explicit installation uses
`model-manager/tools/install_quality_models.py --install`. Without `--install`, it only
resolves pinned upstream metadata and prints total sizes. It is **not** an automatic/signed
update service. Download failures remove only their freshly-created GUID stage; already
installed/corrupt/unrecognized directories are never overwritten or repaired silently.

| Pack | Immutable directory under models/ | Payload bytes |
|---|---|---:|
| VieNeu v3 Turbo fp32 ONNX | vieneu-turbo-61b85e3 | 475,416,448 |
| MOSS Nano ONNX codec | moss-codec-ceff0d0 | 90,577,053 |
| faster-whisper Small multilingual | whisper-small-536b066 | 486,214,370 |

Total additional payload: **1,052,207,871 bytes**, below the 2 GiB new-pack budget. No second
full Hugging Face cache copy: `hf_hub_download(local_dir=stage)` downloads into the stage, compare immutable upstream
LFS SHA-256 or Git blob digest and length, record SHA-256 for every artifact, verify the full
manifest and atomically rename. Aggregate missing bytes plus 256 MiB free-space margin is
reserved before downloading. App/runtime installed dependencies are separate from these sizes.
If an upstream large-file CDN transfer stalls, `--ranged` explicitly selects a bounded fallback:
four concurrent 8 MiB range requests, 30-second per-request socket timeout and at most two
attempts per range. Validate exact HTTP206 Content-Range/length before writing; final artifact
hash is still mandatory. No unlimited retry/background update loop. Interrupted stages remain
unpromoted and may need explicit cleanup; never interpret their partial size as installation.
`--pack NAME --resume-stage EXACT_GUID_DIRECTORY` permits explicit bounded stage recovery:
every existing expected file is compared to pinned upstream length/digest before reuse. Any
invalid existing file stops installation without overwrite/deletion; no scanning/adopting
unknown folders. Fresh stages clean up their own failure, resumed stages are preserved.

## Local-only integration

Set HF_HUB_OFFLINE=1 and TRANSFORMERS_OFFLINE=1 before inference. Verify every manifest against
the trusted catalog/expected pinned identity, exact expected artifact set, size and digest.
ONNX engine accepts these local parameters:

```python
OnnxV3LiteEngine(
    checkpoint_path=str(turbo_dir),
    onnx_dir=str(turbo_dir / "onnx_update"),
    codec_dir=str(codec_dir),
    threads=2,
)
```

SDK 3.8.3 `V3TurboVieNeuTTS` does **not** forward `codec_dir` to the engine, despite accepting
kwargs. Do not call its convenient default constructor and let it fetch floating Hub assets.
The host needs a reviewed engine-construction adapter injecting the local codec directory,
then restores any constructor binding after load. No cloning: encoder/denoiser/speaker enrollment
features are not exposed; current requested voices use publisher-provided preset embeddings.
Inference outputs 48 kHz PCM; buffer limits must use actual sample rate, not Nano's 24 kHz.

ASR: `WhisperModel(str(asr_dir), device="cpu", compute_type="int8", cpu_threads=2,
local_files_only=True)`. This converts FP16 weights at load; disk pack remains FP16.
For Japanese or other supported source audio, Whisper `task="translate"` yields **English**,
then existing Marian EN→VI can supply Vietnamese. This is a two-stage translation with extra
latency/error propagation, **not** a native Japanese→Vietnamese translation model. Audio-source
language and voice-output language are different controls. Real-time performance is not established
by download completion. Never promise support for every language or simultaneous heavy providers
on low-RAM hardware; run caption-only if the selected profile cannot meet resource admission.

## Actual preset roster

Installed SDK3.8.3 asset `voices_v3_turbo.json` SHA-256:
`e1f13cd2c2e0d29fdab5e15bfd7a30f3d7b768e6132bb43dd2b853f8efefdce9`.
Publisher's metadata contains **25** voices (older model card says 23). Region derives from the
second description field, not the ambiguous Vietnamese word “Nam” used for both male/South.
Keep exact SDK names; do not invent aliases or pretend arbitrary languages have regional voices.

| Region | Male | Female |
|---|---|---|
| Bắc | Adam bựa, Thiện Minh, Hải Đăng, Thiền Tâm Đức, Minh Đức, Phạm Tuyên, Xuân Vĩnh, Thanh Bình, Quốc Tuấn | Trúc Ly, Mai Anh, Ngọc Huyền, Ngọc Linh, Đoan Trang, Quỳnh Anh |
| Trung | Quang Sơn | Ngọc Trân |
| Nam | Thái Sơn, Minh Triết, Đức Trí, Adam | Thùy Dung, Thục Đoan, Mỹ Duyên, Kim Thanh |

These labels are author metadata, not an independent accent/rights audit. Nano's approved
Minh Quân voice stays available under Nano; not every Nano voice name exists in Turbo. Switching
profile must invalidate old speech jobs and choose a compatible preset rather than silently
substituting a speaker. Narration is one chosen voice; genuine speaker-separated lip-sync dubbing
and dialogue removal are not implemented by a model dropdown.

Turbo publisher declares Vietnamese/English/code-switching and streaming; these are upstream
capabilities, not AniSub listening or latency results. No promise of correct every English name,
foreign proper noun, acronym or Japanese romanization. Pronunciation dictionary/manual corrections
must preserve the original subtitle and never blindly phonetic-rewrite another language.
Local synthetic checks can establish finite waveform, duration and load success; native-speaker
listening remains the final quality gate for accent, clarity and mixed-language pronunciation.

## Evidence and primary sources

- Installer focused offline integrity/rollback suite: `tests/windows/quality-model-tests.py`,
  **18/18 passed** (atomic install, reuse/no-network, tampering, disk reservation, rollback,
  forged manifest versus upstream digest, unsafe paths and unknown-directory preservation).
- [Turbo pinned model card and Apache-2.0 assets](https://huggingface.co/pnnbao-ump/VieNeu-TTS-v3-Turbo/blob/61b85e3d937fbbacb387714180e8182823512523/README.md).
- [MOSS ONNX codec pinned card, Apache-2.0](https://huggingface.co/OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX/blob/ceff0d0749bfb3fa2d61149794ec6feef0d1e1ae/README.md).
- [Whisper Small converted checkpoint, MIT and language list](https://huggingface.co/Systran/faster-whisper-small/blob/536b0662742c02347bc0e980a01041f333bce120/README.md).
- SDK inference/loading audit is against installed reviewed `vieneu==3.8.3`; GPU paths and
  trust_remote_code codecs are **not** selected for this local CPU profile.

Actual installation completed for all three packs; a second full catalog-backed revalidation
reported **already-installed** for all three and exited 0. Additional payload is exactly
1,052,207,871 bytes. One interrupted HF stage and one interrupted ranged stage remain unpromoted
(approximately 367 MiB combined including small files); no existing Nano/Marian pack was changed.
Installer subprocesses are no longer running. Explicit cleanup was blocked by execution policy,
so partial stages are preserved rather than bypassing that boundary.

Synthesis/ASR benchmark status must be reported independently by the integrating host; none of
the focused installer tests synthesize speech or capture user media. All voices still require
human listening confirmation before a claim of clear natural mixed-language speech.
