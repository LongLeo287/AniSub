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
    private boolean ready;
    private Voice legacyVoice;
    private String language="vi";
    private float volume=1f;
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
                        ready = true; legacyVoice=voice; break;
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
    public boolean select(VoiceRegistry.Entry selected,AniSubPrefs.Snapshot settings){
        if(tts==null||!selected.engine.equals(tts.getDefaultEngine()))return false;
        Set<Voice> voices=tts.getVoices();if(voices!=null)for(Voice v:voices)if(selected.voiceName.equals(v.getName())&&SystemVoices.local(tts,v)
                &&selected.language.equals(v.getLocale().getLanguage())&&tts.setVoice(v)==TextToSpeech.SUCCESS){
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
    public void release() { ready = false; if (tts != null) { tts.stop(); tts.shutdown(); tts = null; } }
}
