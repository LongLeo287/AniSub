package com.anibox.tv.anisub;

import com.anibox.tv.anisub.protocol.AniCue;
import com.anibox.tv.anisub.protocol.AniSubError;
import com.anibox.tv.anisub.protocol.PlaybackEvent;
import com.anibox.tv.anisub.protocol.SpeechEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Single-owner-thread session router; no engine, IPC implementation, or audio policy. */
public final class AniSubClient implements AutoCloseable {
    public interface Listener {
        void speechStarted(SpeechEvent event);
        void speechFinished(SpeechEvent event);
        void onError(AniSubError error);
    }
    private final AniSubConnection connection;
    private final AniSubSettings settings;
    private final Listener listener;
    private AniSubSession session;
    private SpeechEvent activeSpeech;
    private boolean opened;
    private boolean closed;
    private boolean playing;
    private String previousEpisode = "";
    private String previousSource = "";

    public AniSubClient(AniSubConnection connection, AniSubSettings settings, Listener listener) {
        if (connection == null || settings == null) throw new IllegalArgumentException("Missing configuration");
        this.connection = connection;
        this.settings = settings;
        this.listener = listener;
    }

    public static AniSubClient disabled() {
        return new AniSubClient(AniSubConnection.disconnected(), AniSubSettings.disabled(), null);
    }

    public AniSubSession session() { return session; }
    public boolean acceptsCues() {
        return opened && session != null && !closed && settings.enabled && connection.connected()
                && connection.capabilities().compatible() && connection.capabilities().directSubtitles;
    }

    public void begin(String episodeId, String sourceId, long positionMs, float speed) {
        if (closed) return;
        stop(positionMs, speed);
        session = new AniSubSession(UUID.randomUUID().toString(), episodeId, sourceId, 0);
        boolean episodeChanged = !episodeId.equals(previousEpisode);
        boolean sourceChanged = !sourceId.equals(previousSource) || episodeChanged;
        previousEpisode = episodeId;
        previousSource = sourceId;
        if (!settings.enabled || !connection.connected()) return;
        if (!connection.capabilities().compatible()) {
            report(new AniSubError(AniSubError.Code.PROTOCOL_MISMATCH, session.id));
            return;
        }
        opened = true;
        final String ownerId = session.id;
        connection.openSession(session, settings, new AniSubConnection.Callback() {
            @Override public void onSpeech(SpeechEvent event) { receiveSpeech(event); }
            @Override public void onError(AniSubError error) {
                if (session != null && ownerId.equals(session.id)) report(error);
            }
            @Override public void onDisconnected() {
                if (session != null && ownerId.equals(session.id)) {
                    finishSpeech();
                    opened = false;
                    report(new AniSubError(AniSubError.Code.DISCONNECTED, ownerId));
                }
            }
        });
        if (episodeChanged) playback(PlaybackEvent.Type.EPISODE_CHANGE, positionMs, speed);
        if (sourceChanged)
            playback(PlaybackEvent.Type.SOURCE_CHANGE, positionMs, speed);
    }

    public void playback(PlaybackEvent.Type type, long positionMs, float speed) {
        if (session == null || closed) return;
        if (type == PlaybackEvent.Type.SEEK || type == PlaybackEvent.Type.PAUSE
                || type == PlaybackEvent.Type.STOP || type == PlaybackEvent.Type.PLAYBACK_SPEED) {
            invalidateSpeechTimeline();
        }
        if (type == PlaybackEvent.Type.PLAY) playing = true;
        if (type == PlaybackEvent.Type.PAUSE || type == PlaybackEvent.Type.STOP) playing = false;
        if (opened && connection.connected()) connection.sendPlayback(new PlaybackEvent(type, session, positionMs, speed));
    }

    public void cues(List<AniCue> cues) {
        if (!acceptsCues()) return;
        if (cues == null || cues.size() > 32) throw new IllegalArgumentException("Invalid cue snapshot");
        connection.sendCues(session, Collections.unmodifiableList(new ArrayList<>(cues)));
    }

    /** Subtitle track changes also retire pending speech, without inventing a playback command. */
    public void invalidateSpeechTimeline() {
        if (session == null || closed) return;
        finishSpeech();
        session = session.nextTimeline();
    }

    /** START/FINISH pairs are guarded by session/revision and actual playback intent. */
    public void receiveSpeech(SpeechEvent event) {
        if (event == null || session == null || closed || !opened || !connection.connected()
                || !settings.speechEnabled || !connection.capabilities().speech
                || !session.id.equals(event.sessionId) || session.timelineRevision != event.timelineRevision) return;
        if (event.type == SpeechEvent.Type.STARTED && playing) {
            if (activeSpeech != null && activeSpeech.speechId.equals(event.speechId)) return;
            finishSpeech();
            activeSpeech = event;
            if (listener != null) listener.speechStarted(event);
        } else if (event.type == SpeechEvent.Type.FINISHED && activeSpeech != null
                && activeSpeech.speechId.equals(event.speechId)) finishSpeech();
    }

    public void stop(long positionMs, float speed) {
        if (session == null) return;
        playback(PlaybackEvent.Type.STOP, positionMs, speed);
        finishSpeech();
        if (opened) connection.closeSession(session.id);
        opened = false;
        playing = false;
        session = null;
    }

    private void finishSpeech() {
        SpeechEvent speech = activeSpeech;
        activeSpeech = null;
        if (speech != null && listener != null) listener.speechFinished(speech.finished());
    }
    private void report(AniSubError error) { if (listener != null && error != null) listener.onError(error); }
    @Override public void close() {
        if (closed) return;
        stop(0, 1);
        closed = true;
        connection.disconnect();
    }
}
