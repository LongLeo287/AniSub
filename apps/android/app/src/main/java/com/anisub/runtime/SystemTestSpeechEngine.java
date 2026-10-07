package com.anisub.runtime;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import java.util.Set;

/** Explicit test-only system voice. Never requests install, network, or audio focus. */
final class SystemTestSpeechEngine implements SpeechEngine {
    private TextToSpeech tts;
    private boolean ready;
    private String state = "initializing";
    SystemTestSpeechEngine(Context context, final Listener listener, final Runnable changed) {
        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS && tts != null) {
                tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                Set<Voice> voices = tts.getVoices();
                if (voices != null) for (Voice voice : voices) {
                    if ("vi".equals(voice.getLocale().getLanguage()) && !voice.isNetworkConnectionRequired()
                            && !voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                            && tts.isLanguageAvailable(voice.getLocale()) >= TextToSpeech.LANG_AVAILABLE
                            && tts.setVoice(voice) == TextToSpeech.SUCCESS) {
                        ready = true; break;
                    }
                }
                state = ready ? "local-vietnamese-ready" : "local-vietnamese-missing";
            } else state = "engine-unavailable";
            changed.run();
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { listener.started(id); }
            @Override public void onDone(String id) { listener.finished(id); }
            @Override public void onError(String id) { listener.failed(id); }
            @Override public void onStop(String id, boolean interrupted) { listener.finished(id); }
        });
    }
    public boolean ready() { return ready; }
    public String state() { return state; }
    public boolean speak(String text, String id) {
        if (!ready || tts == null) return false;
        Voice voice = tts.getVoice();
        if (voice == null || voice.isNetworkConnectionRequired() || voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                || !"vi".equals(voice.getLocale().getLanguage())) return false;
        return tts.speak(text, TextToSpeech.QUEUE_FLUSH, new Bundle(), id) == TextToSpeech.SUCCESS;
    }
    public void stop() { if (tts != null) tts.stop(); }
    public void release() { ready = false; if (tts != null) { tts.stop(); tts.shutdown(); tts = null; } }
}
