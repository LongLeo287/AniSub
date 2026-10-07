package com.anisub.runtime;

import com.anisub.runtime.ai.RatePolicy;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Major 1 / minor 1 OPEN admission. mode "system" (default when absent) keeps the legacy
 * systemTest:true contract. mode "ai" needs a READY verified voice pack, otherwise
 * VOICE_PACK_MISSING; there is never a silent fallback from AI to the system voice.
 */
public final class OpenRules {
    public static final String MODE_SYSTEM = "system", MODE_AI = "ai";
    public static final String VOICE_PACK_MISSING = "VOICE_PACK_MISSING";

    public static final class Decision {
        public final String error, mode, voiceId; public final float rate;
        private Decision(String error, String mode, float rate, String voiceId) { this.error = error; this.mode = mode; this.rate = rate; this.voiceId = voiceId; }
        public boolean accepted() { return error == null; }
        static Decision reject(String code) { return new Decision(code, null, 0, null); }
    }
    private OpenRules() { }

    /**
     * @param language the validated session language
     * @param systemReady a local, offline Vietnamese system voice is installed
     * @param packReady a verified AI voice pack is READY
     * @param packVoices voice ids in the installed pack (empty when none)
     * @param defaultRate local default user rate from AniSub settings
     * @throws JSONException for wrongly typed or out-of-range optional fields (MALFORMED)
     */
    public static Decision decide(JSONObject open, String language, boolean systemReady, boolean packReady,
                                  Set<String> packVoices, float defaultRate) throws JSONException {
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
        if (MODE_SYSTEM.equals(mode)) {
            boolean systemTest = Boolean.TRUE.equals(open.opt("systemTest"));
            if (!systemTest || !"vi".equals(language) || !systemReady) return Decision.reject("UNAVAILABLE");
            return new Decision(null, MODE_SYSTEM, rate, null);
        }
        if (MODE_AI.equals(mode)) {
            if (!"vi".equals(language)) return Decision.reject("UNSUPPORTED");
            if (!packReady) return Decision.reject(VOICE_PACK_MISSING);
            if (voice != null && !packVoices.contains(voice)) return Decision.reject("UNSUPPORTED");
            return new Decision(null, MODE_AI, rate, voice);
        }
        return Decision.reject("UNSUPPORTED");
    }
}
