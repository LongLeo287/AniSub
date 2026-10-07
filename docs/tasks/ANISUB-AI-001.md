# ANISUB-AI-001 — On-device AI narration, contract 1.1, public-repo preparation

Date: 2026-10-07. Owner decision 07-10: AniSub reassigned to Claude ("Làm full AniSub rồi tích hợp
vào AniBox"); AI "Thuyết minh" runs on the TV with sherpa-onnx + a Vietnamese Piper/VITS voice.
Baseline: no Git (local hashes only); legacy SessionGate gate 4,296 checks; Codex Task 1-2 sources
(protocol DTOs, model store) present and passing.

## Scope (allowed)

- `apps/android/**` (service, settings UI, `ai/`, `voice/`, JNI binding, manifest, gradle, tools, tests)
- `docs/android-addon-contract.md`, `docs/voices.md`, `docs/roadmap.md`, `docs/tasks/ANISUB-AI-001.md`
- root `README.md`, `LICENSE`, `THIRD_PARTY_NOTICES.md`, `.gitignore`, `.gitattributes`
- redaction only: `model-manager/tools/install_ocr_models.py`, `tests/windows/*.ps1` (personal paths)

## Non-goals

Major 2 protocol, translation, OCR/ASR, multi-speaker dubbing, cloud TTS, signing with the release
key, pushing, publishing, any AniBox edit, emulator use while the owner's emulator is running.

## Acceptance

- CAPABILITIES minor 1 fields (`aiVoice`, `voicePack`, `runtime`, `aiEngine`, `modes`, `rate`) with
  all minor-0 fields kept; OPEN `mode`/`rate`/`voice` rules; `VOICE_PACK_MISSING` without fallback.
- Runtime caller trust (UID -> `com.anibox.tv` -> same signer); no permission on service/activity.
- Pinned catalog, consent, one download, SHA-256 per file, resume, <=2 retries, atomic install,
  re-verification before native load, failed update keeps the old pack, crash recovery.
- One inference worker, <=2 utterances ahead, obsolete PCM never plays, watchdog, lossless split,
  rate x1.00-x1.15 when the end is known.
- JVM tests, legacy gate, Node checks, `testDebugUnitTest assembleDebug lintDebug assembleRelease`.
- Local Git repo on `main`, secret scan, first commit (not pushed).

## Evidence

See `apps/android/README.md` "Evidence". Hardware (P650) and emulator measurements are pending.

## Rollback

Uninstall AniSub; AniBox keeps playing with narration off. Source rollback: the first commit is the
whole baseline, so revert individual files from it.
