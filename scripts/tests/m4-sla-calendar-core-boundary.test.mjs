import assert from 'node:assert/strict';
import { stat } from 'node:fs/promises';
import test from 'node:test';
import {
  exec, exists, git, migrationsRoot, path, readdir, root, text,
} from './m4-sla-calendar-boundary-support.mjs';

test('M4 migrations remain contiguous and frozen through V32', async () => {
  const migrationFiles = (await readdir(path.join(root, migrationsRoot)))
    .filter((name) => /^V\d+__.*\.sql$/.test(name));
  const versions = migrationFiles
    .map((name) => Number(name.match(/^V(\d+)__/)?.[1]))
    .sort((left, right) => left - right);
  assert.deepEqual(
    versions.filter((version) => version <= 32),
    Array.from({ length: 32 }, (_, index) => index + 1),
    'M4 Flyway baseline must remain contiguous through V32',
  );
  assert.equal(new Set(versions).size, versions.length, 'Flyway migration versions must remain unique');
  for (const file of [
    'V28__create_versioned_work_calendars.sql',
    'V29__create_immutable_sla_policies.sql',
    'V30__create_sla_instances_and_responsibility_history.sql',
    'V31__create_sla_execution_intents_attempts_and_replay.sql',
    'V32__create_process_release_lifecycle_and_runtime_binding.sql',
  ]) assert.ok(migrationFiles.includes(file), `missing frozen M4 migration ${file}`);
});

test('V32 establishes immutable tenant-scoped release lifecycle and runtime binding evidence', async () => {
  const migration = await text(`${migrationsRoot}/V32__create_process_release_lifecycle_and_runtime_binding.sql`);
  for (const table of [
    'ap_process_release_lifecycle',
    'ap_process_release_lifecycle_history',
    'ap_process_runtime_binding',
  ]) assert.match(migration, new RegExp(`create table ${table}\\b`));
  assert.match(migration, /lifecycle_state in \('PUBLISHED', 'ACTIVE', 'DEPRECATED', 'RETIRED'\)/);
  assert.match(migration, /create unique index uk_process_release_single_active[\s\S]*where lifecycle_state = 'ACTIVE'/);
  assert.match(migration, /foreign key \([\s\n]*tenant_id, definition_key, release_version, release_package_hash/);
  for (const trigger of [
    'trg_process_release_lifecycle_guard',
    'trg_process_release_history_append_only',
    'trg_process_runtime_binding_immutable',
    'trg_approval_release_package_immutable',
  ]) assert.match(migration, new RegExp(trigger));
  assert.doesNotMatch(migration, /\b(?:ACT_[A-Z0-9_]+|act_[a-z0-9_]+)\b/);
});

test('only permanent workflows remain and the M4 boundary is wired permanently', async () => {
  const workflows = await readdir(path.join(root, '.github/workflows'));
  assert.ok(workflows.includes('approval-platform-validation.yml'));
  for (const workflow of workflows) assert.doesNotMatch(workflow, /(?:temporary|temp|pr\d+|m4[-_].*validation)/i);
  assert.match(await text('.github/workflows/approval-platform-validation.yml'), /node --test scripts\/tests\/m4-sla-calendar-boundary\.test\.mjs/);
});

test('accepted governance documents remain byte-for-byte frozen', async () => {
  const frozen = new Map([
    ['docs/M3_FINAL_ACCEPTANCE.md', '459c684027e4a08f08655bff3e31721912dc35bc'],
    ['docs/M4_IDENTITY_AND_TENANT_GOVERNANCE.md', '716ecf6503aeaea7a6dbfa5980964a5c4b983619'],
    ['docs/M4_AUTHORIZATION_AND_RESPONSIBILITY_GOVERNANCE.md', '888f07df905726cfb3507d2ae495db3247d6c4fe'],
    ['docs/M4_SLA_AND_CALENDAR_GOVERNANCE.md', 'beb098bc6b4ee68c6ca11da0678a76780b72a049'],
    ['docs/M4_SLA_EXECUTION_AND_REPLAY_GOVERNANCE.md', 'dc687d073e0352e0b88d96bd8df0f4ee36775b6e'],
  ]);
  for (const [file, expected] of frozen) assert.equal(await git('hash-object', file), expected, `${file} is frozen`);
});

test('tracked tree excludes generated artifacts credentials and temporary payloads', async () => {
  const tracked = (await git('ls-files')).split('\n').filter(Boolean);
  for (const file of tracked) {
    assert.doesNotMatch(file, /(?:^|\/)(?:node_modules|target|dist|coverage)(?:\/|$)/);
    assert.doesNotMatch(file, /\.(?:class|log|tmp|tgz|zip)$/i);
  }
  const credentialPattern = [
    'g' + 'hp_', 'github' + '_pat_', 'AKIA' + '[0-9A-Z]{16}',
    '-----BEGIN ' + '(RSA |EC |OPENSSH )?PRIVATE KEY-----',
  ].join('|');
  let credentialMatches = '';
  try {
    credentialMatches = (await exec('git', ['grep', '-I', '-n', '-E', credentialPattern], { cwd: root })).stdout;
  } catch (error) {
    if (error.code !== 1) throw error;
  }
  assert.equal(credentialMatches, '', `credential-like content detected:\n${credentialMatches}`);
});

for (const [name, file, fields] of [
  ['M4-C governance record remains complete', 'docs/M4_SLA_AND_CALENDAR_GOVERNANCE.md', [
    'Scope', 'Tenant isolation', 'Calendar versioning', 'Policy immutability',
    'Transaction boundaries', 'Migration upgrade matrix', 'Artifact', 'SHA-256', 'M4-D handoff',
  ]],
  ['M4-D governance record is absent before acceptance or complete when present', 'docs/M4_SLA_EXECUTION_AND_REPLAY_GOVERNANCE.md', [
    'Scope', 'Intent lifecycle', 'Claim and lease', 'Retry', 'Dead', 'Replay',
    'Tenant isolation', 'Transaction boundaries', 'EXPLAIN', 'Artifact', 'SHA-256', 'M4-E/F handoff',
  ]],
]) {
  test(name, async () => {
    if (!await exists(file)) return;
    assert.ok((await stat(path.join(root, file))).size > 0);
    const content = await text(file);
    for (const field of fields) assert.match(content, new RegExp(field, 'i'));
  });
}
