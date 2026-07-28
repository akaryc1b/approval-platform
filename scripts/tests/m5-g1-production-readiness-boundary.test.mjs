import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';

const root = process.cwd();
const read = path => readFileSync(join(root, path), 'utf8');

const application = read('apps/server/src/main/resources/application.yml');
const executionConfiguration = read(
  'apps/server/src/main/java/io/github/akaryc1b/approval/config/'
    + 'ApprovalMigrationExecutionConfiguration.java',
);
const aggregationConfiguration = read(
  'apps/server/src/main/java/io/github/akaryc1b/approval/config/'
    + 'ApprovalMigrationPlanAggregationConfiguration.java',
);
const executor = read(
  'server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/application/'
    + 'ApprovalMigrationSingleInstanceExecutor.java',
);
const reconciliation = read(
  'server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/application/'
    + 'ApprovalMigrationReconciliationService.java',
);
const orchestration = read(
  'server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/application/'
    + 'ApprovalMigrationBoundedOrchestrationService.java',
);
const aggregation = read(
  'server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/application/'
    + 'ApprovalMigrationPlanAggregationService.java',
);
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
const upgrade = read(
  'server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/'
    + 'persistence/jdbc/JdbcApprovalMigrationUpgradeIntegrationTest.java',
);
const workflow = read('.github/workflows/approval-platform-validation.yml');
const runbook = read('docs/M5_G1_RELEASE_REHEARSAL_AND_PRODUCTION_READINESS.md');

const coreProduction = [
  executionConfiguration,
  aggregationConfiguration,
  executor,
  reconciliation,
  orchestration,
  aggregation,
].join('\n');
const controllers = [operationsController, diagnosticsController].join('\n');
const clients = [webApi, webView, mobileApi, mobileView].join('\n');

function m5MigrationVersions() {
  const sqlDirectory = join(
    root,
    'server-modules/approval-persistence-jdbc/src/main/resources/db/migration',
  );
  const javaDirectory = join(
    root,
    'server-modules/approval-persistence-jdbc/src/main/java/db/migration',
  );
  const names = [
    ...readdirSync(sqlDirectory),
    ...readdirSync(javaDirectory),
  ];
  return names
    .map(name => name.match(/^V(\d+)__/))
    .filter(Boolean)
    .map(match => Number(match[1]));
}

test('G1 retains every executable M5 feature as default disabled', () => {
  const defaults = [
    'APPROVAL_MIGRATION_EXECUTION_ENABLED',
    'APPROVAL_MIGRATION_WORKER_ENABLED',
    'APPROVAL_MIGRATION_ORCHESTRATION_ENABLED',
    'APPROVAL_MIGRATION_AGGREGATION_ENABLED',
    'APPROVAL_MIGRATION_RECONCILIATION_AUTOMATIC_ENABLED',
    'APPROVAL_MIGRATION_KILL_SWITCH_ENABLED',
  ];
  for (const environmentName of defaults) {
    assert.match(application, new RegExp(`\\$\\{${environmentName}:false\\}`));
  }
  assert.match(executor, /one-shot gate/i);
  assert.match(reconciliation, /There is no polling loop, scheduler or migration redispatch/);
  assert.match(orchestration, /Default-disabled gate/);
  assert.match(aggregation, /Default-disabled internal one-shot gate/);
  assert.doesNotMatch(
    coreProduction,
    /@Scheduled|ScheduledExecutorService|scheduleAtFixedRate|scheduleWithFixedDelay|while\s*\(true\)/,
  );
  assert.doesNotMatch(coreProduction, /cross[- ]tenant scanner|scanAllTenants|findAllTenants/i);
});

