package com.anisub.runtime.voice;

import android.content.Context;

/** Release builds: voice packs come only from the pinned HTTPS URLs. */
public final class DebugSources {
    private DebugSources() { }
    public static HttpSource wrap(HttpSource https, Context app) { return https; }
}
