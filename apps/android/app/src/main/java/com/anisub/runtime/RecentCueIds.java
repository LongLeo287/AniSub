package com.anisub.runtime;

import java.util.LinkedHashSet;

/** Bounded rolling terminal identity history: a long movie never saturates admission. */
public final class RecentCueIds {
    private final LinkedHashSet<String> ids = new LinkedHashSet<>();
    public boolean contains(String id) { return ids.contains(id); }
    public void add(String id) { ids.add(id); if (ids.size() > 512) ids.remove(ids.iterator().next()); }
    public int size() { return ids.size(); }
    public void clear() { ids.clear(); }
}
