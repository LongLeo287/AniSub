package com.anisub.runtime.voice;

import com.anisub.runtime.models.LoadLease;
import com.anisub.runtime.models.ModelStore;
import com.anisub.runtime.models.ModelVersion;
import com.anisub.runtime.models.RemovalResult;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Voice-pack lifecycle: NONE -> DOWNLOADING -> VERIFYING -> READY, or ERROR.
 * One download at a time, explicit consent, per-file SHA-256 verification, atomic install
 * through {@link ModelStore} (which re-verifies), smoke test, then promotion. A failed or
 * cancelled update keeps the previously installed version usable. No URL, path or text is
 * reported to callers; only state, sizes and error codes.
 *
 * Threading: status()/installed() read a cached registry snapshot and never block on the store
 * (whose methods may hash ~64 MB under its lock). Store mutations run on the download worker or
 * the engine loader thread and refresh the snapshot afterwards.
 */
public final class VoicePackManager {
    public enum State { NONE, DOWNLOADING, VERIFYING, READY, ERROR }
    /** Error codes; the UI maps them to Vietnamese text. */
    public static final String E_NO_SPACE = "NO_SPACE", E_NETWORK = "NETWORK", E_CORRUPT = "CORRUPT",
            E_CANCELLED = "CANCELLED", E_INCOMPATIBLE = "INCOMPATIBLE", E_STORAGE = "STORAGE", E_IN_USE = "IN_USE";
    public static final int MAX_ATTEMPTS = 3; // first try + 2 retries per file
    static final long PROGRESS_STEP = 512 << 10;

    public interface Listener { void changed(Status status); }
    /** Optional post-install engine smoke test; returns null on success or an error code. */
    public interface SmokeTest { String run(File packDirectory, VoiceCatalog.Pack pack); }

    /** Immutable status snapshot, safe to send to clients. */
    public static final class Status {
        public final State state; public final VoiceCatalog.Pack pack; public final String installedVersion;
        public final long doneBytes, totalBytes; public final String error; public final boolean updateAvailable;
        public Status(State state, VoiceCatalog.Pack pack, String installedVersion, long doneBytes, long totalBytes, String error, boolean updateAvailable) {
            this.state = state; this.pack = pack; this.installedVersion = installedVersion; this.doneBytes = doneBytes;
            this.totalBytes = totalBytes; this.error = error; this.updateAvailable = updateAvailable;
        }
        public boolean ready() { return state == State.READY; }
    }

