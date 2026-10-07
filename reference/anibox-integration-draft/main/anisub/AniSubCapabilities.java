package com.anibox.tv.anisub;

/** Negotiated runtime features, never assumed from a connected Binder alone. */
public final class AniSubCapabilities {
    public static final int PROTOCOL_VERSION = 0;
    public final int protocolVersion;
    public final boolean directSubtitles;
    public final boolean speech;

    public AniSubCapabilities(int protocolVersion, boolean directSubtitles, boolean speech) {
        this.protocolVersion = protocolVersion;
        this.directSubtitles = directSubtitles;
        this.speech = speech;
    }

    public boolean compatible() { return protocolVersion == PROTOCOL_VERSION; }
    public static AniSubCapabilities unavailable() { return new AniSubCapabilities(PROTOCOL_VERSION, false, false); }
}
