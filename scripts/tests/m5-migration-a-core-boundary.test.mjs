import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';

const root = process.cwd();
const feasibilityPath = path.join(root, 'docs/M5_PROCESS_INSTANCE_MIGRATION_FEASIBILITY.md');
const workflowDirectory = path.join(root, '.github/workflows');
const frozenDocuments = new Map([
  ['docs/M3_FINAL_ACCEPTANCE.md', '459c684027e4a08f08655bff3e31721912dc35bc'],
  ['docs/M4_IDENTITY_AND_TENANT_GOVERNANCE.md', '716ecf6503aeaea7a6dbfa5980964a5c4b983619'],
  ['docs/M4_AUTHORIZATION_AND_RESPONSIBILITY_GOVERNANCE.md', '888f07df905726cfb3507d2ae495db3247d6c4fe'],
  ['docs/M4_SLA_AND_CALENDAR_GOVERNANCE.md', 'beb098bc6b4ee68c6ca11da0678a76780b72a049'],
  ['docs/M4_SLA_EXECUTION_AND_REPLAY_GOVERNANCE.md', 'dc687d073e0352e0b88d96bd8df0f4ee36775b6e'],
  ['docs/M4_PROCESS_RELEASE_AND_MIGRATION_ASSESSMENT_GOVERNANCE.md', '3c78cee75ed1ec3536fc8e26d440592e2038c6f2'],
]);

async function text(file) { return readFile(file, 'utf8'); }
async function gitBlobSha(file) {
  const content = await readFile(file);
  return createHash('sha1').update(Buffer.from(`blob ${content.length}\0`)).update(content).digest('hex');
}
async function filesUnder(directory, extensions) {
  const result = [];
  async function visit(current) {
    for (const entry of await readdir(current, { withFileTypes: true })) {
      const next = path.join(current, entry.name);
      if (entry.isDirectory()) await visit(next);
      else if (extensions.has(path.extname(entry.name))) result.push(next);
    }
  }
  await visit(directory);
  return result;
}

test('accepted M5-A feasibility remains complete and frozen as the M5-B basis', async () => {
  const feasibility = await text(feasibilityPath);
  assert.match(feasibility, /M5-A STATUS: `CAPABILITY_VALIDATION_ONLY`/);
  assert.match(feasibility, /M5-A EVIDENCE GATE: `COMPLETE_PENDING_EXPLICIT_ACCEPTANCE`/);
  assert.match(feasibility, /Overall conclusion: `SUPPORTED_WITH_LIMITATIONS`/);
  assert.match(feasibility, /Flowable dependency baseline: `org\.flowable:flowable-bom:8\.0\.0`/);
  for (let scenario = 1; scenario <= 28; scenario += 1) {
    assert.match(feasibility, new RegExp(`\\| ${scenario} \\|`));
  }
  for (const scenario of [6, 11, 12, 19, 21, 25]) {
    assert.match(feasibility, new RegExp('\\| ' + scenario + ' \\|[^\\n]*\\| `UNSUPPORTED` \\|'));
  }
  assert.match(feasibility, /\| 26 \|[^\n]*\| `UNKNOWN_REQUIRES_MORE_EVIDENCE` \|/);
});

test('production code still cannot invoke Flowable migration or internal tables', async () => {
  const files = (await filesUnder(path.join(root, 'server-modules'), new Set(['.java'])))
    .filter((file) => !file.split(path.sep).join('/').includes('/src/test/'));
  for (const file of files) {
    const content = await text(file);
    assert.doesNotMatch(content, /ACT_[A-Z0-9_]+/);
    assert.doesNotMatch(content, /org\.flowable\.(?:common\.)?engine\.impl|org\.flowable\.engine\.impl/);
    assert.doesNotMatch(content, /\b(?:ProcessMigrationService|ProcessInstanceMigrationBuilder|ActivityMigrationMapping)\b/);
    assert.doesNotMatch(path.basename(file), /migration.*worker|worker.*migration/i);
  }
});

test('M5 retains one permanent automatic workflow', async () => {
  const files = (await readdir(workflowDirectory)).filter((file) => /\.ya?ml$/i.test(file)).sort();
  assert.ok(files.includes('approval-platform-validation.yml'));
  assert.deepEqual(files.filter((file) => /(?:temporary|temp|pr\d+|m5[-_].*validation)/i.test(file)), []);
  for (const file of files) {
    const workflow = await text(path.join(workflowDirectory, file));
    if (file === 'approval-platform-validation.yml') {
      assert.match(workflow, /^\s*pull_request:/m);
      assert.match(workflow, /^\s*push:/m);
      assert.match(workflow, /m5-migration-boundary\.test\.mjs/);
    } else {
      assert.doesNotMatch(workflow, /^\s*(?:pull_request|push):/m);
    }
  }
});

test('accepted M3 and M4 governance documents remain byte-for-byte frozen', async () => {
  for (const [relative, expected] of frozenDocuments) {
    assert.equal(await gitBlobSha(path.join(root, relative)), expected, `${relative} changed`);
  }
});
