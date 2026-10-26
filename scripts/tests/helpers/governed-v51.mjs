import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { basename, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

export const governedV51Path = 'server-modules/approval-persistence-jdbc/src/main/resources/db/migration/V51__preserve_migration_epoch_microsecond_precision.sql';
export const governedV51Sha256 = 'd56d21e4a1eacf8701adfd8e689b60e3b25916cbff736a7e9052f53c1825d917';
const generated = new Set(['target', 'node_modules', 'dist', '.git']);
function files(root) {
  if (!existsSync(root)) return [];
  return readdirSync(root, { withFileTypes: true }).flatMap(entry => {
    const path = join(root, entry.name);
    return entry.isDirectory() ? generated.has(entry.name) ? [] : files(path) : [path];
  });
}
// Historical ownership assertions remain unchanged. Only this exact separately
// governed additive migration may be excluded from their old version ceiling.
export function assertGovernedV51(root) {
  const candidates = ['apps/server/src', 'server-modules'].flatMap(path => files(join(root, path)))
    .filter(path => { const match = /^V(\d+)__/.exec(basename(path)); return match && Number(match[1]) >= 51; })
    .map(path => relative(root, path).replaceAll('\\', '/')).sort();
  assert.deepEqual(candidates, [governedV51Path], 'V51 must have one exact source owner; unknown later migrations are not accepted');
  assert.equal(createHash('sha256').update(readFileSync(join(root, governedV51Path))).digest('hex'), governedV51Sha256,
    'V51 contents require explicit review');
}
let verified = false;
export function historicalMigration(path) {
  if (!verified) { assertGovernedV51(fileURLToPath(new URL('../../../', import.meta.url))); verified = true; }
  return basename(path) !== basename(governedV51Path);
}
