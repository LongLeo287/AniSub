package com.anisub.runtime.models;

import java.io.*;
import java.security.*;
import java.util.*;
import com.anisub.runtime.protocol.RuntimeError;
public final class ModelStore {
    public static final long IDLE_UNLOAD_MS = 60_000;
    private static final int RECORD_MAGIC = 0x41534d32, MAX_RECORD_BYTES = 4 * 1024 * 1024;
    public interface Clock { long nowMs(); }
    public interface Faults { void checkpoint(String point) throws IOException; }
    /** File.renameTo is same-filesystem atomic on Android; callers may inject failures in JVM tests. */
    public interface FileOps {
        void rename(File source, File destination) throws IOException;
        void sync(FileOutputStream stream) throws IOException;
        void delete(File file) throws IOException;
        long usableSpace(File root);
    }
    public static final class LocalFileOps implements FileOps {
        public void rename(File from, File to) throws IOException { if (!from.renameTo(to)) throw new IOException("atomic rename failed"); }
        public void sync(FileOutputStream stream) throws IOException { stream.getFD().sync(); }
        public void delete(File file) throws IOException { if (file.exists() && !file.delete()) throw new IOException("delete failed"); }
        public long usableSpace(File root) { return root.getUsableSpace(); }
    }
    public static final class StoreException extends IOException {
        private final RuntimeError error;
        public StoreException(RuntimeError error) { super(error.name()); this.error = error; }
        public RuntimeError error() { return error; }
    }
    private final File root, staging, versionsDir;
    private final StorageBudget budget;
    private final IntegrityVerifier verifier;
    private final Clock clock;
    private final Faults faults;
    private final FileOps fs;
    private Map<ModelVersion.Identity, ModelVersion> versions = new LinkedHashMap<>();
    private Map<String, ModelVersion.Identity> good = new LinkedHashMap<>();
    private final Map<ModelVersion.Identity, Integer> leases = new HashMap<>();
    private final Map<ModelVersion.Identity, Long> idleSince = new HashMap<>();
    private long generation;

