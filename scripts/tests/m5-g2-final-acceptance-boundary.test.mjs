import assert from 'node:assert/strict';
import {
  existsSync,
  readFileSync,
  readdirSync,
  statSync,
} from 'node:fs';
import { join, relative } from 'node:path';
import test from 'node:test';

const root = process.cwd();
const read = path => readFileSync(join(root, path), 'utf8');

const application = read('apps/server/src/main/resources/application.yml');
const operationsController = read(
  'apps/server/src/main/java/io/github/akaryc1b/approval/api/'
    + 'ApprovalMigrationOperationsController.java',
);
const diagnosticsController = read(
  'apps/server/src/main/java/io/github/akaryc1b/approval/api/'
    + 'ApprovalMigrationDiagnosticsController.java',
);
const webApi = read(
  'apps/web/overlay/apps/web-ele/src/api/approval/process-instance-operations.ts',
);
const webView = read(
  'apps/web/overlay/apps/web-ele/src/views/approval/process-instance-operations/index.vue',
);
const mobileApi = read(
  'apps/mobile/overlay/src/api/approval/process-instance-operations.ts',
);
const mobileView = read(
  'apps/mobile/overlay/src/pages/operations/migrations.vue',
);
const workflow = read('.github/workflows/approval-platform-validation.yml');
const docsIndex = read('docs/README.md');
const finalEvidence = read('docs/M5_FINAL_PERMANENT_ACCEPTANCE_EVIDENCE.md');
const g1Runbook = read('docs/M5_G1_RELEASE_REHEARSAL_AND_PRODUCTION_READINESS.md');
const f2Hardening = read('docs/M5_F2_FAULT_SECURITY_OBSERVABILITY_HARDENING.md');
const metricCatalog = read('docs/M5_F2_OBSERVABILITY_METRIC_CATALOG.md');

const controllers = operationsController + '\n' + diagnosticsController;
const clients = [webApi, webView, mobileApi, mobileView].join('\n');

function walk(directory) {
  const result = [];
  for (const name of readdirSync(directory)) {
    const path = join(directory, name);
    if (statSync(path).isDirectory()) {
      result.push(...walk(path));
    } else {
      result.push(path);
    }
  }
  return result;
}

function m5ProductionJava() {
  const directories = [
    'apps/server/src/main/java',
    'server-modules/approval-application/src/main/java',
    'server-modules/approval-engine-flowable/src/main/java',
    'server-modules/approval-engine-spi/src/main/java',
    'server-modules/approval-persistence-jdbc/src/main/java',
  ];
  return directories.flatMap(directory => walk(join(root, directory)))
    .filter(path => path.endsWith('.java'))
    .filter(path => {
      const name = relative(root, path);
      return /Migration|migration/.test(name) || /migration/i.test(readFileSync(path, 'utf8'));
    });
}

function m5MigrationVersions() {
  const sqlDirectory = join(
    root,
    'server-modules/approval-persistence-jdbc/src/main/resources/db/migration',
  );
  const javaDirectory = join(
    root,
    'server-modules/approval-persistence-jdbc/src/main/java/db/migration',
  );
  return [...readdirSync(sqlDirectory), ...readdirSync(javaDirectory)]
    .map(name => name.match(/^V(\d+)__/))
    .filter(Boolean)
    .map(match => Number(match[1]));
}

test('G2 has a complete permanent M5 evidence lineage', () => {
  const requiredDocuments = [
    'docs/M5_D1_AUTHORIZED_PLAN_ADMISSION_EVIDENCE.md',
    'docs/M5_D2_GOVERNANCE_ACCEPTANCE.md',
    'docs/M5_D3_SINGLE_INSTANCE_EXECUTOR_PERMANENT_EVIDENCE.md',
    'docs/M5_D4_EXACT_VERIFICATION_PERMANENT_EVIDENCE.md',
    'docs/M5_D5_RUNTIME_BINDING_CAS_PERMANENT_EVIDENCE.md',
    'docs/M5_D6_DURABLE_UNKNOWN_RECONCILIATION_PERMANENT_EVIDENCE.md',
    'docs/M5_D7_CANARY_BOUNDED_ORCHESTRATION_PERMANENT_EVIDENCE.md',
    'docs/M5_D8_PLAN_LEVEL_AGGREGATION_PERMANENT_EVIDENCE.md',
    'docs/M5_E1_READ_ONLY_OPERATIONS_PERMANENT_EVIDENCE.md',
    'docs/M5_E2_ADVANCED_DIAGNOSTICS_PERMANENT_EVIDENCE.md',
    'docs/M5_F1_FAULT_SECURITY_OBSERVABILITY_PERMANENT_EVIDENCE.md',
    'docs/M5_F2_DEEP_HARDENING_PERMANENT_EVIDENCE.md',
    'docs/M5_G1_RELEASE_REHEARSAL_PERMANENT_EVIDENCE.md',
    'docs/M5_G1_RELEASE_REHEARSAL_AND_PRODUCTION_READINESS.md',
    'docs/M5_FINAL_PERMANENT_ACCEPTANCE_EVIDENCE.md',
  ];
  for (const document of requiredDocuments) {
    assert.ok(existsSync(join(root, document)), `missing M5 acceptance record ${document}`);
    const content = read(document);
    assert.match(content, /NOT_AUTHORIZED|PERMANENTLY_VALIDATED|Governance result|Acceptance/i);
  }
  assert.match(docsIndex, /M5_FINAL_PERMANENT_ACCEPTANCE_EVIDENCE\.md/);
  assert.match(docsIndex, /pre-merge final permanent acceptance/i);
});

