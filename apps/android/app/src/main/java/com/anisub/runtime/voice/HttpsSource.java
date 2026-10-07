package com.anisub.runtime.voice;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/** HTTPS-only downloader with manual, allowlist-checked redirects and bounded timeouts. */
public final class HttpsSource implements HttpSource {
    private static final int MAX_REDIRECTS = 5;
    private final String userAgent;
    public HttpsSource(String userAgent) { this.userAgent = userAgent; }

    @Override public Response open(String url, long offset) throws IOException {
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!VoiceCatalog.allowedRedirect(current)) throw new IOException("URL_NOT_ALLOWED");
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000); c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", userAgent);
            c.setRequestProperty("Accept-Encoding", "identity");
            if (offset > 0) c.setRequestProperty("Range", "bytes=" + offset + "-");
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String next = c.getHeaderField("Location"); c.disconnect();
                if (next == null) throw new IOException("REDIRECT");
                current = new URL(new URL(current), next).toString();
                continue;
            }
            if (code == 206 && offset > 0) {
                // The resumed body must start exactly at the requested offset.
                String range = c.getHeaderField("Content-Range");
                if (range == null || !range.trim().startsWith("bytes " + offset + "-")) { c.disconnect(); throw new IOException("RANGE"); }
            }
            if (code == 200 || (code == 206 && offset > 0)) {
                long length = c.getContentLength() >= 0 ? c.getContentLength() : -1;
                String header = c.getHeaderField("Content-Length");
                if (header != null) try { length = Long.parseLong(header.trim()); } catch (NumberFormatException ignored) { }
                return new Response(c.getInputStream(), length, code == 206, c::disconnect);
            }
            c.disconnect();
            if (code == 416) throw new IOException("RANGE");
            throw new IOException("HTTP_" + code);
        }
        throw new IOException("REDIRECT_LIMIT");
    }
}
