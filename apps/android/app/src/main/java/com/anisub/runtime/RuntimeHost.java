package com.anisub.runtime;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import com.anisub.runtime.ai.AiSpeechEngine;
import com.anisub.runtime.ai.RatePolicy;
import com.anisub.runtime.ai.SherpaSynthesizer;
import com.anisub.runtime.models.IntegrityVerifier;
import com.anisub.runtime.models.ModelStore;
import com.anisub.runtime.models.StorageBudget;
import com.anisub.runtime.translate.LanguageTags;
import com.anisub.runtime.translate.MlKitTranslation;
import com.anisub.runtime.voice.AutoDownloader;
import com.anisub.runtime.voice.DebugSources;
import com.anisub.runtime.voice.EspeakData;
import com.anisub.runtime.voice.SystemDownloadFetcher;
import com.anisub.runtime.voice.VoiceCatalog;
import com.anisub.runtime.voice.VoicePackManager;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-wide composition root shared by the bound service and the settings screen:
 * pinned catalog, private model store, voice-pack manager and the single AI engine.
 */
public final class RuntimeHost {
    public static final String PREFS = "anisub";
    public static final String KEY_RATE = "defaultRate", KEY_VOICE = "voice";
    /** "Tự tải gói dịch khi cần" (default on); "firstRunModel" = the first-run vi model download finished. */
    public static final String KEY_AUTO_MODELS = "autoDownloadModels", KEY_FIRST_RUN_MODEL = "firstRunModel";
    /** Free space kept when a translation model is downloaded automatically. */
    public static final long MODEL_SPACE_BYTES = 200L << 20;
    @android.annotation.SuppressLint("StaticFieldLeak") // holds the Application context only
    private static RuntimeHost instance;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    /** Stable across resetStore(): the service/UI subscribe here, not to a specific engine instance. */
    private final CopyOnWriteArrayList<AiSpeechEngine.Listener> engineListeners = new CopyOnWriteArrayList<>();
    private final VoiceCatalog catalog;
    /** Voice pack manager per voice language ("vi" first); empty when the store is unusable. */
    private volatile Map<String, VoicePackManager> packs = Collections.emptyMap();
    private VoicePackManager voices;
    private AiSpeechEngine engine;
    private final MlKitTranslation translation;
    private final RatePolicy rates = new RatePolicy();
    private final String versionName;
    private final long versionCode;
    private volatile boolean sessionActive;
    private AutoDownloader voiceAuto, modelAuto;
    private boolean voiceWasBusy;

    public static synchronized RuntimeHost get(Context context) {
        if (instance == null) instance = new RuntimeHost(context.getApplicationContext());
        return instance;
    }

