package com.anisub.runtime;

import com.anisub.runtime.translate.CueTimeline;
import java.util.ArrayList;
import java.util.List;

/**
 * Where an incoming cue goes, and the guarantee that one cue id is spoken at most once per
 * playback epoch even when AniBox sends it both in a timeline batch and in an active snapshot.
 * Pure Java (JVM-tested); the service owns the instance and calls it on its main thread.
 */
public final class CueIntake {
    public static final long HORIZON_MS = 5000;
    public enum Route { TIMELINE, QUEUE, DUPLICATE }

    private final RecentCueIds seen;
    private final CueTimeline timeline;

    public CueIntake(RecentCueIds seen, CueTimeline timeline) { this.seen = seen; this.timeline = timeline; }

    /**
     * @param timelineBatch the CUES message carried {@code timeline:true}
     * @return TIMELINE (keep for later), QUEUE (speak from the queue; call {@link #queued}) or
     *     DUPLICATE (already queued/spoken this epoch, or owned by the timeline)
     */
    public Route route(String id, long startMs, long nowMs, boolean timelineBatch) {
        if (timelineBatch || startMs > nowMs + HORIZON_MS) return Route.TIMELINE;
        if (seen.contains(id) || timeline.get(id) != null) return Route.DUPLICATE;
        return Route.QUEUE;
    }

    /** A snapshot cue entered the speech queue. */
    public void queued(String id) { seen.add(id); }

    /**
     * Timeline cues due within the horizon (at most {@code room}), minus any already queued from a
     * snapshot this epoch; returned cues are marked seen. Missed ones go to {@code expired}.
     */
    public void promote(long nowMs, int room, List<CueTimeline.Entry> due, List<CueTimeline.Entry> expired) {
        if (room <= 0) { timeline.collect(nowMs, HORIZON_MS, 0, new ArrayList<CueTimeline.Entry>(), expired); return; }
        List<CueTimeline.Entry> raw = new ArrayList<>();
        timeline.collect(nowMs, HORIZON_MS, room, raw, expired);
        for (CueTimeline.Entry e : raw) {
            if (seen.contains(e.id)) continue;
            seen.add(e.id);
            due.add(e);
        }
    }

    /** Seek/pause/revision change: cues may be spoken again from {@code positionMs}. */
    public void restart(long positionMs) { seen.clear(); timeline.restart(positionMs); }
}
