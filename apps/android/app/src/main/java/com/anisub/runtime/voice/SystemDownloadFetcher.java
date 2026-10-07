package com.anisub.runtime.voice;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Fetches a pinned file through Android's system DownloadManager, so AniSub itself needs no INTERNET
 * permission (owner/root decision 07-10-2026, ANISUB-004 P1). The download lands in the download
 * provider's cache, is copied into AniSub's private staging file, and the provider entry is removed.
 * The caller verifies size and SHA-256 afterwards (never trusted before that), then installs
 * atomically. Blocking: call on a worker thread only. Redirects are followed by the system service,
 * so the HTTPS host allowlist applies to the catalog URL; integrity comes from the pinned SHA-256.
 */
public final class SystemDownloadFetcher implements VoicePackManager.FileFetcher {
    static final long POLL_MS = 400, STALL_MS = 120_000;
    private final Context app;
    private final String userAgent;

    public SystemDownloadFetcher(Context context, String userAgent) { app = context.getApplicationContext(); this.userAgent = userAgent; }

    @Override public String fetch(String url, File target, long expected, VoicePackManager.Progress progress, VoicePackManager.Cancelled cancelled) {
        if (!VoiceCatalog.allowedRedirect(url)) return VoicePackManager.E_NETWORK;
        DownloadManager dm = (DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return VoicePackManager.E_NETWORK;
        long id;
        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url))
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
                    .setAllowedOverMetered(true).setAllowedOverRoaming(true)
                    .setVisibleInDownloadsUi(false);
            r.addRequestHeader("User-Agent", userAgent);
            id = dm.enqueue(r);
        } catch (RuntimeException e) {
            return VoicePackManager.E_NETWORK; // SecurityException / provider disabled
        }
        try {
            long lastBytes = -1, lastChange = System.currentTimeMillis();
            while (true) {
                if (cancelled.cancelled()) return VoicePackManager.E_CANCELLED;
                int status; long bytes; int reason;
                try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
                    if (c == null || !c.moveToFirst()) return VoicePackManager.E_NETWORK; // removed underneath us
                    status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    bytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                }
                if (status == DownloadManager.STATUS_SUCCESSFUL) break;
                if (status == DownloadManager.STATUS_FAILED) {
                    return reason == DownloadManager.ERROR_INSUFFICIENT_SPACE ? VoicePackManager.E_NO_SPACE : VoicePackManager.E_NETWORK;
                }
                if (bytes > expected) return VoicePackManager.E_CORRUPT;
                if (bytes != lastBytes) { lastBytes = bytes; lastChange = System.currentTimeMillis(); if (bytes > 0) progress.progress(bytes); }
                else if (System.currentTimeMillis() - lastChange > STALL_MS) return VoicePackManager.E_NETWORK;
                try { Thread.sleep(POLL_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return VoicePackManager.E_CANCELLED; }
            }
            // Copy the provider's file into our private staging file (bounded by the expected size).
            try (ParcelFileDescriptor pfd = dm.openDownloadedFile(id);
                 InputStream in = new FileInputStream(pfd.getFileDescriptor());
                 OutputStream out = new FileOutputStream(target, false)) {
                byte[] buf = new byte[64 * 1024]; long got = 0; int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancelled.cancelled()) return VoicePackManager.E_CANCELLED;
                    got += n;
                    if (got > expected) return VoicePackManager.E_CORRUPT;
                    out.write(buf, 0, n);
                }
                progress.progress(got);
            } catch (IOException | RuntimeException e) {
                return VoicePackManager.E_STORAGE;
            }
            return null;
        } finally {
            try { dm.remove(id); } catch (RuntimeException ignored) { }
        }
    }

    /** Cancel: the polling loop sees the cancel flag within POLL_MS and removes the download. */
    @Override public void abort() { }
}
