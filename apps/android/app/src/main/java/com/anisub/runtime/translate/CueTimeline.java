package com.anisub.runtime.translate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Protocol minor 2 "timeline" cues: a whole external subtitle file sent ahead of playback, kept per
 * session, ordered by media start time and bounded ({@link #MAX_CUES}, {@link #MAX_TEXT_UNITS}).
 * Entries survive seeks/pauses (an "epoch" restarts eligibility at the new position); they are
 * dropped only when the session/source ends. Pure Java, single-threaded (service main thread).
 */
public final class CueTimeline {
    public static final int MAX_CUES = 4096;
    public static final int MAX_TEXT_UNITS = 1 << 20;
    /** A cue with an unknown end that started longer ago than this is no longer spoken. */
    public static final long UNKNOWN_END_GRACE_MS = 2000;

    public enum AddResult { ADDED, DUPLICATE, FULL }

    public static final class Entry {
        public final String id, text, language;
        public final long start, end;
        final long order;
        long handledEpoch = -1;
        Entry(String id, String text, String language, long start, long end, long order) {
            this.id = id; this.text = text; this.language = language; this.start = start; this.end = end; this.order = order;
        }
    }

    private final TreeMap<Long, Entry> byKey = new TreeMap<>(); // key: start * 2^12 + arrival order
    private final Map<String, Entry> byId = new HashMap<>();
    private long units, arrivals;
    private long epoch, epochBase;

    public AddResult add(String id, String text, String language, long start, long end) {
        if (byId.containsKey(id)) return AddResult.DUPLICATE;
        if (byId.size() >= MAX_CUES || units + text.length() > MAX_TEXT_UNITS || start < 0 || start > 1_000_000_000_000L) return AddResult.FULL;
        Entry e = new Entry(id, text, language, start, end, arrivals++);
        byKey.put(key(start, e.order), e);
        byId.put(id, e);
        units += text.length();
        return AddResult.ADDED;
    }

    private static long key(long start, long order) {
        // start <= 10^12 (wire bound) fits in 41 bits; the arrival order keeps equal starts distinct.
        return (start << 22) | (order & ((1L << 22) - 1));
    }

    public int size() { return byId.size(); }
    public boolean isEmpty() { return byId.isEmpty(); }
    public Entry get(String id) { return byId.get(id); }

    public void clear() { byKey.clear(); byId.clear(); units = 0; arrivals = 0; restart(0); }

    /**
     * Seek, pause, revision change: every cue becomes eligible again from {@code positionMs}.
     * Cues that end before it are skipped silently; cues that come due later and are missed are
     * reported as expired.
     */
    public void restart(long positionMs) { epoch++; epochBase = positionMs; }

    /**
     * Moves cues due within {@code horizonMs} of {@code nowMs} into {@code due} (at most
     * {@code max}, in start order). Cues that came due during this epoch but already ended go to
     * {@code expired}; cues that ended before the epoch position are skipped silently.
     */
    public void collect(long nowMs, long horizonMs, int max, List<Entry> due, List<Entry> expired) {
        long limit = nowMs + horizonMs;
        for (Entry e : byKey.headMap(key(Math.max(0, limit), (1L << 22) - 1), true).values()) {
            if (e.handledEpoch == epoch) continue;
            boolean over = e.end >= 0 ? e.end <= nowMs : e.start < nowMs - UNKNOWN_END_GRACE_MS;
            if (over) {
                e.handledEpoch = epoch;
                if (e.start >= epochBase) expired.add(e);
                continue;
            }
            if (due.size() >= max) break; // stays for the next call
            e.handledEpoch = epoch;
            due.add(e);
        }
    }

    /** Cues starting in [fromMs, toMs] (plus any still running at fromMs), at most {@code max}. */
    public List<Entry> window(long fromMs, long toMs, int max) {
        List<Entry> out = new ArrayList<>();
        // Cues that started up to a minute earlier may still be running.
        long from = Math.max(0, fromMs - 60_000);
        for (Entry e : byKey.subMap(key(from, 0), true, key(Math.max(from, toMs), (1L << 22) - 1), true).values()) {
            if (e.start < fromMs && (e.end < 0 || e.end <= fromMs)) continue;
            out.add(e);
            if (out.size() >= max) break;
        }
        return out;
    }
}
