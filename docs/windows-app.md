# AniSub Windows — bản thử chạy AI offline thật

Không gắn vào AniBox. Mở `D:\SEOSONA AI\AniSub\Start-AniSub.cmd` bằng double-click.
Launcher dùng cấu hình Python cục bộ ở `work/windows-local.json`; không sửa môi trường toàn máy.

1. Nhấn **Prepare offline models** (lần đầu mất khoảng 10–12 giây trên máy test).
2. Open video, Open SRT/VTT; chọn EN→VI hoặc VI→VI theo nội dung phụ đề.
3. Bật Translate offline cho EN→VI; bật Narrate Vietnamese nếu muốn thuyết minh. Play.
4. Pause/seek/stop/speed/offset/ngắt phụ đề sẽ hủy kết quả cũ và dừng giọng hiện tại.

Demo tự tạo, không phải phim của người dùng: `tests/fixtures/windows-demo.mp4` +
`tests/fixtures/windows-demo.srt`. Video test do FFmpeg tạo, không tải media bên ngoài.

## Model và runtime

- [Marian OPUS EN→VI](https://huggingface.co/Helsinki-NLP/opus-mt-en-vi):
  revision `989c9fb9ec63987901022baf0182dcec3e149be6`, 291,627,698 byte (~278 MiB).
- [VieNeu v3 Nano](https://huggingface.co/pnnbao-ump/VieNeu-TTS-v3-Nano):
  revision `aba295eb96a6fa6003ebe417cc1f2802a7adc1dc`, 281,771,889 byte (~269 MiB),
  preset voice, CPU ONNX, 24 kHz, 8 steps, không voice cloning/graph phụ.
- Hai model card công bố Apache-2.0; license/attribution upstream lưu cùng model README.
  Cần audit dependency/license đầy đủ trước đóng gói/phân phối, không chỉ dựa vào model card.
- Tổng dữ liệu model: 573,399,587 byte (~547 MiB), chưa tính Python/Torch/thư viện.
- Máy test dùng Python 3.11 hiện có; Torch 2.5.1+cu121 chạy **CPU**, Transformers 5.12.1,
  ONNX Runtime 1.20.1; wheel riêng ở `work/python-deps`: vieneu 3.8.3, sea-g2p 0.9.1.
  Không quảng cáo đây là bản độc lập nhỏ gọn đã đóng gói: Python/Torch có thể rất lớn.

Model cài bằng `model-manager/tools/install_windows_models.py`, explicit opt-in, revision pin,
kiểm tra size + SHA/LFS hoặc Git blob, stage rồi rename. Mỗi lần load kiểm tra đủ tập artifact
và SHA-256; không remote model code. Phiên inference ép offline; không gọi cloud/Hub ngầm.
Đây **chưa** là hệ thống signed catalog/auto update/rollback production.

## Kiểm chứng

`tests/windows/app-tests.ps1`: biên parser SRT/VTT, lỗi/giới hạn và tạo UI không model.
`provider-tests.py`: nạp model thật, dịch ba câu synthetic, sinh WAV, rate/RMS/text bounds;
evidence ở `work/windows-provider-evidence.json`, chỉ test này xuất nội dung synthetic.
`wire-tests.py`/`bridge-tests.ps1`: process persistent, UTF-8, correlation, synthesis.
`Start-AniSub.ps1 -Smoke -VideoPath ... -PythonPath ... -ModelDir ... -TtsDir ...`:
clock video, play/pause/seek/speed, overlay dịch, WAV output request và ngắt/khôi phục ducking.
Kết quả tổng hợp: [validation](windows-app-validation.md).

## Giới hạn rõ ràng

EN→VI mới là cặp dịch thật; VI→VI bỏ dịch và đọc tiếng Việt. Không tự phát hiện ngôn ngữ.
Không OCR/ASR/tab capture/DRM/browser site integration, multi-speaker dubbing/lip-sync hay AniBox hook.
Nano đang preview; chất lượng giọng/dịch chưa chấm bởi người nghe, mẫu ngắn không chứng minh
realtime cho phim dài. Giọng được sinh trọn cue, không first-PCM streaming; chưa prefetch.
Cue hết hạn bị bỏ, không đọc đuổi hàng loạt. Đổi tốc độ không time-stretch WAV nên narration
có thể bị cắt khi cue mới xuất hiện. Codec không hỗ trợ báo lỗi thay vì âm thầm fallback.

Một job + cue chờ mới nhất; cache dịch RAM 256 mục (xóa khi đóng process), payload 4096 ký tự,
MT 256 input token/128 output token, TTS 512 ký tự/30 giây; tối đa 8 WAV/phiên. Âm thanh tạm
xóa khi đóng bình thường, parent dọn phiên của mình sau kill; toàn cache có budget ~128 MiB.
Hard crash cả app có thể để lại file; vượt quota thì từ chối tạo phiên thay vì tự xóa media.
Test waveform cố ý giữ một mẫu synthetic để đối chiếu. Không lưu phụ đề người dùng mặc định.

## Bước tiếp theo

Test phụ đề/video thật có quyền sử dụng, đo p50/p95 latency/memory/deadline-drop và nghe giọng;
thêm bounded prefetch, speed-aligned speech, tối ưu Torch CPU/ONNX và package runtime riêng.
Sau đó Chrome direct-cue companion; AniBox plugin chỉ sau yêu cầu tích hợp riêng.
