# Content policy validation — 2026-10-07

Implemented source only; existing AniSub process was not restarted. No user media
capture, Chrome interaction, model download, console work or AniBox changes.
Workspace is standalone without Git metadata; no branch/commit/push created.

Passing evidence this turn:

- 16 Python translation-policy tests: Unicode/bounds/conflicts, whole-word source
  gating, no replacement cascades, full-policy cache isolation, model-free validation.
- 8 Windows policy parser assertions; all eight background host C# files compile
  under Windows PowerShell/.NET Framework WPF.
- 49 async WPF scheduler fixture assertions. New checks cover policy forwarding,
  invalid settings preserving playback, valid policy retiring current output,
  retired delayed translation, fresh terms, and queued input during delayed failed
  validation. Fixtures use synthetic WAVs, not real translation quality evidence.
- 33 existing capture tests and 30 Node protocol/coordinator tests pass.
- Real installed Marian offline smoke (`tests/translation/real_policy_smoke.py`):
  own sentence “The graphics card does not support ray tracing.” translated to
  “Thẻ đồ họa không hỗ trợ khả năng truy tìm tia.” Configured source-gated alias
  changed only “Thẻ đồ họa” to “GPU”; negation and surrounding output stayed intact.
  A subsequent no-policy request restored identical baseline: cache isolation passed.
  No TTS/acoustic/end-to-end realtime evidence is claimed by this smoke.

Total: 136 automated assertions/tests plus real-model smoke. Independent reviewer
confirmed prior validation mismatch and async scheduler ownership blockers resolved.
Provider validation must own scheduler busy lock: otherwise timer could dequeue a
job while worker validation held its bridge semaphore. Regression now covers it.

Limitations: sentence-only EN→VI model; profile selection alone changes no semantics.
Same-word multiple senses, names, numbers and omitted clauses require semantic QA
and a contextual provider. PC-game dialogue/HUD separation and varied-video live
benchmarks remain pending. Applying valid settings intentionally cancels current
and queued speech; apply between scenes. Busy validation reports retry, not a hidden
queue. UI/capture real-user smoke with the new host is still pending restart approval.

Rollback: remove optional content controls/request field and worker policy branch
as a scoped change; preserve existing capture/speech fixes and unrelated user files.
