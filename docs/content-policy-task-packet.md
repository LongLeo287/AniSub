# Content policy implementation

Baseline: standalone unversioned AniSub workspace, 2026-10-07. Existing live ASR and bounded speech scheduler are retained. No AniBox changes.

Allowed: translation worker and new translation policy helper/tests; TranslationBridge.cs, ExternalMedia.cs, ExternalHost.cs; tests/windows/content-policy-tests.ps1, ExternalFixtureTests.cs, external-fixture-worker.py; content policy docs, protocol.md, file-map.md and roadmap.

Acceptance: explicit general/technology/film/pc-game profiles; user supplied source/target/output-alias terminology; whole-word source gating, no substring replacement, bounded settings; profile changes cancel obsolete output; request-scoped policy and cache isolation; honest sentence-only model capability; focused tests and Windows source compilation.

Non-goals: console, Remote Play, controllers, capture changes, cloud services, model downloads, restarting the running app, AniBox integration. Profiles alone must not be claimed to improve Marian contextual understanding. No automatic replacement of ambiguous terms without user configuration.

Rollback: remove content controls and optional request field; worker without policy retains previous sentence translation. Preserve unrelated files and running app.
