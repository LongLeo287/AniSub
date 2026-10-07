import test from 'node:test';
import assert from 'node:assert/strict';
import { AniSubRuntime } from '../../core/src/runtime.mjs';
import { VirtualScheduler, FakeTranslator, FakeTts } from '../../providers/fake/src/index.mjs';
import { PlaybackSimulator } from '../../apps/desktop/harness/playback-simulator.mjs';

const cue = (cueId = 'c', fields = {}) => ({ cueId, originalText: 'Fixture only',
  origin: 'DIRECT', observedAtMediaMs: 0, ...fields });
function fixture(options = {}) {
  const scheduler = new VirtualScheduler();
  const events = [];
  const runtime = new AniSubRuntime({ scheduler,
    translator: new FakeTranslator({ scheduler, ...options.translation }),
    tts: new FakeTts({ scheduler, ...options.tts }),
    onEvent: event => { events.push(event); options.onEvent?.(event, runtime); },
  });
  let sequence = 0;
  const send = (type, revision = 0, fields = {}) => runtime.handle({ type,
    sessionId: 'session', seq: ++sequence, revision, ...fields });
  send('OPEN', 0, { descriptor: { inputMode: 'DIRECT', translation: true, speech: true,
    selectedTextTrackRef: 'text-en' }, clock: { positionMediaMs: 0, playing: true, speed: 1 } });
  return { runtime, scheduler, events, send };
}
const speech = (fixture, event) => fixture.events.filter(item =>
  item.type === 'SPEECH' && item.event === event);

test('playback simulator forwards source/episode changes with fresh sessions',()=>{
  const x=fixture(); x.runtime.disconnect();
  const client=new PlaybackSimulator(x.runtime,x.scheduler);
  assert.equal(client.open('first').accepted,true);
  assert.equal(client.playback('SOURCE_CHANGE',0).accepted,true);
  assert.equal(x.runtime.diagnostics().sessionOpen,false);
  assert.equal(client.open('second').accepted,true);
  assert.equal(client.playback('EPISODE_CHANGE',0).accepted,true);
  assert.equal(client.open('third').accepted,true);
  assert.equal(client.close().accepted,true);
});

test('same-revision close is idempotent and releases pending work', async () => {
  const x = fixture();
  x.send('SNAPSHOT', 0, { cues: [cue()] });
  await x.scheduler.advance(0);
  assert.equal(x.send('CLOSE').accepted, true);
  assert.equal(x.send('CLOSE').accepted, true);
  await x.scheduler.advance(1000);
  assert.equal(x.events.filter(item => item.type === 'CLOSED').length, 1);
  assert.equal(x.runtime.diagnostics().outstandingJobs, 0);
  assert.equal(x.scheduler.pending(), 0);
});

test('STARTED callback can immediately STOP without orphan timers or duplicate terminals', async () => {
  const x = fixture({ onEvent(event, runtime) {
    if (event.type === 'SPEECH' && event.event === 'STARTED') {
      assert.equal(runtime.handle({ type: 'PLAYBACK', event: 'STOP', sessionId: 'session',
        seq: 3, revision: 1, positionMediaMs: 40 }).accepted, true);
    }
  } });
  x.send('SNAPSHOT', 0, { cues: [cue()] });
  await x.scheduler.advance(1000);
  assert.equal(speech(x, 'STARTED').length, 1);
  assert.equal(speech(x, 'FINISHED').length, 1);
  assert.equal(speech(x, 'FINISHED')[0].finishReason, 'CANCELLED');
  assert.equal(x.scheduler.pending(), 0);
});

test('CUE callback close prevents TTS from being submitted', async () => {
  const x = fixture({ onEvent(event, runtime) {
    if (event.type === 'CUE') runtime.disconnect();
  } });
  x.send('SNAPSHOT', 0, { cues: [cue()] });
  await x.scheduler.advance(1000);
  assert.equal(speech(x, 'STARTED').length, 0);
  assert.equal(x.runtime.diagnostics().outstandingJobs, 0);
  assert.equal(x.scheduler.pending(), 0);
});

test('FINISHED callback stop suppresses the next queued utterance', async () => {
  const x = fixture({ onEvent(event, runtime) {
    if (event.type === 'SPEECH' && event.event === 'FINISHED')
      runtime.handle({ type: 'PLAYBACK', event: 'STOP', sessionId: 'session',
        seq: 3, revision: 1, positionMediaMs: 340 });
  } });
  x.send('SNAPSHOT', 0, { cues: [cue('one'), cue('two')] });
  await x.scheduler.advance(1000);
  assert.equal(speech(x, 'STARTED').length, 1);
  assert.equal(speech(x, 'FINISHED').length, 1);
  assert.equal(x.runtime.diagnostics().waitingSpeech, 0);
});

