## AniSub — Code Review Instructions

### Review Objective

Act as a senior realtime systems engineer, AI/ML runtime specialist, cross-platform software architect, and security reviewer.

Prioritize correctness, synchronization, resource efficiency, privacy, security, and cross-platform compatibility.

### Critical Review Areas

**Realtime & Concurrency**
- Detect race conditions, deadlocks, stale callbacks, and session lifecycle defects.
- Verify bounded queues, timeouts, cancellation, and backpressure.
- Ensure obsolete revisions cannot produce output.
- Check subtitle/audio synchronization and scheduling behavior.
- Prevent unbounded latency, memory growth, and CPU usage.

**AI Runtime & Model Management**
- Verify model manifests, SHA-256 checksums, and installation integrity.
- Review model compatibility, atomic updates, rollback, and storage limits.
- Prevent unintended downloads or unauthorized model execution.
- Distinguish real inference evidence from synthetic benchmarks.

**Privacy & Security**
- Protect captured audio, screenshots, subtitle text, and credentials.
- Require explicit authorization for capture operations.
- Review Android IPC caller identity and signing checks.
- Validate Chrome Native Messaging origins, senders, and payloads.
- Prevent unauthorized remote access or unsafe data transmission.
- Avoid logging sensitive content.

**Cross-Platform Compatibility**
- Preserve Android TV, Windows, and Chrome Extension boundaries.
- Check Android SDK compatibility and low-memory TV devices.
- Review process cleanup, worker lifecycle, and platform-specific permissions.
- Preserve existing protocol contracts and version negotiation.
- Do not modify AniBox unless explicitly authorized.

**Quality & Reliability**
- Review OCR, ASR, Translation, and TTS error handling.
- Validate fallback behavior without silently changing privacy modes.
- Check resource exhaustion, unexpected input, and recovery paths.
- Require evidence for performance and realtime claims.
- Evaluate regression risk for existing features.

### Review Standards

- Report actionable, evidence-based findings.
- Prioritize security, correctness, and regressions.
- Identify specific files, conditions, impact, and recommended fixes.
- Avoid speculative warnings and unnecessary refactoring.
- Respect existing AGENTS.md and canonical architecture documents.
- Never confuse planned capabilities with implemented functionality.
- Communicate findings primarily in Vietnamese.

### Guiding Principle

Protect realtime correctness, user privacy, system stability, and protocol compatibility before optimizing secondary features.

## Quy tắc làm việc (working rules)

- Read `README.md`, `docs/system-design.md`, `docs/file-map.md` (module ownership) and `docs/protocol.md` (wire contract) before implementing. Update `docs/roadmap.md` with evidence.
- AniSub is independent of AniBox. Do not edit AniBox, bind its player, or copy its settings, database, tokens, provider URLs or credentials into AniSub unless explicitly requested.
- `reference/` is historical material, excluded from compilation and product dependencies.
- Before non-trivial changes write a task packet (allowed files, non-goals, acceptance, rollback). Use a scoped implementer plus an independent reviewer; the root owns integration and validation.
- Never commit, push or publish a release unless the owner explicitly requests it. Never change `anisub.json`, GitHub releases or catalog assets without explicit owner approval.
- All queues and storage need explicit limits. Invalidate obsolete sessions/revisions before accepting async results. Never log subtitle content, raw capture or credentials.
- Version numbers step minimally: patch = last digit (0.4.1 is next), never jump; versionCode +1.

## AniBox compatibility contract (release gate)

AniSub is only usable with AniBox, and every AniBox/AniSub version pair in users' hands must keep working. A forced update (`anisub.json` `"mandatory"`) installs a release on EVERY AniSub user at AniBox's next open, so a broken AniSub breaks all of them with no way back.

- Protocol major stays 1. Changes are additive only: new fields and new minors; never remove, rename, retype or reinterpret a field.
- Legacy CAPABILITIES fields keep their meaning: `translation:false` and `multiSpeaker:false` with the system TTS engine (`android-system-tts`; translation is advertised only in `translate.*`), `aiVoice` / `voicePack` describe the default VAIS Vietnamese pack, `modes` as before. Released AniBox parsers reject a reply that breaks this and drop the connection.
- Never remove or weaken the caller trust check: sending UID -> package `com.anibox.tv` (exact; `.debug` only in debug builds) -> signed with the same certificate as AniSub, decided before any Bundle is decoded, failing closed.
- Gate tests that must pass before any release: `AniBoxClientCompatibilityTest` (replays what released AniBox versions parse and send), `CallerTrustReleaseGateTest`, `ContractTest`, `ProtocolMinor2Test`, `AniBoxParserCompatTest`. A failing gate is fixed in the code, never by loosening the test. When AniBox releases a new client, add its row to `AniBoxClientCompatibilityTest`.
- Every release requires, before publishing: install the real signed APK on a real device, run a voice/model download, and run an AniBox Thuyết minh (AI narration) playback with it. Unit tests do not replace this.
- A new AniSub release also needs its CAPABILITIES fixture in AniBox (`app/src/test/resources/anisub/compat/` plus a row in `AniSubCompatibilityMatrixTest`).
