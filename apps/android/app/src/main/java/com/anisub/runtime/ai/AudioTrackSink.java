package com.anisub.runtime.ai;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * Streaming 16-bit mono AudioTrack with media/speech attributes. Never requests audio focus:
 * AniBox owns whole-track ducking. Writes are non-blocking so stop stays bounded.
 */
public final class AudioTrackSink implements NarrationPipeline.PcmSink {
    private volatile AudioTrack track;

    @Override public synchronized void open(int sampleRate) {
        if (track != null) return;
        int min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new IllegalStateException("audio format unsupported");
        int bytes = Math.max(min, sampleRate / 4 * 2); // ~250 ms: small enough for prompt stop
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(bytes).setTransferMode(AudioTrack.MODE_STREAM).build();
        if (t.getState() != AudioTrack.STATE_INITIALIZED) { t.release(); throw new IllegalStateException("audio track"); }
        track = t;
    }
    @Override public void reset() {
        AudioTrack t = track; if (t == null) return;
        try { t.pause(); t.flush(); } catch (IllegalStateException ignored) { }
    }
    @Override public void play() { AudioTrack t = track; if (t != null) try { t.play(); } catch (IllegalStateException ignored) { } }
    @Override public int write(short[] pcm, int offset, int length) {
        AudioTrack t = track; if (t == null) return -1;
        int n = t.write(pcm, offset, length, AudioTrack.WRITE_NON_BLOCKING);
        return n < 0 ? -1 : n;
    }
    @Override public long playedFrames() {
        AudioTrack t = track; if (t == null) return 0;
        return t.getPlaybackHeadPosition() & 0xffffffffL;
    }
    @Override public void halt() { AudioTrack t = track; if (t != null) try { t.pause(); } catch (IllegalStateException ignored) { } }
    @Override public synchronized void release() {
        AudioTrack t = track; track = null;
        if (t != null) { try { t.pause(); t.flush(); } catch (IllegalStateException ignored) { } t.release(); }
    }
}
