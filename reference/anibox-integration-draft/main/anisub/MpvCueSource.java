package com.anibox.tv.anisub;

import java.util.Collections;

/** Deliberately unwired placeholder: requires verified MPV text/timing extraction before use. */
public final class MpvCueSource implements CueSource {
    private Sink sink;
    @Override public void setSink(Sink sink) { this.sink = sink; }
    @Override public void reset() { if (sink != null) sink.onCues(Collections.emptyList()); }
    public boolean supported() { return false; }
}
