package com.anisub.runtime.models;

import com.anisub.runtime.protocol.RuntimeError;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import static org.junit.Assert.*;

public class ModelStoreTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private long now;
    private ModelStore store(File root, ModelStore.Faults faults) throws IOException {
        return new ModelStore(root, new StorageBudget(StorageBudget.DEFAULT_QUOTA),
                new IntegrityVerifier(), () -> now, faults);
    }
    private ModelManifest manifest(String id, String version, byte[] data) throws Exception {
        return new ModelManifest.Builder(id, version).assets(Arrays.asList(new ModelManifest.Asset(
                "weights.bin", data.length, hex(MessageDigest.getInstance("SHA-256").digest(data)),
                "weights", "test-license", "https://example.invalid/license"))).build();
    }
    private static String hex(byte[] data) {
        StringBuilder out = new StringBuilder();
        for (byte b : data) out.append(String.format("%02x", b & 255));
        return out.toString();
    }
    private void put(File dir, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(new File(dir, "weights.bin"))) { out.write(data); }
    }
    private ModelVersion install(ModelStore store, String id, String version) throws Exception {
        byte[] data = {1, 2, 3}; File stage = store.createStage(); put(stage, data);
        return store.install(stage, manifest(id, version, data));
    }
    private void denied(RuntimeError error, IOCall call) throws Exception {
        try { call.run(); fail("operation must be denied: " + error); }
        catch (ModelStore.StoreException ex) { assertEquals(error, ex.error()); }
    }
    private interface IOCall { void run() throws Exception; }

    @Test public void recoveryAndLease() throws Exception {
        File root = temp.newFolder("store"); ModelStore store = store(root, p -> {});
        byte[] good = {1, 2, 3}; File stage = store.createStage(); put(stage, new byte[]{1, 2, 4});
        denied(RuntimeError.MODEL_CORRUPT, () -> store.install(stage, manifest("voice", "bad", good)));
        put(stage, new byte[]{1, 2});
        denied(RuntimeError.MODEL_CORRUPT, () -> store.install(stage, manifest("voice", "bad", good)));
        ModelVersion old = install(store, "voice", "v1");
        assertEquals(ModelVersion.State.INSTALLED, old.state());
        denied(RuntimeError.MODEL_MISSING, () -> store.acquireReady(old));
        LoadLease lease = store.acquire(old); assertTrue(lease.permitsPrepare());
        assertEquals(RemovalResult.MODEL_IN_USE, store.remove(old));
        store.markReady(lease); assertEquals(ModelVersion.State.READY, store.registry().find("voice", "v1").state());
        assertNotNull(store.registry().lastKnownGood("voice"));
        lease.close(); lease.close(); assertEquals(0, store.leaseCount(old));
        now = 59_999; store.unloadIdle(); assertEquals(ModelVersion.State.READY, store.registry().find("voice", "v1").state());
        now = 60_000; store.unloadIdle(); assertEquals(ModelVersion.State.INSTALLED, store.registry().find("voice", "v1").state());
        ModelStore crashed = store(root, p -> { if (p.equals("AFTER_VERSION_RENAME")) throw new IOException("simulated crash"); });
        File next = crashed.createStage(); put(next, good);
        try { crashed.install(next, manifest("voice", "v2", good)); fail("crash must interrupt promotion"); }
        catch (IOException expected) { assertEquals("simulated crash", expected.getMessage()); }
        ModelStore recovered = store(root, p -> {});
        assertNotNull(recovered.registry().find("voice", "v1")); assertNull(recovered.registry().find("voice", "v2"));
        assertEquals("last known good survives", old.directory(), recovered.registry().lastKnownGood("voice").directory());
        assertFalse(recovered.orphanStages().isEmpty());
        for (int i = 1; i < 64; i++) install(recovered, "other" + i, "v1");
        assertEquals(64, recovered.registry().versions().size());
        denied(RuntimeError.BACKPRESSURE, () -> install(recovered, "overflow", "v1"));
    }

    @Test public void changedAfterVerification() throws Exception {
        File root = temp.newFolder("store"); ModelStore store = store(root, p -> {});
        ModelVersion v = install(store, "voice", "v1"); put(v.directory(), new byte[]{1, 2, 4});
        denied(RuntimeError.MODEL_CORRUPT, () -> store.acquire(v)); assertEquals(0, store.leaseCount(v));
        assertEquals(ModelVersion.State.CORRUPT, store.registry().find("voice", "v1").state());
        File unrelated = temp.newFolder("unrelated"); put(unrelated, new byte[]{9});
        denied(RuntimeError.MODEL_CORRUPT, () -> store.install(unrelated, manifest("foreign", "v1", new byte[]{9})));
        assertTrue(new File(unrelated, "weights.bin").exists());
        File stage = store.createStage();
        // java.nio is test-only. Windows directory symlink may require Developer Mode/admin;
        // directory junctions provide equivalent canonical redirection without that privilege.
        File link = new File(stage, "weights.bin");
        boolean linked = false;
        try { Files.createSymbolicLink(link.toPath(), new File(unrelated, "weights.bin").toPath()); linked = true; }
        catch (IOException | UnsupportedOperationException ex) {
            File junction = new File(stage, "escape");
            if (System.getProperty("os.name").startsWith("Windows")) {
                Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", junction.getPath(), unrelated.getPath()).start();
                assertEquals("junction fixture creation", 0, p.waitFor());
                assertFalse(new IntegrityVerifier().verify(stage, manifest("foreign", "v1", new byte[]{9})).isValid());
                linked = true;
            } else throw ex;
        }
        assertTrue(linked);
        denied(RuntimeError.MODEL_CORRUPT, () -> store.install(stage, manifest("foreign", "v1", new byte[]{9})));
        assertTrue(new File(unrelated, "weights.bin").exists());
    }

    @Test public void budgetAndIdentity() throws Exception {
        StorageBudget budget = new StorageBudget(StorageBudget.DEFAULT_QUOTA);
        assertTrue(budget.allows(0, 1, StorageBudget.FREE_RESERVE + 1));
        assertFalse(budget.allows(0, 1, StorageBudget.FREE_RESERVE));
        assertFalse(budget.allows(Long.MAX_VALUE, 1, Long.MAX_VALUE));
        assertFalse(budget.allows(0, Long.MAX_VALUE, Long.MAX_VALUE));
        assertFalse(budget.allows(-1, 0, Long.MAX_VALUE));
        ModelStore store = store(temp.newFolder("store"), p -> {});
        install(store, "a\u0000b", "c"); install(store, "a", "b\u0000c");
        assertEquals(2, store.registry().versions().size());
    }

    @Test public void readyDoesNotSurviveRestartAndOnlyOneResident() throws Exception {
        File root = temp.newFolder("store"); ModelStore store = store(root, p -> {});
        ModelVersion a = install(store, "a", "1"), b = install(store, "b", "1");
        LoadLease la = store.acquire(a); denied(RuntimeError.MODEL_IN_USE, () -> store.acquire(b)); store.markReady(la);
        denied(RuntimeError.MODEL_IN_USE, () -> store.acquire(b));
        la.close(); denied(RuntimeError.MODEL_IN_USE, () -> store.acquire(b));
        store.unload(a); LoadLease lb = store.acquire(b);
        assertEquals("unload before loading a different provider", ModelVersion.State.INSTALLED, store.registry().find("a", "1").state());
        store.markReady(lb);
        assertEquals(ModelVersion.State.INSTALLED, store.registry().find("a", "1").state());
        assertEquals(ModelVersion.State.READY, store.registry().find("b", "1").state());
        lb.close(); ModelStore restarted = store(root, p -> {});
        assertEquals(ModelVersion.State.INSTALLED, restarted.registry().find("b", "1").state());
        denied(RuntimeError.MODEL_MISSING, () -> restarted.acquireReady(b));
    }

    @Test public void markReadyMutationRevokesPermission() throws Exception {
        ModelStore store = store(temp.newFolder("store"), p -> {}); ModelVersion v = install(store, "v", "1");
        LoadLease lease = store.acquire(v); put(v.directory(), new byte[]{1, 2, 4});
        denied(RuntimeError.MODEL_CORRUPT, () -> store.markReady(lease));
        assertFalse("a corrupt lease must not retain permission", lease.permitsPrepare());
        assertEquals(ModelVersion.State.CORRUPT, store.registry().find("v", "1").state());
        lease.close(); assertEquals(0, store.leaseCount(v));
    }

    @Test public void failedRegistryPromotionAndRemoval() throws Exception {
        File root = temp.newFolder("store"); ModelStore store = store(root, p -> {}); ModelVersion old = install(store, "v", "1");
        LoadLease oldLease = store.acquire(old); store.markReady(oldLease); oldLease.close();
        ModelStore faulted = store(root, p -> { if (p.equals("BEFORE_REGISTRY_RENAME")) throw new IOException("registry rename crash"); });
        try { install(faulted, "v", "2"); fail("fault must interrupt registry commit"); }
        catch (IOException expected) { assertEquals("registry rename crash", expected.getMessage()); }
        ModelStore recovered = store(root, p -> {});
        assertNull(recovered.registry().find("v", "2")); assertNotNull(recovered.registry().lastKnownGood("v"));
        assertFalse(recovered.orphanStages().isEmpty());
        File unrelated = new File(root, "user-kept"); assertTrue(unrelated.mkdir()); put(unrelated, new byte[]{7});
        assertEquals(RemovalResult.REMOVED, recovered.remove(old));
        assertFalse(old.directory().exists()); assertTrue(new File(unrelated, "weights.bin").exists());
        assertEquals(RemovalResult.MODEL_MISSING, recovered.remove(old));
        assertNull(store(root, p -> {}).registry().find("v", "1"));
    }

    @Test public void quotaFilesystemFaultsAndUnknownCleanup() throws Exception {
        File root = temp.newFolder("store"); final long[] free = {StorageBudget.FREE_RESERVE};
        ModelStore.LocalFileOps local = new ModelStore.LocalFileOps();
        ModelStore.FileOps injected = new ModelStore.FileOps() {
            public void rename(File a, File b) throws IOException { local.rename(a, b); }
            public void sync(FileOutputStream stream) throws IOException { local.sync(stream); }
            public void delete(File file) throws IOException { local.delete(file); }
            public long usableSpace(File file) { return free[0]; }
        };
        ModelStore store = new ModelStore(root, new StorageBudget(StorageBudget.DEFAULT_QUOTA), new IntegrityVerifier(), () -> now, p -> {}, injected);
        assertFalse(store.canReserve(1)); denied(RuntimeError.BACKPRESSURE, () -> store.createStage());
        free[0] = Long.MAX_VALUE; File stage = store.createStage(); put(stage, new byte[]{1, 2, 3}); free[0] = StorageBudget.FREE_RESERVE;
        denied(RuntimeError.BACKPRESSURE, () -> store.install(stage, manifest("v", "1", new byte[]{1, 2, 3})));
        assertTrue(new File(stage, "weights.bin").exists()); free[0] = Long.MAX_VALUE;
        assertTrue(store.canReserve(1)); store.discardStage(stage); assertFalse(stage.exists());
        File unknown = new File(root, "staging/unknown"); assertTrue(unknown.mkdir()); put(unknown, new byte[]{7});
        denied(RuntimeError.MODEL_CORRUPT, () -> store.discardStage(unknown));
        assertTrue(new File(unknown, "weights.bin").exists()); assertFalse(store.orphanStages().contains(unknown));
        assertTrue(new StorageBudget(StorageBudget.MAX_QUOTA).allows(StorageBudget.DEFAULT_QUOTA, 1, Long.MAX_VALUE));
        try { new StorageBudget(StorageBudget.MAX_QUOTA + 1); fail("invalid quota"); } catch (IllegalArgumentException expected) {}
    }

    @Test public void immutableMetadataAndTreeBounds() throws Exception {
        byte[] data = {1, 2, 3}; String digest = hex(MessageDigest.getInstance("SHA-256").digest(data));
        java.util.ArrayList<ModelManifest.Asset> assets = new java.util.ArrayList<>();
        assets.add(new ModelManifest.Asset("weights.bin", 3, digest, "weights", "test", "ref", true));
        ModelManifest metadata = new ModelManifest.Builder("m", "1").assets(assets)
                .provider("p", 1, 2).compatibility(23, 34, Arrays.asList("arm64-v8a"))
                .upstream("reviewed-source", "immutable-revision").sampleRateHz(22050)
                .requiredAssets(Arrays.asList("weights.bin")).measurement("test-device", 1234, "reviewed-test")
                .artifacts(Arrays.asList(new ModelManifest.Artifact("weights", "https://example.invalid/immutable", 3, digest, 3, "raw", "tag")))
                .speakers(Arrays.asList(new ModelManifest.Speaker("0", "vi", "unknown", "unknown", "", false))).build();
        assets.clear(); assertEquals(1, metadata.assets.size());
        try { metadata.assets.clear(); fail("immutable assets"); } catch (UnsupportedOperationException expected) {}
        File root = temp.newFolder("store"); ModelStore store = store(root, p -> {}); File stage = store.createStage(); put(stage, data);
        ModelVersion version = store.install(stage, metadata);
        ModelManifest persisted = store(root, p -> {}).registry().find("m", "1").manifest();
        assertEquals("immutable-revision", persisted.upstreamRevision); assertEquals(22050, persisted.sampleRateHz);
        assertEquals(1234, persisted.residentMemoryBytes); assertEquals("arm64-v8a", persisted.nativeAbis.get(0));
        assertEquals("tag", persisted.artifacts.get(0).etag); assertTrue(persisted.assets.get(0).licenseReviewed);
        assertEquals("unknown", persisted.speakers.get(0).accent);
        for (String invalid : new String[]{"../a", "/a", "a\\b", "a//b", "a/../b", "lib.so", "model.py"}) {
            try { new ModelManifest.Asset(invalid, 3, digest, "weights", "test", "ref"); fail("reject " + invalid); }
            catch (IllegalArgumentException expected) {}
        }
        File empty = new File(version.directory(), "undeclared-empty"); assertTrue(empty.mkdir());
        assertFalse("undeclared directory denied", new IntegrityVerifier().verify(version.directory(), metadata).isValid());
    }

    @Test public void exactCaseRequiredAndArtifactPaths() throws Exception {
        byte[] bytes = {1}; String digest = hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        ModelManifest.Asset asset = new ModelManifest.Asset("Tokens.txt", 1, digest, "tokenizer", "test", "ref");
        try {
            new ModelManifest.Builder("m", "1").assets(Arrays.asList(asset)).requiredAssets(Arrays.asList("tokens.txt")).build();
            fail("wrong-case required asset must be rejected on Android");
        } catch (IllegalArgumentException expected) {}
        try {
            new ModelManifest.Builder("m", "1").assets(Arrays.asList(asset)).artifacts(Arrays.asList(
                    new ModelManifest.Artifact("a", "https://example.invalid/a", 1, digest, 1, "raw", "tag", Arrays.asList("tokens.txt")))).build();
            fail("wrong-case artifact mapping must be rejected on Android");
        } catch (IllegalArgumentException expected) {}
        ModelManifest manifest = new ModelManifest.Builder("m", "1").assets(Arrays.asList(asset))
                .requiredAssets(Arrays.asList("Tokens.txt")).artifacts(Arrays.asList(new ModelManifest.Artifact(
                        "a", "https://example.invalid/a", 1, digest, 1, "raw", "tag", Arrays.asList("Tokens.txt")))).build();
        File root = temp.newFolder("case-store"); ModelStore store = store(root, p -> {}); File stage = store.createStage();
        try (FileOutputStream out = new FileOutputStream(new File(stage, "Tokens.txt"))) { out.write(bytes); }
        store.install(stage, manifest); ModelManifest read = store(root, p -> {}).registry().find("m", "1").manifest();
        assertEquals("Tokens.txt", read.requiredAssets.get(0)); assertEquals("Tokens.txt", read.artifacts.get(0).assetPaths.get(0));
    }

    private static long treeBytes(File file) {
        if (file.isFile()) return file.length(); long total = 0;
        File[] children = file.listFiles(); if (children != null) for (File child : children) total += treeBytes(child);
        return total;
    }
    private static void fillTo(File root, long desiredBytes) throws IOException {
        File padding = new File(root, "quota-padding"); long others = treeBytes(root) - padding.length();
        try (RandomAccessFile out = new RandomAccessFile(padding, "rw")) { out.setLength(desiredBytes - others); }
    }

    @Test public void metadataQuotaPeakAndFinal() throws Exception {
        File root = temp.newFolder("quota-store"); final long[] observedPeak = {0};
        ModelStore.LocalFileOps local = new ModelStore.LocalFileOps();
        ModelStore.FileOps observed = new ModelStore.FileOps() {
            private void sample() { observedPeak[0] = Math.max(observedPeak[0], treeBytes(root)); }
            public void rename(File a, File b) throws IOException { local.rename(a, b); sample(); }
            public void sync(FileOutputStream out) throws IOException { local.sync(out); sample(); }
            public void delete(File f) throws IOException { local.delete(f); sample(); }
            public long usableSpace(File f) { return Long.MAX_VALUE; }
        };
        ModelStore store = new ModelStore(root, new StorageBudget(StorageBudget.DEFAULT_QUOTA), new IntegrityVerifier(), () -> now, p -> {}, observed);
        ModelVersion old = install(store, "v", "1"); LoadLease lease = store.acquire(old); store.markReady(lease); lease.close(); store.unload(old);
        assertTrue(new File(root, "registry.0").isFile()); assertTrue(new File(root, "registry.1").isFile());
        File candidate = store.createStage(); put(candidate, new byte[]{1, 2, 3});
        fillTo(root, StorageBudget.DEFAULT_QUOTA - 1);
        denied(RuntimeError.BACKPRESSURE, () -> store.install(candidate, manifest("v", "2", new byte[]{1, 2, 3})));
        assertTrue("denial must precede moving usable stage", candidate.isDirectory());
        assertNotNull(store.registry().lastKnownGood("v")); assertTrue(treeBytes(root) <= StorageBudget.DEFAULT_QUOTA);
        denied(RuntimeError.BACKPRESSURE, () -> store.createStage());
        LoadLease ready = store.acquire(old);
        denied(RuntimeError.BACKPRESSURE, () -> store.markReady(ready));
        assertEquals(ModelVersion.State.INSTALLED, store.registry().find("v", "1").state()); ready.close();
        fillTo(root, StorageBudget.DEFAULT_QUOTA - 2 * 1024 * 1024);
        store.install(candidate, manifest("v", "2", new byte[]{1, 2, 3}));
        assertTrue("every owner/temp/slot sync/rename stays below quota", observedPeak[0] <= StorageBudget.DEFAULT_QUOTA);
        assertTrue("final tree stays below quota", treeBytes(root) <= StorageBudget.DEFAULT_QUOTA);
    }

    @Test public void createStageQuotaAdmission() throws Exception {
        File root = temp.newFolder("stage-quota"); ModelStore store = store(root, p -> {});
        fillTo(root, StorageBudget.DEFAULT_QUOTA); int before = root.list().length;
        denied(RuntimeError.BACKPRESSURE, () -> store.createStage());
        assertEquals(StorageBudget.DEFAULT_QUOTA, treeBytes(root)); assertEquals(before, root.list().length);
        assertEquals(0, new File(root, "staging").list().length);
    }

    @Test public void orphanReclamationAndStartupRetry() throws Exception {
        File root = temp.newFolder("reclaim-store"); ModelStore store = store(root, p -> {});
        ModelVersion usable = install(store, "v", "1"); LoadLease pin = store.acquire(usable); store.markReady(pin); pin.close();
        File unknown = new File(root, "staging/user-directory"); assertTrue(unknown.mkdir()); put(unknown, new byte[]{7});
        ModelStore crash = store(root, p -> { if (p.equals("AFTER_VERSION_RENAME")) throw new IOException("rename crash"); });
        try { install(crash, "v", "2"); fail("crash fixture"); } catch (IOException expected) { assertEquals("rename crash", expected.getMessage()); }
        File staged = crash.createStage(); put(staged, new byte[]{1, 2, 3});
        ModelStore recovered = store(root, p -> {});
        assertFalse("startup deletes only identified staging", staged.exists());
        assertTrue(new File(unknown, "weights.bin").isFile());
        File promoted = null;
        for (File orphan : recovered.orphanStages()) if (orphan.getParentFile().getName().equals("versions")) promoted = orphan;
        assertNotNull("promoted orphan exposed for explicit recovery", promoted);
        recovered.reclaimOrphan(promoted); assertFalse(promoted.exists());
        assertFalse(new File(promoted.getParentFile(), promoted.getName() + ".owner").exists());
        denied(RuntimeError.MODEL_IN_USE, () -> recovered.reclaimOrphan(usable.directory()));
        denied(RuntimeError.MODEL_CORRUPT, () -> recovered.reclaimOrphan(unknown));
        assertNotNull(recovered.registry().lastKnownGood("v")); assertTrue(usable.directory().isDirectory());
        install(recovered, "v", "2"); assertNotNull(recovered.registry().find("v", "2"));

        File retryStage = recovered.createStage(); put(retryStage, new byte[]{1, 2, 3});
        final boolean[] failDelete = {true}; ModelStore.LocalFileOps local = new ModelStore.LocalFileOps();
        ModelStore.FileOps injected = new ModelStore.FileOps() {
            public void rename(File a, File b) throws IOException { local.rename(a, b); }
            public void sync(FileOutputStream out) throws IOException { local.sync(out); }
            public void delete(File f) throws IOException { if (failDelete[0] && f.getName().equals("weights.bin")) throw new IOException("delete retry"); local.delete(f); }
            public long usableSpace(File f) { return Long.MAX_VALUE; }
        };
        ModelStore failedCleanup = new ModelStore(root, new StorageBudget(StorageBudget.DEFAULT_QUOTA), new IntegrityVerifier(), () -> now, p -> {}, injected);
        assertTrue("failed startup cleanup remains retryable", failedCleanup.orphanStages().contains(retryStage));
        assertNotNull(failedCleanup.registry().find("v", "1")); failDelete[0] = false;
        failedCleanup.reclaimOrphan(retryStage); assertFalse(retryStage.exists());
        File ownerOnly = failedCleanup.createStage(); assertTrue(ownerOnly.delete());
        assertTrue("owner-only residue is enumerated", failedCleanup.orphanStages().contains(ownerOnly));
        failedCleanup.reclaimOrphan(ownerOnly);
        assertFalse(new File(ownerOnly.getParentFile(), ownerOnly.getName() + ".owner").exists());
        assertTrue(new File(unknown, "weights.bin").isFile());
    }

    @Test public void interruptedRemovalIsExplicitlyReclaimable() throws Exception {
        File root = temp.newFolder("remove-retry"); ModelStore store = store(root, p -> {});
        ModelVersion a = install(store, "v", "1"), b = install(store, "v", "2");
        ModelStore.LocalFileOps local = new ModelStore.LocalFileOps(); final boolean[] fail = {true};
        ModelStore.FileOps injected = new ModelStore.FileOps() {
            public void rename(File x, File y) throws IOException { local.rename(x, y); }
            public void sync(FileOutputStream out) throws IOException { local.sync(out); }
            public void delete(File f) throws IOException { if (fail[0] && f.getName().equals("weights.bin")) throw new IOException("removal fault"); local.delete(f); }
            public long usableSpace(File f) { return Long.MAX_VALUE; }
        };
        ModelStore faulted = new ModelStore(root, new StorageBudget(StorageBudget.DEFAULT_QUOTA), new IntegrityVerifier(), () -> now, p -> {}, injected);
        try { faulted.remove(b); fail("removal fixture"); } catch (IOException expected) { assertEquals("removal fault", expected.getMessage()); }
        assertNull(faulted.registry().find("v", "2")); assertTrue(faulted.orphanStages().contains(b.directory()));
        fail[0] = false; faulted.reclaimOrphan(b.directory()); assertFalse(b.directory().exists());
        assertTrue(a.directory().isDirectory()); assertNotNull(store(root, p -> {}).registry().find("v", "1"));
    }

    @Test public void ownerOnlyResidueAfterRenameAndDeleteFaults() throws Exception {
        File root = temp.newFolder("owner-only"); ModelStore store = store(root, p -> {}); ModelVersion usable = install(store, "v", "1");
        ModelStore crash = store(root, p -> { if (p.equals("BEFORE_VERSION_RENAME")) throw new IOException("before rename"); });
        try { install(crash, "v", "2"); fail("crash fixture"); } catch (IOException expected) { assertEquals("before rename", expected.getMessage()); }
        ModelStore.LocalFileOps local = new ModelStore.LocalFileOps(); final boolean[] denyOwnerDelete = {true};
        ModelStore.FileOps injected = new ModelStore.FileOps() {
            public void rename(File a, File b) throws IOException { local.rename(a, b); }
            public void sync(FileOutputStream out) throws IOException { local.sync(out); }
            public void delete(File f) throws IOException { if (denyOwnerDelete[0] && f.getName().endsWith(".owner")) throw new IOException("owner delete"); local.delete(f); }
            public long usableSpace(File f) { return Long.MAX_VALUE; }
        };
        ModelStore recovered = new ModelStore(root, new StorageBudget(StorageBudget.DEFAULT_QUOTA), new IntegrityVerifier(), () -> now, p -> {}, injected);
        assertEquals("staging owner + version owner", 2, recovered.orphanStages().size());
        for (File orphan : recovered.orphanStages()) {
            assertFalse("both are owner-only", orphan.exists());
            try { recovered.reclaimOrphan(orphan); fail("deletion fault remains retryable"); }
            catch (IOException expected) { assertEquals("owner delete", expected.getMessage()); }
        }
        denyOwnerDelete[0] = false;
        for (File orphan : recovered.orphanStages()) recovered.reclaimOrphan(orphan);
        assertTrue(recovered.orphanStages().isEmpty());
        LoadLease pin = recovered.acquire(usable);
        denied(RuntimeError.MODEL_IN_USE, () -> recovered.reclaimOrphan(usable.directory())); pin.close();
        assertTrue(usable.directory().isDirectory()); install(recovered, "v", "2");
    }

    @Test public void boundedSnapshotSerialization() throws Exception {
        ModelStore store = store(temp.newFolder("bounded-record"), p -> {});
        java.util.ArrayList<ModelManifest.Asset> assets = new java.util.ArrayList<>();
        char[] licenseChars = new char[512], refChars = new char[2048]; Arrays.fill(licenseChars, 'a'); Arrays.fill(refChars, 'b');
        String digest = hex(MessageDigest.getInstance("SHA-256").digest(new byte[0]));
        for (int i = 0; i < 256; i++) assets.add(new ModelManifest.Asset("asset" + i + ".bin", 0, digest, "weights", new String(licenseChars), new String(refChars)));
        java.util.Map<ModelVersion.Identity, ModelVersion> entries = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 8; i++) {
            ModelManifest manifest = new ModelManifest.Builder("m" + i, "1").assets(assets).build();
            ModelVersion v = new ModelVersion(manifest, new File(temp.getRoot(), java.util.UUID.randomUUID().toString()), ModelVersion.State.INSTALLED);
            entries.put(v.identity(), v);
        }
        java.lang.reflect.Method serializer = ModelStore.class.getDeclaredMethod("snapshotBytes", java.util.Map.class, java.util.Map.class); serializer.setAccessible(true);
        try { serializer.invoke(store, entries, new java.util.LinkedHashMap<String, ModelVersion.Identity>()); fail("oversized metadata must be bounded during serialization"); }
        catch (java.lang.reflect.InvocationTargetException expected) {
            assertTrue(expected.getCause() instanceof ModelStore.StoreException);
            assertEquals(RuntimeError.BACKPRESSURE, ((ModelStore.StoreException) expected.getCause()).error());
        }
        assertTrue(store.registry().versions().isEmpty());
    }
}
