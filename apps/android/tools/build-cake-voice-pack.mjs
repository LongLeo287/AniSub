// Build the approved Cake sid0/sid2 data pack from the hash-pinned flat staging directory.
// No network access: every one of the 11 staged source files must match its reviewed digest.
//
//   node apps/android/tools/build-cake-voice-pack.mjs <flat-stage-dir> <out-dir> <base-catalog.json>
//
// Output names are the exact filenames pinned in the candidate voices-vi-cake-v1 release.
// This creates a candidate catalog-v1 document only; it does not publish or alter the bundled catalog.
import { createHash } from 'node:crypto';
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptPath = fileURLToPath(import.meta.url);
const RELEASE = 'voices-vi-cake-v1';
const RELEASE_URL = `https://github.com/LongLeo287/AniSub/releases/download/${RELEASE}/`;
const LICENSE_URL = 'https://github.com/LongLeo287/AniSub/blob/main/docs/voices.md';
const MODEL_LICENSE = 'MIT (CakeByVPBank/piper-pgl-v4 weights; synthetic teacher-source provenance and consent are not established by this license)';
const TOKEN_LICENSE = 'Apache-2.0 (sherpa-onnx conversion tokens; as recorded by the verified conversion provenance)';
const ESPEAK_LICENSE = 'GPL-3.0-or-later (espeak-ng-data)';

// Flat source filename -> immutable publication name, catalog path, reviewed size and SHA-256.
export const ASSETS = Object.freeze([
  ['cake-model.onnx', 'cake-model.onnx', 'model.onnx', 77101122, '9f9a5e43541c9504bb15eda2c74986618cb19885a661e6cb306de4b3e9da21c2', MODEL_LICENSE],
  ['cake-tokens.txt', 'cake-tokens.txt', 'tokens.txt', 1129, '30615b46df803181140b628ca264f33860e90a0834977936274da840dd36c905', TOKEN_LICENSE],
  ['cake-MODEL_CARD.md', 'cake-MODEL_CARD.md', 'MODEL_CARD.md', 13919, 'bb1759a72f894359045f0a86bb4866773b2960128bcc942fd0e93410c55b8499', MODEL_LICENSE],
  ['cake-espeak-ng-data.phontab', 'cake-espeak-ng-data.phontab', 'espeak-ng-data/phontab', 55796, '886f3fa402cb0ba73d483aa8ad000af47a6b7cc06293c75a97913fba68a530f6', ESPEAK_LICENSE],
  ['cake-espeak-ng-data.phonindex', 'cake-espeak-ng-data.phonindex', 'espeak-ng-data/phonindex', 39074, '3ca7b8fa3b42624e4b0f152707e7a39245fce569aa99ea47c055d9e622fcf0c4', ESPEAK_LICENSE],
  ['cake-espeak-ng-data.phondata', 'cake-espeak-ng-data.phondata', 'espeak-ng-data/phondata', 550424, '4e0288957874029a8c3c9f41a8f517ad4bf18127046decbdd4b9d1d6807ce3a3', ESPEAK_LICENSE],
  ['cake-espeak-ng-data.intonations', 'cake-espeak-ng-data.intonations', 'espeak-ng-data/intonations', 2040, '3f8af65fd3eda9759a10f021d61361c120871f463515229c925995c7f90918cc', ESPEAK_LICENSE],
  ['cake-espeak-ng-data.vi_dict', 'cake-espeak-ng-data.vi_dict', 'espeak-ng-data/vi_dict', 52608, 'bbf7cab4ba733b2f5d9d3b61eb1194ef745232fa3adebb4bc92456e948cb3722', ESPEAK_LICENSE],
  ['cake-espeak-ng-data.en_dict', 'cake-espeak-ng-data.en_dict', 'espeak-ng-data/en_dict', 166944, '71bd330ba8a2e3e8076e631508208ef49449d6147c17b7bd2b4b1e1468292e35', ESPEAK_LICENSE],
  ['cake-espeak-ng-data.lang.aav.vi', 'cake-espeak-ng-data.lang.aav.vi', 'espeak-ng-data/lang/aav/vi', 111, '3199c980f9e23a88a2aa693cd631bf4fcb0f3408c4272bc01b7ac0ff8e79d778', ESPEAK_LICENSE],
  ['cake-espeak-ng-data.lang.gmw.en', 'cake-espeak-ng-data.lang.gmw.en', 'espeak-ng-data/lang/gmw/en', 140, '4605d5330801de3641c6e366d15f129ea1f5ffbce8722642aba01ace07ab9c83', ESPEAK_LICENSE],
].map(([source, asset, path, bytes, sha256, license]) => Object.freeze({ source, asset, path, bytes, sha256, license })));

export function validateStage(src) {
  const names = readdirSync(src).sort();
  const expected = ASSETS.map(a => a.source).sort();
  if (JSON.stringify(names) !== JSON.stringify(expected)) throw new Error('stage must contain exactly the 11 approved flat assets');
  for (const asset of ASSETS) {
    const file = join(src, asset.source);
    if (!statSync(file).isFile()) throw new Error(`not a regular file: ${asset.source}`);
    const data = readFileSync(file);
    const digest = createHash('sha256').update(data).digest('hex');
    if (data.length !== asset.bytes || digest !== asset.sha256) throw new Error(`pinned size/hash mismatch: ${asset.source}`);
  }
}

