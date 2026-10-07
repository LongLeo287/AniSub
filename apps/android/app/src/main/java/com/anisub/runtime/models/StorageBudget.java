package com.anisub.runtime.models;

public final class StorageBudget {
    public static final long DEFAULT_QUOTA = 1L << 30;
    public static final long MAX_QUOTA = 4L << 30;
    public static final long FREE_RESERVE = 128L << 20;
    private final long quota;
    public StorageBudget(long quota) { if (quota != DEFAULT_QUOTA && quota != MAX_QUOTA) throw new IllegalArgumentException("quota must be 1 or 4 GiB"); this.quota = quota; }
    public long quotaBytes() { return quota; }
    public boolean allows(long used, long expanded, long free) {
        return used >= 0 && expanded >= 0 && free >= 0 && used <= quota && expanded <= quota - used
                && expanded <= Long.MAX_VALUE - FREE_RESERVE && free >= expanded + FREE_RESERVE;
    }
}
