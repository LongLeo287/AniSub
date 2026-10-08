package com.anisub.runtime;

import com.anisub.runtime.ai.RatePolicy;
import com.anisub.runtime.ai.SherpaSynthesizer;
import com.anisub.runtime.translate.CueTimeline;
import com.anisub.runtime.translate.LanguageTags;
import com.anisub.runtime.translate.TranslationScheduler;
import com.anisub.runtime.voice.VoiceCatalog;
import com.anisub.runtime.voice.VoicePackManager;
import com.anisub.runtime.voice.VoiceRegistry;
import com.anisub.runtime.settings.AniSubPrefs;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Builds the major 1 CAPABILITIES reply, minor 2. Additive: every minor-0 and minor-1 field is
 * kept with its old meaning ({@code voicePack}, {@code aiVoice} and {@code modes} describe the
 * Vietnamese pack, as minor-1 clients expect). Minor 2 adds {@code voices}, {@code translate}
 * and {@code timeline}.
 */
public final class Capabilities {
    public static final int MAJOR = 1, MINOR = 2;
    /** Wire states of a voice pack or translation model in minor 2. */
    public static final String READY = "ready", MISSING = "missing", DOWNLOADING = "downloading";
    private Capabilities() { }

    /** Translation engine snapshot for CAPABILITIES. */
    public static final class TranslateInfo {
        public final boolean available, detect; public final String reason;
        /** language -> ready / missing / downloading, in display order. */
        public final Map<String, String> models;
        public TranslateInfo(boolean available, String reason, boolean detect, Map<String, String> models) {
            this.available = available; this.reason = reason; this.detect = detect;
            this.models = models == null ? Collections.<String, String>emptyMap() : models;
        }
        /** No engine: every offered model missing except built-in English. */
        public static TranslateInfo none(String reason) {
            Map<String, String> m = new LinkedHashMap<>();
            for (String l : LanguageTags.OFFERED) m.put(l, LanguageTags.EN.equals(l) ? READY : MISSING);
            return new TranslateInfo(false, reason, false, m);
        }
    }

    /** Minor-1 form (Vietnamese pack only, no translation engine); still reports minor 2. */
    public static JSONObject build(boolean systemReady, String systemState, VoicePackManager.Status pack,
                                   String engineState, String versionName, long versionCode) throws JSONException {
        Map<String, VoicePackManager.Status> packs = new LinkedHashMap<>();
        packs.put(LanguageTags.VI, pack);
        return build(systemReady, systemState, packs, TranslateInfo.none("NO_ENGINE"), engineState, versionName, versionCode);
    }

