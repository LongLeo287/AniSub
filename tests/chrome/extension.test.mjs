import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import { cleanMessage } from '../../clients/chrome-extension/wire.mjs';

const root = new URL('../../clients/chrome-extension/', import.meta.url);
const snapshot = overrides => ({ type: 'snapshot', session: 'unit-session', revision: 0, sequence: 1,
  positionMs: 1000, playing: true, speed: 1, language: 'vi', cues: [{ startMs: 500, endMs: 2500, text: 'Synthetic test' }], ...overrides });

test('minimal permissions, no broad content injection or external connections', () => {
  const manifest = JSON.parse(fs.readFileSync(new URL('manifest.json', root)));
  assert.deepEqual(manifest.permissions.sort(), ['activeTab', 'nativeMessaging', 'scripting']);
  assert.equal(manifest.host_permissions, undefined); assert.equal(manifest.content_scripts, undefined);
  assert.equal(manifest.externally_connectable, undefined);
});
test('wire sanitizes URLs and unknown fields', () => assert.equal(cleanMessage(snapshot({ url: 'https://invalid.test' })).url, undefined));
test('wire rejects language, size, speed and malformed identity', () => {
  for (const value of [snapshot({ language: 'ja' }), snapshot({ speed: 3 }), snapshot({ cues: Array(33).fill(snapshot().cues[0]) }),
    snapshot({ cues: [{ startMs: 0, endMs: 1, text: 'x'.repeat(513) }] }), snapshot({ session: '../path' }), snapshot({ revision: true })]) {
    assert.throws(() => cleanMessage(value));
  }
});
test('close strips body and accepts bounded identity', () => assert.deepEqual(cleanMessage({ type: 'close', session: 'x', revision: 3, sequence: 2, cues: ['no'] }), { type: 'close', session: 'x', revision: 3, sequence: 2 }));

