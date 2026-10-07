package com.anisub.runtime.voice;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Resumable transfer over an {@link HttpSource} (direct HTTP). Used by the JVM tests and the
 * debug-only local pack source; release builds download through {@link SystemDownloadFetcher}
 * (AniSub has no INTERNET permission). Continues an existing partial {@code target}.
 */
public final class HttpFetcher implements VoicePackManager.FileFetcher {
    private final HttpSource http;
    private volatile HttpSource.Response current;

    public HttpFetcher(HttpSource http) {
        if (http == null) throw new IllegalArgumentException("http");
        this.http = http;
    }

    @Override public void abort() { HttpSource.Response r = current; if (r != null) r.close(); }

    @Override public String fetch(String url, File part, long expected, VoicePackManager.Progress progress, VoicePackManager.Cancelled cancelled) {
        long have = part.isFile() ? part.length() : 0;
        HttpSource.Response r;
        try { r = http.open(url, have); } catch (IOException e) { return VoicePackManager.E_NETWORK; }
        current = r;
        try {
            if (cancelled.cancelled()) return VoicePackManager.E_CANCELLED;
            if (have > 0 && !r.resumed) { delete(part); have = 0; }
            if (r.length >= 0 && have + r.length != expected) { delete(part); return VoicePackManager.E_CORRUPT; }
            OutputStream out;
            try { out = new FileOutputStream(part, have > 0); } catch (IOException e) { return VoicePackManager.E_STORAGE; }
            try {
                byte[] buf = new byte[64 * 1024]; long got = have;
                while (true) {
                    int n;
                    try { n = r.body.read(buf); } catch (IOException e) { return cancelled.cancelled() ? VoicePackManager.E_CANCELLED : VoicePackManager.E_NETWORK; }
                    if (n == -1) break;
                    if (cancelled.cancelled()) return VoicePackManager.E_CANCELLED;
                    if (got + n > expected) return VoicePackManager.E_CORRUPT;
                    try { out.write(buf, 0, n); } catch (IOException e) { return VoicePackManager.E_STORAGE; } // disk full is not a network error
                    got += n;
                    progress.progress(got);
                }
                return null;
            } finally {
                try { out.close(); } catch (IOException ignored) { }
            }
        } finally {
            current = null;
            r.close();
        }
    }

    private static void delete(File f) { if (f.exists() && !f.delete()) f.deleteOnExit(); }
}
