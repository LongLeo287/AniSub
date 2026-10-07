package com.anisub.runtime;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;
import com.anisub.runtime.ai.AiSpeechEngine;
import com.anisub.runtime.translate.CueTimeline;
import com.anisub.runtime.translate.LanguageTags;
import com.anisub.runtime.translate.MlKitTranslation;
import com.anisub.runtime.translate.TextCleaner;
import com.anisub.runtime.translate.TranslationScheduler;
import com.anisub.runtime.voice.VoicePackManager;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * DEBUG BUILDS ONLY (src/debug): emulator test harness for ANISUB-004. Fixed English sample lines
 * only (no user subtitles). Results go to logcat (tag AniSubHarness) and files/harness.log.
 *
 * <pre>
 * adb shell am start -n com.anisub.runtime/.DebugHarnessActivity --es cmd status
 *   cmd = status | install-pack (--es lang en) | mlkit-download (--es lang vi) | translate (--es from en --es to vi)
 *       | speak (--es lang vi|en [--es text "..."]) | session (--es from en --es to vi [--ei cues 20]) | cleanup
 * </pre>
 * install-pack / mlkit-download act as the tester's consent on a test device (never in the user flow).
 */
public final class DebugHarnessActivity extends Activity {
    static final String TAG = "AniSubHarness";
    static final String[] SAMPLES = {
            "Where are you going?",
            "We have to leave before the storm reaches the village.",
            "I told you, I'm not afraid of him.",
            "Thank you for saving my life.",
            "The castle gate opens at dawn.",
            "If we lose this battle, everything is over.",
            "Can you hear me? Stay with me!",
            "I'll protect you, no matter what happens.",
            "That's the strongest magic I've ever seen.",
            "Let's eat first, then we'll talk.",
            "He's been gone for three years.",
            "{\\an8}Don't give up now,\\Nwe're almost there.",
    };
    private final Handler main = new Handler(Looper.getMainLooper());
    private RuntimeHost host;
    private TextView out;
    private final StringBuilder log = new StringBuilder();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        host = RuntimeHost.get(this);
        out = new TextView(this); out.setTextColor(Color.WHITE); out.setTextSize(14); out.setPadding(32, 32, 32, 32);
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(0xFF101820); scroll.addView(out);
        setContentView(scroll);
        run(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); run(intent); }

    private void run(Intent intent) {
        String cmd = intent.getStringExtra("cmd");
        log("cmd=" + cmd);
        if (cmd == null) cmd = "status";
        try {
            switch (cmd) {
                case "status": status(); break;
                case "install-pack": installPack(arg(intent, "lang", "en")); break;
                case "mlkit-download": mlkitDownload(arg(intent, "lang", "vi")); break;
                case "translate": translateSamples(arg(intent, "from", "en"), arg(intent, "to", "vi")); break;
                case "speak": speak(arg(intent, "lang", "vi"), intent.getStringExtra("text")); break;
                case "session": session(arg(intent, "from", "en"), arg(intent, "to", "vi"), intent.getIntExtra("cues", 20)); break;
                case "cleanup": cleanup(); break;
                default: log("unknown cmd");
            }
        } catch (Exception e) { log("FAILED " + e); }
    }

    private static String arg(Intent i, String k, String d) { String v = i.getStringExtra(k); return v == null ? d : v; }

    private void log(String line) {
        Log.i(TAG, line);
        log.append(line).append('\n');
        out.setText(log);
        try (FileWriter w = new FileWriter(new File(getFilesDir(), "harness.log"), true)) { w.write(line + "\n"); } catch (IOException ignored) { }
    }

    private void status() throws JSONException {
        MlKitTranslation tr = host.translation();
        JSONObject caps = Capabilities.build(false, "harness", host.packStatuses(), tr.info(), host.engineState(), host.versionName(), host.versionCode());
        log("CAPABILITIES " + caps.toString());
        log("engine=" + host.engineState() + " lang=" + (host.engine() == null ? "-" : host.engine().language()) + " " + (host.engine() == null ? "" : host.engine().diagnostics()));
    }

    private void installPack(String lang) {
        final VoicePackManager m = host.voices(lang);
        if (m == null) { log("no pack for " + lang); return; }
        final long t0 = SystemClock.elapsedRealtime();
        if (host.engine() != null) host.engine().unload();
        log("install-pack " + lang + " started=" + m.download(true));
        main.postDelayed(new Runnable() {
            public void run() {
                VoicePackManager.Status s = m.status();
                log("pack " + lang + " " + s.state + " " + s.doneBytes + "/" + s.totalBytes + (s.error == null ? "" : " error=" + s.error));
                if (s.state == VoicePackManager.State.DOWNLOADING || s.state == VoicePackManager.State.VERIFYING) main.postDelayed(this, 1000);
                else log("install-pack done in " + (SystemClock.elapsedRealtime() - t0) + " ms");
            }
        }, 500);
    }

    private void mlkitDownload(String lang) {
        final long t0 = SystemClock.elapsedRealtime();
        boolean started = host.translation().download(lang, true, ok -> log("mlkit-download " + lang + " ok=" + ok + " in "
                + (SystemClock.elapsedRealtime() - t0) + " ms; error=" + host.translation().lastDownloadError() + " models=" + host.translation().models()));
        log("mlkit-download " + lang + " started=" + started + " available=" + host.translation().available() + " reason=" + host.translation().unavailableReason());
    }

    /** Translates every sample once (serially), logging per-cue latency; then again (cache-free pair warm). */
    private void translateSamples(final String from, final String to) {
        final MlKitTranslation tr = host.translation();
        final List<Long> times = new ArrayList<>();
        final int[] i = {0};
        final long[] t0 = {SystemClock.elapsedRealtime()};
        final TranslationScheduler.Callback[] next = new TranslationScheduler.Callback[1];
        next[0] = (result, error) -> {
            long ms = SystemClock.elapsedRealtime() - t0[0];
            times.add(ms);
            int k = i[0] % SAMPLES.length;
            log(String.format(Locale.ROOT, "translate %s>%s #%d %d ms: \"%s\" -> \"%s\"%s", from, to, i[0], ms,
                    TextCleaner.clean(SAMPLES[k]), result, error == null ? "" : " error=" + error));
            i[0]++;
            if (i[0] < SAMPLES.length * 2 && error == null) {
                t0[0] = SystemClock.elapsedRealtime();
                tr.translate(TextCleaner.clean(SAMPLES[i[0] % SAMPLES.length]), from, to, next[0]);
            } else {
                long sum = 0, max = 0; for (int j = 1; j < times.size(); j++) { sum += times.get(j); max = Math.max(max, times.get(j)); }
                log("translate summary: first(cold)=" + times.get(0) + " ms, warm mean=" + (times.size() > 1 ? sum / (times.size() - 1) : 0) + " ms, warm max=" + max + " ms, n=" + times.size());
            }
        };
        tr.translate(TextCleaner.clean(SAMPLES[0]), from, to, next[0]);
    }

    private String speakId;
    private void speak(final String lang, String text) {
        final AiSpeechEngine engine = host.engine();
        final String say = text != null ? text : LanguageTags.EN.equals(lang) ? "Hello! This is the AniSub AI narration voice, running right on your TV."
                : "Xin chào! Đây là giọng thuyết minh AI của AniSub, chạy ngay trên TV của bạn.";
        final long t0 = SystemClock.elapsedRealtime();
        host.addEngineListener(new AiSpeechEngine.Listener() {
            long tStart;
            public void started(String id) { if (id.equals(speakId)) { tStart = SystemClock.elapsedRealtime(); log("speak " + lang + " STARTED after " + (tStart - t0) + " ms"); } }
            public void finished(String id) { if (id.equals(speakId)) { log("speak " + lang + " FINISHED, audio " + (SystemClock.elapsedRealtime() - tStart) + " ms; " + engine.diagnostics()); host.removeEngineListener(this); } }
            public void failed(String id, String code) { if (id.equals(speakId)) { log("speak failed " + code); host.removeEngineListener(this); } }
            public void engineChanged(AiSpeechEngine.State state, String error) {
                log("engine " + state + (error == null ? "" : " " + error) + " lang=" + engine.loadedLanguage() + " after " + (SystemClock.elapsedRealtime() - t0) + " ms");
                if (state == AiSpeechEngine.State.READY && lang.equals(engine.loadedLanguage()) && speakId == null) {
                    speakId = "harness-" + SystemClock.elapsedRealtime();
                    engine.speak(speakId, say, 1f);
                }
            }
        });
        speakId = null;
        engine.setLanguage(lang);
        if (engine.ready() && lang.equals(engine.loadedLanguage())) { speakId = "harness-" + t0; engine.speak(speakId, say, 1f); }
        else engine.load();
    }

    // ------------------------------------------------------------------ a real service session
    private Messenger service;
    private long seq, sessionT0;
    private final Map<String, Long> cueStart = new HashMap<>();
    private final Map<String, Long> startedLate = new HashMap<>();
    private int finished, errors;
    private String sessionId;

    /**
     * Binds AniSubService (debug trust: own uid), OPENs an AI session {language: from, voiceLang: to},
     * sends a whole synthetic subtitle file as CUES timeline batches BEFORE playback, then plays from
     * 0 at 1x. Logs, per cue, how late STARTED came relative to the cue's media start.
     */
    private void session(final String from, final String to, final int count) {
        sessionId = "harness-" + SystemClock.elapsedRealtime();
        cueStart.clear(); startedLate.clear(); finished = 0; errors = 0; seq = 0;
        final Messenger replies = new Messenger(new Handler(Looper.getMainLooper()) {
            @Override public void handleMessage(Message m) {
                String p = m.getData().getString("payload");
                try {
                    JSONObject r = new JSONObject(p);
                    String type = r.getString("type");
                    long media = SystemClock.elapsedRealtime() - sessionT0;
                    if ("CAPABILITIES".equals(type)) { log("<- CAPABILITIES minor=" + r.getInt("minor") + " voices=" + r.getJSONObject("voices") + " translate=" + r.getJSONObject("translate")); return; }
                    String cue = r.optString("cueId", null);
                    if ("STARTED".equals(type) && cue != null && cueStart.containsKey(cue)) {
                        long late = media - cueStart.get(cue);
                        startedLate.put(cue, late);
                        log("<- STARTED " + cue + " media=" + media + " ms, cueStart=" + cueStart.get(cue) + ", late=" + late + " ms");
                    } else if ("FINISHED".equals(type)) { finished++; log("<- FINISHED " + cue + (r.has("code") ? " " + r.getString("code") : "")); }
                    else { if ("ERROR".equals(type)) errors++; log("<- " + p); }
                } catch (JSONException e) { log("bad reply"); }
            }
        });
        bindService(new Intent().setComponent(new ComponentName(this, AniSubService.class)), new ServiceConnection() {
            public void onServiceConnected(ComponentName n, IBinder b) {
                service = new Messenger(b);
                try {
                    send(replies, new JSONObject().put("type", "HELLO").put("major", 1).put("minor", 2));
                    sessionT0 = SystemClock.elapsedRealtime() + 1_000_000; // not playing yet
                    send(replies, cmd("OPEN", from, 0).put("mode", "ai").put("voiceLang", to).put("playing", false));
                    // The whole "file": cue i starts at 8 s + 3 s * i (2.5 s long), sent ahead in batches of 16.
                    JSONArray batch = new JSONArray();
                    for (int i = 0; i < count; i++) {
                        long start = 8000 + 3000L * i;
                        String id = "c" + i;
                        cueStart.put(id, start);
                        batch.put(new JSONObject().put("id", id).put("text", SAMPLES[i % SAMPLES.length]).put("startMs", start).put("endMs", start + 2500).put("role", "dialogue"));
                        if (batch.length() == 16 || i == count - 1) {
                            send(replies, cmd("CUES", from, 0).put("timeline", true).put("cues", batch));
                            batch = new JSONArray();
                        }
                    }
                    log("-> " + count + " timeline cues sent; PLAY in 1 s");
                    main.postDelayed(() -> {
                        try {
                            sessionT0 = SystemClock.elapsedRealtime();
                            send(replies, cmd("PLAY", from, 0).put("playing", true));
                        } catch (Exception e) { log("PLAY failed " + e); }
                    }, 1000);
                    long endMs = 8000 + 3000L * count + 4000;
                    main.postDelayed(() -> {
                        try {
                            send(replies, cmd("CLOSE", from, endMs));
                            long sum = 0, max = 0, onTime = 0;
                            for (long l : startedLate.values()) { sum += l; max = Math.max(max, l); if (l <= 300) onTime++; }
                            log(String.format(Locale.ROOT, "session %s>%s summary: cues=%d started=%d onTime(<=300ms)=%d meanLate=%d ms maxLate=%d ms finished=%d errors=%d",
                                    from, to, count, startedLate.size(), onTime, startedLate.isEmpty() ? 0 : sum / startedLate.size(), max, finished, errors));
                            log("engine " + host.engine().diagnostics());
                        } catch (Exception e) { log("CLOSE failed " + e); }
                        unbindService(this);
                    }, 1000 + endMs);
                } catch (Exception e) { log("session failed " + e); }
            }
            public void onServiceDisconnected(ComponentName n) { service = null; }
        }, BIND_AUTO_CREATE);
    }

    private JSONObject cmd(String type, String language, long positionMs) throws JSONException {
        return new JSONObject().put("type", type).put("major", 1).put("session", sessionId).put("revision", 1).put("seq", ++seq)
                .put("positionMs", positionMs).put("speed", 1).put("language", language);
    }

    private void send(Messenger replies, JSONObject payload) throws RemoteException {
        Message m = Message.obtain(null, 1);
        Bundle b = new Bundle(); b.putString("payload", payload.toString()); m.setData(b);
        m.replyTo = replies;
        service.send(m);
    }

    /** Removes what the harness installed: the English pack, ML Kit models, side-loaded files. */
    private void cleanup() {
        final VoicePackManager en = host.voices(LanguageTags.EN);
        Runnable remove = () -> { String e = en == null ? null : en.delete(); main.post(() -> log("en pack delete -> " + (e == null ? "ok" : e))); };
        if (host.engine() != null) host.engine().unloadThen(remove); else remove.run();
        for (String lang : host.translation().models().keySet()) {
            if (!LanguageTags.EN.equals(lang) && host.translation().modelReady(lang))
                host.translation().delete(lang, ok -> log("mlkit delete " + lang + " ok=" + ok));
        }
        File dir = new File(getFilesDir(), "debug-packs");
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) log("rm " + f.getName() + " " + f.delete());
        log("debug-packs removed " + dir.delete());
    }
}
