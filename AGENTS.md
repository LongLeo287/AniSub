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
