package com.anisub.runtime.voice;

/**
 * Owner 07-10-2026 ("Tự tải ngay sau khi cài"): the default Vietnamese voice (and, on the first run,
 * the Vietnamese translation model) download automatically, without a consent prompt, as soon as
 * AniSub runs. A failed attempt is retried with backoff (30 s, 2 min, 10 min, 30 min, then hourly);
 * success resets it. One target per instance. Pure: the clock/timer and the target are ports, the
 * caller runs everything on one thread (the main looper on the device).
 */
public final class AutoDownloader {
    public static final long[] BACKOFF_MS = {30_000, 120_000, 600_000, 1_800_000, 3_600_000};

    public interface Timer { void schedule(Runnable task, long delayMs); }
    public interface Target {
        /** The thing is installed already (nothing to do). */
        boolean installed();
        /** A download of it is running (from the user or from us). */
        boolean busy();
        /** Starts one download; false when it could not start (it then counts as a failed attempt). */
        boolean start();
    }

    private final Target target;
    private final Timer timer;
    private int attempts;
    private boolean waiting, enabled = true;
    private long generation;

    public AutoDownloader(Target target, Timer timer) { this.target = target; this.timer = timer; }

    /** Checks now: starts a download when missing and idle. Idempotent. */
    public void kick() {
        if (!enabled || waiting || target.installed() || target.busy()) return;
        if (!target.start()) finished(false);
    }

    /** Report the end of a download (ours or the user's). */
    public void finished(boolean ok) {
        if (ok || target.installed()) { attempts = 0; return; }
        if (!enabled || waiting) return;
        long delay = BACKOFF_MS[Math.min(attempts, BACKOFF_MS.length - 1)];
        attempts++;
        waiting = true;
        final long gen = ++generation;
        timer.schedule(() -> { if (gen != generation) return; waiting = false; kick(); }, delay);
    }

    /** Stops retrying (e.g. the user cancelled or the toggle is off); {@link #resume} restarts. */
    public void pause() { enabled = false; waiting = false; generation++; }
    public void resume() { enabled = true; attempts = 0; kick(); }
    public int attempts() { return attempts; }
    public boolean retryPending() { return waiting; }
    /** Delay of the next retry if this attempt fails. */
    public long nextDelayMs() { return BACKOFF_MS[Math.min(attempts, BACKOFF_MS.length - 1)]; }
}
