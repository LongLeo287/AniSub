package com.anisub.runtime.settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * AniSub's persisted settings (ANISUB-005), over a small key/value port so the rules are JVM-tested.
 * Every value is clamped on read and write; unknown or corrupt values fall back to the default.
 * Optional CAPABILITIES metadata; older clients do not act on these settings.
 */
public final class AniSubPrefs {
    /** SharedPreferences on the device, a map in tests. */
    public interface Store {
        String getString(String key, String def);
        void putString(String key, String value);
        void remove(String key);
    }

    public static final float RATE_MIN = 0.8f, RATE_MAX = 1.3f, RATE_DEFAULT = 1.0f;
    public static final int PITCH_MIN = -3, PITCH_MAX = 3, VOLUME_MIN = 0, VOLUME_MAX = 100, VOLUME_DEFAULT = 100;
    public static final int DUCK_MIN = 10, DUCK_MAX = 60, DUCK_DEFAULT = 30;
    public static final int PAUSE_MIN = 0, PAUSE_MAX = 1000, PAUSE_STEP = 100, PAUSE_DEFAULT = 0;
    public static final String STYLE_CALM = "calm", STYLE_NORMAL = "normal", STYLE_LIVELY = "lively";
    public static final List<String> STYLES = Collections.unmodifiableList(Arrays.asList(STYLE_CALM, STYLE_NORMAL, STYLE_LIVELY));
    public static final String GAP_SHORT = "short", GAP_NORMAL = "normal", GAP_LONG = "long";
    public static final List<String> GAPS = Collections.unmodifiableList(Arrays.asList(GAP_SHORT, GAP_NORMAL, GAP_LONG));
    public static final List<String> DEFAULT_PRIORITY = Collections.unmodifiableList(Arrays.asList("vi", "en", "ja"));

    private final Store store;
    public AniSubPrefs(Store store) { this.store = store; }

    // ---------------------------------------------------------------- per voice
    public boolean voiceEnabled(String voiceId) { return !"false".equals(store.getString("voice." + voiceId + ".enabled", "true")); }
    public void setVoiceEnabled(String voiceId, boolean enabled) { store.putString("voice." + voiceId + ".enabled", Boolean.toString(enabled)); }

    /** Per-voice reading rate; falls back to the app-wide rate. */
    public float voiceRate(String voiceId) {
        return clamp(parseFloat(store.getString("voice." + voiceId + ".rate", null), globalRate()), RATE_MIN, RATE_MAX);
    }
    public void setVoiceRate(String voiceId, float rate) { store.putString("voice." + voiceId + ".rate", fmt(round2(clamp(rate, RATE_MIN, RATE_MAX)))); }
    /** Pitch shift in semitones (Sonic on the PCM for AI voices, TextToSpeech.setPitch for system voices). */
    public int voicePitch(String voiceId) { return clamp(parseInt(store.getString("voice." + voiceId + ".pitch", null), 0), PITCH_MIN, PITCH_MAX); }
    public void setVoicePitch(String voiceId, int semitones) { store.putString("voice." + voiceId + ".pitch", Integer.toString(clamp(semitones, PITCH_MIN, PITCH_MAX))); }
    public int voiceVolume(String voiceId) { return clamp(parseInt(store.getString("voice." + voiceId + ".volume", null), VOLUME_DEFAULT), VOLUME_MIN, VOLUME_MAX); }
    public void setVoiceVolume(String voiceId, int percent) { store.putString("voice." + voiceId + ".volume", Integer.toString(clamp(percent, VOLUME_MIN, VOLUME_MAX))); }
    /** A missing or corrupt voice override inherits the global expressiveness preset. */
    public String voiceStyle(String voiceId) {
        String value = store.getString("voice." + voiceId + ".style", null);
        return STYLES.contains(value) ? value : style();
    }
    public boolean hasVoiceStyle(String voiceId) { return STYLES.contains(store.getString("voice." + voiceId + ".style", null)); }
    public void setVoiceStyle(String voiceId, String value) {
        if (STYLES.contains(value)) store.putString("voice." + voiceId + ".style", value);
        else store.remove("voice." + voiceId + ".style");
    }

    /** The chosen default voice of a voice language, or null (then the language's default pack). */
    public String defaultVoice(String language) {
        String legacy = "vi".equals(language) ? store.getString("voice", null) : null; // 0.2.0 key
        return store.getString("default." + language, legacy);
    }
    public void setDefaultVoice(String language, String voiceId) {
        if (voiceId == null) store.remove("default." + language); else store.putString("default." + language, voiceId);
    }
    /** Kind-separated defaults: a system selection must never replace the legacy AI default. */
    public String defaultVoice(String kind, String language) {
        return "AI".equals(kind) ? defaultVoice(language) : store.getString("default.system." + language, null);
    }
    public void setDefaultVoice(String kind, String language, String voiceId) {
        if ("AI".equals(kind)) setDefaultVoice(language, voiceId);
        else if (voiceId == null) store.remove("default.system." + language);
        else store.putString("default.system." + language, voiceId);
    }
    /** Immutable parameters captured by OPEN/preview, never read again during synthesis. */
    public static final class Snapshot {
        public final float rate; public final int pitch, volume, pauseMs; public final String style, gap;
        public Snapshot(float rate, int pitch, int volume, int pauseMs, String style, String gap) {
            this.rate = rate; this.pitch = pitch; this.volume = volume; this.pauseMs = pauseMs; this.style = style; this.gap = gap;
        }
    }
    public Snapshot snapshot(String id) { return new Snapshot(voiceRate(id), voicePitch(id), voiceVolume(id), pauseMs(), voiceStyle(id), gap()); }