    /**
     * @param packs voice-pack status per voice language ("vi" first); a null status means the
     *              private store is unusable
     * @param engineState AI engine residency (IDLE/LOADING/READY/FAILED)
     */
    public static JSONObject build(boolean systemReady, String systemState, Map<String, VoicePackManager.Status> packs,
                                   TranslateInfo translate, String engineState, String versionName, long versionCode) throws JSONException {
        VoicePackManager.Status pack = packs.get(LanguageTags.VI);
        boolean aiVoice = pack != null && pack.ready();
        boolean anyVoice = false;
        for (VoicePackManager.Status s : packs.values()) anyVoice |= s != null && s.ready();
        JSONObject out = new JSONObject();
        out.put("type", "CAPABILITIES").put("major", MAJOR).put("minor", MINOR).put("protocolMajor", MAJOR)
                .put("directText", true).put("tts", systemReady).put("offline", systemReady || anyVoice)
                // Legacy "translation"/"multiSpeaker" stay false: AniBox (minor-1 parser) rejects a reply whose
                // engine is "android-system-tts" and claims either. Availability is translate.available.
                .put("aiVoice", aiVoice).put("translation", false).put("multiSpeaker", false)
                .put("engine", "android-system-tts").put("state", systemState == null ? "initializing" : systemState);
        out.put("voicePack", legacyPack(pack, aiVoice));
        JSONObject voices = new JSONObject();
        for (String lang : LanguageTags.VOICE) voices.put(lang, voiceEntry(packs.get(lang)));
        out.put("voices", voices);
        JSONObject tr = new JSONObject().put("engine", "mlkit").put("available", translate.available).put("detect", translate.detect);
        if (!translate.available && translate.reason != null) tr.put("reason", translate.reason);
        JSONObject models = new JSONObject();
        for (Map.Entry<String, String> e : translate.models.entrySet()) models.put(e.getKey(), e.getValue());
        tr.put("models", models).put("lookaheadMs", TranslationScheduler.LOOKAHEAD_MS);
        out.put("translate", tr);
        out.put("timeline", new JSONObject().put("maxCues", CueTimeline.MAX_CUES).put("maxTextUnits", CueTimeline.MAX_TEXT_UNITS));
        out.put("aiEngine", new JSONObject().put("name", SherpaSynthesizer.ENGINE).put("version", SherpaSynthesizer.ENGINE_VERSION)
                .put("state", engineState == null ? "IDLE" : engineState));
        JSONArray modes = new JSONArray();
        if (systemReady) modes.put(OpenRules.MODE_SYSTEM);
        if (aiVoice) modes.put(OpenRules.MODE_AI);
        out.put("modes", modes);
        // Decimal literals (not float widening) so clients see 0.8, not 0.800000011920929.
        out.put("rate", new JSONObject().put("min", Double.parseDouble(Float.toString(RatePolicy.USER_MIN)))
                .put("max", Double.parseDouble(Float.toString(RatePolicy.USER_MAX)))
                .put("default", Double.parseDouble(Float.toString(RatePolicy.USER_DEFAULT))));
        out.put("runtime", new JSONObject().put("versionName", versionName).put("versionCode", versionCode));
        return out;
    }

    /** Minor-2 voice entry: {state, id, version, sizeBytes, doneBytes?, error?, voices}. */
    static JSONObject voiceEntry(VoicePackManager.Status s) throws JSONException {
        JSONObject v = new JSONObject();
        if (s == null) return v.put("state", MISSING).put("error", VoicePackManager.E_STORAGE).put("voices", new JSONArray());
        String state = s.ready() ? READY : s.state == VoicePackManager.State.DOWNLOADING || s.state == VoicePackManager.State.VERIFYING ? DOWNLOADING : MISSING;
        v.put("state", state);
        VoiceCatalog.Pack p = s.pack;
        if (p != null) v.put("id", p.id).put("version", s.installedVersion != null ? s.installedVersion : p.version).put("sizeBytes", p.totalBytes);
        if (DOWNLOADING.equals(state)) v.put("doneBytes", s.doneBytes);
        if (s.error != null) v.put("error", s.error);
        JSONArray list = new JSONArray();
        if (s.ready() && p != null) for (VoiceCatalog.Voice voice : p.voices) list.put(new JSONObject().put("id", voice.id).put("name", voice.name));
        return v.put("voices", list);
    }

    private static JSONObject legacyPack(VoicePackManager.Status pack, boolean aiVoice) throws JSONException {
        JSONObject vp = new JSONObject();
        if (pack == null) return vp.put("state", "ERROR").put("error", VoicePackManager.E_STORAGE);
        vp.put("state", pack.state.name());
        VoiceCatalog.Pack p = pack.pack;
        if (p != null) {
            vp.put("id", p.id).put("name", p.name).put("sizeBytes", p.totalBytes)
                    .put("version", pack.installedVersion != null ? pack.installedVersion : p.version)
                    .put("updateAvailable", pack.updateAvailable).put("license", p.license);
            JSONArray voices = new JSONArray();
            if (aiVoice) for (VoiceCatalog.Voice v : p.voices) voices.put(new JSONObject().put("id", v.id).put("name", v.name));
            vp.put("voices", voices);
        }
        if (pack.state == VoicePackManager.State.DOWNLOADING) vp.put("doneBytes", pack.doneBytes);
        if (pack.error != null) vp.put("error", pack.error);
        return vp;
    }

