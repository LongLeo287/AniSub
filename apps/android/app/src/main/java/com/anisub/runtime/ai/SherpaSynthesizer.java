package com.anisub.runtime.ai;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import java.io.File;

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

    /**
     * @param modelFile VITS .onnx inside the verified pack
     * @param tokensFile tokens.txt inside the verified pack
     * @param espeakDataDir espeak-ng-data directory inside the verified pack
     * @param threads inference threads (P650 has 4 cores; 2 leaves room for video decode)
     */
    public SherpaSynthesizer(File modelFile, File tokensFile, File espeakDataDir, int threads, int speakerId) {
        OfflineTtsConfig config = new OfflineTtsConfig();
        config.model.vits.model = modelFile.getAbsolutePath();
        config.model.vits.tokens = tokensFile.getAbsolutePath();
        config.model.vits.dataDir = espeakDataDir.getAbsolutePath();
        config.model.numThreads = Math.max(1, Math.min(4, threads));
        config.model.debug = false;
        config.model.provider = "cpu";
        config.maxNumSentences = 1;
        tts = new OfflineTts(config);
        sampleRate = tts.getSampleRate();
        speakers = Math.max(1, tts.getNumSpeakers());
        setSpeaker(speakerId);
    }

    /** Selects a speaker of a multi-speaker pack; unknown ids fall back to speaker 0. */
    public void setSpeaker(int id) { speakerId = id >= 0 && id < speakers ? id : 0; }
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
        return audio.getSamples();
    }

    public synchronized void release() { released = true; tts.release(); }
}
