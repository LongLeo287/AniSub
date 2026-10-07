# Content profiles and terminology

Windows background prototype supports manually selected general, technology, film and PC-game profiles. Console, Remote Play and gamepad integration remain excluded. AniBox is unchanged.

Enter one line per term: `party | tổ đội | bữa tiệc;đảng`. This is an illustrative user choice, NOT a built-in rule. Supply only aliases observed for the selected content. Different senses within one sentence cannot be resolved by this method. Empty aliases check the target, not force its generation.

The source must match whole words in the English input. Only explicitly declared output aliases are then canonicalized, single-pass. Unmatched terms remain unresolved, never forcibly inserted. Surrounding text is retained; this does not guarantee the model translated negation correctly. Recognition errors remain upstream.

Limits: 32 terms, source/target 80 characters, four aliases of 80 characters each. NFC normalization; reject controls, overlapping sources and conflicting output phrases. Settings are memory-only per app session; no raw subtitle logging/cloud calls.

Private worker optional request field: `contentPolicy: {profile,terms:[{source,target,aliases}]}`. `validate-policy` loads no models. UI validates before changing settings. A valid change cancels current/queued/prepared speech; invalid settings or busy worker leave previous policy/output intact. Cache includes the full canonical policy. Generation guards retire async results.

Replies expose `translationMode: sentence-glossary`, `contextSupported: false`, `glossaryApplied` (matched/already canonical terms, not replacement occurrences), `glossaryUnresolved`. Profiles are namespaces, NOT contextual prompts. Marian still translates individual EN→VI sentences. Direct VI narration bypasses translation. Other languages currently use Whisper→English first; original-language terms are not reliably retained.

Next: separate optional local contextual provider with bounded prior dialogue, terminology/names validation and latency/resource measurements. Do not prepend instructions to Marian and claim contextual translation. Separate PC-game dialogue OCR from HUD/menu before auto-reading UI. Validate capture→recognition→translation→speech across varied media; no zero-delay/perfect-translation/full-dubbing claim.
