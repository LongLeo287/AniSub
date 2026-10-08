import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { ASSETS, createCandidateCatalog, createCandidatePack, validateStage } from './build-cake-voice-pack.mjs';

test('candidate pack pins only the two approved speakers and 11 exact files', () => {
  const pack = createCandidatePack();
  assert.equal(ASSETS.length, 11);
  assert.deepEqual(pack.voices.map(v => [v.id, v.speakerId, v.gender, v.accent]), [
    ['cake-ngoc-lan', 0, 'female', 'north'],
    ['cake-quang-huy', 2, 'male', 'north'],
  ]);
  assert.deepEqual(pack.files.map(f => f.path), [
    'model.onnx', 'tokens.txt', 'MODEL_CARD.md', 'espeak-ng-data/phontab',
    'espeak-ng-data/phonindex', 'espeak-ng-data/phondata', 'espeak-ng-data/intonations',
    'espeak-ng-data/vi_dict', 'espeak-ng-data/en_dict', 'espeak-ng-data/lang/aav/vi',
    'espeak-ng-data/lang/gmw/en',
  ]);
  assert.match(pack.attribution, /synthetic.*OmniVoice and VoxCPM2/s);
  assert.match(pack.attribution, /rights and consent.*not established/i);
  assert.equal(pack.files[0].urls[0], 'https://github.com/LongLeo287/AniSub/releases/download/voices-vi-cake-v1/cake-model.onnx');
});

test('catalog candidate increments catalog version and retains prior packs unchanged', () => {
  const existing = { id: 'existing', version: '1', voices: [{ id: 'old-voice' }] };
  const next = createCandidateCatalog({ schemaVersion: 1, packs: [existing] });
  assert.equal(next.catalogVersion, 2);
  assert.equal(next.packs.length, 2);
  assert.deepEqual(next.packs[0], existing);
  assert.equal(Buffer.byteLength(JSON.stringify(next), 'utf8') < 1024 * 1024, true);
});

test('stage validator rejects missing, unexpected, and unpinned input files', () => {
  const root = mkdtempSync(join(tmpdir(), 'anisub-cake-builder-'));
  try {
    const missing = join(root, 'missing'); mkdirSync(missing);
    writeFileSync(join(missing, ASSETS[0].source), 'not the pinned model');
    assert.throws(() => validateStage(missing), /exactly the 11 approved flat assets/);

    const unexpected = join(root, 'unexpected'); mkdirSync(unexpected);
    for (const asset of ASSETS) writeFileSync(join(unexpected, asset.source), 'placeholder');
    writeFileSync(join(unexpected, 'extra.bin'), 'extra');
    assert.throws(() => validateStage(unexpected), /exactly the 11 approved flat assets/);

    const tampered = join(root, 'tampered'); mkdirSync(tampered);
    for (const asset of ASSETS) writeFileSync(join(tampered, asset.source), 'placeholder');
    assert.throws(() => validateStage(tampered), /pinned size\/hash mismatch/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
