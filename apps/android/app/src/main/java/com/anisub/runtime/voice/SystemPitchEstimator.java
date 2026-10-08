package com.anisub.runtime.voice;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;

/** Bounded local PCM measurement. Gender labels are a pitch heuristic, never speaker identity. */
public final class SystemPitchEstimator {
    public static final int MAX_BYTES = 2 * 1024 * 1024, MAX_SECONDS = 8;
    public static final long TIMEOUT_MS = 15_000;
    public static final class Result {
        public final double pitchHz, confidence;
        public final String gender, reason;
        private Result(double hz, double confidence, String gender, String reason) {
            this.pitchHz=hz; this.confidence=confidence; this.gender=gender; this.reason=reason;
        }
        public static Result unavailable(String reason) { return new Result(0, 0, "unknown", reason); }
    }
    private SystemPitchEstimator() { }

    /** Accept only a complete, bounded RIFF/WAVE containing mono 16-bit little-endian PCM. */
    public static Result analyze(File file) throws IOException {
        long length=file.length();
        if(length<44 || length>MAX_BYTES) return Result.unavailable("size");
        byte[] bytes=new byte[(int)length];
        try(FileInputStream input=new FileInputStream(file)) {
            int offset=0, n;
            while(offset<bytes.length && (n=input.read(bytes,offset,bytes.length-offset))!=-1) offset+=n;
            if(offset!=bytes.length || input.read()!=-1) return Result.unavailable("size");
        }
        if(!tag(bytes,0,"RIFF") || !tag(bytes,8,"WAVE") || u32(bytes,4)+8!=length) return Result.unavailable("wav");
        int rate=0, dataOffset=-1, dataSize=0; boolean format=false;
        for(int offset=12;offset+8<=bytes.length;) {
            long size=u32(bytes,offset+4), end=offset+8L+size;
            if(end>bytes.length) return Result.unavailable("wav");
            if(tag(bytes,offset,"fmt ")) {
                if(format || size<16 || u16(bytes,offset+8)!=1 || u16(bytes,offset+10)!=1
                        || u16(bytes,offset+22)!=16 || u16(bytes,offset+20)!=2) return Result.unavailable("format");
                long sampleRate=u32(bytes,offset+12);
                if(sampleRate<8000 || sampleRate>48000 || u32(bytes,offset+16)!=sampleRate*2) return Result.unavailable("format");
                rate=(int)sampleRate; format=true;
            } else if(tag(bytes,offset,"data")) {
                if(dataOffset>=0 || (size&1)!=0) return Result.unavailable("wav");
                dataOffset=offset+8; dataSize=(int)size;
            }
            long next=end+(size&1);
            if(next>bytes.length) return Result.unavailable("wav");
            offset=(int)next;
        }
        if(!format || dataOffset<0 || dataSize/2>rate*MAX_SECONDS) return Result.unavailable("duration");
        short[] samples=new short[dataSize/2];
        for(int i=0;i<samples.length;i++) samples[i]=(short)u16(bytes,dataOffset+i*2);
        return analyzePcm(samples,rate);
    }

    public static Result analyzePcm(short[] samples,int sampleRate) {
        if(samples==null || sampleRate<8000 || sampleRate>48000 || samples.length>sampleRate*MAX_SECONDS
                || samples.length<sampleRate/4) return Result.unavailable("duration");
        int stride=Math.max(1,sampleRate/8000), count=samples.length/stride;
        double rate=sampleRate/(double)stride;
        double[] pcm=new double[count];
        for(int i=0;i<count;i++) {
            double sum=0; for(int j=0;j<stride;j++)sum+=samples[i*stride+j];
            pcm[i]=sum/(stride*32768.0);
        }
        int window=(int)(rate*.05), minLag=(int)(rate/400), maxLag=(int)(rate/70);
        int frames=Math.min(64,Math.max(1,(count-window)/window+1));
        double[] pitches=new double[frames], qualities=new double[frames]; int voiced=0;
        for(int frame=0;frame<frames;frame++) {
            int start=frames==1?0:frame*(count-window)/(frames-1);
            double[] slice=new double[window]; double mean=0;
            for(int i=0;i<window;i++)mean+=pcm[start+i]; mean/=window;
            double energy=0;
            for(int i=0;i<window;i++){slice[i]=pcm[start+i]-mean;energy+=slice[i]*slice[i];}
            if(energy/window<.0001)continue;
            double[] correlation=new double[maxLag+2]; double best=0;
            for(int lag=minLag-1;lag<=maxLag+1;lag++) {
                double cross=0,a=0,b=0;
                for(int i=0;i<window-lag;i++){double x=slice[i],y=slice[i+lag];cross+=x*y;a+=x*x;b+=y*y;}
                correlation[lag]=a*b<=0?0:cross/Math.sqrt(a*b);
                if(lag>=minLag && lag<=maxLag && correlation[lag]>best)best=correlation[lag];
            }
            if(best<.78)continue;
            int chosen=0;
            for(int lag=minLag;lag<=maxLag;lag++) if(correlation[lag]>=Math.max(.78,best*.95)
                    && correlation[lag]>=correlation[lag-1] && correlation[lag]>correlation[lag+1]) {chosen=lag;break;}
            if(chosen==0)continue;
            pitches[voiced]=rate/chosen; qualities[voiced]=correlation[chosen]; voiced++;
        }
        if(voiced<5 || voiced<frames*.15)return Result.unavailable("unvoiced");
        Arrays.sort(pitches,0,voiced); double median=pitches[voiced/2], quality=0;
        double[] deviations=new double[voiced];
        for(int i=0;i<voiced;i++){deviations[i]=Math.abs(pitches[i]-median);quality+=qualities[i];}
        Arrays.sort(deviations); quality/=voiced;
        if(deviations[voiced/2]/median>.25)return new Result(median,quality,"unknown","unstable");
        // Deliberately leave the overlap 145–185 Hz unknown; this is not a demographic classifier.
        String gender=median<=145?"male":median>=185?"female":"unknown";
        return new Result(median,quality,gender,"unknown".equals(gender)?"overlap":"measured");
    }
    private static boolean tag(byte[] b,int offset,String text) {
        if(offset<0 || offset+4>b.length)return false;
        for(int i=0;i<4;i++)if(b[offset+i]!=(byte)text.charAt(i))return false;
        return true;
    }
    private static int u16(byte[] b,int offset){return (b[offset]&255)|((b[offset+1]&255)<<8);}
    private static long u32(byte[] b,int offset){return (b[offset]&255L)|((b[offset+1]&255L)<<8)|((b[offset+2]&255L)<<16)|((b[offset+3]&255L)<<24);}
}
