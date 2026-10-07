package com.anisub.runtime;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.*;
import com.anisub.runtime.ai.AiSpeechEngine;
import com.anisub.runtime.ai.NarrationPipeline;
import com.anisub.runtime.ai.TextSplitter;
import com.anisub.runtime.translate.CueTimeline;
import com.anisub.runtime.translate.LanguageTags;
import com.anisub.runtime.translate.MlKitTranslation;
import com.anisub.runtime.translate.TextCleaner;
import com.anisub.runtime.translate.TranslationScheduler;
import com.anisub.runtime.voice.VoiceCatalog;
import com.anisub.runtime.voice.VoicePackManager;
import org.json.*;
import java.util.*;

/**
 * Bounded, authenticated Binder host, protocol major 1 / minor 2.
 * mode "system": explicit system-TTS test (legacy, Vietnamese only). mode "ai": on-device
 * sherpa-onnx voice of the OPEN {@code voiceLang} pack ("vi" by default). Minor 2: cues in another
 * language are translated on the device (ML Kit) into the voice language, ahead of time for cues
 * sent in advance ("timeline"); "und" cues are language-identified first. No capture; network only
 * for consented downloads from the settings screen. Subtitle text is never logged.
 */
public final class AniSubService extends Service {
    private static final int LIMIT = 16384, QUEUE = 8;
    private static final long SYSTEM_WATCHDOG_MS = 30000, AI_WATCHDOG_CAP_MS = 120000;
    /** Cues starting further ahead than this are kept in the timeline, not the speech queue. */
    static final long HORIZON_MS = 5000;
    static final long TICK_MS = 500, DETECT_WAIT_MS = 1500, DETECT_TIMEOUT_MS = 10_000;
    static final int DETECT_UNITS = 300, DETECT_CUES = 6, LOOKAHEAD_BATCH = 64;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SessionGate gate = new SessionGate();
    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private final RecentCueIds seen = new RecentCueIds();
    private final CueTimeline timeline = new CueTimeline();
    private final CueIntake intake = new CueIntake(seen, timeline);
    private TranslationScheduler scheduler;
    private SpeechEngine engine;
    private RuntimeHost host;
    private Messenger messenger, peer;
    private IBinder.DeathRecipient death;
    private String session, mode = OpenRules.MODE_SYSTEM;
    private String voiceLang = LanguageTags.VI;
    /** Translation for this session: on, the source ("und" until detected) and a fatal failure code. */
    private boolean translating;
    private List<String> openDownload;
    private final ArrayDeque<String> modelQueue = new ArrayDeque<>();
    private boolean modelWait;
    private String modelStarted;
    private long modelGeneration, lastProgress;
    private Runnable modelPoll = () -> { };
    private final java.util.concurrent.ExecutorService progressWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    static final long MODEL_POLL_MS = 2000;
    /** The OPEN decision's translation setting, restored when the content changes (EPISODE/SOURCE_CHANGE). */
    private boolean openTranslate;
    private String openSource = LanguageTags.VI;
    private String translateFailure;
    private JSONObject translateFailureDetail;
    private boolean detecting, detectRequested;
    private final StringBuilder detectText = new StringBuilder();
    private int detectCues;
    private long detectGeneration;
    private long revision, position, anchor, counter;
    private double speed = 1;
    private float userRate = 1f;
    private boolean playing;
    private Job active;
    private String lastCapabilities;
    private static final class Job {
        String id, text, clean, spoken, session, utterance; long revision, start, end; boolean started, timeline;
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
        scheduler = new TranslationScheduler(host.translation(), handler::post, (task, ms) -> {
            handler.postDelayed(task, ms);
            return () -> handler.removeCallbacks(task);
        }, new TranslationScheduler.Listener() {
            public void translated(String cueId) { drain(); }
            public void failed(String cueId, String code) { drain(); }
        });
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
        // Debug builds only: the in-process test harness (src/debug) drives a real session.
        if (BuildConfig.DEBUG && uid == android.os.Process.myUid()) return true;
        final PackageManager pm = getPackageManager();
        return CallerPolicy.trusted(uid, new CallerPolicy.Packages() {
            public String[] packagesForUid(int u) { return pm.getPackagesForUid(u); }
            public boolean sameSignerAsSelf(String pkg) { return pm.checkSignatures(pkg, getPackageName()) == PackageManager.SIGNATURE_MATCH; }
        }, BuildConfig.DEBUG);
    }

