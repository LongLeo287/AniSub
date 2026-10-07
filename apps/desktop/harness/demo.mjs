import assert from 'node:assert/strict';
import { AniSubRuntime } from '../../../core/src/runtime.mjs';
import { VirtualScheduler, FakeTranslator, FakeTts } from '../../../providers/fake/src/index.mjs';
import { PlaybackSimulator } from './playback-simulator.mjs';

const scheduler = new VirtualScheduler();
// Text/audio are synthetic. Only redacted counters are emitted by this harness.
const events = [];
const runtime = new AniSubRuntime({ scheduler,
  translator: new FakeTranslator({ scheduler, latencyMs: 30, ignoreCancel: true }),
  tts: new FakeTts({ scheduler, latencyMs: 20, durationMs: 300, ignoreCancel: true }),
  onEvent: event => events.push(event),
});
const client = new PlaybackSimulator(runtime, scheduler);
const cue = (cueId, start, end) => ({ cueId, originalText: 'Synthetic dialogue.',
  language: 'en', provenance: 'synthetic-fixture', origin: 'DIRECT',
  observedAtMediaMs: start, startMediaMs: start, endMediaMs: end });
const requireAccepted = response => assert.equal(response.accepted, true);

runtime.handshake({ major: 1, minor: 0 });
requireAccepted(client.open('demo-1'));
requireAccepted(client.snapshot([cue('first', 0, 2000)]));
await client.advance(60);
// Retire output and start a fresh clock anchor. Any old provider work must not revive it.
requireAccepted(client.playback('SEEK', 5000));
requireAccepted(client.snapshot([cue('second', 5000, 7000)]));
await client.advance(60);
requireAccepted(client.playback('PAUSE', 5060));
await client.advance(400);
requireAccepted(client.playback('PLAY', 5060));
requireAccepted(client.snapshot([cue('third', 5060, 7000)]));
await client.advance(400);
requireAccepted(client.close());

const diagnostics = runtime.diagnostics();
for (const field of ['outstandingJobs', 'waitingSpeech', 'activeSpeech', 'activeCues'])
  assert.equal(diagnostics[field], 0, `${field} must be cleared after close`);
assert.equal(diagnostics.sessionOpen, false);
const started = events.filter(event => event.type === 'SPEECH' && event.event === 'STARTED');
const finished = events.filter(event => event.type === 'SPEECH' && event.event === 'FINISHED');
assert.equal(started.length, 3);
assert.equal(finished.length, started.length);
assert.deepEqual(new Set(started.map(event => event.speechId)),
  new Set(finished.map(event => event.speechId)));
console.log(JSON.stringify({
  harness: 'AniSub no-model Windows playback simulation',
  audio: 'virtual lifecycle events only; no audible speech',
  elapsedVirtualMs: scheduler.now(), events: events.length,
  diagnostics,
}, null, 2));
