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

const taskContract = source(
  'server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/application/port/ApprovalTaskQuery.java',
);
const jdbcQuery = source(
  'server-modules/approval-persistence-jdbc/src/main/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcApprovalTaskQuery.java',
);
const jdbcTest = source(
  'server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcApprovalTaskQueryIntegrationTest.java',
);
const service = source(
  'apps/server/src/main/java/io/github/akaryc1b/approval/api/ApprovalAssistanceGenerationService.java',
);
const serviceTest = source(
  'apps/server/src/test/java/io/github/akaryc1b/approval/api/ApprovalAssistanceGenerationServiceTest.java',
);

test('pending task contract carries complete trusted release form and UI provenance', () => {
  for (const field of [
    'Integer releaseVersion',
    'String releasePackageHash',
    'Integer formPackageVersion',
    'String formPackageHash',
    'String formContentHash',
    'Integer uiSchemaVersion',
    'String uiSchemaHash',
    'String formSchemaVersion',
    'Integer formSchemaFieldCount',
  ]) {
    assert.match(taskContract, new RegExp(field));
  }
  assert.match(taskContract, /release provenance must be either complete or absent/);
  assert.match(taskContract, /form provenance must be either complete or absent/);
  assert.match(taskContract, /releaseVersion must be positive/);
  assert.match(taskContract, /formPackageVersion must be positive/);
  assert.match(taskContract, /uiSchemaVersion must be positive/);
  assert.match(taskContract, /formSchemaFieldCount must not be negative/);
  assert.match(
    taskContract,
    /null,\s*null,\s*null,\s*null,\s*null,\s*null,\s*null,\s*null,\s*null\s*\n\s*\);/,
  );
});

test('JDBC resolves the exact instance package before form and UI definitions', () => {
  assert.match(jdbcQuery, /left join ap_form_package form_package/);
  assert.match(
    jdbcQuery,
    /form_package\.package_version = instance\.form_package_version/,
  );
  assert.match(jdbcQuery, /form_package\.package_hash = instance\.form_package_hash/);
  assert.match(jdbcQuery, /form_package\.form_version = instance\.form_version/);
  assert.match(jdbcQuery, /form_definition\.content_hash = form_package\.form_hash/);
  assert.match(jdbcQuery, /ui_schema\.ui_schema_version = form_package\.ui_schema_version/);
  assert.match(jdbcQuery, /ui_schema\.content_hash = form_package\.ui_schema_hash/);
  assert.match(jdbcQuery, /form_package\.package_hash end as form_package_hash/);
  assert.match(jdbcQuery, /form_definition\.content_hash end as form_content_hash/);
  assert.match(jdbcQuery, /form_definition\.field_count end as form_schema_field_count/);
  assert.match(jdbcQuery, /instance\.release_version/);
  assert.match(jdbcQuery, /instance\.release_package_hash/);
  assert.equal(
    (jdbcQuery.match(/form_definition\.form_version is not null/g) || []).length >= 7,
    true,
  );
  assert.equal(
    (jdbcQuery.match(/ui_schema\.ui_schema_version is not null/g) || []).length >= 7,
    true,
  );
});

