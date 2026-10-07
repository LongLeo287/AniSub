export const MAX_BYTES = 65536;
export function cleanMessage(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw Error('INVALID_MESSAGE');
  const { type, session, revision, sequence } = value;
  if (!['snapshot', 'status', 'close'].includes(type) || !/^[a-zA-Z0-9_-]{1,80}$/.test(session ?? '') ||
      !Number.isSafeInteger(revision) || revision < 0 || !Number.isSafeInteger(sequence) || sequence < 1) throw Error('INVALID_MESSAGE');
  const result = { type, session, revision, sequence };
  if (type === 'snapshot') {
    if (!Number.isFinite(value.positionMs) || value.positionMs < 0 || typeof value.playing !== 'boolean' ||
        !Number.isFinite(value.speed) || value.speed < .5 || value.speed > 2 || !['vi', 'en'].includes(value.language) ||
        !Array.isArray(value.cues) || value.cues.length > 32) throw Error('UNSUPPORTED_INPUT');
    Object.assign(result, { positionMs: value.positionMs, playing: value.playing, speed: value.speed, language: value.language,
      cues: value.cues.map(c => {
        if (!c || typeof c.text !== 'string' || c.text.length > 512 || !Number.isFinite(c.startMs) || !Number.isFinite(c.endMs) ||
            c.startMs < 0 || c.endMs <= c.startMs) throw Error('INVALID_CUE');
        return { startMs: c.startMs, endMs: c.endMs, text: c.text };
      }) });
  }
  if (new TextEncoder().encode(JSON.stringify(result)).length > MAX_BYTES) throw Error('MESSAGE_TOO_LARGE');
  return result;
}
