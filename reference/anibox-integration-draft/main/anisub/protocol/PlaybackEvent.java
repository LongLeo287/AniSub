package com.anibox.tv.anisub.protocol;

import com.anibox.tv.anisub.AniSubSession;

/** Protocol v0 playback intent on the engine's media timeline (milliseconds). */
public final class PlaybackEvent {
    public enum Type { PLAY, PAUSE, SEEK, STOP, EPISODE_CHANGE, SOURCE_CHANGE, PLAYBACK_SPEED }
    public final Type type;
    public final AniSubSession session;
    public final long positionMs;
    public final float speed;

    public PlaybackEvent(Type type, AniSubSession session, long positionMs, float speed) {
        if (type == null || session == null || positionMs < 0 || Float.isNaN(speed)
                || Float.isInfinite(speed) || speed <= 0) throw new IllegalArgumentException("Invalid playback event");
        this.type = type;
        this.session = session;
        this.positionMs = positionMs;
        this.speed = speed;
    }
}