    /** The reply without progress counters: equal signatures mean "nothing worth re-sending". */
    public static JSONObject withVoiceMetadata(JSONObject legacy, VoiceRegistry registry) throws JSONException {
        // Keep the legacy object unmodified. If optional metadata exceeds the existing
        // wire budget, drop that optional entry rather than a legacy required field.
        JSONObject out = new JSONObject(legacy.toString());
        if (out.toString().length() > 16384) return out;
        JSONObject languages = out.getJSONObject("voices");
        for (String lang : LanguageTags.VOICE) {
            JSONArray list = languages.getJSONObject(lang).getJSONArray("voices");
            for (int i = 0; i < list.length(); i++) {
                JSONObject original = list.getJSONObject(i);
                VoiceRegistry.Entry entry = registry.find(original.optString("id"));
                if (entry == null || entry.kind != VoiceRegistry.Kind.AI) continue;
                JSONObject decorated = new JSONObject(original.toString());
                decorate(decorated, entry, registry);
                list.put(i, decorated);
                if (out.toString().length() > 16384) list.put(i, original);
            }
            // Additional installed packs share a language, not the legacy bootstrap pack's identity.
            // Preserve language/voicePack readiness fields; explicit voiceId uses per-entry readiness.
            for (VoiceRegistry.Entry entry : registry.all()) if (entry.kind == VoiceRegistry.Kind.AI
                    && entry.installed && lang.equals(entry.language)) {
                boolean found=false;
                for(int i=0;i<list.length();i++)if(entry.id.equals(list.getJSONObject(i).optString("id")))found=true;
                if(found)continue;
                JSONObject item=new JSONObject().put("id",entry.id).put("name",entry.name)
                        .put("installed",true).put("packId",entry.packId);
                decorate(item,entry,registry);list.put(item);
                if(out.toString().length()>16384){list.remove(list.length()-1);break;}
            }
        }
        JSONArray systems = new JSONArray();
        out.put("systemVoices", systems);
        if (out.toString().length() > 16384) { out.remove("systemVoices"); return out; }
        for (VoiceRegistry.Entry entry : registry.all()) if (entry.kind == VoiceRegistry.Kind.SYSTEM) {
            JSONObject item = new JSONObject().put("id", entry.id).put("name", entry.name)
                    .put("language", entry.language).put("kind", "system").put("installed", entry.installed);
            decorate(item, entry, registry);
            systems.put(item);
            if (out.toString().length() > 16384) { systems.remove(systems.length()-1); break; }
        }
        return out;
    }

    /** Optional client-owned duck/subtitle preferences. Never changes a legacy capability meaning. */
    public static JSONObject withReadingPreferences(JSONObject legacy, AniSubPrefs prefs) throws JSONException {
        JSONObject out=new JSONObject(legacy.toString());
        JSONObject reading=new JSONObject().put("duckLevel",prefs.duckLevel())
                .put("languagePriority",new JSONArray(prefs.languagePriority()));
        out.put("readingPreferences",reading);
        if(out.toString().length()>16384)out.remove("readingPreferences");
        return out;
    }

    private static void decorate(JSONObject item, VoiceRegistry.Entry entry, VoiceRegistry registry) throws JSONException {
        VoiceRegistry.Entry selected = registry.defaultVoice(entry.kind, entry.language);
        item.put("gender", entry.gender).put("accent", entry.accent).put("enabled", entry.enabled)
                .put("default", selected != null && selected.id.equals(entry.id));
    }

    /** The reply without progress counters: equal signatures mean "nothing worth re-sending". */
    public static String signature(JSONObject capabilities) throws JSONException {
        JSONObject key = new JSONObject(capabilities.toString());
        key.getJSONObject("voicePack").remove("doneBytes");
        JSONObject voices = key.getJSONObject("voices");
        for (String lang : LanguageTags.VOICE) if (voices.has(lang)) voices.getJSONObject(lang).remove("doneBytes");
        return key.toString();
    }
}
