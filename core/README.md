# core

src/runtime.mjs implements the no-model Node coordinator: validated direct cues, bounded pipeline,
media-clock scheduling, revision guards and virtual speech cleanup. Production modules remain
planned in ../docs/file-map.md. Providers/scheduler are injected; no Android/Binder/engine imports.
