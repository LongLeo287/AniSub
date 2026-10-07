package com.anisub.runtime;

import com.anisub.runtime.voice.VoiceCatalog;
import com.anisub.runtime.voice.VoicePackManager;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import static org.junit.Assert.*;

/** Major 1 / minor 1 contract: caller trust, OPEN mode rules and CAPABILITIES fields. */
public class ContractTest {
    // ------------------------------------------------------------------ caller trust
    static CallerPolicy.Packages pm(Map<Integer, String[]> uids, boolean sameSigner) {
        return new CallerPolicy.Packages() {
            public String[] packagesForUid(int uid) { return uids.get(uid); }
            public boolean sameSignerAsSelf(String pkg) { return sameSigner && CallerPolicy.CLIENT_PACKAGE.equals(pkg); }
        };
    }

    @Test public void rightPackageAndCertificateAcceptedWithoutAnyPermission() {
        Map<Integer, String[]> uids = new HashMap<>();
        uids.put(10123, new String[]{"com.anibox.tv"});
        // Install order AniBox -> AniSub means BIND is never granted; trust must not depend on it.
        assertTrue(CallerPolicy.trusted(10123, pm(uids, true)));
    }

    @Test public void wrongPackageOrCertificateRejected() {
        Map<Integer, String[]> uids = new HashMap<>();
        uids.put(10123, new String[]{"com.anibox.tv"});
        uids.put(10200, new String[]{"com.evil.app"});
        uids.put(10201, new String[]{"com.anibox.tv.debug"});
        uids.put(10202, new String[]{"com.anibox.tv.fake", "com.other"});
        assertFalse("same package name, foreign certificate", CallerPolicy.trusted(10123, pm(uids, false)));
        assertFalse(CallerPolicy.trusted(10200, pm(uids, true)));
        assertFalse(CallerPolicy.trusted(10201, pm(uids, true)));
        assertFalse(CallerPolicy.trusted(10202, pm(uids, true)));
        assertFalse("unknown uid", CallerPolicy.trusted(99999, pm(uids, true)));
        assertFalse(CallerPolicy.trusted(-1, pm(uids, true)));
        assertFalse(CallerPolicy.trusted(10123, null));
        CallerPolicy.Packages throwing = new CallerPolicy.Packages() {
            public String[] packagesForUid(int uid) { return new String[]{"com.anibox.tv"}; }
            public boolean sameSignerAsSelf(String pkg) { throw new SecurityException("package hidden"); }
        };
        assertFalse("lookup failure fails closed", CallerPolicy.trusted(10123, throwing));
    }

    static CallerPolicy.Packages signers(Map<Integer, String[]> uids, Set<String> sameSigner) {
        return new CallerPolicy.Packages() {
            public String[] packagesForUid(int uid) { return uids.get(uid); }
            public boolean sameSignerAsSelf(String pkg) { return sameSigner.contains(pkg); }
        };
    }

    @Test public void debugClientAcceptedOnlyByDebugBuildsWithSameSigner() {
        Map<Integer, String[]> uids = new HashMap<>();
        uids.put(10300, new String[]{"com.anibox.tv.debug"});
        uids.put(10123, new String[]{"com.anibox.tv"});
        Set<String> both = new HashSet<>(Arrays.asList("com.anibox.tv", "com.anibox.tv.debug"));
        assertFalse("release builds stay exact com.anibox.tv", CallerPolicy.trusted(10300, signers(uids, both), false));
        assertFalse(CallerPolicy.trusted(10300, signers(uids, both)));
        assertTrue("debug build pairs with debug AniBox", CallerPolicy.trusted(10300, signers(uids, both), true));
        assertTrue(CallerPolicy.trusted(10123, signers(uids, both), true));
        assertFalse("debug package with a foreign certificate", CallerPolicy.trusted(10300,
                signers(uids, new HashSet<>(Collections.singletonList("com.anibox.tv"))), true));
        assertEquals(Collections.singletonList("com.anibox.tv"), CallerPolicy.clientPackages(false));
    }

