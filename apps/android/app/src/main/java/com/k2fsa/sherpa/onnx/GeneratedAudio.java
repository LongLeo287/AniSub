// Derived from sherpa-onnx v1.13.8 java-api (Copyright 2024 Xiaomi Corporation, Apache-2.0).
// Modified for AniSub: trimmed to the JNI field layout read by libsherpa-onnx-jni; Android
// System.loadLibrary instead of the desktop LibraryLoader. See THIRD_PARTY_NOTICES.md.
package com.k2fsa.sherpa.onnx;

/** Constructed by JNI through the ([FI)V constructor. */
public final class GeneratedAudio {
    private final float[] samples;
    private final int sampleRate;
    public GeneratedAudio(float[] samples, int sampleRate) { this.samples = samples; this.sampleRate = sampleRate; }
    public float[] getSamples() { return samples; }
    public int getSampleRate() { return sampleRate; }
}
