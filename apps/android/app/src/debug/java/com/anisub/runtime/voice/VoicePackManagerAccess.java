package com.anisub.runtime.voice;

import java.io.File;
import java.io.IOException;

/** DEBUG ONLY: exposes the package-private SHA-256 helper to the harness. */
public final class VoicePackManagerAccess {
    private VoicePackManagerAccess() { }
    public static String sha256(File f) throws IOException { return VoicePackManager.sha256(f); }
}
