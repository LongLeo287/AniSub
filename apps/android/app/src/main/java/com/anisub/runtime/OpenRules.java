package com.anisub.runtime;

import com.anisub.runtime.ai.RatePolicy;
import com.anisub.runtime.translate.LanguageTags;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Major 1 OPEN admission (minor 1 + additive minor 2).
 *
 * <p>mode "system" (default when absent) keeps the legacy systemTest:true contract (Vietnamese only).
 * mode "ai" speaks with the voice pack of {@code voiceLang} ("vi" when absent, as for every minor-1
 * client); a missing pack is VOICE_PACK_MISSING, never a silent fallback to the system voice.
 * Minor 2: {@code language} is the cue SOURCE language (BCP-47, "und" allowed). When it differs from
 * the voice language AniSub translates on the device (ML Kit); OPEN is refused with
 * TRANSLATE_UNAVAILABLE (engine cannot run / language not supported) or TRANSLATE_MODEL_MISSING
 * (the user has not downloaded a model yet). "und" is detected on the device from the first cues
 * (English is assumed when detection is unavailable).
 */
public final class OpenRules {
    public static final String MODE_SYSTEM = "system", MODE_AI = "ai";
    public static final String VOICE_PACK_MISSING = "VOICE_PACK_MISSING";
    public static final String TRANSLATE_MODEL_MISSING = "TRANSLATE_MODEL_MISSING", TRANSLATE_UNAVAILABLE = "TRANSLATE_UNAVAILABLE";

    /** What the runtime has right now. */
    public interface Env {
        boolean systemReady();
        boolean packReady(String voiceLang);
        Set<String> packVoices(String voiceLang);
        /** The translation engine can run on this device. */
        boolean translateAvailable();
        /** Why it cannot (null when available). */
        String translateUnavailableReason();
        /** The ML Kit model for {@code lang} is installed ("en" is built in). */
        boolean modelReady(String lang);
        /** On-device language identification is available for "und". */
        boolean detectAvailable();
        /** "Tự tải gói dịch khi cần" is on (default). */
        boolean autoDownloadModels();
        /** A model could be downloaded automatically now (engine usable, enough free space). */
        boolean canAutoDownload();
    }

    public static final class Decision {
        public final String error, mode, voiceId, voiceLang, source; public final float rate;
        /** Error detail fields to add to the ERROR reply (may be empty). */
        public final JSONObject detail;
        /** Cues must be translated from {@link #source} (or a detected source when "und") into {@link #voiceLang}. */
        public final boolean translate;
        /** Models to download automatically before translating (empty: all installed). */
        public final List<String> download;
        private Decision(String error, String mode, float rate, String voiceId, String voiceLang, String source, boolean translate, JSONObject detail) {
            this(error, mode, rate, voiceId, voiceLang, source, translate, detail, Collections.<String>emptyList());
        }
        private Decision(String error, String mode, float rate, String voiceId, String voiceLang, String source, boolean translate, JSONObject detail, List<String> download) {
            this.error = error; this.mode = mode; this.rate = rate; this.voiceId = voiceId; this.voiceLang = voiceLang;
            this.source = source; this.translate = translate; this.detail = detail == null ? new JSONObject() : detail;
            this.download = download;
        }
        public boolean accepted() { return error == null; }
        static Decision reject(String code) { return new Decision(code, null, 0, null, null, null, false, null); }
        static Decision reject(String code, JSONObject detail) { return new Decision(code, null, 0, null, null, null, false, detail); }
    }
    private OpenRules() { }

    /** Minor-1 form: only the Vietnamese pack is known and there is no translation engine. */
    public static Decision decide(JSONObject open, String language, final boolean systemReady, final boolean packReady,
                                  final Set<String> packVoices, float defaultRate) throws JSONException {
        return decide(open, language, new Env() {
            public boolean systemReady() { return systemReady; }
            public boolean packReady(String voiceLang) { return packReady && LanguageTags.VI.equals(voiceLang); }
            public Set<String> packVoices(String voiceLang) { return LanguageTags.VI.equals(voiceLang) ? packVoices : Collections.<String>emptySet(); }
            public boolean translateAvailable() { return false; }
            public String translateUnavailableReason() { return "NO_ENGINE"; }
            public boolean modelReady(String lang) { return LanguageTags.EN.equals(lang); }
            public boolean detectAvailable() { return false; }
            public boolean autoDownloadModels() { return false; }
            public boolean canAutoDownload() { return false; }
        }, defaultRate);
    }

