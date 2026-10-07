package com.anisub.runtime;

/** Shared pure scalar bounds, kept independent of Binder and speech engines for tests. */
public final class PayloadRules {
    private PayloadRules() { }
    public static boolean clock(long position, double speed, String language) {
        return position >= 0 && position <= 1000000000000L && !Double.isNaN(speed) && !Double.isInfinite(speed)
                && speed >= .5 && speed <= 2 && ("vi".equals(language) || "en".equals(language) || "und".equals(language));
    }
    /** Minor 2 session bounds: the language is any BCP-47 tag or "und" (the cue source language). */
    public static boolean session(long position, double speed, String language) {
        // Same rule as LanguageTags.valid (kept dependency-free for the legacy javac gate).
        return clock(position, speed, "vi") && language != null && language.length() <= 35
                && language.matches("[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8}){0,7}");
    }
    public static boolean cue(String id, String text, long start, long end, String role) {
        return id != null && !id.isEmpty() && id.length() <= 80 && text != null && text.length() <= 512
                && start >= 0 && end >= -1 && (end == -1 || end >= start)
                && ("dialogue".equals(role) || "annotation".equals(role) || "unknown".equals(role));
    }
}