    private RuntimeHost(Context context) {
        app = context;
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        VoiceCatalog parsed;
        try { parsed = VoiceCatalog.parse(asset(context, "voice-catalog.json")); }
        catch (Exception e) { throw new IllegalStateException("bundled voice catalog invalid"); }
        catalog = parsed;
        String name = "?"; long code = 0;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            name = info.versionName;
            code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : versionCodeLegacy(info);
        } catch (PackageManager.NameNotFoundException ignored) { }
        versionName = name; versionCode = code;
        openStore();
        translation = new MlKitTranslation(context, this::changed);
        startAutoDownloads();
    }

    // ---------------------------------------------------------------- first run (owner 07-10-2026)
    /**
     * "Tự tải ngay sau khi cài": the default Vietnamese voice pack and the Vietnamese translation model
     * download automatically as soon as AniSub runs (service bind or app launch), without a consent
     * prompt; progress shows in settings and CAPABILITIES. Failures retry with backoff. The voice is
     * re-fetched whenever it is missing; the model only on the first run (later on demand).
     */
    private void startAutoDownloads() {
        voiceAuto = new AutoDownloader(new AutoDownloader.Target() {
            public boolean installed() { return voices != null && voices.installed() != null; }
            public boolean busy() { return anyPackBusy(); }
            public boolean start() { return voices != null && voices.download(true); }
        }, main::postDelayed);
        modelAuto = new AutoDownloader(new AutoDownloader.Target() {
            public boolean installed() { return prefs.getBoolean(KEY_FIRST_RUN_MODEL, false) || translation.modelReady(LanguageTags.VI); }
            public boolean busy() { return translation.busy(); }
            public boolean start() {
                return startModelDownload(LanguageTags.VI, ok -> {
                    if (ok) prefs.edit().putBoolean(KEY_FIRST_RUN_MODEL, true).apply();
                    modelAuto.finished(ok);
                });
            }
        }, main::postDelayed);
        main.post(() -> { voiceAuto.kick(); if (translation.available()) modelAuto.kick(); });
    }

    /** A pack manager reported a change: detect the end of the default pack's download. */
    private void packChanged() {
        main.post(() -> {
            boolean busy = voices != null && voices.busy();
            if (voiceWasBusy && !busy && voiceAuto != null) voiceAuto.finished(voices.installed() != null);
            voiceWasBusy = busy;
        });
    }

    /** The default voice download in progress was started automatically (no consent prompt shown). */
    public boolean autoDownloadingDefault() { return voices != null && voices.busy() && voices.installed() == null; }
    /** The user cancelled the automatic download: no retries until the next start. */
    public void pauseAutoVoice() { if (voiceAuto != null) voiceAuto.pause(); }
    public void resumeAutoVoice() { if (voiceAuto != null) voiceAuto.resume(); }

    public boolean autoDownloadModels() { return prefs.getBoolean(KEY_AUTO_MODELS, true); }
    public void setAutoDownloadModels(boolean on) { prefs.edit().putBoolean(KEY_AUTO_MODELS, on).apply(); changed(); }

    /** Starts one ML Kit model download when the engine runs, nothing else downloads and space allows. */
    public boolean startModelDownload(String lang, MlKitTranslation.Done done) {
        return canAutoDownloadModel() && translation.download(lang, done);
    }
    public boolean canAutoDownloadModel() {
        return translation.available() && !translation.busy() && app.getNoBackupFilesDir().getUsableSpace() >= MODEL_SPACE_BYTES;
    }

    @SuppressWarnings("deprecation")
    private static long versionCodeLegacy(PackageInfo info) { return info.versionCode; }

    private void openStore() {
        try {
            File root = new File(app.getFilesDir(), "voices").getCanonicalFile();
            ModelStore store = new ModelStore(root, new StorageBudget(StorageBudget.DEFAULT_QUOTA), new IntegrityVerifier(),
                    System::currentTimeMillis, point -> { });
            // Every download goes through the system DownloadManager (which requires the caller to hold INTERNET).
            VoicePackManager.FileFetcher source = DebugSources.wrap(new SystemDownloadFetcher(app, "AniSub/" + versionName), app);
            Map<String, VoicePackManager> byLanguage = new LinkedHashMap<>();
            for (String lang : LanguageTags.VOICE) {
                VoiceCatalog.Pack pack = catalog.forLanguage(lang);
                if (pack != null) byLanguage.put(lang, new VoicePackManager(store, catalog, pack.id, source, this::smokeTest, s -> { changed(); packChanged(); }));
            }
            packs = Collections.unmodifiableMap(byLanguage);
            voices = byLanguage.get(LanguageTags.VI);
            int threads = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() / 2));
            final Map<String, VoicePackManager> resolver = packs;
            engine = new AiSpeechEngine(resolver::get, rates, threads, espeakRoot());
            engine.addListener(new AiSpeechEngine.Listener() {
                public void started(String id) { for (AiSpeechEngine.Listener l : engineListeners) l.started(id); }
                public void finished(String id) { for (AiSpeechEngine.Listener l : engineListeners) l.finished(id); }
                public void failed(String id, String code) { for (AiSpeechEngine.Listener l : engineListeners) l.failed(id, code); }
                public void engineChanged(AiSpeechEngine.State state, String error) {
                    // espeak-ng keeps its first data path per process: after a native load, an
                    // update must not delete the previous pack directory until restart.
                    if (state == AiSpeechEngine.State.READY) for (VoicePackManager m : resolver.values()) m.setKeepPreviousUntilRestart(true);
                    for (AiSpeechEngine.Listener l : engineListeners) l.engineChanged(state, error);
                    changed();
                }
            });
        } catch (IOException | RuntimeException e) {
            voices = null; engine = null; packs = Collections.emptyMap(); // store unusable; settings offers a user-initiated reset
        }
    }

    /** Shared espeak-ng data directory (outside the voice store, kept across store resets). */
    private File espeakRoot() { return new File(app.getFilesDir(), "espeak-ng-data"); }

    /** Native smoke test of a freshly installed pack: load + synthesize one short phrase. */
    private String smokeTest(File dir, VoiceCatalog.Pack pack) {
        SherpaSynthesizer s = null;
        try {
            File espeak = EspeakData.prepare(espeakRoot(), dir, pack);
            s = new SherpaSynthesizer(new File(dir, pack.model), new File(dir, pack.tokens), espeak, 1, pack.voices.get(0).speakerId);
            float[] pcm = s.synthesize(AiSpeechEngine.warmUpText(pack.language), 1f, () -> false);
            return pcm.length > s.sampleRate() / 10 ? null : "INCOMPATIBLE";
        } catch (Throwable t) {
            return "INCOMPATIBLE";
        } finally { if (s != null) s.release(); }
    }

    /** The Vietnamese (minor 1) pack manager, or null when the store is unusable. */
    public VoicePackManager voices() { return voices; }
    /** The pack manager of a voice language, or null. */
    public VoicePackManager voices(String language) { return packs.get(language); }
    public MlKitTranslation translation() { return translation; }
    public AiSpeechEngine engine() { return engine; }
    public VoiceCatalog catalog() { return catalog; }
    public RatePolicy rates() { return rates; }
    public String versionName() { return versionName; }
    public long versionCode() { return versionCode; }
    public boolean sessionActive() { return sessionActive; }
    public void setSessionActive(boolean active) { sessionActive = active; changed(); }

    public float defaultRate() {
        float r = prefs.getFloat(KEY_RATE, RatePolicy.USER_DEFAULT);
        return RatePolicy.validUserRate(r) ? r : RatePolicy.USER_DEFAULT;
    }
    public void setDefaultRate(float rate) {
        if (RatePolicy.validUserRate(rate)) prefs.edit().putFloat(KEY_RATE, Math.round(rate * 100) / 100f).apply();
        changed();
    }

    public String defaultVoice() { return prefs.getString(KEY_VOICE, null); }
    public void setDefaultVoice(String id) { prefs.edit().putString(KEY_VOICE, id).apply(); if (engine != null) engine.setVoice(id); changed(); }

    public VoicePackManager.Status packStatus() { return voices == null ? null : voices.status(); }
    public VoicePackManager.Status packStatus(String language) { VoicePackManager m = packs.get(language); return m == null ? null : m.status(); }
    /** Status per voice language (null value: store unusable or no pack for it). */
    public Map<String, VoicePackManager.Status> packStatuses() {
        Map<String, VoicePackManager.Status> out = new LinkedHashMap<>();
        for (String lang : LanguageTags.VOICE) out.put(lang, packStatus(lang));
        return out;
    }
    /** True while any pack downloads or verifies. */
    public boolean anyPackBusy() { for (VoicePackManager m : packs.values()) if (m.busy()) return true; return false; }
    public String engineState() { return engine == null ? "FAILED" : engine.state().name(); }

    /** User-initiated recovery when the private store cannot be opened. Only AniSub's own data. */
    public synchronized boolean resetStore() {
        if (engine != null) engine.unload();
        for (VoicePackManager m : packs.values()) m.shutdown();
        File root = new File(app.getFilesDir(), "voices");
        deleteTree(root, 0);
        openStore();
        changed();
        if (voiceAuto != null) voiceAuto.resume();
        return voices != null;
    }

    private static void deleteTree(File f, int depth) {
        if (depth > 32) return;
        File[] children = f.isDirectory() ? f.listFiles() : null;
        if (children != null) for (File c : children) deleteTree(c, depth + 1);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    public void addEngineListener(AiSpeechEngine.Listener l) { engineListeners.addIfAbsent(l); }
    public void removeEngineListener(AiSpeechEngine.Listener l) { engineListeners.remove(l); }
    public void addListener(Runnable r) { listeners.addIfAbsent(r); }
    public void removeListener(Runnable r) { listeners.remove(r); }
    private void changed() { main.post(() -> { for (Runnable r : listeners) r.run(); }); }

    /** True when AniBox is installed and signed with the same certificate as AniSub. */
    public boolean aniBoxCompatible() {
        PackageManager pm = app.getPackageManager();
        for (String pkg : CallerPolicy.clientPackages(BuildConfig.DEBUG)) {
            try {
                pm.getPackageInfo(pkg, 0);
                if (pm.checkSignatures(pkg, app.getPackageName()) == PackageManager.SIGNATURE_MATCH) return true;
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        return false;
    }
    public boolean aniBoxInstalled() {
        for (String pkg : CallerPolicy.clientPackages(BuildConfig.DEBUG)) {
            try { app.getPackageManager().getPackageInfo(pkg, 0); return true; }
            catch (PackageManager.NameNotFoundException ignored) { }
        }
        return false;
    }

    static String asset(Context context, String name) throws IOException {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n; long total = 0;
            while ((n = in.read(buf)) != -1) { total += n; if (total > 256 * 1024) throw new IOException("asset too large"); out.write(buf, 0, n); }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
