import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import test from 'node:test';

const root = resolve(import.meta.dirname, '../..');
const runtime = readFileSync(
  resolve(
    root,
    'scripts/product-readiness/capacity-recovery/upgrade-restore.mjs',
  ),
  'utf8',
);

test('custom PostgreSQL backup streams once and is validated before replacement', () => {
  assert.equal((runtime.match(/'pg_dump'/gu) || []).length, 1);
  assert.doesNotMatch(runtime, /--file=-/u);
  assert.match(runtime, /Buffer\.from\('PGDMP', 'ascii'\)/u);
  assert.match(
    runtime,
    /'pg_restore',\s*'--list'/u,
  );
  assert.match(runtime, /archiveValidatedBy: 'pg_restore --list'/u);

  const backup = runtime.indexOf('function createBackup');
  const archiveValidation = runtime.indexOf(
    "label: 'validate PostgreSQL custom-format backup before volume replacement'",
  );
  const volumeReplacement = runtime.indexOf(
    "label: 'destroy pre-restore disposable PostgreSQL volume'",
  );
  assert.notEqual(backup, -1);
  assert.notEqual(archiveValidation, -1);
  assert.notEqual(volumeReplacement, -1);
  assert.equal(backup < archiveValidation, true);
  assert.equal(archiveValidation < volumeReplacement, true);
});
