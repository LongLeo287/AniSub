package com.anisub.runtime.voice;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * DEBUG BUILDS ONLY (src/debug): a pinned pack URL whose asset name exists in
 * {@code files/debug-packs/} is served from that local file, so an unpublished pack (e.g. the staged
 * voices-en-v1 assets) can be installed on an emulator through the normal verify/install path. The
 * catalog's size and SHA-256 checks still apply. Any other URL goes to the system DownloadManager, as
 * in release builds (src/release/DebugSources).
 */
public final class DebugSources {
    private DebugSources() { }

    public static VoicePackManager.FileFetcher wrap(final VoicePackManager.FileFetcher system, Context app) {
        final File dir = new File(app.getFilesDir(), "debug-packs");
        final HttpFetcher local = new HttpFetcher((url, offset) -> {
            FileInputStream in = new FileInputStream(localFile(dir, url));
            File f = localFile(dir, url);
            long skip = Math.max(0, Math.min(offset, f.length()));
            if (in.skip(skip) != skip) { in.close(); throw new IOException("seek"); }
            return new HttpSource.Response(in, f.length() - skip, skip > 0, null);
        });
        return new VoicePackManager.FileFetcher() {
            @Override public String fetch(String url, File target, long expected, VoicePackManager.Progress p, VoicePackManager.Cancelled c) {
                File f = localFile(dir, url);
                return f != null && f.isFile() ? local.fetch(url, target, expected, p, c) : system.fetch(url, target, expected, p, c);
            }
            @Override public void abort() { local.abort(); system.abort(); }
        };
    }

    static File localFile(File dir, String url) {
        String name = url.substring(url.lastIndexOf('/') + 1);
        return name.isEmpty() || name.contains("..") ? null : new File(dir, name);
    }
}
