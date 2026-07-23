import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { access, readdir, readFile, stat } from 'node:fs/promises';
import path from 'node:path';
import { promisify } from 'node:util';
import test from 'node:test';

const exec = promisify(execFile);
const root = process.cwd();
const migrationsRoot = 'server-modules/approval-persistence-jdbc/src/main/resources/db/migration';
const clientRoots = [
  'apps/web/overlay/apps/web-ele/src',
  'apps/mobile/overlay/src',
];
const textExtensions = new Set(['.css', '.html', '.java', '.js', '.json', '.mjs', '.sql', '.ts', '.tsx', '.vue', '.xml', '.yaml', '.yml']);

async function text(relativePath) {
  return readFile(path.join(root, relativePath), 'utf8');
}

async function exists(relativePath) {
  try {
    await access(path.join(root, relativePath));
    return true;
  } catch {
    return false;
  }
}

async function filesUnder(relativePath, acceptedExtensions = null) {
  const result = [];
  async function visit(current) {
    for (const entry of await readdir(path.join(root, current), { withFileTypes: true })) {
      const next = path.join(current, entry.name);
      if (entry.isDirectory()) {
        await visit(next);
      } else if (!acceptedExtensions || acceptedExtensions.has(path.extname(entry.name))) {
        result.push(next);
      }
    }
  }
  await visit(relativePath);
  return result;
}

async function git(...args) {
  return (await exec('git', args, { cwd: root })).stdout.trim();
}

test('M4 migrations are contiguous through V32', async () => {
  const migrationFiles = (await readdir(path.join(root, migrationsRoot)))
    .filter((name) => /^V\d+__.*\.sql$/.test(name));
  const versions = migrationFiles
    .map((name) => Number(name.match(/^V(\d+)__/)?.[1]))
    .sort((left, right) => left - right);

  assert.equal(Math.max(...versions), 32, 'highest Flyway migration must be V32 in M4-E');
  assert.ok(migrationFiles.includes('V28__create_versioned_work_calendars.sql'));
  assert.ok(migrationFiles.includes('V29__create_immutable_sla_policies.sql'));
  assert.ok(migrationFiles.includes('V30__create_sla_instances_and_responsibility_history.sql'));
  assert.ok(migrationFiles.includes('V31__create_sla_execution_intents_attempts_and_replay.sql'));
  assert.ok(migrationFiles.includes('V32__create_process_release_lifecycle_and_runtime_binding.sql'));
});

