package com.anibox.tv.anisub;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.Tracks;
import com.anibox.tv.anisub.protocol.AniCue;
import com.anibox.tv.anisub.protocol.SpeechEvent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 23, manifest = Config.NONE)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class AniSubPlayerBridgeTest {
    private static Tracks.Group group(String id, String mime, boolean selected) {
        Format format = new Format.Builder().setId(id).setLabel(id).setLanguage("vi")
                .setSampleMimeType(mime).build();
        return new Tracks.Group(new TrackGroup(id, format), false,
                new int[]{C.FORMAT_HANDLED}, new boolean[]{selected});
    }

    @Test public void textIdentityChangesRetireSpeechButAudioVideoChangesDoNot() {
        AniSubClientTest.Fake fake = new AniSubClientTest.Fake(); AniSubClient client = fake.client();
        Tracks.Group original = group("text1", "text/vtt", true);
        Tracks initial = new Tracks(Collections.singletonList(original));
        final Player.Listener[] listener = {null};
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getCurrentTracks": return initial;
                        case "getCurrentPosition": return 500L;
                        case "getPlaybackParameters": return PlaybackParameters.DEFAULT;
                        case "getPlayWhenReady": return true;
                        case "addListener": listener[0] = (Player.Listener) args[0]; return null;
                        case "removeListener": return null;
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });
        AniSubPlayerBridge bridge = new AniSubPlayerBridge(client);
        bridge.attach(player, "episode", "provider:0");
        String session = client.session().id;
        client.receiveSpeech(new SpeechEvent(SpeechEvent.Type.STARTED, session, 0, "old"));
        client.cues(Collections.singletonList(new AniCue("line", 500, -1, "vi", "provider:0")));
        listener[0].onTracksChanged(new Tracks(Arrays.asList(group("audio", "audio/mp4a-latm", true), original)));
        assertEquals(0, client.session().timelineRevision); assertEquals(1, fake.speech.size());
        assertEquals(1, fake.cues.size());
        listener[0].onTracksChanged(new Tracks(Collections.singletonList(group("text2", "text/vtt", true))));
        assertEquals(1, client.session().timelineRevision); assertEquals(2, fake.speech.size());
        assertTrue(fake.cues.isEmpty());
        listener[0].onTracksChanged(Tracks.EMPTY);
        assertEquals(2, client.session().timelineRevision);
        listener[0].onTracksChanged(Tracks.EMPTY);
        assertEquals(2, client.session().timelineRevision);
        bridge.close();
    }
}
