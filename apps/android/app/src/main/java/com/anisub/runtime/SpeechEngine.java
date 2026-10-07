package com.anisub.runtime;

interface SpeechEngine {
    interface Listener { void started(String id); void finished(String id); void failed(String id); }
    boolean ready();
    String state();
    boolean speak(String text, String id);
    void stop();
    void release();
}
