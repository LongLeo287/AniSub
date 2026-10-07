package com.anisub.runtime.ai;

import org.junit.After;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Cancel / obsolete-PCM guards, look-ahead bounds, splitting and failure paths. */
public class NarrationPipelineTest {
    static final int RATE = 16000;

    /** Synthesizer whose calls can be blocked; records concurrency and cancel observations. */
    static final class FakeSynth implements NarrationPipeline.Synthesizer {
        final List<String> texts = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger inFlight = new AtomicInteger(), maxInFlight = new AtomicInteger();
        volatile CountDownLatch gate; volatile CountDownLatch entered = new CountDownLatch(1);
        volatile boolean sawCancel; volatile float[] override; volatile boolean hang;
        public int sampleRate() { return RATE; }
        public float[] synthesize(String text, float speed, NarrationPipeline.Cancel cancel) throws Exception {
            int n = inFlight.incrementAndGet(); maxInFlight.accumulateAndGet(n, Math::max);
            try {
                texts.add(text); entered.countDown();
                CountDownLatch g = gate; if (g != null) g.await(5, TimeUnit.SECONDS);
                if (hang) Thread.sleep(60_000);
                if (cancel.cancelled()) sawCancel = true;
                if (override != null) return override;
                float[] pcm = new float[RATE / 10]; Arrays.fill(pcm, 0.25f); return pcm; // 100 ms per segment
            } finally { inFlight.decrementAndGet(); }
        }
    }

    /** Instant sink: written frames are immediately "played". Records play/write per reset epoch. */
    static final class FakeSink implements NarrationPipeline.PcmSink {
        final List<String> log = Collections.synchronizedList(new ArrayList<>());
        final List<Short> firstSamples = Collections.synchronizedList(new ArrayList<>());
        volatile long written; volatile boolean full; volatile int halts;
        public void open(int sampleRate) { log.add("open:" + sampleRate); }
        public void reset() { written = 0; log.add("reset"); }
        public void play() { log.add("play"); }
        public int write(short[] pcm, int offset, int length) {
            if (full) return 0;
            if (written == 0) firstSamples.add(pcm[offset]);
            written += length; log.add("write:" + length); return length;
        }
        public long playedFrames() { return written; }
        public void halt() { halts++; log.add("halt"); }
        public void release() { log.add("release"); }
        long writes() { synchronized (log) { return log.stream().filter(s -> s.startsWith("write")).count(); } }
        boolean played() { synchronized (log) { return log.contains("play"); } }
    }

    static final class Events implements NarrationPipeline.Listener {
        final BlockingQueue<String> q = new LinkedBlockingQueue<>();
        public void started(String id) { q.add("started:" + id); }
        public void finished(String id) { q.add("finished:" + id); }
        public void failed(String id, String code) { q.add("failed:" + id + ":" + code); }
        String next() throws InterruptedException { String s = q.poll(5, TimeUnit.SECONDS); assertNotNull("event expected", s); return s; }
        void none(long ms) throws InterruptedException { assertNull(q.poll(ms, TimeUnit.MILLISECONDS)); }
    }

    FakeSynth synth = new FakeSynth(); FakeSink sink = new FakeSink(); Events events = new Events();
    NarrationPipeline p;
    NarrationPipeline pipeline(long stallMs) { p = new NarrationPipeline(synth, sink, events, new RatePolicy(), stallMs); return p; }
    @After public void close() { if (p != null) p.release(); }

    @Test public void speakPlaysThenFinishesWithFadeIn() throws Exception {
        pipeline(2000);
        assertTrue(p.speak("a", "Xin chào.", 1f));
        assertEquals("started:a", events.next());
        assertEquals("finished:a", events.next());
        assertTrue(sink.played());
        assertEquals("fade-in starts from silence", 0, (short) sink.firstSamples.get(0));
        assertEquals(0, p.preparedCount()); assertEquals(0, p.preparedBytes());
    }

    @Test public void obsoletePcmNeverPlaysAfterStop() throws Exception {
        pipeline(2000);
        synth.gate = new CountDownLatch(1);
        p.speak("a", "Câu cũ sẽ bị hủy.", 1f);
        assertTrue(synth.entered.await(5, TimeUnit.SECONDS));
        p.stop();                 // seek/pause/episode change while native inference is running
        synth.gate.countDown();   // native call returns late with now-obsolete PCM
        events.none(400);
        assertEquals("no PCM from the retired utterance", 0, sink.writes());
        assertFalse(sink.played());
        assertTrue("cooperative cancel visible to the provider", synth.sawCancel);
        assertTrue(p.diagnostics().contains("discarded=1"));
        // A new utterance after the stop plays normally.
        synth.gate = null;
        p.speak("b", "Câu mới.", 1f);
        assertEquals("started:b", events.next());
        assertEquals("finished:b", events.next());
    }