    // ---------------------------------------------------------------- reading ("Cách đọc")
    public float globalRate() { return clamp(parseFloat(store.getString("defaultRate", null), RATE_DEFAULT), RATE_MIN, RATE_MAX); }
    public void setGlobalRate(float rate) { store.putString("defaultRate", fmt(round2(clamp(rate, RATE_MIN, RATE_MAX)))); }
    public String style() { String s = store.getString("style", STYLE_NORMAL); return STYLES.contains(s) ? s : STYLE_NORMAL; }
    public void setStyle(String s) { store.putString("style", STYLES.contains(s) ? s : STYLE_NORMAL); }
    public String gap() { String s = store.getString("gap", GAP_NORMAL); return GAPS.contains(s) ? s : GAP_NORMAL; }
    public void setGap(String s) { store.putString("gap", GAPS.contains(s) ? s : GAP_NORMAL); }
    /** Extra silence between two subtitle lines (ms). */
    public int pauseMs(){ int v = parseInt(store.getString("pauseMs", null), PAUSE_DEFAULT); return clamp(v - v % PAUSE_STEP, PAUSE_MIN, PAUSE_MAX); }
    public void setPauseMs(int ms) { store.putString("pauseMs", Integer.toString(clamp(ms - ms % PAUSE_STEP, PAUSE_MIN, PAUSE_MAX))); }
    /** Film volume while the voice speaks, % of normal (AniBox duckFactor = duckLevel / 100; 10-60, default 30). */
    public int duckLevel() { return clamp(parseInt(store.getString("duckLevel", null), DUCK_DEFAULT), DUCK_MIN, DUCK_MAX); }
    public void setDuckLevel(int percent) { store.putString("duckLevel", Integer.toString(clamp(percent, DUCK_MIN, DUCK_MAX))); }
    public boolean readAnnotations() { return "true".equals(store.getString("readAnnotations", "false")); }
    public void setReadAnnotations(boolean on) { store.putString("readAnnotations", Boolean.toString(on)); }
    /** Optional client preference. A client update must consume it before subtitle selection changes. */
    public List<String> languagePriority() {
        String raw = store.getString("languagePriority", null);
        if (raw == null) return DEFAULT_PRIORITY;
        List<String> out = new ArrayList<>();
        for (String l : raw.split(",")) {
            String t = l.trim().toLowerCase(Locale.ROOT);
            if (t.matches("[a-z]{2,3}") && !out.contains(t) && out.size() < 8) out.add(t);
        }
        return out.isEmpty() ? DEFAULT_PRIORITY : Collections.unmodifiableList(out);
    }
    public void setLanguagePriority(List<String> langs) {
        StringBuilder b = new StringBuilder();
        for (String l : langs) { if (b.length() > 0) b.append(','); b.append(l); }
        store.putString("languagePriority", b.toString());
    }
    /** Moves {@code lang} one place up (-1) or down (+1) in the priority list. */
    public void movePriority(String lang, int direction) {
        List<String> list = new ArrayList<>(languagePriority());
        int i = list.indexOf(lang), j = i + direction;
        if (i < 0 || j < 0 || j >= list.size()) return;
        Collections.swap(list, i, j);
        setLanguagePriority(list);
    }

    // ---------------------------------------------------------------- translation
    /** "Tự động dịch": translate a softsub in another language (off = such an OPEN is refused). */
    public boolean autoTranslate() { return !"false".equals(store.getString("autoTranslate", "true")); }
    public void setAutoTranslate(boolean on) { store.putString("autoTranslate", Boolean.toString(on)); }
    /** "Tự tải gói dịch khi cần": download a missing translation model automatically (off = consent in settings). */
    public boolean autoDownloadModels() { return !"false".equals(store.getString("autoDownloadModels", "true")); }
    public void setAutoDownloadModels(boolean on) { store.putString("autoDownloadModels", Boolean.toString(on)); }

    // ---------------------------------------------------------------- first run
    public boolean firstRunDone() { return "true".equals(store.getString("firstRunDone", "false")); }
    public void setFirstRunDone(boolean done) { store.putString("firstRunDone", Boolean.toString(done)); }

    // ---------------------------------------------------------------- helpers
    static float round2(float v) { return Math.round(v * 100) / 100f; }
    static String fmt(float v) { return String.format(Locale.ROOT, "%.2f", v); }
    static float clamp(float v, float lo, float hi) { return Float.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v)); }
    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    static float parseFloat(String s, float def) { if (s == null) return def; try { return Float.parseFloat(s); } catch (NumberFormatException e) { return def; } }
    static int parseInt(String s, int def) { if (s == null) return def; try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; } }
}
