import { readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const root = fileURLToPath(new URL('../', import.meta.url));
const allowedRoots = [
  'protocol/src/', 'core/src/', 'providers/fake/src/',
  'apps/desktop/harness/', 'scripts/', 'tests/contract/', 'tests/integration/',
];
function modules(directory) {
  return readdirSync(new URL(`../${directory}`, import.meta.url), { withFileTypes: true })
    .flatMap(entry => entry.isDirectory() ? modules(`${directory}${entry.name}/`)
      : entry.name.endsWith('.mjs') ? [`${directory}${entry.name}`] : []);
}
const files = allowedRoots.flatMap(modules).sort();
for (const file of files) {
  const result = spawnSync(process.execPath, ['--check', file], {
    cwd: root, stdio: 'inherit', shell: false,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status ?? 1);
}
console.log(`Syntax check passed: ${files.length} modules; no reference/ or engine files included.`);