test('V32 establishes immutable tenant-scoped release lifecycle and runtime binding evidence', async () => {
  const migration = await text(
    `${migrationsRoot}/V32__create_process_release_lifecycle_and_runtime_binding.sql`,
  );
  for (const table of [
    'ap_process_release_lifecycle',
    'ap_process_release_lifecycle_history',
    'ap_process_runtime_binding',
  ]) {
    assert.match(migration, new RegExp(`create table ${table}\\b`));
  }
  assert.match(migration, /lifecycle_state in \('PUBLISHED', 'ACTIVE', 'DEPRECATED', 'RETIRED'\)/);
  assert.match(migration, /create unique index uk_process_release_single_active[\s\S]*where lifecycle_state = 'ACTIVE'/);
  assert.match(migration, /foreign key \([\s\n]*tenant_id, definition_key, release_version, release_package_hash/);
  assert.match(migration, /trg_process_release_lifecycle_guard/);
  assert.match(migration, /trg_process_release_history_append_only/);
  assert.match(migration, /trg_process_runtime_binding_immutable/);
  assert.match(migration, /trg_approval_release_package_immutable/);
  assert.doesNotMatch(migration, /\b(?:ACT_[A-Z0-9_]+|act_[a-z0-9_]+)\b/);
});

test('only permanent workflows remain and the M4 boundary is wired permanently', async () => {
  const workflows = await readdir(path.join(root, '.github/workflows'));
  assert.ok(workflows.includes('approval-platform-validation.yml'));
  for (const workflow of workflows) {
    assert.doesNotMatch(workflow, /(?:temporary|temp|pr\d+|m4[-_].*validation)/i);
  }
  const validation = await text('.github/workflows/approval-platform-validation.yml');
  assert.match(validation, /node --test scripts\/tests\/m4-sla-calendar-boundary\.test\.mjs/);
});

test('browser and mobile never manufacture trusted tenant operator permission or worker identity', async () => {
  const forbidden = [
    'X-Tenant-Id',
    'X-Operator-Id',
    'X-Approval-Trusted-Permissions',
    'X-Approval-Worker-Id',
  ];
  const violations = [];
  for (const clientRoot of clientRoots) {
    for (const file of await filesUnder(clientRoot, textExtensions)) {
      const content = await text(file);
      for (const header of forbidden) {
        if (content.includes(header)) {
          violations.push(`${file}: ${header}`);
        }
      }
    }
  }
  assert.deepEqual(violations, [], `trusted client identity remains:\n${violations.join('\n')}`);
});

test('client sources display server SLA evidence but never manufacture authoritative dueAt', async () => {
  const webClient = await text('apps/web/overlay/apps/web-ele/src/api/approval/sla.ts');
  const mobileClient = await text('apps/mobile/overlay/src/api/approval/sla.ts');
  const mobileDetail = await text('apps/mobile/overlay/src/pages/task/detail.vue');

  assert.match(webClient, /dueAt: string/);
  assert.match(mobileClient, /dueAt: string/);
  assert.match(mobileDetail, /taskSla\.dueAt/);
  for (const content of [webClient, mobileClient, mobileDetail]) {
    assert.doesNotMatch(content, /dueAt\s*[:=]\s*(?:new Date|Date\.now|add|plus)/i);
    assert.doesNotMatch(content, /JSON\.stringify\([\s\S]{0,600}\bdueAt\b/);
  }
});

test('client replay requests never nominate tenant worker or arbitrary target identity', async () => {
  const forbiddenReplayPayload = /(?:tenantId|workerId|leaseOwner)\s*:/i;
  for (const clientRoot of clientRoots) {
    for (const file of await filesUnder(clientRoot, textExtensions)) {
      const content = await text(file);
      const replayRequestPayloads = [
        ...content.matchAll(/\/replay\b[\s\S]{0,1200}?JSON\.stringify\(([^)]*)\)/gi),
        ...content.matchAll(/JSON\.stringify\(([^)]*)\)[\s\S]{0,1200}?\/replay\b/gi),
        ...content.matchAll(/\/replay\b[\s\S]{0,1200}?new URLSearchParams\(([^)]*)\)/gi),
        ...content.matchAll(/new URLSearchParams\(([^)]*)\)[\s\S]{0,1200}?\/replay\b/gi),
      ];
      for (const match of replayRequestPayloads) {
        assert.doesNotMatch(
          match[1] ?? match[0],
          forbiddenReplayPayload,
          `${file} replay request must use principal tenant and server worker identity`,
        );
      }
    }
  }
});

test('SLA management controllers remain capability governed and principal-scoped', async () => {
  const identityFilter = await text(
    'apps/server/src/main/java/io/github/akaryc1b/approval/security/ApprovalIdentityContextFilter.java',
  );
  assert.match(identityFilter, /return principal\.tenantId\(\)/);
  assert.match(identityFilter, /return principal\.operatorId\(\)/);
  assert.match(identityFilter, /new TrustedApprovalRequest/);

  const controllers = [
    'ApprovalCalendarManagementController.java',
    'ApprovalSlaPolicyManagementController.java',
    'ApprovalSlaInstanceManagementController.java',
  ];
  if (await exists('apps/server/src/main/java/io/github/akaryc1b/approval/api/ApprovalSlaExecutionManagementController.java')) {
    controllers.push('ApprovalSlaExecutionManagementController.java');
  }
  for (const name of controllers) {
    const content = await text(`apps/server/src/main/java/io/github/akaryc1b/approval/api/${name}`);
    const mappings = [...content.matchAll(/@(Get|Post|Put|Delete|Patch)Mapping\b/g)].length;
    const permissions = [...content.matchAll(/@ApprovalManagementPermission\b/g)].length;
    assert.ok(mappings > 0, `${name} must expose management mappings`);
    assert.ok(permissions >= mappings, `${name} mappings must declare capabilities`);
    assert.doesNotMatch(content, /@RequestParam[^\n]*(?:tenantId|operatorId)/i);
    assert.doesNotMatch(content, /@RequestBody[^\n]*(?:tenantId|operatorId)/i);
  }
  const capability = await text(
    'apps/server/src/main/java/io/github/akaryc1b/approval/api/ApprovalManagementPermission.java',
  );
  for (const required of ['SLA_READ', 'SLA_DESIGN', 'SLA_PUBLISH', 'SLA_ACTIVATE']) {
    assert.match(capability, new RegExp(`\\b${required}\\b`));
  }
});

