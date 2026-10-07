import { cleanMessage } from './wire.mjs';
let owner = null, native = null, pending = null, queuedClose = null, lastSequence = 0, lastRevision = -1;
const errorReply = error => ({ ok: false, speaking: false, error });
function notifyStopped(tabId, error, session) {
  chrome.tabs.sendMessage(tabId, { action: 'host-disconnected', error, session }).catch(() => {});
}
function disconnect(error = 'DISCONNECTED') {
  const previous = owner;
  owner = null; lastSequence = 0; lastRevision = -1;
  if (pending) { clearTimeout(pending.timer); pending.respond(errorReply(error)); pending = null; }
  if (queuedClose) { queuedClose.respond(errorReply(error)); queuedClose = null; }
  if (native) { const p = native; native = null; try { p.disconnect(); } catch {} }
  if (previous) notifyStopped(previous.tabId, error, previous.session);
}
function connect() {
  if (native) return;
  native = chrome.runtime.connectNative('com.anisub.desktop');
  const port = native;
  port.onMessage.addListener(reply => {
    if (native !== port) return;
    if (!pending) return;
    const p = pending; pending = null; clearTimeout(p.timer);
    const safe = { ok: reply?.ok === true, speaking: reply?.speaking === true,
      translated: typeof reply?.translated === 'string' ? reply.translated.slice(0, 4096) : '',
      error: typeof reply?.error === 'string' && /^[A-Z0-9_]{1,80}$/.test(reply.error) ? reply.error : '' };
    p.respond(safe);
    if (queuedClose && native) {
      const close = queuedClose; queuedClose = null; forward(close.wire, close.respond);
    }
  });
  port.onDisconnect.addListener(() => {
    if (native !== port) return;
    void chrome.runtime.lastError;
    disconnect('NATIVE_HOST_UNAVAILABLE');
  });
}
function forward(wire, respond) {
  try {
    connect();
    pending = { respond, timer: setTimeout(() => disconnect('TIMEOUT'), 5000) };
    if (wire.type === 'close') {
      const original = pending.respond;
      pending.respond = reply => { original(reply); disconnect('STOPPED'); };
    }
    native.postMessage(wire);
  } catch { disconnect('NATIVE_HOST_UNAVAILABLE'); }
}
chrome.runtime.onMessage.addListener((message, sender, respond) => {
  if (sender.id !== chrome.runtime.id) return false;
  if (message?.action === 'release-source' && !sender.tab) {
    if (!owner) { respond({ ok: true }); return false; }
    chrome.tabs.sendMessage(owner.tabId, { action: 'stop' }).catch(() => disconnect());
    respond({ ok: true }); return false;
  }
  // Only the explicitly injected main-frame adapter can claim a source.
  if (!sender.tab || sender.frameId !== 0 || !/^https?:\/\//.test(sender.url ?? '') || message?.action !== 'wire') return false;
  let wire;
  try { wire = cleanMessage(message.payload); } catch (e) { respond(errorReply(e.message)); return false; }
  if (owner && (owner.tabId !== sender.tab.id || owner.session !== wire.session)) {
    respond(errorReply('SOURCE_BUSY')); return false;
  }
  if (!owner) {
    if (wire.type !== 'snapshot') { respond(errorReply('NO_SESSION')); return false; }
    owner = { tabId: sender.tab.id, session: wire.session };
  }
  if (wire.sequence <= lastSequence || wire.revision < lastRevision) { respond(errorReply('STALE_MESSAGE')); return false; }
  if (pending) {
    if (wire.type === 'close' && !queuedClose) {
      lastSequence = wire.sequence; lastRevision = wire.revision;
      queuedClose = { wire, respond }; return true;
    }
    respond(errorReply('BACKPRESSURE')); return false;
  }
  lastSequence = wire.sequence; lastRevision = wire.revision;
  forward(wire, respond);
  return true;
});
chrome.tabs.onRemoved.addListener(tabId => { if (owner?.tabId === tabId) disconnect(); });
chrome.tabs.onUpdated.addListener((tabId, change) => { if (owner?.tabId === tabId && change.status === 'loading') disconnect(); });
