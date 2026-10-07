package com.anisub.runtime;

import com.anisub.runtime.translate.LanguageTags;
import com.anisub.runtime.voice.VoiceCatalog;
import com.anisub.runtime.voice.VoicePackManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import static org.junit.Assert.*;

/** Protocol major 1 / minor 2: OPEN voiceLang + translation admission, CAPABILITIES fields, minor-1 compatibility. */
public class ProtocolMinor2Test {
    static Path repoFile(String rel) {
        Path root = Paths.get("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve(rel))) root = root.getParent();
        assertNotNull("repository file " + rel, root);
        return root.resolve(rel);
    }

    static Set<String> set(JSONObject o, String key) throws JSONException {
        Set<String> out = new HashSet<>();
        JSONArray a = o.optJSONArray(key);
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        return out;
    }

    static OpenRules.Env env(JSONObject e) throws JSONException {
        final Set<String> packs = set(e, "packs"), models = set(e, "models");
        final boolean system = e.optBoolean("system"), translate = e.optBoolean("translate"), detect = e.optBoolean("detect");
        final String reason = e.optString("reason", "NO_ENGINE");
        return new OpenRules.Env() {
            public boolean systemReady() { return system; }
            public boolean packReady(String l) { return packs.contains(l); }
            public Set<String> packVoices(String l) { return packs.contains(l) ? Collections.singleton("vi".equals(l) ? "vais1000" : "ljspeech") : Collections.<String>emptySet(); }
            public boolean translateAvailable() { return translate; }
            public String translateUnavailableReason() { return translate ? null : reason; }
            public boolean modelReady(String l) { return "en".equals(l) || models.contains(l); }
            public boolean detectAvailable() { return translate && detect; }
        };
    }

    @Test public void openCasesFixture() throws Exception {
        JSONObject root = new JSONObject(new String(Files.readAllBytes(repoFile("tests/fixtures/android-v1/open-cases.json")), StandardCharsets.UTF_8));
        JSONArray cases = root.getJSONArray("cases");
        assertTrue(cases.length() >= 15);
        for (int i = 0; i < cases.length(); i++) {
            JSONObject c = cases.getJSONObject(i);
            String name = c.getString("name");
            JSONObject expect = c.getJSONObject("expect");
            OpenRules.Decision d;
            try {
                d = OpenRules.decide(c.getJSONObject("message"), c.getString("language"), env(c.getJSONObject("env")), 1f);
            } catch (JSONException malformed) {
                assertEquals(name, "MALFORMED", expect.optString("error"));
                continue;
            }
            if (expect.has("error")) {
                assertEquals(name, expect.getString("error"), d.error);
                JSONObject detail = expect.optJSONObject("detail");
                if (detail != null) for (Iterator<String> k = detail.keys(); k.hasNext(); ) {
                    String key = k.next();
                    assertEquals(name + "." + key, detail.get(key).toString(), d.detail.get(key).toString());
                }
            } else {
                assertTrue(name + " accepted, got " + d.error, d.accepted());
                assertEquals(name, expect.getString("mode"), d.mode);
                assertEquals(name, expect.getString("voiceLang"), d.voiceLang);
                assertEquals(name, expect.getBoolean("translate"), d.translate);
                if (expect.has("source")) assertEquals(name, expect.getString("source"), d.source);
            }
        }
    }

    @Test public void minor1ClientsDecideExactlyAsBefore() throws Exception {
        // A minor-1 client never sends voiceLang and always sends language "vi": same outcomes as 1.1.
        Set<String> voices = Collections.singleton("vais1000");
        for (boolean packReady : new boolean[]{true, false}) for (boolean systemReady : new boolean[]{true, false}) {
            OpenRules.Decision ai = OpenRules.decide(new JSONObject("{\"mode\":\"ai\"}"), "vi", systemReady, packReady, voices, 1f);
            assertEquals(packReady ? null : "VOICE_PACK_MISSING", ai.error);
            if (packReady) { assertEquals("vi", ai.voiceLang); assertFalse(ai.translate); }
            OpenRules.Decision sys = OpenRules.decide(new JSONObject("{\"systemTest\":true}"), "vi", systemReady, packReady, voices, 1f);
            assertEquals(systemReady ? null : "UNAVAILABLE", sys.error);
        }
        // Explicit voiceLang "vi" == absent.
        assertTrue(OpenRules.decide(new JSONObject("{\"mode\":\"ai\",\"voiceLang\":\"vi\"}"), "vi", false, true, voices, 1f).accepted());
        assertTrue(OpenRules.decide(new JSONObject("{\"mode\":\"ai\",\"voiceLang\":null}"), "vi", false, true, voices, 1f).accepted());
    }

    @Test public void sessionLanguageAcceptsBcp47() {
        assertTrue(PayloadRules.session(0, 1, "ja"));
        assertTrue(PayloadRules.session(0, 1, "zh-Hant"));
        assertTrue(PayloadRules.session(0, 1, "und"));
        assertFalse(PayloadRules.session(0, 1, "japanese"));
        assertFalse(PayloadRules.session(-1, 1, "vi"));
        assertFalse(PayloadRules.session(0, 3, "vi"));
        // The legacy 1.0 rule is unchanged for its gate.
        assertFalse(PayloadRules.clock(0, 1, "ja"));
    }

    static VoiceCatalog bundled() throws Exception {
        return VoiceCatalog.parse(new String(Files.readAllBytes(Paths.get("src/main/assets/voice-catalog.json")), StandardCharsets.UTF_8));
    }

    static VoicePackManager.Status st(VoiceCatalog.Pack p, VoicePackManager.State s) {
        return new VoicePackManager.Status(s, p, s == VoicePackManager.State.READY ? p.version : null, 1234, p.totalBytes, null, false);
    }

    @Test public void capabilitiesMinor2() throws Exception {
        VoiceCatalog c = bundled();
        Map<String, VoicePackManager.Status> packs = new LinkedHashMap<>();
        packs.put("vi", st(c.forLanguage("vi"), VoicePackManager.State.READY));
        packs.put("en", st(c.forLanguage("en"), VoicePackManager.State.DOWNLOADING));
        Map<String, String> models = new LinkedHashMap<>();
        models.put("vi", "ready"); models.put("en", "ready"); models.put("ja", "downloading"); models.put("ko", "missing");
        JSONObject caps = Capabilities.build(false, "local-vietnamese-missing", packs, new Capabilities.TranslateInfo(true, null, true, models), "IDLE", "0.3.0", 3);
        assertEquals(2, caps.getInt("minor"));
        // Minor-1 fields keep their meaning (the Vietnamese pack).
        assertTrue(caps.getBoolean("aiVoice"));
        assertEquals("READY", caps.getJSONObject("voicePack").getString("state"));
        assertEquals("[\"ai\"]", caps.getJSONArray("modes").toString());
        assertFalse("legacy field stays false (AniBox parser)", caps.getBoolean("translation"));
        // Minor 2.
        JSONObject vi = caps.getJSONObject("voices").getJSONObject("vi"), en = caps.getJSONObject("voices").getJSONObject("en");
        assertEquals("ready", vi.getString("state")); assertEquals("vi-vais1000-medium", vi.getString("id"));
        assertEquals("vais1000", vi.getJSONArray("voices").getJSONObject(0).getString("id"));
        assertEquals("downloading", en.getString("state")); assertEquals("en-ljspeech-medium", en.getString("id"));
        assertEquals(1234, en.getLong("doneBytes")); assertEquals(0, en.getJSONArray("voices").length());
        JSONObject tr = caps.getJSONObject("translate");
        assertEquals("mlkit", tr.getString("engine")); assertTrue(tr.getBoolean("available")); assertTrue(tr.getBoolean("detect"));
        assertEquals("ready", tr.getJSONObject("models").getString("vi"));
        assertEquals("downloading", tr.getJSONObject("models").getString("ja"));
        assertFalse(tr.has("reason"));
        assertEquals(4096, caps.getJSONObject("timeline").getInt("maxCues"));
        assertTrue(caps.toString().length() < 16384);
        // Progress ticks do not change the signature; a state change does.
        String sig = Capabilities.signature(caps);
        packs.put("en", new VoicePackManager.Status(VoicePackManager.State.DOWNLOADING, c.forLanguage("en"), null, 999_999, 1, null, false));
        assertEquals(sig, Capabilities.signature(Capabilities.build(false, "local-vietnamese-missing", packs, new Capabilities.TranslateInfo(true, null, true, models), "IDLE", "0.3.0", 3)));
        packs.put("en", st(c.forLanguage("en"), VoicePackManager.State.READY));
        assertNotEquals(sig, Capabilities.signature(Capabilities.build(false, "local-vietnamese-missing", packs, new Capabilities.TranslateInfo(true, null, true, models), "IDLE", "0.3.0", 3)));
    }

    @Test public void capabilitiesWithoutEngineOrStore() throws Exception {
        Map<String, VoicePackManager.Status> packs = new LinkedHashMap<>();
        packs.put("vi", null); packs.put("en", null);
        JSONObject caps = Capabilities.build(false, "x", packs, Capabilities.TranslateInfo.none("NO_DOWNLOAD_MANAGER"), "FAILED", "0.3.0", 3);
        assertEquals("ERROR", caps.getJSONObject("voicePack").getString("state"));
        assertEquals("missing", caps.getJSONObject("voices").getJSONObject("en").getString("state"));
        JSONObject tr = caps.getJSONObject("translate");
        assertFalse(tr.getBoolean("available"));
        assertEquals("NO_DOWNLOAD_MANAGER", tr.getString("reason"));
        assertEquals("ready", tr.getJSONObject("models").getString("en"));
        assertEquals("missing", tr.getJSONObject("models").getString("vi"));
        assertFalse(caps.getBoolean("translation"));
        assertEquals("[]", caps.getJSONArray("modes").toString());
    }

    @Test public void capabilitiesFixtureMatchesBuilder() throws Exception {
        JSONObject fixture = new JSONObject(new String(Files.readAllBytes(repoFile("tests/fixtures/android-v1/capabilities-minor2.json")), StandardCharsets.UTF_8));
        VoiceCatalog c = bundled();
        Map<String, VoicePackManager.Status> packs = new LinkedHashMap<>();
        packs.put("vi", st(c.forLanguage("vi"), VoicePackManager.State.READY));
        packs.put("en", st(c.forLanguage("en"), VoicePackManager.State.NONE));
        Map<String, String> models = new LinkedHashMap<>();
        for (String l : LanguageTags.OFFERED) models.put(l, "en".equals(l) || "vi".equals(l) ? "ready" : "missing");
        JSONObject built = Capabilities.build(true, "local-vietnamese-ready", packs, new Capabilities.TranslateInfo(true, null, true, models), "IDLE", "0.3.0", 3);
        // Same keys and values for every field the fixture documents.
        assertSameShape("", fixture, built);
    }

    static void assertSameShape(String path, Object expected, Object actual) throws JSONException {
        if (expected instanceof JSONObject) {
            assertTrue(path + " is an object", actual instanceof JSONObject);
            JSONObject e = (JSONObject) expected, a = (JSONObject) actual;
            for (Iterator<String> k = e.keys(); k.hasNext(); ) { String key = k.next(); assertTrue(path + "." + key + " present", a.has(key)); assertSameShape(path + "." + key, e.get(key), a.get(key)); }
            for (Iterator<String> k = a.keys(); k.hasNext(); ) { String key = k.next(); assertTrue(path + "." + key + " documented in the fixture", e.has(key)); }
        } else if (expected instanceof JSONArray) {
            assertEquals(path, expected.toString(), actual.toString());
        } else if (expected instanceof Number && actual instanceof Number) {
            assertEquals(path, ((Number) expected).doubleValue(), ((Number) actual).doubleValue(), 0);
        } else {
            assertEquals(path, String.valueOf(expected), String.valueOf(actual));
        }
    }
}
