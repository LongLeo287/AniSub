package com.anisub.runtime.ai;
import sonic.Sonic;

/** Bounded pure-Java effects. Invalid/non-finite PCM never reaches Sonic or the audio writer. */
public final class PcmEffects {
    private PcmEffects() { }
    public static float pitchFactor(int semitones) { return (float)Math.pow(2,Math.max(-3,Math.min(3,semitones))/12.0); }
    public static float[] apply(float[] wave,int rate,int pitch,int volume,int pauseMs) {
        if(wave==null||rate<8000||rate>48000||wave.length>Math.min(rate*30,NarrationPipeline.MAX_WAVE_BYTES/4))throw new IllegalArgumentException("PCM bounds");
        if(wave.length==0)return wave;
        float[] safe=wave.clone();for(int i=0;i<safe.length;i++)safe[i]=Float.isNaN(safe[i])||Float.isInfinite(safe[i])?0:Math.max(-1,Math.min(1,safe[i]));
        if(pitch!=0){Sonic sonic=new Sonic(rate,1);sonic.setPitch(pitchFactor(pitch));sonic.setSpeed(1);sonic.setRate(1);sonic.writeFloatToStream(safe,safe.length);sonic.flushStream();
            int count=sonic.samplesAvailable();if(count<0||count>Math.min(rate*31,NarrationPipeline.MAX_WAVE_BYTES/4))throw new IllegalArgumentException("effect bounds");
            safe=new float[count];int got=sonic.readFloatFromStream(safe,count);if(got!=count)throw new IllegalStateException("effect output");}
        float gain=Math.max(0,Math.min(100,volume))/100f;for(int i=0;i<safe.length;i++)safe[i]=Math.max(-1,Math.min(1,safe[i]*gain));
        int gap=rate*Math.max(0,Math.min(1000,pauseMs))/1000;
        if(gap>0){if((safe.length+(long)gap)*4>NarrationPipeline.MAX_WAVE_BYTES)throw new IllegalArgumentException("effect bounds");safe=java.util.Arrays.copyOf(safe,safe.length+gap);}
        return safe;
    }
}
