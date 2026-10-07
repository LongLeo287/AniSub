// Derived from sherpa-onnx v1.13.8 java-api (Copyright 2024 Xiaomi Corporation, Apache-2.0).
// Modified for AniSub: trimmed to the JNI field layout read by libsherpa-onnx-jni; Android
// System.loadLibrary instead of the desktop LibraryLoader. See THIRD_PARTY_NOTICES.md.
package com.k2fsa.sherpa.onnx;

/** Thin JNI handle. Not thread-safe: AniSub drives it from exactly one inference worker. */
public final class OfflineTts {
    private static volatile boolean loaded;
    private long ptr;
    /** Loads the APK-bundled native library; never loads code from downloaded model data. */
    public static synchronized void loadLibrary() {
        if (loaded) return;
        // x86 emulator builds ship onnxruntime as a separate shared library.
        try { System.loadLibrary("onnxruntime"); } catch (UnsatisfiedLinkError ignored) { }
        System.loadLibrary("sherpa-onnx-jni");
        loaded = true;
    }
    public OfflineTts(OfflineTtsConfig config) {
        loadLibrary();
        ptr = newFromFile(config);
        if (ptr == 0) throw new IllegalArgumentException("native OfflineTts rejected config");
    }
    public int getSampleRate() { check(); return getSampleRate(ptr); }
    public int getNumSpeakers() { check(); return getNumSpeakers(ptr); }
    public GeneratedAudio generate(String text, int sid, float speed, OfflineTtsCallback callback) {
        check();
        return generateWithCallbackImpl(ptr, text, sid, speed, callback);
    }
    public void release() { if (ptr != 0) { delete(ptr); ptr = 0; } }
    private void check() { if (ptr == 0) throw new IllegalStateException("released"); }
    private native long newFromFile(OfflineTtsConfig config);
    private native void delete(long ptr);
    private native int getSampleRate(long ptr);
    private native int getNumSpeakers(long ptr);
    private native GeneratedAudio generateWithCallbackImpl(long ptr, String text, int sid, float speed, OfflineTtsCallback callback);
}
