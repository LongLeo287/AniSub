package com.anisub.runtime;

import com.anisub.runtime.settings.AniSubPrefs;
import com.anisub.runtime.translate.LanguageTags;
import com.anisub.runtime.voice.VoiceCatalog;
import com.anisub.runtime.voice.VoicePackManager;
import com.anisub.runtime.voice.VoiceRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * RELEASE GATE: the CURRENT AniSub must keep working with every AniBox that is already in users' hands. This is the
 * AniSub-side counterpart of AniBox's {@code AniSubCompatibilityMatrixTest}. If it fails, fix AniSub (or add the
 * missing compatibility); never weaken or delete a rule here to get a release out.
 *
 * <p>Released AniBox clients (git tags in the AniBox repository):
 * <ul>
 *   <li>v2.9.6 (versionCode 19): contains NO AniSub client at all (no {@code anisub/} package, no manifest query), so
 *       there is nothing to replay for it; AniSub is not reachable from that build.</li>
 *   <li>v2.9.6-hotfix (code 20): first AniBox with the client. {@code AniSubCapabilities.parse} knows minors 0-2;
 *       OPEN = envelope + mode/systemTest/rate (+ voiceLang on minor 2); no voiceId, no voice inventory.</li>
 *   <li>v2.9.6-hotfix2 (code 21): same parse throw conditions, plus the optional voice inventory
 *       ({@code AniSubVoices}), reading preferences, and OPEN {@code voiceId} after admission.</li>
 * </ul>
 * The parse rules below are re-implemented from {@code app/src/main/java/com/anibox/tv/anisub/AniSubCapabilities.java},
 * {@code AniSubProtocol.java}, {@code AniSubVoices.java} and the OPEN builders in {@code AniSubMessages.java} at those
 * tags ({@code git show v2.9.6-hotfix2:...}). The throw conditions of {@code parse} are identical in hotfix and
 * hotfix2. When a new AniBox is released, add its row here; never edit an old row.
 */
public class AniBoxClientCompatibilityTest {
    // ================================================================== AniBox parse rules (as released)

    /** The reply as the released AniBox understands it. */
    static final class Parsed {
        int minor;
        boolean directText, aiVoice, translation, multiSpeaker;
        String packState = "NONE";
        final Map<String, String> voices = new LinkedHashMap<>();
        /** hotfix2 inventory: "ai:vi", "system:en", ... advertised kinds; ids usable per "kind:lang". */
        final Set<String> advertised = new HashSet<>();
        final Map<String, Set<String>> usable = new HashMap<>();

        boolean aiReady() { return minor >= 1 && directText && aiVoice && "READY".equals(packState); }

        boolean aiReadyFor(String lang) {
            String voice = "en".equals(lang) ? "en" : "vi";
            if (minor >= 2) return directText && "ready".equals(voices.get(voice));
            return "vi".equals(voice) && aiReady();
        }

        Set<String> usableVoices(String kind, String lang) {
            Set<String> s = usable.get(kind + ":" + lang);
            return s == null ? Collections.<String>emptySet() : s;
        }
    }

    /** Released parse behaviour. {@code strictMinorOne}: a hypothetical client that rejects translation at ANY minor. */
    static final class Client {
        final String name;
        final int versionCode;
        final boolean inventory, voiceId, strictMinorOne;

        Client(String name, int versionCode, boolean inventory, boolean voiceId, boolean strictMinorOne) {
            this.name = name; this.versionCode = versionCode; this.inventory = inventory; this.voiceId = voiceId;
            this.strictMinorOne = strictMinorOne;
        }