test('G2 final evidence records exact regression artifacts and non-authorization', () => {
  assert.match(finalEvidence, /9c567a30e7430ee43d7b4ad418a70923edf5c360/);
  assert.match(finalEvidence, /Run ID: `30338254460`/);
  assert.match(finalEvidence, /Run number: `#864`/);
  assert.match(finalEvidence, /tests: `704`/);
  assert.match(finalEvidence, /tests: `138`/);
  for (const artifactId of ['8680189169', '8680036270', '8680019534', '8679998281']) {
    assert.match(finalEvidence, new RegExp(`\\b${artifactId}\\b`));
  }
  for (const digest of [
    '8dbb0a0a0c7f665ed56eed5f767cff49d5fb99dbfb6a353adbd87f8c63d64c84',
    '6c207ef2e36afa8e253c8a822c52e795080227909a8e610b3ca535f4d684328c',
    'b2e3695b2bfd00229eaa1cf5d3ee90aad6e750703c516427f5232415f149ab4b',
    'ddacc72c25945b90d3a6c1f2de97fcaa4ef57e1755209a25c6d0ee3a7b844f9d',
  ]) {
    assert.match(finalEvidence, new RegExp(digest));
  }
  assert.match(finalEvidence, /Production migration execution remains:[\s\S]*NOT_AUTHORIZED/);
  assert.match(finalEvidence, /no V49 exists/i);
  for (const head of [
    '4f59b12dff8b9988c4509b54fbbcb61046069fc9',
    '330dbdd035e436459ffdedf0d2b0c8e07dac7e6c',
    '72acb3ba18602c09c28bfe08b58f8b91e6efe6e4',
    '9d588215e869c8f1332c0bc1a2809fbd235c2efa',
  ]) {
    assert.match(finalEvidence, new RegExp(head));
  }
});

test('G2 retains all execution capabilities as explicit default-disabled one-shot gates', () => {
  for (const environmentName of [
    'APPROVAL_MIGRATION_EXECUTION_ENABLED',
    'APPROVAL_MIGRATION_WORKER_ENABLED',
    'APPROVAL_MIGRATION_ORCHESTRATION_ENABLED',
    'APPROVAL_MIGRATION_AGGREGATION_ENABLED',
    'APPROVAL_MIGRATION_RECONCILIATION_AUTOMATIC_ENABLED',
    'APPROVAL_MIGRATION_KILL_SWITCH_ENABLED',
  ]) {
    assert.match(application, new RegExp(`\\$\\{${environmentName}:false\\}`));
  }
  const production = m5ProductionJava().map(path => readFileSync(path, 'utf8')).join('\n');
  assert.doesNotMatch(
    production,
    /@Scheduled|ScheduledExecutorService|scheduleAtFixedRate|scheduleWithFixedDelay|while\s*\(true\)/,
  );
  assert.doesNotMatch(production, /scanAllTenants|findAllTenants|cross[- ]tenant scanner/i);
});

