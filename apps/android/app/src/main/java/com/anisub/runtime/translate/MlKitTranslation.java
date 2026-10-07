package com.anisub.runtime.translate;

import android.app.DownloadManager;
import android.content.Context;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * On-device translation with Google ML Kit Translate (closed source, accepted by the owner
 * 07-10-2026) plus ML Kit language identification (bundled model) for "und" cues.
 *
 * <ul>
 * <li>Models (about 30 MB per language, English built in) are downloaded ONLY by {@link #download}
 *     after the user's consent in SettingsActivity; ML Kit fetches them from Google's servers
 *     (dl.google.com) through Android's system DownloadManager. Google Play services are NOT needed
 *     for that (checked in the ML Kit 18.11 common runtime: RemoteModelDownloadManager uses
 *     android.app.DownloadManager); a box without an enabled download provider reports
 *     TRANSLATE_UNAVAILABLE reason NO_DOWNLOAD_MANAGER.</li>
 * <li>Translation itself runs on the device, on one background thread at background priority;
 *     ML Kit tasks complete on the main looper. Nothing here blocks the main thread.</li>
 * <li>ML Kit is initialised lazily (its start-up provider and telemetry backend are removed from the
 *     manifest). Subtitle text is never logged.</li>
 * </ul>
 */
public final class MlKitTranslation implements TranslationScheduler.Translator {
    /** ML Kit documents "about 30 MB" per translation model; shown in the consent dialog. */
    public static final long APPROX_MODEL_BYTES = 30_000_000L;
    /** Measured on the AniBox_P650 emulator 07-10-2026: en_vi 45.9 MB, en_ja 63.6 MB once installed. */
    public static final long APPROX_INSTALLED_BYTES = 65_000_000L;
    public static final String NO_DOWNLOAD_MANAGER = "NO_DOWNLOAD_MANAGER", NATIVE_UNAVAILABLE = "NATIVE_UNAVAILABLE";

    public interface IdentifyCallback { void done(String language, boolean assumed); }
    public interface Done { void done(boolean ok); }

    private final Context app;
    private final Runnable changed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run(); }, "anisub-translate");
        t.setDaemon(true); return t;
    });
    private final Set<String> downloaded = new HashSet<>(), downloading = new HashSet<>();
    private final String reason;
    private volatile boolean initialised, nativeFailed, detectFailed, known;
    private Translator client;
    private String clientPair;
    private LanguageIdentifier identifier;
    private String lastDownloadError;
    private final android.content.SharedPreferences prefs;
    private static final String PREF_MODELS = "mlkitModels";

    /** Persists the installed-model set (main thread or under this lock). */
    private synchronized void persist() {
        StringBuilder b = new StringBuilder();
        for (String l : downloaded) { if (b.length() > 0) b.append(','); b.append(l); }
        prefs.edit().putString(PREF_MODELS, b.toString()).apply();
    }

    public MlKitTranslation(Context context, Runnable changed) {
        app = context.getApplicationContext();
        this.changed = changed;
        reason = downloadProblem(app);
        // Last known installed models (only AniSub downloads/deletes them): OPEN right after process
        // start must not report a model missing while ML Kit's own query is still running.
        prefs = app.getSharedPreferences("anisub", Context.MODE_PRIVATE);
        for (String l : prefs.getString(PREF_MODELS, "").split(",")) if (LanguageTags.translatable(l)) downloaded.add(l);
        if (reason == null) work.execute(() -> { initialise(); main.post(this::refresh); });
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

    private void initialise() {
        if (initialised) return;
        synchronized (this) {
            if (initialised) return;
            try { MlKit.initialize(app); } catch (IllegalStateException alreadyInitialised) { /* fine */ }
            catch (Throwable t) { nativeFailed = true; }
            initialised = true;
        }
    }

    public boolean available() { return reason == null && !nativeFailed; }
    public String unavailableReason() { return reason != null ? reason : nativeFailed ? NATIVE_UNAVAILABLE : null; }
    public boolean detectAvailable() { return available() && !detectFailed; }
    /** False until the first downloaded-models query answered. */
    public boolean known() { return known; }
    public synchronized String lastDownloadError() { return lastDownloadError; }

    /** ready / missing / downloading ("en" is built in). Main thread. */
    public synchronized String state(String lang) {
        if (LanguageTags.EN.equals(lang)) return Capabilities.READY;
        if (downloading.contains(lang)) return Capabilities.DOWNLOADING;
        return downloaded.contains(lang) ? Capabilities.READY : Capabilities.MISSING;
    }
    public boolean modelReady(String lang) { return Capabilities.READY.equals(state(lang)); }
    public synchronized boolean busy() { return !downloading.isEmpty(); }

    /** The offered languages plus any other installed model, with their states. */
    public synchronized Map<String, String> models() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String l : LanguageTags.OFFERED) out.put(l, state(l));
        for (String l : downloaded) if (!out.containsKey(l)) out.put(l, state(l));
        return out;
    }

    public Capabilities.TranslateInfo info() {
        return new Capabilities.TranslateInfo(available(), unavailableReason(), detectAvailable(), models());
    }

    /** Re-reads the installed models (asynchronous). */
    public void refresh() {
        if (!available()) return;
        work.execute(() -> {
            initialise();
            if (nativeFailed) { main.post(changed); return; }
            main.post(() -> {
                try {
                    RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel.class).addOnCompleteListener(task -> {
                        if (task.isSuccessful() && task.getResult() != null) {
                            synchronized (MlKitTranslation.this) {
                                downloaded.clear();
                                for (TranslateRemoteModel m : task.getResult()) downloaded.add(m.getLanguage());
                            }
                            persist();
                        }
                        known = true;
                        changed.run();
                    });
                } catch (RuntimeException e) {
                    known = true; changed.run();
                }
            });
        });
    }

    /** Downloads one model. Only after the user's explicit consent (settings screen). Main thread. */
    public boolean download(String lang, boolean consent, Done done) {
        if (!consent || !available() || !LanguageTags.translatable(lang) || LanguageTags.EN.equals(lang)) return false;
        synchronized (this) { if (!downloading.add(lang)) return false; lastDownloadError = null; }
        changed.run();
        work.execute(() -> {
            initialise();
            main.post(() -> {
                try {
                    RemoteModelManager.getInstance().download(new TranslateRemoteModel.Builder(lang).build(), new DownloadConditions.Builder().build())
                            .addOnCompleteListener(task -> {
                                synchronized (MlKitTranslation.this) {
                                    downloading.remove(lang);
                                    if (task.isSuccessful()) downloaded.add(lang); else lastDownloadError = errorName(task.getException());
                                }
                                persist();
                                changed.run();
                                refresh();
                                if (done != null) done.done(task.isSuccessful());
                            });
                } catch (RuntimeException e) {
                    synchronized (MlKitTranslation.this) { downloading.remove(lang); lastDownloadError = "FAILED"; }
                    changed.run();
                    if (done != null) done.done(false);
                }
            });
        });
        return true;
    }

    /** Deletes one model (asynchronous). Main thread. */
    public void delete(String lang, Done done) {
        if (!available() || LanguageTags.EN.equals(lang)) { if (done != null) done.done(false); return; }
        closeClient();
        work.execute(() -> {
            initialise();
            main.post(() -> {
                try {
                    RemoteModelManager.getInstance().deleteDownloadedModel(new TranslateRemoteModel.Builder(lang).build())
                            .addOnCompleteListener(task -> {
                                synchronized (MlKitTranslation.this) { if (task.isSuccessful()) downloaded.remove(lang); }
                                persist();
                                // ML Kit 17.0.3 reports the model gone but leaves part of its files (35 MB of
                                // en_vi + en_ja seen on the emulator): free the space the user asked to free.
                                if (task.isSuccessful()) work.execute(() -> removeLeftovers(lang));
                                changed.run();
                                refresh();
                                if (done != null) done.done(task.isSuccessful());
                            });
                } catch (RuntimeException e) { if (done != null) done.done(false); }
            });
        });
    }

    /** Deletes what ML Kit left of a deleted model (AniSub's private no-backup storage only). Worker thread. */
    private void removeLeftovers(String lang) {
        if (!LanguageTags.translatable(lang) || LanguageTags.EN.equals(lang)) return;
        java.io.File root = new java.io.File(app.getNoBackupFilesDir(), "com.google.mlkit.translate.models");
        deleteTree(new java.io.File(root, "en_" + lang), 0);
    }

    private static void deleteTree(java.io.File f, int depth) {
        if (depth > 8 || !f.exists()) return;
        java.io.File[] children = f.isDirectory() ? f.listFiles() : null;
        if (children != null) for (java.io.File c : children) deleteTree(c, depth + 1);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** TranslationScheduler port. Main thread; the callback arrives on the main looper. */
    @Override public void translate(String text, String from, String to, TranslationScheduler.Callback done) {
        if (!available()) { done.done(null, TranslationScheduler.E_UNAVAILABLE); return; }
        if (!modelReady(from) || !modelReady(to)) { done.done(null, TranslationScheduler.E_MODEL_MISSING); return; }
        initialise();
        Translator t;
        try {
            String pair = from + ">" + to;
            if (client == null || !pair.equals(clientPair)) {
                closeClient();
                client = Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).setExecutor(work).build());
                clientPair = pair;
            }
            t = client;
        } catch (Throwable e) {
            nativeFailed = e instanceof LinkageError || nativeFailed;
            done.done(null, nativeFailed ? TranslationScheduler.E_UNAVAILABLE : TranslationScheduler.E_FAILED);
            return;
        }
        t.translate(text).addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null) { done.done(task.getResult(), null); return; }
            done.done(null, failure(task.getException()));
        });
    }

    private String failure(Exception e) {
        Throwable c = e;
        while (c != null) {
            if (c instanceof LinkageError) { nativeFailed = true; main.post(changed); return TranslationScheduler.E_UNAVAILABLE; }
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
     * Identifies the language of a few cues (bundled ML Kit language-id). "und", romanised
     * results and failures fall back to English with {@code assumed=true}. Main thread.
     */
    public void identify(String text, IdentifyCallback cb) {
        if (!detectAvailable() || text == null || text.trim().isEmpty()) { cb.done(LanguageTags.EN, true); return; }
        initialise();
        try {
            if (identifier == null) identifier = LanguageIdentification.getClient(
                    new LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.5f).setExecutor(work).build());
            identifier.identifyLanguage(text).addOnCompleteListener(task -> {
                String tag = task.isSuccessful() ? task.getResult() : null;
                if (!task.isSuccessful() && task.getException() != null && task.getException().getCause() instanceof LinkageError) detectFailed = true;
                if (tag == null || LanguageTags.UND.equals(tag) || tag.contains("-Latn") || !LanguageTags.valid(tag)) { cb.done(LanguageTags.EN, true); return; }
                cb.done(LanguageTags.base(tag), false);
            });
        } catch (Throwable e) {
            detectFailed = true;
            cb.done(LanguageTags.EN, true);
        }
    }

    /** Frees the translator/identifier native memory (session ended). Main thread. */
    public void releaseClients() {
        closeClient();
        if (identifier != null) { try { identifier.close(); } catch (RuntimeException ignored) { } identifier = null; }
    }

    private void closeClient() {
        if (client != null) { try { client.close(); } catch (RuntimeException ignored) { } }
        client = null; clientPair = null;
    }
}
