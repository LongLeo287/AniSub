# ANISUB-004 — softsub-first AI Thuyết minh: translation and voice language (runtime side)

Date: 2026-10-07. Branch `feat/translate-voices` (worktree `AniSub-wt/R-translate`), baseline AniSub
`main` 2982a78. Owner decisions 07-10: AI Thuyết minh always reads a text softsub, Vietnamese by
default; another language is translated on the TV into the voice language; the voice language is a
setting (vi or en); translator = ML Kit Translate (closed source accepted, as for OCR).

## Scope (allowed)

- `apps/android/**` (service, rules, settings UI, `translate/`, `voice/`, `ai/`, manifest, gradle,
  catalog, tools, debug harness under `src/debug`, tests)
- `docs/protocol.md`, `docs/android-addon-contract.md`, `docs/voices.md`, `docs/roadmap.md`,
  `docs/tasks/ANISUB-004.md`, `protocol/schema/`, `tests/fixtures/android-v1/`, `tests/contract/`
- `README.md`, `THIRD_PARTY_NOTICES.md`, `.gitignore`
- git-ignored `dist/voices-en-v1/` (staged release assets, never committed or uploaded)

## Non-goals

AniBox edits, pushing, publishing, GitHub releases/uploads, major 2, OCR/ASR, cloud translation,
contextual (multi-cue) translation, voices beyond vi/en, uninstalling or clearing emulator data.

## Acceptance

- Protocol major 1 / **minor 2**, additive: CAPABILITIES `voices`, `translate`, `timeline`; OPEN
  `voiceLang`; errors `TRANSLATE_MODEL_MISSING`, `TRANSLATE_UNAVAILABLE`, `VOICE_PACK_MISSING` +
  `voiceLang`; `LANGUAGE_DETECTED` for `und`; CUES `timeline:true` batches far ahead of playback.
  Minor-1 clients (no `voiceLang`) keep the Vietnamese behaviour.
- Lookahead pre-translation (90 s of media time) off the main thread, bounded cache, never waiting on
  translation at cue start when the cue was received ahead of time.
- ML Kit telemetry backend and start-up providers removed; model download only after consent in
  SettingsActivity (size + Google source shown); models deletable; GMS absence reported.
- English Piper voice pack (permissive license) prepared as a new release asset set, pinned in the
  catalog, staged under `dist/` with a PUBLISH-PENDING list.
- JVM tests for the new fields/errors, minor-1 compatibility, scheduler (fake translator), timeline
  and pack selection; build/lint; emulator proof on the x86 build.

## Rollback

Revert the branch commits; the published 0.2.0 APK and `voices-v1` stay valid (the vi catalog entry
is unchanged). On a device: reinstall AniSub 0.2.0; ML Kit models live in AniSub's private storage
and go away with an uninstall.
