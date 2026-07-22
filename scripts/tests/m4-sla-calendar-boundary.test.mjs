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

test('M4 calendar and SLA migrations are contiguous and stop at V30', async () => {
  const migrationFiles = (await readdir(path.join(root, migrationsRoot)))
    .filter((name) => /^V\d+__.*\.sql$/.test(name));
  const versions = migrationFiles
    .map((name) => Number(name.match(/^V(\d+)__/)?.[1]))
    .sort((left, right) => left - right);

  assert.equal(Math.max(...versions), 30, 'highest Flyway migration must be V30 before M4-D');
  assert.ok(migrationFiles.includes('V28__create_versioned_work_calendars.sql'));
  assert.ok(migrationFiles.includes('V29__create_immutable_sla_policies.sql'));
  assert.ok(migrationFiles.includes('V30__create_sla_instances_and_responsibility_history.sql'));
});

test('only permanent workflows remain and M4-C boundary is wired permanently', async () => {
  const workflows = await readdir(path.join(root, '.github/workflows'));
  assert.ok(workflows.includes('approval-platform-validation.yml'));
  for (const workflow of workflows) {
    assert.doesNotMatch(workflow, /(?:temporary|temp|pr\d+|m4[-_].*validation)/i);
  }
  const validation = await text('.github/workflows/approval-platform-validation.yml');
  assert.match(validation, /node --test scripts\/tests\/m4-sla-calendar-boundary\.test\.mjs/);
});

test('browser and mobile never manufacture trusted tenant operator or permission identity', async () => {
  const forbidden = [
    'X-Tenant-Id',
    'X-Operator-Id',
    'X-Approval-Trusted-Permissions',
  ];
  for (const clientRoot of clientRoots) {
    for (const file of await filesUnder(clientRoot, textExtensions)) {
      const content = await text(file);
      for (const header of forbidden) {
        assert.equal(content.includes(header), false, `${file} must not send ${header}`);
      }
    }
  }
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
  try {
    const result = await exec('git', [
      'grep', '-I', '-n', '-E',
      'ghp_|github_pat_|AKIA[0-9A-Z]{16}|-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----',
    ], { cwd: root });
    assert.fail(`credential-like content detected:\n${result.stdout}`);
  } catch (error) {
    assert.equal(error.code, 1, `credential scan failed unexpectedly: ${error.message}`);
  }
});

test('M4-C governance record is absent before acceptance or complete when present', async () => {
  const governance = 'docs/M4_SLA_AND_CALENDAR_GOVERNANCE.md';
  if (!await exists(governance)) return;
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