test('participant SLA endpoint cannot nominate another tenant or user', async () => {
  const controller = await text(
    'apps/server/src/main/java/io/github/akaryc1b/approval/api/ApprovalParticipantSlaController.java',
  );
  const mobileClient = await text('apps/mobile/overlay/src/api/approval/sla.ts');

  assert.doesNotMatch(controller, /@RequestParam/);
  assert.doesNotMatch(controller, /X-User-Id|X-Act-As|X-Trusted-User/i);
  assert.doesNotMatch(mobileClient, /[?&](?:userId|tenantId)=/);
  assert.doesNotMatch(mobileClient, /\/approval\/management\//);
  assert.match(mobileClient, /allowNotFound: true/);
});

test('production sources remain independent from Flowable internal tables', async () => {
  const roots = ['apps', 'server-modules', 'integrations', 'examples'];
  const internalTable = /\b(?:ACT_[A-Z0-9_]+|act_[a-z0-9_]+)\b/;
  for (const sourceRoot of roots) {
    for (const file of await filesUnder(sourceRoot)) {
      const normalized = file.split(path.sep).join('/');
      if (!normalized.includes('/src/main/')) continue;
      if (!/\.(?:java|sql|xml|ya?ml)$/.test(normalized)) continue;
      assert.doesNotMatch(await text(file), internalTable, `${normalized} references a Flowable internal table`);
    }
  }
});

test('accepted governance documents remain byte-for-byte frozen', async () => {
  const frozen = new Map([
    ['docs/M3_FINAL_ACCEPTANCE.md', '459c684027e4a08f08655bff3e31721912dc35bc'],
    ['docs/M4_IDENTITY_AND_TENANT_GOVERNANCE.md', '716ecf6503aeaea7a6dbfa5980964a5c4b983619'],
    ['docs/M4_AUTHORIZATION_AND_RESPONSIBILITY_GOVERNANCE.md', '888f07df905726cfb3507d2ae495db3247d6c4fe'],
    ['docs/M4_SLA_AND_CALENDAR_GOVERNANCE.md', 'beb098bc6b4ee68c6ca11da0678a76780b72a049'],
    ['docs/M4_SLA_EXECUTION_AND_REPLAY_GOVERNANCE.md', 'dc687d073e0352e0b88d96bd8df0f4ee36775b6e'],
  ]);
  for (const [file, expected] of frozen) {
    assert.equal(await git('hash-object', file), expected, `${file} is frozen`);
  }
});

test('tracked tree excludes generated artifacts credentials and temporary payloads', async () => {
  const tracked = (await git('ls-files')).split('\n').filter(Boolean);
  for (const file of tracked) {
    assert.doesNotMatch(file, /(?:^|\/)(?:node_modules|target|dist|coverage)(?:\/|$)/);
    assert.doesNotMatch(file, /\.(?:class|log|tmp|tgz|zip)$/i);
  }

  const credentialPattern = [
    'g' + 'hp_',
    'github' + '_pat_',
    'AKIA' + '[0-9A-Z]{16}',
    '-----BEGIN ' + '(RSA |EC |OPENSSH )?PRIVATE KEY-----',
  ].join('|');
  let credentialMatches = '';
  try {
    credentialMatches = (await exec('git', [
      'grep', '-I', '-n', '-E', credentialPattern,
    ], { cwd: root })).stdout;
  } catch (error) {
    if (error.code !== 1) throw error;
  }
  assert.equal(credentialMatches, '', `credential-like content detected:\n${credentialMatches}`);
});

test('M4-C governance record remains complete', async () => {
  const governance = 'docs/M4_SLA_AND_CALENDAR_GOVERNANCE.md';
  assert.ok(await exists(governance));
  assert.ok((await stat(path.join(root, governance))).size > 0);
  const content = await text(governance);
  for (const field of [
    'Scope',
    'Tenant isolation',
    'Calendar versioning',
    'Policy immutability',
    'Transaction boundaries',
    'Migration upgrade matrix',
    'Artifact',
    'SHA-256',
    'M4-D handoff',
  ]) {
    assert.match(content, new RegExp(field, 'i'));
  }
});

test('M4-D governance record is absent before acceptance or complete when present', async () => {
  const governance = 'docs/M4_SLA_EXECUTION_AND_REPLAY_GOVERNANCE.md';
  if (!await exists(governance)) return;
  assert.ok((await stat(path.join(root, governance))).size > 0);
  const content = await text(governance);
  for (const field of [
    'Scope',
    'Intent lifecycle',
    'Claim and lease',
    'Retry',
    'Dead',
    'Replay',
    'Tenant isolation',
    'Transaction boundaries',
    'EXPLAIN',
    'Artifact',
    'SHA-256',
    'M4-E/F handoff',
  ]) {
    assert.match(content, new RegExp(field, 'i'));
  }
});
