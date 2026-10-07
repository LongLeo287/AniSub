import { LIMITS, ProtocolError, validateMessage, negotiate } from '../../protocol/src/index.mjs';

// Single-process fixture coordinator. Real engines/output ports are later milestones.
export class AniSubRuntime {
  constructor({ scheduler, translator, tts, onEvent = () => {} }) {
    this.scheduler = scheduler; this.translator = translator; this.tts = tts;
    this.onEvent = onEvent; this.session = null;
    this.jobs = new Set(); this.waiting = []; this.active = null; this.wake = null;
    this.seenSessions = new Set(); this.nextSpeech = 1;
    this.events = []; this.delivering = false;
    this.stats = { accepted: 0, rejected: 0, stale: 0, expired: 0,
      backpressure: 0, providerFailures: 0 };
  }
  handshake(version) { return negotiate(version); }
  emit(type, data = {}, revision = this.session?.revision) {
    const session = this.session;
    if (!session) return;
    this.events.push(Object.freeze({ type, sessionId: session.id, revision,
      seq: ++session.outSeq, ...data }));
  }
  // Deliver callbacks after complete mutations, permitting safe reentrant STOP/close.
  flushEvents() {
    if (this.delivering) return;
    this.delivering = true;
    try {
      while (this.events.length) {
        const event = this.events.shift();
        try { this.onEvent(event); } catch { /* Client exceptions cannot prevent cleanup. */ }
      }
    } finally { this.delivering = false; }
  }
  error(code) {
    if (code === 'BACKPRESSURE') this.stats.backpressure++;
    this.emit('ERROR', { code, recoverable: true });
  }
  handle(input) {
    let response;
    try {
      this.admit(validateMessage(input)); this.stats.accepted++;
      response = { accepted: true };
    } catch (error) {
      this.stats.rejected++;
      response = { accepted: false, code: error instanceof ProtocolError ? error.code : 'UNSUPPORTED' };
    }
    this.flushEvents();
    if (response.accepted) { this.pump(); this.flushEvents(); }
    return response;
  }
  admit(message) {
    if (message.type === 'OPEN') {
      if (this.session) throw new ProtocolError('UNSUPPORTED', 'Session already open');
      // Bound replay-history memory; recreate the fixture after 1024 sessions.
      if (this.seenSessions.has(message.sessionId) || this.seenSessions.size >= 1024)
        throw new ProtocolError('UNSUPPORTED', 'Session id reused or fixture lifetime limit');
      this.seenSessions.add(message.sessionId);
      this.session = { id: message.sessionId, revision: message.revision, inSeq: message.seq,
        outSeq: 0, descriptor: message.descriptor, trackEnabled:
          message.descriptor.selectedTextTrackRef !== null,
        anchor: { position: message.clock.positionMediaMs, at: this.scheduler.now(),
          playing: message.clock.playing, speed: message.clock.speed },
        cues: new Map(), dedup: new Set() };
      return;
    }
    const session = this.session;
    if (!session || session.id !== message.sessionId) {
      if (message.type === 'CLOSE' && !session && this.seenSessions.has(message.sessionId)) return;
      throw new ProtocolError('DISCONNECTED', 'Session mismatch');
    }
    if (message.seq === session.inSeq) return;
    if (message.seq < session.inSeq) throw new ProtocolError('UNSUPPORTED', 'Out of order');
    const retiring = message.type === 'INPUT_CHANGED' ||
      (message.type === 'PLAYBACK' && message.event !== 'PLAY');
    if (retiring ? message.revision <= session.revision : message.revision !== session.revision)
      throw new ProtocolError('UNSUPPORTED', 'Revision mismatch');
    const enabledAfterChange = message.type === 'INPUT_CHANGED' &&
      Object.hasOwn(message, 'trackRef') ? message.trackRef !== null : session.trackEnabled;
    if (['SNAPSHOT', 'INPUT_CHANGED'].includes(message.type) &&
        !enabledAfterChange && message.cues.length)
      throw new ProtocolError('UNSUPPORTED', 'Text track disabled');
    // Mutation begins only after every admission check passes.
    session.inSeq = message.seq;
    if (message.type === 'CLOSE') { this.close(); return; }
    if (message.type === 'PLAYBACK') {
      if (['SOURCE_CHANGE', 'EPISODE_CHANGE'].includes(message.event)) {
        this.close(message.revision); return;
      }
      const playing = message.event === 'PLAY' ? true :
        ['PAUSE', 'STOP'].includes(message.event) ? false : session.anchor.playing;
      if (retiring) { this.invalidate(); session.revision = message.revision; }
      session.anchor = { position: message.positionMediaMs, at: this.scheduler.now(),
        playing, speed: message.speed ?? session.anchor.speed };
      this.pump(); return;
    }
    if (message.type === 'INPUT_CHANGED') {
      this.invalidate(); session.revision = message.revision;
      if (Object.hasOwn(message, 'trackRef')) {
        session.trackEnabled = message.trackRef !== null;
        session.descriptor = Object.freeze({ ...session.descriptor,
          selectedTextTrackRef: message.trackRef });
      }
    }
    this.snapshot(message.cues);
  }
  mediaNow() {
    const anchor = this.session.anchor;
    return anchor.position + (anchor.playing ?
      (this.scheduler.now() - anchor.at) * anchor.speed : 0);
  }
  current(job) {
    return this.session?.id === job.sessionId && this.session.revision === job.revision &&
      this.session.cues.get(job.cue.cueId) === job.cue;
  }
  expired(cue) { return cue.endMediaMs != null && cue.endMediaMs <= this.mediaNow(); }
  snapshot(cues) {
    const session = this.session;
    const previous = session.cues;
    const next = new Map();
    for (const cue of cues) {
      const old = previous.get(cue.cueId);
      next.set(cue.cueId, old && JSON.stringify(old) === JSON.stringify(cue) ? old : cue);
    }
    session.cues = next;
    for (const job of this.jobs) if (!this.current(job)) job.controller.abort();
    this.waiting = this.waiting.filter(job => this.current(job));
    if (this.active && !this.current(this.active)) this.finish('CANCELLED');
    if (this.wake !== null) { this.scheduler.cancel(this.wake); this.wake = null; }
    for (const key of session.dedup)
      if (!next.has(key) || previous.get(key) !== next.get(key)) session.dedup.delete(key);
    for (const cue of next.values()) {
      if (session.dedup.has(cue.cueId)) continue;
      if (this.expired(cue)) { session.dedup.add(cue.cueId); this.stats.expired++; continue; }
      if (this.jobs.size >= LIMITS.maxJobs) { this.error('BACKPRESSURE'); continue; }
      session.dedup.add(cue.cueId);
      const job = { sessionId: session.id, revision: session.revision, cue,
        controller: new AbortController() };
      this.jobs.add(job); void this.process(job);
    }
    this.pump();
  }
  async process(job) {
    try {
      // Admission finishes before callbacks or even zero-latency providers can reenter it.
      await Promise.resolve();
      if (!this.current(job)) { this.stats.stale++; return; }
      const session = this.session;
      let text = job.cue.originalText;
      if (session.descriptor.translation) {
        const result = await this.translator.process(job.cue, { signal: job.controller.signal });
        if (typeof result.text !== 'string' || result.text.length > LIMITS.maxTextUnits)
          throw new Error('Invalid translation');
        text = result.text;
      }
      if (!this.current(job)) { this.stats.stale++; return; }
      if (this.expired(job.cue)) { this.stats.expired++; return; }
      this.emit('CUE', { cue: Object.freeze({ ...job.cue,
        ...(session.descriptor.translation ? { derivedText: text } : {}) }) });
      this.flushEvents();
      // A callback may have invalidated the session while consuming the derived cue.
      if (!this.current(job) || !session.descriptor.speech || !session.anchor.playing) return;
      const audio = await this.tts.process(text, { signal: job.controller.signal });
      if (!this.current(job)) { this.stats.stale++; return; }
      if (this.expired(job.cue)) { this.stats.expired++; return; }
      if (!Number.isFinite(audio.durationMs) || audio.durationMs <= 0 ||
          audio.durationMs > LIMITS.maxHorizonMs || audio.virtual !== true)
        throw new Error('Invalid fake speech');
      const speed = session.anchor.speed;
      const now = this.mediaNow();
      const future = Math.max(0, (job.cue.startMediaMs ?? now) - now);
      const queuedMedia = this.waiting.reduce((sum, waiting) =>
        sum + waiting.audio.durationMs * speed, 0);
      const activeMedia = this.active ?
        Math.max(0, this.active.outputEndAt - this.scheduler.now()) * speed : 0;
      const projectedHorizon = Math.max(future, activeMedia + queuedMedia) + audio.durationMs * speed;
      if (projectedHorizon >
          LIMITS.maxHorizonMs || this.waiting.length >= LIMITS.maxWaitingSpeech) {
        this.error('BACKPRESSURE'); return;
      }
      job.audio = audio; this.waiting.push(job); this.pump();
    } catch {
      if (this.current(job) && !job.controller.signal.aborted) {
        this.stats.providerFailures++; this.error('PROVIDER_FAILED');
      }
    } finally { this.jobs.delete(job); this.flushEvents(); }
  }
  pump() {
    if (!this.session || !this.session.anchor.playing || this.active ||
        this.events.some(event => event.type === 'SPEECH' && event.event === 'FINISHED')) return;
    this.waiting = this.waiting.filter(job => {
      if (!this.current(job)) { this.stats.stale++; return false; }
      if (this.expired(job.cue)) { this.stats.expired++; return false; }
      return true;
    });
    const now = this.mediaNow();
    this.waiting.sort((a, b) => (a.cue.startMediaMs ?? now) - (b.cue.startMediaMs ?? now));
    const job = this.waiting[0];
    if (!job) return;
    const delay = job.cue.startMediaMs == null ? 0 :
      (job.cue.startMediaMs - now) / this.session.anchor.speed;
    if (delay > 0) {
      if (this.wake !== null) this.scheduler.cancel(this.wake);
      this.wake = this.scheduler.schedule(delay, () => {
        this.wake = null; this.pump(); this.flushEvents();
      });
      return;
    }
    this.waiting.shift(); job.speechId = 'speech-' + this.nextSpeech++; this.active = job;
    const remaining = job.cue.endMediaMs == null ? Infinity :
      (job.cue.endMediaMs - now) / this.session.anchor.speed;
    job.outputEndAt = this.scheduler.now() + Math.min(job.audio.durationMs, remaining);
    job.timer = this.scheduler.schedule(Math.min(job.audio.durationMs, remaining), () => {
      if (this.active === job) this.finish(remaining < job.audio.durationMs ? 'CANCELLED' : 'COMPLETED');
      this.flushEvents();
      this.pump(); this.flushEvents();
    });
    this.emit('SPEECH', { event: 'STARTED', speechId: job.speechId, virtual: true }, job.revision);
  }
  finish(reason) {
    const job = this.active;
    if (!job) return;
    this.active = null; this.scheduler.cancel(job.timer);
    this.emit('SPEECH', { event: 'FINISHED', speechId: job.speechId,
      finishReason: reason, virtual: true }, job.revision);
  }
  invalidate() {
    if (this.wake !== null) { this.scheduler.cancel(this.wake); this.wake = null; }
    this.waiting = []; this.session.cues.clear(); this.session.dedup.clear();
    for (const job of this.jobs) job.controller.abort();
    this.finish('CANCELLED');
  }
  close(revision = this.session?.revision) {
    if (!this.session) return;
    this.invalidate(); this.session.revision = revision;
    this.emit('CLOSED'); this.session = null;
  }
  disconnect() { this.close(); this.flushEvents(); }
  diagnostics() {
    return Object.freeze({ ...this.stats, outstandingJobs: this.jobs.size,
      waitingSpeech: this.waiting.length, activeSpeech: this.active ? 1 : 0,
      activeCues: this.session?.cues.size ?? 0, revision: this.session?.revision ?? null,
      sessionOpen: !!this.session });
  }
}
