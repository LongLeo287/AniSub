// Writes anisub.json next to a SIGNED AniSub APK so AniBox can verify it before installing.
// Dependency-free (Node >= 22); needs ANDROID_HOME (build-tools with apksigner + aapt2).
//
//   node tools/make-release-manifest.mjs <AniSub.apk> [--notes "text"] [--out anisub.json]
//       [--url https://github.com/LongLeo287/AniSub/releases/latest/download/AniSub.apk]
//
// Output: {schemaVersion:1, versionCode, versionName, apk, sha256, signerSha256, notes}
// Fails if the APK is unsigned, has more than one signer, or is not com.anisub.runtime.
import { createHash } from 'node:crypto';
import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

const args = process.argv.slice(2);
const apkPath = args.find((a, i) => !a.startsWith('--') && (i === 0 || !args[i - 1].startsWith('--')));
const opt = (name, fallback) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : fallback; };
if (!apkPath || !existsSync(apkPath)) { console.error('usage: node tools/make-release-manifest.mjs <AniSub.apk> [--notes text] [--out file] [--url url]'); process.exit(2); }

const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
if (!sdk) throw new Error('Set ANDROID_HOME to an Android SDK with build-tools');
const toolsRoot = join(sdk, 'build-tools');
const version = readdirSync(toolsRoot).sort((a, b) => a.localeCompare(b, undefined, { numeric: true })).pop();
const win = process.platform === 'win32';
const tool = (name) => join(toolsRoot, version, win ? (name === 'apksigner' ? 'apksigner.bat' : name + '.exe') : name);

function run(exe, argv) {
  const quoted = [exe, ...argv].map((s) => `"${s}"`).join(' ');
  const r = spawnSync(quoted, { shell: true, encoding: 'utf8' });
  if (r.status !== 0) throw new Error(`${exe} failed (${r.status}): ${r.stderr || r.stdout}`);
  return r.stdout;
}

const apk = resolve(apkPath);
const certs = run(tool('apksigner'), ['verify', '--print-certs', apk]);
const signers = [...certs.matchAll(/Signer #(\d+) certificate SHA-256 digest: ([0-9a-f]{64})/g)];
if (signers.length !== 1) throw new Error(`Expected exactly one signer, found ${signers.length}`);
const badging = run(tool('aapt2'), ['dump', 'badging', apk]);
const pkg = badging.match(/package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'/);
if (!pkg || pkg[1] !== 'com.anisub.runtime') throw new Error('Not a com.anisub.runtime APK');

const manifest = {
  schemaVersion: 1,
  versionCode: Number(pkg[2]),
  versionName: pkg[3],
  apk: opt('--url', 'https://github.com/LongLeo287/AniSub/releases/latest/download/AniSub.apk'),
  sha256: createHash('sha256').update(readFileSync(apk)).digest('hex'),
  signerSha256: signers[0][2],
  notes: opt('--notes', ''),
};
const out = resolve(opt('--out', join(dirname(apk), 'anisub.json')));
writeFileSync(out, JSON.stringify(manifest, null, 2) + '\n');
console.log(`Wrote ${out}`);
console.log(JSON.stringify(manifest, null, 2));
