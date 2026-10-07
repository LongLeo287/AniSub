package com.anibox.tv.anisub.protocol;

/** Structured transport/runtime failures; messages must never contain source URLs or credentials. */
public final class AniSubError {
    public enum Code { UNAVAILABLE, DISCONNECTED, PROTOCOL_MISMATCH, UNSUPPORTED, RUNTIME_FAILURE }
    public final Code code;
    public final String sessionId;

    public AniSubError(Code code, String sessionId) {
        if (code == null) throw new IllegalArgumentException("Missing error code");
        this.code = code;
        this.sessionId = sessionId == null ? "" : sessionId;
    }
}
