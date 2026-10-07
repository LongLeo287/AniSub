package com.anibox.tv.anisub;

/** Opaque session identity and durable provenance only; no playback URL or request headers. */
public final class AniSubSession {
    public final String id;
    public final String episodeId;
    public final String sourceId;
    public final long timelineRevision;

    public AniSubSession(String id, String episodeId, String sourceId, long timelineRevision) {
        if (id == null || id.isEmpty() || episodeId == null || episodeId.isEmpty()
                || sourceId == null || sourceId.isEmpty() || timelineRevision < 0)
            throw new IllegalArgumentException("Missing session identity");
        this.id = id;
        this.episodeId = episodeId;
        this.sourceId = sourceId;
        this.timelineRevision = timelineRevision;
    }

    public AniSubSession nextTimeline() { return new AniSubSession(id, episodeId, sourceId, timelineRevision + 1); }
}