    private final ModelStore store;
    private final VoiceCatalog catalog;
    private final String packId;
    private final HttpSource http;
    private final SmokeTest smoke;
    private final Listener listener;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "anisub-voice-download"); t.setDaemon(true); return t; });
    private final Object lock = new Object();
    private volatile List<ModelVersion> snapshot = Collections.emptyList();
    private State state = State.NONE;
    private long done, total, lastPublished;
    private String error;
    private volatile boolean cancelRequested, keepPreviousUntilRestart;
    private volatile HttpSource.Response current;
    private boolean busy;

    /** Manager of the catalog's default (Vietnamese, minor 1) pack. */
    public VoicePackManager(ModelStore store, VoiceCatalog catalog, HttpSource http, SmokeTest smoke, Listener listener) {
        this(store, catalog, catalog == null || catalog.defaultPack() == null ? null : catalog.defaultPack().id, http, smoke, listener);
    }

    /** Manager of one catalog pack ({@code packId}); several managers may share one store. */
    public VoicePackManager(ModelStore store, VoiceCatalog catalog, String packId, HttpSource http, SmokeTest smoke, Listener listener) {
        if (store == null || catalog == null || http == null || listener == null) throw new IllegalArgumentException("ports");
        this.store = store; this.catalog = catalog; this.http = http; this.smoke = smoke; this.listener = listener;
        this.packId = packId;
        refresh();
        cleanupSuperseded();
        synchronized (lock) {
            state = installed() != null ? State.READY : corruptVersions().isEmpty() ? State.NONE : State.ERROR;
            if (state == State.ERROR) error = E_CORRUPT;
        }
    }

    public VoiceCatalog catalog() { return catalog; }
    /** The catalog pack this manager installs (null when the catalog lacks it). */
    public VoiceCatalog.Pack pack() { return packId == null ? null : catalog.find(packId); }
    /** True while a download/verification of this pack runs. */
    public boolean busy() { synchronized (lock) { return busy; } }

    /**
     * When the native engine has already used a pack in this process, keep the previous version
     * on disk after an update until the next process start (espeak-ng keeps its first data path).
     */
    public void setKeepPreviousUntilRestart(boolean keep) { keepPreviousUntilRestart = keep; }

    public Status status() {
        synchronized (lock) {
            VoiceCatalog.Pack pack = pack();
            ModelVersion v = installed();
            String installedVersion = v == null ? null : v.manifest().version;
            boolean update = pack != null && installedVersion != null && !installedVersion.equals(pack.version);
            long size = pack == null ? 0 : pack.totalBytes;
            return new Status(state, pack, installedVersion, done, busy ? total : size, error, update);
        }
    }

    /** Installed, registry-verified version of the catalog's default pack id (any version). */
    public ModelVersion installed() {
        VoiceCatalog.Pack pack = pack();
        if (pack == null) return null;
        ModelVersion best = null;
        for (ModelVersion v : snapshot) {
            if (!v.manifest().id.equals(pack.id) || v.state() == ModelVersion.State.CORRUPT) continue;
            if (v.manifest().version.equals(pack.version)) return v;
            best = v;
        }
        return best;
    }

    private List<ModelVersion> corruptVersions() {
        VoiceCatalog.Pack pack = pack();
        List<ModelVersion> out = new ArrayList<>();
        if (pack == null) return out;
        for (ModelVersion v : snapshot) if (v.manifest().id.equals(pack.id) && v.state() == ModelVersion.State.CORRUPT) out.add(v);
        return out;
    }

    private void refresh() { snapshot = store.registry().versions(); }

    /** Startup: when the catalog version is installed, older versions of the same pack are surplus. */
    private void cleanupSuperseded() {
        VoiceCatalog.Pack pack = pack();
        ModelVersion current = installed();
        if (pack == null || current == null || !current.manifest().version.equals(pack.version)) return;
        for (ModelVersion v : snapshot) {
            if (v.manifest().id.equals(pack.id) && !v.identity().equals(current.identity())) {
                try { store.remove(v); } catch (IOException ignored) { /* retried next start */ }
            }
        }
        refresh();
    }

    /** Starts the single allowed download. Requires explicit consent; returns false when busy. */
    public boolean download(boolean consent) {
        VoiceCatalog.Pack pack = pack();
        if (!consent || pack == null) return false;
        synchronized (lock) {
            if (busy) return false;
            busy = true; cancelRequested = false; done = 0; lastPublished = 0; total = pack.totalBytes; error = null; state = State.DOWNLOADING;
        }
        publish();
        worker.execute(() -> run(pack));
        return true;
    }

    /** Cancels promptly: also closes the in-flight response so a stalled read cannot delay it. */
    public void cancel() {
        cancelRequested = true;
        HttpSource.Response r = current;
        if (r != null) r.close();
    }

    /** Removes the installed (or corrupt) pack unless a speech session holds its lease. Not on the main thread. */
    public String delete() {
        synchronized (lock) { if (busy) return E_IN_USE; }
        List<ModelVersion> targets = new ArrayList<>(corruptVersions());
        ModelVersion v = installed();
        if (v != null) targets.add(v);
        try {
            for (ModelVersion t : targets) {
                RemovalResult r = store.remove(t);
                if (r == RemovalResult.MODEL_IN_USE) { refresh(); return E_IN_USE; }
            }
        } catch (IOException e) { refresh(); return E_STORAGE; }
        refresh();
        synchronized (lock) { state = installed() != null ? State.READY : State.NONE; error = null; }
        publish();
        return null;
    }

    /** Re-verifies every asset digest and pins the pack for native load. Caller closes the lease. Not on the main thread. */
    public LoadLease acquire() throws IOException {
        ModelVersion v = installed();
        if (v == null) throw new IOException("VOICE_PACK_MISSING");
        try { return store.acquire(v); }
        catch (ModelStore.StoreException e) {
            if ("MODEL_CORRUPT".equals(e.error().name())) {
                refresh();
                synchronized (lock) { state = installed() != null ? State.READY : State.ERROR; error = E_CORRUPT; }
                publish();
            }
            throw e;
        }
    }

    public void shutdown() { cancel(); worker.shutdownNow(); }

    // --------------------------------------------------------------------------------------
    private void run(VoiceCatalog.Pack pack) {
        File stage = null;
        String failure = null;
        try {
            long reserve = pack.totalBytes * 2; // staged copy may coexist with the previous version
            if (!store.canReserve(Math.min(reserve, pack.totalBytes + (64L << 20)))) { failure = E_NO_SPACE; return; }
            stage = store.createStage();
            for (VoiceCatalog.PackFile f : pack.files) {
                if (cancelRequested) { failure = E_CANCELLED; return; }
                failure = fetch(stage, f);
                if (failure != null) return;
            }
            synchronized (lock) { state = State.VERIFYING; }
            publish();
            // A version found corrupt earlier stays registered (state CORRUPT); remove it so the
            // fresh download of the same id/version can be installed.
            for (ModelVersion bad : corruptVersions()) {
                try { store.remove(bad); } catch (IOException ignored) { }
            }
            refresh();
            ModelVersion previous = installed();
            ModelVersion version;
            try { version = store.install(stage, pack.manifest()); stage = null; }
            catch (ModelStore.StoreException e) {
                String code = e.error().name();
                failure = "BACKPRESSURE".equals(code) ? E_NO_SPACE : "MODEL_IN_USE".equals(code) ? E_IN_USE : E_CORRUPT;
                return;
            }
            refresh();
            if (smoke != null) {
                String smokeError = smoke.run(version.directory(), pack);
                if (smokeError != null) {
                    try { store.remove(version); } catch (IOException ignored) { }
                    failure = E_INCOMPATIBLE; return;
                }
            }
            if (previous != null && !previous.identity().equals(version.identity()) && !keepPreviousUntilRestart) {
                try { store.remove(previous); } catch (IOException ignored) { /* removed at next start */ }
            }
        } catch (IOException | RuntimeException e) {
            failure = E_STORAGE;
        } finally {
            if (stage != null) try { store.discardStage(stage); } catch (IOException ignored) { }
            current = null;
            refresh();
            synchronized (lock) {
                busy = false;
                boolean usable = installed() != null;
                if (failure == null) { state = State.READY; error = null; }
                else if (E_CANCELLED.equals(failure)) { state = usable ? State.READY : State.NONE; error = null; }
                else { state = usable ? State.READY : State.ERROR; error = failure; }
            }
            publish();
        }
    }

    /** Downloads one file with bounded retries/resume and verifies size + SHA-256 before rename. */
    private String fetch(File stage, VoiceCatalog.PackFile f) throws IOException {
        File target = new File(stage, f.path), part = new File(stage, f.path + ".part");
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) return E_STORAGE;
        long baseDone; synchronized (lock) { baseDone = done; }
        String last = E_NETWORK;
        for (String url : f.urls) {
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                if (cancelRequested) return E_CANCELLED;
                long have = part.isFile() ? part.length() : 0;
                if (have > f.bytes) { delete(part); have = 0; }
                if (have < f.bytes) {
                    String result = transfer(url, part, have, f.bytes, baseDone);
                    if (result != null) {
                        if (E_CANCELLED.equals(result) || E_STORAGE.equals(result)) return result;
                        last = result;
                        if (E_CORRUPT.equals(result)) { delete(part); break; } // this URL serves the wrong file
                        continue; // network: retry, resuming from the partial file
                    }
                }
                // Complete length (possibly already complete before the request): verify, never trust.
                if (part.length() == f.bytes && sha256(part).equals(f.sha256)) {
                    delete(target);
                    if (!part.renameTo(target)) return E_STORAGE;
                    synchronized (lock) { done = baseDone + f.bytes; }
                    return null;
                }
                // Wrong bytes: never resume from a corrupt prefix.
                delete(part); last = E_CORRUPT;
            }
        }
        delete(part);
        return last;
    }

    /** One HTTP attempt. Returns null when the response body was fully read. */
    private String transfer(String url, File part, long have, long expected, long baseDone) {
        HttpSource.Response r;
        try { r = http.open(url, have); } catch (IOException e) { return E_NETWORK; }
        current = r;
        try {
            if (cancelRequested) return E_CANCELLED;
            if (have > 0 && !r.resumed) { delete(part); have = 0; }
            if (r.length >= 0 && have + r.length != expected) { delete(part); return E_CORRUPT; }
            OutputStream out;
            try { out = new FileOutputStream(part, have > 0); } catch (IOException e) { return E_STORAGE; }
            try {
                byte[] buf = new byte[64 * 1024]; long got = have;
                while (true) {
                    int n;
                    try { n = r.body.read(buf); } catch (IOException e) { return cancelRequested ? E_CANCELLED : E_NETWORK; }
                    if (n == -1) break;
                    if (cancelRequested) return E_CANCELLED;
                    if (got + n > expected) return E_CORRUPT;
                    try { out.write(buf, 0, n); } catch (IOException e) { return E_STORAGE; } // disk full is not a network error
                    got += n;
                    progress(baseDone + got);
                }
                return null;
            } finally {
                try { out.close(); } catch (IOException ignored) { }
            }
        } finally {
            current = null;
            r.close();
        }
    }

    private void progress(long bytes) {
        boolean notify;
        synchronized (lock) {
            done = bytes;
            notify = bytes - lastPublished >= PROGRESS_STEP || bytes == total;
            if (notify) lastPublished = bytes;
        }
        if (notify) publish();
    }

    private void publish() { listener.changed(status()); }

    private static void delete(File f) { if (f.exists() && !f.delete()) f.deleteOnExit(); }

    static String sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024]; int n;
            while ((n = in.read(buf)) != -1) d.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : d.digest()) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException("SHA-256"); }
    }

    /** Human-readable size for the consent dialog, e.g. "64,0 MB". */
    public static String formatBytes(long bytes) {
        return String.format(Locale.forLanguageTag("vi-VN"), "%.1f MB", bytes / 1_000_000.0);
    }
}
