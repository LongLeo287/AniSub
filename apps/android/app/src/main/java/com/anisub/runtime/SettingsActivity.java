package com.anisub.runtime;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.anisub.runtime.ai.AiSpeechEngine;
import com.anisub.runtime.ai.RatePolicy;
import com.anisub.runtime.ai.SherpaSynthesizer;
import com.anisub.runtime.translate.LanguageTags;
import com.anisub.runtime.translate.MlKitTranslation;
import com.anisub.runtime.voice.VoiceCatalog;
import com.anisub.runtime.voice.VoicePackManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * TV settings: D-pad rows, BACK closes, Vietnamese text, dark theme. Sections: the Vietnamese and
 * the English AI voice packs (consent download / update / delete / "Nghe thử"), reading speed (per
 * app), and subtitle translation (ML Kit models: consent download with size and Google as source,
 * delete, "Dịch thử"). Exported without a permission on purpose (AniBox is usually installed before
 * AniSub, so a custom permission would never be granted). It exposes no data and only acts on
 * explicit on-screen confirmation, so any launcher/AniBox may open it.
 */
public final class SettingsActivity extends Activity {
    public static final String ACTION = "com.anisub.runtime.action.SETTINGS";
    private static final String PREVIEW_VI = "Xin chào! Đây là giọng thuyết minh AI của AniSub, chạy ngay trên TV của bạn.";
    private static final String PREVIEW_EN = "Hello! This is the AniSub AI narration voice, running right on your TV.";
    /** "Dịch thử": an English line translated on the TV into Vietnamese, then spoken. */
    static final String SAMPLE_EN = "We have to leave before the storm reaches the village.";
    private static final int BG = 0xFF0E1621, CARD = 0xFF162231, CARD_FOCUS = 0xFF24364D, ACCENT = 0xFF70DAD0, MUTED = 0xFF9FB0C3;

    private RuntimeHost host;
    private PackRows vi, en;
    private Row voice, rate, trStatus, trAuto, trModels, trTest, anibox, about;
    private final Runnable refresh = this::render;
    private int previewCounter;
    private String previewId, previewPending, previewText;
    private String trialResult;
    private boolean trialRunning;

    private final AiSpeechEngine.Listener engineListener = new AiSpeechEngine.Listener() {
        public void started(String id) { if (id.equals(previewId)) render(); }
        public void finished(String id) { if (id.equals(previewId)) { previewId = null; render(); } }
        public void failed(String id, String code) { if (id.equals(previewId)) { previewId = null; render(); } }
        public void engineChanged(AiSpeechEngine.State state, String error) {
            AiSpeechEngine engine = host.engine();
            if (previewPending != null && state == AiSpeechEngine.State.READY && engine != null && previewPending.equals(engine.loadedLanguage())) {
                String lang = previewPending; previewPending = null; speak(lang, previewText);
            }
            if (state == AiSpeechEngine.State.FAILED) previewPending = null;
            render();
        }
    };

    private final class Row {
        final LinearLayout view; final TextView title, value; boolean info;
        Row(String label) {
            view = new LinearLayout(SettingsActivity.this);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setPadding(dp(24), dp(14), dp(24), dp(14));
            view.setMinimumHeight(dp(64));
            view.setBackground(rowBackground());
            title = text(label, 20, Color.WHITE, true);
            value = text("", 16, MUTED, false);
            view.addView(title); view.addView(value);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(10);
            view.setLayoutParams(lp);
        }
        Row set(String t, String v, boolean actionable, boolean visible) {
            title.setText(t); value.setText(v == null ? "" : v);
            value.setVisibility(v == null || v.isEmpty() ? View.GONE : View.VISIBLE);
            view.setFocusable(actionable); view.setClickable(actionable);
            view.setAlpha(actionable || info ? 1f : .55f);
            view.setVisibility(visible ? View.VISIBLE : View.GONE);
            return this;
        }
    }

