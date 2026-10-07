package com.anisub.runtime.voice;

import java.io.IOException;
import java.io.InputStream;

/** Injectable transport so download/verify/install logic is JVM-testable without network. */
public interface HttpSource {
    final class Response implements AutoCloseable {
        public final InputStream body;
        /** Bytes remaining in this response, or -1 when unknown. */
        public final long length;
        /** True when the server honoured the requested offset (HTTP 206). */
        public final boolean resumed;
        private final AutoCloseable closer;
        public Response(InputStream body, long length, boolean resumed, AutoCloseable closer) {
            this.body = body; this.length = length; this.resumed = resumed; this.closer = closer;
        }
        @Override public void close() { try { body.close(); } catch (IOException ignored) { } try { if (closer != null) closer.close(); } catch (Exception ignored) { } }
    }
    /** Opens url from offset (0 = whole file). Implementations enforce HTTPS + host allowlist. */
    Response open(String url, long offset) throws IOException;
}
