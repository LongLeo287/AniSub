package com.anisub.runtime;

import com.anisub.runtime.ai.RatePolicy;
import com.anisub.runtime.ai.SherpaSynthesizer;
import com.anisub.runtime.voice.VoiceCatalog;
import com.anisub.runtime.voice.VoicePackManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Builds the major 1 / minor 1 CAPABILITIES reply. Additive: every minor-0 field is kept. */
public final class Capabilities {
    public static final int MAJOR = 1, MINOR = 1;
    private Capabilities() { }

    /**
     * @param pack voice-pack status, or null when the private store is unusable
     * @param engineState AI engine residency (IDLE/LOADING/READY/FAILED)
     */
    public static JSONObject build(boolean systemReady, String systemState, VoicePackManager.Status pack,
                                   String engineState, String versionName, long versionCode) throws JSONException {
        boolean aiVoice = pack != null && pack.ready();
        JSONObject out = new JSONObject();
        out.put("type", "CAPABILITIES").put("major", MAJOR).put("minor", MINOR).put("protocolMajor", MAJOR)
                .put("directText", true).put("tts", systemReady).put("offline", systemReady || aiVoice)
                .put("aiVoice", aiVoice).put("translation", false).put("multiSpeaker", false)
                .put("engine", "android-system-tts").put("state", systemState == null ? "initializing" : systemState);
        JSONObject vp = new JSONObject();
        if (pack == null) {
            vp.put("state", "ERROR").put("error", VoicePackManager.E_STORAGE);
        } else {
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
        }
        out.put("voicePack", vp);
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
}