        /** AniSubCapabilities.parse: throws IllegalArgumentException exactly where the released AniBox does. */
        Parsed parse(JSONObject reply) throws JSONException {
            if (!"CAPABILITIES".equals(string(reply, "type")) || !isInteger(reply, "major", 1) || !isInteger(reply, "protocolMajor", 1)) {
                throw new IllegalArgumentException("not a major-1 CAPABILITIES");
            }
            String engine = string(reply, "engine");
            if (engine == null || engine.length() > 80) throw new IllegalArgumentException("engine");
            Parsed p = new Parsed();
            if (reply.has("minor")) {
                Long value = integer(reply, "minor");
                if (value == null || value < 0 || value > 1000) throw new IllegalArgumentException("minor");
                p.minor = (int) (long) value;
            }
            if (p.minor >= 1 && reply.opt("voicePack") instanceof JSONObject) {
                String s = string(reply.getJSONObject("voicePack"), "state");
                p.packState = s == null ? "ERROR" : Arrays.asList("NONE", "DOWNLOADING", "VERIFYING", "READY").contains(s) ? s : "ERROR";
            }
            p.directText = bool(reply, "directText");
            bool(reply, "tts");
            bool(reply, "offline");
            p.aiVoice = bool(reply, "aiVoice");
            p.translation = bool(reply, "translation");
            p.multiSpeaker = bool(reply, "multiSpeaker");
            if ("android-system-tts".equals(engine)) {
                boolean translationRefused = strictMinorOne ? p.translation : (p.translation && p.minor < 2);
                if (translationRefused || p.multiSpeaker) throw new IllegalArgumentException("system TTS must not claim AI");
                if (p.minor == 0 && p.aiVoice) throw new IllegalArgumentException("minor-0 system TTS must not claim AI");
            }
            if (p.minor < 2) return p;
            if (reply.opt("voices") instanceof JSONObject) {
                JSONObject v = reply.getJSONObject("voices");
                for (java.util.Iterator<String> it = v.keys(); it.hasNext(); ) {
                    String lang = it.next();
                    if (!lang.matches("[a-z]{2,3}") || !(v.opt(lang) instanceof JSONObject)) continue;
                    String s = string(v.getJSONObject(lang), "state");
                    p.voices.put(lang, "ready".equals(s) || "downloading".equals(s) ? s : "missing");
                }
            }
            if (inventory) readInventory(reply, p);
            return p;
        }

        /** hotfix2 AniSubVoices.parse + admission inputs (voice usable = enabled, ready and AniSub directText). */
        private void readInventory(JSONObject reply, Parsed p) throws JSONException {
            JSONObject languages = reply.opt("voices") instanceof JSONObject ? reply.getJSONObject("voices") : null;
            if (languages != null) for (String lang : new String[]{"vi", "en"}) {
                if (!(languages.opt(lang) instanceof JSONObject)) continue;
                JSONObject pack = languages.getJSONObject(lang);
                Object items = pack.opt("voices");
                boolean metadata = reply.has("systemVoices");
                if (items instanceof JSONArray && ((JSONArray) items).length() <= 64) {
                    JSONArray array = (JSONArray) items;
                    for (int i = 0; i < array.length(); i++) if (array.opt(i) instanceof JSONObject) {
                        JSONObject v = array.getJSONObject(i);
                        if (v.has("gender") || v.has("accent") || v.has("enabled") || v.has("default")) metadata = true;
                    }
                } else if (items != null) metadata = true;
                if (metadata) {
                    p.advertised.add("ai:" + lang);
                    String ready = "ready".equals(string(pack, "state")) ? "ready" : "downloading".equals(string(pack, "state")) ? "downloading" : "missing";
                    readVoices(p, items, "ai", lang, ready);
                }
            }
            if (reply.has("systemVoices")) {
                p.advertised.add("system:vi");
                p.advertised.add("system:en");
                readVoices(p, reply.opt("systemVoices"), "system", null, "ready");
            }
        }

        private void readVoices(Parsed p, Object items, String kind, String parentLang, String state) throws JSONException {
            if (!(items instanceof JSONArray) || ((JSONArray) items).length() > 64) return;
            JSONArray array = (JSONArray) items;
            Set<String> ids = new HashSet<>();
            List<String[]> accepted = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                if (!(array.opt(i) instanceof JSONObject)) continue;
                JSONObject o = array.getJSONObject(i);
                String id = string(o, "id"), name = text(o, "name", 160), gender = text(o, "gender", 32), accent = text(o, "accent", 80);
                String lang = parentLang == null ? string(o, "language") : parentLang;
                if (id == null || id.trim().isEmpty() || id.length() > 80 || name == null || gender == null || accent == null
                        || (!"vi".equals(lang) && !"en".equals(lang))) continue;
                if (o.has("language") && !lang.equals(string(o, "language"))) continue;
                if (("system".equals(kind) || o.has("kind")) && !kind.equals(string(o, "kind"))) continue;
                if (!(o.opt("enabled") instanceof Boolean) || !(o.opt("default") instanceof Boolean)) continue;
                if (o.has("installed") && !(o.opt("installed") instanceof Boolean)) continue;
                if (!ids.add(id)) return; // ambiguous ids select nothing
                String readiness = o.has("installed") ? (o.getBoolean("installed") ? "ready" : "missing") : state;
                if (o.getBoolean("enabled") && "ready".equals(readiness) && p.directText) accepted.add(new String[]{lang, id});
            }
            for (String[] a : accepted) {
                Set<String> set = p.usable.get(kind + ":" + a[0]);
                if (set == null) p.usable.put(kind + ":" + a[0], set = new HashSet<>());
                set.add(a[1]);
            }
        }

