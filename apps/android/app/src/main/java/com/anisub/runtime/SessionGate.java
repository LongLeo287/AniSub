package com.anisub.runtime;

import java.util.LinkedHashMap;

/** Pure Java monotonic admission; closed session IDs cannot reopen in this service lifetime. */
public final class SessionGate {
    private static final class Entry { long revision, seq; boolean closed; }
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private String current;
    public boolean accept(String type, String session, long revision, long seq) {
        if (session == null || session.length() == 0 || session.length() > 80 || revision < 0 || seq <= 0) return false;
        Entry e = entries.get(session);
        if ("OPEN".equals(type)) {
            if (e != null || entries.size() >= 256) return false;
            closeCurrent(); e = new Entry(); entries.put(session, e); current = session;
        } else {
            if (e == null || e.closed || !session.equals(current) || revision < e.revision || seq <= e.seq) return false;
        }
        e.revision = revision; e.seq = seq;
        if ("CLOSE".equals(type)) { e.closed = true; current = null; }
        return true;
    }
    public void closeCurrent() { if (current != null) entries.get(current).closed = true; current = null; }
}
