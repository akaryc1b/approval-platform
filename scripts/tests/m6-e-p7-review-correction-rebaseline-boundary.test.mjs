import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

function source(relativePath) {
  const file = path.join(root, relativePath);
  assert.equal(existsSync(file), true, `${relativePath} must exist`);
  return readFileSync(file, 'utf8');
}

const acceptance = source(
  'docs/m6/M6_E_P7_REVIEW_CORRECTION_REBASELINE_ACCEPTANCE.md',
);
const pom = source('server-modules/approval-persistence-jdbc/pom.xml');
const workflow = source('.github/workflows/approval-platform-validation.yml');
const selector = source('scripts/ci/select-persistence-jdbc-tests.sh');
const verifier = source('scripts/ci/verify-persistence-jdbc-shards.py');
const decoder = source(
  'server-modules/approval-ai-openai/src/main/java/io/github/akaryc1b/approval/ai/openai/OpenAiResponsesResponseDecoder.java',
);
const context = source(
  'server-modules/approval-ai-core/src/main/java/io/github/akaryc1b/approval/ai/core/AiServerRequestContext.java',
);

test('P7 review correction rebaseline freezes exact main and Merge Commit ancestry', () => {
  assert.match(acceptance, /b20b5cca68bb6b77e7a51233bc2aee3387b21993/);
  assert.match(acceptance, /75470f61d4c363be03fdf490b47abe73f45cf804/);
  assert.match(acceptance, /783dbf30be49c69d7e694ec7ff87f3f7b4af0d85/);
  assert.match(acceptance, /PR #86/);
  assert.match(acceptance, /PR #87/);
  assert.match(acceptance, /ordinary Merge Commit/);
  assert.match(acceptance, /behind `0`/);
  assert.match(acceptance, /P7_REVIEW_CORRECTION_REBASELINE_PENDING_EXACT_VALIDATION/);
});

test('current-main sharded POM retains M6-E dependency and every CI control', () => {
  assert.match(pom, /<artifactId>approval-ai-core<\/artifactId>/);
  assert.match(
    pom,
    /<approval\.persistence\.test\.fork-count>4<\/approval\.persistence\.test\.fork-count>/,
  );
  assert.match(
    pom,
    /<approval\.persistence\.tests\.skip>false<\/approval\.persistence\.tests\.skip>/,
  );
  assert.match(pom, /<skipTests>\$\{approval\.persistence\.tests\.skip\}<\/skipTests>/);
  assert.match(pom, /<reuseForks>true<\/reuseForks>/);
  assert.match(pom, /<append>true<\/append>/);
});

test('permanent workflow shards Persistence JDBC and still emits four final artifacts', () => {
  assert.match(workflow, /name: Java 21 \/ Maven core/);
  assert.match(workflow, /name: Persistence JDBC \/ shard \$\{\{ matrix\.shard \}\}/);
  for (const shard of ['0', '1', '2', '3']) {
    assert.match(workflow, new RegExp(`\\n\\s+- ${shard}\\n`));
  }
  assert.match(workflow, /name: Java 21 \/ Maven \/ PostgreSQL/);
  assert.match(
    workflow,
    /actions\/upload-artifact\/merge@ea165f8d65b6e75b540449e92b4886f43607fa02 # v4/,
  );
  assert.match(workflow, /name: approval-maven-\$\{\{ github\.run_id \}\}/);
  assert.match(workflow, /name: approval-vben-\$\{\{ github\.run_id \}\}/);
  assert.match(workflow, /name: approval-mobile-\$\{\{ github\.run_id \}\}/);
  assert.match(workflow, /name: approval-hygiene-\$\{\{ github\.run_id \}\}/);
  assert.match(acceptance, /formal release evidence remains four final artifact groups/i);
});

test('sharding helpers remain deterministic exact and duplicate rejecting', () => {
  assert.match(selector, /checksum % shard_total == shard_index/);
  assert.match(selector, /selected-tests-\$\{shard_index\}\.txt|manifest_path/);
  assert.match(verifier, /expected_shards/);
  assert.match(verifier, /duplicate/i);
  assert.match(verifier, /Surefire/i);
  assert.match(verifier, /selected classes/i);
  assert.match(acceptance, /duplicate shard assignments: zero/i);
  assert.match(acceptance, /selected classes: `73`/i);
});

test('rebaseline retains both actionable production corrections', () => {
  assert.match(decoder, /String providerRequestId = exactText/);
  assert.match(decoder, /response\.transportEvidence\(\)[\s\S]*?clientRequestIdHash\(\)/);
  assert.doesNotMatch(
    decoder,
    /providerRequestIdHash\.equals\(expectations\.admittedRequestIdHash\(\)\)/,
  );
  assert.match(context, /tenantId = requireText\(tenantId, "tenantId", 128\)/);
  assert.match(acceptance, /PRRT_kwDOTbeZ186WReSu/);
  assert.match(acceptance, /PRRT_kwDOTbeZ186WReS2/);
  assert.match(acceptance, /threads remain unresolved until this documented Head/i);
});

test('rebaseline adds no migration workflow or M6-F product capability', () => {
  const migrationRoot = path.join(
    root,
    'server-modules/approval-persistence-jdbc/src/main/resources/db/migration',
  );
  const migrations = readdirSync(migrationRoot);
  assert.equal(migrations.filter(name => /^V49__/.test(name)).length, 1);
  assert.equal(migrations.some(name => /^V(?:5[0-9]|[6-9][0-9])__/.test(name)), false);

  const workflowRoot = path.join(root, '.github/workflows');
  const automatic = readdirSync(workflowRoot)
    .filter(name => /\.ya?ml$/.test(name))
    .filter(name => {
      const content = readFileSync(path.join(workflowRoot, name), 'utf8');
      return /^\s{0,4}(pull_request|push):\s*$/m.test(content);
    });
  assert.deepEqual(automatic, ['approval-platform-validation.yml']);

  assert.match(acceptance, /adds no Provider, model, Prompt, endpoint, Secret, retry, fallback/);
  assert.match(acceptance, /M6_F_REMAINS_GATED/);
  assert.match(acceptance, /AI_IS_NOT_AN_OPERATOR/);
});

test('permanent transport review loads the review correction rebaseline boundary', () => {
  const aggregator = source('scripts/tests/m6-ai-transport-review-boundary.test.mjs');
  assert.match(
    aggregator,
    /import '\.\/m6-e-p7-review-correction-rebaseline-boundary\.test\.mjs';/,
  );
});
