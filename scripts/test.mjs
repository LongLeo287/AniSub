import { readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

// Explicit roots exclude historical reference code and models from every test run.
const root = fileURLToPath(new URL('../', import.meta.url));
const files = ['tests/contract/', 'tests/integration/'].flatMap(directory =>
  readdirSync(new URL(`../${directory}`, import.meta.url), { withFileTypes: true })
    .filter(entry => entry.isFile() && entry.name.endsWith('.test.mjs'))
    .map(entry => `${directory}${entry.name}`));
if (!files.length) throw new Error('No contract/integration tests found');
const result = spawnSync(process.execPath, ['--test', ...files.sort()], {
  cwd: root, stdio: 'inherit', shell: false,
});
if (result.error) throw result.error;
process.exitCode = result.status ?? 1;
