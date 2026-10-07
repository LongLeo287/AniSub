package com.anisub.runtime;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
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
import com.anisub.runtime.voice.VoiceCatalog;
import com.anisub.runtime.voice.VoicePackManager;
import java.util.Locale;

/**
 * TV settings for the AI voice: D-pad rows, BACK closes, Vietnamese text, dark theme.
 * Exported without a permission on purpose (AniBox is usually installed before AniSub, so a
 * custom permission would never be granted). It exposes no data and only acts on explicit
 * on-screen confirmation, so any launcher/AniBox may open it.
 */
public final class SettingsActivity extends Activity {
    public static final String ACTION = "com.anisub.runtime.action.SETTINGS";
    private static final String PREVIEW_TEXT = "Xin chào! Đây là giọng thuyết minh AI của AniSub, chạy ngay trên TV của bạn.";
    private static final int BG = 0xFF0E1621, CARD = 0xFF162231, CARD_FOCUS = 0xFF24364D, ACCENT = 0xFF70DAD0, MUTED = 0xFF9FB0C3;

    private RuntimeHost host;
    private Row status, action, voice, rate, preview, delete, anibox, about;
    private final Runnable refresh = this::render;
    private int previewCounter;
    private String previewId;
    private boolean previewPending;

    private final AiSpeechEngine.Listener engineListener = new AiSpeechEngine.Listener() {
        public void started(String id) { if (id.equals(previewId)) render(); }
        public void finished(String id) { if (id.equals(previewId)) { previewId = null; render(); } }
        public void failed(String id, String code) { if (id.equals(previewId)) { previewId = null; render(); } }
        public void engineChanged(AiSpeechEngine.State state, String error) {
            if (previewPending && state == AiSpeechEngine.State.READY) { previewPending = false; speakPreview(); }
            if (state == AiSpeechEngine.State.FAILED) previewPending = false;
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

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        host = RuntimeHost.get(this);
        getWindow().setBackgroundDrawable(new ColorDrawable(BG));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(56), dp(32), dp(56), dp(24));
        root.setBackgroundColor(BG);
        root.addView(text("AniSub · Thuyết minh AI", 30, Color.WHITE, true));
        TextView sub = text("Đọc phụ đề tiếng Việt bằng giọng AI chạy ngay trên TV. Tải gói giọng một lần, sau đó dùng không cần mạng.", 16, MUTED, false);
        sub.setPadding(0, dp(6), 0, dp(18));
        root.addView(sub);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        status = add(list, "Trạng thái giọng AI");
        action = add(list, "Tải giọng AI");
        voice = add(list, "Giọng đọc");
        rate = add(list, "Tốc độ đọc");
        preview = add(list, "Nghe thử");
        delete = add(list, "Xóa giọng AI");
        anibox = add(list, "Dùng trong AniBox");
        about = add(list, "Giấy phép và giới thiệu");
        status.info = true; anibox.info = true;
        action.view.setOnClickListener(v -> onAction());
        voice.view.setOnClickListener(v -> nextVoice());
        rate.view.setOnClickListener(v -> stepRate(+1, true));
        rate.view.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { stepRate(+1, false); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { stepRate(-1, false); return true; }
            return false;
        });
        preview.view.setOnClickListener(v -> onPreview());
        delete.view.setOnClickListener(v -> onDelete());
        about.view.setOnClickListener(v -> onAbout());
        setContentView(root);
        render();
        action.view.requestFocus();
    }

    @Override protected void onStart() {
        super.onStart();
        host.addListener(refresh);
        host.addEngineListener(engineListener);
        render();
    }

    @Override protected void onStop() {
        host.removeListener(refresh);
        host.removeEngineListener(engineListener);
        if (host.engine() != null) {
            if (!host.sessionActive()) { host.engine().stop(); host.engine().scheduleIdleUnload(); }
        }
        previewPending = false; previewId = null;
        super.onStop();
    }

    private Row add(LinearLayout list, String label) { Row r = new Row(label); list.addView(r.view); return r; }

    private void render() {
        VoicePackManager.Status s = host.packStatus();
        AiSpeechEngine engine = host.engine();
        if (s == null) {
            status.set("Trạng thái giọng AI", "Lỗi bộ nhớ giọng: không mở được dữ liệu giọng trong máy.", false, true);
            action.set("Xóa dữ liệu giọng và làm lại", "Chỉ xóa dữ liệu giọng của AniSub.", true, true);
            voice.set("Giọng đọc", null, false, false); rate.set("Tốc độ đọc", null, false, false);
            preview.set("Nghe thử", null, false, false); delete.set("Xóa giọng AI", null, false, false);
            renderCommon();
            return;
        }
        VoiceCatalog.Pack pack = s.pack;
        String size = pack == null ? "" : VoicePackManager.formatBytes(pack.totalBytes);
        boolean installed = s.installedVersion != null;
        String statusText;
        switch (s.state) {
            case DOWNLOADING:
                int pct = s.totalBytes <= 0 ? 0 : (int) Math.min(100, s.doneBytes * 100 / s.totalBytes);
                statusText = "Đang tải… " + pct + "% (" + VoicePackManager.formatBytes(s.doneBytes) + " / " + size + ")"
                        + "\nGiữ màn hình này mở cho tới khi tải xong.";
                break;
            case VERIFYING: statusText = "Đang kiểm tra SHA-256 và cài đặt…"; break;
            case READY:
                statusText = "Sẵn sàng · " + (pack == null ? "" : pack.name) + " · phiên bản " + s.installedVersion;
                if (engine != null && engine.state() == AiSpeechEngine.State.LOADING) statusText += " · đang nạp giọng…";
                if (engine != null && engine.state() == AiSpeechEngine.State.FAILED) statusText += "\n" + engineError(engine.error());
                if (s.error != null) statusText += "\nLần cập nhật trước lỗi: " + packError(s.error, pack);
                break;
            case ERROR: statusText = "Lỗi: " + packError(s.error, pack); break;
            default: statusText = "Chưa cài gói giọng AI. AniBox vẫn dùng được giọng hệ thống (nếu có).";
        }
        if (host.sessionActive()) statusText += "\nĐang dùng trong AniBox.";
        // The download runs in this app process; keep the TV awake while it is in progress.
        if (s.state == VoicePackManager.State.DOWNLOADING || s.state == VoicePackManager.State.VERIFYING)
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        status.set("Trạng thái giọng AI", statusText, false, true);

        if (s.state == VoicePackManager.State.DOWNLOADING) action.set("Hủy tải", "Dữ liệu đang tải dở sẽ bị xóa.", true, true);
        else if (s.state == VoicePackManager.State.VERIFYING) action.set("Đang cài đặt…", null, false, true);
        else if (installed && s.updateAvailable) action.set("Cập nhật giọng AI (" + size + ")", "Bản mới: " + pack.version + ". Bản đang dùng vẫn giữ nếu cập nhật lỗi.", true, true);
        else if (installed) action.set("Giọng AI đã cài", "Gói giọng đã được kiểm tra toàn vẹn.", false, true);
        else action.set(s.state == VoicePackManager.State.ERROR ? "Thử tải lại (" + size + ")" : "Tải giọng AI (" + size + ")",
                "Cần đồng ý trước khi tải. Hiện dung lượng và giấy phép.", pack != null, true);

        boolean ready = s.ready();
        if (pack != null && pack.voices.size() > 1) {
            VoiceCatalog.Voice chosen = pack.voice(host.defaultVoice());
            voice.set("Giọng đọc", (chosen == null ? pack.voices.get(0) : chosen).name + "  ·  OK để đổi", ready && !host.sessionActive(), true);
        }
        else voice.set("Giọng đọc", pack == null ? "—" : pack.voices.get(0).name + " (gói có một giọng)", false, true);
        rate.set("Tốc độ đọc", String.format(Locale.ROOT, "%.1f×  ·  ◀ ▶ để chỉnh (0.8–1.3)", host.defaultRate()), true, true);
        String previewValue;
        boolean canPreview = ready && engine != null && !host.sessionActive();
        if (!ready) previewValue = "Cần tải giọng AI trước.";
        else if (host.sessionActive()) previewValue = "AniBox đang dùng giọng; dừng phát để nghe thử.";
        else if (previewPending || (engine != null && engine.state() == AiSpeechEngine.State.LOADING)) previewValue = "Đang nạp giọng…";
        else if (previewId != null) previewValue = "Đang đọc…";
        else previewValue = "Đọc một câu mẫu với tốc độ đã chọn.";
        preview.set("Nghe thử", previewValue, canPreview, true);
        delete.set("Xóa giọng AI", installed ? "Giải phóng " + size + ". Có thể tải lại bất cứ lúc nào." : null, installed && !host.sessionActive()
                && s.state != VoicePackManager.State.DOWNLOADING && s.state != VoicePackManager.State.VERIFYING, installed);
        renderCommon();
    }

    private void renderCommon() {
        String ab;
        if (host.aniBoxCompatible()) ab = "AniBox đã cài và cùng chữ ký. Mở AniBox › Cài đặt › Thuyết minh, chọn “Giọng AI (AniSub)”.";
        else if (host.aniBoxInstalled()) ab = "AniBox đã cài nhưng khác chữ ký nên không thể kết nối. Cài bản AniBox và AniSub chính thức.";
        else ab = "Chưa cài AniBox. AniSub là phần bổ trợ, cần AniBox để thuyết minh khi xem phim.";
        anibox.set("Dùng trong AniBox", ab, false, true);
        about.set("Giấy phép và giới thiệu", "AniSub " + host.versionName() + " (" + host.versionCode() + ") · "
                + SherpaSynthesizer.ENGINE + " " + SherpaSynthesizer.ENGINE_VERSION + " · GPL-3.0-or-later", true, true);
        keepFocus();
    }

    /** A row that stops being actionable (or hides) while focused must not strand D-pad focus. */
    private void keepFocus() {
        View focused = getCurrentFocus();
        if (focused != null && focused.isFocusable() && focused.getVisibility() == View.VISIBLE) return;
        for (Row r : new Row[]{action, preview, rate, voice, delete, about}) {
            if (r.view.isFocusable() && r.view.getVisibility() == View.VISIBLE) { r.view.requestFocus(); return; }
        }
    }

    private void onAction() {
        VoicePackManager voices = host.voices();
        if (voices == null) {
            confirm("Xóa dữ liệu giọng?", "Chỉ xóa dữ liệu giọng AI của AniSub để khởi tạo lại.", "Xóa", () -> host.resetStore());
            return;
        }
        VoicePackManager.Status s = voices.status();
        if (s.state == VoicePackManager.State.DOWNLOADING) { voices.cancel(); return; }
        VoiceCatalog.Pack pack = s.pack;
        if (pack == null) return;
        if (host.sessionActive()) { info("Đang phát trong AniBox", "Dừng phát trong AniBox rồi tải hoặc cập nhật giọng để không làm giật phim."); return; }
        String message = "Gói: " + pack.name + "\nDung lượng tải: " + VoicePackManager.formatBytes(pack.totalBytes)
                + " (cần thêm khoảng 128 MB trống dự phòng)\n\nGiấy phép: " + pack.license + "\n\n" + pack.attribution
                + "\n\nTải qua HTTPS từ bản phát hành công khai của AniSub trên GitHub. Mỗi tệp được kiểm tra SHA-256 trước khi cài. "
                + "Sau khi tải, giọng chạy hoàn toàn trên TV, không gửi phụ đề đi đâu.";
        confirm("Tải giọng AI?", message, "Đồng ý tải", () -> {
            if (host.sessionActive()) return;
            // Free the resident model first so the post-install smoke test never holds two copies.
            if (host.engine() != null) host.engine().unload();
            voices.download(true);
        });
    }

    private void nextVoice() {
        VoicePackManager.Status s = host.packStatus();
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

    private void onPreview() {
        AiSpeechEngine engine = host.engine();
        if (engine == null || host.sessionActive()) return;
        if (previewId != null) { engine.stop(); previewId = null; render(); return; }
        if (engine.ready()) speakPreview();
        else { previewPending = true; engine.load(); render(); }
    }

    private void speakPreview() {
        AiSpeechEngine engine = host.engine();
        if (engine == null || host.sessionActive()) return;
        engine.stop();
        previewId = "preview-" + (++previewCounter);
        if (!engine.speak(previewId, PREVIEW_TEXT, host.defaultRate())) previewId = null;
        render();
    }

    private void onDelete() {
        VoicePackManager voices = host.voices();
        if (voices == null || host.sessionActive()) return;
        confirm("Xóa giọng AI?", "Gói giọng sẽ bị xóa khỏi TV. AniBox sẽ không dùng được giọng AI cho tới khi tải lại.", "Xóa", () -> {
            if (host.sessionActive()) { info("Không xóa được", packError(VoicePackManager.E_IN_USE, null)); return; }
            AiSpeechEngine engine = host.engine();
            Runnable remove = () -> {
                String error = voices.delete();
                if (error != null) runOnUiThread(() -> info("Không xóa được", packError(error, null)));
            };
            if (engine != null) engine.unloadThen(remove); else remove.run();
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
