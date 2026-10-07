package com.anisub.runtime.protocol;

/** Same-device elapsed realtime; boxed fields preserve missing required values. */
public final class ClockAnchor {
    public final Long positionMs, sampledAtElapsedMs;
    public final Boolean playing;
    public final Double speed;
    public ClockAnchor(Long positionMs, Long sampledAtElapsedMs, Boolean playing, Double speed) {
        this.positionMs=positionMs;this.sampledAtElapsedMs=sampledAtElapsedMs;this.playing=playing;this.speed=speed;
    }
}
