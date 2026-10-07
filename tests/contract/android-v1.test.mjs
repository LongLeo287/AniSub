// Android Messenger major 1 / minor 2: schema vs. the shared fixtures (the Java runtime tests run the
// same fixtures through OpenRules/Capabilities). Dependency-free mini validator for the schema subset used.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = (p) => JSON.parse(readFileSync(new URL(`../../${p}`, import.meta.url), 'utf8'));
const schema = read('protocol/schema/android-v1.schema.json');
const cases = read('tests/fixtures/android-v1/open-cases.json').cases;
const caps = read('tests/fixtures/android-v1/capabilities-minor2.json');

function resolve(s) { return s && s.$ref ? resolve(schema.$defs[s.$ref.split('/').pop()]) : s; }
function validate(s, v, path = '$') {
  s = resolve(s);
  const errors = [];
  if (!s) return errors;
  for (const part of s.allOf ?? []) errors.push(...validate(part, v, path));
  if (s.const !== undefined && v !== s.const) errors.push(`${path} != ${JSON.stringify(s.const)}`);
  if (s.enum && !s.enum.includes(v)) errors.push(`${path} not in enum`);
  const t = s.type;
  if (t === 'object' && (typeof v !== 'object' || v === null || Array.isArray(v))) return [...errors, `${path} not object`];
  if (t === 'array' && !Array.isArray(v)) return [...errors, `${path} not array`];
  if (t === 'string' && typeof v !== 'string') return [...errors, `${path} not string`];
  if (t === 'boolean' && typeof v !== 'boolean') return [...errors, `${path} not boolean`];
  if (t === 'integer' && !Number.isInteger(v)) return [...errors, `${path} not integer`];
  if (t === 'number' && typeof v !== 'number') return [...errors, `${path} not number`];
  if (typeof v === 'string' && s.pattern && !new RegExp(s.pattern).test(v)) errors.push(`${path} pattern`);
  if (typeof v === 'string' && s.maxLength !== undefined && v.length > s.maxLength) errors.push(`${path} too long`);
  if (typeof v === 'number' && s.minimum !== undefined && v < s.minimum) errors.push(`${path} < min`);
  if (typeof v === 'number' && s.maximum !== undefined && v > s.maximum) errors.push(`${path} > max`);
  if (Array.isArray(v)) {
    if (s.maxItems !== undefined && v.length > s.maxItems) errors.push(`${path} too many`);
    if (s.items) v.forEach((x, i) => errors.push(...validate(s.items, x, `${path}[${i}]`)));
  }
  if (v && typeof v === 'object' && !Array.isArray(v)) {
    for (const r of s.required ?? []) if (!(r in v)) errors.push(`${path}.${r} required`);
    for (const [k, sub] of Object.entries(s.properties ?? {})) if (k in v) errors.push(...validate(sub, v[k], `${path}.${k}`));
    if (s.additionalProperties && typeof s.additionalProperties === 'object')
      for (const [k, x] of Object.entries(v)) if (!(k in (s.properties ?? {}))) errors.push(...validate(s.additionalProperties, x, `${path}.${k}`));
  }
  return errors;
}

const envelope = (language) => ({ type: 'OPEN', major: 1, session: 's1', revision: 1, seq: 1, positionMs: 0, speed: 1, language });

test('every fixture OPEN that the runtime accepts or rejects by rule is schema-valid; MALFORMED ones are not', () => {
  for (const c of cases) {
    const errs = validate(schema.$defs.open, { ...envelope(c.language), ...c.message });
    if (c.expect.error === 'MALFORMED') assert.ok(errs.length > 0, `${c.name} should violate the schema`);
    else if (!['UNSUPPORTED'].includes(c.expect.error)) assert.deepEqual(errs, [], c.name);
  }
});

test('expected error replies (code + detail) are schema-valid', () => {
  for (const c of cases.filter(x => x.expect.error && x.expect.error !== 'MALFORMED')) {
    const reply = { type: 'ERROR', major: 1, code: c.expect.error, session: 's1', revision: 1, ...(c.expect.detail ?? {}) };
    assert.deepEqual(validate(schema.$defs.error, reply), [], c.name);
  }
});

test('capabilities fixture is minor 2 and schema-valid; minor-1 fields kept', () => {
  assert.deepEqual(validate(schema.$defs.capabilities, caps), []);
  assert.equal(caps.minor, 2);
  for (const legacy of ['voicePack', 'aiVoice', 'modes', 'rate', 'runtime', 'aiEngine', 'tts', 'engine', 'state']) assert.ok(legacy in caps, legacy);
  assert.equal(caps.translate.models.en, 'ready');
});

test('a minor-1 OPEN (no voiceLang) is valid and a timeline CUES batch is valid', () => {
  assert.deepEqual(validate(schema.$defs.open, { ...envelope('vi'), mode: 'ai' }), []);
  const cues = { ...envelope('en'), type: 'CUES', timeline: true, cues: [{ id: 'c1', text: 'Hello', startMs: 600000, endMs: 602000, role: 'dialogue' }] };
  assert.deepEqual(validate(schema.$defs.cues, cues), []);
  assert.ok(validate(schema.$defs.cues, { ...cues, cues: Array(17).fill(cues.cues[0]) }).length > 0);
  assert.ok(validate(schema.$defs.open, { ...envelope('vi'), voiceLang: 'fr' }).length > 0);
  assert.deepEqual(validate(schema.$defs.languageDetected, { type: 'LANGUAGE_DETECTED', major: 1, session: 's', revision: 1, language: 'ja', assumed: false }), []);
});