test('G2 Operations and client surfaces remain tenant-scoped GET-only and command-free', () => {
  assert.equal((controllers.match(/@GetMapping/g) ?? []).length, 7);
  assert.doesNotMatch(controllers, /@(?:Post|Put|Patch|Delete)Mapping|@RequestBody/);
  assert.match(controllers, /MIGRATION_OPERATIONS_READ/);
  assert.match(controllers, /ResourceScope\.TENANT/);
  assert.doesNotMatch(
    clients,
    /method:\s*['"](?:POST|PUT|PATCH|DELETE)['"]|approvalCommandHeaders|Idempotency-Key/i,
  );
  assert.doesNotMatch(
    clients,
    /function\s+(?:execute|retry|rollback|forceSuccess|startReconciliation|setKillSwitch|setFeatureFlag)/i,
  );
  assert.doesNotMatch(
    clients,
    /localStorage|sessionStorage|uni\.setStorage|uni\.setStorageSync/,
  );
  assert.doesNotMatch(
    clients,
    /@click="[^"]*(?:execute|retry|rollback|force|reconcile|killSwitch|featureFlag)[^"]*"/i,
  );
});

test('G2 repository contains no direct Flowable table access or unauthorized V49', () => {
  const productionPaths = m5ProductionJava();
  for (const path of productionPaths) {
    const content = readFileSync(path, 'utf8');
    assert.doesNotMatch(
      content,
      /\bACT_[A-Z0-9_]+\b/,
      `direct Flowable table reference in ${relative(root, path)}`,
    );
  }
  const versions = [...new Set(m5MigrationVersions())].sort((left, right) => left - right);
  for (let version = 33; version <= 48; version++) {
    assert.ok(versions.includes(version), `missing M5 migration V${version}`);
  }
  assert.equal(Math.max(...versions), 48);
  assert.ok(versions.every(version => version <= 48));
});

test('G2 fault security observability and release readiness records remain complete', () => {
  assert.equal((f2Hardening.match(/^\| \d+ \|/gm) ?? []).length, 48);
  assert.match(metricCatalog, /approval\.migration\.operations\.read/);
  assert.match(metricCatalog, /approval\.migration\.operations\.read\.latency/);
  assert.match(metricCatalog, /approval\.migration\.safety\.event/);
  assert.match(metricCatalog, /approval\.migration\.safety\.feature\.enabled/);
  assert.equal(
    (g1Runbook.slice(
      g1Runbook.indexOf('## 10. Dry-run release rehearsal matrix'),
      g1Runbook.indexOf('## 11. Production readiness checklist'),
    ).match(/^\| \d+ \|/gm) ?? []).length,
    18,
  );
  assert.equal(
    (g1Runbook.slice(
      g1Runbook.indexOf('## 12. Operator scenarios'),
      g1Runbook.indexOf('## 13. G1 acceptance decision'),
    ).match(/^\| \d+ \|/gm) ?? []).length,
    14,
  );
  assert.equal((g1Runbook.match(/^- \[ \] `/gm) ?? []).length, 23);
});

test('G2 keeps one read-only permanent workflow and executes every M5 final boundary', () => {
  assert.equal((workflow.match(/pull_request:/g) ?? []).length, 1);
  assert.equal((workflow.match(/push:/g) ?? []).length, 1);
  assert.match(workflow, /permissions:\s*\n\s*contents: read/);
  assert.doesNotMatch(workflow, /contents: write|pull-requests: write/);
  const expectedBoundaries = [
    'm5-d3-engine-execution-boundary.test.mjs',
    'm5-d4-exact-verification-boundary.test.mjs',
    'm5-d5-runtime-binding-cas-boundary.test.mjs',
    'm5-d6-durable-unknown-reconciliation-boundary.test.mjs',
    'm5-d7-canary-orchestration-boundary.test.mjs',
    'm5-d8-plan-aggregation-boundary.test.mjs',
    'm5-e1-operations-visibility-boundary.test.mjs',
    'm5-e2-advanced-diagnostics-boundary.test.mjs',
    'm5-f1-fault-security-observability-boundary.test.mjs',
    'm5-f2-deep-hardening-boundary.test.mjs',
    'm5-g1-production-readiness-boundary.test.mjs',
    'm5-g2-final-acceptance-boundary.test.mjs',
  ];
  for (const boundary of expectedBoundaries) {
    assert.match(workflow, new RegExp(boundary.replaceAll('.', '\\.')));
  }
});

test('G2 explicitly preserves non-authorization and independent M6 scope', () => {
  assert.match(g1Runbook, /Production migration execution: `NOT_AUTHORIZED`/);
  assert.match(g1Runbook, /Real production migration: `NOT_PERFORMED`/);
  assert.match(g1Runbook, /Production credentials\/endpoints: `ABSENT`/);
  assert.match(f2Hardening, /production execution is `NOT_AUTHORIZED`/i);
  assert.doesNotMatch(
    [operationsController, diagnosticsController, webApi, mobileApi].join('\n'),
    /M6[-_/]|docs\/m6|agent\/m6/i,
  );
});
