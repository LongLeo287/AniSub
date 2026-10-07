package com.anisub.runtime.ai;

import android.os.Handler;
import android.os.Looper;
import com.anisub.runtime.models.LoadLease;
import com.anisub.runtime.voice.VoiceCatalog;
import com.anisub.runtime.voice.VoicePackManager;
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
    public interface Listener {
        void started(String id);
        void finished(String id);
        void failed(String id, String code);
        void engineChanged(State state, String error);
    }

    private final VoicePackManager voices;
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
    private volatile String voiceId;
    private final Runnable idleUnload = this::unload;

    public AiSpeechEngine(VoicePackManager voices, RatePolicy rates, int threads) {
        this.voices = voices; this.rates = rates; this.threads = threads;
    }

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
                l = voices.acquire();
                VoiceCatalog.Pack pack = voices.catalog().find(l.version().manifest().id);
                if (pack == null) throw new IllegalStateException("MODEL_INCOMPATIBLE");
                File dir = l.version().directory();
                VoiceCatalog.Voice v = pack.voice(voiceId);
                s = new SherpaSynthesizer(new File(dir, pack.model), new File(dir, pack.tokens), new File(dir, pack.dataDir), threads, (v == null ? pack.voices.get(0) : v).speakerId);
                s.synthesize("Xin chào.", 1f, () -> false); // warm-up so the first cue is not cold
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
                if (accepted && failure == null) {
                    lease = l; synth = s; pipeline = p; state = State.READY; loadedPackVersion = l.version().manifest().version;
                } else if (accepted) { state = State.FAILED; error = failure; }
            }
            if (!accepted || failure != null) {
                if (p != null) p.release();
                if (s != null) s.release();
                if (l != null) l.close();
            }
            main.removeCallbacks(timeout);
            if (accepted) notifyChanged();
        });
    }

    /** Chooses the pack voice for new utterances (null = first voice). */
    public void setVoice(String id) {
        voiceId = id;
        SherpaSynthesizer s; LoadLease l; synchronized (this) { s = synth; l = lease; }
        if (s == null || l == null) return;
        VoiceCatalog.Pack pack = voices.catalog().find(l.version().manifest().id);
        VoiceCatalog.Voice v = pack == null ? null : pack.voice(id);
        if (v != null) s.setSpeaker(v.speakerId);
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
        NarrationPipeline p; SherpaSynthesizer s; LoadLease l;
        synchronized (this) {
            loadGeneration++;
            p = pipeline; s = synth; l = lease; pipeline = null; synth = null; lease = null; loadedPackVersion = null;
            state = State.IDLE; error = null;
        }
        main.removeCallbacks(idleUnload);
        if (p != null || s != null || l != null) {
            final NarrationPipeline fp = p; final SherpaSynthesizer fs = s; final LoadLease fl = l;
            if (fp != null) fp.stop();
            // Native release runs on the loader thread after any in-flight load finishes.
            loader.execute(() -> { if (fp != null) fp.release(); if (fs != null) fs.release(); if (fl != null) fl.close(); });
        }
        notifyChanged();
    }

    /** Unloads, then runs {@code after} on the loader thread once the lease is closed. */
    public void unloadThen(Runnable after) { unload(); loader.execute(after); }

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
