// Builds AniSub voice-pack release assets and the APK-pinned catalog entry from an extracted
// upstream sherpa-onnx Piper archive. Dependency-free (Node >= 22).
//
//   node tools/build-voice-pack.mjs <extracted pack dir> [outDir] [--pack vi|en] [--catalog <voice-catalog.json>]
//
// --pack vi (default): release "voices-v1", Piper vi_VN-vais1000-medium
//   https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-vi_VN-vais1000-medium.tar.bz2
//   sha256 fa1367710767d36ed5cf13b4a449e20c35ffd12791c2e47c2e64142bfa55551a
// --pack en: release "voices-en-v1", Piper en_US-ljspeech-medium
//   https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-ljspeech-medium.tar.bz2
//   sha256 3dfb4b759d8be032a4903a9538d128b0fda2a06ab1de6cbc2d93a97e2dd83dba
// Only the espeak-ng-data files the two voices read are shipped. Both packs carry the SAME 8 espeak
// files (identical SHA-256): the app copies them into one shared espeak directory (EspeakData), so
// either pack alone serves both languages' phonemizer data.
// With --catalog, the pack entry replaces the entry of the same id in that catalog (other packs kept);
// otherwise a catalog with just this pack is written to <outDir>/voice-catalog.json.
import { createHash } from 'node:crypto';
import { copyFileSync, existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const opt = (name) => { const i = args.indexOf(name); if (i < 0) return undefined; const v = args[i + 1]; args.splice(i, 2); return v; };
const which = opt('--pack') ?? 'vi';
const catalogPath = opt('--catalog');
const [src, outArg] = args;
if (!src || !['vi', 'en'].includes(which)) {
  console.error('usage: node tools/build-voice-pack.mjs <extracted pack dir> [outDir] [--pack vi|en] [--catalog <voice-catalog.json>]');
  process.exit(2);
}

const ESPEAK = 'GPL-3.0-or-later (espeak-ng)';
const espeakFiles = ['phontab', 'phonindex', 'phondata', 'intonations', 'vi_dict', 'en_dict', 'lang/aav/vi', 'lang/gmw/en']
  .map(f => [`espeak-ng-data/${f}`, ESPEAK]);

const PACKS = {
  vi: {
    release: 'voices-v1', prefix: 'vais1000-',
    files: [
      ['vi_VN-vais1000-medium.onnx', 'MIT (piper-voices); training data VAIS-1000 CC BY 4.0'],
      ['tokens.txt', 'Apache-2.0 (sherpa-onnx conversion)'],
      ['MODEL_CARD', 'MIT (piper-voices)'],
      ...espeakFiles,
    ],
    entry: {
      id: 'vi-vais1000-medium', version: '1', name: 'VAIS-1000 (Piper, tiếng Việt)', language: 'vi',
      license: 'Mô hình: MIT (rhasspy/piper-voices) · Dữ liệu VAIS-1000: CC BY 4.0 · espeak-ng-data: GPL-3.0-or-later',
      attribution: 'Giọng vi_VN-vais1000-medium của dự án Piper (rhasspy/piper-voices), huấn luyện trên kho VAIS-1000 '
        + '(IEEE DataPort, CC BY 4.0), tinh chỉnh từ giọng en_US-lessac; bản ONNX do k2-fsa/sherpa-onnx đóng gói. '
        + 'Dữ liệu phát âm espeak-ng (GPL-3.0-or-later).',
      model: 'vi_VN-vais1000-medium.onnx',
      voices: [{ id: 'vais1000', name: 'VAIS-1000', speakerId: 0, gender: 'unknown', accent: 'unknown' }],
    },
  },
  en: {
    release: 'voices-en-v1', prefix: 'ljspeech-',
    files: [
      ['en_US-ljspeech-medium.onnx', 'MIT (piper-voices); training data LJ Speech, public domain'],
      ['tokens.txt', 'Apache-2.0 (sherpa-onnx conversion)'],
      ['MODEL_CARD', 'MIT (piper-voices)'],
      ...espeakFiles,
    ],
    entry: {
      id: 'en-ljspeech-medium', version: '1', name: 'LJSpeech (Piper, tiếng Anh)', language: 'en',
      license: 'Mô hình: MIT (rhasspy/piper-voices) · Dữ liệu LJ Speech: phạm vi công cộng · espeak-ng-data: GPL-3.0-or-later',
      attribution: 'Giọng en_US-ljspeech-medium của dự án Piper (rhasspy/piper-voices), do Bryce Beattie huấn luyện từ đầu '
        + 'trên LJ Speech Dataset (keithito.com, phạm vi công cộng); bản ONNX do k2-fsa/sherpa-onnx đóng gói. '
        + 'Dữ liệu phát âm espeak-ng (GPL-3.0-or-later).',
      model: 'en_US-ljspeech-medium.onnx',
      voices: [{ id: 'ljspeech', name: 'LJSpeech', speakerId: 0, gender: 'female', accent: 'en-US' }],
    },
  },
};

const spec = PACKS[which];
const outDir = resolve(outArg ?? join(here, '..', 'build', spec.release));
const RELEASE = `https://github.com/LongLeo287/AniSub/releases/download/${spec.release}/`;
mkdirSync(outDir, { recursive: true });
const entries = spec.files.map(([path, license]) => {
  const from = join(src, path);
  const bytes = statSync(from).size;
  const sha256 = createHash('sha256').update(readFileSync(from)).digest('hex');
  const asset = spec.prefix + path.replaceAll('/', '.');
  copyFileSync(from, join(outDir, asset));
  return { path, bytes, sha256, license, urls: [RELEASE + asset] };
});

const pack = {
  ...spec.entry,
  engine: 'sherpa-onnx-vits',
  sampleRate: 22050,
  ...(which === 'vi' ? {} : { release: spec.release }),
  licenseUrl: 'https://github.com/LongLeo287/AniSub/blob/main/docs/voices.md',
  tokens: 'tokens.txt',
  dataDir: 'espeak-ng-data',
  files: entries,
};
// Stable key order (matches the reviewed catalog layout).
const ordered = {};
for (const k of ['id', 'version', 'name', 'language', 'engine', 'sampleRate', 'release', 'license', 'licenseUrl', 'attribution', 'model', 'tokens', 'dataDir', 'voices', 'files']) {
  if (pack[k] !== undefined) ordered[k] = pack[k];
}

let catalog = { schemaVersion: 1, packs: [] };
if (catalogPath && existsSync(catalogPath)) catalog = JSON.parse(readFileSync(catalogPath, 'utf8'));
const at = catalog.packs.findIndex(p => p.id === ordered.id);
if (at >= 0) catalog.packs[at] = ordered; else catalog.packs.push(ordered);
const json = JSON.stringify(catalog, null, 2) + '\n';
const target = catalogPath ?? join(outDir, 'voice-catalog.json');
writeFileSync(target, json);
const total = entries.reduce((n, e) => n + e.bytes, 0);
console.log(`Wrote ${entries.length} ${spec.prefix}* assets (${total} bytes) to ${outDir}; catalog ${target}`);
console.log(`Upload every ${spec.prefix}* file to the GitHub release tagged ${spec.release}.`);
