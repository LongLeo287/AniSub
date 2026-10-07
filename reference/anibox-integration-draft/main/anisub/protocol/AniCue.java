package com.anibox.tv.anisub.protocol;

/** Immutable direct subtitle text; bitmap/OCR input belongs to the separate runtime. */
public final class AniCue {
    public static final long UNKNOWN_END_MS = -1;
    public final String text;
    public final long startMs;
    public final long endMs;
    public final String language;
    public final String provenance;

    public AniCue(String text, long startMs, long endMs, String language, String provenance) {
        if (text == null || text.trim().isEmpty() || text.length() > 4096)
            throw new IllegalArgumentException("Invalid cue text");
        if (startMs < 0 || (endMs != UNKNOWN_END_MS && endMs < startMs))
            throw new IllegalArgumentException("Invalid cue timing");
        this.text = text;
        this.startMs = startMs;
        this.endMs = endMs;
        this.language = language == null ? "" : language;
        this.provenance = provenance == null ? "" : provenance;
    }
}
