package com.anisub.runtime.translate;

import org.junit.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Lookahead scheduler with a fake asynchronous translator (no ML Kit, no Android). */
public class TranslationSchedulerTest {
    /** Records requests; answers only when the test says so (like ML Kit on its own thread). */
    static final class FakeTranslator implements TranslationScheduler.Translator {
        final List<String> requests = new ArrayList<>();
        final ArrayDeque<Object[]> pending = new ArrayDeque<>();
        int concurrent, maxConcurrent;
        String failWith;
        @Override public void translate(String text, String from, String to, TranslationScheduler.Callback done) {
            requests.add(from + ">" + to + ":" + text);
            concurrent++; maxConcurrent = Math.max(maxConcurrent, concurrent);
            pending.add(new Object[]{text, done});
        }
        /** Completes the oldest request. */
        boolean answer() {
            Object[] p = pending.poll();
            if (p == null) return false;
            concurrent--;
            if (failWith != null) ((TranslationScheduler.Callback) p[1]).done(null, failWith);
            else ((TranslationScheduler.Callback) p[1]).done("VI(" + p[0] + ")", null);
            return true;
        }
        void answerAll() { while (answer()) { } }
    }

    final FakeTranslator fake = new FakeTranslator();
    final List<String> events = new ArrayList<>();
    /** Direct delivery: the test thread plays the service's main looper. */
    final TranslationScheduler s = new TranslationScheduler(fake, Runnable::run, new TranslationScheduler.Listener() {
        public void translated(String cueId) { events.add("ok:" + cueId); }
        public void failed(String cueId, String code) { events.add("fail:" + cueId + ":" + code); }
    });

    static List<CueTimeline.Entry> window(CueTimeline t, long from, long to) { return t.window(from, to, 64); }

    @Test public void urgentFirstThenEarliestStartOneAtATime() {
        s.reset("en", "vi");
        s.want("late", "Later line", 50_000, false);
        s.want("early", "Early line", 10_000, false);
        assertEquals(1, fake.requests.size()); // first registered goes out at once
        s.want("now", "Now line", 3_000, true);
        fake.answer(); // "late" done
        fake.answer(); // next must be the urgent one
        fake.answer();
        assertEquals("en>vi:Later line", fake.requests.get(0));
        assertEquals("en>vi:Now line", fake.requests.get(1));
        assertEquals("en>vi:Early line", fake.requests.get(2));
        assertEquals(1, fake.maxConcurrent);
        assertEquals("VI(Now line)", s.result("now"));
        assertNull(s.error("now"));
    }

    @Test public void lookaheadTranslatesTheNextNinetySecondsBeforeTheirStart() {
        CueTimeline t = new CueTimeline();
        for (int i = 0; i < 100; i++) t.add("c" + i, "Line " + i, "en", 2_000L * i, 2_000L * i + 1500);
        s.reset("en", "vi");
        // At media 0 the window is [0, 90 s]: cues 0..45.
        s.lookahead(window(t, 0, TranslationScheduler.LOOKAHEAD_MS), TextCleaner::clean);
        fake.answerAll();
        for (int i = 0; i <= 45; i++) assertEquals("VI(Line " + i + ")", s.result("c" + i));
        assertNull("beyond the window: not yet", s.result("c46"));
        assertFalse(s.known("c46"));
        // Playback moves on; the window slides and the next cues are ready long before their start.
        s.lookahead(window(t, 30_000, 30_000 + TranslationScheduler.LOOKAHEAD_MS), TextCleaner::clean);
        fake.answerAll();
        for (int i = 46; i <= 60; i++) assertNotNull("c" + i, s.result("c" + i));
        assertEquals(61, fake.requests.size());
    }

    @Test public void sameLineInTheSamePairIsTranslatedOnce() {
        s.reset("en", "vi");
        s.want("a", "Yes.", 1000, false);
        fake.answerAll();
        s.want("b", "Yes.", 5000, false);
        assertEquals("cache hit answers at once", "VI(Yes.)", s.result("b"));
        assertEquals(1, fake.requests.size());
        assertTrue(s.diagnostics().contains("cacheHits=1"));
    }

    @Test public void emptyCueNeedsNoTranslation() {
        s.reset("en", "vi");
        s.want("e", "", 0, true);
        assertEquals("", s.result("e"));
        assertTrue(fake.requests.isEmpty());
    }

    @Test public void resetDiscardsLateResultsOfTheOldSession() {
        s.reset("en", "vi");
        s.want("x", "Old line", 0, true);
        s.reset("en", "vi"); // new session
        fake.answer(); // the old result arrives late
        assertNull(s.result("x"));
        assertFalse(s.known("x"));
        assertEquals(0, s.running());
        s.want("y", "New line", 0, true);
        assertEquals(2, fake.requests.size());
    }

