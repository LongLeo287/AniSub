package com.anibox.tv.anisub;

import com.anibox.tv.anisub.protocol.AniCue;
import com.anibox.tv.anisub.protocol.AniSubError;
import com.anibox.tv.anisub.protocol.PlaybackEvent;
import com.anibox.tv.anisub.protocol.SpeechEvent;
import java.util.List;

/**
 * Transport port for a future Binder/AIDL service. Implementations must be non-blocking, use
 * bounded queues, and dispatch replies on the same owner thread as AniSubClient/Media3.
 * AniBox's shipped default performs no binding, networking or engine work.
 */
public interface AniSubConnection {
    interface Callback {
        void onSpeech(SpeechEvent event);
        void onError(AniSubError error);
        void onDisconnected();
    }
    boolean connected();
    AniSubCapabilities capabilities();
    void openSession(AniSubSession session, AniSubSettings settings, Callback callback);
    void sendPlayback(PlaybackEvent event);
    /** A complete active text snapshot. An empty list clears previous cues. */
    void sendCues(AniSubSession session, List<AniCue> cues);
    void closeSession(String sessionId);
    void disconnect();

    static AniSubConnection disconnected() {
        return new AniSubConnection() {
            @Override public boolean connected() { return false; }
            @Override public AniSubCapabilities capabilities() { return AniSubCapabilities.unavailable(); }
            @Override public void openSession(AniSubSession session, AniSubSettings settings, Callback callback) { }
            @Override public void sendPlayback(PlaybackEvent event) { }
            @Override public void sendCues(AniSubSession session, List<AniCue> cues) { }
            @Override public void closeSession(String sessionId) { }
            @Override public void disconnect() { }
        };
    }
}