    private OpenRules.Env env() {
        final MlKitTranslation tr = host.translation();
        return new OpenRules.Env() {
            public boolean systemReady() { return engine.ready(); }
            public boolean packReady(String lang) { VoicePackManager.Status s = host.packStatus(lang); return s != null && s.ready() && host.engine() != null; }
            public Set<String> packVoices(String lang) { return voiceIds(host.packStatus(lang)); }
            public boolean translateAvailable() { return tr != null && tr.available(); }
            public String translateUnavailableReason() { return tr == null ? "NO_ENGINE" : tr.unavailableReason(); }
            public boolean modelReady(String lang) { return tr != null && tr.modelReady(lang); }
            public boolean detectAvailable() { return tr != null && tr.detectAvailable(); }
            public boolean autoDownloadModels() { return host.autoDownloadModels(); }
            public boolean canAutoDownload() { return tr != null && tr.available() && host.canAutoDownloadModel() || tr != null && tr.busy(); }
        };
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
            if (!PayloadRules.session(pos, rate, language)) throw new JSONException("bounds");
            OpenRules.Decision decision = null;
            if ("OPEN".equals(type)) {
                decision = OpenRules.decide(input, language, env(), host.defaultRate());
                if (!decision.accepted()) { error(decision.error, null, decision.detail); return; }
            }
            if (input.has("playing") && !(input.get("playing") instanceof Boolean)) throw new JSONException("boolean required");
            boolean timelineBatch = false;
            if (input.has("timeline")) {
                Object raw = input.get("timeline");
                if (!(raw instanceof Boolean)) throw new JSONException("boolean required");
                timelineBatch = (Boolean) raw;
            }
            List<Job> incoming = "CUES".equals(type) ? parseCues(input, id, rev, language) : Collections.<Job>emptyList();
            if (!gate.accept(type, id, rev, seq)) { error("STALE", null); return; }
            // The system-voice test stays Vietnamese-only; AI mode accepts any source language (minor 2).
            if ("CUES".equals(type) && Objects.equals(session, id) && !OpenRules.MODE_AI.equals(mode) && !"vi".equals(language)) throw new JSONException("cue language");
            boolean newSession = "OPEN".equals(type) || !Objects.equals(session, id);
            if (newSession || rev != revision) {
                cancel(); intake.restart(pos);
                if (newSession) { openTranslate = false; openDownload = null; resetContent(); }
            }
            session = id; revision = rev; position = pos; speed = rate; anchor = SystemClock.elapsedRealtime();
            if ("CUES".equals(type)) {
                // An empty active snapshot clears pending captions (not timeline cues, not a phrase already started).
                if (incoming.isEmpty() && !timelineBatch) removeSnapshotJobs();
                for (Job job : incoming) {
                    if (job == null) continue;
                    CueIntake.Route route = intake.route(job.id, job.start, mediaNow(), timelineBatch);
                    if (route == CueIntake.Route.TIMELINE) { addToTimeline(job); continue; }
                    if (route == CueIntake.Route.DUPLICATE) continue; // queued/spoken already, or the timeline owns it
                    if (queue.size() >= QUEUE) { event("ERROR", job, "BACKPRESSURE"); continue; }
                    if (job.end >= 0 && job.end <= mediaNow()) { event("ERROR", job, "EXPIRED"); continue; }
                    if (aiMode() && !TextSplitter.speakable(job.clean)) continue;
                    intake.queued(job.id);
                    job.utterance = "speech-" + (++counter);
                    queue.add(job);
                    noteForDetection(job.clean);
                }
            } else if ("PLAY".equals(type)) playing = true;
            else if ("OPEN".equals(type)) {
                playing = input.optBoolean("playing", true);
                mode = decision.mode; userRate = decision.rate; voiceLang = decision.voiceLang;
                boolean ai = OpenRules.MODE_AI.equals(mode);
                host.setSessionActive(ai);
                if (ai) {
                    openTranslate = decision.translate; openSource = decision.source; openDownload = decision.download;
                    resetContent();
                    // A session without translation frees any translator/identifier a previous one left.
                    if (!openTranslate && host.translation() != null) host.translation().releaseClients();
                    host.engine().setLanguage(voiceLang);
                    host.engine().setVoice(decision.voiceId != null ? decision.voiceId : host.defaultVoice());
                    host.engine().load();
                }
                else if (host.engine() != null) host.engine().scheduleIdleUnload(); // free native memory after an AI session
            }
            else {
                cancel(); intake.restart(pos);
                if ("PAUSE".equals(type) || "STOP".equals(type) || "CLOSE".equals(type)
                        || "EPISODE_CHANGE".equals(type) || "SOURCE_CHANGE".equals(type)) playing = false;
                if ("EPISODE_CHANGE".equals(type) || "SOURCE_CHANGE".equals(type)) resetContent();
                if ("CLOSE".equals(type)) { openTranslate = false; openDownload = null; resetContent(); session = null; endAiSession(); }
            }
            scheduleTick();
            drain();
        } catch (JSONException | BadParcelableException | ClassCastException | RemoteException e) {
            error("MALFORMED", null);
        }
    }

    /**
     * New session or new content (episode/source): timeline, translations and detection start over
     * with the OPEN decision's pair ("und" is detected again for the new content).
     */
    private void resetContent() {
        stopModelWait();
        timeline.clear();
        translating = openTranslate;
        if (translating) scheduler.reset(openSource, voiceLang); else scheduler.clear();
        translateFailure = null; translateFailureDetail = null;
        detecting = translating && LanguageTags.UND.equals(openSource);
        detectRequested = false; detectText.setLength(0); detectCues = 0; detectGeneration++;
        handler.removeCallbacks(detectTimeout);
        if (translating && openDownload != null && !openDownload.isEmpty()) waitForModels(openDownload);
    }

    // ------------------------------------------------------------------ missing models: automatic download
    /**
     * "Tự tải gói dịch khi cần": the session's missing ML Kit models download one at a time through the
     * system DownloadManager; AniBox gets TRANSLATE_MODEL_DOWNLOADING (with progress) and then
     * TRANSLATE_MODEL_READY, and speech starts once they are installed. A failure ends translation for
     * the session with TRANSLATE_MODEL_MISSING {reason:"DOWNLOAD_FAILED"|"NO_SPACE"}.
     */
    private void waitForModels(List<String> langs) {
        modelQueue.clear(); modelQueue.addAll(langs); modelWait = true; modelStarted = null; final long gen = ++modelGeneration;
        lastProgress = -2;
        handler.removeCallbacks(modelPoll);
        modelPoll = () -> pollModels(gen);
        handler.post(modelPoll);
    }
    private void stopModelWait() { modelWait = false; modelQueue.clear(); modelGeneration++; handler.removeCallbacks(modelPoll); }
    private void pollModels(final long gen) {
        if (gen != modelGeneration || !modelWait || session == null) return;
        final MlKitTranslation tr = host.translation();
        while (!modelQueue.isEmpty() && tr.modelReady(modelQueue.peek())) modelQueue.poll();
        if (modelQueue.isEmpty()) {
            modelWait = false;
            JSONObject ready = new JSONObject();
            try { send(ready.put("type", "TRANSLATE_MODEL_READY").put("major", 1).put("session", session).put("revision", revision)); } catch (JSONException ignored) { }
            drain();
            return;
        }
        final String lang = modelQueue.peek();
        if (!tr.busy()) {
            if (lang.equals(modelStarted)) { // ours finished without installing it
                modelWait = false;
                try { failTranslation(OpenRules.TRANSLATE_MODEL_MISSING, new JSONObject().put("language", lang).put("voiceLang", voiceLang)
                        .put("missing", new JSONArray(new ArrayList<>(modelQueue))).put("reason", "DOWNLOAD_FAILED")); } catch (JSONException ignored) { }
                drain();
                return;
            }
            if (!host.startModelDownload(lang, ok -> handler.post(modelPoll))) {
                modelWait = false;
                try { failTranslation(OpenRules.TRANSLATE_MODEL_MISSING, new JSONObject().put("language", lang).put("voiceLang", voiceLang)
                        .put("missing", new JSONArray(new ArrayList<>(modelQueue))).put("reason", "NO_SPACE")); } catch (JSONException ignored) { }
                drain();
                return;
            }
            modelStarted = lang;
        }
        progressWorker.execute(() -> {
            final long[] p = tr.downloadProgress();
            handler.post(() -> {
                if (gen != modelGeneration || !modelWait) return;
                if (p[0] != lastProgress) {
                    lastProgress = p[0];
                    try {
                        send(new JSONObject().put("type", "TRANSLATE_MODEL_DOWNLOADING").put("major", 1).put("session", session).put("revision", revision)
                                .put("language", lang).put("missing", new JSONArray(new ArrayList<>(modelQueue))).put("doneBytes", p[0]).put("totalBytes", p[1]));
                    } catch (JSONException ignored) { }
                }
            });
        });
        handler.removeCallbacks(modelPoll);
        handler.postDelayed(modelPoll, MODEL_POLL_MS);
    }

    private void addToTimeline(Job job) {
        if (aiMode() && !TextSplitter.speakable(job.clean)) return;
        CueTimeline.AddResult r = timeline.add(job.id, job.text, "", job.start, job.end);
        if (r == CueTimeline.AddResult.FULL) event("ERROR", job, "BACKPRESSURE");
        else if (r == CueTimeline.AddResult.ADDED) noteForDetection(job.clean);
    }

    private void removeSnapshotJobs() {
        Iterator<Job> it = queue.iterator();
        while (it.hasNext()) if (!it.next().timeline) it.remove();
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
        if (cues.length() > 16) throw new JSONException("cue bounds");
        List<Job> jobs = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < cues.length(); i++) {
            JSONObject cue = cues.getJSONObject(i);
            Job job = new Job(); job.id = cue.getString("id"); job.text = cue.getString("text");
            job.start = strictLong(cue, "startMs"); job.end = strictLong(cue, "endMs");
            String role = cue.getString("role");
            if (!PayloadRules.cue(job.id, job.text, job.start, job.end, role) || !ids.add(job.id)) throw new JSONException("cue invalid");
            job.session = id; job.revision = rev; job.clean = TextCleaner.clean(job.text);
            if (!"annotation".equals(role) && !job.text.trim().isEmpty()) jobs.add(job);
        }
        return jobs;
    }
    private long mediaNow() { return position + (playing ? (long)((SystemClock.elapsedRealtime() - anchor) * speed) : 0); }
    private boolean aiMode() { return OpenRules.MODE_AI.equals(mode) && host.engine() != null; }

    // ------------------------------------------------------------------ timeline + lookahead tick
    private final Runnable tick = this::tick;
    private void scheduleTick() {
        handler.removeCallbacks(tick);
        if (session != null && (!timeline.isEmpty() || translating)) handler.postDelayed(tick, TICK_MS);
    }
    private void tick() {
        if (session == null) return;
        promote();
        if (aiMode() && translating && scheduler.active() && translateFailure == null && !modelWait) {
            long now = mediaNow();
            scheduler.lookahead(timeline.window(now, now + TranslationScheduler.LOOKAHEAD_MS, LOOKAHEAD_BATCH), TextCleaner::clean);
        }
        drain();
        scheduleTick();
    }
    /** Moves timeline cues entering the speech horizon into the queue (only while playing). */
    private void promote() {
        if (!playing || timeline.isEmpty()) return;
        List<CueTimeline.Entry> due = new ArrayList<>(), expired = new ArrayList<>();
        intake.promote(mediaNow(), Math.max(0, QUEUE - queue.size()), due, expired);
        for (CueTimeline.Entry e : expired) event("ERROR", fromTimeline(e), "EXPIRED");
        for (CueTimeline.Entry e : due) {
            Job job = fromTimeline(e);
            job.utterance = "speech-" + (++counter);
            queue.add(job);
        }
    }
    private Job fromTimeline(CueTimeline.Entry e) {
        Job job = new Job(); job.id = e.id; job.text = e.text; job.clean = TextCleaner.clean(e.text);
        job.start = e.start; job.end = e.end; job.session = session; job.revision = revision; job.timeline = true;
        return job;
    }

    // ------------------------------------------------------------------ "und": language detection
    private void noteForDetection(String clean) {
        if (!detecting || detectRequested || clean == null || clean.isEmpty()) return;
        if (detectText.length() > 0) detectText.append('\n');
        detectText.append(clean, 0, Math.min(clean.length(), 200));
        detectCues++;
        if (detectText.length() >= DETECT_UNITS || detectCues >= DETECT_CUES) startDetection();
        else if (detectCues == 1) handler.postDelayed(detectTimeout, DETECT_WAIT_MS);
    }
    private final Runnable detectTimeout = this::startDetection;
    private void startDetection() {
        handler.removeCallbacks(detectTimeout);
        if (!detecting || detectRequested) return;
        detectRequested = true;
        final long generation = detectGeneration;
        final String sample = detectText.toString();
        detectText.setLength(0);
        host.translation().identify(sample, DETECT_TIMEOUT_MS, (lang, assumed) -> {
            if (generation != detectGeneration || !detecting) return;
            detected(lang, assumed);
        });
    }
    private void detected(String lang, boolean assumed) {
        detecting = false;
        host.translation().closeIdentifier(); // one detection per content: free the language-id model
        JSONObject result = new JSONObject();
        try {
            result.put("type", "LANGUAGE_DETECTED").put("major", 1).put("session", session).put("revision", revision)
                    .put("language", lang).put("assumed", assumed);
            send(result);
        } catch (JSONException ignored) { }
        try {
            if (lang.equals(voiceLang)) { translating = false; scheduler.clear(); }
            else if (!LanguageTags.translatable(lang)) {
                failTranslation(OpenRules.TRANSLATE_UNAVAILABLE, new JSONObject().put("language", lang).put("reason", "UNSUPPORTED_LANGUAGE").put("voiceLang", voiceLang));
            } else {
                JSONObject missing = OpenRules.missing(env(), lang, voiceLang, lang);
                if (missing != null && host.autoDownloadModels() && (host.canAutoDownloadModel() || host.translation().busy())) {
                    scheduler.setSource(lang);
                    List<String> list = new ArrayList<>();
                    for (int i = 0; i < missing.getJSONArray("missing").length(); i++) list.add(missing.getJSONArray("missing").getString(i));
                    waitForModels(list);
                } else if (missing != null) failTranslation(OpenRules.TRANSLATE_MODEL_MISSING, missing);
                else scheduler.setSource(lang);
            }
        } catch (JSONException ignored) { }
        drain();
    }
    private void failTranslation(String code, JSONObject detail) {
        translateFailure = code; translateFailureDetail = detail;
        error(code, null, detail);
    }

    // ------------------------------------------------------------------ speech
    /** Wall-clock window left for a job, or -1 when its end is unknown. */
    private long windowMs(Job job) {
        if (job.end < 0) return -1;
        long from = Math.max(mediaNow(), job.start);
        return Math.max(1, (long) ((job.end - from) / Math.max(0.5, speed)));
    }
    private float aiRate(Job job) { return host.rates().choose(job.spoken.length(), windowMs(job), userRate); }

    private static final int READY = 0, WAIT = 1, SKIP = 2;
    /**
     * Readies the text a job will speak. AI mode: the cleaned cue, or its translation (requested
     * urgently when missing). Returns READY, WAIT (translation/detection pending), SKIP (nothing
     * speakable), or fails the job with an ERROR event (returned as SKIP with failed[0] set).
     */
    private int prepare(Job job, boolean urgent, String[] failed) {
        if (!aiMode()) { job.spoken = job.text; return READY; }
        if (job.spoken != null) return READY;
        if (!translating) { job.spoken = job.clean; return TextSplitter.speakable(job.spoken) ? READY : SKIP; }
        if (translateFailure != null) { failed[0] = translateFailure; return SKIP; }
        if (scheduler.waitingForSource()) return WAIT; // LANGUAGE_DETECTED (or its timeout) drains again
        if (modelWait) return WAIT; // TRANSLATE_MODEL_READY drains again
        String result = scheduler.result(job.id);
        if (result != null) { job.spoken = result; return TextSplitter.speakable(result) ? READY : SKIP; }
        String error = scheduler.error(job.id);
        if (error != null) { failed[0] = error; return SKIP; }
        scheduler.want(job.id, job.clean, job.start, urgent);
        result = scheduler.result(job.id); // a cache hit answers at once
        if (result != null) { job.spoken = result; return TextSplitter.speakable(result) ? READY : SKIP; }
        error = scheduler.error(job.id);
        if (error != null) { failed[0] = error; return SKIP; }
        return WAIT;
    }

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
            for (Job j : queue) prepare(j, true, new String[1]); // translations can run meanwhile
            return;
        }
        String[] failed = new String[1];
        while (!queue.isEmpty()) {
            Job job = queue.peek();
            if (!session.equals(job.session) || revision != job.revision || (job.end >= 0 && job.end <= mediaNow())) {
                queue.remove(); event("ERROR", job, "EXPIRED"); continue;
            }
            failed[0] = null;
            int state = prepare(job, true, failed);
            if (failed[0] != null) { queue.remove(); event("ERROR", job, failed[0]); continue; }
            if (state == SKIP) { queue.remove(); continue; }
            if (state == WAIT) { prefetch(); return; } // the translation callback drains again
            if (job.start > mediaNow()) { handler.removeCallbacks(wake); handler.postDelayed(wake, Math.max(1, (long)((job.start - mediaNow()) / speed))); prefetch(); return; }
            active = queue.remove();
            final String watchdogId = active.utterance;
            handler.removeCallbacks(watchdog);
            watchdog = () -> { if (active != null && watchdogId.equals(active.utterance)) { stopEngines(); terminal(watchdogId, "TIMEOUT"); } };
            boolean accepted;
            if (aiMode()) {
                handler.postDelayed(watchdog, aiWatchdogMs(active));
                accepted = host.engine().speak(active.utterance, active.spoken, aiRate(active));
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
        if (!aiMode() || !host.engine().ready() || session == null) return;
        Set<String> keep = new HashSet<>();
        int n = 0;
        String[] failed = new String[1];
        for (Job job : queue) {
            if (n >= NarrationPipeline.MAX_AHEAD) break;
            if (!session.equals(job.session) || revision != job.revision) continue;
            failed[0] = null;
            if (prepare(job, true, failed) != READY) continue;
            keep.add(job.utterance); n++;
            host.engine().prefetch(job.utterance, job.spoken, aiRate(job));
        }
        host.engine().retainOnly(keep);
    }
    private long aiWatchdogMs(Job job) {
        double units = job.spoken.length();
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
        String code = packProblem(engineError) ? OpenRules.VOICE_PACK_MISSING : "PROVIDER_FAILED";
        JSONObject detail = new JSONObject();
        try { if (OpenRules.VOICE_PACK_MISSING.equals(code)) detail.put("voiceLang", voiceLang); } catch (JSONException ignored) { }
        error(code, null, detail);
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
        if (host.translation() != null) host.translation().releaseClients();
        host.setSessionActive(false);
        mode = OpenRules.MODE_SYSTEM; voiceLang = LanguageTags.VI;
    }
    private void disconnect() {
        if (engine != null) cancel(); gate.closeCurrent(); session = null; playing = false; seen.clear();
        openTranslate = false;
        if (scheduler != null) resetContent();
        handler.removeCallbacks(tick);
        if (host != null) endAiSession();
        if (peer != null && death != null) peer.getBinder().unlinkToDeath(death, 0);
        peer = null; death = null; lastCapabilities = null;
    }
    private void pushCapabilitiesIfChanged() { if (peer != null) capabilities(); }
    private void capabilities() {
        try {
            MlKitTranslation tr = host.translation();
            JSONObject result = Capabilities.build(engine != null && engine.ready(), engine == null ? null : engine.state(),
                    host.packStatuses(), tr == null ? Capabilities.TranslateInfo.none("NO_ENGINE") : tr.info(),
                    host.engineState(), host.versionName(), host.versionCode());
            // Progress ticks alone do not spam the client; state/engine changes always go out.
            String signature = Capabilities.signature(result);
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
    private void error(String code, String cueId) { error(code, cueId, null); }
    private void error(String code, String cueId, JSONObject detail) {
        JSONObject result = new JSONObject();
        try {
            result.put("type", "ERROR").put("major", 1).put("code", code);
            if (session != null) result.put("session", session).put("revision", revision);
            if (cueId != null) result.put("cueId", cueId);
            if (detail != null) for (Iterator<String> k = detail.keys(); k.hasNext(); ) { String key = k.next(); if (!result.has(key)) result.put(key, detail.get(key)); }
            send(result);
        }
        catch (JSONException ignored) { }
    }
    private void send(JSONObject reply) {
        if (peer == null) return;
        Message message = Message.obtain(null, 1); Bundle data = new Bundle(); data.putString("payload", reply.toString()); message.setData(data);
        try { peer.send(message); } catch (RemoteException e) { disconnect(); }
    }
}
