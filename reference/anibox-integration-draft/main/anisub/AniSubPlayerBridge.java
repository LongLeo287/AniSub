package com.anibox.tv.anisub;

import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.text.CueGroup;
import com.anibox.tv.anisub.protocol.PlaybackEvent;

/** Media3 lifecycle/event mapping lives here, leaving the Activity a small host. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class AniSubPlayerBridge implements AutoCloseable {
    private final AniSubClient client;
    private final AniSubCueAdapter cues;
    private Player player;
    private Player.Listener events;
    private String provenance = "";
    private String language = "";
    private String selectedTextTrack = "";

    public AniSubPlayerBridge(AniSubClient client) {
        this.client = client;
        cues = new AniSubCueAdapter(client);
    }
    public static AniSubPlayerBridge disabled() { return new AniSubPlayerBridge(AniSubClient.disabled()); }

    public void attach(Player next, String episodeId, String sourceId) {
        detach();
        player = next;
        provenance = sourceId;
        language = selectedLanguage(next.getCurrentTracks());
        selectedTextTrack = selectedTrackKey(next.getCurrentTracks());
        client.begin(episodeId, sourceId, position(next), next.getPlaybackParameters().speed);
        cues.use(new Media3CueSource());
        final Player owner = next;
        events = new Player.Listener() {
            private void emit(PlaybackEvent.Type type) {
                if (player == owner) client.playback(type, position(owner), owner.getPlaybackParameters().speed);
            }
            @Override public void onPlayWhenReadyChanged(boolean ready, int reason) {
                emit(ready ? PlaybackEvent.Type.PLAY : PlaybackEvent.Type.PAUSE);
            }
            @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition,
                    Player.PositionInfo newPosition, int reason) {
                if (player != owner) return;
                if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                    emit(PlaybackEvent.Type.SEEK);
                    cues.reset();
                }
            }
            @Override public void onPlaybackParametersChanged(PlaybackParameters parameters) {
                emit(PlaybackEvent.Type.PLAYBACK_SPEED);
            }
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED || state == Player.STATE_IDLE) emit(PlaybackEvent.Type.STOP);
            }
            @Override public void onTracksChanged(Tracks tracks) {
                if (player != owner) return;
                language = selectedLanguage(tracks);
                String nextTrack = selectedTrackKey(tracks);
                if (!nextTrack.equals(selectedTextTrack)) {
                    selectedTextTrack = nextTrack;
                    client.invalidateSpeechTimeline();
                    cues.reset();
                }
            }
        };
        next.addListener(events);
        client.playback(next.getPlayWhenReady() ? PlaybackEvent.Type.PLAY : PlaybackEvent.Type.PAUSE,
                position(next), next.getPlaybackParameters().speed);
    }

    /** Called by the existing Activity onCues callback before its visual style adjustment. */
    public void onCues(Player owner, CueGroup group) {
        if (owner == player) cues.onCues(group, position(owner), language, provenance);
    }

    public void detach() {
        Player outgoing = player;
        Player.Listener outgoingEvents = events;
        player = null;
        events = null;
        if (outgoing != null) {
            outgoing.removeListener(outgoingEvents);
            cues.release();
            client.stop(position(outgoing), outgoing.getPlaybackParameters().speed);
        }
    }
    @Override public void close() { detach(); client.close(); }
    private static long position(Player player) { return Math.max(0, player.getCurrentPosition()); }
    private static String selectedLanguage(Tracks tracks) {
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != androidx.media3.common.C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < group.length; i++) if (group.isTrackSelected(i)) {
                String language = group.getTrackFormat(i).language;
                return language == null ? "" : language;
            }
        }
        return "";
    }
    /** Track identity includes index/id, so switching between two tracks of one language is detected. */
    private static String selectedTrackKey(Tracks tracks) {
        StringBuilder key = new StringBuilder();
        int textGroupIndex = 0;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() == androidx.media3.common.C.TRACK_TYPE_TEXT) {
                for (int i = 0; i < group.length; i++) if (group.isTrackSelected(i)) {
                    androidx.media3.common.Format format = group.getTrackFormat(i);
                    key.append(textGroupIndex).append(':').append(i).append(':')
                            .append(format.id).append(':').append(format.language).append(':')
                            .append(format.sampleMimeType).append(':').append(format.label).append(';');
                }
                textGroupIndex++;
            }
        }
        return key.toString();
    }
}