    @Test public void signingConfigKeepsV1AndV2() throws Exception {
        String gradle = new String(Files.readAllBytes(Paths.get("build.gradle")), StandardCharsets.UTF_8);
        assertTrue(gradle.contains("debug { enableV1Signing true; enableV2Signing true }"));
        // Both the debug and the optional release signing config enable v1 + v2.
        assertEquals(3, gradle.split("enableV1Signing true; enableV2Signing true", -1).length);
    }

    @Test public void manifestDoesNotRequireBindPermissionOnServiceOrActivity() throws Exception {
        String manifest = new String(Files.readAllBytes(Paths.get("src/main/AndroidManifest.xml")), StandardCharsets.UTF_8);
        int service = manifest.indexOf("<service"), end = manifest.indexOf("/>", service);
        assertFalse(manifest.substring(service, end).contains("android:permission"));
        int activity = manifest.indexOf("<activity"), activityEnd = manifest.indexOf(">", activity);
        assertFalse(manifest.substring(activity, activityEnd).contains("android:permission"));
        assertTrue(manifest.contains("com.anisub.runtime.action.SETTINGS"));
        assertTrue(manifest.contains("LEANBACK_LAUNCHER"));
    }

    // ------------------------------------------------------------------ OPEN modes
    static final Set<String> VOICES = new HashSet<>(Collections.singletonList("vais1000"));
    static OpenRules.Decision open(String json, boolean systemReady, boolean packReady) throws JSONException {
        return OpenRules.decide(new JSONObject(json), "vi", systemReady, packReady, packReady ? VOICES : Collections.<String>emptySet(), 1.0f);
    }

    @Test public void absentModeIsLegacySystemTest() throws Exception {
        OpenRules.Decision d = open("{\"systemTest\":true}", true, false);
        assertTrue(d.accepted()); assertEquals("system", d.mode);
        assertEquals("UNAVAILABLE", open("{}", true, true).error);
        assertEquals("UNAVAILABLE", open("{\"systemTest\":true}", false, true).error);
        assertEquals("UNAVAILABLE", OpenRules.decide(new JSONObject("{\"systemTest\":true}"), "en", true, true, VOICES, 1f).error);
    }

    @Test public void systemModeKeepsSystemTestRule() throws Exception {
        assertTrue(open("{\"mode\":\"system\",\"systemTest\":true}", true, false).accepted());
        assertEquals("UNAVAILABLE", open("{\"mode\":\"system\"}", true, false).error);
    }

    @Test public void aiModeNeedsReadyPackAndNeverFallsBackToSystem() throws Exception {
        assertEquals("VOICE_PACK_MISSING", open("{\"mode\":\"ai\"}", true, false).error);
        assertEquals("VOICE_PACK_MISSING", open("{\"mode\":\"ai\",\"systemTest\":true}", true, false).error);
        OpenRules.Decision d = open("{\"mode\":\"ai\"}", false, true);
        assertTrue(d.accepted()); assertEquals("ai", d.mode); assertEquals(1.0f, d.rate, 0); assertNull(d.voiceId);
        assertEquals("UNSUPPORTED", OpenRules.decide(new JSONObject("{\"mode\":\"ai\"}"), "en", true, true, VOICES, 1f).error);
    }

    @Test public void rateAndVoiceAreValidated() throws Exception {
        assertEquals(0.8f, open("{\"mode\":\"ai\",\"rate\":0.8}", false, true).rate, 1e-6);
        assertEquals(1.3f, open("{\"mode\":\"ai\",\"rate\":1.3}", false, true).rate, 1e-6);
        assertEquals("vais1000", open("{\"mode\":\"ai\",\"voice\":\"vais1000\"}", false, true).voiceId);
        assertEquals("UNSUPPORTED", open("{\"mode\":\"ai\",\"voice\":\"nam-mien-nam\"}", false, true).error);
        assertEquals("UNSUPPORTED", open("{\"mode\":\"dub\"}", true, true).error);
        for (String bad : new String[]{"{\"mode\":\"ai\",\"rate\":0.79}", "{\"mode\":\"ai\",\"rate\":1.31}",
                "{\"mode\":\"ai\",\"rate\":\"1.0\"}", "{\"mode\":1}", "{\"mode\":\"ai\",\"voice\":5}"}) {
            try { open(bad, true, true); fail("MALFORMED expected for " + bad); } catch (JSONException expected) { }
        }
        // Default rate comes from AniSub settings when OPEN omits it.
        assertEquals(1.1f, OpenRules.decide(new JSONObject("{\"mode\":\"ai\"}"), "vi", false, true, VOICES, 1.1f).rate, 1e-6);
    }