export function createCandidatePack() {
  return {
    id: 'vi-cake-piper-pgl-v4', version: '1', name: 'Cake Piper PGL v4 (tiếng Việt)',
    language: 'vi', engine: 'sherpa-onnx-vits', sampleRate: 22050, release: RELEASE,
    license: 'Weights: MIT (CakeByVPBank); tokens: Apache-2.0 (sherpa-onnx conversion); espeak-ng-data: GPL-3.0-or-later. Synthetic teacher-source rights and consent are not established; see attribution.',
    licenseUrl: LICENSE_URL,
    attribution: 'CakeByVPBank piper-pgl-v4 Vietnamese Piper/VITS weights; source model card identifies MIT weights. Selected speakers only: sid0 Ngọc Lan (female, north) and sid2 Quang Huy (male, north). The card says training audio is synthetic/distilled from OmniVoice and VoxCPM2 voice-cloning teachers; identities, rights and consent for those source voices are not established in the card. This provenance limitation is disclosed and is not resolved by the weights license. Converted tokens are attributed Apache-2.0 to sherpa-onnx conversion; phonemizer data is espeak-ng GPL-3.0-or-later.',
    model: 'model.onnx', tokens: 'tokens.txt', dataDir: 'espeak-ng-data',
    voices: [
      { id: 'cake-ngoc-lan', name: 'Ngọc Lan (Cake sid0)', speakerId: 0, gender: 'female', accent: 'north' },
      { id: 'cake-quang-huy', name: 'Quang Huy (Cake sid2)', speakerId: 2, gender: 'male', accent: 'north' },
    ],
    files: ASSETS.map(a => ({ path: a.path, bytes: a.path==='tokens.txt'?968:a.bytes,
      sha256: a.path==='tokens.txt'?'e8a50ae0c75612d18cfcf3f90800dca79f961e3ed97f9574f3535194469187fd':a.sha256,
      license: a.license, urls: [RELEASE_URL + a.asset] })),
  };
}

export function createCandidateCatalog(base) {
  if (base?.schemaVersion !== 1 || !Array.isArray(base.packs)) throw new Error('base catalog must use schemaVersion 1 and contain packs');
  if (base.packs.some(p => p.id === 'vi-cake-piper-pgl-v4')) throw new Error('Cake pack already exists in base catalog');
  const catalog = { ...base, catalogVersion: 2, packs: [...base.packs, createCandidatePack()] };
  const json = JSON.stringify(catalog, null, 2) + '\n';
  if (Buffer.byteLength(json, 'utf8') > 1024 * 1024 || json.length > 256 * 1024) throw new Error('candidate catalog exceeds VoiceCatalog limits');
  if (catalog.packs.length > 16 || catalog.packs.reduce((n, p) => n + p.voices.length, 0) > 64) throw new Error('candidate catalog exceeds pack/voice limits');
  const pack = catalog.packs.at(-1);
  if (pack.files.length > 64 || pack.files.reduce((n, f) => n + f.bytes, 0) > 512 * 1024 * 1024) throw new Error('candidate pack exceeds VoiceCatalog limits');
  return catalog;
}

export function build({ src, outDir, baseCatalogPath }) {
  validateStage(src);
  const base=JSON.parse(readFileSync(baseCatalogPath,'utf8'));
  // Rebuilding this unpublished candidate never changes the other reviewed packs.
  base.packs=base.packs.filter(p=>p.id!=='vi-cake-piper-pgl-v4');
  const catalog = createCandidateCatalog(base);
  const catalogText = JSON.stringify(catalog, null, 2) + '\n';
  if (existsSync(outDir)) {
    const names = readdirSync(outDir).sort();
    const expected = ASSETS.map(a => a.asset).sort();
    if (JSON.stringify(names) !== JSON.stringify(expected)) throw new Error('output directory must be empty or contain exactly the 11 generated assets');
    for (const asset of ASSETS) {
      const file = join(outDir, asset.asset);
      if (!statSync(file).isFile()) throw new Error(`refusing to overwrite a changed output: ${asset.asset}`);
      const data = readFileSync(file);
      const expectedOutput=catalog.packs.at(-1).files.find(f=>f.path===asset.path);
      if (data.length !== expectedOutput.bytes || createHash('sha256').update(data).digest('hex') !== expectedOutput.sha256)
        throw new Error(`refusing to overwrite a changed output: ${asset.asset}`);
    }
  }
  mkdirSync(outDir, { recursive: true });
  for (const asset of ASSETS) {
    if(asset.path==='tokens.txt')writeFileSync(join(outDir,asset.asset),readFileSync(join(src,asset.source),'utf8').replaceAll('\r',''));
    else copyFileSync(join(src, asset.source), join(outDir, asset.asset));
  }
  const catalogDir = join(dirname(outDir), 'catalog-v1');
  mkdirSync(catalogDir, { recursive: true });
  const candidatePath = join(catalogDir, 'catalog.json');
  if (existsSync(candidatePath) && readFileSync(candidatePath, 'utf8') !== catalogText)
    throw new Error('refusing to overwrite a changed candidate catalog');
  writeFileSync(candidatePath, catalogText);
  return { catalogPath: candidatePath, files: ASSETS.length, totalBytes: catalog.packs.at(-1).files.reduce((n, a) => n + a.bytes, 0) };
}

if (process.argv[1] && resolve(process.argv[1]) === scriptPath) {
  const [src, outDir, baseCatalogPath] = process.argv.slice(2);
  if (!src || !outDir || !baseCatalogPath) {
    console.error('usage: node apps/android/tools/build-cake-voice-pack.mjs <flat-stage-dir> <out-dir> <base-catalog.json>');
    process.exit(2);
  }
  try {
    const result = build({ src, outDir, baseCatalogPath });
    console.log(`Verified and staged ${result.files} pinned Cake assets (${result.totalBytes} bytes) at ${outDir}`);
    console.log(`Wrote candidate catalog ${result.catalogPath}; release URLs remain pending publication.`);
  } catch (error) {
    console.error(`Cake pack build rejected: ${error.message}`);
    process.exit(1);
  }
}