test('snapshot replacement terminal callback STOP cannot expose a queued stale STARTED', async () => {
  let shouldStop=false;
  const x=fixture({onEvent(event,runtime){
    if(shouldStop && event.type==='SPEECH' && event.event==='FINISHED')
      runtime.handle({type:'PLAYBACK',event:'STOP',sessionId:'session',seq:4,
        revision:1,positionMediaMs:40});
  }});
  x.send('SNAPSHOT',0,{cues:[cue('one'),cue('two')]});
  await x.scheduler.advance(40);
  shouldStop=true;
  x.send('SNAPSHOT',0,{cues:[cue('two')]});
  await x.scheduler.advance(1000);
  assert.equal(speech(x,'STARTED').length,1);
  assert.equal(speech(x,'FINISHED').length,1);
  assert.equal(x.scheduler.pending(),0);
});

test('track disabling rejects new cues without changing revision or sequence', async () => {
  const x = fixture();
  assert.equal(x.send('INPUT_CHANGED', 1, { trackRef: null, cues: [] }).accepted, true);
  assert.equal(x.send('SNAPSHOT', 1, { cues: [cue()] }).accepted, false);
  assert.equal(x.send('INPUT_CHANGED', 2, { cues: [cue()] }).accepted, false);
  assert.equal(x.runtime.diagnostics().revision, 1);
  assert.equal(x.send('INPUT_CHANGED', 2, { trackRef: 'text-vi', cues: [cue()] }).accepted, true);
  await x.scheduler.advance(1000);
  assert.equal(speech(x, 'STARTED').length, 1);
});

test('active audio plus waiting audio cannot exceed five-second media horizon', async () => {
  const x = fixture({ tts: { durationMs: 3000 } });
  x.send('SNAPSHOT', 0, { cues: [cue('one'), cue('two')] });
  await x.scheduler.advance(40);
  assert.equal(x.runtime.diagnostics().waitingSpeech, 0);
  assert.equal(x.runtime.diagnostics().backpressure, 1);
  await x.scheduler.advance(4000);
  assert.equal(speech(x, 'STARTED').length, 1);
});

test('future start plus output duration is bounded, not just future start', async () => {
  const x = fixture({ tts: { durationMs: 2000 } });
  x.send('SNAPSHOT', 0, { cues: [cue('future', { startMediaMs: 4000 })] });
  await x.scheduler.advance(40);
  assert.equal(x.runtime.diagnostics().waitingSpeech, 0);
  assert.equal(x.runtime.diagnostics().backpressure, 1);
});

test('real in-flight uncooperative translation cannot revive a retired revision', async () => {
  const x = fixture({ translation: { latencyMs: 100, ignoreCancel: true } });
  x.send('SNAPSHOT', 0, { cues: [cue()] });
  await x.scheduler.advance(1);
  assert.equal(x.scheduler.pending(), 1);
  x.send('PLAYBACK', 1, { event: 'SEEK', positionMediaMs: 5000 });
  await x.scheduler.advance(200);
  assert.equal(x.events.length, 0);
  assert.equal(x.runtime.diagnostics().stale, 1);
  assert.equal(x.runtime.diagnostics().outstandingJobs, 0);
});

test('known cue boundary cancels active speech exactly once', async () => {
  const x = fixture({ tts: { durationMs: 300 } });
  x.send('SNAPSHOT', 0, { cues: [cue('short', { endMediaMs: 100 })] });
  await x.scheduler.advance(1000);
  assert.equal(speech(x, 'STARTED').length, 1);
  assert.equal(speech(x, 'FINISHED').length, 1);
  assert.equal(speech(x, 'FINISHED')[0].finishReason, 'CANCELLED');
});

test('cooperative queue pressure can retry the unchanged snapshot after capacity frees', async () => {
  const x = fixture({ translation: { ignoreCancel: true, latencyMs: 100 } });
  x.send('SNAPSHOT', 0, { cues: Array.from({ length: 8 }, (_, i) => cue(`old-${i}`)) });
  await x.scheduler.advance(1);
  x.send('PLAYBACK', 1, { event: 'SEEK', positionMediaMs: 5000 });
  const fresh = [cue('fresh')];
  x.send('SNAPSHOT', 1, { cues: fresh });
  assert.equal(x.runtime.diagnostics().outstandingJobs, 8);
  await x.scheduler.advance(100);
  x.send('SNAPSHOT', 1, { cues: fresh });
  await x.scheduler.advance(500);
  assert.equal(speech(x, 'STARTED').length, 1);
  assert.equal(speech(x, 'STARTED')[0].revision, 1);
});
