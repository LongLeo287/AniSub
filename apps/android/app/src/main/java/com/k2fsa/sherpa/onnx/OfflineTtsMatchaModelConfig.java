// Derived from sherpa-onnx v1.13.8 java-api (Copyright 2024 Xiaomi Corporation, Apache-2.0).
// Modified for AniSub: trimmed to the JNI field layout read by libsherpa-onnx-jni; Android
// System.loadLibrary instead of the desktop LibraryLoader. See THIRD_PARTY_NOTICES.md.
package com.k2fsa.sherpa.onnx;

public final class OfflineTtsMatchaModelConfig {
    public String acousticModel = "", vocoder = "", lexicon = "", tokens = "", dataDir = "";
    public float noiseScale = 1.0f, lengthScale = 1.0f;
}
