# AniSub — nghiên cứu repo và quyết định triển khai

Snapshot 2026-10-06. AniSub độc lập; chưa gắn vào AniBox. Đây là nghiên cứu nguồn và thiết kế,
không phải kết quả chạy model hoặc xác nhận realtime trên thiết bị.

## Phạm vi thực tế

Nguồn đầu vào: [Repo Inventory - Classified Tiered](https://docs.google.com/spreadsheets/d/1IgJ_RvV9FfBkeFmXJpYUWfBJ6nkww1j1yjC9F0WMyo0/edit).
Đã đọc 1.693 hàng dữ liệu trong Repos_Classified, sàng lọc metadata thành 126 ứng viên bằng
từ khóa audio/speech/subtitle/translation/OCR/ASR và tên engine. Đây không phải 1.693 repo đã
đọc code. Bộ lọc có false positive và có thể bỏ sót repo không chứa từ khóa; không coi tier,
visits hay mô tả cũ trong sheet là bằng chứng chất lượng AniSub.

Filter tái lập: case-insensitive regex trên Repo/Model, Name, Representative Title, Category,
Tags (cột C/D/G/K/L):

```text
speech|subtitle|translat|whisper|sensevoice|tts|ocr|paddle|onnx|sherpa|audio\.cpp|omnivoice|vieneu|supertonic|nllb|marian|ctranslate|llama\.cpp|vad|voice|subtitl|asr|faster-whisper|silero|vosk|seamless
```

CSV snapshot toàn corpus là projection tên/URL/category, không phải bản sao đủ mọi cột để rerun
regex. Muốn rerun phải đọc lại C/D/G/K/L của sheet; CSV ứng viên giữ kết quả snapshot và row id.

- Snapshot toàn bộ hàng (`sheet-repo-snapshot.csv`): chỉ lưu cục bộ, không công khai (có tên repo riêng tư).
- 126 ứng viên (`sheet-candidate-inventory.csv`): chỉ lưu cục bộ, không công khai.
- [TTS](tts-provider-research.md): 18 ứng viên sheet và Supertonic bổ sung; đọc sâu VieNeu/OmniVoice.
- [OCR/workflow](ocr-workflow-research.md): 20 ứng viên; đọc sâu SubVoice/RSTGameTranslation/VideoLingo.
- [Native/ASR/translation](native-asr-translation-research.md): API/lifecycle audio.cpp, whisper.cpp,
  sherpa và các comparator/model dịch; ghi rõ upstream bổ sung ngoài bộ lọc.
- [Task packet](task-packet.md): scope và giới hạn nghiên cứu.

Các hàng không được dẫn trong ba báo cáo chi tiết mới chỉ được sàng lọc metadata, chưa có
verdict kỹ thuật. Báo cáo phân biệt source-code inspection, README/model-card claims và platform
contracts. Không có benchmark local. Commit pin có ở các phần đọc sâu; link moving HEAD được
ghi là bằng chứng ít tái lập hơn. GitHub API bị rate-limit; tiếp tục đọc raw text/public docs,
không dùng việc fetch lỗi LICENSE để kết luận chắc chắn rằng upstream không có giấy phép.

## Shortlist có điều kiện, không phải engine đã chọn

| Vai trò | Ưu tiên nghiên cứu | Điều kiện trước khi đưa vào runtime |
|---|---|---|
| TTS Việt desktop | VieNeu v3 Turbo CPU/ONNX | dependency/frontend/voice assets; cancel; cue deadline; RAM/latency |
| Native audio Windows | audio.cpp adapter | capability từng model; owner-thread; cancel blocking; license dependency |
| TTS Việt/Thái máy mạnh | VoxCPM2; MOSS variant cụ thể | audit API/cancel; model card từng variant; VRAM và chất lượng |
| ASR native | whisper.cpp; sherpa-onnx + checkpoint cụ thể | ngôn ngữ input; timestamps; ABI/API/device; bộ nhớ và BGM |
| ASR desktop đối chứng | faster-whisper; Qwen3-ASR | CPU/GPU profile; streaming backend; alignment không suy từ language list |
| Dịch local | CTranslate2 + model pair được phép | EN→VI baseline; JA→VI/TH→VI phải đánh giá riêng bằng dialogue |
| OCR | ROI/change gate + bounded worker; scene-OCR nhỏ sau | consent capture; model/language cụ thể; outline/fade/names/false dedup |

VieNeu gốc và audio.cpp port không đồng nhất: native VieNeu port được đọc hiện chưa port
streaming/CAM++; không lấy benchmark SDK Python để hứa hiệu năng native. OmniVoice code Apache
nhưng pretrained weights CC-BY-NC; NLLB và nhiều TTS khác có hạn chế riêng, không chọn làm
default phát hành chỉ vì repo mang license permissive. Chi tiết và nguồn nằm trong từng báo cáo.

SubVoice đáng học chính sách ROI, generation guard và queue; không phải nền tảng Windows/Android
để fork trực tiếp. RST có actual GPL v3 khác badge BSD. Batch dubbing của VideoLingo/OpenCreator
không giải quyết sẵn scheduling theo media clock khi seek/pause. License chưa rõ thì chưa sao chép
code; process isolation không tự loại bỏ nghĩa vụ license.

## Những điều thay đổi trong thiết kế AniSub

1. Core điều phối không phụ thuộc audio.cpp/ONNX/Python. Host compose adapter; chọn ngôn ngữ
   core sau portability spike Windows/Android không tải model.
2. Capability là **artifact + backend + host + mode**: tách full utterance, completed text chunks,
   incremental PCM; tách ASR language, alignment language, translation pair và TTS language.
3. Manifest là dependency graph: weights, tokenizer, codec, G2P/dictionary, voice assets và native
   libraries đều có revision/digest/size/license riêng. Không auto-download hay trust_remote_code
   ngoài approval/review rõ ràng.
4. Stop output và loại kết quả revision cũ phải hiệu lực dù inference chưa dừng. Cooperative,
   discard-only và worker-termination là ba cancellation capability khác nhau.
5. Direct subtitle first. OCR/ASR là chế độ input được cấp quyền, không phải fallback ngầm khi
   một snapshot rỗng. Audio capture Android API 29+ không đồng nghĩa API 23 có cùng khả năng.
6. SpeechStarted phát khi audible output bắt đầu; first PCM là metric khác. Client tương lai sở
   hữu ducking. Không để callback inference giả làm callback playback.

## Kế hoạch kiểm chứng tiếp theo

Phase 1: portability/contracts. Phase 2: standalone fake runtime chạy qua PlaybackSimulator,
chưa dùng model và chưa cần AniBox. Test pause/seek/stop/speed/track/source/reconnect, stale results,
exactly-once terminal callbacks, bounded queues và resource cleanup.

Sau approval tải model: benchmark preset voice có quyền dùng, 100 cue Việt đa dạng; JA/EN/Thai
audio cho ASR và language-pair dịch được chấm riêng. Fixture do người dùng cung cấp/có quyền dùng,
không tự tải phim hay clone giọng. Đo cold/warm load, first PCM/first audible, full duration,
p50/p95 deadline misses, RSS/VRAM/storage, drop counts và cancel recovery. OCR cần cảnh nền sáng,
outline, karaoke/fade, text mới gần giống text cũ và câu ngắn; ASR cần BGM/chồng thoại/silence.

Acceptance trước enable provider: không phát chunk obsolete sau output-stop acknowledgement;
không callback terminal kép; queue/memory có giới hạn; lỗi worker không phá session; đánh giá
ngôn ngữ và nghe thủ công đạt yêu cầu sản phẩm được xác định trước. Không mặc định batch RTF
hoặc lời tác giả chứng minh subtitle realtime. Android/P650/API 23 cần evidence riêng.

## Trạng thái bàn giao

Đã có nghiên cứu, shortlist, nguồn và implementation gates. Chưa có engine/model được cài,
runtime build/test, emulator hay hardware benchmark. Không sửa sheet, không thay code AniBox,
không commit/push/release. Roadmap provider vẫn Pending; bước kế tiếp là no-model portability spike.
