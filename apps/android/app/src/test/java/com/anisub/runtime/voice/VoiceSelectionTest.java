package com.anisub.runtime.voice;

import com.anisub.runtime.models.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import static org.junit.Assert.*;

/** Minor 2 voice packs: one pack per voice language, selected by voiceLang, sharing one store and one espeak dir. */
public class VoiceSelectionTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    static VoiceCatalog bundled() throws Exception {
        return VoiceCatalog.parse(new String(Files.readAllBytes(Paths.get("src/main/assets/voice-catalog.json")), StandardCharsets.UTF_8));
    }

    @Test public void bundledCatalogHasViAndEnPacks() throws Exception {
        VoiceCatalog c = bundled();
        VoiceCatalog.Pack vi = c.forLanguage("vi"), en = c.forLanguage("en");
        assertSame("minor-1 default stays Vietnamese", vi, c.defaultPack());
        assertEquals("vi-vais1000-medium", vi.id);
        assertEquals("voices-v1", vi.release);
        assertEquals("en-ljspeech-medium", en.id);
        assertEquals("voices-en-v1", en.release);
        assertEquals(22050, en.sampleRate);
        assertEquals("en_US-ljspeech-medium.onnx", en.model);
        assertTrue(en.totalBytes > 60_000_000 && en.totalBytes < 70_000_000);
        for (VoiceCatalog.PackFile f : en.files) for (String u : f.urls)
            assertTrue(u, u.startsWith("https://github.com/LongLeo287/AniSub/releases/download/voices-en-v1/ljspeech-"));
        assertNull(c.forLanguage("fr"));
        assertEquals("en", en.manifest().languages.get(0));
    }

    /** EspeakData relies on it: every pack ships the same espeak-ng-data files, byte for byte. */
    @Test public void packsShareIdenticalEspeakData() throws Exception {
        VoiceCatalog c = bundled();
        Map<String, String> vi = new HashMap<>(), en = new HashMap<>();
        for (VoiceCatalog.PackFile f : c.forLanguage("vi").files) if (f.path.startsWith("espeak-ng-data/")) vi.put(f.path, f.sha256);
        for (VoiceCatalog.PackFile f : c.forLanguage("en").files) if (f.path.startsWith("espeak-ng-data/")) en.put(f.path, f.sha256);
        assertEquals(8, vi.size());
        assertEquals(vi, en);
        assertTrue("English voice data", vi.containsKey("espeak-ng-data/en_dict") && vi.containsKey("espeak-ng-data/lang/gmw/en"));
        assertTrue("Vietnamese voice data", vi.containsKey("espeak-ng-data/vi_dict") && vi.containsKey("espeak-ng-data/lang/aav/vi"));
    }

    String twoPackCatalog(Map<String, byte[]> files) throws Exception {
        JSONArray packs = new JSONArray();
        for (String lang : new String[]{"vi", "en"}) {
            JSONArray fs = new JSONArray();
            for (Map.Entry<String, byte[]> e : files.entrySet())
                fs.put(new JSONObject().put("path", e.getKey()).put("bytes", e.getValue().length).put("sha256", VoicePackManagerTest.sha(e.getValue()))
                        .put("license", "test").put("urls", new JSONArray().put(VoicePackManagerTest.BASE + e.getKey().replace('/', '.'))));
            packs.put(new JSONObject().put("id", lang + "-test").put("version", "1").put("name", lang).put("language", lang)
                    .put("engine", "sherpa-onnx-vits").put("sampleRate", 22050).put("license", "MIT").put("licenseUrl", "https://example.org/l")
                    .put("attribution", "test").put("model", "model.onnx").put("tokens", "tokens.txt").put("dataDir", "espeak-ng-data")
                    .put("voices", new JSONArray().put(new JSONObject().put("id", lang + "-voice").put("name", "V").put("speakerId", 0)))
                    .put("files", fs));
        }
        return new JSONObject().put("schemaVersion", 1).put("packs", packs).toString();
    }

    @Test public void eachLanguageHasItsOwnManagerOnOneStore() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("model.onnx", VoicePackManagerTest.bytes(70_000, 7));
        files.put("tokens.txt", "_ 0\n".getBytes(StandardCharsets.UTF_8));
        files.put("espeak-ng-data/phontab", VoicePackManagerTest.bytes(500, 8));
        VoiceCatalog catalog = VoiceCatalog.parse(twoPackCatalog(files));
        VoicePackManagerTest.FakeHttp http = new VoicePackManagerTest.FakeHttp();
        http.serve(files);
        File root = temp.newFolder().getCanonicalFile();
        ModelStore store = new ModelStore(root, new StorageBudget(StorageBudget.DEFAULT_QUOTA), new IntegrityVerifier(), System::currentTimeMillis, p -> { });
        VoicePackManager vi = new VoicePackManager(store, catalog, "vi-test", http, null, s -> { });
        VoicePackManager en = new VoicePackManager(store, catalog, "en-test", http, null, s -> { });
        assertEquals("en-test", en.pack().id);
        assertTrue(en.download(true));
        long deadline = System.currentTimeMillis() + 10000;
        while (en.busy() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals(VoicePackManager.State.READY, en.status().state);
        assertEquals("en-test", en.status().pack.id);
        assertEquals("en", en.installed().manifest().languages.get(0));
        assertEquals("the Vietnamese pack is independent", VoicePackManager.State.NONE, vi.status().state);
        assertNull(vi.installed());
        assertNull(en.delete());
        assertEquals(VoicePackManager.State.NONE, en.status().state);
    }

    @Test public void espeakDataIsCopiedVerifiedAndShared() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("model.onnx", VoicePackManagerTest.bytes(100, 1));
        files.put("tokens.txt", "_ 0\n".getBytes(StandardCharsets.UTF_8));
        files.put("espeak-ng-data/phontab", VoicePackManagerTest.bytes(500, 2));
        files.put("espeak-ng-data/lang/aav/vi", VoicePackManagerTest.bytes(50, 3));
        VoiceCatalog catalog = VoiceCatalog.parse(twoPackCatalog(files));
        File pack = temp.newFolder("pack");
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            File f = new File(pack, e.getKey()); f.getParentFile().mkdirs(); Files.write(f.toPath(), e.getValue());
        }
        File shared = new File(temp.getRoot(), "espeak-ng-data");
        assertEquals(shared, EspeakData.prepare(shared, pack, catalog.forLanguage("vi")));
        assertArrayEquals(files.get("espeak-ng-data/lang/aav/vi"), Files.readAllBytes(new File(shared, "lang/aav/vi").toPath()));
        assertFalse("only espeak files", new File(shared, "model.onnx").exists());
        // The other language's pack (same files) reuses the shared copy; a tampered pack is refused.
        EspeakData.prepare(shared, pack, catalog.forLanguage("en"));
        Files.write(new File(shared, "phontab").toPath(), new byte[]{1, 2, 3});
        EspeakData.prepare(shared, pack, catalog.forLanguage("en"));
        assertArrayEquals("a damaged shared file is replaced", files.get("espeak-ng-data/phontab"), Files.readAllBytes(new File(shared, "phontab").toPath()));
        Files.write(new File(pack, "espeak-ng-data/phontab").toPath(), new byte[]{9});
        new File(shared, "phontab").delete();
        try { EspeakData.prepare(shared, pack, catalog.forLanguage("vi")); fail("corrupt pack file accepted"); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("CORRUPT")); }
    }
}