    @Test public void modelMissingFailsEveryPendingCueAtOnce() {
        s.reset("ja", "vi");
        for (int i = 0; i < 5; i++) s.want("c" + i, "line " + i, i * 1000, false);
        fake.failWith = TranslationScheduler.E_MODEL_MISSING;
        fake.answer();
        for (int i = 0; i < 5; i++) assertEquals(TranslationScheduler.E_MODEL_MISSING, s.error("c" + i));
        assertEquals("no retry storm", 1, fake.requests.size());
        assertEquals(TranslationScheduler.E_MODEL_MISSING, s.fatalError());
        s.want("later", "x", 9000, true);
        assertEquals(TranslationScheduler.E_MODEL_MISSING, s.error("later"));
    }

    @Test public void transientFailureOnlyFailsThatCue() {
        s.reset("en", "vi");
        s.want("a", "A", 0, false); s.want("b", "B", 1, false);
        fake.failWith = TranslationScheduler.E_FAILED; fake.answer();
        fake.failWith = null; fake.answerAll();
        assertEquals(TranslationScheduler.E_FAILED, s.error("a"));
        assertEquals("VI(B)", s.result("b"));
        assertNull(s.fatalError());
    }

    @Test public void undeterminedSourceWaitsForDetection() {
        s.reset(LanguageTags.UND, "vi");
        assertTrue(s.waitingForSource());
        assertFalse(s.active());
        s.want("a", "Hello there", 0, true);
        assertTrue("nothing is translated before the language is known", fake.requests.isEmpty());
        s.setSource("en");
        assertTrue(s.active());
        assertEquals("en>vi:Hello there", fake.requests.get(0));
    }

    @Test public void sameLanguageIsNotActive() {
        s.reset("vi", "vi");
        assertFalse(s.active());
        s.want("a", "Xin chào", 0, true);
        assertTrue(fake.requests.isEmpty());
    }

    @Test public void itemsAndCacheAreBounded() {
        s.reset("en", "vi");
        for (int i = 0; i < TranslationScheduler.MAX_ITEMS + 200; i++) { s.want("c" + i, "Line number " + i, i * 100L, false); fake.answerAll(); }
        assertTrue(s.size() <= TranslationScheduler.MAX_ITEMS);
        assertTrue(s.cacheSize() <= TranslationScheduler.CACHE_ENTRIES);
        // The newest cues are the ones kept.
        assertNotNull(s.result("c" + (TranslationScheduler.MAX_ITEMS + 199)));
        assertFalse(s.known("c0"));
    }

    @Test public void forgottenCueResultIsIgnored() {
        s.reset("en", "vi");
        s.want("a", "A", 0, true);
        s.forget("a");
        fake.answer();
        assertFalse(s.known("a"));
        assertEquals(0, s.running());
    }

    @Test public void clearedSchedulerDoesNothing() {
        s.clear();
        s.want("a", "A", 0, true);
        assertTrue(fake.requests.isEmpty());
        assertFalse(s.known("a"));
    }

    /** Fake timer: the test fires due tasks by advancing time. */
    static final class FakeDelay implements TranslationScheduler.Delay {
        final List<long[]> at = new ArrayList<>(); final List<Runnable> tasks = new ArrayList<>(); long now;
        public Runnable schedule(Runnable task, long delayMs) {
            final int i = tasks.size(); tasks.add(task); at.add(new long[]{now + delayMs});
            return () -> tasks.set(i, null);
        }
        void advance(long ms) {
            now += ms;
            for (int i = 0; i < tasks.size(); i++) if (tasks.get(i) != null && at.get(i)[0] <= now) { Runnable r = tasks.get(i); tasks.set(i, null); r.run(); }
        }
    }

    @Test public void aTranslationThatNeverAnswersTimesOutAndTheNextOneRuns() {
        FakeDelay delay = new FakeDelay();
        TranslationScheduler t = new TranslationScheduler(fake, Runnable::run, delay, new TranslationScheduler.Listener() {
            public void translated(String cueId) { events.add("ok:" + cueId); }
            public void failed(String cueId, String code) { events.add("fail:" + cueId + ":" + code); }
        });
        t.reset("en", "vi");
        t.want("stuck", "Stuck line", 0, true);
        t.want("next", "Next line", 1000, false);
        assertEquals(1, fake.requests.size());
        delay.advance(TranslationScheduler.TIMEOUT_MS - 1);
        assertNull(t.error("stuck"));
        delay.advance(1);
        assertEquals(TranslationScheduler.E_FAILED, t.error("stuck"));
        assertNull("a timeout is not fatal for the pair", t.fatalError());
        assertEquals("the next cue is sent at once", 2, fake.requests.size());
        fake.answer(); // the stuck answer arrives late: ignored
        assertNull(t.result("stuck"));
        assertEquals(TranslationScheduler.E_FAILED, t.error("stuck"));
        fake.answer(); // "next"
        assertEquals("VI(Next line)", t.result("next"));
        delay.advance(TranslationScheduler.TIMEOUT_MS); // its timer was cancelled on success
        assertEquals("VI(Next line)", t.result("next"));
        assertEquals(0, t.running());
    }
}
