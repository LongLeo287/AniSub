package com.anibox.tv.anisub.protocol;

/** A runtime reply must echo both session and timeline revision to reject stale speech. */
public final class SpeechEvent {
    public enum Type { STARTED, FINISHED }
    public final Type type;
    public final String sessionId;
    public final long timelineRevision;
    public final String speechId;

    public SpeechEvent(Type type, String sessionId, long timelineRevision, String speechId) {
        if (type == null || sessionId == null || sessionId.isEmpty() || timelineRevision < 0
                || speechId == null || speechId.isEmpty()) throw new IllegalArgumentException("Invalid speech event");
        this.type = type;
        this.sessionId = sessionId;
        this.timelineRevision = timelineRevision;
        this.speechId = speechId;
    }

    public SpeechEvent finished() { return new SpeechEvent(Type.FINISHED, sessionId, timelineRevision, speechId); }
}
