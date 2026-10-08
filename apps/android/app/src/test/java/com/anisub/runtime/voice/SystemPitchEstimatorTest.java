package com.anisub.runtime.voice;
import org.junit.Test;
import static org.junit.Assert.*;
public class SystemPitchEstimatorTest {
    private short[] tone(int hz){short[] pcm=new short[16000];for(int i=0;i<pcm.length;i++)pcm[i]=(short)(Math.sin(2*Math.PI*hz*i/16000)*12000);return pcm;}
    @Test public void measuredLowAndHighPitchUseExplicitHeuristic(){
        assertEquals("male",SystemPitchEstimator.analyzePcm(tone(120),16000).gender);
        assertEquals("female",SystemPitchEstimator.analyzePcm(tone(230),16000).gender);
    }
    @Test public void OverlapAndSilenceRemainUnknown(){
        assertEquals("unknown",SystemPitchEstimator.analyzePcm(tone(165),16000).gender);
        assertEquals("unknown",SystemPitchEstimator.analyzePcm(new short[16000],16000).gender);
    }
    @Test public void durationIsBounded(){assertEquals("unknown",SystemPitchEstimator.analyzePcm(new short[16000*9],16000).gender);}
}