    @Test public void stopDuringPlaybackHaltsWithoutTerminalEvent() throws Exception {
        pipeline(5000);
        sink.full = true; // device buffer full: writer keeps polling
        synth.override = new float[RATE]; // 1 s
        p.speak("a", "Một câu dài.", 1f);
        Thread.sleep(200);
        p.stop();
        events.none(300);
        assertTrue(sink.halts >= 1);
        assertEquals(0, p.preparedCount());
    }

    @Test public void lookAheadIsBoundedAndSpeakDropsOlderPrefetches() throws Exception {
        pipeline(2000);
        synth.gate = new CountDownLatch(1);
        assertTrue(p.prefetch("a", "Một.", 1f));
        assertTrue(p.prefetch("b", "Hai.", 1f));
        assertFalse("third look-ahead rejected", p.prefetch("c", "Ba.", 1f));
        assertFalse("duplicate id rejected", p.prefetch("a", "Một.", 1f));
        assertEquals(Arrays.asList("a", "b"), p.ids());
        assertTrue(p.speak("b", "Hai.", 1f)); // host moved past a
        assertEquals(Collections.singletonList("b"), p.ids());
        synth.gate.countDown();
        assertEquals("started:b", events.next());
        assertEquals("finished:b", events.next());
        assertEquals("one inference worker", 1, synth.maxInFlight.get());
    }

    @Test public void retainOnlyDropsPrefetchesNoLongerQueued() {
        pipeline(2000);
        synth.gate = new CountDownLatch(1);
        p.prefetch("a", "Một.", 1f); p.prefetch("b", "Hai.", 1f);
        p.retainOnly(new HashSet<>(Collections.singletonList("b")));
        assertEquals(Collections.singletonList("b"), p.ids());
        synth.gate.countDown();
    }

    @Test public void longCueIsSynthesizedInOrderedSegments() throws Exception {
        pipeline(2000);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 12; i++) b.append("Đây là câu số ").append(i).append(" của một lời thoại rất dài. ");
        String text = b.toString();
        p.speak("long", text, 1f);
        assertEquals("started:long", events.next());
        assertEquals("finished:long", events.next());
        StringBuilder joined = new StringBuilder(); for (String t : synth.texts) joined.append(t);
        assertEquals(text, joined.toString());
        assertTrue(synth.texts.size() > 1);
    }

    @Test public void invalidPcmFailsVisibly() throws Exception {
        pipeline(2000);
        synth.override = new float[]{0f, Float.NaN};
        p.speak("nan", "Lỗi.", 1f);
        assertEquals("failed:nan:PROVIDER_FAILED", events.next());
        synth.override = new float[RATE * (NarrationPipeline.MAX_WAVE_SECONDS + 1)];
        p.speak("long", "Quá dài.", 1f);
        assertEquals("failed:long:BACKPRESSURE", events.next());
        assertFalse(sink.played());
    }

    @Test public void stuckNativeCallTimesOut() throws Exception {
        pipeline(300);
        synth.hang = true;
        p.speak("stuck", "Treo.", 1f);
        assertEquals("failed:stuck:TIMEOUT", events.next());
    }

    @Test public void emptyTextIsRejectedWithoutKillingTheWorker() throws Exception {
        pipeline(2000);
        assertFalse(p.speak("e", "", 1f));
        assertFalse(p.prefetch("e", "", 1f));
        assertTrue(p.speak("ok", "Vẫn chạy.", 1f));
        assertEquals("started:ok", events.next());
        assertEquals("finished:ok", events.next());
    }

    @Test public void pcmConversionClipsAndFades() {
        short[] pcm = NarrationPipeline.toPcm(new float[]{2f, -2f, 0.5f, 4f}, 2);
        assertEquals(0, pcm[0]);                // fade-in frame 0 starts from silence
        assertEquals(-32767, pcm[1]);           // half gain: -2 * 0.5 = -1.0 full scale
        assertEquals(16384, pcm[2]);
        assertEquals(32767, pcm[3]);            // clipped
    }
}
