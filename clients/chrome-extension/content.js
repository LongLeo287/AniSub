(() => {
  if (globalThis.__aniSubAdapter) return;
  globalThis.__aniSubAdapter = true;
  let state = null, timer = null, busy = false, closing = Promise.resolve(), startTicket = 0;
  const videos = () => [...document.querySelectorAll('video')].slice(0, 32);
  const language = track => (track.language || '').toLowerCase().split('-')[0];
  const inventory = () => ({ ok: true, videos: videos().map(v => ({ width: v.videoWidth, height: v.videoHeight, playing: !v.paused,
    tracks: Array.from({ length: Math.min(32, v.textTracks.length) }, (_, index) => {
      const t = v.textTracks[index]; return { index, label: (t.label || '').slice(0, 80), language: language(t) };
    }) })) });
  function makeOverlay() {
    const host = document.createElement('div');
    host.style.cssText = 'position:fixed;left:50%;bottom:12px;transform:translateX(-50%);z-index:2147483647;pointer-events:none;max-width:85vw';
    const shadow = host.attachShadow({ mode: 'closed' });
    const label = document.createElement('div'); label.style.cssText = 'background:#111827e8;color:white;border-radius:14px;padding:10px 18px;font:16px/1.5 system-ui;text-align:center;white-space:pre-wrap';
    label.textContent = 'AniSub đang kết nối…'; shadow.append(label); document.documentElement.append(host);
    return { host, label };
  }
  function ramp(s, target, duration = 200) {
    if (s.ramp) cancelAnimationFrame(s.ramp);
    const from = s.video.volume, started = performance.now();
    function frame(now) {
      if (state !== s) return;
      const x = Math.min(1, (now - started) / duration), eased = x * x * (3 - 2 * x);
      s.lastSet = from + (target - from) * eased; s.video.volume = s.lastSet;
      if (x < 1) s.ramp = requestAnimationFrame(frame); else s.ramp = null;
    }
    s.ramp = requestAnimationFrame(frame);
  }
  function duck(s, speaking) {
    if (s.speaking === speaking) return;
    s.speaking = speaking; ramp(s, speaking ? s.baseline * .22 : s.baseline, speaking ? 180 : 320);
  }
  function envelope(s, type) { return { type, session: s.id, revision: s.revision, sequence: ++s.sequence }; }
  async function wire(s, payload) {
    return chrome.runtime.sendMessage({ action: 'wire', payload });
  }
  function snapshot(s) {
    const now = Math.max(0, s.video.currentTime * 1000), cues = [];
    const trackCues = s.track.cues;
    if (trackCues && trackCues.length > 10000) throw Error('TRACK_TOO_LARGE');
    for (let i = 0; i < (trackCues?.length || 0); i++) {
      const cue = trackCues[i];
      if (cues.length >= 32) break;
      const startMs = cue.startTime * 1000, endMs = cue.endTime * 1000;
      if (endMs <= now || startMs > now + 5000 || !Number.isFinite(startMs) || !Number.isFinite(endMs) || endMs <= startMs || startMs < 0) continue;
      const text = (cue.text || '').replace(/<[^>]*>/g, '').trim();
      if (text.length > 512) throw Error('CUE_TOO_LONG');
      if (text) cues.push({ startMs, endMs, text });
    }
    return { ...envelope(s, 'snapshot'), positionMs: now, playing: !s.video.paused && !s.video.ended && !s.video.seeking,
      speed: s.video.playbackRate, language: language(s.track), cues };
  }
  async function pump(force = false) {
    const s = state; if (!s || busy) return;
    if (!s.video.isConnected) { stop('VIDEO_REMOVED'); return; }
    if (s.video.playbackRate < .5 || s.video.playbackRate > 2) { stop('UNSUPPORTED_SPEED'); return; }
    if (!['vi', 'en'].includes(language(s.track))) { stop('UNSUPPORTED_LANGUAGE'); return; }
    busy = true;
    try {
      const now = performance.now();
      const sendSnapshot = force || s.dirty || now - s.lastSnapshot >= 750;
      const payload = sendSnapshot ? snapshot(s) : envelope(s, 'status');
      if (sendSnapshot) { s.dirty = false; s.lastSnapshot = now; }
      const reply = await wire(s, payload);
      if (state !== s || payload.revision !== s.revision) return;
      if (!reply?.ok) { stop(reply?.error || 'UNAVAILABLE'); return; }
      duck(s, reply.speaking === true);
      s.overlay.label.textContent = reply.translated || (s.video.paused ? 'AniSub — tạm dừng' : 'AniSub — thuyết minh');
    } catch { if (state === s) stop('DISCONNECTED'); }
    finally { busy = false; }
  }
  function stop(reason = 'STOPPED') {
    const s = state; if (!s) return;
    state = null; clearInterval(timer); timer = null;
    if (s.ramp) cancelAnimationFrame(s.ramp);
    for (const [target, event, handler] of s.listeners) target.removeEventListener(event, handler);
    // Restore latest user-intended volume, not the value observed before a manual change.
    s.video.volume = s.baseline;
    if (s.track.mode === 'hidden' && s.previousTrackMode === 'disabled') s.track.mode = 'disabled';
    if (reason !== 'STOPPED') {
      s.overlay.label.textContent = 'AniSub đã dừng — ' + reason + '. Mở nút AniSub để chọn lại nguồn.';
      setTimeout(() => s.overlay.host.remove(), 8000);
    } else s.overlay.host.remove();
    closing = wire(s, envelope(s, 'close')).catch(() => {});
    return closing;
  }
  async function start(message, ticket) {
    if (!Number.isInteger(message.video) || !Number.isInteger(message.track)) return { ok: false, error: 'INVALID_SELECTION' };
    stop(); await closing;
    if (ticket !== startTicket) return { ok: false, error: 'CANCELLED' };
    const video = videos()[message.video], track = video?.textTracks[message.track];
    if (!track) return { ok: false, error: 'NO_TEXT_TRACK' };
    if (!['vi', 'en'].includes(language(track))) return { ok: false, error: 'UNSUPPORTED_LANGUAGE' };
    const s = { video, track, id: crypto.randomUUID(), revision: 0, sequence: 0, dirty: true, lastSnapshot: -Infinity,
      baseline: video.volume, lastSet: video.volume, speaking: false, ramp: null, listeners: [], previousTrackMode: track.mode, overlay: makeOverlay() };
    state = s; if (track.mode === 'disabled') track.mode = 'hidden';
    const on = (target, event, handler) => { target.addEventListener(event, handler); s.listeners.push([target, event, handler]); };
    const invalidate = () => { if (state !== s) return; s.revision++; s.dirty = true; duck(s, false); pump(true); };
    for (const event of ['play', 'pause', 'seeking', 'seeked', 'ratechange']) on(video, event, invalidate);
    for (const event of ['ended', 'emptied']) on(video, event, () => stop());
    on(track, 'cuechange', () => { s.dirty = true; });
    on(video.textTracks, 'change', invalidate);
    on(video, 'volumechange', () => {
      if (Math.abs(video.volume - s.lastSet) <= .0001) return;
      if (s.ramp) { cancelAnimationFrame(s.ramp); s.ramp = null; }
      s.baseline = video.volume; s.lastSet = video.volume;
      // Preserve manual intent; let the next speech transition reapply ducking.
    });
    timer = setInterval(() => pump(), 250); pump(true);
    return { ok: true };
  }
  chrome.runtime.onMessage.addListener((message, sender, respond) => {
    if (sender.id !== chrome.runtime.id) return false;
    if (message?.action === 'inventory') respond(inventory());
    else if (message?.action === 'start') { start(message, ++startTicket).then(respond).catch(() => respond({ ok: false, error: 'UNAVAILABLE' })); return true; }
    else if (message?.action === 'stop' || message?.action === 'host-disconnected') {
      if (message.action === 'host-disconnected' && message.session && message.session !== state?.id) { respond({ ok: true }); return false; }
      ++startTicket;
      stop(message.action === 'host-disconnected' ? (message.error || 'DISCONNECTED') : 'STOPPED'); respond({ ok: true });
    }
    return false;
  });
  addEventListener('pagehide', () => { ++startTicket; stop(); });
})();
