package com.anisub.runtime;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.*;
import com.anisub.runtime.ai.AiSpeechEngine;
import com.anisub.runtime.ai.NarrationPipeline;
import com.anisub.runtime.ai.TextSplitter;
import com.anisub.runtime.voice.VoiceCatalog;
import com.anisub.runtime.voice.VoicePackManager;
import org.json.*;
import java.util.*;

/**
 * Bounded, authenticated Binder host, protocol major 1 / minor 1.
 * mode "system": explicit system-TTS test (legacy). mode "ai": on-device sherpa-onnx voice from a
 * verified pack. No capture, translation or network use here; downloads happen only from the
 * settings screen after explicit consent. Subtitle text is never logged.
 */
public final class AniSubService extends Service {
    private static final int LIMIT = 16384;
    private static final long SYSTEM_WATCHDOG_MS = 30000, AI_WATCHDOG_CAP_MS = 120000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SessionGate gate = new SessionGate();
    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private final RecentCueIds seen = new RecentCueIds();
    private SpeechEngine engine;
    private RuntimeHost host;
    private Messenger messenger, peer;
    private IBinder.DeathRecipient death;
    private String session, mode = OpenRules.MODE_SYSTEM;
    private long revision, position, anchor, counter;
    private double speed = 1;
    private float userRate = 1f;
    private boolean playing;
    private Job active;
    private String lastCapabilities;
    private static final class Job {
        String id, text, session, utterance; long revision, start, end; boolean started;
    }
    private final AiSpeechEngine.Listener aiListener = new AiSpeechEngine.Listener() {
        public void started(String id) { if (active != null && id.equals(active.utterance) && !active.started) { active.started = true; event("STARTED", active, null); } }
        public void finished(String id) { terminal(id, null); }
        public void failed(String id, String code) { terminal(id, code == null ? "PROVIDER_FAILED" : code); }
        public void engineChanged(AiSpeechEngine.State state, String error) {
            if (state == AiSpeechEngine.State.FAILED && OpenRules.MODE_AI.equals(mode) && session != null) failQueued(error);
            drain();
        }
    };
    private final Runnable hostChanged = this::pushCapabilitiesIfChanged;

