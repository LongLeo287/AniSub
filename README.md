# AniSub

AniSub là runtime độc lập hỗ trợ phụ đề, dịch và giọng đọc đồng bộ với playback.
AniBox là client qua addon riêng do Claude phụ trách. AniSub không sở hữu player, catalog hay nguồn phim.

Ưu tiên sản phẩm: **gọn nhẹ, nhanh, realtime cho video** — dịch phụ đề, thuyết minh và
lồng tiếng thử nghiệm. Ba dạng sử dụng dự kiến: plugin/client AniBox, app Windows và extension
Chrome. Model được cập nhật độc lập, có kiểm tra tương thích và rollback; không tự thay model
giữa phiên xem. Plugin/extension không mang theo bộ engine nặng.

Trạng thái 2026-10-07: **host Windows nền chạy model thật**, Chrome companion direct subtitle,
OCR Việt/Anh và ASR thu âm riêng tiến trình. Turbo/codec/Whisper/OCR đã tải và xác minh.
Chrome extension cần nạp/đăng ký bằng thao tác người dùng; chưa kiểm thử trong Chrome đã cài.
Android: **AniSub cho TV** có giọng thuyết minh AI chạy ngay trên máy (sherpa-onnx + giọng Piper
`vi_VN-vais1000-medium`), tự tải gói giọng ngay lần đầu chạy, kiểm tra SHA-256, sau đó
chạy offline; giao thức Messenger major 1 / minor 2 với AniBox cùng chữ ký. Từ 0.3.0: phụ đề khác
ngôn ngữ giọng đọc được dịch ngay trên TV (ML Kit Translate; gói dịch tiếng Việt tự tải lần đầu, gói khác tự tải khi cần) và có thêm giọng
tiếng Anh `en_US-ljspeech-medium` (gói `voices-en-v1`, chờ phát hành). Số đo trên P650
đang chờ kiểm thử phần cứng. Mã nguồn công khai: https://github.com/LongLeo287/AniSub (GPL-3.0-or-later).
[Cách dùng video ở ứng dụng riêng](docs/external-desktop.md), [kiểm chứng](docs/full-desktop-validation.md).
[Trạng thái Android và cách kiểm thử](apps/android/README.md).

Mở `Start-AniSub.cmd` hoặc `Start-AniSub-Background.cmd` để dùng app nền, không player.
`Start-AniSub-Player.cmd` giữ harness thử nghiệm cũ. Trong harness chọn **Prepare offline models**, mở video và SRT/VTT,
bật **Translate offline** và **Narrate Vietnamese**, rồi Play. Chuẩn bị model một lần trước
khi xem để tránh chậm lần đầu. Tùy máy, codec video có thể không được WPF hỗ trợ.

Host nền có 25 preset Turbo Bắc/Trung/Nam và nam/nữ; Nano riêng còn 11 preset Bắc/Nam.
Nạp danh sách giọng để chọn, chuẩn bị model trước khi xem. Auto cửa sổ chỉ gợi ý, không xác minh
mọi app đang phát video. OCR/ASR cần chọn nguồn và xác nhận quyền; không thu toàn hệ thống.
Tên riêng/ngoại ngữ vẫn cần người nghe đánh giá, không hứa chuẩn mọi ngôn ngữ.

Bản thuyết minh đã có tạo giọng trước, đọc trọn câu và chuyển âm lượng mềm. Chế độ mặc định
**Read complete / sync wait** cho video chờ ngắn khi cần để không nuốt câu; bỏ chọn nếu ưu tiên
video không chờ (câu bỏ sẽ được đếm). [Kiểm chứng đồng bộ](docs/narration-sync-validation.md).

Chạy từ folder AniSub, dùng Node >=22 có sẵn, không cần cài dependency:

```powershell
node scripts/check.mjs
node scripts/test.mjs
node apps/desktop/harness/demo.mjs
```

Demo chỉ xuất diagnostic counters, không in nội dung phụ đề. Thời gian giả lập không phải
benchmark realtime. [Hướng dẫn harness](docs/windows-harness.md) phân biệt phần đã chạy và còn thiếu.

## Cài AniSub trên Android TV

AniSub là phần bổ trợ của AniBox: AniBox phát phim, AniSub đọc phụ đề bằng giọng AI. Từ 0.3.0, phụ đề khác
ngôn ngữ giọng đọc được dịch ngay trên TV, nên AI Thuyết minh dùng được trên mọi video có phụ đề, kể cả phim hardsub
(AniBox tìm phụ đề chữ chạy ngầm và khớp giờ).

1. Cài AniBox trước (bình thường). AniSub cài sau vẫn kết nối được, không cần cài lại AniBox.
2. Cài AniSub:
   - Từ AniBox: AniBox tự tải và cài bản chính thức (kiểm tra SHA-256 và chữ ký).
   - Hoặc bằng ứng dụng **Downloader** trên TV, nhập địa chỉ:
     `https://github.com/LongLeo287/AniSub/releases/latest/download/AniSub.apk`
