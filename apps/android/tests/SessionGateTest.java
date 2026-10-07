package com.anisub.runtime;
public final class SessionGateTest {
    private static int checks;
    private static void check(boolean result) { checks++; if (!result) throw new AssertionError("check " + checks); }
    public static void main(String[] args) {
        SessionGate gate = new SessionGate();
        check(!gate.accept("OPEN", "", 0, 1));
        check(!gate.accept("OPEN", "s", -1, 1));
        check(!gate.accept("OPEN", "s", 0, 0));
        check(!gate.accept("CUES", "s", 0, 1));
        check(gate.accept("OPEN", "s", 3, 1));
        check(!gate.accept("OPEN", "s", 2, 2));
        check(!gate.accept("OPEN", "s", 4, 2));
        check(!gate.accept("CUES", "s", 2, 2));
        check(!gate.accept("CUES", "s", 3, 1));
        check(gate.accept("PAUSE", "s", 4, 2));
        check(!gate.accept("CUES", "s", 3, 3));
        check(gate.accept("CLOSE", "s", 4, 3));
        check(!gate.accept("OPEN", "s", 5, 4));
        check(gate.accept("OPEN", "t", 0, 1));
        gate.closeCurrent();
        check(!gate.accept("CUES", "t", 0, 2));
        check(!gate.accept("OPEN", "t", 1, 3));
        for (int i = 0; i < 254; i++) check(gate.accept("OPEN", "bounded-" + i, 0, 1));
        check(!gate.accept("OPEN", "over-limit", 0, 1));
        check(PayloadRules.clock(0, 1, "vi"));
        check(PayloadRules.clock(0, .5, "und"));
        check(PayloadRules.clock(100, 2, "en"));
        check(!PayloadRules.clock(-1, 1, "vi"));
        check(!PayloadRules.clock(Long.MAX_VALUE, 1, "vi"));
        check(!PayloadRules.clock(0, Double.NaN, "vi"));
        check(!PayloadRules.clock(0, Double.POSITIVE_INFINITY, "vi"));
        check(!PayloadRules.clock(0, .49, "vi"));
        check(!PayloadRules.clock(0, 2.01, "vi"));
        check(!PayloadRules.clock(0, 1, "ja"));
        check(PayloadRules.cue("c", "test", 0, -1, "unknown"));
        check(PayloadRules.cue("c", "", 0, 10, "dialogue"));
        check(PayloadRules.cue("c", "test", 10, 10, "annotation"));
        check(!PayloadRules.cue("", "test", 0, -1, "unknown"));
        check(!PayloadRules.cue(null, "test", 0, -1, "unknown"));
        check(!PayloadRules.cue("c", null, 0, -1, "unknown"));
        check(!PayloadRules.cue("c", "test", -1, -1, "unknown"));
        check(!PayloadRules.cue("c", "test", 0, -2, "unknown"));
        check(!PayloadRules.cue("c", "test", 10, 9, "unknown"));
        check(!PayloadRules.cue("c", "test", 0, -1, "other"));
        check(!PayloadRules.cue(new String(new char[81]), "test", 0, -1, "unknown"));
        check(!PayloadRules.cue("c", new String(new char[513]), 0, -1, "unknown"));
        RecentCueIds recent = new RecentCueIds();
        for (int i = 0; i < 2000; i++) { recent.add("cue-" + i); check(recent.contains("cue-" + i)); check(recent.size() <= 512); }
        check(!recent.contains("cue-0"));
        check(recent.contains("cue-1999"));
        recent.clear(); check(recent.size() == 0);
        System.out.println(checks + " admission checks passed");
    }
}
