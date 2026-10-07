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
import com.anisub.runtime.voice.HttpsSource;
import com.anisub.runtime.voice.VoiceCatalog;
import com.anisub.runtime.voice.VoicePackManager;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-wide composition root shared by the bound service and the settings screen:
 * pinned catalog, private model store, voice-pack manager and the single AI engine.
 */
public final class RuntimeHost {
    public static final String PREFS = "anisub";
    public static final String KEY_RATE = "defaultRate", KEY_VOICE = "voice";
    @android.annotation.SuppressLint("StaticFieldLeak") // holds the Application context only
    private static RuntimeHost instance;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    /** Stable across resetStore(): the service/UI subscribe here, not to a specific engine instance. */
    private final CopyOnWriteArrayList<AiSpeechEngine.Listener> engineListeners = new CopyOnWriteArrayList<>();
    private final VoiceCatalog catalog;
    private VoicePackManager voices;
    private AiSpeechEngine engine;
    private final RatePolicy rates = new RatePolicy();
    private final String versionName;
    private final long versionCode;
    private volatile boolean sessionActive;

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
    }

    @SuppressWarnings("deprecation")
    private static long versionCodeLegacy(PackageInfo info) { return info.versionCode; }

    private void openStore() {
        try {
            File root = new File(app.getFilesDir(), "voices").getCanonicalFile();
            ModelStore store = new ModelStore(root, new StorageBudget(StorageBudget.DEFAULT_QUOTA), new IntegrityVerifier(),
                    System::currentTimeMillis, point -> { });
            voices = new VoicePackManager(store, catalog, new HttpsSource("AniSub/" + versionName), this::smokeTest, s -> changed());
            int threads = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() / 2));
            engine = new AiSpeechEngine(voices, rates, threads);
            final VoicePackManager packs = voices;
            engine.addListener(new AiSpeechEngine.Listener() {
                public void started(String id) { for (AiSpeechEngine.Listener l : engineListeners) l.started(id); }
                public void finished(String id) { for (AiSpeechEngine.Listener l : engineListeners) l.finished(id); }
                public void failed(String id, String code) { for (AiSpeechEngine.Listener l : engineListeners) l.failed(id, code); }
                public void engineChanged(AiSpeechEngine.State state, String error) {
                    // espeak-ng keeps its first data path per process: after a native load, an
                    // update must not delete the previous pack directory until restart.
                    if (state == AiSpeechEngine.State.READY) packs.setKeepPreviousUntilRestart(true);
                    for (AiSpeechEngine.Listener l : engineListeners) l.engineChanged(state, error);
                    changed();
                }
            });
        } catch (IOException | RuntimeException e) {
            voices = null; engine = null; // store unusable; settings offers a user-initiated reset
        }
    }

    /** Native smoke test of a freshly installed pack: load + synthesize one short phrase. */
    private String smokeTest(File dir, VoiceCatalog.Pack pack) {
        SherpaSynthesizer s = null;
        try {
            s = new SherpaSynthesizer(new File(dir, pack.model), new File(dir, pack.tokens), new File(dir, pack.dataDir), 1, pack.voices.get(0).speakerId);
            float[] pcm = s.synthesize("Xin chào.", 1f, () -> false);
            return pcm.length > s.sampleRate() / 10 ? null : "INCOMPATIBLE";
        } catch (Throwable t) {
            return "INCOMPATIBLE";
        } finally { if (s != null) s.release(); }
    }

    public VoicePackManager voices() { return voices; }
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
    public String engineState() { return engine == null ? "FAILED" : engine.state().name(); }

    /** User-initiated recovery when the private store cannot be opened. Only AniSub's own data. */
    public synchronized boolean resetStore() {
        if (engine != null) engine.unload();
        if (voices != null) voices.shutdown();
        File root = new File(app.getFilesDir(), "voices");
        deleteTree(root, 0);
        openStore();
        changed();
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
