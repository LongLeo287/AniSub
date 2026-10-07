package com.anisub.runtime.translate;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import com.anisub.runtime.Capabilities;
import com.google.mlkit.common.MlKit;
import com.google.mlkit.common.MlKitException;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import java.io.File;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * On-device translation with Google ML Kit Translate (closed source, accepted by the owner
 * 07-10-2026) plus ML Kit language identification (bundled model) for "und" cues.
 *
 * <ul>
 * <li>Models (about 30 MB download per language, English built in) are fetched by ML Kit from Google
 *     (dl.google.com) through Android's system DownloadManager: Google Play services are not needed.
 *     A box without an enabled download provider reports NO_DOWNLOAD_MANAGER.</li>
 * <li>Threading: ML Kit is initialised lazily on the first translate/identify/download/refresh, on
 *     the background {@code work} thread under {@code initLock}; model state lives under
 *     {@code stateLock}, so {@link #state}/{@link #models} never wait for ML Kit. Translator and
 *     identifier clients are created and called on {@code work} (background priority); results are
 *     delivered on the main looper. Translate/identify have their own timeouts.</li>
 * <li>Subtitle text is never logged.</li>
 * </ul>
 */
public final class MlKitTranslation implements TranslationScheduler.Translator {
    /** ML Kit documents "about 30 MB" per translation model. */
    public static final long APPROX_MODEL_BYTES = 30_000_000L;
    /** Measured on the AniBox_P650 emulator 07-10-2026: en_vi 45.9 MB, en_ja 63.6 MB once installed. */
    public static final long APPROX_INSTALLED_BYTES = 65_000_000L;
    public static final long TRANSLATE_TIMEOUT_MS = TranslationScheduler.TIMEOUT_MS, IDENTIFY_TIMEOUT_MS = 10_000;
    public static final String NO_DOWNLOAD_MANAGER = "NO_DOWNLOAD_MANAGER", NATIVE_UNAVAILABLE = "NATIVE_UNAVAILABLE";
    private static final String PREF_MODELS = "mlkitModels";

    public interface IdentifyCallback { void done(String language, boolean assumed); }
    public interface Done { void done(boolean ok); }

    private final Context app;
    private final Runnable changed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run(); }, "anisub-translate");
        t.setDaemon(true); return t;
    });
    private final Object initLock = new Object(), stateLock = new Object();
    private final Set<String> downloaded = new HashSet<>(), downloading = new HashSet<>();
    private final String reason;
    private final SharedPreferences prefs;
    private volatile boolean initialised, nativeFailed, detectFailed;
    private String lastDownloadError;
    // work thread only:
    private Translator client;
    private String clientPair;
    private LanguageIdentifier identifier;

    public MlKitTranslation(Context context, Runnable changed) {
        app = context.getApplicationContext();
        this.changed = changed;
        reason = downloadProblem(app);
        // Last known installed models (only AniSub downloads/deletes them): no ML Kit call at start.
        prefs = app.getSharedPreferences("anisub", Context.MODE_PRIVATE);
        for (String l : prefs.getString(PREF_MODELS, "").split(",")) if (LanguageTags.translatable(l)) downloaded.add(l);
    }

    /** Null when the system DownloadManager (the provider ML Kit downloads models with) is usable. */
    static String downloadProblem(Context app) {
        try {
            if (app.getSystemService(Context.DOWNLOAD_SERVICE) == null) return NO_DOWNLOAD_MANAGER;
            PackageManager pm = app.getPackageManager();
            if (pm.resolveContentProvider("downloads", 0) == null) return NO_DOWNLOAD_MANAGER;
            return null;
        } catch (RuntimeException e) {
            return NO_DOWNLOAD_MANAGER;
        }
    }

    /** Work thread only. */
    private void initialise() {
        if (initialised) return;
        synchronized (initLock) {
            if (initialised) return;
            try { MlKit.initialize(app); } catch (IllegalStateException alreadyInitialised) { /* fine */ }
            catch (Throwable t) { nativeFailed = true; }
            initialised = true;
        }
    }

    public boolean available() { return reason == null && !nativeFailed; }
    public String unavailableReason() { return reason != null ? reason : nativeFailed ? NATIVE_UNAVAILABLE : null; }
    public boolean detectAvailable() { return available() && !detectFailed; }
    public String lastDownloadError() { synchronized (stateLock) { return lastDownloadError; } }

    /** ready / missing / downloading ("en" is built in). Never blocks on ML Kit. */
    public String state(String lang) {
        if (LanguageTags.EN.equals(lang)) return Capabilities.READY;
        synchronized (stateLock) {
            if (downloading.contains(lang)) return Capabilities.DOWNLOADING;
            return downloaded.contains(lang) ? Capabilities.READY : Capabilities.MISSING;
        }
    }
    public boolean modelReady(String lang) { return Capabilities.READY.equals(state(lang)); }
    public boolean busy() { synchronized (stateLock) { return !downloading.isEmpty(); } }
    public boolean downloading(String lang) { synchronized (stateLock) { return downloading.contains(lang); } }

    /** The offered languages plus any other installed model, with their states. */
    public Map<String, String> models() {
        Map<String, String> out = new LinkedHashMap<>();
        Set<String> extra;
        synchronized (stateLock) { extra = new HashSet<>(downloaded); extra.addAll(downloading); }
        for (String l : LanguageTags.OFFERED) out.put(l, state(l));
        for (String l : extra) if (!out.containsKey(l)) out.put(l, state(l));
        return out;
    }

    public Capabilities.TranslateInfo info() {
        return new Capabilities.TranslateInfo(available(), unavailableReason(), detectAvailable(), models());
    }

    private void persist() {
        StringBuilder b = new StringBuilder();
        synchronized (stateLock) { for (String l : downloaded) { if (b.length() > 0) b.append(','); b.append(l); } }
        prefs.edit().putString(PREF_MODELS, b.toString()).apply();
    }

    /** Re-reads the installed models from ML Kit (asynchronous). */
    public void refresh() {
        if (!available()) return;
        work.execute(() -> {
            initialise();
            if (nativeFailed) { main.post(changed); return; }
            try {
                RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel.class).addOnCompleteListener(main::post, task -> {
                    if (task.isSuccessful() && task.getResult() != null) {
                        synchronized (stateLock) {
                            downloaded.clear();
                            for (TranslateRemoteModel m : task.getResult()) downloaded.add(m.getLanguage());
                        }
                        persist();
                    }
                    changed.run();
                });
            } catch (RuntimeException e) { main.post(changed); }
        });
    }

    /**
     * Downloads one model (ML Kit -> system DownloadManager). The caller decides whether this needs the
     * user's consent (settings dialog) or is automatic (the default Vietnamese model at first run, a
     * source model an OPEN needs while "Tự tải gói dịch khi cần" is on). One at a time. Main thread.
     */
    public boolean download(String lang, Done done) {
        if (!available() || !LanguageTags.translatable(lang) || LanguageTags.EN.equals(lang)) return false;
        synchronized (stateLock) {
            if (!downloading.isEmpty() || downloaded.contains(lang)) return false;
            downloading.add(lang); lastDownloadError = null;
        }
        changed.run();
        work.execute(() -> {
            initialise();
            try {
                RemoteModelManager.getInstance().download(new TranslateRemoteModel.Builder(lang).build(), new DownloadConditions.Builder().build())
                        .addOnCompleteListener(main::post, task -> {
                            synchronized (stateLock) {
                                downloading.remove(lang);
                                if (task.isSuccessful()) downloaded.add(lang); else lastDownloadError = errorName(task.getException());
                            }
                            persist();
                            changed.run();
                            if (done != null) done.done(task.isSuccessful());
                        });
            } catch (RuntimeException e) {
                main.post(() -> {
                    synchronized (stateLock) { downloading.remove(lang); lastDownloadError = "FAILED"; }
                    changed.run();
                    if (done != null) done.done(false);
                });
            }
        });
        return true;
    }

    /** Deletes one model, including the files ML Kit leaves behind (asynchronous). Main thread. */
    public void delete(String lang, Done done) {
        if (!available() || LanguageTags.EN.equals(lang)) { if (done != null) done.done(false); return; }
        work.execute(() -> {
            initialise();
            closeClientOnWork();
            try {
                RemoteModelManager.getInstance().deleteDownloadedModel(new TranslateRemoteModel.Builder(lang).build())
                        .addOnCompleteListener(work, task -> {
                            // ML Kit 17.0.3 reports the model gone but leaves part of its files (35 MB of
                            // en_vi + en_ja seen on the emulator): free the space the user asked to free.
                            removeLeftovers(lang);
                            main.post(() -> {
                                synchronized (stateLock) { downloaded.remove(lang); }
                                persist();
                                changed.run();
                                if (done != null) done.done(task.isSuccessful());
                            });
                        });
            } catch (RuntimeException e) { main.post(() -> { if (done != null) done.done(false); }); }
        });
    }

    /** Bytes ML Kit keeps for {@code lang} (0 when none). Not on the main thread. */
    public long installedBytes(String lang) { return treeBytes(new File(modelRoot(), "en_" + lang), 0); }
    private File modelRoot() { return new File(app.getNoBackupFilesDir(), "com.google.mlkit.translate.models"); }

    private void removeLeftovers(String lang) {
        if (!LanguageTags.translatable(lang) || LanguageTags.EN.equals(lang)) return;
        deleteTree(new File(modelRoot(), "en_" + lang), 0);
    }

    private static long treeBytes(File f, int depth) {
        if (depth > 8 || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long n = 0; File[] c = f.listFiles();
        if (c != null) for (File x : c) n += treeBytes(x, depth + 1);
        return n;
    }

    private static void deleteTree(File f, int depth) {
        if (depth > 8 || !f.exists()) return;
        File[] children = f.isDirectory() ? f.listFiles() : null;
        if (children != null) for (File c : children) deleteTree(c, depth + 1);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /**
     * TranslationScheduler port: any thread; the client is created and called on {@code work}, the
     * answer arrives on the main looper. The scheduler applies the timeout.
     */
    @Override public void translate(String text, String from, String to, TranslationScheduler.Callback done) {
        if (!available()) { done.done(null, TranslationScheduler.E_UNAVAILABLE); return; }
        if (!modelReady(from) || !modelReady(to)) { done.done(null, TranslationScheduler.E_MODEL_MISSING); return; }
        work.execute(() -> {
            initialise();
            Translator t;
            try {
                String pair = from + ">" + to;
                if (client == null || !pair.equals(clientPair)) {
                    closeClientOnWork();
                    client = Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).setExecutor(work).build());
                    clientPair = pair;
                }
                t = client;
            } catch (Throwable e) {
                if (e instanceof LinkageError) nativeFailed = true;
                final String code = nativeFailed ? TranslationScheduler.E_UNAVAILABLE : TranslationScheduler.E_FAILED;
                main.post(() -> { if (nativeFailed) changed.run(); done.done(null, code); });
                return;
            }
            t.translate(text).addOnCompleteListener(main::post, task -> {
                if (task.isSuccessful() && task.getResult() != null) { done.done(task.getResult(), null); return; }
                done.done(null, failure(task.getException()));
            });
        });
    }

    private String failure(Exception e) {
        Throwable c = e;
        while (c != null) {
            if (c instanceof LinkageError) { nativeFailed = true; changed.run(); return TranslationScheduler.E_UNAVAILABLE; }
            if (c instanceof MlKitException && ((MlKitException) c).getErrorCode() == MlKitException.NOT_FOUND) {
                refresh(); return TranslationScheduler.E_MODEL_MISSING;
            }
            c = c.getCause();
        }
        return TranslationScheduler.E_FAILED;
    }

    private static String errorName(Exception e) {
        if (e instanceof MlKitException) {
            int code = ((MlKitException) e).getErrorCode();
            if (code == MlKitException.NOT_ENOUGH_SPACE) return "NO_SPACE";
            if (code == MlKitException.NETWORK_ISSUE || code == MlKitException.UNAVAILABLE) return "NETWORK";
        }
        return "FAILED";
    }

    /**
     * Identifies the language of a few cues (bundled language-id) on {@code work}. "und", romanised
     * results, failures and no answer within {@code timeoutMs} fall back to English with
     * {@code assumed=true}. The callback runs once, on the main looper.
     */
    public void identify(String text, long timeoutMs, IdentifyCallback cb) {
        final AtomicBoolean answered = new AtomicBoolean();
        final IdentifyCallback once = (lang, assumed) -> { if (answered.compareAndSet(false, true)) cb.done(lang, assumed); };
        if (!detectAvailable() || text == null || text.trim().isEmpty()) { main.post(() -> once.done(LanguageTags.EN, true)); return; }
        main.postDelayed(() -> once.done(LanguageTags.EN, true), timeoutMs);
        work.execute(() -> {
            initialise();
            try {
                if (identifier == null) identifier = LanguageIdentification.getClient(
                        new LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.5f).setExecutor(work).build());
                identifier.identifyLanguage(text).addOnCompleteListener(main::post, task -> {
                    String tag = task.isSuccessful() ? task.getResult() : null;
                    if (!task.isSuccessful() && task.getException() != null && task.getException().getCause() instanceof LinkageError) detectFailed = true;
                    if (tag == null || LanguageTags.UND.equals(tag) || tag.contains("-Latn") || !LanguageTags.valid(tag)) { once.done(LanguageTags.EN, true); return; }
                    once.done(LanguageTags.base(tag), false);
                });
            } catch (Throwable e) {
                detectFailed = true;
                main.post(() -> once.done(LanguageTags.EN, true));
            }
        });
    }

    /** Frees the language-id model (detection done). */
    public void closeIdentifier() {
        work.execute(() -> { if (identifier != null) { try { identifier.close(); } catch (RuntimeException ignored) { } identifier = null; } });
    }

    /** Frees translator and identifier native memory (session ended / no translation needed). */
    public void releaseClients() {
        work.execute(this::closeClientOnWork);
        closeIdentifier();
    }

    private void closeClientOnWork() {
        if (client != null) { try { client.close(); } catch (RuntimeException ignored) { } }
        client = null; clientPair = null;
    }
}
