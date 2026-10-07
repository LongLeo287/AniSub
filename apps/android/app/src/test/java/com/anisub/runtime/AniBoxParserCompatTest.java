package com.anisub.runtime;

import com.anisub.runtime.voice.VoiceCatalog;
import com.anisub.runtime.voice.VoicePackManager;
import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

/**
 * Every CAPABILITIES AniSub can send must pass AniBox main's minor-1 parser
 * (AniBox app/src/main/java/com/anibox/tv/anisub/AniSubCapabilities.parse + AniSubProtocol helpers,
 * read 07-10-2026). The rules are copied here because AniSub must not depend on AniBox code; when
 * AniBox's parser changes, update this copy. A reply failing these rules makes AniBox report
 * INVALID_REPLY and drop the connection.
 */
public class AniBoxParserCompatTest {
    static final String ENGINE_SYSTEM_TTS = "android-system-tts";
    static final int MAX_ID_UNITS = 80, MAX_PAYLOAD_UNITS = 16384;

    /** AniSubProtocol.integer: a JSON integer written as plain digits, else null. */
    static Long integer(String json, JSONObject o, String key) throws Exception {
        if (!o.has(key)) return null;
        Object v = o.opt(key);
        if (!(v instanceof Number)) return null;
        // org.json writes Integer/Long as plain digits and Double with a fraction; check the wire form.
        String raw = JSONObject.numberToString((Number) v);
        int start = raw.startsWith("-") ? 1 : 0;
        if (raw.length() == start || raw.length() > start + 19) return null;
        for (int i = start; i < raw.length(); i++) if (raw.charAt(i) < '0' || raw.charAt(i) > '9') return null;
        return Long.parseLong(raw);
    }
    static String string(JSONObject o, String key) { Object v = o.opt(key); return v instanceof String ? (String) v : null; }
    static boolean bool(JSONObject o, String key) {
        Object v = o.opt(key);
        if (!(v instanceof Boolean)) throw new IllegalArgumentException("boolean required: " + key);
        return (Boolean) v;
    }

    /** AniSubCapabilities.parse, plus the pieces AniBox reads afterwards (aiReady consistency). */
    static void anibox(String payload) throws Exception {
        assertTrue("payload fits AniBox's limit", payload.length() <= MAX_PAYLOAD_UNITS);
        JSONObject reply = new JSONObject(payload);
        if (!"CAPABILITIES".equals(string(reply, "type")) || !Long.valueOf(1).equals(integer(payload, reply, "major"))
                || !Long.valueOf(1).equals(integer(payload, reply, "protocolMajor"))) throw new IllegalArgumentException("not a major-1 CAPABILITIES");
        String engine = string(reply, "engine");
        if (engine == null || engine.length() > MAX_ID_UNITS) throw new IllegalArgumentException("engine");
        int minor = 0;
        if (reply.has("minor")) {
            Long value = integer(payload, reply, "minor");
            if (value == null || value < 0 || value > 1000) throw new IllegalArgumentException("minor");
            minor = (int) (long) value;
        }
        boolean translation = bool(reply, "translation"), multiSpeaker = bool(reply, "multiSpeaker"), aiVoice = bool(reply, "aiVoice");
        bool(reply, "directText"); bool(reply, "tts"); bool(reply, "offline");
        if (ENGINE_SYSTEM_TTS.equals(engine)) {
            if (translation || multiSpeaker) throw new IllegalArgumentException("system TTS must not claim AI");
            if (minor == 0 && aiVoice) throw new IllegalArgumentException("minor-0 system TTS must not claim AI");
        }
        // Fields AniBox silently blanks when wrong: AniSub must send them well-formed so the UI shows them.
        String state = string(reply, "state");
        assertTrue("state <= 80", state != null && state.length() <= MAX_ID_UNITS);
        JSONObject pack = reply.optJSONObject("voicePack");
        assertNotNull("voicePack object", pack);
        String ps = string(pack, "state");
        assertTrue("voicePack.state known: " + ps, java.util.Arrays.asList("NONE", "DOWNLOADING", "VERIFYING", "READY", "ERROR").contains(ps));
        for (String k : new String[]{"id", "name", "version"}) if (pack.has(k)) assertTrue("voicePack." + k + " <= 80", string(pack, k) != null && string(pack, k).length() <= MAX_ID_UNITS);
        if (pack.has("sizeBytes")) assertNotNull("voicePack.sizeBytes integer", integer(payload, pack, "sizeBytes"));
        JSONObject runtime = reply.optJSONObject("runtime");
        assertNotNull(runtime);
        assertTrue(string(runtime, "versionName").length() <= MAX_ID_UNITS);
        assertNotNull("runtime.versionCode integer", integer(payload, runtime, "versionCode"));
        // aiReady(): aiVoice must agree with a READY Vietnamese pack (AniBox never guesses).
        assertEquals("aiVoice <=> voicePack READY", aiVoice, "READY".equals(ps));
    }