    /** The rows of one voice pack (one voice language). */
    private final class PackRows {
        final String lang, label;
        final Row status, action, preview, delete;
        PackRows(LinearLayout list, String lang, String label) {
            this.lang = lang; this.label = label;
            status = add(list, "Trạng thái " + label);
            action = add(list, "Tải " + label);
            preview = add(list, "Nghe thử " + label);
            delete = add(list, "Xóa " + label);
            status.info = true;
            action.view.setOnClickListener(v -> onAction(this));
            preview.view.setOnClickListener(v -> onPreview(lang));
            delete.view.setOnClickListener(v -> onDelete(this));
        }
        VoicePackManager manager() { return host.voices(lang); }
    }

    private static void header(LinearLayout list, TextView t) { t.setPadding(t.getPaddingLeft(), t.getPaddingTop() + 12, 0, 12); list.addView(t); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        host = RuntimeHost.get(this);
        getWindow().setBackgroundDrawable(new ColorDrawable(BG));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(56), dp(32), dp(56), dp(24));
        root.setBackgroundColor(BG);
        root.addView(text("AniSub · Thuyết minh AI", 30, Color.WHITE, true));
        TextView sub = text("Đọc phụ đề bằng giọng AI chạy ngay trên TV; phụ đề khác ngôn ngữ giọng đọc được dịch trên TV. "
                + "Giọng tiếng Việt mặc định (tải từ GitHub của AniSub) và gói dịch tiếng Việt (tải từ máy chủ Google, dl.google.com) "
                + "tự tải ngay lần đầu chạy; gói dịch khác tự tải khi cần. Sau đó dùng không cần mạng.", 16, MUTED, false);
        sub.setPadding(0, dp(6), 0, dp(18));
        root.addView(sub);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        header(list, text("Giọng tiếng Việt", 22, ACCENT, true));
        vi = new PackRows(list, LanguageTags.VI, "giọng tiếng Việt");
        voice = add(list, "Giọng đọc");
        header(list, text("Giọng tiếng Anh", 22, ACCENT, true));
        en = new PackRows(list, LanguageTags.EN, "giọng tiếng Anh");
        header(list, text("Đọc và dịch", 22, ACCENT, true));
        rate = add(list, "Tốc độ đọc");
        trStatus = add(list, "Dịch phụ đề trên TV");
        trAuto = add(list, "Tự tải gói dịch khi cần");
        trModels = add(list, "Mô hình dịch");
        trTest = add(list, "Dịch thử");
        anibox = add(list, "Dùng trong AniBox");
        about = add(list, "Giấy phép và giới thiệu");
        trStatus.info = true; anibox.info = true;
        voice.view.setOnClickListener(v -> nextVoice());
        rate.view.setOnClickListener(v -> stepRate(+1, true));
        rate.view.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { stepRate(+1, false); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { stepRate(-1, false); return true; }
            return false;
        });
        trModels.view.setOnClickListener(v -> onModels());
        trAuto.view.setOnClickListener(v -> { host.setAutoDownloadModels(!host.autoDownloadModels()); render(); });
        trTest.view.setOnClickListener(v -> onTrial());
        about.view.setOnClickListener(v -> onAbout());
        setContentView(root);
        render();
        vi.action.view.requestFocus();
    }

    @Override protected void onStart() {
        super.onStart();
        host.addListener(refresh);
        host.addEngineListener(engineListener);
        if (host.translation() != null) host.translation().refresh();
        render();
    }

    @Override protected void onStop() {
        host.removeListener(refresh);
        host.removeEngineListener(engineListener);
        if (host.engine() != null) {
            if (!host.sessionActive()) { host.engine().stop(); host.engine().scheduleIdleUnload(); }
        }
        if (host.translation() != null && !host.sessionActive()) host.translation().releaseClients();
        previewPending = null; previewId = null;
        super.onStop();
    }

    private Row add(LinearLayout list, String label) { Row r = new Row(label); list.addView(r.view); return r; }

    private void render() {
        if (host.voices() == null) {
            vi.status.set("Trạng thái giọng AI", "Lỗi bộ nhớ giọng: không mở được dữ liệu giọng trong máy.", false, true);
            vi.action.set("Xóa dữ liệu giọng và làm lại", "Chỉ xóa dữ liệu giọng của AniSub.", true, true);
            for (Row r : new Row[]{vi.preview, vi.delete, voice, en.status, en.action, en.preview, en.delete}) r.set(r.title.getText().toString(), null, false, false);
        } else {
            renderPack(vi);
            renderPack(en);
            VoicePackManager.Status s = host.packStatus(LanguageTags.VI);
            VoiceCatalog.Pack pack = s == null ? null : s.pack;
            if (pack != null && pack.voices.size() > 1) {
                VoiceCatalog.Voice chosen = pack.voice(host.defaultVoice());
                voice.set("Giọng đọc", (chosen == null ? pack.voices.get(0) : chosen).name + "  ·  OK để đổi", s.ready() && !host.sessionActive(), true);
            }
            else voice.set("Giọng đọc", pack == null ? "—" : pack.voices.get(0).name + " (gói có một giọng)", false, true);
        }
        // Downloads run in this app process; keep the TV awake while one is in progress.
        boolean busy = host.anyPackBusy() || (host.translation() != null && host.translation().busy());
        if (busy) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        rate.set("Tốc độ đọc", String.format(Locale.ROOT, "%.1f×  ·  ◀ ▶ để chỉnh (0.8–1.3), dùng cho cả hai giọng", host.defaultRate()), true, true);
        renderTranslation();
        renderCommon();
    }

    private void renderPack(PackRows rows) {
        VoicePackManager m = rows.manager();
        VoicePackManager.Status s = m == null ? null : m.status();
        if (s == null || s.pack == null) {
            rows.status.set("Trạng thái " + rows.label, "Bản AniSub này chưa có gói " + rows.label + ".", false, true);
            for (Row r : new Row[]{rows.action, rows.preview, rows.delete}) r.set(r.title.getText().toString(), null, false, false);
            return;
        }
        AiSpeechEngine engine = host.engine();
        boolean engineOnThis = engine != null && rows.lang.equals(engine.language());
        VoiceCatalog.Pack pack = s.pack;
        String size = VoicePackManager.formatBytes(pack.totalBytes);
        boolean installed = s.installedVersion != null;
        String statusText;
        switch (s.state) {
            case DOWNLOADING:
                int pct = s.totalBytes <= 0 ? 0 : (int) Math.min(100, s.doneBytes * 100 / s.totalBytes);
                statusText = (LanguageTags.VI.equals(rows.lang) && host.autoDownloadingDefault() ? "Đang tự tải giọng mặc định… " : "Đang tải… ")
                        + pct + "% (" + VoicePackManager.formatBytes(s.doneBytes) + " / " + size + ")"
                        + "\nTải bằng trình tải xuống của Android từ GitHub của AniSub, kiểm tra SHA-256 rồi cài.";
                break;
            case VERIFYING: statusText = "Đang kiểm tra SHA-256 và cài đặt…"; break;
            case READY:
                statusText = "Sẵn sàng · " + pack.name + " · phiên bản " + s.installedVersion;
                if (engineOnThis && engine.state() == AiSpeechEngine.State.LOADING) statusText += " · đang nạp giọng…";
                if (engineOnThis && engine.state() == AiSpeechEngine.State.FAILED) statusText += "\n" + engineError(engine.error());
                if (s.error != null) statusText += "\nLần cập nhật trước lỗi: " + packError(s.error, pack);
                break;
            case ERROR: statusText = "Lỗi: " + packError(s.error, pack); break;
            default: statusText = LanguageTags.VI.equals(rows.lang)
                    ? "Chưa cài. AniBox vẫn dùng được giọng hệ thống (nếu có)."
                    : "Chưa cài. Cần khi chọn giọng đọc tiếng Anh trong AniBox.";
        }
        if (host.sessionActive()) statusText += "\nĐang dùng trong AniBox.";
        rows.status.set("Trạng thái " + rows.label, statusText, false, true);

        boolean otherBusy = host.anyPackBusy() && !m.busy();
        if (s.state == VoicePackManager.State.DOWNLOADING) rows.action.set("Hủy tải", "Dữ liệu đang tải dở sẽ bị xóa.", true, true);
        else if (s.state == VoicePackManager.State.VERIFYING) rows.action.set("Đang cài đặt…", null, false, true);
        else if (installed && s.updateAvailable) rows.action.set("Cập nhật " + rows.label + " (" + size + ")", "Bản mới: " + pack.version + ". Bản đang dùng vẫn giữ nếu cập nhật lỗi.", !otherBusy, true);
        else if (installed) rows.action.set(capital(rows.label) + " đã cài", "Gói giọng đã được kiểm tra toàn vẹn.", false, true);
        else rows.action.set((s.state == VoicePackManager.State.ERROR ? "Thử tải lại " : "Tải ") + rows.label + " (" + size + ")",
                otherBusy ? "Đang tải gói khác; chờ xong rồi tải gói này." : "Cần đồng ý trước khi tải. Hiện dung lượng và giấy phép.", !otherBusy, true);

        boolean ready = s.ready();
        String previewValue;
        boolean canPreview = ready && engine != null && !host.sessionActive();
        if (!ready) previewValue = "Cần tải " + rows.label + " trước.";
        else if (host.sessionActive()) previewValue = "AniBox đang dùng giọng; dừng phát để nghe thử.";
        else if (rows.lang.equals(previewPending) || (engineOnThis && engine.state() == AiSpeechEngine.State.LOADING)) previewValue = "Đang nạp giọng…";
        else if (previewId != null && engineOnThis) previewValue = "Đang đọc…";
        else previewValue = "Đọc một câu mẫu với tốc độ đã chọn.";
        rows.preview.set("Nghe thử " + rows.label, previewValue, canPreview, true);
        if (LanguageTags.VI.equals(rows.lang)) {
            // The default voice: always kept (and re-downloaded automatically when missing).
            rows.delete.set("Giọng mặc định", installed ? "Giọng tiếng Việt mặc định không xóa được (" + size + ")." : null, false, installed);
            return;
        }
        rows.delete.set("Xóa " + rows.label, installed ? "Giải phóng " + size + ". Có thể tải lại bất cứ lúc nào." : null, installed && !host.sessionActive()
                && s.state != VoicePackManager.State.DOWNLOADING && s.state != VoicePackManager.State.VERIFYING, installed);
    }

    private void renderTranslation() {
        MlKitTranslation tr = host.translation();
        if (tr == null || !tr.available()) {
            String why = tr == null ? "" : translateReason(tr.unavailableReason());
            trStatus.set("Dịch phụ đề trên TV", "Không dùng được trên thiết bị này. " + why
                    + "\nAniSub vẫn đọc phụ đề cùng ngôn ngữ với giọng đọc.", false, true);
            trAuto.set("Tự tải gói dịch khi cần", null, false, false);
            trModels.set("Mô hình dịch", null, false, false);
            trTest.set("Dịch thử", null, false, false);
            return;
        }
        List<String> ready = new ArrayList<>(), downloading = new ArrayList<>();
        for (Map.Entry<String, String> e : tr.models().entrySet()) {
            if (LanguageTags.EN.equals(e.getKey())) continue;
            if (Capabilities.READY.equals(e.getValue())) ready.add(LanguageTags.displayName(e.getKey()));
            if (Capabilities.DOWNLOADING.equals(e.getValue())) downloading.add(LanguageTags.displayName(e.getKey()));
        }
        String st = "Dịch ngay trên TV bằng ML Kit của Google. Tiếng Anh có sẵn; đã cài: "
                + (ready.isEmpty() ? "chưa có" : join(ready)) + ".";
        if (!downloading.isEmpty()) st += "\nĐang tải: " + join(downloading) + "…";
        if (tr.lastDownloadError() != null) st += "\nLần tải trước lỗi: " + downloadError(tr.lastDownloadError());
        st += "\nCần mô hình Tiếng Việt để dịch sang giọng Việt; phụ đề tiếng Nhật cần thêm mô hình Tiếng Nhật.";
        trStatus.set("Dịch phụ đề trên TV", st, false, true);
        trAuto.set("Tự tải gói dịch khi cần: " + (host.autoDownloadModels() ? "Bật" : "Tắt"),
                host.autoDownloadModels() ? "Khi phim cần gói dịch chưa có (vd. phụ đề tiếng Nhật), AniSub tự tải từ máy chủ Google rồi đọc. OK để tắt."
                        : "Tắt: gói dịch thiếu phải tải ở mục Mô hình dịch. OK để bật.", true, true);
        trModels.set("Mô hình dịch", "Tải hoặc xóa mô hình từng ngôn ngữ (tải khoảng 30 MB, chiếm 45–65 MB mỗi ngôn ngữ).", !host.sessionActive(), true);
        boolean canTrial = tr.modelReady(LanguageTags.VI) && !host.sessionActive(); // stays focusable while running (onTrial ignores repeats)
        String tv;
        if (trialRunning) tv = "Đang dịch…";
        else if (trialResult != null) tv = trialResult;
        else if (!tr.modelReady(LanguageTags.VI)) tv = "Cần tải mô hình Tiếng Việt trước.";
        else tv = "Dịch một câu tiếng Anh mẫu sang tiếng Việt" + (host.packStatus(LanguageTags.VI) != null && host.packStatus(LanguageTags.VI).ready() ? " rồi đọc bằng giọng Việt." : ".");
        trTest.set("Dịch thử", tv, canTrial, true);
    }

    private void renderCommon() {
        String ab;
        if (host.aniBoxCompatible()) ab = "AniBox đã cài và cùng chữ ký. Mở AniBox › Cài đặt › Thuyết minh, chọn “Giọng AI (AniSub)”.";
        else if (host.aniBoxInstalled()) ab = "AniBox đã cài nhưng khác chữ ký nên không thể kết nối. Cài bản AniBox và AniSub chính thức.";
        else ab = "Chưa cài AniBox. AniSub là phần bổ trợ, cần AniBox để thuyết minh khi xem phim.";
        anibox.set("Dùng trong AniBox", ab, false, true);
        about.set("Giấy phép và giới thiệu", "AniSub " + host.versionName() + " (" + host.versionCode() + ") · "
                + SherpaSynthesizer.ENGINE + " " + SherpaSynthesizer.ENGINE_VERSION + " · ML Kit Translate · GPL-3.0-or-later", true, true);
        keepFocus();
    }

    /** A row that stops being actionable (or hides) while focused must not strand D-pad focus. */
    private void keepFocus() {
        View focused = getCurrentFocus();
        if (focused != null && focused.isFocusable() && focused.getVisibility() == View.VISIBLE) return;
        for (Row r : new Row[]{vi.action, vi.preview, en.action, en.preview, rate, trAuto, trModels, voice, vi.delete, en.delete, about}) {
            if (r.view.isFocusable() && r.view.getVisibility() == View.VISIBLE) { r.view.requestFocus(); return; }
        }
    }

    // ------------------------------------------------------------------ voice packs
    private void onAction(PackRows rows) {
        if (host.voices() == null) {
            confirm("Xóa dữ liệu giọng?", "Chỉ xóa dữ liệu giọng AI của AniSub để khởi tạo lại.", "Xóa", () -> host.resetStore());
            return;
        }
        VoicePackManager voices = rows.manager();
        if (voices == null) return;
        VoicePackManager.Status s = voices.status();
        if (s.state == VoicePackManager.State.DOWNLOADING) {
            if (LanguageTags.VI.equals(rows.lang)) host.pauseAutoVoice(); // the user stopped it: no automatic retry until restart
            voices.cancel(); return;
        }
        VoiceCatalog.Pack pack = s.pack;
        if (pack == null) return;
        if (host.sessionActive()) { info("Đang phát trong AniBox", "Dừng phát trong AniBox rồi tải hoặc cập nhật giọng để không làm giật phim."); return; }
        if (host.anyPackBusy()) { info("Đang tải gói khác", "Mỗi lần chỉ tải một gói giọng. Chờ gói đang tải xong rồi thử lại."); return; }
        String message = "Gói: " + pack.name + "\nDung lượng tải: " + VoicePackManager.formatBytes(pack.totalBytes)
                + " (cần thêm khoảng 128 MB trống dự phòng)\n\nGiấy phép: " + pack.license + "\n\n" + pack.attribution
                + "\n\nTải từ bản phát hành công khai của AniSub trên GitHub bằng trình tải xuống của hệ thống Android (AniSub không có quyền Internet). Mỗi tệp được kiểm tra SHA-256 trước khi cài. "
                + "Sau khi tải, giọng chạy hoàn toàn trên TV, không gửi phụ đề đi đâu.";
        confirm("Tải " + rows.label + "?", message, "Đồng ý tải", () -> {
            if (host.sessionActive() || host.anyPackBusy()) return;
            // Free the resident model first so the post-install smoke test never holds two copies.
            if (host.engine() != null) host.engine().unload();
            if (LanguageTags.VI.equals(rows.lang)) host.resumeAutoVoice();
            voices.download(true);
        });
    }

    private void nextVoice() {
        VoicePackManager.Status s = host.packStatus(LanguageTags.VI);
        if (s == null || s.pack == null || s.pack.voices.size() < 2 || host.sessionActive()) return;
        VoiceCatalog.Voice current = s.pack.voice(host.defaultVoice());
        int i = current == null ? 0 : s.pack.voices.indexOf(current);
        host.setDefaultVoice(s.pack.voices.get((i + 1) % s.pack.voices.size()).id);
        render();
    }

    private void stepRate(int direction, boolean wrap) {
        float r = Math.round((host.defaultRate() + direction * 0.1f) * 10) / 10f;
        if (r > RatePolicy.USER_MAX + 1e-3) r = wrap ? RatePolicy.USER_MIN : RatePolicy.USER_MAX;
        if (r < RatePolicy.USER_MIN - 1e-3) r = RatePolicy.USER_MIN;
        host.setDefaultRate(r);
        render();
    }

    private void onPreview(String lang) {
        AiSpeechEngine engine = host.engine();
        if (engine == null || host.sessionActive()) return;
        if (previewId != null) { engine.stop(); previewId = null; render(); return; }
        speakWhenReady(lang, LanguageTags.EN.equals(lang) ? PREVIEW_EN : PREVIEW_VI);
    }

    /** Loads the pack of {@code lang} when needed, then speaks {@code text}. */
    private void speakWhenReady(String lang, String text) {
        AiSpeechEngine engine = host.engine();
        if (engine == null || host.sessionActive()) return;
        engine.setLanguage(lang);
        if (engine.ready() && lang.equals(engine.loadedLanguage())) speak(lang, text);
        else { previewPending = lang; previewText = text; engine.load(); render(); }
    }

    private void speak(String lang, String text) {
        AiSpeechEngine engine = host.engine();
        if (engine == null || host.sessionActive() || !lang.equals(engine.loadedLanguage())) return;
        engine.stop();
        previewId = "preview-" + (++previewCounter);
        if (!engine.speak(previewId, text, host.defaultRate())) previewId = null;
        render();
    }

    private void onDelete(PackRows rows) {
        VoicePackManager voices = rows.manager();
        if (voices == null || host.sessionActive()) return;
        confirm("Xóa " + rows.label + "?", "Gói giọng sẽ bị xóa khỏi TV. AniBox sẽ không dùng được " + rows.label + " cho tới khi tải lại.", "Xóa", () -> {
            if (host.sessionActive()) { info("Không xóa được", packError(VoicePackManager.E_IN_USE, null)); return; }
            AiSpeechEngine engine = host.engine();
            Runnable remove = () -> {
                String error = voices.delete();
                if (error != null) runOnUiThread(() -> info("Không xóa được", packError(error, null)));
            };
            if (engine != null) engine.unloadThen(remove); else remove.run();
        });
    }

    // ------------------------------------------------------------------ translation models
    private void onModels() {
        final MlKitTranslation tr = host.translation();
        if (tr == null || !tr.available() || host.sessionActive()) return;
        final List<String> langs = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (Map.Entry<String, String> e : tr.models().entrySet()) {
            String l = e.getKey();
            String state = LanguageTags.EN.equals(l) ? "có sẵn" : Capabilities.READY.equals(e.getValue()) ? "đã cài · OK để xóa"
                    : Capabilities.DOWNLOADING.equals(e.getValue()) ? "đang tải…" : "chưa cài · OK để tải";
            langs.add(l); labels.add(LanguageTags.displayName(l) + "  ·  " + state);
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Mô hình dịch (ML Kit)")
                .setItems(labels.toArray(new String[0]), (d, which) -> onModel(tr, langs.get(which)))
                .setNegativeButton("Đóng", null).show();
    }

    private void onModel(MlKitTranslation tr, String lang) {
        String name = LanguageTags.displayName(lang);
        String state = tr.state(lang);
        if (LanguageTags.EN.equals(lang)) { info("Tiếng Anh", "Tiếng Anh có sẵn trong ML Kit, không cần tải."); return; }
        if (Capabilities.DOWNLOADING.equals(state)) { info("Đang tải", "Mô hình " + name + " đang được tải."); return; }
        if (Capabilities.READY.equals(state)) {
            confirm("Xóa mô hình " + name + "?", "Mô hình dịch " + name + " sẽ bị xóa khỏi TV. Có thể tải lại bất cứ lúc nào.", "Xóa",
                    () -> tr.delete(lang, ok -> runOnUiThread(this::render)));
            return;
        }
        String message = "Mô hình dịch " + name + ": tải khoảng " + VoicePackManager.formatBytes(MlKitTranslation.APPROX_MODEL_BYTES)
                + ", chiếm tối đa khoảng " + VoicePackManager.formatBytes(MlKitTranslation.APPROX_INSTALLED_BYTES) + " trên TV.\n\nTải từ máy chủ của Google (ML Kit Translate, phần mềm đóng của Google, theo Điều khoản ML Kit). "
                + "Việc tải dùng trình tải xuống của hệ thống Android. Sau khi tải, việc dịch chạy hoàn toàn trên TV; "
                + "phụ đề không gửi đi đâu. Có thể xóa mô hình bất cứ lúc nào.";
        confirm("Tải mô hình dịch " + name + "?", message, "Đồng ý tải",
                () -> tr.download(lang, ok -> runOnUiThread(this::render)));
    }

    /** Translates a fixed English line into Vietnamese on the TV, shows it and speaks it. */
    private void onTrial() {
        final MlKitTranslation tr = host.translation();
        if (tr == null || !tr.modelReady(LanguageTags.VI) || host.sessionActive() || trialRunning) return;
        trialRunning = true; trialResult = null; render();
        final long t0 = SystemClock.elapsedRealtime();
        // Language identification first (as for "und" subtitles), then translation into Vietnamese.
        tr.identify(SAMPLE_EN, MlKitTranslation.IDENTIFY_TIMEOUT_MS, (detected, assumed) -> {
        final String from = LanguageTags.translatable(detected) ? detected : LanguageTags.EN;
        tr.translate(SAMPLE_EN, from, LanguageTags.VI, (result, error) -> runOnUiThread(() -> {
            trialRunning = false;
            long ms = SystemClock.elapsedRealtime() - t0;
            if (result == null) trialResult = "Không dịch được (" + error + ").";
            else {
                trialResult = "Nhận diện: " + LanguageTags.displayName(detected) + (assumed ? " (giả định)" : "") + "\n“" + SAMPLE_EN + "”\n→ “" + result + "”  (" + ms + " ms)";
                VoicePackManager.Status s = host.packStatus(LanguageTags.VI);
                if (s != null && s.ready()) speakWhenReady(LanguageTags.VI, result);
            }
            render();
        }));
        });
    }

    private void onAbout() {
        String text;
        try { text = RuntimeHost.asset(this, "licenses.txt"); } catch (Exception e) { text = "Không đọc được tệp giấy phép."; }
        info("Giấy phép và giới thiệu", text);
    }

    private void confirm(String title, String message, String positive, Runnable onYes) {
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title).setMessage(message)
                .setPositiveButton(positive, (dialog, which) -> { onYes.run(); render(); })
                .setNegativeButton("Hủy", null).create();
        d.show();
    }

    private void info(String title, String message) {
        ScrollView scroll = new ScrollView(this);
        TextView body = text(message, 15, Color.WHITE, false);
        body.setPadding(dp(24), dp(12), dp(24), dp(12));
        body.setFocusable(true);
        scroll.addView(body);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title).setView(scroll).setPositiveButton("Đóng", null).show();
    }

    private static String join(List<String> items) {
        StringBuilder b = new StringBuilder();
        for (String s : items) { if (b.length() > 0) b.append(", "); b.append(s); }
        return b.toString();
    }
    private static String capital(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    static String translateReason(String reason) {
        if (MlKitTranslation.NO_DOWNLOAD_MANAGER.equals(reason)) return "Thiết bị không có trình tải xuống của hệ thống (Download Manager) mà ML Kit dùng để tải mô hình.";
        if (MlKitTranslation.NATIVE_UNAVAILABLE.equals(reason)) return "Thư viện dịch ML Kit không chạy được trên CPU này.";
        return "";
    }

    static String downloadError(String code) {
        if ("NO_SPACE".equals(code)) return "không đủ dung lượng trống.";
        if ("NETWORK".equals(code)) return "không tải được (mạng hoặc máy chủ Google).";
        return "không tải được. Hãy thử lại.";
    }

    static String packError(String code, VoiceCatalog.Pack pack) {
        if (code == null) return "";
        switch (code) {
            case VoicePackManager.E_NO_SPACE: return "Không đủ dung lượng trống" + (pack == null ? "." : " (cần khoảng " + VoicePackManager.formatBytes(pack.totalBytes) + " + 128 MB dự phòng).");
            case VoicePackManager.E_NETWORK: return "Không tải được (mạng hoặc máy chủ). Kiểm tra kết nối rồi thử lại.";
            case VoicePackManager.E_CORRUPT: return "Tệp tải về không khớp mã SHA-256 nên đã bị loại bỏ. Hãy thử lại sau.";
            case VoicePackManager.E_INCOMPATIBLE: return "Gói giọng không chạy được trên thiết bị này.";
            case VoicePackManager.E_IN_USE: return "Giọng đang được dùng. Dừng phát trong AniBox rồi thử lại.";
            case VoicePackManager.E_STORAGE: return "Lỗi bộ nhớ khi cài. Hãy thử lại.";
            default: return "Lỗi không xác định (" + code + ").";
        }
    }

    static String engineError(String code) {
        if (code == null) return "Không khởi động được giọng AI.";
        switch (code) {
            case "MODEL_CORRUPT": return "Gói giọng bị hỏng — hãy xóa rồi tải lại.";
            case "NATIVE_UNAVAILABLE": return "Thiết bị không hỗ trợ thư viện giọng AI (kiến trúc CPU).";
            case "TIMEOUT": return "Nạp giọng quá lâu (quá 60 giây).";
            default: return "Không khởi động được giọng AI.";
        }
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(color); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.START);
        return t;
    }

    private StateListDrawable rowBackground() {
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[]{android.R.attr.state_focused}, shape(CARD_FOCUS, ACCENT));
        d.addState(new int[]{android.R.attr.state_pressed}, shape(CARD_FOCUS, ACCENT));
        d.addState(new int[]{}, shape(CARD, 0));
        return d;
    }

    private GradientDrawable shape(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill); g.setCornerRadius(dp(12));
        if (stroke != 0) g.setStroke(dp(3), stroke);
        return g;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
