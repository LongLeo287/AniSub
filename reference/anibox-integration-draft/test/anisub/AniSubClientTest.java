package com.anibox.tv.anisub;

import com.anibox.tv.anisub.protocol.AniCue;
import com.anibox.tv.anisub.protocol.AniSubError;
import com.anibox.tv.anisub.protocol.PlaybackEvent;
import com.anibox.tv.anisub.protocol.SpeechEvent;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class AniSubClientTest {
    static final class Fake implements AniSubConnection, AniSubClient.Listener {
        final List<PlaybackEvent> events = new ArrayList<>();
        final List<SpeechEvent> speech = new ArrayList<>();
        List<AniCue> cues;
        Callback callback;
        int opens, closes, errors;
        boolean connected = true;
        AniSubCapabilities capabilities = new AniSubCapabilities(0, true, true);
        @Override public boolean connected() { return connected; }
        @Override public AniSubCapabilities capabilities() { return capabilities; }
        @Override public void openSession(AniSubSession s, AniSubSettings settings, Callback cb) { opens++; callback = cb; }
        @Override public void sendPlayback(PlaybackEvent e) { events.add(e); }
        @Override public void sendCues(AniSubSession s, List<AniCue> c) { cues = c; }
        @Override public void closeSession(String s) { closes++; }
        @Override public void disconnect() { connected = false; }
        @Override public void speechStarted(SpeechEvent e) { speech.add(e); }
        @Override public void speechFinished(SpeechEvent e) { speech.add(e); }
        @Override public void onError(AniSubError e) { errors++; }
        AniSubClient client() { return new AniSubClient(this, new AniSubSettings(true, true, "vi"), this); }
    }

    @Test public void disabledNeverOpensOrSends() {
        Fake fake = new Fake();
        AniSubClient client = new AniSubClient(fake, AniSubSettings.disabled(), fake);
        client.begin("episode", "source", 0, 1);
        client.playback(PlaybackEvent.Type.PLAY, 0, 1);
        client.cues(Collections.singletonList(new AniCue("text", 0, -1, "vi", "provider")));
        assertEquals(0, fake.opens);
        assertTrue(fake.events.isEmpty());
        assertNull(fake.cues);
        assertFalse(client.acceptsCues());
    }

    @Test public void forwardsEveryPlaybackTypeAndPreservesTimeline() {
        Fake fake = new Fake(); AniSubClient client = fake.client();
        client.begin("episode", "source", 1200, 1.25f);
        client.playback(PlaybackEvent.Type.PLAY, 1200, 1.25f);
        client.playback(PlaybackEvent.Type.PAUSE, 1300, 1.25f);
        client.playback(PlaybackEvent.Type.SEEK, 4000, 1.25f);
        client.playback(PlaybackEvent.Type.PLAYBACK_SPEED, 4000, 1.5f);
        client.stop(5000, 1.5f);
        assertArrayEquals(PlaybackEvent.Type.values(), new PlaybackEvent.Type[] {
            fake.events.get(2).type, fake.events.get(3).type, fake.events.get(4).type,
            fake.events.get(6).type, fake.events.get(0).type, fake.events.get(1).type, fake.events.get(5).type });
        assertEquals(4000, fake.events.get(4).positionMs);
        assertEquals(2, fake.events.get(4).session.timelineRevision);
        assertEquals(1, fake.closes);
    }

    @Test public void staleSpeechCannotDuckNewEpisodeOrSeekAndPairsAreBalanced() {
        Fake fake = new Fake(); AniSubClient client = fake.client();
        client.begin("ep1", "source", 0, 1);
        client.playback(PlaybackEvent.Type.PLAY, 0, 1);
        AniSubSession first = client.session();
        SpeechEvent started = new SpeechEvent(SpeechEvent.Type.STARTED, first.id, first.timelineRevision, "s1");
        client.receiveSpeech(started); client.receiveSpeech(started);
        assertEquals(1, fake.speech.size());
        client.playback(PlaybackEvent.Type.SEEK, 2000, 1);
        assertEquals(SpeechEvent.Type.FINISHED, fake.speech.get(1).type);
        client.receiveSpeech(started);
        client.begin("ep2", "source", 0, 1);
        client.playback(PlaybackEvent.Type.PLAY, 0, 1);
        client.receiveSpeech(started);
        assertEquals(2, fake.speech.size());
        assertNotEquals(first.id, client.session().id);
    }

    @Test public void cueSnapshotIsImmutableAndEmptyClears() {
        Fake fake = new Fake(); AniSubClient client = fake.client();
        client.begin("ep", "source", 0, 1);
        List<AniCue> input = new ArrayList<>(); input.add(new AniCue("text", 100, -1, "vi", "provider"));
        client.cues(input); input.clear();
        assertEquals(1, fake.cues.size());
        assertThrows(UnsupportedOperationException.class, () -> fake.cues.clear());
        client.cues(Collections.emptyList()); assertTrue(fake.cues.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> client.cues(Collections.nCopies(33,
                new AniCue("text", 0, -1, "", ""))));
    }

    @Test public void subtitleTrackChangeFinishesSpeechAndRejectsOldRevisionReplies() {
        Fake fake = new Fake(); AniSubClient client = fake.client();
        client.begin("ep", "source", 0, 1); client.playback(PlaybackEvent.Type.PLAY, 0, 1);
        AniSubSession before = client.session();
        SpeechEvent old = new SpeechEvent(SpeechEvent.Type.STARTED, before.id, before.timelineRevision, "old");
        client.receiveSpeech(old);
        client.invalidateSpeechTimeline();
        assertEquals(2, fake.speech.size());
        assertEquals(SpeechEvent.Type.FINISHED, fake.speech.get(1).type);
        assertEquals(before.timelineRevision + 1, client.session().timelineRevision);
        SpeechEvent current = new SpeechEvent(SpeechEvent.Type.STARTED, before.id,
                client.session().timelineRevision, "current");
        client.receiveSpeech(current);
        client.receiveSpeech(old); client.receiveSpeech(old.finished());
        assertEquals(3, fake.speech.size());
        client.receiveSpeech(current.finished());
        assertEquals(4, fake.speech.size());
        assertEquals("current", fake.speech.get(3).speechId);
    }

    @Test public void disconnectFinishesSpeechAndRejectsSubsequentReplies() {
        Fake fake = new Fake(); AniSubClient client = fake.client();
        client.begin("ep", "source", 0, 1); client.playback(PlaybackEvent.Type.PLAY, 0, 1);
        AniSubSession session = client.session();
        SpeechEvent event = new SpeechEvent(SpeechEvent.Type.STARTED, session.id, 0, "s");
        fake.callback.onSpeech(event); fake.connected = false; fake.callback.onDisconnected();
        fake.callback.onSpeech(event);
        assertEquals(2, fake.speech.size()); assertEquals(1, fake.errors);
        assertFalse(client.acceptsCues());
    }

    @Test public void unchangedEpisodeReloadDoesNotEmitFalseEpisodeChange() {
        Fake fake = new Fake(); AniSubClient client = fake.client();
        client.begin("ep", "source", 0, 1); client.stop(500, 1);
        fake.events.clear(); client.begin("ep", "source", 500, 1);
        assertTrue(fake.events.isEmpty());
        client.begin("ep", "other", 500, 1);
        assertEquals(PlaybackEvent.Type.SOURCE_CHANGE, fake.events.get(1).type);
    }

    @Test public void protocolMismatchDoesNotOpenAndCloseIsIdempotent() {
        Fake fake = new Fake(); fake.capabilities = new AniSubCapabilities(99, true, true);
        AniSubClient client = fake.client(); client.begin("ep", "source", 0, 1);
        assertEquals(1, fake.errors); assertEquals(0, fake.opens);
        client.close(); client.close(); client.begin("ep2", "source", 0, 1);
        assertNull(client.session()); assertEquals(0, fake.opens);
    }
}