    /**
     * @param language the validated session language tag (cue source language)
     * @throws JSONException for wrongly typed or out-of-range optional fields (MALFORMED)
     */
    public static Decision decide(JSONObject open, String language, Env env, float defaultRate) throws JSONException {
        String mode = MODE_SYSTEM;
        if (open.has("mode")) {
            Object raw = open.get("mode");
            if (!(raw instanceof String)) throw new JSONException("mode type");
            mode = (String) raw;
        }
        float rate = defaultRate;
        if (open.has("rate")) {
            Object raw = open.get("rate");
            if (!(raw instanceof Number)) throw new JSONException("rate type");
            double r = ((Number) raw).doubleValue();
            if (!RatePolicy.validUserRate(r)) throw new JSONException("rate bounds");
            rate = (float) r;
        }
        String voice = null;
        if (open.has("voice") && !open.isNull("voice")) {
            Object raw = open.get("voice");
            if (!(raw instanceof String) || ((String) raw).isEmpty() || ((String) raw).length() > 80) throw new JSONException("voice type");
            voice = (String) raw;
        }
        String voiceLang = LanguageTags.VI;
        if (open.has("voiceLang") && !open.isNull("voiceLang")) {
            Object raw = open.get("voiceLang");
            if (!(raw instanceof String)) throw new JSONException("voiceLang type");
            voiceLang = (String) raw;
        }
        if (!LanguageTags.valid(language)) throw new JSONException("language");
        if (MODE_SYSTEM.equals(mode)) {
            boolean systemTest = Boolean.TRUE.equals(open.opt("systemTest"));
            if (!LanguageTags.VI.equals(voiceLang)) return Decision.reject("UNSUPPORTED");
            if (!systemTest || !"vi".equals(language) || !env.systemReady()) return Decision.reject("UNAVAILABLE");
            return new Decision(null, MODE_SYSTEM, rate, null, LanguageTags.VI, LanguageTags.VI, false, null);
        }
        if (!MODE_AI.equals(mode)) return Decision.reject("UNSUPPORTED");
        if (!LanguageTags.voice(voiceLang)) return Decision.reject("UNSUPPORTED", detail("voiceLang", voiceLang));
        if (!env.packReady(voiceLang)) return Decision.reject(VOICE_PACK_MISSING, detail("voiceLang", voiceLang));
        if (voice != null && !env.packVoices(voiceLang).contains(voice)) return Decision.reject("UNSUPPORTED");

        String source = LanguageTags.base(language);
        if (LanguageTags.UND.equals(source) && !env.detectAvailable()) source = LanguageTags.EN; // documented assumption
        if (source.equals(voiceLang)) return new Decision(null, MODE_AI, rate, voice, voiceLang, source, false, null);
        if (!env.translateAvailable()) {
            JSONObject d = detail("voiceLang", voiceLang);
            d.put("reason", env.translateUnavailableReason() == null ? "UNAVAILABLE" : env.translateUnavailableReason());
            if (!LanguageTags.UND.equals(source)) d.put("language", source);
            return Decision.reject(TRANSLATE_UNAVAILABLE, d);
        }
        if (LanguageTags.UND.equals(source)) {
            // Detected from the first cues; the target model must be there already.
            JSONObject missing = missing(env, LanguageTags.EN, voiceLang, "und");
            if (missing != null) return missingOrDownload(env, missing, rate, voice, voiceLang, source);
            return new Decision(null, MODE_AI, rate, voice, voiceLang, source, true, null);
        }
        if (!LanguageTags.translatable(source)) {
            JSONObject d = detail("voiceLang", voiceLang).put("language", source).put("reason", "UNSUPPORTED_LANGUAGE");
            return Decision.reject(TRANSLATE_UNAVAILABLE, d);
        }
        JSONObject missing = missing(env, source, voiceLang, source);
        if (missing != null) return missingOrDownload(env, missing, rate, voice, voiceLang, source);
        return new Decision(null, MODE_AI, rate, voice, voiceLang, source, true, null);
    }

    /**
     * Missing models: with "Tự tải gói dịch khi cần" on, the OPEN is accepted and the service downloads
     * them (TRANSLATE_MODEL_DOWNLOADING), speaking once they are ready; otherwise, or without space,
     * TRANSLATE_MODEL_MISSING (reason NO_SPACE when the automatic download is impossible).
     */
    private static Decision missingOrDownload(Env env, JSONObject missing, float rate, String voice, String voiceLang, String source) throws JSONException {
        if (!env.autoDownloadModels()) return Decision.reject(TRANSLATE_MODEL_MISSING, missing);
        if (!env.canAutoDownload()) return Decision.reject(TRANSLATE_MODEL_MISSING, missing.put("reason", "NO_SPACE"));
        List<String> list = new ArrayList<>();
        org.json.JSONArray a = missing.getJSONArray("missing");
        for (int i = 0; i < a.length(); i++) list.add(a.getString(i));
        return new Decision(null, MODE_AI, rate, voice, voiceLang, source, true, null, Collections.unmodifiableList(list));
    }

    /**
     * Error detail for missing models of the pair, or null when all are installed:
     * {language: first missing model, from, to, voiceLang, missing: [...]}.
     */
    public static JSONObject missing(Env env, String from, String to, String reportedFrom) throws JSONException {
        List<String> missing = new ArrayList<>();
        for (String m : LanguageTags.modelsFor(from, to)) if (!env.modelReady(m)) missing.add(m);
        if (missing.isEmpty()) return null;
        JSONObject d = new JSONObject().put("language", missing.get(0)).put("from", reportedFrom).put("to", to).put("voiceLang", to);
        d.put("missing", new org.json.JSONArray(missing));
        return d;
    }

    private static JSONObject detail(String key, String value) throws JSONException { return new JSONObject().put(key, value); }
}
