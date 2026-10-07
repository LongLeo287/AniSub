package com.anisub.runtime.voice;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** First-run automatic download of the default voice / Vietnamese model: no prompt, retry with backoff. */
public class AutoDownloaderTest {
    final List<Long> delays = new ArrayList<>();
    final List<Runnable> tasks = new ArrayList<>();
    boolean installed, busy, startOk = true;
    int starts;
    final AutoDownloader auto = new AutoDownloader(new AutoDownloader.Target() {
        public boolean installed() { return installed; }
        public boolean busy() { return busy; }
        public boolean start() { starts++; busy = startOk; return startOk; }
    }, (task, ms) -> { tasks.add(task); delays.add(ms); });

    void fire() { Runnable r = tasks.remove(0); r.run(); }

    @Test public void firstRunStartsWithoutAskingAndStopsWhenInstalled() {
        auto.kick();
        assertEquals("starts at once", 1, starts);
        auto.kick();
        assertEquals("not twice while busy", 1, starts);
        busy = false; installed = true;
        auto.finished(true);
        assertTrue(tasks.isEmpty());
        auto.kick();
        assertEquals("nothing to do once installed", 1, starts);
    }

    @Test public void failuresRetryWithGrowingBackoffThenHourly() {
        auto.kick();
        for (int i = 0; i < 7; i++) {
            busy = false;
            auto.finished(false);
            assertEquals(Math.min(i, AutoDownloader.BACKOFF_MS.length - 1), indexOf(delays.get(i)));
            fire();
        }
        assertEquals(8, starts);
        assertEquals(30_000L, (long) delays.get(0));
        assertEquals(3_600_000L, (long) delays.get(6));
    }

    @Test public void aStartThatCannotBeginCountsAsAFailedAttempt() {
        startOk = false;
        auto.kick();
        assertEquals(1, starts);
        assertTrue(auto.retryPending());
        startOk = true;
        fire();
        assertEquals(2, starts);
    }

    @Test public void successResetsTheBackoff() {
        auto.kick(); busy = false; auto.finished(false); fire(); busy = false;
        auto.finished(false); fire(); busy = false;
        assertEquals(2, auto.attempts());
        installed = true;
        auto.finished(true);
        assertEquals(0, auto.attempts());
    }

    @Test public void pauseStopsRetriesUntilResume() {
        auto.kick(); busy = false;
        auto.finished(false);
        auto.pause();
        fire(); // the pending retry is void
        assertEquals(1, starts);
        auto.resume();
        assertEquals(2, starts);
    }

    static int indexOf(long delay) {
        for (int i = 0; i < AutoDownloader.BACKOFF_MS.length; i++) if (AutoDownloader.BACKOFF_MS[i] == delay) return i;
        return -1;
    }
}
