// Derived from sherpa-onnx v1.13.8 java-api (Copyright 2024 Xiaomi Corporation, Apache-2.0).
// Modified for AniSub: trimmed to the JNI field layout read by libsherpa-onnx-jni; Android
// System.loadLibrary instead of the desktop LibraryLoader. See THIRD_PARTY_NOTICES.md.
package com.k2fsa.sherpa.onnx;

public final class OfflineTtsModelConfig {
    public OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
    public OfflineTtsMatchaModelConfig matcha = new OfflineTtsMatchaModelConfig();
    public OfflineTtsKokoroModelConfig kokoro = new OfflineTtsKokoroModelConfig();
    public OfflineTtsZipVoiceModelConfig zipvoice = new OfflineTtsZipVoiceModelConfig();
    public OfflineTtsKittenModelConfig kitten = new OfflineTtsKittenModelConfig();
    public OfflineTtsPocketModelConfig pocket = new OfflineTtsPocketModelConfig();
    public OfflineTtsSupertonicModelConfig supertonic = new OfflineTtsSupertonicModelConfig();
    public int numThreads = 1;
    /** Upstream default is true; AniSub keeps native debug logging off (no text in logs). */
    public boolean debug = false;
    public String provider = "cpu";
}
