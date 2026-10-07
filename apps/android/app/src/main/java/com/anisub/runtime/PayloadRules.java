package com.anisub.runtime;

/** Shared pure scalar bounds, kept independent of Binder and speech engines for tests. */
public final class PayloadRules {
    private PayloadRules() { }
    public static boolean clock(long position, double speed, String language) {
        return position >= 0 && position <= 1000000000000L && !Double.isNaN(speed) && !Double.isInfinite(speed)
                && speed >= .5 && speed <= 2 && ("vi".equals(language) || "en".equals(language) || "und".equals(language));
    }
    public static boolean cue(String id, String text, long start, long end, String role) {
        return id != null && !id.isEmpty() && id.length() <= 80 && text != null && text.length() <= 512
                && start >= 0 && end >= -1 && (end == -1 || end >= start)
                && ("dialogue".equals(role) || "annotation".equals(role) || "unknown".equals(role));
    }
}
