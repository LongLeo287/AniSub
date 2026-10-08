package com.anisub.runtime.voice;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class CatalogRepositoryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private String bundledJson() throws Exception {
        return new JSONObject(new String(Files.readAllBytes(Paths.get("src/main/assets/voice-catalog.json")), StandardCharsets.UTF_8))
                .put("catalogVersion",1).toString();
    }
    private String update(int version) throws Exception {
        return new JSONObject(bundledJson()).put("catalogVersion", version).toString();
    }
    @Test public void malformedAnswerReleasesSlotAndRetainsCurrent() throws Exception {
        VoiceCatalog baseline = VoiceCatalog.parse(bundledJson());
        CatalogRepository repo = new CatalogRepository(temp.newFolder(), baseline);
        long token = repo.begin(); assertEquals(-1, repo.begin());
        try { repo.accept(token, "{"); fail(); } catch (Exception expected) { }
        assertSame(baseline, repo.current()); assertFalse(repo.busy());
        assertTrue(repo.accept(repo.begin(), update(2)));
    }
    @Test public void remoteAdmissionPinsReviewedBytesNotJustValidJson() throws Exception {
        String reviewed=new String(Files.readAllBytes(Paths.get("src/main/assets/voice-catalog.json")),StandardCharsets.UTF_8).replace("\r","");
        assertTrue(CatalogRepository.trustedRemote(reviewed));
        assertFalse(CatalogRepository.trustedRemote(reviewed+" "));
        assertFalse(CatalogRepository.trustedRemote(update(99)));
    }
    @Test public void staleCompletionCannotReleaseNewOperation() throws Exception {
        CatalogRepository repo = new CatalogRepository(temp.newFolder(), VoiceCatalog.parse(bundledJson()));
        long old = repo.begin(); repo.cancel(); long current = repo.begin();
        assertFalse(repo.accept(old, update(2))); repo.failed(old);
        assertTrue(repo.busy()); assertTrue(repo.accept(current, update(2)));
    }
    @Test public void failedRenameRestoresLastGoodAcrossRestart() throws Exception {
        File directory = temp.newFolder(); VoiceCatalog baseline = VoiceCatalog.parse(bundledJson());
        CatalogRepository first = new CatalogRepository(directory, baseline);
        assertTrue(first.accept(first.begin(), update(2)));
        CatalogRepository failing = new CatalogRepository(directory, baseline,
                (from, to) -> !from.getName().equals("catalog.pending") && from.renameTo(to));
        try { failing.accept(failing.begin(), update(3)); fail(); } catch (Exception expected) { }
        assertFalse(failing.busy()); assertEquals(2, failing.current().catalogVersion);
        assertEquals(2, new CatalogRepository(directory, baseline).current().catalogVersion);
        assertFalse(new File(directory, "catalog.pending").exists());
    }
    @Test public void downgradeAndImmutableRewriteAreRejected() throws Exception {
        CatalogRepository repo = new CatalogRepository(temp.newFolder(), VoiceCatalog.parse(bundledJson()));
        assertTrue(repo.accept(repo.begin(), update(2)));
        try { repo.accept(repo.begin(), update(2)); fail(); } catch (Exception expected) { }
        JSONObject altered = new JSONObject(update(3));
        altered.getJSONArray("packs").getJSONObject(0).put("sampleRate", 16000);
        try { repo.accept(repo.begin(), altered.toString()); fail(); } catch (Exception expected) { }
        assertEquals(2, repo.current().catalogVersion); assertFalse(repo.busy());
    }
}