test('G1 keeps Operations APIs and clients read-only and non-persistent', () => {
  assert.equal((controllers.match(/@GetMapping/g) ?? []).length, 7);
  assert.doesNotMatch(controllers, /@(?:Post|Put|Patch|Delete)Mapping|@RequestBody/);
  assert.match(controllers, /MIGRATION_OPERATIONS_READ/);
  assert.match(controllers, /ResourceScope\.TENANT/);
  for (const api of [webApi, mobileApi]) {
    assert.match(api, /process-instance-operations/);
    assert.doesNotMatch(api, /method:\s*['"](?:POST|PUT|PATCH|DELETE)['"]/i);
    assert.doesNotMatch(api, /approvalCommandHeaders|Idempotency-Key/);
    assert.doesNotMatch(
      api,
      /function\s+(?:execute|retry|rollback|forceSuccess|startReconciliation|setKillSwitch|setFeatureFlag)/i,
    );
  }
  assert.doesNotMatch(
    clients,
    /localStorage|sessionStorage|uni\.setStorage|uni\.setStorageSync/,
  );
  assert.doesNotMatch(
    clients,
    /@click="[^"]*(?:execute|retry|rollback|force|reconcile|killSwitch|featureFlag)[^"]*"/i,
  );
  assert.match(webView, /只读/);
  assert.match(mobileView, /只读/);
});

test('G1 verifies clean and historical Flyway migration to exactly V48', () => {
  const versions = m5MigrationVersions();
  const unique = [...new Set(versions)].sort((left, right) => left - right);
  assert.equal(Math.max(...unique), 48);
  assert.ok(unique.every(version => version <= 48));
  for (let version = 33; version <= 48; version++) {
    assert.ok(unique.includes(version), `missing M5-owned Flyway migration V${version}`);
  }
  assert.match(upgrade, /LATEST_VERSION = "48"/);
  assert.match(upgrade, /new UpgradeCase\("approval_latest_fresh", null\)/);
  for (const version of ['1', '13', '23', '31', '36', '37', '38', '39', '40', '41', '42', '43', '44', '45', '46', '47']) {
    assert.match(upgrade, new RegExp(`new UpgradeCase\\("approval_latest_v${version}", "${version}"\\)`));
  }
  assert.match(upgrade, /freshAndHistoricalUpgradePathsReachV48WithoutExecutionSideEffects/);
  assert.match(upgrade, /upgradesV27WithFiveThousandInstancesAndTasksWithoutChangingEvidence/);
  assert.match(upgrade, /assertNoExecutionSideEffects/);
  assert.match(upgrade, /assertD6Empty\(jdbc\)/);
  assert.match(upgrade, /assertD7Empty\(jdbc\)/);
  assert.match(upgrade, /assertD8Empty\(jdbc\)/);
});

test('G1 repository and production-authority boundaries remain closed', () => {
  const combined = [coreProduction, controllers, clients, runbook].join('\n');
  assert.doesNotMatch(combined, /\bACT_[A-Z0-9_]+\b/);
  assert.doesNotMatch(combined, /V49__/);
  assert.equal((workflow.match(/pull_request:/g) ?? []).length, 1);
  assert.equal((workflow.match(/push:/g) ?? []).length, 1);
  assert.match(workflow, /contents: read/);
  assert.doesNotMatch(workflow, /contents: write|pull-requests: write/);
  assert.match(runbook, /Production migration execution: `NOT_AUTHORIZED`/);
  assert.match(runbook, /Real production migration: `NOT_PERFORMED`/);
  assert.match(runbook, /Production credentials\/endpoints: `ABSENT`/);
});

test('G1 runbook contains the complete dry-run and operator scenario matrices', () => {
  const dryRunSection = runbook.slice(
    runbook.indexOf('## 10. Dry-run release rehearsal matrix'),
    runbook.indexOf('## 11. Production readiness checklist'),
  );
  const operatorSection = runbook.slice(
    runbook.indexOf('## 12. Operator scenarios'),
    runbook.indexOf('## 13. G1 acceptance decision'),
  );
  assert.equal((dryRunSection.match(/^\| \d+ \|/gm) ?? []).length, 18);
  assert.equal((operatorSection.match(/^\| \d+ \|/gm) ?? []).length, 14);
  for (const topic of [
    'Release preconditions',
    'Database migration checks',
    'Safe configuration baseline',
    'Canary and bounded-orchestration parameters',
    'Read-only Operations verification',
    'Observability and redaction verification',
    'UNKNOWN and reconciliation operator procedure',
    'Rollback and stop-the-line',
    'Post-release observation window',
  ]) {
    assert.match(runbook, new RegExp(topic));
  }
});

test('G1 readiness checklist contains every auditable acceptance item', () => {
  const checklistItems = [
    'code complete',
    'tests complete',
    'security complete',
    'observability complete',
    'documentation complete',
    'migration continuity complete',
    'configuration defaults safe',
    'no production credentials',
    'no production endpoints',
    'no auto execution',
    'no auto retry UNKNOWN',
    'no cross-tenant access',
    'no direct ACT_* access',
    'no hidden write API',
    'no extra permanent workflow',
    'Web read-only',
    'Mobile read-only',
    'PR evidence complete',
    'permanent Run success',
    'artifacts retained',
    'artifact SHA-256 verified',
    'rollback and kill switch runbook complete',
    'production execution NOT_AUTHORIZED',
  ];
  for (const item of checklistItems) {
    const marker = '- [ ] `' + item + '`';
    assert.ok(runbook.includes(marker), `missing readiness item ${item}`);
  }
  assert.equal((runbook.match(/^- \[ \] `/gm) ?? []).length, checklistItems.length);
});
