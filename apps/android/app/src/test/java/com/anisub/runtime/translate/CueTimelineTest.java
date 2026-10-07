package com.anisub.runtime.translate;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Minor 2 timeline: whole subtitle files sent ahead, bounded, surviving seeks. */
public class CueTimelineTest {
    static List<String> ids(List<CueTimeline.Entry> es) { List<String> out = new ArrayList<>(); for (CueTimeline.Entry e : es) out.add(e.id); return out; }

    CueTimeline file(int n) {
        CueTimeline t = new CueTimeline();
        // Added out of order on purpose: batches may arrive in any order.
        for (int i = n - 1; i >= 0; i--) assertEquals(CueTimeline.AddResult.ADDED, t.add("c" + i, "line " + i, "en", 10_000L * i, 10_000L * i + 4000));
        return t;
    }

    @Test public void collectsInStartOrderWithinTheHorizon() {
        CueTimeline t = file(10);
        List<CueTimeline.Entry> due = new ArrayList<>(), expired = new ArrayList<>();
        t.collect(0, 5000, 8, due, expired);
        assertEquals(java.util.Arrays.asList("c0"), ids(due));
        due.clear();
        t.collect(16_000, 5000, 8, due, expired);
        assertEquals("c1 came due and passed while not collected", java.util.Arrays.asList("c1"), ids(expired));
        assertEquals(java.util.Arrays.asList("c2"), ids(due));
        due.clear(); expired.clear();
        t.collect(16_500, 5000, 8, due, expired);
        assertTrue("collected once per epoch", due.isEmpty() && expired.isEmpty());
    }

    @Test public void seekRestartsEligibilityAndSkipsThePastSilently() {
        CueTimeline t = file(10);
        List<CueTimeline.Entry> due = new ArrayList<>(), expired = new ArrayList<>();
        t.collect(0, 5000, 8, due, expired);
        t.restart(55_000); // seek forward
        due.clear();
        t.collect(55_000, 5000, 8, due, expired);
        assertTrue("cues before the seek point are not reported", expired.isEmpty());
        assertEquals(java.util.Arrays.asList("c6"), ids(due));
        t.restart(0); // seek back: spoken again
        due.clear();
        t.collect(0, 5000, 8, due, expired);
        assertEquals(java.util.Arrays.asList("c0"), ids(due));
    }

    @Test public void queueLimitLeavesCuesForLater() {
        CueTimeline t = new CueTimeline();
        for (int i = 0; i < 5; i++) t.add("c" + i, "x", "en", 100L * i, 100L * i + 3000);
        List<CueTimeline.Entry> due = new ArrayList<>(), expired = new ArrayList<>();
        t.collect(0, 5000, 2, due, expired);
        assertEquals(java.util.Arrays.asList("c0", "c1"), ids(due));
        due.clear();
        t.collect(0, 5000, 8, due, expired);
        assertEquals(java.util.Arrays.asList("c2", "c3", "c4"), ids(due));
    }

    @Test public void duplicatesAndBoundsAreEnforced() {
        CueTimeline t = new CueTimeline();
        assertEquals(CueTimeline.AddResult.ADDED, t.add("a", "x", "en", 0, 10));
        assertEquals(CueTimeline.AddResult.DUPLICATE, t.add("a", "y", "en", 5, 10));
        for (int i = 1; i < CueTimeline.MAX_CUES; i++) assertEquals(CueTimeline.AddResult.ADDED, t.add("c" + i, "x", "en", i, i + 1));
        assertEquals(CueTimeline.AddResult.FULL, t.add("over", "x", "en", 1, 2));
        assertEquals(CueTimeline.MAX_CUES, t.size());
        t.clear();
        assertTrue(t.isEmpty());
        StringBuilder big = new StringBuilder(); for (int i = 0; i < 512; i++) big.append('a');
        int added = 0;
        while (t.add("b" + added, big.toString(), "en", added, added + 1) == CueTimeline.AddResult.ADDED) added++;
        assertEquals("text units bounded", CueTimeline.MAX_TEXT_UNITS / 512, added);
    }

    @Test public void windowIncludesRunningAndUpcomingCues() {
        CueTimeline t = file(20);
        // At 32 s: c3 (30-34 s) is running; window to 90 s ahead.
        List<String> w = ids(t.window(32_000, 32_000 + 90_000, 64));
        assertEquals("c3", w.get(0));
        assertEquals("c12", w.get(w.size() - 1));
        assertFalse(w.contains("c2"));
        assertEquals(3, t.window(0, 1_000_000, 3).size());
    }

    @Test public void unknownEndIsDroppedAfterGrace() {
        CueTimeline t = new CueTimeline();
        t.add("u", "x", "en", 1000, -1);
        List<CueTimeline.Entry> due = new ArrayList<>(), expired = new ArrayList<>();
        t.collect(1000 + CueTimeline.UNKNOWN_END_GRACE_MS + 1, 5000, 8, due, expired);
        assertTrue(due.isEmpty());
        assertEquals(1, expired.size());
    }
}