class Events {
  constructor() { this.handlers = new Map(); }
  addEventListener(type, fn) { if (!this.handlers.has(type)) this.handlers.set(type, new Set()); this.handlers.get(type).add(fn); }
  removeEventListener(type, fn) { this.handlers.get(type)?.delete(fn); }
  emit(type) { for (const fn of this.handlers.get(type) || []) fn(); }
}
function harness() {
  const track = Object.assign(new Events(), { language: 'vi', label: 'VI', mode: 'disabled', cues: [{ startTime: .5, endTime: 2.5, text: 'Synthetic test' }] });
  const tracks = Object.assign(new Events(), { 0: track, length: 1, [Symbol.iterator]: function* () { yield track; } });
  const video = Object.assign(new Events(), { textTracks: tracks, videoWidth: 1280, videoHeight: 720, paused: false,
    ended: false, seeking: false, playbackRate: 1, currentTime: 1, isConnected: true, volume: .8 });
  let listener, interval, pagehide, frames = new Map(), frameId = 0, now = 0, speaking = false;
  const messages = [], nodes = [];
  function element() { const n = { style: {}, remove() { this.removed = true; }, append() {}, attachShadow() { return { append() {} }; } }; nodes.push(n); return n; }
  const context = {
    document: { querySelectorAll: () => [video], createElement: element, documentElement: { append() {} } },
    chrome: { runtime: { id: 'extension-id', onMessage: { addListener: fn => { listener = fn; } }, sendMessage: async message => { messages.push(message.payload); return { ok: true, speaking }; } } },
    crypto: { randomUUID: () => 'unit-session' }, performance: { now: () => now },
    requestAnimationFrame: fn => { const id = ++frameId; frames.set(id, fn); return id; }, cancelAnimationFrame: id => frames.delete(id),
    setInterval: fn => { interval = fn; return 1; }, clearInterval: () => { interval = null; },
    setTimeout: () => 1,
    addEventListener: (event, fn) => { if (event === 'pagehide') pagehide = fn; }, console
  };
  vm.runInNewContext(fs.readFileSync(new URL('content.js', root), 'utf8'), context);
  const flush = async () => { await Promise.resolve(); await Promise.resolve(); };
  return { video, track, messages, nodes,
    async message(value, id = 'extension-id') { let result; listener(value, { id }, reply => result = reply); await flush(); return result; },
    async tick(ms = 250) { now += ms; interval?.(); await flush(); },
    async control(event) { video.emit(event); await flush(); },
    frames(ms = 400) { now += ms; const old = [...frames.values()]; frames.clear(); old.forEach(fn => fn(now)); },
    speaking(value) { speaking = value; }, pagehide: () => pagehide()
  };
}
test('inventory has no native send or track side effects', async () => {
  const h = harness(); const reply = await h.message({ action: 'inventory' });
  assert.equal(reply.videos.length, 1); assert.equal(h.messages.length, 0); assert.equal(h.track.mode, 'disabled');
});
test('explicit start sends bounded direct cues, no URL', async () => {
  const h = harness(); assert.equal((await h.message({ action: 'start', video: 0, track: 0 })).ok, true);
  assert.equal(h.track.mode, 'hidden'); assert.equal(h.messages[0].type, 'snapshot');
  assert.equal(h.messages[0].language, 'vi'); assert.equal(h.messages[0].cues.length, 1); assert.equal(h.messages[0].url, undefined);
});
test('array-like non-iterable browser cue list works and only future horizon crosses wire', async () => {
  const h = harness(); h.track.cues = { length: 3, 0: { startTime: 0, endTime: .5, text: 'expired' },
    1: { startTime: 2, endTime: 3, text: 'future' }, 2: { startTime: 8, endTime: 9, text: 'outside' } };
  await h.message({ action: 'start', video: 0, track: 0 });
  assert.equal(h.messages[0].cues.length, 1); assert.equal(h.messages[0].cues[0].text, 'future');
});
test('pause/seek/speed advance revision and sequence, paused heartbeat continues', async () => {
  const h = harness(); await h.message({ action: 'start', video: 0, track: 0 });
  h.video.paused = true; await h.control('pause'); assert.equal(h.messages.at(-1).revision, 1); assert.equal(h.messages.at(-1).playing, false);
  await h.tick(); assert.equal(h.messages.at(-1).type, 'status');
  await h.control('seeking'); h.video.playbackRate = 1.2; await h.control('ratechange');
  assert.equal(h.messages.at(-1).revision, 3);
  for (let i = 1; i < h.messages.length; i++) assert.ok(h.messages[i].sequence > h.messages[i - 1].sequence);
});
test('stop restores latest user volume and track mode, terminal close', async () => {
  const h = harness(); await h.message({ action: 'start', video: 0, track: 0 });
  h.speaking(true); await h.tick(); h.frames(); assert.ok(h.video.volume < .8);
  h.video.volume = .6; h.video.emit('volumechange');
  await h.message({ action: 'stop' }); assert.equal(h.video.volume, .6); assert.equal(h.track.mode, 'disabled'); assert.equal(h.messages.at(-1).type, 'close');
});
test('disconnect restores original baseline and cancels future polling', async () => {
  const h = harness(); await h.message({ action: 'start', video: 0, track: 0 });
  h.speaking(true); await h.tick(); h.frames();
  await h.message({ action: 'host-disconnected' }); assert.equal(h.video.volume, .8);
  const count = h.messages.length; await h.tick(); assert.equal(h.messages.length, count);
});
test('unsupported track and foreign extension cannot start', async () => {
  const h = harness(); h.track.language = 'ja'; assert.equal((await h.message({ action: 'start', video: 0, track: 0 })).error, 'UNSUPPORTED_LANGUAGE');
  h.track.language = 'vi'; assert.equal(await h.message({ action: 'start', video: 0, track: 0 }, 'other-extension'), undefined);
  assert.equal(h.messages.length, 0);
});
test('navigation and removed video close source', async () => {
  const h = harness(); await h.message({ action: 'start', video: 0, track: 0 }); h.pagehide(); assert.equal(h.messages.at(-1).type, 'close');
});
test('old disconnect cannot stop a newer source', async () => {
  const h = harness(); await h.message({ action: 'start', video: 0, track: 0 });
  await h.message({ action: 'host-disconnected', session: 'retired-session', error: 'STOPPED' });
  assert.equal(h.track.mode, 'hidden'); await h.tick(); assert.equal(h.messages.at(-1).type, 'status');
});

