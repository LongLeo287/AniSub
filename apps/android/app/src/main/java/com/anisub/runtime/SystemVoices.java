package com.anisub.runtime;
import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import com.anisub.runtime.settings.AniSubPrefs;
import com.anisub.runtime.voice.VoiceRegistry;
import com.anisub.runtime.voice.SystemPitchEstimator;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Device-default TTS engine inventory. Local installed voices only; gender is unknown. */
public final class SystemVoices {
    private TextToSpeech tts;private List<VoiceRegistry.SystemVoice> voices=Collections.emptyList();private Runnable finished;
    private String previewId;
    private long previewSerial;
    private boolean initialized;
    private final Context app;
    private final Map<String,SystemPitchEstimator.Result> estimates=new LinkedHashMap<>();
    private final ScheduledExecutorService measurementWorker=Executors.newSingleThreadScheduledExecutor();
    private Measurement measurement;
    public interface PitchCallback { void finished(SystemPitchEstimator.Result result); }
    private static final class Measurement {
        final String utteranceId,voiceId; final File file; final PitchCallback callback;
        final long started=android.os.SystemClock.elapsedRealtime();
        ScheduledFuture<?> watchdog; boolean processing;
        Measurement(String utterance,String voiceId,File file,PitchCallback callback){utteranceId=utterance;this.voiceId=voiceId;this.file=file;this.callback=callback;}
    }
    public SystemVoices(Context context,Runnable changed){
        app=context.getApplicationContext();
        tts=new TextToSpeech(context,status->{synchronized(this){initialized=status==TextToSpeech.SUCCESS;}if(initialized)refresh();changed.run();});
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
            public void onStart(String id){}public void onDone(String id){measurementDone(id,true);done(id);}public void onError(String id){measurementDone(id,false);done(id);}public void onStop(String id,boolean interrupted){measurementDone(id,false);done(id);}
        });
    }
    public synchronized List<VoiceRegistry.SystemVoice> all(){return voices;}
    public synchronized String enginePackage(){return tts==null?null:tts.getDefaultEngine();}
    public synchronized void refresh(){
        if(tts==null||!initialized)return;List<VoiceRegistry.SystemVoice> out=new ArrayList<>();
        try {
            java.util.Set<Voice> found=tts.getVoices();if(found!=null)for(Voice v:found){
                if(v==null||v.getLocale()==null||v.getName()==null)continue;
                String lang=v.getLocale().getLanguage();if(("vi".equals(lang)||"en".equals(lang))&&local(tts,v)&&out.size()<64)
                    out.add(new VoiceRegistry.SystemVoice(tts.getDefaultEngine(),v.getName(),lang,true,estimates.get(VoiceRegistry.systemId(tts.getDefaultEngine(),v.getName()))));
            }
        } catch(RuntimeException ignored) { out.clear(); }
        Collections.sort(out,(a,b)->a.id.compareTo(b.id));voices=Collections.unmodifiableList(out);
    }
    static boolean local(TextToSpeech tts,Voice voice){return voice!=null&&!voice.isNetworkConnectionRequired()
            &&(voice.getFeatures()==null||!voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
            &&tts.isLanguageAvailable(voice.getLocale())>=TextToSpeech.LANG_AVAILABLE;}
    public synchronized boolean preview(VoiceRegistry.Entry selected,AniSubPrefs.Snapshot settings,String phrase,Runnable terminal){
        if(tts==null||!initialized||measurement!=null||selected.kind!=VoiceRegistry.Kind.SYSTEM||!selected.engine.equals(tts.getDefaultEngine()))return false;
        java.util.Set<Voice> found=tts.getVoices();if(found!=null)for(Voice v:found)if(selected.voiceName.equals(v.getName())&&local(tts,v)&&selected.language.equals(v.getLocale().getLanguage())){
            if(tts.setVoice(v)!=TextToSpeech.SUCCESS)return false;finished=terminal;previewId="system-preview-"+(++previewSerial);tts.setSpeechRate(settings.rate);tts.setPitch((float)Math.pow(2,settings.pitch/12.0));
            Bundle params=new Bundle();params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,settings.volume/100f);
            if(tts.speak(phrase,TextToSpeech.QUEUE_FLUSH,params,previewId)==TextToSpeech.SUCCESS)return true;finished=null;previewId=null;return false;
        }return false;
    }
    /** Explicit user-consented sample only. Neutral pitch/rate; never synthesize every inventory voice. */
    public synchronized boolean estimatePitch(VoiceRegistry.Entry selected,String phrase,PitchCallback callback){
        if(tts==null||!initialized||measurement!=null||previewId!=null||selected==null||selected.kind!=VoiceRegistry.Kind.SYSTEM
                ||!selected.engine.equals(tts.getDefaultEngine())||phrase==null||phrase.length()>200)return false;
        try {
            java.util.Set<Voice> found=tts.getVoices();
            if(found!=null)for(Voice voice:found)if(selected.voiceName.equals(voice.getName())&&local(tts,voice)
                    &&selected.language.equals(voice.getLocale().getLanguage())){
                if(tts.setVoice(voice)!=TextToSpeech.SUCCESS)return false;
                tts.setPitch(1f);tts.setSpeechRate(1f);
                File file=File.createTempFile("system-pitch-",".wav",app.getCacheDir());
                Measurement work=new Measurement("system-pitch-"+(++previewSerial),selected.id,file,callback);
                measurement=work;
                work.watchdog=measurementWorker.scheduleAtFixedRate(()->checkMeasurement(work),200,200,TimeUnit.MILLISECONDS);
                if(tts.synthesizeToFile(phrase,new Bundle(),file,work.utteranceId)==TextToSpeech.SUCCESS)return true;
                measurement=null;work.watchdog.cancel(false);file.delete();return false;
            }
        }catch(IOException|RuntimeException ignored){if(measurement!=null){Measurement old=measurement;measurement=null;if(old.watchdog!=null)old.watchdog.cancel(false);old.file.delete();}}
        return false;
    }
    private void checkMeasurement(Measurement work){
        String reason=null;
        synchronized(this){if(measurement!=work)return;
            if(work.file.length()>SystemPitchEstimator.MAX_BYTES)reason="size";
            else if(android.os.SystemClock.elapsedRealtime()-work.started>=SystemPitchEstimator.TIMEOUT_MS)reason="timeout";
            if(reason!=null&&tts!=null)tts.stop();
        }
        if(reason!=null)finishMeasurement(work,SystemPitchEstimator.Result.unavailable(reason),false);
    }
    private void measurementDone(String id,boolean success){
        Measurement work;
        synchronized(this){work=measurement;if(work==null||!work.utteranceId.equals(id)||work.processing)return;work.processing=true;}
        measurementWorker.execute(()->{
            SystemPitchEstimator.Result result=SystemPitchEstimator.Result.unavailable("synthesis");
            if(success)try{result=SystemPitchEstimator.analyze(work.file);}catch(IOException|RuntimeException ignored){result=SystemPitchEstimator.Result.unavailable("wav");}
            finishMeasurement(work,result,true);
        });
    }
    private void finishMeasurement(Measurement work,SystemPitchEstimator.Result result,boolean retain){
        synchronized(this){if(measurement!=work)return;measurement=null;
            if(work.watchdog!=null)work.watchdog.cancel(false);
            if(retain){if(estimates.size()>=64&&!estimates.containsKey(work.voiceId))estimates.remove(estimates.keySet().iterator().next());estimates.put(work.voiceId,result);refresh();}
        }
        work.file.delete();work.callback.finished(result);
    }
    public void stop(){String id;Measurement work;synchronized(this){id=previewId;work=measurement;if(tts!=null)tts.stop();}done(id);if(work!=null)finishMeasurement(work,SystemPitchEstimator.Result.unavailable("cancelled"),false);}
    private void done(String id){Runnable callback;synchronized(this){if(id==null||!id.equals(previewId))return;callback=finished;finished=null;previewId=null;}if(callback!=null)callback.run();}
}
