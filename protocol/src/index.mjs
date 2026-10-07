export const LIMITS = Object.freeze({
  maxCues: 32, maxTextUnits: 4096, maxMessageBytes: 262144,
  maxJobs: 8, maxWaitingSpeech: 2, maxHorizonMs: 5000,
});
export class ProtocolError extends Error {
  constructor(code, message) { super(message); this.name = 'ProtocolError'; this.code = code; }
}
const fail = (message, code = 'UNSUPPORTED') => { throw new ProtocolError(code, message); };
const finite = value => typeof value === 'number' && Number.isFinite(value) && value >= 0;
const id = value => typeof value === 'string' && value.length > 0 && value.length <= 128;
function deepFreeze(value) {
  if (value && typeof value === 'object') {
    for (const child of Object.values(value)) deepFreeze(child);
    Object.freeze(value);
  }
  return value;
}
// Reject lossy JSON coercion (NaN -> null, Date -> string), cycles and deep payloads.
function checkJson(value, depth = 0, ancestors = new Set()) {
  if (depth > 16) fail('Nested payload limit');
  if (value === null || ['string', 'boolean'].includes(typeof value)) return;
  if (typeof value === 'number') { if (!Number.isFinite(value)) fail('Nonfinite number'); return; }
  if (typeof value !== 'object' || ancestors.has(value)) fail('Invalid JSON value');
  if (!Array.isArray(value) && Object.getPrototypeOf(value) !== Object.prototype &&
      Object.getPrototypeOf(value) !== null) fail('Only plain JSON objects supported');
  ancestors.add(value);
  for (const child of Object.values(value)) checkJson(child, depth + 1, ancestors);
  ancestors.delete(value);
}
function optionalId(object, field, nullable = false) {
  if (object[field] !== undefined && !(nullable && object[field] === null) && !id(object[field]))
    fail('Invalid ' + field);
}
function refuseSecrets(object) {
  for (const field of ['url', 'sourceUrl', 'headers', 'credentials'])
    if (Object.hasOwn(object, field)) fail('Sensitive transport field not supported');
}
export function negotiate(version) {
  if (!version || version.major !== 1) fail('Incompatible protocol major', 'PROTOCOL_MISMATCH');
  if (version.minor !== undefined && (!Number.isSafeInteger(version.minor) || version.minor < 0))
    fail('Invalid minor');
  return deepFreeze({major: 1, minor: 0, inputModes: ['DIRECT'], translation: true,
    speech: true, virtualSpeech: true, limits: LIMITS});
}
function cues(list) {
  if (!Array.isArray(list) || list.length > LIMITS.maxCues) fail('Invalid cue count');
  const ids = new Set();
  for (const cue of list) {
    if (!cue || !id(cue.cueId) || ids.has(cue.cueId)) fail('Invalid or duplicate cue id');
    ids.add(cue.cueId);
    if (typeof cue.originalText !== 'string' || cue.originalText.length > LIMITS.maxTextUnits)
      fail('Invalid cue text');
    if (cue.origin !== 'DIRECT') fail('Input origin unsupported');
    if (!finite(cue.observedAtMediaMs)) fail('Invalid observation');
    optionalId(cue, 'language'); optionalId(cue, 'provenance'); refuseSecrets(cue);
    for (const field of ['startMediaMs', 'endMediaMs'])
      if (cue[field] != null && !finite(cue[field])) fail('Invalid cue bound');
    if (cue.startMediaMs != null && cue.endMediaMs != null && cue.endMediaMs < cue.startMediaMs)
      fail('Reversed bounds');
    if (cue.confidence !== undefined && (!finite(cue.confidence) || cue.confidence > 1))
      fail('Invalid confidence');
    if (cue.derivedText !== undefined) fail('Client derived text not supported');
  }
}
export function validateMessage(input) {
  checkJson(input);
  let json;
  try { json = JSON.stringify(input); } catch { fail('Not serializable'); }
  if (typeof json !== 'string' || Buffer.byteLength(json, 'utf8') > LIMITS.maxMessageBytes)
    fail('Message byte bound');
  const message = JSON.parse(json);
  if (!message || !id(message.sessionId) || !Number.isSafeInteger(message.revision) ||
      message.revision < 0 || !Number.isSafeInteger(message.seq) || message.seq < 1)
    fail('Invalid envelope');
  if (!['OPEN', 'SNAPSHOT', 'PLAYBACK', 'INPUT_CHANGED', 'CLOSE'].includes(message.type))
    fail('Unknown message');
  if (message.type === 'OPEN') {
    if (message.seq !== 1) fail('OPEN sequence starts at 1');
    const descriptor = message.descriptor;
    if (!descriptor || descriptor.inputMode !== 'DIRECT') fail('Only DIRECT input supported');
    for (const field of ['translation', 'speech'])
      if (descriptor[field] !== undefined && typeof descriptor[field] !== 'boolean')
        fail('Invalid setting');
    for (const field of ['episodeRef', 'sourceRef', 'originalLanguage', 'targetLanguage'])
      optionalId(descriptor, field);
    optionalId(descriptor, 'selectedTextTrackRef', true); refuseSecrets(descriptor);
    if (!message.clock || !finite(message.clock.positionMediaMs) ||
        typeof message.clock.playing !== 'boolean' || !finite(message.clock.speed) ||
        message.clock.speed === 0) fail('Invalid clock');
  }
  if (message.type === 'SNAPSHOT' || message.type === 'INPUT_CHANGED') cues(message.cues);
  if (message.type === 'INPUT_CHANGED') {
    if (message.inputMode !== undefined && message.inputMode !== 'DIRECT')
      fail('Only DIRECT input supported');
    optionalId(message, 'trackRef', true);
    if (message.trackRef === null && message.cues.length) fail('Disabled track has no cues');
  }
  if (message.type === 'PLAYBACK') {
    if (!['PLAY', 'PAUSE', 'SEEK', 'STOP', 'EPISODE_CHANGE', 'SOURCE_CHANGE',
      'PLAYBACK_SPEED'].includes(message.event)) fail('Unknown playback event');
    if (!finite(message.positionMediaMs)) fail('Invalid position');
    if (message.speed !== undefined && (!finite(message.speed) || message.speed === 0))
      fail('Invalid speed');
  }
  if (message.clientMonotonicMs !== undefined && !finite(message.clientMonotonicMs))
    fail('Invalid monotonic anchor');
  return deepFreeze(message);
}
