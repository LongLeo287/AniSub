package com.anisub.runtime.models;

import java.io.*;
import java.util.*;

/** Reviewed data metadata only; this object does not authorize downloads or advertise readiness. */
public final class ModelManifest {
    public final int schemaVersion;
    public final String id, version, task, providerId, upstream, upstreamRevision;
    public final int providerApiMin, providerApiMax, androidApiMin, androidApiMax, sampleRateHz;
    public final List<String> languages, nativeAbis, requiredAssets;
    public final List<Asset> assets;
    public final List<Artifact> artifacts;
    public final long expandedBytes, residentMemoryBytes;
    public final String measuredDeviceClass, qualityStatus;
    public final List<Speaker> speakers;

    public static final class Asset {
        public final String path, sha256, role, license, licenseReference;
        public final long bytes;
        public final boolean licenseReviewed;
        public Asset(String path, long bytes, String sha256, String role, String license, String licenseReference) {
            this(path, bytes, sha256, role, license, licenseReference, true);
        }
        public Asset(String path, long bytes, String sha256, String role, String license,
                     String licenseReference, boolean licenseReviewed) {
            requireRelativePath(path); requireDigest(sha256);
            if (bytes < 0) throw new IllegalArgumentException("asset size");
            this.path = path; this.bytes = bytes; this.sha256 = sha256.toLowerCase(Locale.ROOT);
            this.role = text(role, 80); this.license = text(license, 512);
            this.licenseReference = text(licenseReference, 2048); this.licenseReviewed = licenseReviewed;
        }
    }
    /** Transport artifact may be an archive; each extracted asset still has its own license/digest. */
    public static final class Artifact {
        public final String id, url, sha256, format, etag;
        public final long bytes, expandedBytes;
        public final List<String> assetPaths;
        public Artifact(String id, String url, long bytes, String sha256, long expandedBytes, String format, String etag) {
            this(id, url, bytes, sha256, expandedBytes, format, etag, Collections.emptyList());
        }
        public Artifact(String id, String url, long bytes, String sha256, long expandedBytes, String format, String etag, List<String> assetPaths) {
            this.id = text(id, 80); this.url = text(url, 2048); requireDigest(sha256);
            if (bytes < 0 || expandedBytes < 0) throw new IllegalArgumentException("artifact size");
            this.bytes = bytes; this.sha256 = sha256.toLowerCase(Locale.ROOT); this.expandedBytes = expandedBytes;
            this.format = text(format, 80); this.etag = etag == null ? "" : bounded(etag, 512);
            this.assetPaths = strings(assetPaths, 256); for (String path : this.assetPaths) requireRelativePath(path);
        }
    }
    public static final class Speaker {
        public final String id, language, gender, accent, provenance;
        public final boolean humanReviewed;
        public Speaker(String id, String language, String gender, String accent, String provenance, boolean humanReviewed) {
            this.id = text(id, 80); this.language = text(language, 80);
            this.gender = text(gender, 80); this.accent = text(accent, 80);
            this.provenance = provenance == null ? "" : bounded(provenance, 2048); this.humanReviewed = humanReviewed;
            if ((!"unknown".equals(gender) || !"unknown".equals(accent)) && (!humanReviewed || this.provenance.isEmpty()))
                throw new IllegalArgumentException("unverified speaker labels");
        }
    }
    public static final class Builder {
        private final String id, version;
        private int schemaVersion = 1, providerApiMin = 1, providerApiMax = 1, apiMin = 23, apiMax = Integer.MAX_VALUE, sampleRate;
        private String task = "tts", provider = "unassigned", upstream = "unassigned", revision = "unassigned";
        private String device = "unmeasured", quality = "unreviewed";
        private long expanded = -1, memory;
        private List<String> languages = Collections.singletonList("vi"), abis = Collections.emptyList(), required = Collections.emptyList();
        private List<Asset> assets = Collections.emptyList();
        private List<Artifact> artifacts = Collections.emptyList();
        private List<Speaker> speakers = Collections.emptyList();
        public Builder(String id, String version) { this.id = text(id, 80); this.version = text(version, 80); }
        public Builder schemaVersion(int v) { schemaVersion = v; return this; }
        public Builder task(String v) { task = v; return this; }
        public Builder languages(List<String> v) { languages = copy(v, 32); return this; }
        public Builder provider(String id, int min, int max) { provider = id; providerApiMin = min; providerApiMax = max; return this; }
        public Builder compatibility(int minApi, int maxApi, List<String> nativeAbis) { apiMin = minApi; apiMax = maxApi; abis = copy(nativeAbis, 16); return this; }
        public Builder upstream(String source, String immutableRevision) { upstream = source; revision = immutableRevision; return this; }
        public Builder assets(List<Asset> v) { assets = copy(v, 256); return this; }
        public Builder artifacts(List<Artifact> v) { artifacts = copy(v, 16); return this; }
        public Builder expandedBytes(long v) { expanded = v; return this; }
        public Builder sampleRateHz(int v) { sampleRate = v; return this; }
        public Builder requiredAssets(List<String> v) { required = copy(v, 256); return this; }
        public Builder speakers(List<Speaker> v) { speakers = copy(v, 128); return this; }
        public Builder measurement(String deviceClass, long residentBytes, String reviewedQualityStatus) { device = deviceClass; memory = residentBytes; quality = reviewedQualityStatus; return this; }
        public ModelManifest build() { return new ModelManifest(this); }
    }
    private ModelManifest(Builder b) {
        if (b.schemaVersion != 1 || b.providerApiMin < 1 || b.providerApiMax < b.providerApiMin || b.apiMin < 1
                || b.apiMax < b.apiMin || b.sampleRate < 0 || b.sampleRate > 192000 || b.memory < 0 || b.assets.isEmpty())
            throw new IllegalArgumentException("manifest range");
        schemaVersion = b.schemaVersion; id = b.id; version = b.version; task = text(b.task, 80);
        providerId = text(b.provider, 80); providerApiMin = b.providerApiMin; providerApiMax = b.providerApiMax;
        androidApiMin = b.apiMin; androidApiMax = b.apiMax; sampleRateHz = b.sampleRate;
        upstream = text(b.upstream, 2048); upstreamRevision = text(b.revision, 512);
        languages = strings(b.languages, 32); nativeAbis = strings(b.abis, 16); requiredAssets = strings(b.required, 256);
        if (languages.isEmpty()) throw new IllegalArgumentException("languages");
        assets = copy(b.assets, 256); artifacts = copy(b.artifacts, 16); speakers = copy(b.speakers, 128);
        long sum = 0; Set<String> paths = new HashSet<>(), exactPaths = new HashSet<>();
        for (Asset a : assets) {
            if (!paths.add(a.path.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("duplicate asset");
            exactPaths.add(a.path);
            if (sum > Long.MAX_VALUE - a.bytes) throw new IllegalArgumentException("size overflow");
            sum += a.bytes;
        }
        for (String p : requiredAssets) { requireRelativePath(p); if (!exactPaths.contains(p)) throw new IllegalArgumentException("required asset missing"); }
        Set<String> artifactIds = new HashSet<>();
        for (Artifact a : artifacts) {
            if (!artifactIds.add(a.id)) throw new IllegalArgumentException("duplicate artifact");
            for (String path : a.assetPaths) if (!exactPaths.contains(path)) throw new IllegalArgumentException("artifact asset missing");
        }
        expandedBytes = b.expanded == -1 ? sum : b.expanded;
        if (expandedBytes != sum) throw new IllegalArgumentException("expanded asset size mismatch");
        residentMemoryBytes = b.memory; measuredDeviceClass = text(b.device, 256); qualityStatus = text(b.quality, 256);
    }
    static void requireRelativePath(String path) {
        text(path, 512);
        if (path.startsWith("/") || path.contains("\\") || path.contains(":")) throw new IllegalArgumentException("asset path");
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")) throw new IllegalArgumentException("asset path");
            for (int i = 0; i < part.length(); i++) if (part.charAt(i) < 32) throw new IllegalArgumentException("asset path");
        }
        String lower = path.toLowerCase(Locale.ROOT);
        for (String suffix : new String[]{".so", ".dll", ".exe", ".py", ".pyc", ".jar", ".class", ".dex", ".sh", ".bat", ".cmd", ".ps1", ".js", ".mjs", ".kts"})
            if (lower.endsWith(suffix)) throw new IllegalArgumentException("executable model data");
    }
    private static void requireDigest(String digest) { if (digest == null || !digest.matches("[a-fA-F0-9]{64}")) throw new IllegalArgumentException("digest"); }
    private static String text(String value, int max) { if (value == null || value.isEmpty()) throw new IllegalArgumentException("missing metadata"); return bounded(value, max); }
    private static String bounded(String v, int max) { if (v.length() > max) throw new IllegalArgumentException("metadata too long"); return v; }
    private static <T> List<T> copy(List<T> input, int max) {
        if (input == null || input.size() > max || input.contains(null)) throw new IllegalArgumentException("metadata list");
        return Collections.unmodifiableList(new ArrayList<>(input));
    }
    private static List<String> strings(List<String> input, int max) { List<String> out = copy(input, max); for (String s : out) text(s, 512); return out; }

    void write(DataOutput out) throws IOException {
        out.writeInt(schemaVersion); out.writeUTF(id); out.writeUTF(version); out.writeUTF(task); writeStrings(out, languages);
        out.writeUTF(providerId); out.writeInt(providerApiMin); out.writeInt(providerApiMax); out.writeInt(androidApiMin); out.writeInt(androidApiMax); writeStrings(out, nativeAbis);
        out.writeUTF(upstream); out.writeUTF(upstreamRevision); out.writeLong(expandedBytes); out.writeInt(sampleRateHz); writeStrings(out, requiredAssets);
        out.writeLong(residentMemoryBytes); out.writeUTF(measuredDeviceClass); out.writeUTF(qualityStatus);
        out.writeInt(assets.size()); for (Asset a : assets) { out.writeUTF(a.path); out.writeLong(a.bytes); out.writeUTF(a.sha256); out.writeUTF(a.role); out.writeUTF(a.license); out.writeUTF(a.licenseReference); out.writeBoolean(a.licenseReviewed); }
        out.writeInt(artifacts.size()); for (Artifact a : artifacts) { out.writeUTF(a.id); out.writeUTF(a.url); out.writeLong(a.bytes); out.writeUTF(a.sha256); out.writeLong(a.expandedBytes); out.writeUTF(a.format); out.writeUTF(a.etag); writeStrings(out, a.assetPaths); }
        out.writeInt(speakers.size()); for (Speaker s : speakers) { out.writeUTF(s.id); out.writeUTF(s.language); out.writeUTF(s.gender); out.writeUTF(s.accent); out.writeUTF(s.provenance); out.writeBoolean(s.humanReviewed); }
    }
    static ModelManifest read(DataInput in) throws IOException {
        int schema = in.readInt(); Builder b = new Builder(in.readUTF(), in.readUTF()).schemaVersion(schema).task(in.readUTF()).languages(readStrings(in, 32));
        b.provider(in.readUTF(), in.readInt(), in.readInt()); b.compatibility(in.readInt(), in.readInt(), readStrings(in, 16));
        b.upstream(in.readUTF(), in.readUTF()); b.expandedBytes(in.readLong()).sampleRateHz(in.readInt()).requiredAssets(readStrings(in, 256));
        long memory = in.readLong(); b.measurement(in.readUTF(), memory, in.readUTF());
        List<Asset> assets = new ArrayList<>(); for (int n = count(in, 256); n > 0; n--) assets.add(new Asset(in.readUTF(), in.readLong(), in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readBoolean())); b.assets(assets);
        List<Artifact> artifacts = new ArrayList<>(); for (int n = count(in, 16); n > 0; n--) artifacts.add(new Artifact(in.readUTF(), in.readUTF(), in.readLong(), in.readUTF(), in.readLong(), in.readUTF(), in.readUTF(), readStrings(in, 256))); b.artifacts(artifacts);
        List<Speaker> speakers = new ArrayList<>(); for (int n = count(in, 128); n > 0; n--) speakers.add(new Speaker(in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readBoolean()));
        return b.speakers(speakers).build();
    }
    private static void writeStrings(DataOutput out, List<String> values) throws IOException { out.writeInt(values.size()); for (String v : values) out.writeUTF(v); }
    private static List<String> readStrings(DataInput in, int max) throws IOException { List<String> out = new ArrayList<>(); for (int n = count(in, max); n > 0; n--) out.add(in.readUTF()); return out; }
    static int count(DataInput in, int max) throws IOException { int n = in.readInt(); if (n < 0 || n > max) throw new IOException("metadata count"); return n; }
}
