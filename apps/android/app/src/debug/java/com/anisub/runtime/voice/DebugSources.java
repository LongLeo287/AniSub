package com.anisub.runtime.voice;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * DEBUG BUILDS ONLY (src/debug): a pinned pack URL whose asset name exists in
 * {@code files/debug-packs/} is served from that local file, so an unpublished pack (e.g. the staged
 * voices-en-v1 assets) can be installed on an emulator through the normal consent/verify/install
 * path. The catalog's size and SHA-256 checks still apply. Release builds use the HTTPS source only
 * (src/release/DebugSources).
 */
public final class DebugSources {
    private DebugSources() { }

    public static HttpSource wrap(final HttpSource https, Context app) {
        final File dir = new File(app.getFilesDir(), "debug-packs");
        return (url, offset) -> {
            String name = url.substring(url.lastIndexOf('/') + 1);
            File local = name.isEmpty() || name.contains("..") ? null : new File(dir, name);
            if (local == null || !local.isFile()) return https.open(url, offset);
            FileInputStream in = new FileInputStream(local);
            long skip = Math.max(0, Math.min(offset, local.length()));
            if (in.skip(skip) != skip) { in.close(); throw new IOException("seek"); }
            return new HttpSource.Response(in, local.length() - skip, skip > 0, null);
        };
    }
}
