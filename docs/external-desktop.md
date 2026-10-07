# AniSub — video ở ứng dụng riêng

## Chạy trên máy hiện tại

Mở `Start-AniSub-Background.cmd`. Không cần mở video trong AniSub. **Auto** ở panel nguồn
gợi ý cửa sổ đang dùng; đây chưa phải bằng chứng cửa sổ đang phát video. Có thể chọn thủ công.
Chọn **Nạp danh sách giọng**, lọc vùng/nam-nữ, rồi **Chuẩn bị model trước khi xem** để giảm
trễ lần đầu. Profile Auto dùng Turbo đã cài, không đổi model giữa phiên. Muốn Nano nhẹ hơn:
chạy `apps/desktop/windows/Start-Background.ps1 -VoiceProfile Nano` bằng Windows PowerShell STA.

**Thu gọn** giấu cài đặt, giữ trạng thái/phụ đề và nút dừng. Đóng cửa sổ sẽ dừng xử lý/capture;
không có dịch vụ chạy ẩn sau khi đóng, tự khởi động Windows hoặc Windhawk dependency.

## Web có phụ đề

1. Mở phần **Kết nối Chrome** trong app. Trong Chrome Extensions, bật Developer mode và Load
   unpacked folder `clients/chrome-extension` (app có nút copy đường dẫn).
2. Dán ID extension vào app và bấm cho phép kết nối. Chỉ thao tác này mới đăng ký native host
   cho người dùng Windows hiện tại; không cần quyền admin. Không được tự ghi đè host khác.
3. Bỏ chọn nguồn cửa sổ thủ công trong AniSub khi dùng Chrome companion. Mở video HTTP(S),
   bấm biểu tượng AniSub, chọn video và track Việt/Anh rồi Bắt đầu. Giọng chọn ở app Windows.
4. Dừng hoặc đổi tab sẽ khôi phục âm lượng video. Gỡ bridge bằng
   `apps/desktop/native-messaging/Unregister-NativeHost.ps1`, gỡ extension trong Chrome riêng.

Companion hỗ trợ TextTracks đọc được của video HTML5 main-frame. Không hứa đọc được mọi
YouTube/player tùy biến/iframe/canvas/DRM. Không thu âm toàn tab hay trích xuất nội dung bảo vệ.
Native-host registration và smoke trong Chrome đang đăng nhập chưa được thực hiện trong task.

## App/game có chữ trong hình

Chọn đúng cửa sổ, chọn **OCR phụ đề trong hình**, xác nhận quyền đọc nguồn rồi Bắt đầu.
ROI mặc định là 35% phía dưới cửa sổ. Auto Việt/Anh dùng nhận diện Latin và heuristic ngôn ngữ;
chọn ngôn ngữ thủ công khi nhận nhầm. Việt dùng EasyOCR pack local; Anh có Windows OCR.
Đọc được phụ thuộc render/cỡ chữ/vị trí/độ tương phản; PrintWindow có thể trả đen với game,
minimized/hardware rendering/protected video. Không injection hay bypass anti-cheat.

## Không có sub: ASR

Chọn app và **ASR âm thanh ứng dụng**, Auto hoặc ngôn ngữ nguồn, xác nhận quyền rồi Bắt đầu.
Thu âm chỉ cây tiến trình đã chọn bằng process loopback, không lấy âm thanh toàn máy. Không
chọn AniSub làm nguồn để tránh vòng lặp. Cần Windows build 20348+ và nguồn cho phép capture.
Game launcher không đồng nghĩa game đang phát: chọn tiến trình/cửa sổ game thực tế.

Capture chạy liên tục; chunk 4 giây, tối đa hai chunk chờ. Quá tải bỏ chunk cũ và báo đếm.
Whisper Small nhận dạng Việt trực tiếp; nguồn khác dịch sang Anh rồi Marian EN→VI. Đây là
thuyết minh live **có độ trễ**, không có media clock của game, không hứa khớp tuyệt đối hay
không mất bất kỳ câu nào. OCR và ASR là mode được chọn, không chạy đồng thời mặc định.

## Giọng và model

Turbo có 25 preset với metadata Bắc/Trung/Nam và nam/nữ. Hai giọng Trung Quang Sơn/Ngọc Trân
đã sinh waveform thật; đây không phải đánh giá người nghe về chất giọng. Nano vẫn giữ riêng
với 11 preset Bắc/Nam. Turbo hỗ trợ đường Việt/Anh; không hứa phát âm chuẩn mọi ngoại ngữ/tên
riêng. Giữ nguyên văn bản, không tự thay tên sang phiên âm thiếu kiểm chứng.

Các pack mới đã cài, kiểm tra đầy đủ: Turbo+codec+Whisper 1,052,207,871 byte; OCR Latin
98,558,471 byte (detector được tái sử dụng từ cache đã xác minh, không sửa cache cũ). Model
nằm ngoài source/app. Runtime Python/.NET trên máy này chưa được đóng gói installer portable.
Catalog đang pinned/manual; cập nhật tự động có chữ ký, rollback phiên bản và UI download
cho máy mới chưa hoàn thiện. Không chạy code Hub, không tự tải lúc inference.

## Giới hạn sản phẩm

Đây là host standalone có đường xử lý thật, chưa phải bản release đã thử mọi app/game/site.
Thuyết minh một giọng khác với lồng tiếng tách vai, loại bỏ lời gốc và lip-sync; các phần sau
chưa có. Process-audio ducking cho app/game chưa có; browser ducking đã có. AniBox không bị sửa.
Model freshness, chất lượng dịch/giọng trên phim dài, bộ nhớ/điện năng và cài Chrome thật vẫn
cần nghiệm thu riêng. Không claim full production chỉ từ unit test.
