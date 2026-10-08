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

/**
 * Device-default TTS engine inventory. Local installed voices only; gender is unknown.
 * The TextToSpeech client lives only while something needs it (inventory scan, a screen holding it,
 * a preview or a pitch measurement) and is shut down afterwards; the scanned inventory stays cached.
 * Binder calls (getVoices / isLanguageAvailable) never run on the main thread.
 */
public final class SystemVoices {
    public static final String INITIALIZING = "initializing", READY = "ready", UNAVAILABLE = "unavailable";
    private static final class Raw { final String engine, name, language; final Voice voice;
        Raw(String engine, String name, String language, Voice voice) { this.engine=engine; this.name=name; this.language=language; this.voice=voice; } }
    private TextToSpeech tts;private boolean ttsReady;private long generation;private int holders,scans;
    private final List<Runnable> waiting=new ArrayList<>();
    private List<Raw> inventory=Collections.emptyList();private String engineName;
    private List<VoiceRegistry.SystemVoice> voices=Collections.emptyList();private Runnable finished;
    private String inventoryState=INITIALIZING;
    private String previewId;
    private long previewSerial;
    private final Context app;private final Runnable changed;
    private final Map<String,SystemPitchEstimator.Result> estimates=new LinkedHashMap<>();
    private final ScheduledExecutorService measurementWorker=Executors.newSingleThreadScheduledExecutor();
    private final java.util.concurrent.ExecutorService inventoryWorker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"anisub-tts-inventory");t.setDaemon(true);return t;});
    private Measurement measurement;
    public interface PitchCallback { void finished(SystemPitchEstimator.Result result); }
    private static final class Measurement {
        final String utteranceId,voiceId; final File file; final PitchCallback callback;
        final long started=android.os.SystemClock.elapsedRealtime();
        ScheduledFuture<?> watchdog; boolean processing;
        Measurement(String utterance,String voiceId,File file,PitchCallback callback){utteranceId=utterance;this.voiceId=voiceId;this.file=file;this.callback=callback;}
    }
    public SystemVoices(Context context,Runnable changed){
        app=context.getApplicationContext();this.changed=changed;
        refresh();
    }
    /** "initializing" until the first scan ends, then "ready", or "unavailable" when the engine cannot start. */
    public synchronized String state(){return inventoryState;}
    public synchronized List<VoiceRegistry.SystemVoice> all(){return voices;}
    public synchronized String enginePackage(){return engineName;}
    /** Cached platform Voice of a scanned local voice (no binder call), or null. */
    public synchronized Voice voice(String engine,String name,String language){
        if(engine==null||name==null)return null;
        for(Raw r:inventory)if(r.engine.equals(engine)&&r.name.equals(name)&&r.language.equals(language))return r.voice;
        return null;
    }
    /** A screen that wants previews / measurements keeps the client alive until {@link #release()}. */
    public void acquire(){synchronized(this){holders++;}refresh();}
    public void release(){synchronized(this){if(holders>0)holders--;}releaseIfIdle();}

    /** Runs {@code task} once a client is ready; creates it when needed. May be called from any thread. */
    private void ensure(Runnable task){
        synchronized(this){
            if(!(tts!=null&&ttsReady)){
                waiting.add(task);
                if(tts!=null)return;
                final long gen=++generation;
                tts=new TextToSpeech(app,status->onInit(gen,status));
                return;
            }
        }
        task.run();
    }
    private void onInit(long gen,int status){
        List<Runnable> run=null;boolean failed=false;
        synchronized(this){
            if(gen!=generation||tts==null)return;
            if(status==TextToSpeech.SUCCESS){
                ttsReady=true;
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
                    public void onStart(String id){}public void onDone(String id){measurementDone(id,true);done(id);}public void onError(String id){measurementDone(id,false);done(id);}public void onStop(String id,boolean interrupted){measurementDone(id,false);done(id);}
                });
                run=new ArrayList<>(waiting);waiting.clear();
            }else{
                failed=true;try{tts.shutdown();}catch(RuntimeException ignored){}tts=null;ttsReady=false;waiting.clear();scans=0;
                if(INITIALIZING.equals(inventoryState))inventoryState=UNAVAILABLE;
            }
        }
        if(failed){changed.run();return;}
        for(Runnable r:run)r.run();
    }
    /** Rescans the inventory on a worker thread, then releases the client again when nothing else needs it. */
    public void refresh(){
        synchronized(this){scans++;}
        ensure(()->inventoryWorker.execute(this::scan));
    }
    private void scan(){
        TextToSpeech t;
        synchronized(this){t=tts;if(t==null||!ttsReady){if(scans>0)scans--;return;}}
        List<Raw> out=new ArrayList<>();String engine=null;boolean ok=true;
        try {
            engine=t.getDefaultEngine();
            java.util.Set<Voice> found=t.getVoices();if(found!=null)for(Voice v:found){
                if(v==null||v.getLocale()==null||v.getName()==null)continue;
                String lang=v.getLocale().getLanguage();if(("vi".equals(lang)||"en".equals(lang))&&local(t,v)&&out.size()<64)
                    out.add(new Raw(engine,v.getName(),lang,v));
            }
        } catch(RuntimeException ignored) { ok=false; }
        synchronized(this){
            if(scans>0)scans--;
            if(ok&&engine!=null){inventory=Collections.unmodifiableList(out);engineName=engine;inventoryState=READY;rebuild();}
            else if(INITIALIZING.equals(inventoryState))inventoryState=UNAVAILABLE;
        }
        changed.run();releaseIfIdle();
    }
    /** Caller holds the monitor. */
    private void rebuild(){
        List<VoiceRegistry.SystemVoice> out=new ArrayList<>();
        for(Raw r:inventory)out.add(new VoiceRegistry.SystemVoice(r.engine,r.name,r.language,true,estimates.get(VoiceRegistry.systemId(r.engine,r.name))));
        Collections.sort(out,(a,b)->a.id.compareTo(b.id));voices=Collections.unmodifiableList(out);
    }
    private void releaseIfIdle(){
        TextToSpeech t=null;
        synchronized(this){
            if(tts!=null&&ttsReady&&holders==0&&scans==0&&previewId==null&&measurement==null&&waiting.isEmpty()){t=tts;tts=null;ttsReady=false;generation++;}
        }
        if(t!=null){try{t.stop();t.shutdown();}catch(RuntimeException ignored){}}
    }
    static boolean local(TextToSpeech tts,Voice voice){return voice!=null&&!voice.isNetworkConnectionRequired()
            &&(voice.getFeatures()==null||!voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
            &&tts.isLanguageAvailable(voice.getLocale())>=TextToSpeech.LANG_AVAILABLE;}
    public synchronized boolean preview(VoiceRegistry.Entry selected,AniSubPrefs.Snapshot settings,String phrase,Runnable terminal){
        if(tts==null||!ttsReady||measurement!=null||selected.kind!=VoiceRegistry.Kind.SYSTEM||!selected.engine.equals(engineName))return false;
        Voice v=voice(selected.engine,selected.voiceName,selected.language);if(v==null)return false;
        if(tts.setVoice(v)!=TextToSpeech.SUCCESS)return false;finished=terminal;previewId="system-preview-"+(++previewSerial);tts.setSpeechRate(settings.rate);tts.setPitch((float)Math.pow(2,settings.pitch/12.0));
        Bundle params=new Bundle();params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,settings.volume/100f);
        if(tts.speak(phrase,TextToSpeech.QUEUE_FLUSH,params,previewId)==TextToSpeech.SUCCESS)return true;finished=null;previewId=null;return false;
    }
    /** Explicit user-consented sample only. Neutral pitch/rate; never synthesize every inventory voice. */
    public synchronized boolean estimatePitch(VoiceRegistry.Entry selected,String phrase,PitchCallback callback){
        if(tts==null||!ttsReady||measurement!=null||previewId!=null||selected==null||selected.kind!=VoiceRegistry.Kind.SYSTEM
                ||!selected.engine.equals(engineName)||phrase==null||phrase.length()>200)return false;
        try {
            Voice voice=voice(selected.engine,selected.voiceName,selected.language);
            if(voice!=null){
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
            if(retain){if(estimates.size()>=64&&!estimates.containsKey(work.voiceId))estimates.remove(estimates.keySet().iterator().next());estimates.put(work.voiceId,result);rebuild();}
        }
        work.file.delete();work.callback.finished(result);releaseIfIdle();
    }
    public void stop(){String id;Measurement work;synchronized(this){id=previewId;work=measurement;if(tts!=null)tts.stop();}done(id);if(work!=null)finishMeasurement(work,SystemPitchEstimator.Result.unavailable("cancelled"),false);}
    private void done(String id){Runnable callback;synchronized(this){if(id==null||!id.equals(previewId))return;callback=finished;finished=null;previewId=null;}if(callback!=null)callback.run();releaseIfIdle();}
}
