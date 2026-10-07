package com.anisub.runtime.translate;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Lookahead pre-translation of subtitle cues into the voice language.
 *
 * <p>The service registers cues it will speak ({@link #want}) and, while a session runs, the cues of
 * the next {@link #LOOKAHEAD_MS} of media time ({@link #lookahead}). One translation runs at a time
 * (the translator works off the main thread); urgent requests (a cue about to be spoken) go first,
 * then the earliest start. Results are kept per cue and in a bounded text cache (same line, same
 * pair: translated once). Everything is per session: {@link #reset} drops results, cache and
 * in-flight work (late results of an older generation are discarded).
 *
 * <p>A model-missing / engine-unavailable failure is fatal for the pair: every pending cue fails with
 * that code at once instead of retrying per cue. Single-threaded: call from the service thread; the
 * translator's callbacks are re-posted through {@code deliver}. Pure Java, no text is logged.
 */
public final class TranslationScheduler {
    public static final long LOOKAHEAD_MS = 90_000;
    public static final int MAX_IN_FLIGHT = 1, MAX_ITEMS = 512, CACHE_ENTRIES = 1024, CACHE_UNITS = 256 * 1024;
    public static final String E_MODEL_MISSING = "TRANSLATE_MODEL_MISSING", E_UNAVAILABLE = "TRANSLATE_UNAVAILABLE",
            E_FAILED = "PROVIDER_FAILED";

    /** Asynchronous translation port (ML Kit on the device, a fake in tests). */
    public interface Translator { void translate(String text, String from, String to, Callback done); }
    /** Exactly one of result / errorCode is non-null. May be called on any thread. */
    public interface Callback { void done(String result, String errorCode); }
    public interface Listener { void translated(String cueId); void failed(String cueId, String code); }

    private enum State { PENDING, RUNNING, DONE, FAILED }
    private static final class Item {
        final String id, text; final long start; boolean urgent; State state = State.PENDING; String result, error;
        Item(String id, String text, long start, boolean urgent) { this.id = id; this.text = text; this.start = start; this.urgent = urgent; }
    }

    private final Translator translator;
    private final Executor deliver;
    private final Listener listener;
    private final LinkedHashMap<String, Item> items = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> cache = new LinkedHashMap<>(64, 0.75f, true);
    private long cacheUnits;
    private String from, to, fatal;
    private long generation;
    private int running;
    // Diagnostics only (counts and timings, never text).
    private long requests, translated, cacheHits, failures, totalNanos, maxNanos;

    public TranslationScheduler(Translator translator, Executor deliver, Listener listener) {
        if (translator == null || deliver == null || listener == null) throw new IllegalArgumentException("ports");
        this.translator = translator; this.deliver = deliver; this.listener = listener;
    }

    /** New session or pair: everything is dropped; {@code from} may be "und" (waits for {@link #setSource}). */
    public void reset(String from, String to) {
        generation++; items.clear(); cache.clear(); cacheUnits = 0; running = 0; fatal = null;
        this.from = from; this.to = to;
    }

    /** Stops all work (session closed). */
    public void clear() { reset(null, null); }

    /** The detected source language for an "und" session; restarts pending work with it. */
    public void setSource(String detected) {
        if (detected == null || detected.equals(from)) return;
        generation++; running = 0; fatal = null; cache.clear(); cacheUnits = 0;
        for (Item it : items.values()) if (it.state != State.DONE || !detected.equals(to)) { it.state = State.PENDING; it.result = null; it.error = null; }
        from = detected;
        pump();
    }

    public String source() { return from; }
    public String target() { return to; }
    /** True when cues must be translated (known source different from the voice language). */
    public boolean active() { return from != null && to != null && !LanguageTags.UND.equals(from) && !from.equals(to); }
    public boolean waitingForSource() { return to != null && LanguageTags.UND.equals(from); }
    public String fatalError() { return fatal; }

    /** The translation, "" for a cue with nothing to translate, or null while pending/failed. */
    public String result(String cueId) { Item it = items.get(cueId); return it != null && it.state == State.DONE ? it.result : null; }
    public String error(String cueId) { Item it = items.get(cueId); return it != null && it.state == State.FAILED ? it.error : null; }
    public boolean known(String cueId) { return items.containsKey(cueId); }

    /**
     * Registers a cue (cleaned text). {@code urgent}: it is about to be spoken. Idempotent per id;
     * a later urgent call promotes a pending lookahead request.
     */
    public void want(String cueId, String cleanText, long startMs, boolean urgent) {
        if (to == null) return;
        Item it = items.get(cueId);
        if (it == null) {
            it = new Item(cueId, cleanText == null ? "" : cleanText, startMs, urgent);
            items.put(cueId, it);
            requests++;
            if (it.text.isEmpty()) { it.state = State.DONE; it.result = ""; }
            else if (fatal != null) { it.state = State.FAILED; it.error = fatal; }
            else {
                String hit = cache.get(cacheKey(it.text));
                if (hit != null) { it.state = State.DONE; it.result = hit; cacheHits++; }
            }
            evictIfNeeded();
        } else if (urgent) it.urgent = true;
        pump();
    }

    /** Pre-translates the cues of the coming window (call periodically while a session runs). */
    public void lookahead(List<CueTimeline.Entry> window, Cleaner cleaner) {
        for (CueTimeline.Entry e : window) if (!items.containsKey(e.id)) want(e.id, cleaner.clean(e.text), e.start, false);
    }
    public interface Cleaner { String clean(String text); }

    public void forget(String cueId) {
        Item it = items.remove(cueId);
        if (it != null && it.state == State.RUNNING) { /* its result is ignored when it arrives */ }
    }

    public int size() { return items.size(); }
    public int cacheSize() { return cache.size(); }
    public int running() { return running; }
    public int pending() { int n = 0; for (Item it : items.values()) if (it.state == State.PENDING) n++; return n; }

    /** Counts and timings only, for diagnostics and evidence. */
    public String diagnostics() {
        long mean = translated == 0 ? 0 : totalNanos / translated / 1_000_000;
        return "requests=" + requests + " translated=" + translated + " cacheHits=" + cacheHits + " failures=" + failures
                + " meanMs=" + mean + " maxMs=" + maxNanos / 1_000_000 + " items=" + items.size() + " cache=" + cache.size();
    }

    // ------------------------------------------------------------------------------------------
    private void pump() {
        while (running < MAX_IN_FLIGHT && active() && fatal == null) {
            Item next = null;
            for (Item it : items.values()) {
                if (it.state != State.PENDING) continue;
                if (next == null || (it.urgent && !next.urgent) || (it.urgent == next.urgent && it.start < next.start)) next = it;
            }
            if (next == null) return;
            String hit = cache.get(cacheKey(next.text));
            if (hit != null) { next.state = State.DONE; next.result = hit; cacheHits++; listener.translated(next.id); continue; }
            final Item job = next; final long gen = generation; final long t0 = System.nanoTime();
            job.state = State.RUNNING; running++;
            try {
                translator.translate(job.text, from, to, (result, error) -> deliver.execute(() -> complete(job, gen, t0, result, error)));
            } catch (RuntimeException e) {
                complete(job, gen, t0, null, E_FAILED);
            }
        }
    }

    private void complete(Item job, long gen, long t0, String result, String error) {
        if (gen != generation) return; // reset/setSource since: discarded, the counter was reset
        running--;
        long took = System.nanoTime() - t0;
        if (items.get(job.id) != job) { pump(); return; } // forgotten meanwhile
        if (error == null && result != null) {
            job.state = State.DONE; job.result = result; translated++; totalNanos += took; maxNanos = Math.max(maxNanos, took);
            putCache(job.text, result);
            listener.translated(job.id);
        } else {
            String code = error == null ? E_FAILED : error;
            failures++;
            job.state = State.FAILED; job.error = code;
            listener.failed(job.id, code);
            if (E_MODEL_MISSING.equals(code) || E_UNAVAILABLE.equals(code)) failAll(code);
        }
        pump();
    }

    private void failAll(String code) {
        fatal = code;
        List<Item> failed = new ArrayList<>();
        for (Item it : items.values()) if (it.state == State.PENDING) { it.state = State.FAILED; it.error = code; failed.add(it); }
        for (Item it : failed) listener.failed(it.id, code);
    }

    private String cacheKey(String text) { return from + '\u0000' + to + '\u0000' + text; }

    private void putCache(String text, String result) {
        String key = cacheKey(text);
        String old = cache.put(key, result);
        if (old != null) cacheUnits -= key.length() + old.length();
        cacheUnits += key.length() + result.length();
        Iterator<Map.Entry<String, String>> it = cache.entrySet().iterator();
        while ((cache.size() > CACHE_ENTRIES || cacheUnits > CACHE_UNITS) && it.hasNext()) {
            Map.Entry<String, String> e = it.next();
            cacheUnits -= e.getKey().length() + e.getValue().length();
            it.remove();
        }
    }

    /** Over {@link #MAX_ITEMS}: drop finished cues first (earliest start), then the farthest lookahead. */
    private void evictIfNeeded() {
        while (items.size() > MAX_ITEMS) {
            Item victim = null;
            for (Item it : items.values()) {
                boolean finished = it.state == State.DONE || it.state == State.FAILED;
                if (finished && (victim == null || victim.state == State.PENDING || it.start < victim.start)) victim = it;
                else if (!finished && it.state == State.PENDING && !it.urgent && (victim == null || (victim.state == State.PENDING && it.start > victim.start))) victim = it;
            }
            if (victim == null) return; // only running/urgent work left: bounded by the speech queue
            items.remove(victim.id);
        }
    }
}
