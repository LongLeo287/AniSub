let tabId;
const status = document.querySelector('#status'), videoSelect = document.querySelector('#video'), trackSelect = document.querySelector('#track');
let videos = [];
const errors = { NATIVE_HOST_UNAVAILABLE: 'Chưa đăng ký cầu nối Chrome hoặc chưa mở AniSub Windows.', NO_TEXT_TRACK: 'Video chưa có track phụ đề đọc được. OCR/ASR phải chọn trong app Windows.', UNSUPPORTED_LANGUAGE: 'Bản này hỗ trợ phụ đề tiếng Việt / Anh.', SOURCE_BUSY: 'Dừng nguồn tab trước rồi chọn nguồn mới.' };
function display(reply) { status.textContent = reply?.ok ? 'Đang kết nối; xem trạng thái AniSub trên video.' : (errors[reply?.error] ?? 'Không kết nối được. Kiểm tra AniSub Windows và trang video.'); }
function tracks() {
  trackSelect.replaceChildren();
  const selected = videos[Number(videoSelect.value)];
  for (const track of selected?.tracks ?? []) {
    const option = document.createElement('option'); option.value = track.index;
    option.textContent = `${track.label || 'Phụ đề'} — ${track.language || 'chưa rõ ngôn ngữ'}`;
    option.disabled = !['vi', 'en'].includes(track.language); trackSelect.append(option);
  }
  const first = [...trackSelect.options].find(o => !o.disabled); if (first) trackSelect.value = first.value;
  document.querySelector('#start').disabled = !first;
}
videoSelect.addEventListener('change', tracks);
document.querySelector('#start').addEventListener('click', async () => {
  try { display(await chrome.tabs.sendMessage(tabId, { action: 'start', video: Number(videoSelect.value), track: Number(trackSelect.value) })); }
  catch { display({ error: 'UNAVAILABLE' }); }
});
document.querySelector('#stop').addEventListener('click', async () => {
  try { await chrome.runtime.sendMessage({ action: 'release-source' }); status.textContent = 'Đã yêu cầu dừng, âm lượng được khôi phục.'; }
  catch { status.textContent = 'Không có nguồn đang chạy.'; }
});
(async () => {
  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true }); tabId = tab.id;
    // Injection occurs only after opening this action popup (activeTab grant).
    await chrome.scripting.executeScript({ target: { tabId }, files: ['content.js'] });
    const result = await chrome.tabs.sendMessage(tabId, { action: 'inventory' }); videos = result.videos;
    videos.forEach((v, index) => { const o = document.createElement('option'); o.value = index; o.textContent = `Video ${index + 1} — ${v.width}×${v.height}${v.playing ? ' đang phát' : ''}`; videoSelect.append(o); });
    tracks(); status.textContent = videos.length ? 'Chọn video và phụ đề rồi Bắt đầu.' : 'Không tìm thấy video ở trang chính.';
  } catch { status.textContent = 'Không truy cập được trang này. Chọn trang web video thông thường.'; }
})();
