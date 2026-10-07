package com.anisub.runtime.protocol;

public final class Cue {
    public final String cueId, originalText, language, origin, role, trackRef;
    public final Long observedAtMediaMs, startMs, endMs;
    public Cue(String cueId,String originalText,String language,Long observedAtMediaMs,Long startMs,Long endMs,String origin,String role,String trackRef) {
        this.cueId=cueId;this.originalText=originalText;this.language=language;this.observedAtMediaMs=observedAtMediaMs;
        this.startMs=startMs;this.endMs=endMs;this.origin=origin;this.role=role;this.trackRef=trackRef;
    }
}
