package com.anisub.runtime.ai;

/**
 * Speech-rate choice. The user rate (0.8-1.3, default 1.0) is the base; when the cue end is
 * known the runtime may adjust automatically within 0.95-1.15 of that base so a phrase fits
 * its remaining window. Unknown end means natural (base) rate. Never extreme acceleration.
 */
public final class RatePolicy {
    public static final float USER_MIN = 0.8f, USER_MAX = 1.3f, USER_DEFAULT = 1.0f;
    public static final float AUTO_MIN = 0.95f, AUTO_MAX = 1.15f;
    /** Conservative initial estimate, refined by measured synthesis (Vietnamese, vais1000). */
    public static final double DEFAULT_UNITS_PER_SECOND = 11.0;
    private double unitsPerSecond = DEFAULT_UNITS_PER_SECOND;

    public static boolean validUserRate(double rate) {
        // Compare against the decimal bounds (0.8/1.3), not their float widening.
        return !Double.isNaN(rate) && !Double.isInfinite(rate) && rate >= 0.8 - 1e-9 && rate <= 1.3 + 1e-9;
    }

    /**
     * @param units text length in UTF-16 units
     * @param windowMs remaining wall-clock window for the phrase, or a negative value when unknown
     * @param userRate validated user base rate
     */
    public synchronized float choose(int units, long windowMs, float userRate) {
        float base = Math.max(USER_MIN, Math.min(USER_MAX, userRate));
        if (windowMs <= 0 || units <= 0) return base;
        double naturalMs = units / unitsPerSecond * 1000.0 / base;
        double auto = naturalMs / windowMs;
        // A phrase that already fits keeps its natural speed (slowing down never helps sync);
        // one that does not fit speeds up at most to AUTO_MAX, then reports a timing deficit.
        if (auto < 1.0) auto = 1.0;
        if (auto > AUTO_MAX) auto = AUTO_MAX;
        return (float) (base * auto);
    }

    /** Learn the speaking rate from a real synthesis at a known speed (bounded EMA). */
    public synchronized void observe(int units, double audioSeconds, float speed) {
        if (units < 8 || audioSeconds <= 0.2 || speed <= 0) return;
        double natural = units / (audioSeconds * speed);
        if (natural < 3 || natural > 40) return;
        unitsPerSecond = unitsPerSecond * 0.8 + natural * 0.2;
    }

    public synchronized double unitsPerSecond() { return unitsPerSecond; }
}
