package com.anisub.runtime;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import java.util.Set;
import com.anisub.runtime.settings.AniSubPrefs;
import com.anisub.runtime.voice.VoiceRegistry;

/** Explicit test-only system voice. Never requests install, network, or audio focus. */
final class SystemTestSpeechEngine implements SpeechEngine {
    private TextToSpeech tts;
    private volatile boolean ready;
    private volatile Voice legacyVoice;
    private String language="vi";
    private float volume=1f;
    private volatile String state = "initializing";
    private final SystemVoices inventory;
    private final java.util.concurrent.ExecutorService scanner = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "anisub-system-tts-scan"); t.setDaemon(true); return t; });
    SystemTestSpeechEngine(Context context, SystemVoices inventory, final Listener listener, final Runnable changed) {
        this.inventory = inventory;
        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS && tts != null) {
                tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                final TextToSpeech client = tts;
                // getVoices / isLanguageAvailable are binder calls: never on the main thread.
                try { scanner.execute(() -> scanLegacy(client, changed)); }
                catch (java.util.concurrent.RejectedExecutionException released) { /* engine already released */ }
            } else { state = "engine-unavailable"; changed.run(); }
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { listener.started(id); }
            @Override public void onDone(String id) { listener.finished(id); }
            @Override public void onError(String id) { listener.failed(id); }
            @Override public void onStop(String id, boolean interrupted) { listener.finished(id); }
        });
    }
    private void scanLegacy(TextToSpeech client, Runnable changed) {
        try {
            Set<Voice> voices = client.getVoices();
            if (voices != null) for (Voice voice : voices) {
                if ("vi".equals(voice.getLocale().getLanguage()) && !voice.isNetworkConnectionRequired()
                        && (voice.getFeatures() == null || !voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
                        && client.isLanguageAvailable(voice.getLocale()) >= TextToSpeech.LANG_AVAILABLE
                        && client.setVoice(voice) == TextToSpeech.SUCCESS) {
                    ready = true; legacyVoice = voice; break;
                }
            }
            state = ready ? "local-vietnamese-ready" : "local-vietnamese-missing";
        } catch (RuntimeException failed) { state = "engine-unavailable"; }
        changed.run();
    }
    public boolean ready() { return ready; }
    public String state() { return state; }
    public boolean select(VoiceRegistry.Entry selected,AniSubPrefs.Snapshot settings){
        if(tts==null||!selected.engine.equals(tts.getDefaultEngine()))return false;
        Voice v=inventory==null?null:inventory.voice(selected.engine,selected.voiceName,selected.language); // cached scan, no binder call here
        if(v!=null&&tts.setVoice(v)==TextToSpeech.SUCCESS){
            language=selected.language;volume=settings.volume/100f;tts.setSpeechRate(settings.rate);tts.setPitch((float)Math.pow(2,settings.pitch/12.0));return true;
        }return false;
    }
    public void legacy(){language="vi";volume=1f;if(tts!=null){if(legacyVoice!=null)tts.setVoice(legacyVoice);tts.setPitch(1);tts.setSpeechRate(1);}}
    public boolean speak(String text, String id) {
        if (tts == null) return false;
        Voice voice = tts.getVoice();
        if (voice == null || voice.isNetworkConnectionRequired() || voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                || !language.equals(voice.getLocale().getLanguage())) return false;
        Bundle params=new Bundle();params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,volume);
        return tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) == TextToSpeech.SUCCESS;
    }
    public void stop() { if (tts != null) tts.stop(); }
    public void release() { ready = false; scanner.shutdownNow(); if (tts != null) { tts.stop(); tts.shutdown(); tts = null; } }
}
