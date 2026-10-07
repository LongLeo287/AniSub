package com.anisub.runtime.ai;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Engine-neutral narration pipeline: ONE inference worker, up to {@link #MAX_AHEAD} prepared
 * utterances beyond the active one, and one PCM writer that owns output start/stop.
 *
 * Invariants (all checked under {@code lock}):
 * - stop() bumps the generation and drops every utterance; results finishing later are
 *   discarded and their PCM never reaches play().
 * - play() is only called by the writer while the utterance is still current; a halted
 *   sink is flushed by the writer before any newer utterance starts, so stale buffered
 *   samples are never audible.
 * - Listener callbacks are never invoked for utterances retired by stop(); the host owns
 *   the CANCELLED terminal event for those.
 * No subtitle text is logged. Text only flows to the synthesizer.
 */
public final class NarrationPipeline {
    public static final int MAX_AHEAD = 2;
    public static final int MAX_WAVE_SECONDS = 30;
    public static final long MAX_WAVE_BYTES = 8L << 20, MAX_PREPARED_BYTES = 16L << 20;
    public static final int FADE_IN_MS = 80;

    public interface Cancel { boolean cancelled(); }
    public interface Synthesizer {
        int sampleRate();
        /** Returns mono float PCM; may return early (any length) when cancel reports true. */
        float[] synthesize(String text, float speed, Cancel cancel) throws Exception;
    }
    /** PCM output. write() must be non-blocking. halt() may be called from any thread. */
    public interface PcmSink {
        void open(int sampleRate) throws Exception;
        /** Pause and drop any buffered samples (writer thread only). */
        void reset();
        void play();
        int write(short[] pcm, int offset, int length);
        long playedFrames();
        void halt();
        void release();
    }
    public interface Listener {
        void started(String id);
        void finished(String id);
        void failed(String id, String code);
    }

    private static final class Utterance {
        final String id; final long generation; final float speed;
        final List<String> segments; int nextSegment;
        final LinkedList<short[]> chunks = new LinkedList<>();
        long bytes, totalFrames; boolean active, started, synthesized; String failure;
        Utterance(String id, long generation, List<String> segments, float speed) {
            this.id = id; this.generation = generation; this.segments = segments; this.speed = speed;
        }
    }

    private final Object lock = new Object();
    private final Synthesizer synth;
    private final PcmSink sink;
    private final Listener listener;
    private final RatePolicy rates;
    private final long stallMs;
    private final LinkedList<Utterance> utterances = new LinkedList<>();
    private long generation, preparedBytes;
    private boolean closed, sinkOpen;
    private final Thread inference, writer;
    // Diagnostic counters only (no content).
    private long synthesizedSegments, discardedResults, failures;
    private double synthSeconds, audioSeconds;

    public NarrationPipeline(Synthesizer synth, PcmSink sink, Listener listener, RatePolicy rates, long stallMs) {
        if (synth == null || sink == null || listener == null || rates == null || stallMs < 100) throw new IllegalArgumentException("pipeline ports");
        this.synth = synth; this.sink = sink; this.listener = listener; this.rates = rates; this.stallMs = stallMs;
        inference = new Thread(this::inferenceLoop, "anisub-inference");
        writer = new Thread(this::writerLoop, "anisub-pcm-writer");
        inference.setDaemon(true); writer.setDaemon(true);
        inference.start(); writer.start();
    }

    /** Prepare ahead. Returns false when the look-ahead budget is full or the id is known. */
    public boolean prefetch(String id, String text, float speed) {
        synchronized (lock) {
            if (closed || text == null || text.isEmpty() || find(id) != null || pendingAhead() >= MAX_AHEAD) return false;
            utterances.add(new Utterance(id, generation, TextSplitter.split(text), speed));
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Make {@code id} the active utterance. Reuses a prefetched result for the same id;
     * prefetched utterances queued before it are obsolete and dropped (host order is FIFO).
     */
    public boolean speak(String id, String text, float speed) {
        synchronized (lock) {
            if (closed || text == null || text.isEmpty()) return false;
            for (Utterance u : utterances) if (u.active) return false; // host plays one at a time
            Utterance target = find(id);
            Iterator<Utterance> it = utterances.iterator();
            while (it.hasNext()) {
                Utterance u = it.next();
                if (u == target) break;
                drop(it, u);
            }
            if (target == null) {
                target = new Utterance(id, generation, TextSplitter.split(text), speed);
                utterances.addFirst(target);
            }
            target.active = true;
            lock.notifyAll();
            return true;
        }
    }

    /** Retire everything: queued, in-flight and playing. Bounded and non-blocking. */
    public void stop() {
        synchronized (lock) {
            generation++;
            Iterator<Utterance> it = utterances.iterator();
            while (it.hasNext()) drop(it, it.next());
            sink.halt();
            lock.notifyAll();
        }
    }

    public void release() {
        synchronized (lock) { closed = true; generation++; utterances.clear(); preparedBytes = 0; sink.halt(); lock.notifyAll(); }
        inference.interrupt(); writer.interrupt();
        try { writer.join(1000); inference.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        sink.release();
    }

    /** Drop prefetched (not active) utterances whose ids the host no longer queues. */
    public void retainOnly(java.util.Set<String> ids) {
        synchronized (lock) {
            Iterator<Utterance> it = utterances.iterator();
            while (it.hasNext()) { Utterance u = it.next(); if (!u.active && !ids.contains(u.id)) drop(it, u); }
            lock.notifyAll();
        }
    }

    public int preparedCount() { synchronized (lock) { return utterances.size(); } }
    public long preparedBytes() { synchronized (lock) { return preparedBytes; } }
    /** Diagnostics without content: segments, discarded stale results, failures, measured RTF. */
    public String diagnostics() {
        synchronized (lock) {
            double rtf = audioSeconds > 0 ? synthSeconds / audioSeconds : 0;
            return String.format(java.util.Locale.ROOT, "segments=%d discarded=%d failures=%d rtf=%.3f", synthesizedSegments, discardedResults, failures, rtf);
        }
    }
    public double measuredRtf() { synchronized (lock) { return audioSeconds > 0 ? synthSeconds / audioSeconds : 0; } }

    private int pendingAhead() { int n = 0; for (Utterance u : utterances) if (!u.active) n++; return n; }
    private Utterance find(String id) { for (Utterance u : utterances) if (u.id.equals(id)) return u; return null; }
    private void drop(Iterator<Utterance> it, Utterance u) { preparedBytes -= u.bytes; u.chunks.clear(); u.bytes = 0; it.remove(); }
    private boolean current(Utterance u) { return !closed && u.generation == generation && utterances.contains(u); }

    // ---------------------------------------------------------------- inference worker
    private void inferenceLoop() {
        while (true) {
            Utterance u; String segment; int index;
            synchronized (lock) {
                u = null;
                while (!closed && (u = nextToSynthesize()) == null) {
                    try { lock.wait(); } catch (InterruptedException e) { return; }
                }
                if (closed) return;
                index = u.nextSegment;
                segment = u.segments.get(index);
            }
            final Utterance job = u; final long gen = u.generation;
            float[] wave = null; String failure = null; long t0 = System.nanoTime();
            try {
                if (TextSplitter.speakable(segment)) {
                    wave = synth.synthesize(segment, job.speed, () -> { synchronized (lock) { return closed || gen != generation || !utterances.contains(job); } });
                }
            } catch (Throwable t) { failure = "PROVIDER_FAILED"; }
            double elapsed = (System.nanoTime() - t0) / 1e9;
            synchronized (lock) {
                if (!current(job)) { discardedResults++; continue; }
                job.nextSegment = index + 1;
                if (failure == null && wave != null) failure = validate(wave);
                if (failure == null && wave != null && wave.length > 0) {
                    short[] pcm = toPcm(wave, index == 0 ? FADE_IN_MS * synth.sampleRate() / 1000 : 0);
                    long bytes = pcm.length * 2L;
                    if (preparedBytes + bytes > MAX_PREPARED_BYTES) failure = "BACKPRESSURE";
                    else {
                        job.chunks.add(pcm); job.bytes += bytes; preparedBytes += bytes; job.totalFrames += pcm.length;
                        synthesizedSegments++; synthSeconds += elapsed;
                        double seconds = pcm.length / (double) synth.sampleRate(); audioSeconds += seconds;
                        rates.observe(segment.length(), seconds, job.speed);
                    }
                }
                if (failure != null) { job.failure = failure; failures++; }
                if (job.nextSegment >= job.segments.size() || failure != null) job.synthesized = true;
                lock.notifyAll();
            }
        }
    }

    private Utterance nextToSynthesize() {
        for (Utterance u : utterances) if (u.active && !u.synthesized) return u;
        for (Utterance u : utterances) if (!u.synthesized) return u;
        return null;
    }

    private String validate(float[] wave) {
        int rate = synth.sampleRate();
        if (rate < 8000 || rate > 48000) return "PROVIDER_FAILED";
        if (wave.length > (long) rate * MAX_WAVE_SECONDS || wave.length * 4L > MAX_WAVE_BYTES) return "BACKPRESSURE";
        for (float f : wave) if (Float.isNaN(f) || Float.isInfinite(f)) return "PROVIDER_FAILED";
        return null;
    }

    static short[] toPcm(float[] wave, int fadeFrames) {
        short[] out = new short[wave.length];
        for (int i = 0; i < wave.length; i++) {
            float v = wave[i];
            if (i < fadeFrames) v *= i / (float) fadeFrames;
            int s = Math.round(v * 32767f);
            out[i] = (short) Math.max(-32768, Math.min(32767, s));
        }
        return out;
    }

    // ---------------------------------------------------------------- PCM writer
    private void writerLoop() {
        while (true) {
            Utterance u;
            synchronized (lock) {
                while (!closed && (u = activeReady()) == null) {
                    try { lock.wait(); } catch (InterruptedException e) { return; }
                }
                if (closed) return;
                u = activeReady();
            }
            play(u);
        }
    }

    /** The active utterance; the writer owns its stall watchdog even before the first PCM. */
    private Utterance activeReady() {
        for (Utterance u : utterances) if (u.active) return u;
        return null;
    }

    private void play(Utterance u) {
        long written = 0, lastProgress = System.currentTimeMillis();
        boolean playing = false;
        try {
            if (!sinkOpen) { sink.open(synth.sampleRate()); sinkOpen = true; }
            sink.reset(); // drops anything left by a halted, retired utterance
            // Some devices do not reset the head position on flush: measure relative to here.
            final long base = sink.playedFrames();
            while (true) {
                short[] chunk; boolean done; String failure;
                synchronized (lock) {
                    if (!current(u)) { sink.halt(); return; }
                    chunk = u.chunks.peekFirst(); done = u.synthesized && u.chunks.isEmpty(); failure = u.failure;
                    if (chunk == null && failure != null) { finish(u, failure); return; }
                    if (chunk == null && !done) {
                        if (System.currentTimeMillis() - lastProgress > stallMs) { failures++; finish(u, "TIMEOUT"); return; }
                        try { lock.wait(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                        continue;
                    }
                }
                if (done) break;
                int offset = 0;
                while (offset < chunk.length) {
                    int n = sink.write(chunk, offset, Math.min(chunk.length - offset, 2048));
                    if (n < 0) { synchronized (lock) { if (current(u)) { failures++; finish(u, "PROVIDER_FAILED"); } } return; }
                    synchronized (lock) {
                        if (!current(u)) { sink.halt(); return; }
                        if (n > 0) { offset += n; written += n; lastProgress = System.currentTimeMillis(); }
                        // Under the lock so STARTED can never follow a stop() that retired u.
                        if (!playing && written > 0) { sink.play(); playing = true; u.started = true; listener.started(u.id); }
                    }
                    if (n == 0) {
                        if (System.currentTimeMillis() - lastProgress > stallMs) { synchronized (lock) { if (current(u)) { failures++; finish(u, "TIMEOUT"); } } return; }
                        Thread.sleep(10);
                    }
                }
                synchronized (lock) {
                    if (!current(u)) { sink.halt(); return; }
                    u.chunks.removeFirst(); u.bytes -= chunk.length * 2L; preparedBytes -= chunk.length * 2L;
                }
            }
            // Drain: wait for the device to report every written frame played (bounded).
            long deadline = System.currentTimeMillis() + written * 1000L / Math.max(1, synth.sampleRate()) + 1500;
            long lastPlayed = -1;
            while (true) {
                synchronized (lock) { if (!current(u)) { sink.halt(); return; } }
                long played = sink.playedFrames() - base;
                if (played >= written || System.currentTimeMillis() > deadline) break;
                if (played != lastPlayed) { lastPlayed = played; lastProgress = System.currentTimeMillis(); }
                else if (System.currentTimeMillis() - lastProgress > stallMs) break;
                Thread.sleep(15);
            }
            synchronized (lock) {
                if (!current(u)) { sink.halt(); return; }
                sink.halt();
                finish(u, written > 0 ? null : (u.failure != null ? u.failure : "PROVIDER_FAILED"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            synchronized (lock) { if (current(u)) { failures++; finish(u, "PROVIDER_FAILED"); } }
        }
    }

    /** Called under lock: retire u and notify outside-visible terminal state exactly once. */
    private void finish(Utterance u, String failure) {
        Iterator<Utterance> it = utterances.iterator();
        while (it.hasNext()) if (it.next() == u) { drop(it, u); break; }
        final String id = u.id;
        lock.notifyAll();
        // Listener is invoked while holding lock only to keep ordering with stop(); host listeners
        // must only post to their own thread (never call back into the pipeline synchronously).
        if (failure == null) listener.finished(id); else listener.failed(id, failure);
    }

    /** Snapshot for tests: ids currently held. */
    List<String> ids() { synchronized (lock) { List<String> out = new ArrayList<>(); for (Utterance u : utterances) out.add(u.id); return out; } }
}