        // ---- AniSubProtocol helpers, as released
        static String string(JSONObject o, String key) { Object v = o.opt(key); return v instanceof String ? (String) v : null; }

        static Long integer(JSONObject o, String key) {
            Object v = o.opt(key);
            // The runtime's JSON writes integers as plain digits; a fraction or exponent (Double/BigDecimal) is not an integer.
            if (v instanceof Integer || v instanceof Long) return ((Number) v).longValue();
            return null;
        }

        static boolean isInteger(JSONObject o, String key, long expected) { Long v = integer(o, key); return v != null && v == expected; }

        static boolean bool(JSONObject o, String key) {
            Object v = o.opt(key);
            if (!(v instanceof Boolean)) throw new IllegalArgumentException("boolean required: " + key);
            return (Boolean) v;
        }

        static String text(JSONObject o, String key, int max) {
            String v = string(o, key);
            return v == null || v.trim().isEmpty() || v.length() > max ? null : v;
        }
    }

    static final Client HOTFIX = new Client("2.9.6-hotfix", 20, false, false, false);
    static final Client HOTFIX2 = new Client("2.9.6-hotfix2", 21, true, true, false);
    /** Never released: a parser that would refuse translation at any minor (the pre-minor-2 rule). Kept as a floor. */
    static final Client STRICT_FLOOR = new Client("strict minor-1 floor", 0, false, false, true);
    static final Client[] RELEASED = {HOTFIX, HOTFIX2};
    static final Client[] ALL = {HOTFIX, HOTFIX2, STRICT_FLOOR};

    // ================================================================== CAPABILITIES the current AniSub can send

    static VoiceCatalog catalog() throws Exception {
        return VoiceCatalog.parse(new String(Files.readAllBytes(Paths.get("src/main/assets/voice-catalog.json")), StandardCharsets.UTF_8));
    }

    static VoicePackManager.Status status(VoiceCatalog.Pack pack, VoicePackManager.State state) {
        return new VoicePackManager.Status(state, pack, state == VoicePackManager.State.READY ? pack.version : null,
                state == VoicePackManager.State.DOWNLOADING ? 5 : pack.totalBytes, pack.totalBytes,
                state == VoicePackManager.State.ERROR ? "CORRUPT" : null, false);
    }

    static AniSubPrefs prefs() {
        final Map<String, String> values = new HashMap<>();
        return new AniSubPrefs(new AniSubPrefs.Store() {
            public String getString(String key, String def) { return values.containsKey(key) ? values.get(key) : def; }
            public void putString(String key, String value) { values.put(key, value); }
            public void remove(String key) { values.remove(key); }
        });
    }

    /** CAPABILITIES exactly as AniSubService assembles it: build, then reading preferences, then voice metadata. */
    static JSONObject assemble(boolean systemReady, Map<String, VoicePackManager.Status> packs, Capabilities.TranslateInfo translate,
                               List<VoiceRegistry.SystemVoice> systems, String systemState, Set<String> installedPacks) throws Exception {
        VoiceCatalog catalog = catalog();
        AniSubPrefs prefs = prefs();
        JSONObject out = Capabilities.build(systemReady, systemReady ? "local-vietnamese-ready" : "local-vietnamese-missing",
                packs, translate, "IDLE", "0.4.0", 5);
        out = Capabilities.withReadingPreferences(out, prefs);
        final Set<String> installed = installedPacks;
        VoiceRegistry registry = new VoiceRegistry(catalog, new VoiceRegistry.PackStates() {
            public boolean installed(String id) { return installed.contains(id); }
        }, systems, prefs);
        return Capabilities.withVoiceMetadata(out, registry, systemState);
    }

    static Capabilities.TranslateInfo translateReady() {
        Map<String, String> models = new LinkedHashMap<>();
        for (String l : LanguageTags.OFFERED) models.put(l, "vi".equals(l) || "en".equals(l) ? "ready" : "missing");
        return new Capabilities.TranslateInfo(true, null, true, models);
    }

    /** Named replies covering the states a user can be in. All of them must be accepted by every released AniBox. */
    static Map<String, JSONObject> currentReplies() throws Exception {
        VoiceCatalog c = catalog();
        VoiceCatalog.Pack vi = c.forLanguage("vi"), en = c.forLanguage("en");
        Map<String, JSONObject> out = new LinkedHashMap<>();
        List<VoiceRegistry.SystemVoice> systems = Arrays.asList(
                new VoiceRegistry.SystemVoice("com.google.android.tts", "vi-vn-x-gft-local", "vi", true),
                new VoiceRegistry.SystemVoice("com.google.android.tts", "en-us-x-tpd-local", "en", true));
        Set<String> viOnly = new HashSet<>(Collections.singleton(vi.id));
        Set<String> both = new HashSet<>(Arrays.asList(vi.id, en.id));
        for (VoicePackManager.State viState : VoicePackManager.State.values()) {
            for (VoicePackManager.State enState : VoicePackManager.State.values()) {
                Map<String, VoicePackManager.Status> packs = new LinkedHashMap<>();
                packs.put("vi", status(vi, viState));
                packs.put("en", status(en, enState));
                Set<String> installed = new HashSet<>();
                if (viState == VoicePackManager.State.READY) installed.add(vi.id);
                if (enState == VoicePackManager.State.READY) installed.add(en.id);
                out.put("vi=" + viState + " en=" + enState, assemble(true, packs, translateReady(), systems, "ready", installed));
            }
        }
        Map<String, VoicePackManager.Status> ready = new LinkedHashMap<>();
        ready.put("vi", status(vi, VoicePackManager.State.READY));
        ready.put("en", status(en, VoicePackManager.State.READY));
        out.put("both packs ready", assemble(true, ready, translateReady(), systems, "ready", both));
        out.put("no system TTS engine", assemble(false, ready, Capabilities.TranslateInfo.none("NO_ENGINE"), new ArrayList<VoiceRegistry.SystemVoice>(), "unavailable", both));
        out.put("system voices still initializing", assemble(true, ready, translateReady(), new ArrayList<VoiceRegistry.SystemVoice>(), "initializing", viOnly));
        Map<String, VoicePackManager.Status> broken = new LinkedHashMap<>();
        broken.put("vi", null);
        broken.put("en", null);
        out.put("private store unusable", assemble(false, broken, Capabilities.TranslateInfo.none("NO_DOWNLOAD_MANAGER"),
                new ArrayList<VoiceRegistry.SystemVoice>(), "unavailable", new HashSet<String>()));
        List<VoiceRegistry.SystemVoice> many = new ArrayList<>();
        for (int i = 0; i < 64; i++) many.add(new VoiceRegistry.SystemVoice("test.engine", "voice-" + i + new String(new char[200]).replace('\0', 'x'), i % 2 == 0 ? "vi" : "en", true));
        out.put("64 long system voices (wire budget)", assemble(true, ready, translateReady(), many, "ready", both));
        out.put("the plain build() without metadata", Capabilities.build(true, "local-vietnamese-ready", ready, translateReady(), "IDLE", "0.4.0", 5));
        out.put("the minor-1 build overload", Capabilities.build(true, "local-vietnamese-ready", status(vi, VoicePackManager.State.READY), "IDLE", "0.4.0", 5));
        return out;
    }

    @Test public void everyCurrentCapabilitiesReplyIsAcceptedByEveryReleasedAniBox() throws Exception {
        Map<String, JSONObject> replies = currentReplies();
        assertTrue(replies.size() >= 30);
        for (Map.Entry<String, JSONObject> e : replies.entrySet()) {
            String wire = e.getValue().toString();
            assertTrue(e.getKey() + " fits the 16384-unit limit", wire.length() <= 16384);
            for (Client client : ALL) {
                try {
                    client.parse(new JSONObject(wire));
                } catch (IllegalArgumentException rejected) {
                    fail("AniBox " + client.name + " would reject [" + e.getKey() + "]: " + rejected.getMessage());
                }
            }
        }
    }

    @Test public void theReadyReplyIsUsableByEveryReleasedAniBox() throws Exception {
        Map<String, JSONObject> replies = currentReplies();
        JSONObject ready = replies.get("vi=READY en=NONE");
        for (Client client : ALL) {
            Parsed p = client.parse(new JSONObject(ready.toString()));
            assertEquals(client.name, 2, p.minor);
            assertTrue(client.name + " reads the Vietnamese AI voice as ready", p.aiReady());
            assertTrue(client.name, p.aiReadyFor("vi"));
            assertFalse(client.name + " English pack not installed", p.aiReadyFor("en"));
            assertTrue(client.name + " system test voice", p.directText);
        }
        // Each pack state a user can see: AniBox's AI-ready decision agrees with AniSub's, never optimistic.
        for (Map.Entry<String, JSONObject> e : replies.entrySet()) {
            if (!e.getKey().startsWith("vi=")) continue;
            boolean viReady = e.getKey().startsWith("vi=READY");
            boolean enReady = e.getKey().endsWith("en=READY");
            for (Client client : ALL) {
                Parsed p = client.parse(new JSONObject(e.getValue().toString()));
                assertEquals(client.name + " " + e.getKey(), viReady, p.aiReady());
                assertEquals(client.name + " " + e.getKey(), viReady, p.aiReadyFor("vi"));
                assertEquals(client.name + " " + e.getKey(), enReady, p.aiReadyFor("en"));
            }
        }
    }

    @Test public void hotfixTwoReadsTheVoiceInventoryAndMayOfferTheDefaultVoiceId() throws Exception {
        Parsed p = HOTFIX2.parse(new JSONObject(currentReplies().get("vi=READY en=NONE").toString()));
        assertTrue(p.advertised.contains("ai:vi"));
        assertTrue(p.advertised.contains("system:vi"));
        assertEquals(Collections.singleton("vais1000"), p.usableVoices("ai", "vi"));
        assertEquals("a not-installed English pack offers no voice", Collections.emptySet(), p.usableVoices("ai", "en"));
        assertEquals(1, p.usableVoices("system", "vi").size());
        // Hotfix (no inventory) simply never offers voiceId.
        Parsed old = HOTFIX.parse(new JSONObject(currentReplies().get("vi=READY en=NONE").toString()));
        assertTrue(old.advertised.isEmpty());
        // Bigger inventories (both packs ready) stay unambiguous.
        Parsed both = HOTFIX2.parse(new JSONObject(currentReplies().get("both packs ready").toString()));
        assertEquals(Collections.singleton("ljspeech"), both.usableVoices("ai", "en"));
    }

    @Test public void legacyFieldsKeepTheirMeaning() throws Exception {
        for (Map.Entry<String, JSONObject> e : currentReplies().entrySet()) {
            JSONObject r = e.getValue();
            String who = e.getKey();
            assertEquals(who, "android-system-tts", r.getString("engine"));
            assertFalse(who + ": legacy translation stays false with the system engine", r.getBoolean("translation"));
            assertFalse(who, r.getBoolean("multiSpeaker"));
            assertTrue(who, r.getBoolean("directText"));
            assertEquals(who + ": protocol major", 1, r.getInt("major"));
            assertEquals(who, 1, r.getInt("protocolMajor"));
            if (r.has("minor")) assertEquals(who + ": additive minors only, this is minor 2", 2, r.getInt("minor"));
            // aiVoice / voicePack describe the default Vietnamese pack, not "any pack".
            boolean viReady = r.getJSONObject("voicePack").getString("state").equals("READY");
            assertEquals(who, viReady, r.getBoolean("aiVoice"));
            assertEquals(who, "vi-vais1000-medium", r.getJSONObject("voicePack").optString("id", "vi-vais1000-medium"));
        }
    }

    @Test public void theReleasedParsersStillRejectTheOldNearMiss() throws Exception {
        // AniSub 0.3.0 development once sent translation:true beside the system engine; minor-1 clients refuse that. The
        // test pins the rule so a "harmless" CAPABILITIES change cannot start a forced-update outage.
        JSONObject nearMiss = new JSONObject(currentReplies().get("vi=READY en=NONE").toString());
        nearMiss.put("minor", 1);
        nearMiss.put("translation", true);
        for (Client client : ALL) {
            try {
                client.parse(nearMiss);
                fail(client.name + " should reject minor-1 translation:true with the system engine");
            } catch (IllegalArgumentException expected) { }
        }
        JSONObject current = new JSONObject(currentReplies().get("vi=READY en=NONE").toString());
        current.put("translation", true);
        try {
            STRICT_FLOOR.parse(current);
            fail("a strict client rejects translation:true at minor 2");
        } catch (IllegalArgumentException expected) { }
    }

    // ================================================================== OPEN as the released AniBox builds it

    /** What the runtime has when the OPEN arrives (typical ready 0.4.0 state, configurable per case). */
    static final class Phone implements OpenRules.Env {
        Set<String> packs = new HashSet<>(Collections.singleton("vi"));
        Set<String> models = new HashSet<>(Arrays.asList("vi", "en"));
        Set<String> systemVoiceIds = new HashSet<>();
        boolean system = true, translate = true, detect = true;

        public boolean systemReady() { return system; }
        public boolean packReady(String lang) { return packs.contains(lang); }
        public Set<String> packVoices(String lang) {
            return packs.contains(lang) ? Collections.singleton("vi".equals(lang) ? "vais1000" : "ljspeech") : Collections.<String>emptySet();
        }
        public boolean systemVoiceReady(String id, String lang) { return systemVoiceIds.contains(id); }
        public boolean translateAvailable() { return translate; }
        public String translateUnavailableReason() { return translate ? null : "NO_ENGINE"; }
        public boolean modelReady(String lang) { return models.contains(lang); }
        public boolean detectAvailable() { return translate && detect; }
        public boolean autoDownloadModels() { return false; }
        public boolean canAutoDownload() { return false; }
    }

    private static JSONObject envelope(String type, String language) throws JSONException {
        return new JSONObject().put("major", 1).put("type", type).put("session", "s1").put("revision", 0).put("seq", 1)
                .put("positionMs", 9000).put("speed", 1.0).put("language", language).put("playing", true);
    }

    /** AniSubMessages.open(...) of v2.9.6-hotfix (and hotfix2 without a voiceId), for a runtime of {@code minor}. */
    static JSONObject open(String sourceLanguage, boolean ai, int minor, double rate, String voiceLang) throws JSONException {
        if (minor < 1) return envelope("OPEN", foldLanguage(sourceLanguage)).put("systemTest", true);
        JSONObject m = envelope("OPEN", minor >= 2 ? sourceLanguageTag(sourceLanguage) : foldLanguage(sourceLanguage));
        m.put("mode", ai ? "ai" : "system");
        if (!ai) m.put("systemTest", true);
        m.put("rate", Math.max(.8, Math.min(1.3, rate)));
        if (minor >= 2 && voiceLang != null) m.put("voiceLang", "en".equals(voiceLang) ? "en" : "vi");
        return m;
    }

    /** AniSubProtocol.language: only vi / en / und travel to a minor-1 runtime. */
    static String foldLanguage(String v) {
        String l = v == null ? "" : v.toLowerCase(java.util.Locale.ROOT);
        if (l.equals("vi") || l.equals("vie") || l.startsWith("vi-")) return "vi";
        if (l.equals("en") || l.equals("eng") || l.startsWith("en-")) return "en";
        return "und";
    }

    /** AniSubProtocol.sourceLanguage for a minor-2 session: the BCP-47 primary tag, "und" when not well formed. */
    static String sourceLanguageTag(String v) {
        String folded = foldLanguage(v);
        if (!"und".equals(folded)) return folded;
        String code = v == null ? "" : v.toLowerCase(java.util.Locale.ROOT).split("-")[0];
        if (code.equals("jpn")) code = "ja";
        return code.matches("[a-z]{2,3}") ? code : "und";
    }

    /** One replayed OPEN and what the current AniSub must answer. */
    private static final class Case {
        final String name;
        final Client[] clients;
        final JSONObject message;
        final String sourceLanguage;
        final Phone phone;
        final String error;
        final String mode, voiceLang, voiceId;
        final boolean translate;

        Case(String name, Client[] clients, JSONObject message, Phone phone, String error, String mode, String voiceLang,
             boolean translate, String voiceId) throws JSONException {
            this.name = name; this.clients = clients; this.message = message; this.sourceLanguage = message.getString("language");
            this.phone = phone; this.error = error; this.mode = mode; this.voiceLang = voiceLang; this.translate = translate;
            this.voiceId = voiceId;
        }
    }

    static Phone noPacks() {
        Phone phone = new Phone();
        phone.packs.clear();
        return phone;
    }

    static List<Case> cases() throws Exception {
        List<Case> out = new ArrayList<>();
        Phone ready = new Phone();
        Phone bothPacks = new Phone();
        bothPacks.packs.add("en");
        Phone systemPhone = new Phone();
        String sysId = VoiceRegistry.systemId("com.google.android.tts", "vi-vn-x-gft-local");
        systemPhone.systemVoiceIds.add(sysId);
        // --- hotfix and hotfix2, runtime minor 2 (what they send to AniSub 0.3.x / 0.4.0)
        out.add(new Case("AI, Vietnamese file, Vietnamese voice", RELEASED, open("vi", true, 2, 1.0, "vi"), ready, null, "ai", "vi", false, null));
        out.add(new Case("AI, English file translated into Vietnamese", RELEASED, open("en", true, 2, 1.0, "vi"), ready, null, "ai", "vi", true, null));
        out.add(new Case("AI, region-tagged English (en-US)", RELEASED, open("en-US", true, 2, 1.1, "vi"), ready, null, "ai", "vi", true, null));
        out.add(new Case("AI, English file read by the English voice", RELEASED, open("en", true, 2, 1.0, "en"), bothPacks, null, "ai", "en", false, null));
        out.add(new Case("AI, undetermined file language", RELEASED, open("und", true, 2, 1.0, "vi"), ready, null, "ai", "vi", true, null));
        Phone japanese = new Phone();
        japanese.models.add("ja");
        out.add(new Case("AI, Japanese file with the ja model", RELEASED, open("jpn", true, 2, 1.0, "vi"), japanese, null, "ai", "vi", true, null));
        out.add(new Case("AI, Japanese file without the ja model", RELEASED, open("ja", true, 2, 1.0, "vi"), ready, "TRANSLATE_MODEL_MISSING", null, null, false, null));
        out.add(new Case("AI, English voice without the English pack", RELEASED, open("en", true, 2, 1.0, "en"), ready, "VOICE_PACK_MISSING", null, null, false, null));
        out.add(new Case("system voice test, Vietnamese", RELEASED, open("vi", false, 2, 1.0, "vi"), ready, null, "system", "vi", false, null));
        out.add(new Case("rate bounds are clamped by the client", RELEASED, open("vi", true, 2, 9.0, "vi"), ready, null, "ai", "vi", false, null));
        // --- hotfix2 only: voiceId
        out.add(new Case("AI with the saved default voice id", new Client[]{HOTFIX2},
                open("vi", true, 2, 1.0, "vi").put("voiceId", "vais1000"), ready, null, "ai", "vi", false, "vais1000"));
        out.add(new Case("AI English file, saved voice id, translated", new Client[]{HOTFIX2},
                open("en", true, 2, 1.0, "vi").put("voiceId", "vais1000"), ready, null, "ai", "vi", true, "vais1000"));
        out.add(new Case("AI English voice with its saved voice id", new Client[]{HOTFIX2},
                open("en", true, 2, 1.0, "en").put("voiceId", "ljspeech"), bothPacks, null, "ai", "en", false, "ljspeech"));
        out.add(new Case("system voice with a saved system voice id", new Client[]{HOTFIX2},
                open("vi", false, 2, 1.0, "vi").put("voiceId", sysId), systemPhone, null, "system", "vi", false, sysId));
        // --- the minor-1 form: what an AniBox sends to a minor-1 runtime (and what a minor-1-only dev build sent)
        out.add(new Case("minor-1 form: AI Vietnamese, no voiceLang", RELEASED, open("vi", true, 1, 1.0, null), ready, null, "ai", "vi", false, null));
        out.add(new Case("minor-1 form: system voice test", RELEASED, open("vi", false, 1, 1.0, null), ready, null, "system", "vi", false, null));
        out.add(new Case("minor-1 form: pack missing", RELEASED, open("vi", true, 1, 1.0, null), noPacks(),
                "VOICE_PACK_MISSING", null, null, false, null));
        out.add(new Case("minor-0 form: bare systemTest OPEN", RELEASED, open("vi", false, 0, 1.0, null), ready, null, "system", "vi", false, null));
        return out;
    }

    @Test public void replayedOpensAreAdmittedAsEachAniBoxExpects() throws Exception {
        List<Case> cases = cases();
        assertTrue(cases.size() >= 18);
        for (Case c : cases) {
            String who = c.name + " " + c.message;
            // The envelope the service validates before OpenRules.
            assertTrue(who, PayloadRules.session(c.message.getLong("positionMs"), c.message.getDouble("speed"), c.sourceLanguage));
            assertTrue(who, c.message.toString().length() <= 16384);
            OpenRules.Decision d = OpenRules.decide(c.message, c.sourceLanguage, c.phone, 1f);
            if (c.error != null) {
                assertEquals(who, c.error, d.error);
                continue;
            }
            assertTrue(who + " rejected with " + d.error, d.accepted());
            assertEquals(who, c.mode, d.mode);
            assertEquals(who, c.voiceLang, d.voiceLang);
            assertEquals(who, c.translate, d.translate);
            assertEquals(who, c.voiceId, d.voiceId);
            assertTrue(who + " rate within the user bounds", d.rate >= .8f && d.rate <= 1.3f);
        }
        Set<String> clients = new HashSet<>();
        for (Case c : cases) for (Client client : c.clients) clients.add(client.name);
        assertEquals(new HashSet<>(Arrays.asList("2.9.6-hotfix", "2.9.6-hotfix2")), clients);
    }

    @Test public void cuesAsReleasedAniBoxesSendThemFitTheSessionRules() throws Exception {
        for (boolean timeline : new boolean[]{false, true}) {
            JSONObject message = envelope("CUES", "en");
            if (timeline) message.put("timeline", true);
            JSONArray cues = new JSONArray();
            cues.put(new JSONObject().put("id", "t1-0").put("text", "Hello there.").put("startMs", 10000).put("endMs", 12500).put("role", "dialogue"));
            cues.put(new JSONObject().put("id", "t1-1").put("text", "Hmm").put("startMs", 12600).put("endMs", -1).put("role", "unknown"));
            message.put("cues", cues);
            assertTrue(PayloadRules.session(9000, 1.0, "en"));
            for (int i = 0; i < cues.length(); i++) {
                JSONObject cue = cues.getJSONObject(i);
                assertTrue(cue.toString(), PayloadRules.cue(cue.getString("id"), cue.getString("text"), cue.getLong("startMs"), cue.getLong("endMs"), cue.getString("role")));
            }
            // An empty timeline batch is AniBox's "keep the clock anchored" heartbeat: must stay legal.
            message.put("cues", new JSONArray());
            assertTrue(message.toString().length() < 16384);
        }
    }

    @Test public void clientsBelowMinorTwoNeverSeeAnyRuntimeOnlyField() throws Exception {
        // Documents the OPEN fields each released AniBox may send; OpenRules must not require any other.
        Set<String> minorOneFields = new HashSet<>(Arrays.asList("major", "type", "session", "revision", "seq", "positionMs", "speed",
                "language", "playing", "mode", "systemTest", "rate"));
        for (Case c : cases()) {
            if (!c.name.startsWith("minor-")) continue;
            for (java.util.Iterator<String> it = c.message.keys(); it.hasNext(); ) {
                String key = it.next();
                assertTrue(c.name + " field " + key, minorOneFields.contains(key));
            }
        }
    }
}
