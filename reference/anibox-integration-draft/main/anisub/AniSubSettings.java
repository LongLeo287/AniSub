package com.anibox.tv.anisub;

/** Opt-in configuration. Persistence/UI and runtime consent are follow-up work. */
public final class AniSubSettings {
    public final boolean enabled;
    public final boolean speechEnabled;
    public final String targetLanguage;

    public AniSubSettings(boolean enabled, boolean speechEnabled, String targetLanguage) {
        if (targetLanguage == null || targetLanguage.trim().isEmpty())
            throw new IllegalArgumentException("Missing target language");
        this.enabled = enabled;
        this.speechEnabled = enabled && speechEnabled;
        this.targetLanguage = targetLanguage;
    }

    public static AniSubSettings disabled() { return new AniSubSettings(false, false, "vi"); }
}
