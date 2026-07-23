import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';

const root = process.cwd();
const feasibilityPath = path.join(
  root,
  'docs/M5_PROCESS_INSTANCE_MIGRATION_FEASIBILITY.md',
);
const capabilityTestPath = path.join(
  root,
  'server-modules/approval-engine-flowable/src/test/java/io/github/akaryc1b/approval/engine/flowable/FlowableProcessInstanceMigrationCapabilityTest.java',
);
const migrationDirectory = path.join(
  root,
  'server-modules/approval-persistence-jdbc/src/main/resources/db/migration',
);
const workflowDirectory = path.join(root, '.github/workflows');

const frozenDocuments = new Map([
  ['docs/M3_FINAL_ACCEPTANCE.md', '459c684027e4a08f08655bff3e31721912dc35bc'],
  [
    'docs/M4_IDENTITY_AND_TENANT_GOVERNANCE.md',
    '716ecf6503aeaea7a6dbfa5980964a5c4b983619',
  ],
  [
    'docs/M4_AUTHORIZATION_AND_RESPONSIBILITY_GOVERNANCE.md',
    '888f07df905726cfb3507d2ae495db3247d6c4fe',
  ],
  [
    'docs/M4_SLA_AND_CALENDAR_GOVERNANCE.md',
    'beb098bc6b4ee68c6ca11da0678a76780b72a049',
  ],
  [
    'docs/M4_SLA_EXECUTION_AND_REPLAY_GOVERNANCE.md',
    'dc687d073e0352e0b88d96bd8df0f4ee36775b6e',
  ],
  [
    'docs/M4_PROCESS_RELEASE_AND_MIGRATION_ASSESSMENT_GOVERNANCE.md',
    '3c78cee75ed1ec3536fc8e26d440592e2038c6f2',
  ],
]);

async function text(file) {
  return readFile(file, 'utf8');
}

async function gitBlobSha(file) {
  const content = await readFile(file);
  const header = Buffer.from(`blob ${content.length}\0`, 'utf8');
  return createHash('sha1').update(header).update(content).digest('hex');
}

async function filesUnder(directory, extensions) {
  const result = [];
  async function visit(current) {
    for (const entry of await readdir(current, { withFileTypes: true })) {
      const next = path.join(current, entry.name);
      if (entry.isDirectory()) {
        await visit(next);
      } else if (extensions.has(path.extname(entry.name))) {
        result.push(next);
      }
    }
  }
  await visit(directory);
  return result;
}

test('M5-A remains capability validation only with a complete status matrix', async () => {
  const feasibility = await text(feasibilityPath);

  assert.match(feasibility, /M5-A STATUS: `CAPABILITY_VALIDATION_ONLY`/);
  assert.match(feasibility, /Overall conclusion: `SUPPORTED_WITH_LIMITATIONS`/);
  assert.match(feasibility, /Flowable dependency baseline: `org\.flowable:flowable-bom:8\.0\.0`/);
  assert.match(feasibility, /Current M5-A decision: `SUPPORTED_WITH_LIMITATIONS`/);

  for (let scenario = 1; scenario <= 28; scenario += 1) {
    assert.match(
      feasibility,
      new RegExp(`\\| ${scenario} \\|`),
      `M5-A capability matrix omits scenario ${scenario}`,
    );
  }

  for (const status of [
    'SUPPORTED',
    'SUPPORTED_WITH_LIMITATIONS',
    'UNSUPPORTED',
    'UNKNOWN_REQUIRES_MORE_EVIDENCE',
  ]) {
    assert.ok(
      feasibility.includes('`' + status + '`'),
      `M5-A capability matrix omits status ${status}`,
    );
  }

  for (const requiredAnswer of [
    'Does the official API support migration?',
    'Can it migrate multiple instances?',
    'Can it migrate one exact instance?',
    'Is there an official rollback?',
    'Is migration idempotent?',
    'What happens on duplicate invocation?',
    'How can a timeout be evaluated?',
    'How is activity mapping proven?',
    'How are task, variable, job, and identity-link losses detected?',
    'Does migration affect history?',
    'Can verification avoid internal tables?',
  ]) {
    assert.ok(feasibility.includes(requiredAnswer), `missing M5-A answer: ${requiredAnswer}`);
  }
});

