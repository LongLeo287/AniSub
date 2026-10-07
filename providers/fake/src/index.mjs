// Deterministic fixture clock; elapsed virtual time is not a performance measurement.
export class VirtualScheduler {
  constructor() { this.time = 0; this.next = 1; this.tasks = new Map(); }
  now() { return this.time; }
  schedule(delay, callback) {
    if (!Number.isFinite(delay) || delay < 0) throw new Error('Invalid delay');
    const id = this.next++;
    this.tasks.set(id, {at: this.time + delay, callback});
    return id;
  }
  cancel(id) { this.tasks.delete(id); }
  async advance(ms) {
    if (!Number.isFinite(ms) || ms < 0) throw new Error('Invalid advance');
    const target = this.time + ms;
    for (let steps = 0; steps < 100000; steps++) {
      await this.flush();
      const next = [...this.tasks].filter(([, task]) => task.at <= target)
        .sort((a, b) => a[1].at - b[1].at || a[0] - b[0])[0];
      if (!next) { this.time = target; await this.flush(); return; }
      this.time = next[1].at;
      this.tasks.delete(next[0]);
      next[1].callback();
    }
    throw new Error('Scheduler runaway');
  }
  // Sufficient for this finite fake pipeline, not an arbitrary Promise-draining API.
  async flush() { for (let i = 0; i < 12; i++) await Promise.resolve(); }
  pending() { return this.tasks.size; }
}
class FakeProvider {
  constructor({scheduler, latencyMs = 20, fail = false, ignoreCancel = false} = {}) {
    this.scheduler = scheduler; this.latencyMs = latencyMs;
    this.fail = fail; this.ignoreCancel = ignoreCancel;
  }
  run(value, signal, transform) {
    return new Promise((resolve, reject) => {
      let done = false;
      const finish = (callback, result) => {
        if (done) return;
        done = true;
        signal?.removeEventListener('abort', abort);
        callback(result);
      };
      const timer = this.scheduler.schedule(this.latencyMs, () => {
        try {
          if (this.fail) throw new Error('Synthetic provider failure');
          finish(resolve, transform(value));
        } catch (error) { finish(reject, error); }
      });
      const abort = () => {
        if (this.ignoreCancel) return;
        this.scheduler.cancel(timer);
        finish(reject, new Error('Cancelled'));
      };
      signal?.addEventListener('abort', abort, {once: true});
      if (signal?.aborted) abort();
    });
  }
}
export class FakeTranslator extends FakeProvider {
  process(cue, {signal} = {}) {
    return this.run(cue, signal, current => ({text: '[fake] ' + current.originalText}));
  }
}
export class FakeTts extends FakeProvider {
  constructor(options = {}) { super(options); this.durationMs = options.durationMs ?? 300; }
  process(text, {signal} = {}) {
    return this.run(text, signal, () => ({durationMs: this.durationMs, virtual: true}));
  }
}
