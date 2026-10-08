package com.anisub.runtime.ai;

import org.junit.Test;
import static org.junit.Assert.*;

public class PcmEffectsTest {
    @Test public void sanitizesPcmAndAppendsBoundedSilenceWithoutMutatingInput() {
        float[] input = {Float.NaN, Float.POSITIVE_INFINITY, 2f, -2f, .5f};
        float[] output = PcmEffects.apply(input, 8000, 0, 50, 100);
        assertEquals(805, output.length);
        assertEquals(0, output[0], 0); assertEquals(0, output[1], 0);
        assertEquals(.5f, output[2], 0); assertEquals(-.5f, output[3], 0);
        assertEquals(.25f, output[4], 0); assertEquals(0, output[804], 0);
        assertTrue(Float.isNaN(input[0])); assertEquals(2f, input[2], 0);
    }
    @Test public void pitchProcessingProducesFiniteBoundedAudio() {
        float[] input = new float[16000];
        for (int i = 0; i < input.length; i++) input[i] = (float)Math.sin(2 * Math.PI * 220 * i / 16000) * .2f;
        for (int pitch : new int[]{-3, 3}) {
            float[] output = PcmEffects.apply(input, 16000, pitch, 100, 0);
            assertTrue(output.length > 8000); assertTrue(output.length < 24000);
            for (float value : output) { assertFalse(Float.isNaN(value)); assertFalse(Float.isInfinite(value)); assertTrue(Math.abs(value) <= 1); }
        }
    }
    @Test public void rejectsInvalidInputBeforeAllocatingEffects() {
        for (int rate : new int[]{0, 7999, 48001}) {
            try { PcmEffects.apply(new float[10], rate, 0, 100, 0); fail("rate accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        try { PcmEffects.apply(new float[8000 * 30 + 1], 8000, 0, 100, 0); fail("duration accepted"); }
        catch (IllegalArgumentException expected) { }
        try { PcmEffects.apply(null, 8000, 0, 100, 0); fail("null accepted"); }
        catch (IllegalArgumentException expected) { }
    }
}
