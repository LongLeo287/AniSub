package com.anisub.runtime.models;

import java.util.*;
public final class ModelRegistry {
    public static final int MAX_VERSIONS = 64;
    private final Map<ModelVersion.Identity, ModelVersion> versions;
    private final Map<String, ModelVersion.Identity> good;
    ModelRegistry(Map<ModelVersion.Identity, ModelVersion> versions, Map<String, ModelVersion.Identity> good) {
        this.versions = Collections.unmodifiableMap(new LinkedHashMap<>(versions));
        this.good = Collections.unmodifiableMap(new LinkedHashMap<>(good));
    }
    public List<ModelVersion> versions() { return Collections.unmodifiableList(new ArrayList<>(versions.values())); }
    public ModelVersion find(String id, String version) { return versions.get(new ModelVersion.Identity(id, version)); }
    public ModelVersion lastKnownGood(String id) { return versions.get(good.get(id)); }
}
