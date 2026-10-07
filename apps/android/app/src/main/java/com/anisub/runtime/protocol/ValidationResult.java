package com.anisub.runtime.protocol;

/** Content-free validation result; never includes subtitle or payload text. */
public final class ValidationResult {
    public final boolean accepted;
    public final String code;
    private ValidationResult(boolean accepted, String code) { this.accepted=accepted; this.code=code; }
    public static ValidationResult accept() { return new ValidationResult(true,null); }
    public static ValidationResult reject(String code) { return new ValidationResult(false,code); }
}
