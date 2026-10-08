package com.anisub.runtime;

import com.anisub.runtime.settings.AniSubPrefs;
import com.anisub.runtime.voice.VoiceCatalog;
import com.anisub.runtime.voice.VoiceRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Bulk cleanup must preserve effective AI defaults, including fallback and shared-model voices. */
public class SettingsStorageRulesTest {
    private AniSubPrefs prefs() {
        Map<String, String> values = new HashMap<>();
        return new AniSubPrefs(new AniSubPrefs.Store() {
            public String getString(String key, String def) { return values.containsKey(key) ? values.get(key) : def; }
            public void putString(String key, String value) { values.put(key, value); }
            public void remove(String key) { values.remove(key); }
        });
    }
    private VoiceCatalog catalog() throws Exception {
        JSONObject root = new JSONObject(new String(Files.readAllBytes(Paths.get("src/main/assets/voice-catalog.json")), StandardCharsets.UTF_8));
        JSONObject alternate = new JSONObject(root.getJSONArray("packs").getJSONObject(0).toString());
        alternate.put("id", "vi-alternate");
        alternate.getJSONArray("voices").getJSONObject(0).put("id", "alternate-voice");
        root.getJSONArray("packs").put(alternate);
        return VoiceCatalog.parse(root.toString());
    }
    @Test public void bulkCleanupKeepsAiDefaultsForEachLanguageAndNotSystemSelections() throws Exception {
        VoiceCatalog catalog = catalog(); AniSubPrefs prefs = prefs();
        prefs.setDefaultVoice("AI", "vi", "alternate-voice");
        VoiceRegistry.SystemVoice system = new VoiceRegistry.SystemVoice("engine", "local", "vi", true);
        prefs.setDefaultVoice("SYSTEM", "vi", system.id);
        VoiceRegistry registry = new VoiceRegistry(catalog, id -> true, Arrays.asList(system), prefs);
        Set<String> kept = SettingsActivity.defaultPackIds(registry);
        assertTrue(kept.contains("vi-alternate"));
        assertTrue(kept.contains(catalog.forLanguage("en").id));
        assertFalse(kept.contains(catalog.defaultPack().id));
        assertEquals(2, kept.size());
    }
    @Test public void missingOrDisabledDefaultKeepsEffectiveInstalledFallback() throws Exception {
        VoiceCatalog catalog = catalog(); AniSubPrefs prefs = prefs();
        prefs.setDefaultVoice("AI", "vi", "alternate-voice"); prefs.setVoiceEnabled("alternate-voice", false);
        VoiceRegistry registry = new VoiceRegistry(catalog, id -> !"vi-alternate".equals(id), Collections.emptyList(), prefs);
        assertTrue(SettingsActivity.defaultPackIds(registry).contains(catalog.defaultPack().id));
        assertFalse(SettingsActivity.defaultPackIds(registry).contains("vi-alternate"));
    }
}