test('generation projects actual release form content and UI provenance fail closed', () => {
  const provenanceGate = service.indexOf('if (!hasTrustedSchemaProvenance(task))');
  const projection = service.indexOf('ApprovalAssistanceContextProjection projection = projection(');
  const preProviderRead = service.indexOf(
    'Optional<PendingTaskDetails> revalidated = taskQuery.findPendingTask(identity)',
  );
  const execution = service.indexOf('outcome = orchestrator.execute(request)');
  const postProviderRead = service.indexOf(
    'Optional<PendingTaskDetails> postInvocation = taskQuery.findPendingTask(identity)',
  );
  const evidence = service.indexOf(
    'evidence = ApprovalAssistanceProductionDurableEvidenceFactory.create',
  );
  const store = service.indexOf('stored = evidenceStore.store(evidence)');

  assert.ok(provenanceGate >= 0 && projection > provenanceGate);
  assert.ok(preProviderRead > projection && execution > preProviderRead);
  assert.ok(postProviderRead > execution && evidence > postProviderRead && store > evidence);
  assert.equal((service.match(/orchestrator\.execute\s*\(/g) || []).length, 1);
  assert.equal((service.match(/evidenceStore\.store\s*\(/g) || []).length, 1);

  assert.match(
    service,
    /task\.formKey\(\),\s*task\.formVersion\(\),\s*task\.releaseVersion\(\),\s*task\.releasePackageHash\(\)/,
  );
  assert.match(
    service,
    /task\.formKey\(\),\s*task\.formVersion\(\),\s*task\.formSchemaVersion\(\),\s*task\.formContentHash\(\),\s*task\.formSchemaFieldCount\(\),\s*task\.uiSchemaVersion\(\),\s*task\.uiSchemaHash\(\)/,
  );
  assert.doesNotMatch(service, /task\.definitionVersion\(\),\s*task\.contentHash\(\)\s*\n\s*\)/);
  assert.doesNotMatch(service, /"ui-v1"/);
  assert.match(service, /task\.formSchemaFieldCount\(\) >= 1/);
  assert.match(service, /task\.formSchemaFieldCount\(\) <= 500/);
});

test('all hashed evidence instants are normalized before construction and storage', () => {
  assert.match(service, /truncatedTo\(ChronoUnit\.MICROS\)/);
  assert.match(service, /Instant taskObservedAt = postgresTimestamp\(task\.taskUpdatedAt\(\)\)/);
  assert.match(service, /postgresTimestamp\(clock\.instant\(\)\)/);
  assert.match(service, /postgresTimestamp\(task\.taskUpdatedAt\(\)\)/);
  assert.match(service, /Instant recordedAt = latest\(postgresTimestamp\(clock\.instant\(\)\), requestedAt\)/);
  assert.match(service, /recordedAt\.plus\(EVIDENCE_RETENTION\)/);
  assert.match(serviceTest, /evidenceClockInstantsAreNormalizedToPostgresMicroseconds/);
  assert.match(serviceTest, /storedEvidenceUsesPostgresMicrosecondPrecision/);
  assert.match(serviceTest, /store\.lastEvidence\.requestedAt\(\)\.getNano\(\) % 1_000/);
  assert.match(serviceTest, /store\.lastEvidence\.recordedAt\(\)\.getNano\(\) % 1_000/);
  assert.match(serviceTest, /store\.lastEvidence\.retentionUntil\(\)\.getNano\(\) % 1_000/);
});

test('directed tests prove exact package chain and no fabricated release fallback', () => {
  assert.match(serviceTest, /missingTrustedSchemaProvenanceFailsBeforeRuntimeBinding/);
  assert.match(serviceTest, /projectionUsesExactTrustedReleaseFormAndUiProvenance/);
  assert.match(serviceTest, /FORM_PACKAGE_HASH/);
  assert.match(serviceTest, /FORM_CONTENT_HASH/);
  assert.match(serviceTest, /assertEquals\(RELEASE_VERSION, projection\.process\(\)\.releaseVersion\(\)\)/);
  assert.match(jdbcTest, /insert into ap_form_package/);
  assert.match(jdbcTest, /FORM_PACKAGE_HASH/);
  assert.match(jdbcTest, /FORM_CONTENT_HASH/);
  assert.match(jdbcTest, /assertNull\(pending\.releaseVersion\(\)\)/);
  assert.match(jdbcTest, /assertNull\(pending\.releasePackageHash\(\)\)/);
  assert.match(jdbcTest, /assertEquals\(FORM_CONTENT_HASH, pending\.formContentHash\(\)\)/);
});

test('correction preserves migration workflow and non-operator boundaries', () => {
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

  assert.doesNotMatch(service, /Queue|Worker|Scheduler|listener|polling/);
  assert.doesNotMatch(service, /approve\(|reject\(|transfer\(|withdraw\(|terminate\(/);
});