    @Override public void onCreate() {
        super.onCreate();
        messenger = new Messenger(new Handler(Looper.getMainLooper()) {
            @Override public void handleMessage(Message message) { receive(message); }
        });
        host = RuntimeHost.get(this);
        host.addListener(hostChanged);
        host.addEngineListener(aiListener);
        engine = new SystemTestSpeechEngine(this, new SpeechEngine.Listener() {
            public void started(String id) { handler.post(() -> { if (active != null && id.equals(active.utterance) && !active.started) { active.started = true; event("STARTED", active, null); } }); }
            public void finished(String id) { handler.post(() -> terminal(id, null)); }
            public void failed(String id) { handler.post(() -> terminal(id, "PROVIDER_FAILED")); }
        }, () -> handler.post(this::capabilities));
    }
    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }
    @Override public boolean onUnbind(Intent intent) { disconnect(); return false; }
    @Override public void onDestroy() {
        disconnect(); engine.release();
        host.removeListener(hostChanged);
        host.removeEngineListener(aiListener);
        super.onDestroy();
    }

    /** UID -> exact package -> same signing certificate. No install-time permission involved. */
    private boolean trusted(int uid) {
        final PackageManager pm = getPackageManager();
        return CallerPolicy.trusted(uid, new CallerPolicy.Packages() {
            public String[] packagesForUid(int u) { return pm.getPackagesForUid(u); }
            public boolean sameSignerAsSelf(String pkg) { return pm.checkSignatures(pkg, getPackageName()) == PackageManager.SIGNATURE_MATCH; }
        }, BuildConfig.DEBUG);
    }

    private void receive(Message message) {
        // Caller is validated BEFORE unparcelling any Bundle or accepting replyTo.
        if (message.what != 1 || !trusted(message.sendingUid)) return;
        try {
            Bundle data = message.getData();
            String payload = data.getString("payload");
            if (payload == null || payload.length() > LIMIT || message.replyTo == null) return;
            JSONObject input = new JSONObject(payload);
            if (strictLong(input, "major") != 1) return;
            String type = input.getString("type");
            if ("HELLO".equals(type)) {
                if (peer == null || !peer.getBinder().equals(message.replyTo.getBinder())) {
                    disconnect(); peer = message.replyTo;
                    death = () -> handler.post(this::disconnect);
                    peer.getBinder().linkToDeath(death, 0);
                }
                lastCapabilities = null; capabilities(); return;
            }
            if (peer == null || !peer.getBinder().equals(message.replyTo.getBinder())) return;
            if ("GET_CAPABILITIES".equals(type)) { lastCapabilities = null; capabilities(); return; }
            Set<String> types = new HashSet<>(Arrays.asList("OPEN", "CUES", "PLAY", "PAUSE", "SEEK", "STOP", "EPISODE_CHANGE", "SOURCE_CHANGE", "PLAYBACK_SPEED", "CLOSE"));
            if (!types.contains(type)) { error("UNSUPPORTED", null); return; }
            String id = input.getString("session");
            long rev = strictLong(input, "revision"), seq = strictLong(input, "seq"), pos = strictLong(input, "positionMs");
            Object rawRate = input.get("speed");
            if (!(rawRate instanceof Number)) throw new JSONException("number required");
            double rate = ((Number)rawRate).doubleValue();
            String language = input.getString("language");
            if (!PayloadRules.clock(pos, rate, language)) throw new JSONException("bounds");
            OpenRules.Decision decision = null;
            if ("OPEN".equals(type)) {
                VoicePackManager.Status pack = host.packStatus();
                decision = OpenRules.decide(input, language, engine.ready(), pack != null && pack.ready() && host.engine() != null,
                        voiceIds(pack), host.defaultRate());
                if (!decision.accepted()) { error(decision.error, null); return; }
            }
            if (input.has("playing") && !(input.get("playing") instanceof Boolean)) throw new JSONException("boolean required");
            List<Job> incoming = "CUES".equals(type) ? parseCues(input, id, rev, language) : Collections.<Job>emptyList();
            if (!gate.accept(type, id, rev, seq)) { error("STALE", null); return; }
            if ("OPEN".equals(type) || rev != revision || !Objects.equals(session, id)) { cancel(); seen.clear(); }
            session = id; revision = rev; position = pos; speed = rate; anchor = SystemClock.elapsedRealtime();
            if ("CUES".equals(type)) {
                // An empty active snapshot clears pending captions, not a phrase already started.
                if (incoming.isEmpty()) queue.clear();
                for (Job job : incoming) {
                    if (job == null) continue;
                    if (seen.contains(job.id)) continue;
                    if (queue.size() >= 8) { event("ERROR", job, "BACKPRESSURE"); continue; }
                    if (job.end >= 0 && job.end <= mediaNow()) { event("ERROR", job, "EXPIRED"); continue; }
                    if (job.start > mediaNow() + 5000) { event("ERROR", job, "OUTSIDE_HORIZON"); continue; }
                    if (OpenRules.MODE_AI.equals(mode) && !TextSplitter.speakable(job.text)) continue;
                    seen.add(job.id);
                    job.utterance = "speech-" + (++counter);
                    queue.add(job);
                }
            } else if ("PLAY".equals(type)) playing = true;
            else if ("OPEN".equals(type)) {
                playing = input.optBoolean("playing", true);
                mode = decision.mode; userRate = decision.rate;
                boolean ai = OpenRules.MODE_AI.equals(mode);
                host.setSessionActive(ai);
                if (ai) { host.engine().setVoice(decision.voiceId != null ? decision.voiceId : host.defaultVoice()); host.engine().load(); }
                else if (host.engine() != null) host.engine().scheduleIdleUnload(); // free native memory after an AI session
            }
            else {
                cancel(); seen.clear();
                if ("PAUSE".equals(type) || "STOP".equals(type) || "CLOSE".equals(type)
                        || "EPISODE_CHANGE".equals(type) || "SOURCE_CHANGE".equals(type)) playing = false;
                if ("CLOSE".equals(type)) { session = null; endAiSession(); }
            }
            drain();
        } catch (JSONException | BadParcelableException | ClassCastException | RemoteException e) {
            error("MALFORMED", null);
        }
    }
    private static Set<String> voiceIds(VoicePackManager.Status pack) {
        Set<String> ids = new HashSet<>();
        if (pack != null && pack.pack != null) for (VoiceCatalog.Voice v : pack.pack.voices) ids.add(v.id);
        return ids;
    }
    private static long strictLong(JSONObject object, String key) throws JSONException {
        Object raw = object.get(key);
        if (!(raw instanceof Integer) && !(raw instanceof Long)) throw new JSONException("integer required");
        return ((Number)raw).longValue();
    }
    private List<Job> parseCues(JSONObject input, String id, long rev, String language) throws JSONException {
        JSONArray cues = input.getJSONArray("cues");
        if (cues.length() > 16 || !"vi".equals(language)) throw new JSONException("cue bounds");
        List<Job> jobs = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < cues.length(); i++) {
            JSONObject cue = cues.getJSONObject(i);
            Job job = new Job(); job.id = cue.getString("id"); job.text = cue.getString("text");
            job.start = strictLong(cue, "startMs"); job.end = strictLong(cue, "endMs");
            String role = cue.getString("role");
            if (!PayloadRules.cue(job.id, job.text, job.start, job.end, role) || !ids.add(job.id)) throw new JSONException("cue invalid");
            job.session = id; job.revision = rev;
            if (!"annotation".equals(role) && !job.text.trim().isEmpty()) jobs.add(job);
        }
        return jobs;
    }
    private long mediaNow() { return position + (playing ? (long)((SystemClock.elapsedRealtime() - anchor) * speed) : 0); }
    private boolean aiMode() { return OpenRules.MODE_AI.equals(mode) && host.engine() != null; }

    /** Wall-clock window left for a job, or -1 when its end is unknown. */
    private long windowMs(Job job) {
        if (job.end < 0) return -1;
        long from = Math.max(mediaNow(), job.start);
        return Math.max(1, (long) ((job.end - from) / Math.max(0.5, speed)));
    }
    private float aiRate(Job job) { return host.rates().choose(job.text.length(), windowMs(job), userRate); }

    private void drain() {
        if (!playing || active != null || session == null) { prefetch(); return; }
        if (aiMode() && host.engine().state() == AiSpeechEngine.State.FAILED) {
            // No silent fallback to the system voice: every queued AI cue fails visibly.
            String code = packProblem(host.engine().error()) ? OpenRules.VOICE_PACK_MISSING : "PROVIDER_FAILED";
            while (!queue.isEmpty()) event("ERROR", queue.remove(), code);
            return;
        }
        if (aiMode() && host.engine().state() == AiSpeechEngine.State.IDLE) host.engine().load(); // unloaded mid-session: reload
        if (aiMode() && !host.engine().ready()) {
            // Expire what can no longer be spoken while the engine loads; keep the rest bounded.
            Iterator<Job> it = queue.iterator();
            while (it.hasNext()) { Job j = it.next(); if (j.end >= 0 && j.end <= mediaNow()) { it.remove(); event("ERROR", j, "EXPIRED"); } }
            return;
        }
        while (!queue.isEmpty()) {
            Job job = queue.peek();
            if (!session.equals(job.session) || revision != job.revision || (job.end >= 0 && job.end <= mediaNow())) {
                queue.remove(); event("ERROR", job, "EXPIRED"); continue;
            }
            if (job.start > mediaNow()) { handler.removeCallbacks(wake); handler.postDelayed(wake, Math.max(1, (long)((job.start - mediaNow()) / speed))); prefetch(); return; }
            active = queue.remove();
            final String watchdogId = active.utterance;
            handler.removeCallbacks(watchdog);
            watchdog = () -> { if (active != null && watchdogId.equals(active.utterance)) { stopEngines(); terminal(watchdogId, "TIMEOUT"); } };
            boolean accepted;
            if (aiMode()) {
                handler.postDelayed(watchdog, aiWatchdogMs(active));
                accepted = host.engine().speak(active.utterance, active.text, aiRate(active));
            } else {
                handler.postDelayed(watchdog, SYSTEM_WATCHDOG_MS);
                accepted = engine.speak(active.text, active.utterance);
            }
            if (!accepted) terminal(active.utterance, "PROVIDER_FAILED");
            prefetch();
            return;
        }
        prefetch();
    }
    /** Prepare up to two upcoming utterances ahead (AI mode only); drop prefetches no longer queued. */
    private void prefetch() {
        if (!aiMode() || !host.engine().ready()) return;
        Set<String> keep = new HashSet<>();
        int n = 0;
        for (Job job : queue) {
            if (n >= NarrationPipeline.MAX_AHEAD) break;
            if (!session.equals(job.session) || revision != job.revision) continue;
            keep.add(job.utterance); n++;
            host.engine().prefetch(job.utterance, job.text, aiRate(job));
        }
        host.engine().retainOnly(keep);
    }
    private long aiWatchdogMs(Job job) {
        double units = job.text.length();
        double expectedMs = units / host.rates().unitsPerSecond() * 1000.0;
        return Math.min(AI_WATCHDOG_CAP_MS, (long) (expectedMs * 2 + 15000));
    }
    private final Runnable wake = this::drain;
    private Runnable watchdog = () -> { };
    private void terminal(String id, String failure) {
        if (active == null || !id.equals(active.utterance)) return;
        handler.removeCallbacks(watchdog);
        Job done = active; active = null;
        event(failure != null ? "ERROR" : "FINISHED", done, failure);
        if (failure != null && done.started) event("FINISHED", done, "FAILED");
        drain();
    }
    private void failQueued(String engineError) {
        // Session-level notice once; drain() fails the queued cues individually.
        error(packProblem(engineError) ? OpenRules.VOICE_PACK_MISSING : "PROVIDER_FAILED", null);
    }
    private static boolean packProblem(String engineError) {
        return "MODEL_CORRUPT".equals(engineError) || OpenRules.VOICE_PACK_MISSING.equals(engineError);
    }
    private void stopEngines() {
        engine.stop();
        if (host.engine() != null) host.engine().stop();
    }
    private void cancel() {
        handler.removeCallbacks(wake); handler.removeCallbacks(watchdog); queue.clear();
        Job cancelled = active; active = null; stopEngines();
        if (cancelled != null) event("FINISHED", cancelled, "CANCELLED");
    }
    private void endAiSession() {
        if (host.engine() != null) { host.engine().stop(); host.engine().scheduleIdleUnload(); }
        host.setSessionActive(false);
        mode = OpenRules.MODE_SYSTEM;
    }
    private void disconnect() {
        if (engine != null) cancel(); gate.closeCurrent(); session = null; playing = false; seen.clear();
        if (host != null) endAiSession();
        if (peer != null && death != null) peer.getBinder().unlinkToDeath(death, 0);
        peer = null; death = null; lastCapabilities = null;
    }
    private void pushCapabilitiesIfChanged() { if (peer != null) capabilities(); }
    private void capabilities() {
        try {
            JSONObject result = Capabilities.build(engine != null && engine.ready(), engine == null ? null : engine.state(),
                    host.packStatus(), host.engineState(), host.versionName(), host.versionCode());
            // Progress ticks alone do not spam the client; state/engine changes always go out.
            JSONObject key = new JSONObject(result.toString());
            key.getJSONObject("voicePack").remove("doneBytes");
            String signature = key.toString();
            if (signature.equals(lastCapabilities)) return;
            lastCapabilities = signature;
            send(result);
        } catch (JSONException ignored) { }
    }
    private void event(String type, Job job, String reason) {
        JSONObject result = new JSONObject();
        try { result.put("type", type).put("major", 1).put("session", job.session).put("revision", job.revision).put("cueId", job.id); if (reason != null) result.put("code", reason); send(result); }
        catch (JSONException ignored) { }
    }
    private void error(String code, String cueId) {
        JSONObject result = new JSONObject();
        try { result.put("type", "ERROR").put("major", 1).put("code", code); if (session != null) result.put("session", session).put("revision", revision); if (cueId != null) result.put("cueId", cueId); send(result); }
        catch (JSONException ignored) { }
    }
    private void send(JSONObject reply) {
        if (peer == null) return;
        Message message = Message.obtain(null, 1); Bundle data = new Bundle(); data.putString("payload", reply.toString()); message.setData(data);
        try { peer.send(message); } catch (RemoteException e) { disconnect(); }
    }
}
