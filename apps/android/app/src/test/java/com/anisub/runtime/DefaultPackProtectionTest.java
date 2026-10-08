package com.anisub.runtime;

import com.anisub.runtime.ai.AiSpeechEngine;
import com.anisub.runtime.settings.AniSubPrefs;
import com.anisub.runtime.voice.VoiceCatalog;
import com.anisub.runtime.voice.VoiceRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** 0.4.0 rules: the bootstrap VAIS pack is permanent; system voices never read as an empty list while initializing; no needless model reload. */
public class DefaultPackProtectionTest {
    private static AniSubPrefs prefs() {
        final Map<String, String> values = new HashMap<>();
        return new AniSubPrefs(new AniSubPrefs.Store() {
            public String getString(String key, String def) { return values.containsKey(key) ? values.get(key) : def; }
            public void putString(String key, String value) { values.put(key, value); }
            public void remove(String key) { values.remove(key); }
        });
    }
    private static VoiceCatalog catalog() throws Exception {
        return VoiceCatalog.parse(new String(Files.readAllBytes(Paths.get("src/main/assets/voice-catalog.json")), StandardCharsets.UTF_8));
    }

    @Test public void defaultVaisPackCanNeverBeDeletedOrDisabledEvenWithCakeInstalled() throws Exception {
        VoiceCatalog catalog = catalog();
        VoiceRegistry registry = new VoiceRegistry(catalog, id -> true, new ArrayList<VoiceRegistry.SystemVoice>(), prefs());
        String vais = catalog.defaultPack().id;
        assertEquals(vais, registry.protectedPackId());
        assertFalse("another installed Vietnamese pack must not make VAIS deletable", registry.canDeletePack(vais));
        assertFalse(registry.canDisable("vais1000"));
        VoiceCatalog.Pack cake = catalog.forVoice("cake-quang-huy");
        assertNotNull(cake);
        assertTrue(registry.canDeletePack(cake.id));
        assertTrue(registry.canDisable("cake-quang-huy"));
        assertTrue(registry.canDeletePack(catalog.forLanguage("en").id));
    }

    @Test public void defaultPackStaysProtectedWhenUserDefaultIsCake() throws Exception {
        VoiceCatalog catalog = catalog(); AniSubPrefs p = prefs();
        p.setDefaultVoice("AI", "vi", "cake-quang-huy");
        VoiceRegistry registry = new VoiceRegistry(catalog, id -> true, new ArrayList<VoiceRegistry.SystemVoice>(), p);
        assertEquals("cake-quang-huy", registry.defaultVoice(VoiceRegistry.Kind.AI, "vi").id);
        assertFalse(registry.canDeletePack(catalog.defaultPack().id));
        assertTrue(SettingsActivity.defaultPackIds(registry).contains(catalog.defaultPack().id));
    }

    @Test public void systemVoicesAreOmittedNotEmptyWhileInitializing() throws Exception {
        VoiceRegistry registry = new VoiceRegistry(catalog(), id -> false, new ArrayList<VoiceRegistry.SystemVoice>(), prefs());
        JSONObject legacy = Capabilities.build(false, "missing", null, "IDLE", "0.4.0", 5);
        JSONObject pending = Capabilities.withVoiceMetadata(legacy, registry, "initializing");
        assertFalse("no systemVoices: [] before the TTS inventory is known", pending.has("systemVoices"));
        assertEquals("initializing", pending.getString("systemVoicesState"));
        JSONObject unavailable = Capabilities.withVoiceMetadata(legacy, registry, "unavailable");
        assertFalse(unavailable.has("systemVoices"));
        JSONObject ready = Capabilities.withVoiceMetadata(legacy, registry, "ready");
        assertTrue(ready.has("systemVoices"));
        assertEquals(0, ready.getJSONArray("systemVoices").length());
        assertEquals("ready", ready.getString("systemVoicesState"));
        assertTrue("legacy fields untouched", pending.has("voicePack") && pending.has("voices") && pending.has("aiVoice"));
    }

    @Test public void sameVoiceAndPresetIsNotReloaded() {
        AniSubPrefs.Snapshot loaded = new AniSubPrefs.Snapshot(1f, 0, 100, 0, "normal", "normal");
        AniSubPrefs.Snapshot rateOnly = new AniSubPrefs.Snapshot(1.2f, 0, 100, 0, "normal", "normal");
        AniSubPrefs.Snapshot pitched = new AniSubPrefs.Snapshot(1f, 2, 100, 0, "normal", "normal");
        assertTrue(AiSpeechEngine.reusable(AiSpeechEngine.State.READY, "vi", "cake-quang-huy", loaded, "vi", "cake-quang-huy", loaded));
        assertTrue("still loading counts as the same load", AiSpeechEngine.reusable(AiSpeechEngine.State.LOADING, "vi", "cake-quang-huy", loaded, "vi", "cake-quang-huy", loaded));
        assertTrue("rate is applied per utterance, not at load", AiSpeechEngine.reusable(AiSpeechEngine.State.READY, "vi", "vais1000", loaded, "vi", "vais1000", rateOnly));
        assertFalse(AiSpeechEngine.reusable(AiSpeechEngine.State.READY, "vi", "vais1000", loaded, "vi", "cake-quang-huy", loaded));
        assertFalse("pitch is baked into the synthesizer", AiSpeechEngine.reusable(AiSpeechEngine.State.READY, "vi", "vais1000", loaded, "vi", "vais1000", pitched));
        assertFalse(AiSpeechEngine.reusable(AiSpeechEngine.State.READY, "vi", "vais1000", loaded, "en", "vais1000", loaded));
        assertFalse(AiSpeechEngine.reusable(AiSpeechEngine.State.IDLE, null, null, null, "vi", "vais1000", loaded));
        assertFalse(AiSpeechEngine.reusable(AiSpeechEngine.State.FAILED, "vi", "vais1000", loaded, "vi", "vais1000", loaded));
    }
}
