# Source selection and Vietnamese voice controls

Baseline: standalone local workspace, no Git repository. Preserve existing narration tests and movie session; do not change AniBox.

Allowed files: apps/desktop/windows source discovery/selection classes and App.cs, TranslationBridge.cs; providers/translation/windows_worker.py; focused tests; this packet and validation/roadmap docs.

Acceptance: bounded read-only window discovery; Auto recommends but does not capture; manual PID binding revalidated; browser tab limitations visible; preset voices filtered by actual installed metadata, explicit unavailable Central region; voice selection reaches real synthesis, changing it invalidates queued narration; no claims of universal foreign-language correctness.

Non-goals: automatic whole-desktop capture, injection, new models, anti-cheat bypass, implemented OCR/ASR or browser tab capture, release.

Rollback: remove new controls/classes and restore changed worker/bridge paths from reviewed patch; preserve model assets and user media. Root validates; scoped implementation plus independent read-only review.
