package com.anisub.runtime;

import com.anisub.runtime.translate.CueTimeline;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** The service's cue routing: one cue id is spoken at most once per epoch (timeline vs. snapshot). */
public class CueIntakeTest {
    final RecentCueIds seen = new RecentCueIds();
    final CueTimeline timeline = new CueTimeline();
    final CueIntake intake = new CueIntake(seen, timeline);

    List<String> promote(long now) {
        List<CueTimeline.Entry> due = new ArrayList<>(), expired = new ArrayList<>();
        intake.promote(now, 8, due, expired);
        List<String> ids = new ArrayList<>();
        for (CueTimeline.Entry e : due) ids.add(e.id);
        return ids;
    }

    @Test public void farCuesAndTimelineBatchesGoToTheTimeline() {
        assertEquals(CueIntake.Route.TIMELINE, intake.route("a", 10_000, 0, false));
        assertEquals(CueIntake.Route.TIMELINE, intake.route("b", 1_000, 0, true));
        assertEquals(CueIntake.Route.QUEUE, intake.route("c", 4_000, 0, false));
    }

    @Test public void snapshotOfATimelineCueIsNotSpokenTwice() {
        timeline.add("x", "Hello", "", 1000, 3000);
        // The active snapshot for the same cue arrives before the timeline tick promotes it.
        assertEquals(CueIntake.Route.DUPLICATE, intake.route("x", 1000, 900, false));
        assertEquals(java.util.Collections.singletonList("x"), promote(1000));
        assertEquals(CueIntake.Route.DUPLICATE, intake.route("x", 1000, 1200, false));
        assertTrue(promote(1300).isEmpty());
    }

    @Test public void timelineCueAlreadyQueuedFromASnapshotIsSkipped() {
        assertEquals(CueIntake.Route.QUEUE, intake.route("y", 2000, 1000, false));
        intake.queued("y");
        timeline.add("y", "Late batch", "", 2000, 4000); // the file batch arrives afterwards
        assertTrue("already queued from the snapshot", promote(2000).isEmpty());
        assertEquals(CueIntake.Route.DUPLICATE, intake.route("y", 2000, 2100, false));
    }

    @Test public void seekAllowsSpeakingAgainOnce() {
        timeline.add("z", "Again", "", 1000, 3000);
        assertEquals(1, promote(1000).size());
        intake.restart(0); // seek back
        assertEquals(1, promote(1000).size());
        assertTrue(promote(1100).isEmpty());
    }

    @Test public void fullQueueStillReportsMissedCues() {
        timeline.add("m", "missed", "", 1000, 2000);
        List<CueTimeline.Entry> due = new ArrayList<>(), expired = new ArrayList<>();
        intake.promote(5000, 0, due, expired);
        assertTrue(due.isEmpty());
        assertEquals(1, expired.size());
    }
}
