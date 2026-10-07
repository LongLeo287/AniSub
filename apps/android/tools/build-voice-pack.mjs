// Builds the AniSub "voices-v1" release assets and the APK-pinned catalog from an extracted
// upstream sherpa-onnx Piper archive. Dependency-free (Node >= 22).
//
//   node tools/build-voice-pack.mjs <extracted vits-piper-vi_VN-vais1000-medium dir> [outDir]
//
// Upstream archive (verify before extracting):
//   https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-vi_VN-vais1000-medium.tar.bz2
//   sha256 fa1367710767d36ed5cf13b4a449e20c35ffd12791c2e47c2e64142bfa55551a
// Only the espeak-ng-data files Vietnamese phonemization actually reads are shipped (deterministic
// output was verified identical to the full 18 MB data set with noise disabled).
import { createHash } from 'node:crypto';
import { copyFileSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const src = process.argv[2];
const outDir = resolve(process.argv[3] ?? join(here, '..', 'build', 'voices-v1'));
if (!src) { console.error('usage: node tools/build-voice-pack.mjs <extracted pack dir> [outDir]'); process.exit(2); }

const RELEASE = 'https://github.com/LongLeo287/AniSub/releases/download/voices-v1/';
const PREFIX = 'vais1000-';
const files = [
  ['vi_VN-vais1000-medium.onnx', 'MIT (piper-voices); training data VAIS-1000 CC BY 4.0'],
  ['tokens.txt', 'Apache-2.0 (sherpa-onnx conversion)'],
  ['MODEL_CARD', 'MIT (piper-voices)'],
  ['espeak-ng-data/phontab', 'GPL-3.0-or-later (espeak-ng)'],
  ['espeak-ng-data/phonindex', 'GPL-3.0-or-later (espeak-ng)'],
  ['espeak-ng-data/phondata', 'GPL-3.0-or-later (espeak-ng)'],
  ['espeak-ng-data/intonations', 'GPL-3.0-or-later (espeak-ng)'],
  ['espeak-ng-data/vi_dict', 'GPL-3.0-or-later (espeak-ng)'],
  ['espeak-ng-data/en_dict', 'GPL-3.0-or-later (espeak-ng)'],
  ['espeak-ng-data/lang/aav/vi', 'GPL-3.0-or-later (espeak-ng)'],
  ['espeak-ng-data/lang/gmw/en', 'GPL-3.0-or-later (espeak-ng)'],
];

mkdirSync(outDir, { recursive: true });
const entries = files.map(([path, license]) => {
  const from = join(src, path);
  const bytes = statSync(from).size;
  const sha256 = createHash('sha256').update(readFileSync(from)).digest('hex');
  const asset = PREFIX + path.replaceAll('/', '.');
  copyFileSync(from, join(outDir, asset));
  return { path, bytes, sha256, license, urls: [RELEASE + asset] };
});

const catalog = {
  schemaVersion: 1,
  packs: [{
    id: 'vi-vais1000-medium',
    version: '1',
    name: 'VAIS-1000 (Piper, tiếng Việt)',
    language: 'vi',
    engine: 'sherpa-onnx-vits',
    sampleRate: 22050,
    license: 'Mô hình: MIT (rhasspy/piper-voices) · Dữ liệu VAIS-1000: CC BY 4.0 · espeak-ng-data: GPL-3.0-or-later',
    licenseUrl: 'https://github.com/LongLeo287/AniSub/blob/main/docs/voices.md',
    attribution: 'Giọng vi_VN-vais1000-medium của dự án Piper (rhasspy/piper-voices), huấn luyện trên kho VAIS-1000 '
      + '(IEEE DataPort, CC BY 4.0), tinh chỉnh từ giọng en_US-lessac; bản ONNX do k2-fsa/sherpa-onnx đóng gói. '
      + 'Dữ liệu phát âm espeak-ng (GPL-3.0-or-later).',
    model: 'vi_VN-vais1000-medium.onnx',
    tokens: 'tokens.txt',
    dataDir: 'espeak-ng-data',
    voices: [{ id: 'vais1000', name: 'VAIS-1000', speakerId: 0, gender: 'unknown', accent: 'unknown' }],
    files: entries,
  }],
};
const json = JSON.stringify(catalog, null, 2) + '\n';
writeFileSync(join(outDir, 'voice-catalog.json'), json);
const total = entries.reduce((n, e) => n + e.bytes, 0);
console.log(`Wrote ${entries.length} assets (${total} bytes) and voice-catalog.json to ${outDir}`);
console.log('Copy voice-catalog.json to app/src/main/assets/ and upload every other file to the voices-v1 release.');
