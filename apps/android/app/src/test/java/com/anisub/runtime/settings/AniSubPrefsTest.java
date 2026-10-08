package com.anisub.runtime.settings;

import java.util.HashMap;
import java.util.Map;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class AniSubPrefsTest {
    private AniSubPrefs prefs(Map<String, String> values) {
        return new AniSubPrefs(new AniSubPrefs.Store() {
            public String getString(String key, String def) { return values.containsKey(key) ? values.get(key) : def; }
            public void putString(String key, String value) { values.put(key, value); }
            public void remove(String key) { values.remove(key); }
        });
    }
    @Test public void capturedSettingsDoNotChangeDuringAnActiveReading() {
        AniSubPrefs p = prefs(new HashMap<>());
        p.setVoiceRate("first", 1.2f); p.setVoicePitch("first", 2); p.setVoiceVolume("first", 75);
        p.setStyle("calm"); p.setGap("long"); p.setPauseMs(300);
        AniSubPrefs.Snapshot old = p.snapshot("first");
        p.setVoiceRate("first", .8f); p.setVoicePitch("first", -3); p.setVoiceVolume("first", 0);
        p.setStyle("lively"); p.setGap("short"); p.setPauseMs(1000);
        assertEquals(1.2f, old.rate, .001f); assertEquals(2, old.pitch); assertEquals(75, old.volume);
        assertEquals("calm", old.style); assertEquals("long", old.gap); assertEquals(300, old.pauseMs);
        assertEquals("lively", p.snapshot("first").style);
    }
    @Test public void defaultsStaySeparatedByKindAndLanguage() {
        AniSubPrefs p = prefs(new HashMap<>());
        p.setDefaultVoice("AI", "vi", "ai-vi"); p.setDefaultVoice("SYSTEM", "vi", "system-vi");
        p.setDefaultVoice("AI", "en", "ai-en");
        assertEquals("ai-vi", p.defaultVoice("vi")); assertEquals("system-vi", p.defaultVoice("SYSTEM", "vi"));
        assertEquals("ai-en", p.defaultVoice("AI", "en")); assertNull(p.defaultVoice("SYSTEM", "en"));
    }
    @Test public void corruptPreferencesCannotEscapeSynthesisBounds() {
        Map<String, String> values = new HashMap<>();
        values.put("voice.x.rate", "Infinity"); values.put("voice.x.pitch", "999");
        values.put("voice.x.volume", "-100"); values.put("style", "invalid");
        values.put("gap", "invalid"); values.put("pauseMs", "99999");
        AniSubPrefs.Snapshot s = prefs(values).snapshot("x");
        assertEquals(1.3f, s.rate, .001f); assertEquals(3, s.pitch); assertEquals(0, s.volume);
        assertEquals("normal", s.style); assertEquals("normal", s.gap); assertEquals(1000, s.pauseMs);
    }
    @Test public void perVoiceStyleInheritsGlobalAndRemainsSnapshotted() {
        Map<String, String> values = new HashMap<>();
        AniSubPrefs p = prefs(values);
        p.setStyle("calm"); p.setVoiceStyle("first", "lively");
        AniSubPrefs.Snapshot captured = p.snapshot("first");
        assertEquals("lively", captured.style); assertEquals("calm", p.snapshot("other").style);
        p.setVoiceStyle("first", "normal"); p.setStyle("lively");
        assertEquals("lively", captured.style); assertEquals("normal", p.snapshot("first").style);
        values.put("voice.first.style", "corrupt");
        assertEquals("lively", p.snapshot("first").style);
        p.setVoiceStyle("first", null);
        assertFalse(values.containsKey("voice.first.style"));
        assertEquals("lively", p.snapshot("first").style);
    }
    @Test public void duckDefaultAndBoundsArePersisted() {
        Map<String, String> values = new HashMap<>(); AniSubPrefs p = prefs(values);
        assertEquals(30, p.duckLevel());
        p.setDuckLevel(0); assertEquals(10, p.duckLevel());
        p.setDuckLevel(99); assertEquals(60, p.duckLevel());
        values.put("duckLevel", "broken"); assertEquals(30, p.duckLevel());
    }
    @Test public void priorityMovesAtBothEndsAndSurvivesReload() {
        Map<String, String> values = new HashMap<>(); AniSubPrefs p = prefs(values);
        p.movePriority("vi", -1); assertEquals(Arrays.asList("vi", "en", "ja"), p.languagePriority());
        p.movePriority("en", -1); assertEquals(Arrays.asList("en", "vi", "ja"), prefs(values).languagePriority());
        p.movePriority("ja", 1); p.movePriority("missing", -1);
        assertEquals(Arrays.asList("en", "vi", "ja"), p.languagePriority());
        p.movePriority("en", 1); assertEquals(Arrays.asList("vi", "en", "ja"), p.languagePriority());
    }
}
