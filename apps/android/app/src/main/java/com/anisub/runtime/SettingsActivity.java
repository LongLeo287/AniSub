package com.anisub.runtime;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.speech.tts.TextToSpeech;
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
import com.anisub.runtime.voice.VoiceRegistry;
import com.anisub.runtime.settings.AniSubPrefs;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private Row managerRow, catalogRow, readingRow;
    private Row systemInstallRow, storageRow, runtimeRow;
    private AlertDialog activeDialog;
    private int readingIndex, voiceActionIndex, priorityIndex;
    private String selectedVoiceId;
    private final ExecutorService storageWorker = Executors.newSingleThreadExecutor();
    private final Runnable refresh = this::render;
    private int previewCounter;
    private String previewId, previewPending, previewText;
    private float previewRate = 1f;
    private String trialResult;
    private boolean trialRunning;

    private final AiSpeechEngine.Listener engineListener = new AiSpeechEngine.Listener() {
        public void started(String id) { if (id.equals(previewId)) render(); }
        public void finished(String id) { if (id.equals(previewId)) { previewId = null; host.endPreview(); render(); } }
        public void failed(String id, String code) { if (id.equals(previewId)) { previewId = null; host.endPreview(); render(); } }
        public void engineChanged(AiSpeechEngine.State state, String error) {
            AiSpeechEngine engine = host.engine();
            if (previewPending != null && state == AiSpeechEngine.State.READY && engine != null && previewPending.equals(engine.loadedLanguage())) {
                String lang = previewPending; previewPending = null; speak(lang, previewText);
            }
            if (state == AiSpeechEngine.State.FAILED && previewPending != null) { previewPending = null; host.endPreview(); }
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
        managerRow = add(list, "Danh mục giọng đọc");
        systemInstallRow = add(list, "Tải giọng hệ thống");
        systemInstallRow.view.setOnClickListener(v -> installSystemVoices());
        catalogRow = add(list, "Cập nhật danh mục");
        managerRow.view.setOnClickListener(v -> showVoiceManager());
        catalogRow.view.setOnClickListener(v -> confirm("Kiểm tra danh mục?",
                "Tải danh mục từ GitHub của AniSub. Bản lỗi không thay thế danh mục đang dùng; không tự tải giọng mới.",
                "Kiểm tra", () -> { if (!host.refreshCatalog()) info("Chưa kiểm tra được", "Chờ phiên đọc hoặc thao tác tải hiện tại kết thúc."); }));
        header(list, text("Đọc và dịch", 22, ACCENT, true));
        rate = add(list, "Tốc độ đọc");
        readingRow = add(list, "Cách đọc giọng AI");
        readingRow.view.setOnClickListener(v -> showReadingSettings());
        trStatus = add(list, "Dịch phụ đề trên TV");
        trAuto = add(list, "Tự tải gói dịch khi cần");
        trModels = add(list, "Mô hình dịch");
        trTest = add(list, "Dịch thử");
        anibox = add(list, "Dùng trong AniBox");
        runtimeRow = add(list, "Tình trạng runtime");
        runtimeRow.info = true;
        storageRow = add(list, "Bộ nhớ");
        storageRow.view.setOnClickListener(v -> showStorage());
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

    @Override protected void onResume() {
        super.onResume();
        host.systemVoices().refresh();
        render();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (oneShotRepeat(event)) return true;
        return super.dispatchKeyEvent(normalizeKey(event));
    }

    static int normalizedKeyCode(int key) {
        if (key == KeyEvent.KEYCODE_BUTTON_A) return KeyEvent.KEYCODE_DPAD_CENTER;
        if (key == KeyEvent.KEYCODE_BUTTON_B) return KeyEvent.KEYCODE_BACK;
        return key;
    }

    private static KeyEvent normalizeKey(KeyEvent event) {
        int code = normalizedKeyCode(event.getKeyCode());
        return code == event.getKeyCode() ? event : new KeyEvent(event.getDownTime(), event.getEventTime(),
                event.getAction(), code, event.getRepeatCount(), event.getMetaState(), event.getDeviceId(),
                event.getScanCode(), event.getFlags(), event.getSource());
    }

    static boolean oneShotRepeat(KeyEvent event) {
        int code = normalizedKeyCode(event.getKeyCode());
        return event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0
                && (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER);
    }

    /** Dialogs have their own Window, so gamepad normalization must also happen here. */
    private void showDialog(AlertDialog dialog, boolean safe, int selection) {
        View opener = activeDialog != null && activeDialog.isShowing() ? activeDialog.getCurrentFocus() : getCurrentFocus();
        activeDialog = dialog;
        dialog.setOnKeyListener((d, key, event) -> {
            if (oneShotRepeat(event)) return true;
            KeyEvent normalized = normalizeKey(event);
            if (normalized != event) { dialog.dispatchKeyEvent(normalized); return true; }
            return false;
        });
        dialog.setOnDismissListener(d -> {
            if (activeDialog != dialog) return;
            activeDialog = null;
            if (opener != null && opener.isShown() && opener.isFocusable()) opener.requestFocus();
            else keepFocus();
        });
        dialog.show();
        if (safe && dialog.getButton(AlertDialog.BUTTON_NEGATIVE) != null)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
        else if (dialog.getListView() != null && selection >= 0) {
            dialog.getListView().setSelection(selection);
            dialog.getListView().requestFocus();
        }
    }

    @Override protected void onStop() {
        if (host.previewActive()) host.cancelPreview();
        host.removeListener(refresh);
        host.removeEngineListener(engineListener);
        if (host.engine() != null) {
            if (!host.sessionActive()) { host.engine().stop(); host.engine().scheduleIdleUnload(); }
        }
        if (host.translation() != null && !host.sessionActive()) host.translation().releaseClients();
        previewPending = null; previewId = null;
        super.onStop();
    }

    @Override protected void onDestroy() {
        storageWorker.shutdown();
        if (activeDialog != null) activeDialog.dismiss();
        super.onDestroy();
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
        rate.set("Tốc độ đọc", String.format(Locale.ROOT, "%.1f× mặc định · ◀ ▶ để chỉnh (0.8–1.3); từng giọng có tùy chỉnh riêng", host.defaultRate()), true, true);
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
                    ? "Chưa cài giọng AI. Có thể chọn riêng giọng hệ thống nếu thiết bị đã cài."
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
        managerRow.set("Danh mục giọng đọc", "Giọng AI và giọng hệ thống cục bộ · lựa chọn và tùy chỉnh riêng từng giọng", true, true);
        systemInstallRow.set("Tải giọng hệ thống", "Mở trình cài dữ liệu TTS của thiết bị; AniSub chỉ liệt kê giọng cục bộ đã cài.", true, true);
        catalogRow.set("Cập nhật danh mục", host.catalogState(), !host.sessionActive() && !host.anyPackBusy() && !host.previewActive(), true);
        readingRow.set("Cách đọc", "Biểu cảm mặc định, ngắt câu, khoảng nghỉ, âm lượng phim khi đọc " + host.settings().duckLevel()
                + "% và thứ tự phụ đề " + priorityLabel(host.settings().languagePriority()) + ".", true, true);
        String ab;
        if (host.aniBoxCompatible()) ab = "AniBox đã cài và cùng chữ ký. Mở AniBox › Cài đặt › Thuyết minh, chọn “Giọng AI (AniSub)”.";
        else if (host.aniBoxInstalled()) ab = "AniBox đã cài nhưng khác chữ ký nên không thể kết nối. Cài bản AniBox và AniSub chính thức.";
        else ab = "Chưa cài AniBox. AniSub là phần bổ trợ, cần AniBox để thuyết minh khi xem phim.";
        anibox.set("Dùng trong AniBox", ab, false, true);
        AiSpeechEngine engine = host.engine();
        String runtime = host.sessionActive() ? "Phiên đọc AniBox đang hoạt động" : "Không có phiên đọc AniBox";
        runtime += " · Engine AI: " + host.engineState();
        if (engine != null && engine.loadedLanguage() != null) runtime += " · model " + LanguageTags.displayName(engine.loadedLanguage());
        if(engine!=null&&engine.loadedVoiceId()!=null){VoiceRegistry.Entry resident=host.registry().find(engine.loadedVoiceId());if(resident!=null)runtime+=" · "+resident.name;}
        runtime += "\nGiọng hệ thống cục bộ đã cài: " + host.systemVoices().all().size();
        runtimeRow.set("Tình trạng runtime", runtime, false, true);
        storageRow.set("Bộ nhớ", "Dung lượng thực tế của gói giọng, mô hình dịch và bộ nhớ đệm · xóa an toàn", true, true);
        about.set("Giấy phép và giới thiệu", "AniSub " + host.versionName() + " (" + host.versionCode() + ") · "
                + SherpaSynthesizer.ENGINE + " " + SherpaSynthesizer.ENGINE_VERSION + " · ML Kit Translate · GPL-3.0-or-later", true, true);
        keepFocus();
    }

    /** A row that stops being actionable (or hides) while focused must not strand D-pad focus. */
    private void keepFocus() {
        if (activeDialog != null && activeDialog.isShowing()) return;
        View focused = getCurrentFocus();
        if (focused != null && focused.isFocusable() && focused.getVisibility() == View.VISIBLE) return;
        for (Row r : new Row[]{vi.action, vi.preview, en.action, en.preview, managerRow, systemInstallRow, catalogRow, rate, readingRow, trAuto, trModels, voice, vi.delete, en.delete, storageRow, about}) {
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
        if (previewId != null) { host.cancelPreview(); previewId = null; render(); return; }
        speakWhenReady(lang, LanguageTags.EN.equals(lang) ? PREVIEW_EN : PREVIEW_VI);
    }

    /** Loads the pack of {@code lang} when needed, then speaks {@code text}. */
    private void speakWhenReady(String lang, String text) {
        AiSpeechEngine engine = host.engine();
        if (engine == null || host.sessionActive()) return;
        previewVoice(host.defaultVoice(lang), text);
    }

    private void speak(String lang, String text) {
        AiSpeechEngine engine = host.engine();
        if (engine == null || host.sessionActive() || !lang.equals(engine.loadedLanguage())) return;
        engine.stop();
        previewId = "preview-" + (++previewCounter);
        if (!engine.speak(previewId, text, previewRate)) { previewId = null; host.endPreview(); }
        render();
    }

    private void onDelete(PackRows rows) {
        VoicePackManager voices = rows.manager();
        if (voices == null || host.sessionActive()) return;
        confirm("Xóa " + rows.label + "?", "Gói giọng sẽ bị xóa khỏi TV. AniBox sẽ không dùng được " + rows.label + " cho tới khi tải lại.", "Xóa", () -> {
            if (host.sessionActive()) { info("Không xóa được", packError(VoicePackManager.E_IN_USE, null)); return; }
            AiSpeechEngine engine = host.engine();
            Runnable remove = () -> {
                String error = host.deletePack(voices.pack().id);
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
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Mô hình dịch (ML Kit)")
                .setItems(labels.toArray(new String[0]), (d, which) -> onModel(tr, langs.get(which)))
                .setNegativeButton("Đóng", null).create();
        showDialog(dialog, false, 0);
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
        showDialog(d, true, -1);
    }

    private void showReadingSettings() {
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Cách đọc")
                .setItems(new String[]{"Biểu cảm mặc định", "Độ ngắt câu", "Khoảng nghỉ thêm giữa các dòng",
                        "Âm lượng phim khi đọc: " + host.settings().duckLevel() + "%",
                        "Ưu tiên phụ đề: " + priorityLabel(host.settings().languagePriority())}, (d, index) -> {
                    readingIndex = index;
                    if (index == 0) readingChoice("Biểu cảm mặc định · lần chuẩn bị kế tiếp", new String[]{"Điềm tĩnh", "Bình thường", "Sôi nổi"}, 0);
                    if (index == 1) readingChoice("Độ ngắt câu", new String[]{"Ngắn", "Bình thường", "Dài"}, 1);
                    if (index == 2) readingChoice("Khoảng nghỉ thêm", new String[]{"0 ms", "100 ms", "200 ms", "300 ms", "400 ms", "500 ms", "600 ms", "700 ms", "800 ms", "900 ms", "1000 ms"}, 2);
                    if (index == 3) readingChoice("Âm lượng phim · cần client hỗ trợ tùy chọn mới", new String[]{"10%", "20%", "30%", "40%", "50%", "60%"}, 3);
                    if (index == 4) showPriority();
                }).setNegativeButton("Đóng", null).create();
        showDialog(dialog, false, readingIndex);
    }

    private void readingChoice(String title, String[] labels, int kind) {
        com.anisub.runtime.settings.AniSubPrefs prefs = host.settings();
        int checked = kind == 0 ? com.anisub.runtime.settings.AniSubPrefs.STYLES.indexOf(prefs.style())
                : kind == 1 ? com.anisub.runtime.settings.AniSubPrefs.GAPS.indexOf(prefs.gap())
                : kind == 2 ? prefs.pauseMs() / 100 : Math.round(prefs.duckLevel() / 10f) - 1;
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title).setSingleChoiceItems(labels, checked, (d, index) -> {
                    if (kind == 0) prefs.setStyle(com.anisub.runtime.settings.AniSubPrefs.STYLES.get(index));
                    if (kind == 1) prefs.setGap(com.anisub.runtime.settings.AniSubPrefs.GAPS.get(index));
                    if (kind == 2) prefs.setPauseMs(index * 100);
                    if (kind == 3) prefs.setDuckLevel((index + 1) * 10);
                    host.notifySettingsChanged();
                    d.dismiss(); showReadingSettings();
                }).setNegativeButton("Quay lại", (d, which) -> showReadingSettings()).create();
        dialog.setOnCancelListener(d -> showReadingSettings());
        showDialog(dialog, false, checked);
    }

    private static String priorityLabel(List<String> languages) {
        List<String> names = new ArrayList<>();
        for (String language : languages) names.add(LanguageTags.displayName(language));
        return android.text.TextUtils.join(" → ", names);
    }

    private void showPriority() {
        List<String> languages = host.settings().languagePriority();
        String[] labels = new String[languages.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = (i + 1) + ". " + LanguageTags.displayName(languages.get(i));
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Ưu tiên phụ đề · cần client hỗ trợ tùy chọn mới")
                .setItems(labels, (d, index) -> { priorityIndex = index; showPriorityActions(languages.get(index)); })
                .setNegativeButton("Quay lại", (d, which) -> showReadingSettings()).create();
        dialog.setOnCancelListener(d -> showReadingSettings());
        showDialog(dialog, false, Math.min(priorityIndex, labels.length - 1));
    }

    private void showPriorityActions(String language) {
        List<String> languages = host.settings().languagePriority();
        int index = languages.indexOf(language);
        List<String> labels = new ArrayList<>(); List<Integer> moves = new ArrayList<>();
        if (index > 0) { labels.add("Đưa lên trước"); moves.add(-1); }
        if (index >= 0 && index < languages.size() - 1) { labels.add("Đưa xuống sau"); moves.add(1); }
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(LanguageTags.displayName(language))
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    host.settings().movePriority(language, moves.get(which));
                    host.notifySettingsChanged();
                    priorityIndex = host.settings().languagePriority().indexOf(language); render(); showPriority();
                }).setNegativeButton("Quay lại", (d, which) -> showPriority()).create();
        dialog.setOnCancelListener(d -> showPriority());
        showDialog(dialog, false, 0);
    }

    private void showVoiceManager() {
        List<VoiceRegistry.Entry> entries = host.registry().all();
        if (entries.isEmpty()) { info("Danh mục giọng", "Chưa có giọng khả dụng."); return; }
        String[] labels = new String[entries.size()];
        for (int i=0;i<entries.size();i++) {
            VoiceRegistry.Entry e=entries.get(i);
            VoiceRegistry.Entry def = host.registry().defaultVoice(e.kind, e.language);
            VoicePackManager manager = e.packId == null ? null : host.pack(e.packId);
            VoiceCatalog.Pack pack = manager == null ? null : manager.pack();
            labels[i]=e.name+" · "+LanguageTags.displayName(e.language)+" · "+(e.kind==VoiceRegistry.Kind.AI?"AI":"Hệ thống")
                    +" · "+genderLabel(e.gender)+(e.kind==VoiceRegistry.Kind.SYSTEM?" (ước lượng cao độ)":"")+" · "+accentLabel(e.accent)
                    + (pack == null ? "" : " · " + VoicePackManager.formatBytes(pack.totalBytes) + " · " + pack.license)
                    +" · "+(!e.installed?"Chưa cài":e.enabled?"Đã cài":"Đã tắt")
                    + (def != null && def.id.equals(e.id) ? " · Mặc định" : "");
        }
        AlertDialog dialog=new AlertDialog.Builder(this,android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Giọng AI / Giọng Google (hệ thống)").setItems(labels,(d,index)->{
                    selectedVoiceId = entries.get(index).id; voiceActionIndex = 0; showVoiceActions(selectedVoiceId);
                })
                .setNegativeButton("Đóng",null).create();
        int selected = 0;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).id.equals(selectedVoiceId)) selected = i;
        showDialog(dialog, false, selected);
    }

    private void showVoiceActions(String id) {
        VoiceRegistry.Entry e=host.registry().find(id);if(e==null)return;
        selectedVoiceId = id;
        String[] actions={"Thông tin / giấy phép", "Nghe thử", "Đặt mặc định ("+e.language+")",
                e.enabled?"Tắt giọng":"Bật giọng", "Tốc độ", "Cao độ", "Âm lượng", "Tải gói", "Xóa gói", "Biểu cảm giọng AI", "Ước lượng cao độ giọng hệ thống"};
        AlertDialog actionsDialog = new AlertDialog.Builder(this,android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(e.name)
                .setItems(actions,(dialog,index)->{
                    voiceActionIndex = index;
                    VoiceRegistry.Entry current=host.registry().find(id);if(current==null)return;
                    switch(index){
                        case 0:
                            VoicePackManager m=host.pack(current.packId);VoiceCatalog.Pack p=m==null?null:m.pack();
                            String detail=current.kind==VoiceRegistry.Kind.SYSTEM?"Giọng hệ thống đã cài, không dùng mạng. Nhãn giới tính chỉ là ước lượng cao độ: "+genderLabel(current.gender)+". Vùng giọng chưa xác minh.":
                                    "Giới tính: "+genderLabel(current.gender)+" · Vùng: "+accentLabel(current.accent)+"\n"+(p==null?"":VoicePackManager.formatBytes(p.totalBytes)+"\n"+p.license+"\n"+p.attribution);
                            info(current.name,detail);break;
                        case 1: previewVoice(id,"en".equals(current.language)?PREVIEW_EN:PREVIEW_VI);break;
                        case 2: if(current.usable())host.setDefaultVoice(id);else info("Chưa chọn được","Cài và bật giọng trước khi đặt mặc định.");break;
                        case 3: String error=host.setVoiceEnabled(id,!current.enabled);if(error!=null)info("Không tắt được","Phải giữ ít nhất một giọng AI tiếng Việt khả dụng.");break;
                        case 4: chooseSetting(id,"Tốc độ",new String[]{"0.8×","0.9×","1.0×","1.1×","1.2×","1.3×"},4);break;
                        case 5: chooseSetting(id,"Cao độ",new String[]{"−3","−2","−1","0","+1","+2","+3"},5);break;
                        case 6: chooseSetting(id,"Âm lượng",new String[]{"0%","25%","50%","75%","100%"},6);break;
                        case 7:
                            VoicePackManager manager=host.pack(current.packId);VoiceCatalog.Pack pack=manager==null?null:manager.pack();
                            if(pack==null){info("Giọng hệ thống","Dữ liệu giọng do ứng dụng TTS của thiết bị quản lý.");break;}
                            confirm("Tải "+pack.name+"?",VoicePackManager.formatBytes(pack.totalBytes)+" · GitHub AniSub\n"+pack.license+"\n"+pack.attribution,"Tải",()->{if(!host.downloadPack(pack.id))info("Chưa tải được","Chờ thao tác đang chạy kết thúc.");});break;
                        case 8:
                            if(current.packId==null){info("Giọng hệ thống","AniSub không xóa dữ liệu của ứng dụng TTS khác.");break;}
                            confirm("Xóa gói giọng?","Các giọng dùng chung gói sẽ bị xóa. Giọng mặc định cuối cùng và model đang dùng được bảo vệ.","Xóa",()->{
                                if(host.previewActive())host.cancelPreview();
                                Runnable remove=()->{String result=host.deletePack(current.packId);if(result!=null)runOnUiThread(()->info("Không xóa được","Gói đang dùng hoặc là giọng AI tiếng Việt cuối cùng."));};
                                if(host.engine()!=null)host.engine().unloadThen(remove);else remove.run();
                            });break;
                        case 9:
                            if (current.kind == VoiceRegistry.Kind.SYSTEM) { info("Giọng hệ thống", "Engine hệ thống không hỗ trợ bộ tham số biểu cảm AI này."); break; }
                            chooseSetting(id, "Biểu cảm · áp dụng lần chuẩn bị kế tiếp", new String[]{"Theo mặc định chung", "Điềm tĩnh", "Bình thường", "Sôi nổi"}, 9); break;
                        case 10:
                            if(current.kind!=VoiceRegistry.Kind.SYSTEM){info("Giọng AI","Thông tin giọng AI lấy từ catalog đã duyệt.");break;}
                            confirm("Đo cao độ cục bộ?","Tạo một mẫu WAV ngắn trên thiết bị rồi xóa. Nhãn nam/nữ chỉ là ước lượng theo cao độ, không xác minh danh tính hoặc giới tính người nói. Không dùng mạng.","Đo",()->{
                                if(!host.beginPreview(()->{})){info("Chưa đo được","Chờ phiên đọc hoặc thao tác đang chạy kết thúc.");return;}
                                boolean started=host.systemVoices().estimatePitch(current,"en".equals(current.language)?PREVIEW_EN:PREVIEW_VI,result->runOnUiThread(()->{
                                    host.endPreview();host.notifySettingsChanged();
                                    if(!isFinishing()&&!isDestroyed()&&!"cancelled".equals(result.reason)){info("Ước lượng cao độ",genderLabel(result.gender)+" (ước lượng) · "+Math.round(result.pitchHz)+" Hz\nVùng giọng chưa xác minh.");render();}
                                }));
                                if(!started){host.endPreview();info("Chưa đo được","Engine không hỗ trợ tạo mẫu cục bộ hoặc đang bận.");}
                            });break;
                    }
                    render();
                }).setNegativeButton("Quay lại",(d,which)->showVoiceManager()).create();
        actionsDialog.setOnCancelListener(d->showVoiceManager());
        showDialog(actionsDialog, false, voiceActionIndex);
    }

    private void chooseSetting(String id,String title,String[] labels,int setting) {
        AniSubPrefs prefs = host.settings();
        int selected = setting == 4 ? Math.round((prefs.voiceRate(id) - .8f) * 10)
                : setting == 5 ? prefs.voicePitch(id) + 3 : setting == 6 ? Math.round(prefs.voiceVolume(id) / 25f)
                : AniSubPrefs.STYLES.indexOf(prefs.voiceStyle(id)) + 1;
        if (setting == 9 && !prefs.hasVoiceStyle(id)) selected = 0;
        AlertDialog settingDialog = new AlertDialog.Builder(this,android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title)
                .setSingleChoiceItems(labels,selected,(d,index)->{
                    if(setting==4)host.settings().setVoiceRate(id,.8f+index*.1f);
                    if(setting==5)host.settings().setVoicePitch(id,index-3);
                    if(setting==6)host.settings().setVoiceVolume(id,index*25);
                    if(setting==9)host.settings().setVoiceStyle(id,index == 0 ? null : AniSubPrefs.STYLES.get(index - 1));
                    host.notifySettingsChanged();
                    d.dismiss();
                    showVoiceActions(id);
                }).setNegativeButton("Hủy",(d,which)->showVoiceActions(id)).create();
        settingDialog.setOnCancelListener(d->showVoiceActions(id));
        showDialog(settingDialog, false, selected);
    }

    private void previewVoice(String id,String phrase) {
        VoiceRegistry.Entry e=host.registry().find(id);
        if(e==null||!e.usable()){info("Chưa nghe thử được","Cài và bật giọng trước.");return;}
        if(!host.beginPreview(()->{++previewCounter;if(host.engine()!=null)host.engine().stop();previewId=null;previewPending=null;})){
            info("Đang dùng model","Dừng phiên đọc hoặc chờ thao tác tải hoàn tất.");return;
        }
        if(e.kind==VoiceRegistry.Kind.SYSTEM){
            final int generation=++previewCounter;
            if(!host.systemVoices().preview(e,host.settings().snapshot(id),phrase,()->runOnUiThread(()->{if(generation==previewCounter){host.endPreview();render();}}))){
                host.endPreview(); info("Chưa nghe thử được", "Giọng hệ thống không sẵn sàng. Kiểm tra dữ liệu TTS của thiết bị rồi thử lại.");
            }
            return;
        }
        AiSpeechEngine engine=host.engine();if(engine==null){host.endPreview();return;}
        previewPending=e.language;previewText=phrase;
        com.anisub.runtime.settings.AniSubPrefs.Snapshot snapshot=host.settings().snapshot(id);
        previewRate=snapshot.rate;
        engine.configure(e.language,id,snapshot);render();
    }

    private void info(String title, String message) {
        ScrollView scroll = new ScrollView(this);
        TextView body = text(message, 15, Color.WHITE, false);
        body.setPadding(dp(24), dp(12), dp(24), dp(12));
        body.setFocusable(true);
        scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title).setView(scroll).setPositiveButton("Đóng", null).create();
        showDialog(dialog, false, -1);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
    }

    private void installSystemVoices() {
        Intent intent = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
        String engine = host.systemVoices().enginePackage();
        if (engine != null && !engine.isEmpty()) intent.setPackage(engine);
        if (intent.resolveActivity(getPackageManager()) == null) {
            info("Không có trình cài giọng", "Ứng dụng TTS của thiết bị không cung cấp màn hình cài dữ liệu. Cài giọng cục bộ trong cài đặt hệ thống."); return;
        }
        try { startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException ignored) {
            info("Không mở được trình cài giọng", "Thiết bị không cho mở trình cài dữ liệu TTS. Cài giọng trong cài đặt hệ thống.");
        }
    }

    private static String genderLabel(String gender) {
        if ("male".equals(gender)) return "Nam";
        if ("female".equals(gender)) return "Nữ";
        return "Chưa xác minh giới tính";
    }

    private static String accentLabel(String accent) {
        if ("north".equals(accent)) return "Bắc";
        if ("central".equals(accent)) return "Trung";
        if ("south".equals(accent)) return "Nam";
        if ("us".equals(accent) || "en-US".equals(accent)) return "Mỹ";
        return "Chưa xác minh vùng giọng";
    }

    private void showStorage() {
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(8), dp(24), dp(8));
        TextView details = text("Đang đo dung lượng tệp…", 15, Color.WHITE, false);
        details.setFocusable(true); details.setPadding(0, dp(8), 0, dp(16)); content.addView(details);
        Row cache = add(content, "Xóa bộ nhớ đệm");
        cache.set("Xóa bộ nhớ đệm", "Chỉ xóa tệp tạm khi runtime rảnh; giữ model và dữ liệu dịch.", true, true);
        cache.view.setOnClickListener(v -> confirm("Xóa bộ nhớ đệm?", "Tệp tạm của AniSub sẽ bị xóa. Các model, giọng mặc định và bản model tốt gần nhất được giữ.", "Xóa", this::clearCache));
        Row remove = add(content, "Xóa các gói ngoài giọng mặc định");
        remove.set("Xóa các gói ngoài giọng mặc định", "Giữ gói chứa giọng AI mặc định của từng ngôn ngữ; giọng dùng chung gói cũng được giữ.", true, true);
        remove.view.setOnClickListener(v -> confirm("Xóa các gói ngoài giọng mặc định?", "Chỉ gói giọng AI không chứa giọng mặc định được xét xóa. Model đang dùng và giọng tiếng Việt cuối cùng luôn được bảo vệ; dữ liệu TTS hệ thống và dịch không bị xóa.", "Xóa", this::deleteNonDefaultPacks));
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Bộ nhớ").setView(scroll).setNegativeButton("Đóng", null).create();
        showDialog(dialog, false, -1); details.requestFocus();
        storageWorker.execute(() -> {
            RuntimeHost.StorageSnapshot snapshot;
            try { snapshot = host.storageSnapshot(); }
            catch (java.io.IOException | RuntimeException ignored) {
                runOnUiThread(() -> { if (!isFinishing() && !isDestroyed() && dialog.isShowing()) details.setText("Không đo được dung lượng tệp. Hãy thử mở lại khi runtime rảnh."); });
                return;
            }
            StringBuilder report = new StringBuilder("Gói giọng (kể cả bản tốt gần nhất): ")
                    .append(VoicePackManager.formatBytes(snapshot.voiceBytes));
            for (Map.Entry<String, Long> item : snapshot.packBytes.entrySet()) {
                VoicePackManager manager = host.pack(item.getKey());
                VoiceCatalog.Pack pack = manager == null ? null : manager.pack();
                report.append("\n• ").append(pack == null ? item.getKey() : pack.name).append(": ")
                        .append(VoicePackManager.formatBytes(item.getValue()));
            }
            report.append("\nDữ liệu riêng khác (gồm dữ liệu dịch): ").append(VoicePackManager.formatBytes(snapshot.otherPrivateBytes));
            report.append("\nBộ nhớ đệm: ").append(VoicePackManager.formatBytes(snapshot.cacheBytes));
            report.append("\nTrống trên thiết bị: ").append(VoicePackManager.formatBytes(snapshot.freeBytes));
            report.append("\nGiọng hệ thống do ứng dụng TTS quản lý. ML Kit không cung cấp dung lượng tệp riêng từng ngôn ngữ qua API công khai.");
            runOnUiThread(() -> { if (!isFinishing() && !isDestroyed() && dialog.isShowing()) details.setText(report.toString()); });
        });
    }

    private void clearCache() {
        if (host.sessionActive() || host.anyPackBusy()) { info("Chưa xóa được", "Dừng phiên đọc và chờ thao tác tải hoàn tất."); return; }
        if (host.previewActive()) host.cancelPreview();
        Runnable clear = () -> submitStorage(() -> {
            String error = host.clearSafeCache();
            runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) info(error == null ? "Đã xóa bộ nhớ đệm" : "Chưa xóa được",
                    error == null ? "Các model và giọng đọc được giữ." : "Runtime còn đang dùng tệp hoặc không truy cập được bộ nhớ. Hãy thử lại khi rảnh."); });
        });
        if (host.engine() != null) host.engine().unloadThen(clear); else clear.run();
    }

    static Set<String> defaultPackIds(VoiceRegistry registry) {
        Set<String> kept = new LinkedHashSet<>();
        for (VoiceRegistry.Entry entry : registry.all()) if (entry.kind == VoiceRegistry.Kind.AI) {
            VoiceRegistry.Entry def = registry.defaultVoice(VoiceRegistry.Kind.AI, entry.language);
            if (def != null && def.packId != null) kept.add(def.packId);
        }
        return kept;
    }

    private void deleteNonDefaultPacks() {
        if (host.sessionActive() || host.anyPackBusy()) { info("Chưa xóa được", "Dừng phiên đọc và chờ thao tác tải hoàn tất."); return; }
        if (host.previewActive()) host.cancelPreview();
        Runnable remove = () -> submitStorage(() -> {
            int removed = 0, retained = 0;
            // The host rechecks the live default/lease rules for every deletion.
            for (VoiceCatalog.Pack pack : host.catalog().packs) {
                if (defaultPackIds(host.registry()).contains(pack.id)) { retained++; continue; }
                VoicePackManager manager = host.pack(pack.id);
                if (manager == null || manager.installed() == null) continue;
                if (host.deletePackUnlessDefault(pack.id) == null) removed++; else retained++;
            }
            final String result = "Đã xóa " + removed + " gói; giữ " + retained + " gói mặc định hoặc đang được bảo vệ. Mô hình dịch và TTS hệ thống được giữ.";
            runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) { render(); info("Dọn gói giọng", result); } });
        });
        if (host.engine() != null) host.engine().unloadThen(remove); else remove.run();
    }

    private void submitStorage(Runnable work) {
        try { storageWorker.execute(work); }
        catch (java.util.concurrent.RejectedExecutionException ignored) { /* Activity already left. */ }
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
