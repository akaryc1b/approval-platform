import assert from 'node:assert/strict';
import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';

const root = process.cwd();
const migrationDir = path.join(
  root,
  'server-modules/approval-persistence-jdbc/src/main/resources/db/migration',
);
async function text(file) { return readFile(file, 'utf8'); }

test('M5-B persistence slice is explicitly authorized but remains below M5-C and M5-D', async () => {
  const evidence = await text(path.join(root, 'docs/M5_B_PROCESS_MIGRATION_PERSISTENCE_PROTOCOL.md'));
  for (const boundary of [
    'M5-B PERSISTENCE SLICE: `AUTHORIZED_DOMAIN_AND_PERSISTENCE_ONLY`',
    'M5-B stage status: `IN_PROGRESS`',
    'user explicitly accepted the completed M5-A evidence package',
    'planId` and `planHash` are persisted only as opaque future references',
    'Never retry `UNKNOWN` automatically',
    'does not authorize M5-C',
    'PR #58 remains Open + Draft',
    'Issues #13 and #14 remain Open',
  ]) assert.ok(evidence.includes(boundary), `M5-B evidence omits ${boundary}`);
  assert.doesNotMatch(evidence, /M5-B stage status: `COMPLETE`/);
});

test('Flyway advances continuously through V35 with exactly six M5-B protocol tables', async () => {
  const files = await readdir(migrationDir);
  for (const expected of [
    'V33__create_process_migration_intents.sql',
    'V34__create_process_migration_attempts.sql',
    'V35__create_process_migration_outcome_evidence.sql',
  ]) assert.ok(files.includes(expected), `missing ${expected}`);
  assert.ok(
    files.every((file) => !/^V(?:3[6-9]|[4-9][0-9])__/.test(file)),
    `M5-B advanced beyond V35: ${files.join(', ')}`,
  );
  const sql = (await Promise.all(files.filter((file) => /^V3[3-5]__/.test(file))
    .sort().map((file) => text(path.join(migrationDir, file))))).join('\n');
  for (const table of [
    'ap_process_migration_intent', 'ap_process_migration_intent_event',
    'ap_process_migration_attempt', 'ap_process_migration_attempt_event',
    'ap_process_migration_verification', 'ap_process_migration_reconciliation',
  ]) assert.match(sql, new RegExp(`create table ${table} \\(`));
  for (const boundary of [
    'uk_process_migration_attempt_active_instance',
    'ap_guard_process_migration_intent',
    'ap_guard_process_migration_intent_event',
    'ap_guard_process_migration_attempt',
    'ap_guard_process_migration_attempt_event',
    'ap_guard_process_migration_verification_insert',
    'ap_guard_process_migration_reconciliation_insert',
    'ap_reject_process_migration_evidence_mutation',
    'migration intent revision must advance exactly once',
    'migration attempt revision must advance exactly once',
    'migration verification sequence must advance exactly once',
    'migration reconciliation sequence must advance exactly once',
  ]) assert.ok(sql.includes(boundary), `M5-B SQL omits ${boundary}`);
  assert.doesNotMatch(sql, /ACT_[A-Z0-9_]+/);
});