test('Flowable migration capability code is isolated to test sources and public APIs', async () => {
  const capabilityTest = await text(capabilityTestPath);

  for (const publicType of [
    'ProcessMigrationService',
    'ProcessInstanceMigrationBuilder',
    'ProcessInstanceMigrationValidationResult',
    'ActivityMigrationMapping',
  ]) {
    assert.match(capabilityTest, new RegExp(`org\\.flowable.*${publicType}|${publicType}`));
  }
  for (const operation of [
    'validateMigration',
    'migrate(instance.getId())',
    'migrateToProcessDefinition',
    'addActivityMigrationMapping',
  ]) {
    assert.ok(capabilityTest.includes(operation), `capability test omits ${operation}`);
  }
  assert.doesNotMatch(capabilityTest, /ACT_[A-Z0-9_]+/);
  assert.doesNotMatch(capabilityTest, /org\.flowable\.(?:common\.)?engine\.impl|org\.flowable\.engine\.impl/);

  const productionRoots = [
    path.join(root, 'apps/server/src/main/java'),
    path.join(root, 'server-modules'),
  ];
  const forbiddenPublicMigrationUse = /\b(?:ProcessMigrationService|ProcessInstanceMigrationBuilder|ActivityMigrationMapping)\b/;
  for (const productionRoot of productionRoots) {
    const files = await filesUnder(productionRoot, new Set(['.java']));
    for (const file of files) {
      const normalized = file.split(path.sep).join('/');
      if (normalized.includes('/src/test/')) continue;
      assert.doesNotMatch(
        await text(file),
        forbiddenPublicMigrationUse,
        `${path.relative(root, file)} introduces production migration execution capability during M5-A`,
      );
    }
  }
});

test('M5-A introduces no V33, production worker, or execution surface', async () => {
  const migrationFiles = await readdir(migrationDirectory);
  assert.ok(
    migrationFiles.some((file) => /^V32__/.test(file)),
    'V32 migration baseline is missing',
  );
  assert.ok(
    migrationFiles.every((file) => !/^V(?:3[3-9]|[4-9][0-9])__/.test(file)),
    `M5-A must not introduce a migration after V32: ${migrationFiles.join(', ')}`,
  );

  const serverProductionFiles = await filesUnder(
    path.join(root, 'apps/server/src/main/java'),
    new Set(['.java']),
  );
  const moduleProductionFiles = (await filesUnder(
    path.join(root, 'server-modules'),
    new Set(['.java']),
  )).filter((file) => !file.split(path.sep).join('/').includes('/src/test/'));

  for (const file of [...serverProductionFiles, ...moduleProductionFiles]) {
    const relative = path.relative(root, file);
    assert.doesNotMatch(
      path.basename(file),
      /migration.*worker|worker.*migration/i,
      `${relative} introduces a migration worker during M5-A`,
    );
    const content = await text(file);
    assert.doesNotMatch(
      content,
      /class\s+\w*Migration\w*Worker|@Scheduled[\s\S]{0,500}migration/i,
      `${relative} introduces migration worker behavior during M5-A`,
    );
  }

  const apiFiles = await filesUnder(
    path.join(root, 'apps/server/src/main/java/io/github/akaryc1b/approval/api'),
    new Set(['.java']),
  );
  const clientFiles = [
    ...(await filesUnder(
      path.join(root, 'apps/web/overlay/apps/web-ele/src'),
      new Set(['.ts', '.vue']),
    )),
    ...(await filesUnder(
      path.join(root, 'apps/mobile/overlay/src'),
      new Set(['.ts', '.vue']),
    )),
  ];

  const forbiddenExecutionWords = /(?:execute|force|rollback|start|resume)[-_/]?(?:process[-_/]?)?migration|migration[-_/]?(?:execute|execution|force|rollback|start|resume)/i;
  for (const file of [...apiFiles, ...clientFiles]) {
    const content = await text(file);
    assert.doesNotMatch(
      content.replaceAll('migration-dry-run', ''),
      forbiddenExecutionWords,
      `${path.relative(root, file)} exposes a migration execution surface during M5-A`,
    );
  }
});

test('M5 uses the accepted automatic validation workflow and no second M5 workflow', async () => {
  const workflowFiles = (await readdir(workflowDirectory))
    .filter((file) => /\.ya?ml$/i.test(file))
    .sort();
  assert.ok(workflowFiles.includes('approval-platform-validation.yml'));

  const forbiddenWorkflowFiles = workflowFiles.filter((file) =>
    /(?:temporary|temp|pr\d+|m5[-_].*validation)/i.test(file));
  assert.deepEqual(forbiddenWorkflowFiles, []);

  for (const file of workflowFiles) {
    const workflow = await text(path.join(workflowDirectory, file));
    if (file === 'approval-platform-validation.yml') {
      assert.match(workflow, /^\s*pull_request:/m);
      assert.match(workflow, /^\s*push:/m);
      assert.match(workflow, /node --test scripts\/tests\/m5-migration-boundary\.test\.mjs/);
      assert.match(workflow, /m5-migration-boundary\.log/);
      continue;
    }
    assert.doesNotMatch(workflow, /^\s*(?:pull_request|push):/m);
    assert.doesNotMatch(workflow, /m5-migration-boundary|M5 migration capability/i);
  }
});

test('accepted M3 and M4 governance documents remain byte-for-byte frozen', async () => {
  for (const [relative, expectedSha] of frozenDocuments) {
    assert.equal(
      await gitBlobSha(path.join(root, relative)),
      expectedSha,
      `${relative} changed after formal acceptance`,
    );
  }
});