3. Ngay lần đầu AniSub chạy (AniBox kết nối hoặc bạn mở AniSub), giọng tiếng Việt mặc định (khoảng
   64 MB, từ GitHub của AniSub) và gói dịch tiếng Việt (khoảng 30 MB, từ máy chủ Google) **tự tải**;
   mở **AniSub** để xem tiến độ, giấy phép và bấm **Nghe thử**. Mọi tệp được tải bằng trình tải
   xuống của Android rồi kiểm tra SHA-256 (ML Kit của Google có thể liên lạc máy chủ Google, không gửi phụ đề). Giọng tiếng Anh là tùy chọn.
4. Trong AniBox, vào **Cài đặt › AniSub** chọn **Ngôn ngữ giọng đọc** (Việt hoặc Anh), rồi bấm chip
   **Thuyết minh** trong trình phát. Khi giọng AI đọc, AniBox tự giảm âm lượng phim rồi trả lại khi đọc xong.
5. Cập nhật: khi có AniSub mới, AniBox bắt cập nhật lúc mở app (trường `mandatory` trong `anisub.json`; vắng
   mặt hoặc `true` là bắt buộc, `false` là cập nhật mềm). Phần quản lý giọng đọc (thêm giọng nam, giọng Google)
   hoãn sang bản sau; 0.3.0 có giọng Việt và giọng Anh.

Hai ứng dụng phải cùng chữ ký phát hành (bản chính thức đã như vậy; người dùng không cần khóa nào).
Không thu âm, không chụp màn hình, không gửi phụ đề đi đâu; mạng chỉ dùng để tải gói giọng và gói dịch
(qua trình tải xuống của Android).
Gỡ AniSub sẽ xóa luôn gói giọng; AniBox vẫn phát phim bình thường.

Chi tiết kỹ thuật: [apps/android/README.md](apps/android/README.md),
[hợp đồng AniBox](docs/android-addon-contract.md), [gói giọng và giấy phép](docs/voices.md).

## Giấy phép

AniSub: GPL-3.0-or-later ([LICENSE](LICENSE)). Thành phần bên thứ ba:
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Repo không chứa trọng số model.

## Tài liệu chuẩn

- [System design](docs/system-design.md): nguồn sự thật về boundary và invariants.
- [Thiết kế runtime Android](docs/superpowers/specs/2026-10-07-anisub-runtime-design.md): thiết kế giai đoạn tiếp theo, không phải bằng chứng engine đã chạy.
- [Hợp đồng addon cho Claude](docs/android-addon-contract.md): tách major 1 test đang có khỏi v2 dự thảo chưa triển khai.
- [File/folder map](docs/file-map.md): module, file dự kiến và chủ sở hữu.
- [Protocol](docs/protocol.md): session, cue, playback, speech và lỗi.
- [Roadmap](docs/roadmap.md): acceptance và trạng thái từng giai đoạn.
- [Task packet](docs/task-packet.md): scope, baseline, kiểm chứng và rollback.
- [Repo research](docs/research/README.md): nguồn từ sheet, shortlist có điều kiện và provider gates.
- [Realtime product design](docs/realtime-product-design.md): ba dạng sử dụng, realtime và cập nhật model.

## Cấu trúc

```text
AniSub/
├── docs/                    hợp đồng kiến trúc và tiến độ
├── core/                    session, pipeline và scheduling thuần logic
├── protocol/                model giao tiếp, schema và versioning
├── providers/               adapter OCR / ASR / translation / TTS
├── model-manager/           manifest, download, integrity, storage, load leases
├── apps/
│   ├── android/             APK AniSub cho TV: giọng AI sherpa-onnx, tải gói giọng và gói dịch, dịch trên máy, Messenger 1.2
│   └── desktop/             harness Node + prototype Windows WPF/model worker
├── clients/                 kế hoạch SDK AniBox và Chrome companion mỏng; addon AniBox do Claude phụ trách
├── tests/                   fixtures, contract, integration, performance
└── reference/               tư liệu chưa đưa vào runtime
    └── anibox-integration-draft/ bản nháp đã tách khỏi AniBox
```

Các folder có README xác định trách nhiệm. File-map ghi rõ những module Node đã có và phần
production còn dự kiến. Không copy repo engine hoặc model
weights vào source control. Model được lưu riêng trong `models/`, thư viện riêng/tệp tạm
trong `work/` (đều Git-ignored). Dependency, license và benchmark được ghi trong tài liệu Windows.

Nguyên tắc sản phẩm: direct subtitle first; OCR khi chỉ có chữ trong hình; ASR khi thiếu
phụ đề phù hợp. Translation và speech đều cần opt-in. Bản gốc và provenance được giữ lại.
