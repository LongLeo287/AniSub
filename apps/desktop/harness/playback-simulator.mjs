// A cooperating fake client, not a video player or AniBox integration.
export class PlaybackSimulator {
  constructor(runtime, scheduler) {
    this.runtime = runtime;
    this.scheduler = scheduler;
    this.sessionId = null;
    this.revision = 0;
    this.sequence = 0;
  }

  open(sessionId, { positionMediaMs = 0, playing = true, speed = 1,
    translation = true, speech = true } = {}) {
    this.sessionId = sessionId;
    this.revision = 0;
    this.sequence = 0;
    return this.send('OPEN', {
      descriptor: {
        episodeRef: 'synthetic-episode', sourceRef: 'synthetic-source',
        selectedTextTrackRef: 'fixture-en', originalLanguage: 'en', targetLanguage: 'vi',
        inputMode: 'DIRECT', translation, speech,
      },
      clock: { positionMediaMs, playing, speed, clientMonotonicMs: this.scheduler.now() },
    });
  }

  send(type, fields = {}) {
    if (!this.sessionId) throw new Error('Open a session before sending commands');
    return this.runtime.handle({ type, sessionId: this.sessionId,
      revision: this.revision, seq: ++this.sequence, ...fields });
  }

  snapshot(cues) { return this.send('SNAPSHOT', { cues }); }

  playback(event, positionMediaMs, speed) {
    if (['PAUSE', 'SEEK', 'STOP', 'PLAYBACK_SPEED', 'SOURCE_CHANGE', 'EPISODE_CHANGE']
      .includes(event)) this.revision++;
    return this.send('PLAYBACK', {
      event, positionMediaMs, ...(speed === undefined ? {} : { speed }),
      clientMonotonicMs: this.scheduler.now(),
    });
  }

  inputChanged(trackRef, cues = []) {
    this.revision++;
    return this.send('INPUT_CHANGED', { trackRef, cues });
  }

  close() { return this.send('CLOSE'); }
  async advance(milliseconds) { await this.scheduler.advance(milliseconds); }
}
