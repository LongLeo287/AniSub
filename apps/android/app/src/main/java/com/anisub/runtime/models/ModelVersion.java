package com.anisub.runtime.models;

import java.io.File;
public final class ModelVersion {
    public enum State { INSTALLED, READY, CORRUPT }
    /** Pair equality avoids delimiter collisions, including IDs containing control characters. */
    public static final class Identity {
        public final String id, version;
        public Identity(String id, String version) {
            if (id == null || version == null) throw new IllegalArgumentException("identity");
            this.id = id; this.version = version;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Identity)) return false;
            Identity that = (Identity) other; return id.equals(that.id) && version.equals(that.version);
        }
        @Override public int hashCode() { return 31 * id.hashCode() + version.hashCode(); }
    }
    private final ModelManifest manifest;
    private final File directory;
    private final State state;
    ModelVersion(ModelManifest manifest, File directory, State state) { this.manifest = manifest; this.directory = directory; this.state = state; }
    public Identity identity() { return new Identity(manifest.id, manifest.version); }
    public ModelManifest manifest() { return manifest; }
    public File directory() { return directory; }
    public State state() { return state; }
    ModelVersion withState(State state) { return new ModelVersion(manifest, directory, state); }
}
