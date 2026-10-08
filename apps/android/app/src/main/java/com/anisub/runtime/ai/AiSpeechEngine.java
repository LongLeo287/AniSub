package com.anisub.runtime.ai;

import android.os.Handler;
import android.os.Looper;
import com.anisub.runtime.models.LoadLease;
import com.anisub.runtime.voice.EspeakData;
import com.anisub.runtime.voice.VoiceCatalog;
import com.anisub.runtime.voice.VoicePackManager;
import com.anisub.runtime.settings.AniSubPrefs;
import java.io.File;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * On-device AI narration engine: verified voice-pack lease -> sherpa-onnx -> NarrationPipeline
 * -> AudioTrack. Loads asynchronously (never on the main thread); events are posted to the
 * main looper. Unloads after {@link #IDLE_UNLOAD_MS} without use.
 */
public final class AiSpeechEngine {
    public static final long IDLE_UNLOAD_MS = 60_000, LOAD_DEADLINE_MS = 60_000, STALL_MS = 10_000;
    public enum State { IDLE, LOADING, READY, FAILED }
    /** Voice pack manager for a voice language ("vi" / "en"), or null when none exists. */
    public interface PackResolver { VoicePackManager forLanguage(String language); default VoicePackManager forVoice(String id, String lang) { return forLanguage(lang); } }
    public interface Listener {
        void started(String id);
        void finished(String id);
        void failed(String id, String code);
        void engineChanged(State state, String error);
    }

    private final PackResolver packs;
    private final File espeakRoot;
    private final RatePolicy rates;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "anisub-engine-load"); t.setDaemon(true); return t; });
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final int threads;
    private State state = State.IDLE;
    private String error;
    private long loadGeneration;
    private LoadLease lease;
    private SherpaSynthesizer synth;
    private NarrationPipeline pipeline;
    private String loadedPackVersion;
    private VoicePackManager loadedPacks;
    /** Voice language of the next load; a different loaded language is unloaded first. */
    private String language = "vi", loadedLanguage, loadedVoiceId;
    private volatile String voiceId;
    private AniSubPrefs.Snapshot settings = new AniSubPrefs.Snapshot(1f,0,100,0,"normal","normal");
    private final Runnable idleUnload = this::unload;
    /** Configuration of the resident (or loading) synthesizer; null when nothing is loaded. */
    private String activeLang, activeVoice;
    private AniSubPrefs.Snapshot activeSettings;

    /**
     * @param packs voice pack per language
     * @param espeakRoot shared espeak-ng data directory (see {@link com.anisub.runtime.voice.EspeakData})
     */
    public AiSpeechEngine(PackResolver packs, RatePolicy rates, int threads, File espeakRoot) {
        this.packs = packs; this.rates = rates; this.threads = threads; this.espeakRoot = espeakRoot;
    }

    /**
     * Chooses the voice language ("vi" / "en"). When another language is resident it is unloaded;
     * the next {@link #load()} loads this language's pack (one engine in memory at a time).
     */
    public void setLanguage(String lang) {
        boolean unload;
        synchronized (this) {
            if (lang == null || lang.equals(language)) return;
            language = lang;
            unload = state != State.IDLE;
        }
        if (unload) unload();
    }
    public synchronized String language() { return language; }
    /** Language of the resident (READY) pack, or null. */
    public synchronized String loadedLanguage() { return state == State.READY ? loadedLanguage : null; }
    public synchronized String loadedVoiceId() { return state == State.READY ? loadedVoiceId : null; }

    public void addListener(Listener l) { listeners.addIfAbsent(l); }
    public void removeListener(Listener l) { listeners.remove(l); }
    public synchronized State state() { return state; }
    public synchronized String error() { return error; }
    public synchronized boolean ready() { return state == State.READY && pipeline != null; }
    public RatePolicy rates() { return rates; }

    /** Idempotent asynchronous load. Re-verifies the pack (full SHA-256) before native load. */
    public synchronized void load() {
        main.removeCallbacks(idleUnload);
        if (state == State.LOADING || state == State.READY) return;
        state = State.LOADING; error = null;
        final long generation = ++loadGeneration;
        final String lang = language;
        final String selectedVoice = voiceId;
        final AniSubPrefs.Snapshot selectedSettings = settings;
        activeLang = lang; activeVoice = selectedVoice; activeSettings = selectedSettings;
        final VoicePackManager voices = packs.forVoice(selectedVoice, lang);
        notifyChanged();
        final Runnable timeout = () -> {
            synchronized (AiSpeechEngine.this) {
                if (loadGeneration != generation || state != State.LOADING) return;
                loadGeneration++; state = State.FAILED; error = "TIMEOUT"; // late result is discarded
            }
            notifyChanged();
        };
        main.postDelayed(timeout, LOAD_DEADLINE_MS);
        loader.execute(() -> {
            LoadLease l = null; SherpaSynthesizer s = null; NarrationPipeline p = null; String failure = null;
            try {
                if (voices == null) throw new java.io.IOException("VOICE_PACK_MISSING");
                l = voices.acquire();
                VoiceCatalog.Pack pack = voices.installedPack();
                if (pack == null || !pack.version.equals(l.version().manifest().version)) throw new IllegalStateException("MODEL_INCOMPATIBLE");
                File dir = l.version().directory();
                File espeak = EspeakData.prepare(espeakRoot, dir, pack);
                VoiceCatalog.Voice v = pack.voice(selectedVoice);
                if (v == null) throw new IllegalStateException("VOICE_MISSING");
                s = new SherpaSynthesizer(new File(dir, pack.model), new File(dir, pack.tokens), espeak, threads, v.speakerId, selectedSettings);
                s.synthesize(warmUpText(pack.language), 1f, () -> false); // warm-up so the first cue is not cold
                p = new NarrationPipeline(s, new AudioTrackSink(), new Relay(), rates, STALL_MS);
            } catch (Throwable t) {
                String m = t.getMessage() == null ? "" : t.getMessage();
                failure = t instanceof java.io.IOException && m.contains("CORRUPT") ? "MODEL_CORRUPT"
                        : t instanceof java.io.IOException && m.contains("VOICE_PACK_MISSING") ? "VOICE_PACK_MISSING"
                        : t instanceof UnsatisfiedLinkError ? "NATIVE_UNAVAILABLE" : "PROVIDER_FAILED";
            }
            boolean accepted;
            synchronized (AiSpeechEngine.this) {
                accepted = loadGeneration == generation && state == State.LOADING;
                if(accepted&&failure==null)try{voices.markReady(l);}catch(java.io.IOException e){failure="PROVIDER_FAILED";}
                if (accepted && failure == null) {
                    lease = l; synth = s; pipeline = p; state = State.READY; loadedPackVersion = l.version().manifest().version;
                    loadedLanguage = lang; loadedVoiceId = selectedVoice; loadedPacks = voices;
                    android.util.Log.i("AniSub","voice_ready id="+selectedVoice+" language="+lang);
                } else if (accepted) { state = State.FAILED; error = failure; }
            }
            if (!accepted || failure != null) {
                if (p != null) p.release();
                if (s != null) s.release();
                if (l != null) voices.release(l);
            }
            main.removeCallbacks(timeout);
            if (accepted) notifyChanged();
        });
    }

    /** Chooses the next load only. Existing sessions keep their immutable speaker/settings. */
    public void setVoice(String id) {
        voiceId = id;
    }
    /** New OPEN/preview retires the prior pipeline before taking a fresh model/config snapshot. */
    public void configure(String lang, String id, AniSubPrefs.Snapshot snapshot) {
        synchronized (this) {
            if (reusable(state, activeLang, activeVoice, activeSettings, lang, id, snapshot)) {
                // Same voice and preset already resident (or loading): keep the model, only refresh the per-utterance values.
                language = lang; voiceId = id; settings = snapshot;
                main.removeCallbacks(idleUnload);
                return;
            }
        }
        unload(); synchronized (this) { language=lang;voiceId=id;settings=snapshot; } load();
    }
    /** Pure rule: a resident/loading synthesizer is reused when voice, language and baked-in preset all match. */
    public static boolean reusable(State state, String activeLang, String activeVoice, AniSubPrefs.Snapshot activeSettings,
                                   String lang, String id, AniSubPrefs.Snapshot snapshot) {
        return (state == State.READY || state == State.LOADING) && activeSettings != null && snapshot != null
                && java.util.Objects.equals(activeLang, lang) && java.util.Objects.equals(activeVoice, id)
                && activeSettings.sameSynthesis(snapshot);
    }
    /** Candidate smoke is serialized behind release/load; it can never create a second resident. */
    public String smokeTest(File dir, VoiceCatalog.Pack pack) throws InterruptedException {
        java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
        final String[] result={"INCOMPATIBLE"};
        unloadThen(() -> {
            SherpaSynthesizer s=null;com.anisub.runtime.models.LoadLease pin=null;VoicePackManager manager=packs.forVoice(pack.voices.get(0).id,pack.language);
            try { File espeak=EspeakData.prepare(espeakRoot,dir,pack);
                if(manager==null)throw new java.io.IOException("missing manager");pin=manager.acquire();
                s=new SherpaSynthesizer(new File(dir,pack.model),new File(dir,pack.tokens),espeak,1,pack.voices.get(0).speakerId);
                for(VoiceCatalog.Voice voice:pack.voices){
                    s.setSpeaker(voice.speakerId);
                    float[] pcm=s.synthesize(warmUpText(pack.language),1f,()->false);
                    if(pcm.length<=s.sampleRate()/10)throw new java.io.IOException("empty speaker audio");
                    android.util.Log.i("AniSub","voice_smoke pack="+pack.id+" sid="+voice.speakerId+" samples="+pcm.length+" rate="+s.sampleRate());
                }
                manager.markReady(pin);result[0]=null;
            }catch(Throwable ignored){ /* never expose paths or native details */ }
            finally {if(s!=null)s.release();if(pin!=null)manager.release(pin);done.countDown();}
        });
        done.await();return result[0];
    }

    public boolean prefetch(String id, String text, float speed) {
        NarrationPipeline p; synchronized (this) { p = pipeline; }
        return p != null && p.prefetch(id, text, speed);
    }
    public boolean speak(String id, String text, float speed) {
        NarrationPipeline p; synchronized (this) { p = pipeline; main.removeCallbacks(idleUnload); }
        return p != null && p.speak(id, text, speed);
    }
    public void retainOnly(Set<String> ids) {
        NarrationPipeline p; synchronized (this) { p = pipeline; }
        if (p != null) p.retainOnly(ids);
    }
    public void stop() {
        NarrationPipeline p; synchronized (this) { p = pipeline; }
        if (p != null) p.stop();
    }
    /** No session needs the engine: free native memory after the idle window. */
    public void scheduleIdleUnload() { main.removeCallbacks(idleUnload); main.postDelayed(idleUnload, IDLE_UNLOAD_MS); }
    public void cancelIdleUnload() { main.removeCallbacks(idleUnload); }

    public String diagnostics() { NarrationPipeline p; synchronized (this) { p = pipeline; } return p == null ? "unloaded" : p.diagnostics(); }
    public synchronized String loadedVersion() { return loadedPackVersion; }

    /** Releases native resources; the lease is closed so the pack can be deleted/updated. */
    public void unload() {
        NarrationPipeline p; SherpaSynthesizer s; LoadLease l;VoicePackManager manager;
        synchronized (this) {
            loadGeneration++;
            p = pipeline; s = synth; l = lease; pipeline = null; synth = null; lease = null; loadedPackVersion = null;
            loadedLanguage = null; loadedVoiceId=null; manager=loadedPacks;loadedPacks = null;
            state = State.IDLE; error = null; activeLang = null; activeVoice = null; activeSettings = null;
        }
        main.removeCallbacks(idleUnload);
        if (p != null || s != null || l != null) {
            final NarrationPipeline fp = p; final SherpaSynthesizer fs = s; final LoadLease fl = l;
            if (fp != null) fp.stop();
            // Native release runs on the loader thread after any in-flight load finishes.
            loader.execute(() -> { if (fp != null) fp.release(); if (fs != null) fs.release(); if (fl != null) manager.release(fl); });
        }
        notifyChanged();
    }

    /** Unloads, then runs {@code after} on the loader thread once the lease is closed. */
    public void unloadThen(Runnable after) { unload(); loader.execute(after); }

    /** A short phrase in the pack's language (warm-up and post-install smoke test). */
    public static String warmUpText(String language) { return "en".equals(language) ? "Hello." : "Xin chào."; }

    private void notifyChanged() {
        final State s; final String e; synchronized (this) { s = state; e = error; }
        main.post(() -> { for (Listener l : listeners) l.engineChanged(s, e); });
    }

    /** Pipeline callbacks arrive on worker threads; deliver on the main looper only. */
    private final class Relay implements NarrationPipeline.Listener {
        @Override public void started(String id) { main.post(() -> { for (Listener l : listeners) l.started(id); }); }
        @Override public void finished(String id) { main.post(() -> { for (Listener l : listeners) l.finished(id); }); }
        @Override public void failed(String id, String code) { main.post(() -> { for (Listener l : listeners) l.failed(id, code); }); }
    }
}
