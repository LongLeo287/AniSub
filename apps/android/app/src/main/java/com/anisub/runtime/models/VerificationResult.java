package com.anisub.runtime.models;

import com.anisub.runtime.protocol.RuntimeError;
public final class VerificationResult {
    private final RuntimeError error;
    private final long bytes;
    private VerificationResult(RuntimeError error, long bytes) { this.error = error; this.bytes = bytes; }
    public static VerificationResult valid(long bytes) { return new VerificationResult(null, bytes); }
    public static VerificationResult corrupt() { return new VerificationResult(RuntimeError.MODEL_CORRUPT, 0); }
    public boolean isValid() { return error == null; }
    public RuntimeError error() { return error; }
    public long verifiedBytes() { return bytes; }
}