    static VoicePackManager.Status st(VoiceCatalog.Pack p, VoicePackManager.State s, String error) {
        return new VoicePackManager.Status(s, p, s == VoicePackManager.State.READY ? p.version : null, 5, p.totalBytes, error, false);
    }

    @Test public void everyCapabilitiesVariantPassesAniBoxMainParser() throws Exception {
        VoiceCatalog c = VoiceCatalog.parse(new String(Files.readAllBytes(Paths.get("src/main/assets/voice-catalog.json")), StandardCharsets.UTF_8));
        int checked = 0;
        for (VoicePackManager.State vi : VoicePackManager.State.values()) for (VoicePackManager.State en : VoicePackManager.State.values())
            for (boolean translate : new boolean[]{true, false}) for (boolean system : new boolean[]{true, false}) {
                Map<String, VoicePackManager.Status> packs = new LinkedHashMap<>();
                packs.put("vi", st(c.forLanguage("vi"), vi, vi == VoicePackManager.State.ERROR ? "CORRUPT" : null));
                packs.put("en", st(c.forLanguage("en"), en, null));
                Map<String, String> models = new LinkedHashMap<>();
                models.put("vi", "ready"); models.put("en", "ready"); models.put("ja", "downloading");
                Capabilities.TranslateInfo info = translate ? new Capabilities.TranslateInfo(true, null, true, models)
                        : Capabilities.TranslateInfo.none("NO_DOWNLOAD_MANAGER");
                anibox(Capabilities.build(system, system ? "local-vietnamese-ready" : "local-vietnamese-missing", packs, info, "READY", "0.3.0", 3).toString());
                checked++;
            }
        assertEquals(100, checked);
        Map<String, VoicePackManager.Status> broken = new LinkedHashMap<>();
        broken.put("vi", null); broken.put("en", null);
        anibox(Capabilities.build(false, "x", broken, Capabilities.TranslateInfo.none("NO_ENGINE"), "FAILED", "0.3.0", 3).toString());
    }

    @Test public void fixturePassesAniBoxMainParser() throws Exception {
        anibox(new String(Files.readAllBytes(ProtocolMinor2Test.repoFile("tests/fixtures/android-v1/capabilities-minor2.json")), StandardCharsets.UTF_8));
    }

    @Test public void theParserCopyRejectsWhatAniBoxRejects() throws Exception {
        String good = "{\"type\":\"CAPABILITIES\",\"major\":1,\"protocolMajor\":1,\"minor\":2,\"engine\":\"android-system-tts\",\"state\":\"s\","
                + "\"directText\":true,\"tts\":false,\"offline\":false,\"aiVoice\":false,\"translation\":false,\"multiSpeaker\":false,"
                + "\"voicePack\":{\"state\":\"NONE\"},\"runtime\":{\"versionName\":\"0.3.0\",\"versionCode\":3}}";
        anibox(good);
        for (String bad : new String[]{good.replace("\"translation\":false", "\"translation\":true"),
                good.replace("\"multiSpeaker\":false", "\"multiSpeaker\":true"), good.replace("\"major\":1", "\"major\":1.5"),
                good.replace("\"tts\":false", "\"tts\":0"), good.replace("\"minor\":2", "\"minor\":2000")}) {
            try { anibox(bad); fail("AniBox would reject: " + bad); } catch (IllegalArgumentException expected) { }
        }
    }
}
