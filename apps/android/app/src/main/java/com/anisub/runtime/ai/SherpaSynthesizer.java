package com.anisub.runtime.ai;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import java.io.File;
import com.anisub.runtime.settings.AniSubPrefs;

/**
 * sherpa-onnx VITS/Piper adapter. Android-free so the same binding runs in host benchmarks.
 * Paths come only from a verified, leased voice-pack directory. Not thread-safe: the
 * pipeline's single inference worker is the only caller of synthesize().
 */
public final class SherpaSynthesizer implements NarrationPipeline.Synthesizer {
    public static final String ENGINE = "sherpa-onnx";
    public static final String ENGINE_VERSION = "1.13.8";
    private final OfflineTts tts;
    private final int sampleRate, speakers;
    private volatile int speakerId;
    private boolean released;
    private final AniSubPrefs.Snapshot settings;

    /**
     * @param modelFile VITS .onnx inside the verified pack
     * @param tokensFile tokens.txt inside the verified pack
     * @param espeakDataDir espeak-ng-data directory inside the verified pack
     * @param threads inference threads (P650 has 4 cores; 2 leaves room for video decode)
     */
    public SherpaSynthesizer(File modelFile, File tokensFile, File espeakDataDir, int threads, int speakerId) {
        this(modelFile,tokensFile,espeakDataDir,threads,speakerId,new AniSubPrefs.Snapshot(1,0,100,0,"normal","normal"));
    }
    public SherpaSynthesizer(File modelFile, File tokensFile, File espeakDataDir, int threads, int speakerId, AniSubPrefs.Snapshot settings) {
        this.settings=settings;
        try { VoiceTokens.verify(tokensFile); }
        catch(java.io.IOException invalid){throw new IllegalArgumentException("MODEL_TOKEN_FORMAT",invalid);}
        OfflineTtsConfig config = new OfflineTtsConfig();
        config.model.vits.model = modelFile.getAbsolutePath();
        config.model.vits.tokens = tokensFile.getAbsolutePath();
        config.model.vits.dataDir = espeakDataDir.getAbsolutePath();
        config.model.numThreads = Math.max(1, Math.min(4, threads));
        config.model.debug = false;
        config.model.provider = "cpu";
        config.maxNumSentences = 1;
        config.model.vits.noiseScale=.667f;config.model.vits.noiseScaleW=.8f;
        if("calm".equals(settings.style)){config.model.vits.noiseScale=.45f;config.model.vits.noiseScaleW=.6f;}
        if("lively".equals(settings.style)){config.model.vits.noiseScale=.8f;config.model.vits.noiseScaleW=1f;}
        config.silenceScale="short".equals(settings.gap)?.1f:"long".equals(settings.gap)?.4f:.2f;
        tts = new OfflineTts(config);
        sampleRate = tts.getSampleRate();
        speakers = Math.max(1, tts.getNumSpeakers());
        try { setSpeaker(speakerId); }
        catch (RuntimeException invalid) { tts.release(); throw invalid; }
    }

    /** A descriptor/model mismatch must fail, never silently read with another speaker. */
    public void setSpeaker(int id) {
        if(id<0||id>=speakers)throw new IllegalArgumentException("MODEL_SPEAKER_MISMATCH");
        speakerId=id;
    }
    public int speakers() { return speakers; }

    @Override public int sampleRate() { return sampleRate; }

    /**
     * Synchronized with {@link #release()}: the native object can never be freed while a
     * generate call is still running on the inference thread (release waits for it).
     */
    @Override public synchronized float[] synthesize(String text, float speed, NarrationPipeline.Cancel cancel) {
        if (released) throw new IllegalStateException("released");
        if (cancel.cancelled()) return new float[0];
        // Callback runs per generated sentence; returning 0 asks native generation to stop early.
        GeneratedAudio audio = tts.generate(text, speakerId, speed, samples -> cancel.cancelled() ? 0 : 1);
        if (audio == null || audio.getSamples() == null) throw new IllegalStateException("no audio");
        if (audio.getSampleRate() != sampleRate) throw new IllegalStateException("sample rate changed");
        if(cancel.cancelled())return new float[0];
        return PcmEffects.apply(audio.getSamples(),sampleRate,settings.pitch,settings.volume,settings.pauseMs);
    }

    public synchronized void release() { released = true; tts.release(); }
}
