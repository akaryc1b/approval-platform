import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';

const root = process.cwd();
const read = path => readFileSync(join(root, path), 'utf8');

const application = read('apps/server/src/main/resources/application.yml');
const workflow = read('.github/workflows/approval-platform-validation.yml');
const executor = read(
  'server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/application/'
    + 'ApprovalMigrationSingleInstanceExecutor.java',
);
const executorTest = read(
  'server-modules/approval-application/src/test/java/io/github/akaryc1b/approval/application/'
    + 'ApprovalMigrationSingleInstanceExecutorTest.java',
);
const controller = read(
  'apps/server/src/main/java/io/github/akaryc1b/approval/api/'
    + 'ApprovalMigrationOperationsController.java',
);
const operationsAdvice = read(
  'apps/server/src/main/java/io/github/akaryc1b/approval/api/'
    + 'ApprovalMigrationOperationsObservabilityAdvice.java',
);
const operationsClassifier = read(
  'apps/server/src/main/java/io/github/akaryc1b/approval/api/'
    + 'ApprovalMigrationOperationsTelemetryClassifier.java',
);
const safetyMetrics = read(
  'apps/server/src/main/java/io/github/akaryc1b/approval/config/'
    + 'ApprovalMigrationSafetyMetricsConfiguration.java',
);
const operationsQuery = read(
  'server-modules/approval-persistence-jdbc/src/main/java/io/github/akaryc1b/approval/persistence/jdbc/'
    + 'JdbcApprovalMigrationOperationsQuery.java',
);

const f1Production = [
  operationsAdvice,
  operationsClassifier,
  safetyMetrics,
  controller,
  operationsQuery,
].join('\n');

test('F1 retains every migration execution feature as explicit opt-in', () => {
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
  assert.match(safetyMetrics, /approval\.migration\.safety\.feature\.enabled/);
  for (const feature of [
    'execution',
    'worker',
    'orchestration',
    'aggregation',
    'automatic_reconciliation',
    'kill_switch',
  ]) {
    assert.match(safetyMetrics, new RegExp(`\\"${feature}\\"`));
  }
});

test('F1 freezes durable UNKNOWN and no-second-write fault semantics', () => {
  assert.match(executor, /catch \(AmbiguousMigrationDispatchException exception\)/);
  assert.match(executor, /catch \(RuntimeException exception\)/);
  assert.match(executor, /"ENGINE_PORT_UNEXPECTED"/);
  assert.match(executor, /resultDisposition = "AMBIGUOUS_UNKNOWN"/);
  assert.match(executor, /Finalization is deliberately outside the engine exception boundary/);
  assert.doesNotMatch(executor, /while\s*\(|for\s*\([^)]*retry|Thread\.sleep|@Scheduled/);
  assert.match(executorTest, /persistsDurableUnknownOnceAndNeverRetriesAmbiguousDispatch/);
  assert.match(executorTest, /staleOwnerOrAuditFinalizationFailureIsPropagatedWithoutSecondOutcomeWrite/);
  assert.match(executorTest, /oneShotRunnerFailsClosedUnlessBothExecutionAndWorkerAreEnabled/);
});

test('F1 Operations observability is structured and low cardinality', () => {
  assert.match(operationsClassifier, /READ_COUNT_METRIC = "approval\.migration\.operations\.read"/);
  assert.match(
    operationsAdvice,
    /ApprovalMigrationOperationsTelemetryClassifier\.READ_COUNT_METRIC/,
  );
  assert.match(operationsAdvice, /MAX_MESSAGE_CODE_POINTS = 512/);
  assert.match(operationsAdvice, /MAX_EVIDENCE_CODE_POINTS = 128/);
  assert.match(operationsAdvice, /MDC\.get\("requestId"\)/);
  assert.match(operationsAdvice, /MDC\.get\("traceId"\)/);
  assert.match(operationsAdvice, /"operation", classification\.operation\(\)\.metricValue\(\)/);
  assert.match(operationsAdvice, /"result", classification\.result\(\)\.metricValue\(\)/);
  assert.match(operationsAdvice, /"failure_class", classification\.failureClass\(\)\.metricValue\(\)/);
  assert.match(operationsAdvice, /Map\.of\("failureClass", failureClass\.metricValue\(\)\)/);
  assert.doesNotMatch(
    f1Production,
    /"tenantId"\s*,|"operatorId"\s*,|"planId"\s*,|"intentId"\s*,|"attemptId"\s*,|"instanceId"\s*,|"requestId"\s*,|"traceId"\s*,|"reason"\s*,/,
  );
});

test('F1 security acceptance keeps Operations read-only and redacted', () => {
  assert.equal((controller.match(/@GetMapping/g) ?? []).length, 4);
  assert.doesNotMatch(controller, /@(?:Post|Put|Patch|Delete)Mapping|@RequestBody/);
  assert.match(controller, /MIGRATION_OPERATIONS_READ/);
  assert.match(controller, /ResourceScope\.TENANT/);
  assert.doesNotMatch(operationsQuery, /jdbc\.update\s*\(/);
  assert.doesNotMatch(operationsQuery, /\b(?:insert into|update ap_|delete from)\b/i);
  assert.doesNotMatch(operationsQuery, /payload_json::text|operation_reason|reason\s+from/i);
  assert.doesNotMatch(operationsQuery, /\bACT_[A-Z0-9_]+\b/);
  assert.doesNotMatch(f1Production, /X-Approval-Trusted-Permissions/);
});

test('F1 remains a bounded foundation and does not claim F2 or production authority', () => {
  assert.doesNotMatch(f1Production, /V49__|\bM6[-_A-Z0-9]*\b/);
  assert.doesNotMatch(
    f1Production,
    /@Scheduled|ScheduledExecutor|scheduleAtFixedRate|scheduleWithFixedDelay|while\s*\(true\)/,
  );
  assert.doesNotMatch(
    controller,
    /execute|retry|rollback|forceSuccess|reconcile|cancel|killSwitch/i,
  );
  assert.equal((workflow.match(/pull_request:/g) ?? []).length, 1);
  assert.equal((workflow.match(/push:/g) ?? []).length, 1);
  assert.doesNotMatch(workflow, /m5-f1.*workflow/i);
});
