package com.anisub.runtime.voice;

import android.content.Context;

/** Release builds: voice packs come only from the pinned URLs, through the system DownloadManager. */
public final class DebugSources {
    private DebugSources() { }
    public static VoicePackManager.FileFetcher wrap(VoicePackManager.FileFetcher system, Context app) { return system; }
}
