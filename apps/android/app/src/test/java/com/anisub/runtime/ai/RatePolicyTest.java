package com.anisub.runtime.ai;

import org.junit.Test;
import static org.junit.Assert.*;

public class RatePolicyTest {
    @Test public void userRateBounds() {
        assertTrue(RatePolicy.validUserRate(0.8)); assertTrue(RatePolicy.validUserRate(1.3)); assertTrue(RatePolicy.validUserRate(1.0));
        assertFalse(RatePolicy.validUserRate(0.79)); assertFalse(RatePolicy.validUserRate(1.31));
        assertFalse(RatePolicy.validUserRate(Double.NaN)); assertFalse(RatePolicy.validUserRate(Double.POSITIVE_INFINITY));
    }

    @Test public void unknownEndUsesNaturalBaseRate() {
        RatePolicy p = new RatePolicy();
        assertEquals(1.0f, p.choose(100, -1, 1.0f), 1e-6);
        assertEquals(1.2f, p.choose(100, -1, 1.2f), 1e-6);
    }

    @Test public void fittingPhraseKeepsBaseAndTightPhraseSpeedsUpAtMost115() {
        RatePolicy p = new RatePolicy();
        // 22 units at 11 units/s = 2 s natural.
        assertEquals(1.0f, p.choose(22, 5000, 1.0f), 1e-6);
        assertEquals(1.1f, p.choose(22, 2000 * 10 / 11, 1.0f), 1e-3);
        assertEquals(1.15f, p.choose(22, 500, 1.0f), 1e-6);
        // Auto bound is relative to the user base rate.
        assertEquals(1.2f * 1.15f, p.choose(220, 100, 1.2f), 1e-5);
        float any = p.choose(500, 1, 0.8f);
        assertTrue(any >= 0.8f * RatePolicy.AUTO_MIN && any <= 0.8f * RatePolicy.AUTO_MAX + 1e-6);
    }

    @Test public void observationIsBoundedEma() {
        RatePolicy p = new RatePolicy();
        p.observe(200, 10, 1f); // 20 units/s
        assertEquals(RatePolicy.DEFAULT_UNITS_PER_SECOND * 0.8 + 20 * 0.2, p.unitsPerSecond(), 1e-9);
        double before = p.unitsPerSecond();
        p.observe(200, 1, 1f);   // 200 units/s: implausible, ignored
        p.observe(3, 1, 1f);     // too short to learn from
        assertEquals(before, p.unitsPerSecond(), 1e-12);
    }
}