function backgroundHarness() {
  let listener, updated, removed, nativeMessage, nativeDisconnect, timeout;
  const posted = [], sent = [], ports=[]; let connections = 0;
  const makePort=()=>{const handlers={};const port={onMessage:{addListener:fn=>handlers.message=fn},onDisconnect:{addListener:fn=>handlers.disconnect=fn},postMessage:value=>posted.push(value),disconnect(){},handlers};ports.push(port);return port;};
  const chrome = { runtime: { id: 'extension-id', onMessage: { addListener: fn => listener = fn }, connectNative: () => { connections++; return makePort(); } },
    tabs: { sendMessage: async (id, value) => sent.push({ id, value }), onRemoved: { addListener: fn => removed = fn }, onUpdated: { addListener: fn => updated = fn } } };
  const source = fs.readFileSync(new URL('background.js', root), 'utf8').replace("import { cleanMessage } from './wire.mjs';", '');
  vm.runInNewContext(source, { chrome, cleanMessage, setTimeout: fn => { timeout = fn; return 1; }, clearTimeout() {}, console });
  return { posted, sent, connections: () => connections,
    send(payload, sender = { id: 'extension-id', frameId: 0, tab: { id: 4 }, url: 'https://invalid.test/video' }) {
      const result = { reply: null }; result.pending = listener({ action: 'wire', payload }, sender, reply => result.reply = reply); return result;
    }, reply: (value,index=ports.length-1) => ports[index].handlers.message(value), disconnect: (index=ports.length-1) => ports[index].handlers.disconnect(), timeout: () => timeout(), updated: (...args) => updated(...args), removed: id => removed(id) };
}
test('obsolete native port callbacks cannot affect reconnect',()=>{
 const h=backgroundHarness();h.send(snapshot());h.reply({ok:true});
 h.send({type:'close',session:'unit-session',revision:1,sequence:2});h.reply({ok:true});
 const next=h.send(snapshot({session:'new-session'}));assert.equal(h.connections(),2);
 h.disconnect(0);h.reply({ok:false,error:'OLD_PORT'},0);assert.equal(next.reply,null);
 h.reply({ok:true,speaking:true});assert.equal(next.reply.ok,true);assert.equal(next.reply.speaking,true);
});
test('background refuses non-mainframe and foreign extension before native connection', () => {
  const h = backgroundHarness();
  h.send(snapshot(), { id: 'other-id', frameId: 0, tab: { id: 4 }, url: 'https://invalid.test' });
  h.send(snapshot(), { id: 'extension-id', frameId: 1, tab: { id: 4 }, url: 'https://invalid.test' });
  assert.equal(h.connections(), 0);
});
test('background enforces source ownership and redacts host result', () => {
  const h = backgroundHarness(); const first = h.send(snapshot());
  assert.equal(h.posted.length, 1);
  const other = h.send(snapshot({ session: 'other-source' })); assert.equal(other.reply.error, 'SOURCE_BUSY');
  h.reply({ ok: true, speaking: true, translated: 'x', error: 'https://secret.invalid' });
  assert.equal(first.reply.error, ''); assert.equal(first.reply.speaking, true);
  const duplicate = h.send(snapshot()); assert.equal(duplicate.reply.error, 'STALE_MESSAGE');
});
test('close waits behind one request then acknowledges and disconnects', () => {
  const h = backgroundHarness(); h.send(snapshot());
  const closing = h.send({ type: 'close', session: 'unit-session', revision: 1, sequence: 2 });
  assert.equal(h.posted.length, 1); assert.equal(closing.pending, true);
  h.reply({ ok: true }); assert.equal(h.posted.length, 2); assert.equal(h.posted[1].type, 'close');
  h.reply({ ok: true }); assert.equal(closing.reply.ok, true); assert.equal(h.sent[0].value.action, 'host-disconnected');
});
test('timeout and tab navigation restore source through disconnect notification', () => {
  const h = backgroundHarness(); const request = h.send(snapshot()); h.timeout();
  assert.equal(request.reply.error, 'TIMEOUT'); assert.equal(h.sent[0].id, 4);
  const n = backgroundHarness(); n.send(snapshot()); n.reply({ ok: true }); n.updated(4, { status: 'loading' });
  assert.equal(n.sent[0].value.error, 'DISCONNECTED');
});
