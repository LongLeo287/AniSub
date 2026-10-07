package com.anisub.runtime.voice;

import com.anisub.runtime.models.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Catalog pinning, SHA-256 verification, atomic install, recovery, retries and resume. */
public class VoicePackManagerTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    static final String BASE = "https://github.com/LongLeo287/AniSub/releases/download/voices-v1/";

    static String sha(byte[] b) throws Exception {
        StringBuilder s = new StringBuilder(); for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) s.append(String.format("%02x", x & 255)); return s.toString();
    }
    static byte[] bytes(int n, int seed) { byte[] b = new byte[n]; for (int i = 0; i < n; i++) b[i] = (byte) (i * 31 + seed); return b; }

    final Map<String, byte[]> files = new LinkedHashMap<>();
    {
        files.put("model.onnx", bytes(1_500_000, 1)); // > 2 progress steps
        files.put("tokens.txt", "_ 0\n^ 1\n".getBytes(StandardCharsets.UTF_8));
        files.put("espeak-ng-data/phontab", bytes(5000, 2));
        files.put("espeak-ng-data/lang/aav/vi", bytes(111, 3));
    }

    String catalogJson(String version, Map<String, byte[]> content) throws Exception {
        JSONArray fs = new JSONArray();
        for (Map.Entry<String, byte[]> e : content.entrySet())
            fs.put(new JSONObject().put("path", e.getKey()).put("bytes", e.getValue().length).put("sha256", sha(e.getValue()))
                    .put("license", "test").put("urls", new JSONArray().put(BASE + e.getKey().replace('/', '.'))));
        JSONObject pack = new JSONObject().put("id", "vi-test").put("version", version).put("name", "Test").put("language", "vi")
                .put("engine", "sherpa-onnx-vits").put("sampleRate", 22050).put("license", "MIT").put("licenseUrl", "https://example.org/l")
                .put("attribution", "test").put("model", "model.onnx").put("tokens", "tokens.txt").put("dataDir", "espeak-ng-data")
                .put("voices", new JSONArray().put(new JSONObject().put("id", "v0").put("name", "V0").put("speakerId", 0)))
                .put("files", fs);
        return new JSONObject().put("schemaVersion", 1).put("packs", new JSONArray().put(pack)).toString();
    }

    /** Serves `served` bytes (may differ from the catalog); supports Range and scripted failures. */
    static final class FakeHttp implements HttpSource {
        final Map<String, byte[]> served = new HashMap<>();
        final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger failuresLeft = new AtomicInteger();
        volatile int breakAfterBytes = -1; volatile boolean honourRange = true; volatile CountDownLatch slow;
        public Response open(String url, long offset) throws IOException {
            requests.add(url.substring(url.lastIndexOf('/') + 1) + "@" + offset);
            if (failuresLeft.getAndDecrement() > 0) throw new IOException("HTTP_503");
            byte[] all = served.get(url.substring(BASE.length()));
            if (all == null) throw new IOException("HTTP_404");
            long from = honourRange ? offset : 0;
            byte[] body = Arrays.copyOfRange(all, (int) from, all.length);
            InputStream in = new ByteArrayInputStream(body);
            final int cut = breakAfterBytes; breakAfterBytes = -1;
            final CountDownLatch gate = slow;
            InputStream stream = new FilterInputStream(in) {
                int sent;
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    if (gate != null) try { gate.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new IOException(e); }
                    if (cut >= 0 && sent >= cut) throw new IOException("connection reset");
                    int n = super.read(b, off, cut >= 0 ? Math.min(len, cut - sent) : Math.min(len, 4096));
                    if (n > 0) sent += n; return n;
                }
            };
            return new Response(stream, body.length, honourRange && offset > 0, null);
        }
        void serve(Map<String, byte[]> m) { for (Map.Entry<String, byte[]> e : m.entrySet()) served.put(e.getKey().replace('/', '.'), e.getValue()); }
    }

    final FakeHttp http = new FakeHttp();
    final BlockingQueue<VoicePackManager.Status> statuses = new LinkedBlockingQueue<>();
    File root;
    ModelStore store;

    VoicePackManager manager(String catalog, VoicePackManager.SmokeTest smoke) throws Exception {
        if (root == null) root = temp.newFolder().getCanonicalFile();
        store = new ModelStore(root, new StorageBudget(StorageBudget.DEFAULT_QUOTA), new IntegrityVerifier(), System::currentTimeMillis, p -> { });
        return new VoicePackManager(store, VoiceCatalog.parse(catalog), http, smoke, statuses::add);
    }

    /** Waits until the single download worker reaches a terminal state. */
    VoicePackManager.Status settle(VoicePackManager m) throws Exception {
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            VoicePackManager.Status now = m.status();
            if (now.state != VoicePackManager.State.DOWNLOADING && now.state != VoicePackManager.State.VERIFYING) return now;
            Thread.sleep(20);
        }
        fail("download did not settle"); return null;
    }

    @Test public void bundledCatalogIsPinnedAndValid() throws Exception {
        VoiceCatalog c = VoiceCatalog.parse(new String(Files.readAllBytes(Paths.get("src/main/assets/voice-catalog.json")), StandardCharsets.UTF_8));
        VoiceCatalog.Pack p = c.defaultPack();
        assertEquals("vi-vais1000-medium", p.id);
        assertEquals(22050, p.sampleRate);
        assertTrue(p.totalBytes > 60_000_000 && p.totalBytes < 70_000_000);
        for (VoiceCatalog.PackFile f : p.files) {
            assertTrue(f.sha256.matches("[0-9a-f]{64}"));
            for (String u : f.urls) assertTrue(u, u.startsWith("https://github.com/LongLeo287/AniSub/releases/download/voices-v1/"));
        }
        assertNotNull(p.manifest());
    }

    @Test public void catalogRejectsUnsafeEntries() throws Exception {
        String good = catalogJson("1", files);
        VoiceCatalog.parse(good);
        String[][] mutations = {
                {BASE, "http://github.com/LongLeo287/AniSub/releases/download/voices-v1/"},
                {BASE, "https://evil.example.com/"},
                {"\"model.onnx\"", "\"../model.onnx\""},
                {"\"tokens.txt\"", "\"/etc/tokens.txt\""},
                {"\"espeak-ng-data/phontab\"", "\"espeak-ng-data/lib.so\""},
                {"\"language\":\"vi\"", "\"language\":\"fr\""}, // only vi / en voices (minor 2)
        };
        for (String[] m : mutations) {
            String bad = good.replace(m[0], m[1]);
            assertNotEquals(good, bad);
            try { VoiceCatalog.parse(bad); fail("must reject " + m[1]); } catch (JSONException expected) { }
        }
        try { VoiceCatalog.parse(good.replaceFirst("[0-9a-f]{64}", "zz")); fail("bad digest"); } catch (JSONException expected) { }
    }

    @Test public void noConsentDoesNoNetwork() throws Exception {
        VoicePackManager m = manager(catalogJson("1", files), null);
        assertFalse(m.download(false));
        assertTrue(http.requests.isEmpty());
        assertEquals(VoicePackManager.State.NONE, m.status().state);
    }

    @Test public void downloadVerifiesInstallsAndReverifiesBeforeLoad() throws Exception {
        http.serve(files);
        List<File> smoked = new ArrayList<>();
        VoicePackManager m = manager(catalogJson("1", files), (dir, pack) -> { smoked.add(dir); return null; });
        assertTrue(m.download(true));
        assertFalse("one download at a time", m.download(true));
        VoicePackManager.Status s = settle(m);
        assertEquals(VoicePackManager.State.READY, s.state);
        assertEquals("1", s.installedVersion);
        assertEquals(1, smoked.size());
        assertTrue(new File(smoked.get(0), "espeak-ng-data/lang/aav/vi").isFile());
        assertTrue(store.orphanStages().isEmpty());
        try (LoadLease lease = m.acquire()) { assertTrue(lease.permitsPrepare()); }
        // A byte flipped after install is caught by the re-verification before native load.
        File model = new File(m.installed().directory(), "model.onnx");
        try (RandomAccessFile raf = new RandomAccessFile(model, "rw")) { raf.seek(1000); int b = raf.read(); raf.seek(1000); raf.write(b ^ 0xFF); }
        try { m.acquire().close(); fail("corrupted pack must not load"); }
        catch (ModelStore.StoreException e) { assertEquals("MODEL_CORRUPT", e.error().name()); }
        assertNotEquals(VoicePackManager.State.READY, m.status().state);
    }

    @Test public void progressIsPublishedDuringDownload() throws Exception {
        http.serve(files);
        VoicePackManager m = manager(catalogJson("1", files), null);
        m.download(true);
        settle(m);
        long last = -1; int downloading = 0;
        for (VoicePackManager.Status s : statuses) if (s.state == VoicePackManager.State.DOWNLOADING) {
            assertTrue(s.doneBytes >= last); last = s.doneBytes; downloading++;
        }
        assertTrue("intermediate progress events: " + downloading, downloading >= 3);
        assertTrue(last > 0);
    }

    @Test public void corruptPackCanBeDeletedOrRedownloaded() throws Exception {
        http.serve(files);
        VoicePackManager m = manager(catalogJson("1", files), null);
        m.download(true); settle(m);
        File model = new File(m.installed().directory(), "model.onnx");
        try (RandomAccessFile raf = new RandomAccessFile(model, "rw")) { raf.seek(10); int b = raf.read(); raf.seek(10); raf.write(b ^ 0xFF); }
        try { m.acquire().close(); fail(); } catch (ModelStore.StoreException expected) { }
        assertEquals(VoicePackManager.State.ERROR, m.status().state);
        // Re-download of the same id/version must replace the corrupt registration.
        m.download(true);
        VoicePackManager.Status s = settle(m);
        assertEquals(s.error, VoicePackManager.State.READY, s.state);
        try (LoadLease lease = m.acquire()) { assertTrue(lease.permitsPrepare()); }
        // And a corrupt pack can also be deleted outright.
        model = new File(m.installed().directory(), "model.onnx");
        try (RandomAccessFile raf = new RandomAccessFile(model, "rw")) { raf.seek(10); int b = raf.read(); raf.seek(10); raf.write(b ^ 0xFF); }
        try { m.acquire().close(); fail(); } catch (ModelStore.StoreException expected) { }
        assertNull(m.delete());
        assertEquals(VoicePackManager.State.NONE, m.status().state);
        assertTrue(store.registry().versions().isEmpty());
    }

    @Test public void completePartialFileIsVerifiedNotRequested() throws Exception {
        http.serve(files);
        http.breakAfterBytes = files.get("model.onnx").length; // connection drops right at EOF
        VoicePackManager m = manager(catalogJson("1", files), null);
        m.download(true);
        assertEquals(VoicePackManager.State.READY, settle(m).state);
        for (String r : http.requests) assertFalse(r, r.startsWith("model.onnx@") && !r.equals("model.onnx@0"));
    }

    @Test public void previousVersionKeptUntilRestartAfterNativeUse() throws Exception {
        http.serve(files);
        VoicePackManager m = manager(catalogJson("1", files), null);
        m.download(true); settle(m);
        Map<String, byte[]> v2 = new LinkedHashMap<>(files); v2.put("model.onnx", bytes(1_500_000, 9));
        http.serve(v2);
        VoicePackManager m2 = manager(catalogJson("2", v2), null);
        m2.setKeepPreviousUntilRestart(true);
        m2.download(true);
        assertEquals("2", settle(m2).installedVersion);
        assertEquals("old directory kept for the running native engine", 2, store.registry().versions().size());
        VoicePackManager restarted = manager(catalogJson("2", v2), null);
        assertEquals("2", restarted.status().installedVersion);
        assertEquals("superseded version removed at next start", 1, store.registry().versions().size());
    }

    @Test public void hashMismatchIsRejectedAndNothingInstalled() throws Exception {
        Map<String, byte[]> tampered = new LinkedHashMap<>(files);
        byte[] model = files.get("model.onnx").clone(); model[5] ^= 1; tampered.put("model.onnx", model);
        http.serve(tampered);
        VoicePackManager m = manager(catalogJson("1", files), null);
        m.download(true);
        VoicePackManager.Status s = settle(m);
        assertEquals(VoicePackManager.State.ERROR, s.state);
        assertEquals(VoicePackManager.E_CORRUPT, s.error);
        assertNull(m.installed());
        assertTrue("staging cleaned", store.orphanStages().isEmpty());
    }

    @Test public void transientFailuresRetryAtMostTwice() throws Exception {
        http.serve(files);
        http.failuresLeft.set(2);
        VoicePackManager m = manager(catalogJson("1", files), null);
        m.download(true);
        assertEquals(VoicePackManager.State.READY, settle(m).state);

        FakeHttp failing = http; failing.failuresLeft.set(100); failing.requests.clear();
        root = null; statuses.clear();
        VoicePackManager m2 = manager(catalogJson("1", files), null);
        m2.download(true);
        VoicePackManager.Status s = settle(m2);
        assertEquals(VoicePackManager.State.ERROR, s.state);
        assertEquals(VoicePackManager.E_NETWORK, s.error);
        assertEquals("first try + 2 retries", VoicePackManager.MAX_ATTEMPTS, failing.requests.size());
    }

    @Test public void interruptedTransferResumesFromVerifiedOffset() throws Exception {
        http.serve(files);
        http.breakAfterBytes = 100_000;
        VoicePackManager m = manager(catalogJson("1", files), null);
        m.download(true);
        assertEquals(VoicePackManager.State.READY, settle(m).state);
        assertTrue(http.requests.toString(), http.requests.contains("model.onnx@100000"));
    }

    @Test public void serverIgnoringRangeRestartsCleanly() throws Exception {
        http.serve(files);
        http.breakAfterBytes = 100_000;
        http.honourRange = false;
        VoicePackManager m = manager(catalogJson("1", files), null);
        m.download(true);
        assertEquals(VoicePackManager.State.READY, settle(m).state);
    }

    @Test public void cancelLeavesNothingInstalled() throws Exception {
        http.serve(files);
        http.slow = new CountDownLatch(1);
        VoicePackManager m = manager(catalogJson("1", files), null);
        m.download(true);
        Thread.sleep(100);
        m.cancel();
        http.slow.countDown();
        VoicePackManager.Status s = settle(m);
        assertEquals(VoicePackManager.State.NONE, s.state);
        assertNull(s.error);
        assertNull(m.installed());
        assertTrue(store.orphanStages().isEmpty());
    }

    @Test public void failedUpdateKeepsPreviousVersionUsable() throws Exception {
        http.serve(files);
        VoicePackManager m = manager(catalogJson("1", files), null);
        m.download(true);
        assertEquals(VoicePackManager.State.READY, settle(m).state);
        // Catalog v2 announces new bytes, but the server still delivers the old ones.
        Map<String, byte[]> v2 = new LinkedHashMap<>(files); v2.put("model.onnx", bytes(1_500_000, 9));
        statuses.clear();
        VoicePackManager m2 = manager(catalogJson("2", v2), null);
        assertTrue(m2.status().updateAvailable);
        m2.download(true);
        VoicePackManager.Status s = settle(m2);
        assertEquals(VoicePackManager.State.READY, s.state);
        assertEquals("1", s.installedVersion);
        assertEquals(VoicePackManager.E_CORRUPT, s.error);
        // Successful update replaces the old version.
        http.serve(v2);
        statuses.clear();
        m2.download(true);
        s = settle(m2);
        assertEquals("2", s.installedVersion);
        assertEquals(1, store.registry().versions().size());
    }

    @Test public void smokeFailureRemovesIncompatiblePack() throws Exception {
        http.serve(files);
        VoicePackManager m = manager(catalogJson("1", files), (dir, pack) -> "INCOMPATIBLE");
        m.download(true);
        VoicePackManager.Status s = settle(m);
        assertEquals(VoicePackManager.State.ERROR, s.state);
        assertEquals(VoicePackManager.E_INCOMPATIBLE, s.error);
        assertTrue(store.registry().versions().isEmpty());
    }

    @Test public void deleteRespectsActiveLease() throws Exception {
        http.serve(files);
        VoicePackManager m = manager(catalogJson("1", files), null);
        m.download(true); settle(m);
        LoadLease lease = m.acquire();
        assertEquals(VoicePackManager.E_IN_USE, m.delete());
        lease.close();
        assertNull(m.delete());
        assertEquals(VoicePackManager.State.NONE, m.status().state);
    }

    @Test public void crashDuringDownloadRecoversOnRestart() throws Exception {
        VoicePackManager m = manager(catalogJson("1", files), null);
        File stage = store.createStage();
        try (FileOutputStream out = new FileOutputStream(new File(stage, "model.onnx.part"))) { out.write(new byte[1234]); }
        // Process dies here. A new process opens the same private store.
        VoicePackManager restarted = manager(catalogJson("1", files), null);
        assertEquals(VoicePackManager.State.NONE, restarted.status().state);
        assertFalse("orphan stage reclaimed", stage.exists());
        assertTrue(store.orphanStages().isEmpty());
        http.serve(files);
        restarted.download(true);
        assertEquals(VoicePackManager.State.READY, settle(restarted).state);
        // And an installed pack survives a restart as READY.
        VoicePackManager again = manager(catalogJson("1", files), null);
        assertEquals(VoicePackManager.State.READY, again.status().state);
    }
}
