package com.anisub.runtime.models;

public final class LoadLease implements AutoCloseable {
    private final ModelStore owner;
    private final ModelVersion version;
    private boolean closed;
    LoadLease(ModelStore owner, ModelVersion version) { this.owner = owner; this.version = version; }
    public ModelVersion version() { return version; }
    public boolean permitsPrepare() { synchronized (owner) { return !closed && owner.isUsable(this); } }
    boolean belongsTo(ModelStore store) { return owner == store && !closed; }
    @Override public void close() { synchronized (owner) { if (!closed) { closed = true; owner.release(this); } } }
}