    // ------------------------------------------------------------------ CAPABILITIES
    static VoiceCatalog bundled() throws Exception {
        return VoiceCatalog.parse(new String(Files.readAllBytes(Paths.get("src/main/assets/voice-catalog.json")), StandardCharsets.UTF_8));
    }

    @Test public void capabilitiesKeepMinor0FieldsAndAddMinor1() throws Exception {
        VoiceCatalog.Pack pack = bundled().defaultPack();
        VoicePackManager.Status none = new VoicePackManager.Status(VoicePackManager.State.NONE, pack, null, 0, pack.totalBytes, null, false);
        JSONObject c = Capabilities.build(true, "local-vietnamese-ready", none, "IDLE", "0.2.0", 2);
        assertEquals("CAPABILITIES", c.getString("type"));
        assertEquals(1, c.getInt("major")); assertEquals(1, c.getInt("minor")); assertEquals(1, c.getInt("protocolMajor"));
        assertTrue(c.getBoolean("directText")); assertTrue(c.getBoolean("tts")); assertTrue(c.getBoolean("offline"));
        assertFalse(c.getBoolean("aiVoice")); assertFalse(c.getBoolean("translation")); assertFalse(c.getBoolean("multiSpeaker"));
        assertEquals("android-system-tts", c.getString("engine"));
        JSONObject vp = c.getJSONObject("voicePack");
        assertEquals("NONE", vp.getString("state"));
        assertEquals("vi-vais1000-medium", vp.getString("id"));
        assertEquals(pack.name, vp.getString("name"));
        assertEquals(pack.totalBytes, vp.getLong("sizeBytes"));
        assertEquals("1", vp.getString("version"));
        assertEquals(0, vp.getJSONArray("voices").length());
        assertEquals("0.2.0", c.getJSONObject("runtime").getString("versionName"));
        assertEquals(2, c.getJSONObject("runtime").getLong("versionCode"));
        assertEquals(0.8, c.getJSONObject("rate").getDouble("min"), 0);
        assertEquals(1.3, c.getJSONObject("rate").getDouble("max"), 0);
        assertEquals("[\"system\"]", c.getJSONArray("modes").toString());
    }

    @Test public void aiVoiceTrueOnlyWhenPackReady() throws Exception {
        VoiceCatalog.Pack pack = bundled().defaultPack();
        for (VoicePackManager.State s : VoicePackManager.State.values()) {
            VoicePackManager.Status st = new VoicePackManager.Status(s, pack, s == VoicePackManager.State.READY ? "1" : null, 10, pack.totalBytes,
                    s == VoicePackManager.State.ERROR ? "CORRUPT" : null, false);
            JSONObject c = Capabilities.build(false, "local-vietnamese-missing", st, "IDLE", "0.2.0", 2);
            assertEquals(s.name(), s == VoicePackManager.State.READY, c.getBoolean("aiVoice"));
            assertEquals(s.name(), c.getJSONObject("voicePack").getString("state"));
            assertEquals(s == VoicePackManager.State.READY, c.getBoolean("offline"));
            if (s == VoicePackManager.State.DOWNLOADING) assertEquals(10, c.getJSONObject("voicePack").getLong("doneBytes"));
            if (s == VoicePackManager.State.ERROR) assertEquals("CORRUPT", c.getJSONObject("voicePack").getString("error"));
            if (s == VoicePackManager.State.READY) {
                assertEquals("[\"ai\"]", c.getJSONArray("modes").toString());
                assertEquals("vais1000", c.getJSONObject("voicePack").getJSONArray("voices").getJSONObject(0).getString("id"));
            }
        }
        JSONObject broken = Capabilities.build(true, "x", null, "FAILED", "0.2.0", 2);
        assertFalse(broken.getBoolean("aiVoice"));
        assertEquals("ERROR", broken.getJSONObject("voicePack").getString("state"));
        assertTrue("fits the 16,384-unit wire limit", broken.toString().length() < 16384);
    }
}