    /** A host owns one store instance/serial management lane for an app-private root. */
    public ModelStore(File root, StorageBudget budget, IntegrityVerifier verifier, Clock clock, Faults faults) throws IOException {
        this(root, budget, verifier, clock, faults, new LocalFileOps());
    }
    public ModelStore(File root, StorageBudget budget, IntegrityVerifier verifier, Clock clock, Faults faults, FileOps fs) throws IOException {
        if (root == null || budget == null || verifier == null || clock == null || faults == null || fs == null) throw new IllegalArgumentException("store ports");
        this.root = root.getAbsoluteFile(); this.budget = budget; this.verifier = verifier; this.clock = clock; this.faults = faults; this.fs = fs;
        if (!this.root.equals(this.root.getCanonicalFile())) throw new StoreException(RuntimeError.MODEL_CORRUPT);
        mkdir(this.root); staging = new File(this.root, "staging"); versionsDir = new File(this.root, "versions"); mkdir(staging); mkdir(versionsDir);
        Snapshot a = readSnapshot(new File(this.root, "registry.0")), b = readSnapshot(new File(this.root, "registry.1"));
        Snapshot latest = a == null ? b : b == null || a.generation >= b.generation ? a : b;
        if (latest == null && (new File(this.root, "registry.0").exists() || new File(this.root, "registry.1").exists())) throw new StoreException(RuntimeError.MODEL_CORRUPT);
        if (latest != null) { generation = latest.generation; versions = latest.versions; good = latest.good; }
        // READY is process-local residency: persisted entries always restart INSTALLED (or CORRUPT).
        // Only exact-owned unregistered staging is automatically reclaimed. Failed cleanup
        // remains discoverable for explicit retry; promoted versions always need host action.
        for (File orphan : orphanStages()) if (orphan.getParentFile().equals(staging)) {
            try { reclaimOrphan(orphan); } catch (IOException ignored) { /* Retain marker for a safe retry. */ }
        }
    }
    public synchronized File createStage() throws IOException {
        File stage = new File(staging, UUID.randomUUID().toString());
        admitGrowth(ownerContents(stage).length); mkdir(stage); writeOwner(stage); return stage;
    }
    /** Called before transfer/extraction. Temporary and orphan data remain counted, never auto-evicted. */
    public synchronized boolean canReserve(long incomingExpandedBytes) throws IOException {
        return budget.allows(usedBytes(), incomingExpandedBytes, fs.usableSpace(root));
    }
    public synchronized ModelVersion install(File stage, ModelManifest manifest) throws IOException {
        if (manifest == null || !owned(stage, staging) || !verifier.verify(stage, manifest).isValid()) throw new StoreException(RuntimeError.MODEL_CORRUPT);
        ModelVersion.Identity id = new ModelVersion.Identity(manifest.id, manifest.version);
        if (versions.containsKey(id)) throw new StoreException(RuntimeError.MODEL_IN_USE);
        if (versions.size() >= ModelRegistry.MAX_VERSIONS) throw new StoreException(RuntimeError.BACKPRESSURE);
        long used = usedBytes();
        if (used < manifest.expandedBytes || !budget.allows(used - manifest.expandedBytes, manifest.expandedBytes, fs.usableSpace(root))) throw new StoreException(RuntimeError.BACKPRESSURE);
        File destination = new File(versionsDir, UUID.randomUUID().toString());
        ModelVersion version = new ModelVersion(manifest, destination, ModelVersion.State.INSTALLED);
        Map<ModelVersion.Identity, ModelVersion> next = new LinkedHashMap<>(versions); next.put(id, version);
        byte[] snapshot = snapshotBytes(next, good);
        // Reserve both peak (old slots + new owner + temp snapshot) and committed footprint
        // before moving a usable candidate or creating transaction metadata.
        admitSnapshot(snapshot, ownerContents(destination).length, ownerFile(stage).length());
        // Persist each verified payload before its directory becomes a committed registry reference.
        for (ModelManifest.Asset asset : manifest.assets) {
            File file = new File(stage, asset.path); safe(file);
            try (FileOutputStream payload = new FileOutputStream(file, true)) { fs.sync(payload); }
        }
        writeOwner(destination); // Durable exact-name ownership before promotion; detects rename-only crash orphans.
        faults.checkpoint("BEFORE_VERSION_RENAME"); fs.rename(stage, destination); faults.checkpoint("AFTER_VERSION_RENAME");
        if (!verifier.verify(destination, manifest).isValid()) throw new StoreException(RuntimeError.MODEL_CORRUPT);
        persistSnapshot(snapshot); versions = next;
        fs.delete(ownerFile(stage)); return version;
    }
    public synchronized LoadLease acquire(ModelVersion version) throws IOException { return acquireInternal(version, false); }
    public synchronized LoadLease acquireReady(ModelVersion version) throws IOException { return acquireInternal(version, true); }
    private LoadLease acquireInternal(ModelVersion handle, boolean requireReady) throws IOException {
        ModelVersion version = current(handle);
        if (version == null) throw new StoreException(RuntimeError.MODEL_MISSING);
        if (version.state() == ModelVersion.State.CORRUPT || !verifier.verify(version.directory(), version.manifest()).isValid()) {
            corrupt(version);
            throw new StoreException(RuntimeError.MODEL_CORRUPT);
        }
        if (requireReady && version.state() != ModelVersion.State.READY) throw new StoreException(RuntimeError.MODEL_MISSING);
        // Host must physically release an unleased resident and explicitly unload its metadata
        // before another native provider prepares. A lease does not imply physical unloading.
        if (!requireReady) for (ModelVersion resident : versions.values())
            if (resident.state() == ModelVersion.State.READY && !resident.identity().equals(version.identity()))
                throw new StoreException(RuntimeError.MODEL_IN_USE);
        for (Map.Entry<ModelVersion.Identity, Integer> pin : leases.entrySet())
            if (pin.getValue() > 0 && !pin.getKey().equals(version.identity())) throw new StoreException(RuntimeError.MODEL_IN_USE);
        int count = leaseCount(version); if (count == Integer.MAX_VALUE) throw new StoreException(RuntimeError.BACKPRESSURE);
        leases.put(version.identity(), count + 1); idleSince.remove(version.identity()); return new LoadLease(this, version);
    }
    /** Host calls only after provider compatibility/smoke/init succeeds for its current prepare generation. */
    public synchronized void markReady(LoadLease lease) throws IOException {
        if (lease == null || !lease.belongsTo(this) || !isUsable(lease)) throw new StoreException(RuntimeError.MODEL_MISSING);
        ModelVersion version = current(lease.version());
        if (!verifier.verify(version.directory(), version.manifest()).isValid()) { corrupt(version); throw new StoreException(RuntimeError.MODEL_CORRUPT); }
        Map<ModelVersion.Identity, ModelVersion> next = new LinkedHashMap<>(versions);
        for (ModelVersion resident : versions.values()) if (resident.state() == ModelVersion.State.READY && !resident.identity().equals(version.identity())) {
            throw new StoreException(RuntimeError.MODEL_IN_USE);
        }
        next.put(version.identity(), version.withState(ModelVersion.State.READY));
        Map<String, ModelVersion.Identity> nextGood = new LinkedHashMap<>(good); nextGood.put(version.manifest().id, version.identity());
        persist(next, nextGood); versions = next; good = nextGood;
    }
    public synchronized void unloadIdle() throws IOException {
        long now = clock.nowMs(); Map<ModelVersion.Identity, ModelVersion> next = new LinkedHashMap<>(versions); boolean changed = false;
        for (ModelVersion v : versions.values()) {
            Long since = idleSince.get(v.identity());
            if (v.state() == ModelVersion.State.READY && leaseCount(v) == 0 && since != null && now >= since && now - since >= IDLE_UNLOAD_MS) {
                next.put(v.identity(), v.withState(ModelVersion.State.INSTALLED)); changed = true;
            }
        }
        if (changed) { persist(next, good); versions = next; }
    }
    /** Residency owner must release its provider when this transition reports INSTALLED. */
    public synchronized void unload(ModelVersion handle) throws IOException {
        ModelVersion v = current(handle); if (v == null) throw new StoreException(RuntimeError.MODEL_MISSING);
        if (leaseCount(v) > 0) throw new StoreException(RuntimeError.MODEL_IN_USE);
        Map<ModelVersion.Identity, ModelVersion> next = new LinkedHashMap<>(versions);
        if (v.state() == ModelVersion.State.READY) { next.put(v.identity(), v.withState(ModelVersion.State.INSTALLED)); persist(next, good); versions = next; }
    }
    public synchronized RemovalResult remove(ModelVersion handle) throws IOException {
        ModelVersion v = current(handle); if (v == null) return RemovalResult.MODEL_MISSING;
        if (leaseCount(v) > 0) return RemovalResult.MODEL_IN_USE;
        if (!owned(v.directory(), versionsDir)) throw new StoreException(RuntimeError.MODEL_CORRUPT);
        Map<ModelVersion.Identity, ModelVersion> next = new LinkedHashMap<>(versions); next.remove(v.identity());
        Map<String, ModelVersion.Identity> nextGood = new LinkedHashMap<>(good);
        if (v.identity().equals(nextGood.get(v.manifest().id))) nextGood.remove(v.manifest().id);
        persist(next, nextGood); versions = next; good = nextGood; idleSince.remove(v.identity());
        deleteTree(v.directory(), 0); fs.delete(ownerFile(v.directory())); return RemovalResult.REMOVED;
    }
    public synchronized ModelRegistry registry() { return new ModelRegistry(versions, good); }
    /** Includes owner-only residue; an absent directory still names an exact-owned cleanup target. */
    public synchronized List<File> orphanStages() {
        List<File> orphans = new ArrayList<>();
        for (File parent : new File[]{staging, versionsDir}) {
            File[] children = parent.listFiles(); if (children == null) continue;
            for (File child : children) try {
                if (!child.getName().endsWith(".owner")) continue;
                File directory = new File(parent, child.getName().substring(0, child.getName().length() - 6));
                if (owned(directory, parent) && !registered(directory)) orphans.add(directory);
            } catch (IOException ignored) { /* Unrecognised/redirected paths remain untouched. */ }
        }
        return Collections.unmodifiableList(orphans);
    }
    public synchronized void discardStage(File stage) throws IOException {
        if (stage == null || !stage.getAbsoluteFile().getParentFile().equals(staging)) throw new StoreException(RuntimeError.MODEL_CORRUPT);
        reclaimOrphan(stage);
    }
    /** Explicitly reclaim exact-owned unregistered stage/version/owner-only residue. Retry-safe. */
    public synchronized void reclaimOrphan(File orphan) throws IOException {
        if (orphan == null) throw new StoreException(RuntimeError.MODEL_CORRUPT);
        safe(orphan); File parent = orphan.getAbsoluteFile().getParentFile();
        if ((!parent.equals(staging) && !parent.equals(versionsDir)) || !owned(orphan, parent)) throw new StoreException(RuntimeError.MODEL_CORRUPT);
        // Registered versions include all last-known-good and leased models; never reclaim them.
        if (registered(orphan)) throw new StoreException(RuntimeError.MODEL_IN_USE);
        if (orphan.exists()) {
            if (!orphan.isDirectory()) throw new StoreException(RuntimeError.MODEL_CORRUPT);
            deleteTree(orphan, 0);
        }
        fs.delete(ownerFile(orphan));
    }
    private boolean registered(File directory) {
        for (ModelVersion version : versions.values()) if (version.directory().equals(directory)) return true;
        return false;
    }
    public synchronized int leaseCount(ModelVersion version) { Integer count = leases.get(version.identity()); return count == null ? 0 : count; }
    synchronized boolean isUsable(LoadLease lease) { ModelVersion v = current(lease.version()); return lease.belongsTo(this) && v != null && v.state() != ModelVersion.State.CORRUPT; }
    synchronized void release(LoadLease lease) {
        ModelVersion.Identity key = lease.version().identity(); Integer count = leases.get(key);
        if (count != null && count > 1) leases.put(key, count - 1);
        else if (count != null) { leases.remove(key); idleSince.put(key, clock.nowMs()); }
    }
    private ModelVersion current(ModelVersion handle) {
        if (handle == null) return null; ModelVersion current = versions.get(handle.identity());
        return current != null && current.directory().equals(handle.directory()) ? current : null;
    }
    private void corrupt(ModelVersion version) throws IOException {
        Map<ModelVersion.Identity, ModelVersion> next = new LinkedHashMap<>(versions); next.put(version.identity(), version.withState(ModelVersion.State.CORRUPT));
        Map<String, ModelVersion.Identity> nextGood = new LinkedHashMap<>(good);
        if (version.identity().equals(nextGood.get(version.manifest().id))) nextGood.remove(version.manifest().id);
        // Immediately revoke in-memory permission even if durable recording encounters a disk fault.
        versions = next; good = nextGood; persist(next, nextGood);
    }
    private void mkdir(File directory) throws IOException {
        safe(directory); if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("store directory unavailable"); safe(directory);
    }
    private void safe(File file) throws IOException {
        File absolute = file.getAbsoluteFile(), canonical = file.getCanonicalFile();
        if (!absolute.equals(canonical) || !(canonical.equals(root) || canonical.getPath().startsWith(root.getPath() + File.separator))) throw new StoreException(RuntimeError.MODEL_CORRUPT);
    }
    private File ownerFile(File directory) { return new File(directory.getParentFile(), directory.getName() + ".owner"); }
    private void writeOwner(File directory) throws IOException {
        safe(directory); File owner = ownerFile(directory); safe(owner);
        byte[] contents = ownerContents(directory); admitGrowth(Math.max(0, contents.length - owner.length()));
        try (FileOutputStream raw = new FileOutputStream(owner)) {
            raw.write(contents); fs.sync(raw);
        }
    }
    private static byte[] ownerContents(File directory) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(raw);
        out.writeInt(RECORD_MAGIC); out.writeUTF(directory.getName()); out.flush(); return raw.toByteArray();
    }
    private void admitGrowth(long growth) throws IOException {
        if (!budget.allows(usedBytes(), growth, fs.usableSpace(root))) throw new StoreException(RuntimeError.BACKPRESSURE);
    }
    private boolean owned(File directory, File expectedParent) throws IOException {
        if (directory == null) return false; safe(directory);
        if (!directory.getAbsoluteFile().getParentFile().equals(expectedParent) || !directory.getName().matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) return false;
        File owner = ownerFile(directory); safe(owner); if (!owner.isFile() || owner.length() > 128) return false;
        try (DataInputStream in = new DataInputStream(new FileInputStream(owner))) { return in.readInt() == RECORD_MAGIC && directory.getName().equals(in.readUTF()) && in.read() == -1; }
    }
    private long usedBytes() throws IOException { return bytes(root, 0); }
    private long bytes(File file, int depth) throws IOException {
        safe(file); if (depth > 32) throw new StoreException(RuntimeError.MODEL_CORRUPT);
        if (file.isFile()) return file.length();
        File[] children = file.listFiles(); if (children == null) throw new IOException("unreadable store"); long sum = 0;
        for (File child : children) { long n = bytes(child, depth + 1); if (sum > Long.MAX_VALUE - n) throw new StoreException(RuntimeError.BACKPRESSURE); sum += n; }
        return sum;
    }
    private void deleteTree(File directory, int depth) throws IOException {
        safe(directory); if (depth > 32) throw new StoreException(RuntimeError.MODEL_CORRUPT);
        if (directory.isDirectory()) { File[] children = directory.listFiles(); if (children == null) throw new IOException("unreadable owned directory"); for (File child : children) deleteTree(child, depth + 1); }
        fs.delete(directory);
    }
    private void persist(Map<ModelVersion.Identity, ModelVersion> next, Map<String, ModelVersion.Identity> nextGood) throws IOException {
        persistSnapshot(snapshotBytes(next, nextGood));
    }
    private byte[] snapshotBytes(Map<ModelVersion.Identity, ModelVersion> next, Map<String, ModelVersion.Identity> nextGood) throws IOException {
        if (generation >= 9_007_199_254_740_991L) throw new StoreException(RuntimeError.BACKPRESSURE);
        long nextGeneration = generation + 1;
        BoundedBytes data = new BoundedBytes(); DataOutputStream payload = new DataOutputStream(data);
        payload.writeLong(nextGeneration); payload.writeInt(next.size());
        for (ModelVersion v : next.values()) { payload.writeUTF(v.directory().getName()); payload.writeBoolean(v.state() == ModelVersion.State.CORRUPT); v.manifest().write(payload); }
        payload.writeInt(nextGood.size()); for (Map.Entry<String, ModelVersion.Identity> entry : nextGood.entrySet()) { payload.writeUTF(entry.getKey()); payload.writeUTF(entry.getValue().version); }
        payload.flush(); return data.bytes();
    }
    private void admitSnapshot(byte[] contents, long addedOwnerBytes, long removedOwnerBytes) throws IOException {
        File tmp = new File(root, "registry.tmp"), target = new File(root, "registry." + ((generation + 1) % 2)); safe(tmp); safe(target);
        long current = usedBytes(), recordBytes = contents.length + 40L;
        long growth = checkedAdd(addedOwnerBytes, Math.max(0, recordBytes - tmp.length()));
        long retained = current - tmp.length() - target.length() - removedOwnerBytes;
        long finalBytes = checkedAdd(checkedAdd(retained, recordBytes), addedOwnerBytes);
        if (retained < 0 || finalBytes > budget.quotaBytes() || !budget.allows(current, growth, fs.usableSpace(root))) throw new StoreException(RuntimeError.BACKPRESSURE);
    }
    private static long checkedAdd(long a, long b) throws IOException {
        if (a < 0 || b < 0 || a > Long.MAX_VALUE - b) throw new StoreException(RuntimeError.BACKPRESSURE); return a + b;
    }
    private void persistSnapshot(byte[] contents) throws IOException {
        admitSnapshot(contents, 0, 0);
        long nextGeneration = generation + 1;
        File tmp = new File(root, "registry.tmp"), target = new File(root, "registry." + (nextGeneration % 2)); safe(tmp); safe(target);
        try (FileOutputStream raw = new FileOutputStream(tmp); DataOutputStream out = new DataOutputStream(raw)) {
            out.writeInt(RECORD_MAGIC); out.writeInt(contents.length); out.write(contents); out.write(digest(contents)); out.flush(); fs.sync(raw);
        }
        faults.checkpoint("AFTER_REGISTRY_SYNC");
        // Replacing the older slot leaves the latest committed slot intact across interruption.
        fs.delete(target); faults.checkpoint("BEFORE_REGISTRY_RENAME"); fs.rename(tmp, target);
        generation = nextGeneration;
    }
    /** Enforce the 4MiB snapshot limit while serializing, before allocating an oversized buffer. */
    private static final class BoundedBytes extends OutputStream {
        private final ByteArrayOutputStream data = new ByteArrayOutputStream();
        @Override public void write(int value) throws IOException { if (data.size() == MAX_RECORD_BYTES) throw new StoreException(RuntimeError.BACKPRESSURE); data.write(value); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if (length > MAX_RECORD_BYTES - data.size()) throw new StoreException(RuntimeError.BACKPRESSURE); data.write(bytes, offset, length);
        }
        byte[] bytes() { return data.toByteArray(); }
    }
    private static byte[] digest(byte[] contents) throws IOException {
        try { return MessageDigest.getInstance("SHA-256").digest(contents); } catch (NoSuchAlgorithmException ex) { throw new IOException("SHA-256 unavailable"); }
    }
    private Snapshot readSnapshot(File file) throws IOException {
        safe(file); if (!file.isFile()) return null;
        if (file.length() < 40 || file.length() > MAX_RECORD_BYTES + 40L) return null;
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            if (in.readInt() != RECORD_MAGIC) return null; int size = in.readInt(); if (size < 0 || size > MAX_RECORD_BYTES) return null;
            byte[] contents = new byte[size], checksum = new byte[32]; in.readFully(contents); in.readFully(checksum);
            if (in.read() != -1 || !MessageDigest.isEqual(checksum, digest(contents))) return null;
            DataInputStream payload = new DataInputStream(new ByteArrayInputStream(contents)); long gen = payload.readLong();
            if (gen < 1 || gen > 9_007_199_254_740_991L) return null;
            Map<ModelVersion.Identity, ModelVersion> entries = new LinkedHashMap<>(); Set<File> paths = new HashSet<>();
            for (int n = ModelManifest.count(payload, ModelRegistry.MAX_VERSIONS); n > 0; n--) {
                String token = payload.readUTF(); boolean corrupt = payload.readBoolean(); ModelManifest manifest = ModelManifest.read(payload);
                File directory = new File(versionsDir, token);
                if (!owned(directory, versionsDir) || !paths.add(directory)) return null;
                ModelVersion v = new ModelVersion(manifest, directory, corrupt ? ModelVersion.State.CORRUPT : ModelVersion.State.INSTALLED);
                if (entries.put(v.identity(), v) != null) return null;
            }
            Map<String, ModelVersion.Identity> knownGood = new LinkedHashMap<>();
            for (int n = ModelManifest.count(payload, ModelRegistry.MAX_VERSIONS); n > 0; n--) {
                String id = payload.readUTF(); ModelVersion.Identity key = new ModelVersion.Identity(id, payload.readUTF());
                if (!entries.containsKey(key) || knownGood.put(id, key) != null || entries.get(key).state() == ModelVersion.State.CORRUPT) return null;
            }
            return payload.read() == -1 ? new Snapshot(gen, entries, knownGood) : null;
        } catch (IOException | IllegalArgumentException ex) { return null; }
    }
    private static final class Snapshot {
        final long generation; final Map<ModelVersion.Identity, ModelVersion> versions; final Map<String, ModelVersion.Identity> good;
        Snapshot(long generation, Map<ModelVersion.Identity, ModelVersion> versions, Map<String, ModelVersion.Identity> good) { this.generation = generation; this.versions = versions; this.good = good; }
    }
}
