package com.anisub.runtime.voice;

import com.anisub.runtime.models.ModelManifest;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Reviewed, APK-pinned voice catalog. Users choose a pack by id; every file has a pinned size,
 * SHA-256 and HTTPS URLs on allowlisted hosts. A caller can never supply a URL.
 */
public final class VoiceCatalog {
    public static final int SCHEMA = 1;
    public static final Set<String> ALLOWED_HOSTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com",
            "huggingface.co", "cdn-lfs.huggingface.co", "cdn-lfs.hf.co", "cas-bridge.xethub.hf.co")));
    public static final long MAX_PACK_BYTES = 512L << 20;

    public static final class Voice {
        public final String id, name, gender, accent; public final int speakerId;
        Voice(String id, String name, int speakerId, String gender, String accent) {
            this.id = id; this.name = name; this.speakerId = speakerId; this.gender = gender; this.accent = accent;
        }
    }
    public static final class PackFile {
        public final String path, sha256, license; public final long bytes; public final List<String> urls;
        PackFile(String path, long bytes, String sha256, String license, List<String> urls) {
            this.path = path; this.bytes = bytes; this.sha256 = sha256; this.license = license; this.urls = urls;
        }
    }
    public static final class Pack {
        public final String id, version, name, language, engine, license, licenseUrl, attribution;
        public final String model, tokens, dataDir;
        public final int sampleRate;
        public final List<Voice> voices;
        public final List<PackFile> files;
        public final long totalBytes;
        Pack(JSONObject o) throws JSONException {
            id = id(o.getString("id")); version = id(o.getString("version"));
            name = text(o.getString("name"), 120); language = o.getString("language");
            engine = o.getString("engine"); license = text(o.getString("license"), 400);
            licenseUrl = https(o.getString("licenseUrl"), false); attribution = text(o.getString("attribution"), 1200);
            model = o.getString("model"); tokens = o.getString("tokens"); dataDir = o.getString("dataDir");
            sampleRate = o.getInt("sampleRate");
            if (!"vi".equals(language) || !"sherpa-onnx-vits".equals(engine) || sampleRate < 8000 || sampleRate > 48000)
                throw new JSONException("unsupported pack");
            List<Voice> vs = new ArrayList<>(); JSONArray va = o.getJSONArray("voices");
            if (va.length() < 1 || va.length() > 64) throw new JSONException("voices");
            Set<String> vids = new HashSet<>();
            for (int i = 0; i < va.length(); i++) {
                JSONObject v = va.getJSONObject(i);
                Voice voice = new Voice(id(v.getString("id")), text(v.getString("name"), 80), v.getInt("speakerId"),
                        v.optString("gender", "unknown"), v.optString("accent", "unknown"));
                if (voice.speakerId < 0 || !vids.add(voice.id)) throw new JSONException("voice");
                vs.add(voice);
            }
            voices = Collections.unmodifiableList(vs);
            List<PackFile> fs = new ArrayList<>(); JSONArray fa = o.getJSONArray("files");
            if (fa.length() < 1 || fa.length() > 64) throw new JSONException("files");
            long total = 0; Set<String> paths = new HashSet<>();
            for (int i = 0; i < fa.length(); i++) {
                JSONObject f = fa.getJSONObject(i);
                String sha = f.getString("sha256").toLowerCase(Locale.ROOT);
                if (!sha.matches("[0-9a-f]{64}")) throw new JSONException("sha256");
                long bytes = f.getLong("bytes");
                if (bytes <= 0 || bytes > MAX_PACK_BYTES) throw new JSONException("bytes");
                JSONArray ua = f.getJSONArray("urls"); List<String> urls = new ArrayList<>();
                if (ua.length() < 1 || ua.length() > 4) throw new JSONException("urls");
                for (int u = 0; u < ua.length(); u++) urls.add(https(ua.getString(u), true));
                String path = f.getString("path");
                if (!paths.add(path.toLowerCase(Locale.ROOT))) throw new JSONException("duplicate path");
                fs.add(new PackFile(path, bytes, sha, text(f.getString("license"), 200), Collections.unmodifiableList(urls)));
                total += bytes;
            }
            if (total > MAX_PACK_BYTES) throw new JSONException("pack too large");
            if (!paths.contains(model.toLowerCase(Locale.ROOT)) || !paths.contains(tokens.toLowerCase(Locale.ROOT))) throw new JSONException("model files");
            files = Collections.unmodifiableList(fs); totalBytes = total;
            manifest(); // validates paths (no traversal, no executable payloads) eagerly
        }
        public Voice voice(String voiceId) {
            if (voiceId == null) return voices.get(0);
            for (Voice v : voices) if (v.id.equals(voiceId)) return v;
            return null;
        }
        /** Model-store manifest: every file's bytes and digest are re-checked at install and before load. */
        public ModelManifest manifest() {
            List<ModelManifest.Asset> assets = new ArrayList<>();
            for (PackFile f : files) assets.add(new ModelManifest.Asset(f.path, f.bytes, f.sha256, "voice", f.license, licenseUrl));
            List<ModelManifest.Speaker> speakers = new ArrayList<>();
            for (Voice v : voices) speakers.add(new ModelManifest.Speaker(v.id, "vi", "unknown", "unknown", "", false));
            return new ModelManifest.Builder(id, version).task("tts").languages(Collections.singletonList("vi"))
                    .provider("sherpa-onnx-vits", 1, 1).compatibility(23, Integer.MAX_VALUE, Arrays.asList("armeabi-v7a", "arm64-v8a", "x86"))
                    .upstream("https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models", "voices-v1")
                    .sampleRateHz(sampleRate).requiredAssets(Arrays.asList(model, tokens)).assets(assets)
                    .speakers(speakers).build();
        }
    }

    public final List<Pack> packs;

    private VoiceCatalog(List<Pack> packs) { this.packs = packs; }

    public static VoiceCatalog parse(String json) throws JSONException {
        if (json == null || json.length() > 256 * 1024) throw new JSONException("catalog size");
        JSONObject root = new JSONObject(json);
        if (root.getInt("schemaVersion") != SCHEMA) throw new JSONException("catalog schema");
        JSONArray pa = root.getJSONArray("packs");
        if (pa.length() > 16) throw new JSONException("packs");
        List<Pack> out = new ArrayList<>(); Set<String> ids = new HashSet<>();
        for (int i = 0; i < pa.length(); i++) {
            Pack p;
            try { p = new Pack(pa.getJSONObject(i)); } catch (IllegalArgumentException e) { throw new JSONException("pack: " + e.getMessage()); }
            if (!ids.add(p.id)) throw new JSONException("duplicate pack");
            out.add(p);
        }
        return new VoiceCatalog(Collections.unmodifiableList(out));
    }

    public Pack find(String id) { for (Pack p : packs) if (p.id.equals(id)) return p; return null; }
    public Pack defaultPack() { return packs.isEmpty() ? null : packs.get(0); }

    static String https(String url, boolean allowlisted) throws JSONException {
        try {
            URI uri = new URI(url);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getPort() != -1)
                throw new JSONException("https only");
            if (allowlisted && !ALLOWED_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))) throw new JSONException("host not allowlisted");
            return url;
        } catch (java.net.URISyntaxException e) { throw new JSONException("url"); }
    }
    public static boolean allowedRedirect(String url) {
        try { https(url, true); return true; } catch (JSONException e) { return false; }
    }
    private static String id(String s) throws JSONException {
        if (s == null || !s.matches("[a-z0-9][a-z0-9._-]{0,63}")) throw new JSONException("id");
        return s;
    }
    private static String text(String s, int max) throws JSONException {
        if (s == null || s.isEmpty() || s.length() > max) throw new JSONException("text");
        return s;
    }
}
