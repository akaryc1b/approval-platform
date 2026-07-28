import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';

const root = process.cwd();
const read = path => readFileSync(join(root, path), 'utf8');

const classifier = read(
  'apps/server/src/main/java/io/github/akaryc1b/approval/api/'
    + 'ApprovalMigrationOperationsTelemetryClassifier.java',
);
const advice = read(
  'apps/server/src/main/java/io/github/akaryc1b/approval/api/'
    + 'ApprovalMigrationOperationsObservabilityAdvice.java',
);
const filter = read(
  'apps/server/src/main/java/io/github/akaryc1b/approval/api/'
    + 'ApprovalMigrationOperationsTelemetryFilter.java',
);
const safetyPort = read(
  'server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/application/port/'
    + 'ApprovalMigrationSafetyTelemetry.java',
);
const safetyAdapter = read(
  'apps/server/src/main/java/io/github/akaryc1b/approval/config/'
    + 'MicrometerApprovalMigrationSafetyTelemetry.java',
);
const safetyGauge = read(
  'apps/server/src/main/java/io/github/akaryc1b/approval/config/'
    + 'ApprovalMigrationSafetyMetricsConfiguration.java',
);
const executor = read(
  'server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/application/'
    + 'ApprovalMigrationSingleInstanceExecutor.java',
);
const pipeline = read(
  'server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/application/'
    + 'ApprovalMigrationAttemptPipelineService.java',
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
const application = read('apps/server/src/main/resources/application.yml');
const hardening = read('docs/M5_F2_FAULT_SECURITY_OBSERVABILITY_HARDENING.md');
const catalog = read('docs/M5_F2_OBSERVABILITY_METRIC_CATALOG.md');
const alerts = read('docs/examples/m5-migration-alerting-baseline.yml');
const workflow = read('.github/workflows/approval-platform-validation.yml');

const telemetryProduction = [
  classifier,
  advice,
  filter,
  safetyPort,
  safetyAdapter,
  safetyGauge,
].join('\n');
const coreProduction = [executor, pipeline, reconciliation, orchestration, aggregation].join('\n');
const apiProduction = [operationsController, diagnosticsController].join('\n');

test('F2 classifies every E1 and E2 read with closed low-cardinality values', () => {
  for (const operation of [
    'summary',
    'plan_list',
    'plan_detail',
    'instance_list',
    'plan_diagnostics',
    'diagnostic_instance_list',
    'instance_diagnostics',
  ]) {
    assert.match(classifier, new RegExp(`"${operation}"`));
  }
  for (const failureClass of [
    'none',
    'invalid_request',
    'unauthenticated',
    'forbidden',
    'not_found',
    'method_not_allowed',
    'conflict',
    'rate_limited',
    'server_error',
  ]) {
    assert.match(classifier, new RegExp(`"${failureClass}"`));
  }
  assert.match(classifier, /case 429 -> RATE_LIMITED/);
  assert.match(classifier, /case 405 -> METHOD_NOT_ALLOWED/);
  assert.doesNotMatch(classifier, /UUID\.fromString|tenantId|planId|instanceId/);
});

test('F2 exposes bounded latency and stable fail-open metric writes', () => {
  assert.match(
    classifier,
    /READ_LATENCY_METRIC = "approval\.migration\.operations\.read\.latency"/,
  );
  assert.match(filter, /READ_LATENCY_METRIC/);
  assert.match(filter, /minimumExpectedValue\(MIN_EXPECTED\)/);
  assert.match(filter, /maximumExpectedValue\(MAX_EXPECTED\)/);
  assert.match(filter, /publishPercentileHistogram\(false\)/);
  assert.match(filter, /catch \(RuntimeException exception\)/);
  assert.match(advice, /response remains fail-open/);
  assert.match(safetyPort, /static void safeRecord/);
  assert.match(safetyPort, /catch \(RuntimeException ignored\)/);
  assert.match(safetyAdapter, /startup remains fail-open/);
  assert.match(safetyAdapter, /migration semantics remain unchanged/);
  assert.doesNotMatch(
    telemetryProduction,
    /\.tag\("(?:tenant|plan|intent|attempt|instance|request|trace|message|exception)/i,
  );
  assert.doesNotMatch(
    telemetryProduction,
    /"(?:tenantId|planId|intentId|attemptId|instanceId|requestId|traceId)",/,
  );
});

test('F2 registers the full closed safety-event catalog in existing control flow', () => {
  const events = [
    'UNKNOWN_ENTERED',
    'RECONCILIATION_OBSERVATION_RECORDED',
    'RECONCILIATION_MANUAL_REVIEW_REQUIRED',
    'CANARY_LIMIT_REACHED',
    'ORCHESTRATION_BOUNDED_STOP',
    'KILL_SWITCH_BLOCKED',
    'PLAN_AGGREGATION_COMPLETED',
    'STALE_OWNERSHIP_REJECTED',
    'DUPLICATE_OUTCOME_PREVENTED',
    'VERIFICATION_MISMATCH',
    'RUNTIME_BINDING_CAS_FAILED',
    'COMPLETION_EVIDENCE_FAILED',
  ];
  for (const event of events) {
    assert.match(safetyPort, new RegExp(`\\b${event}\\b`));
    assert.match(coreProduction, new RegExp(`Event\\.${event}`));
  }
  assert.match(safetyAdapter, /for \(Event event : Event\.values\(\)\)/);
  assert.match(safetyAdapter, /\.tag\("event", event\.name\(\)\.toLowerCase/);
});

test('F2 keeps diagnostics non-cacheable and commands absent', () => {
  assert.match(filter, /Cache-Control", "no-store, max-age=0"/);
  assert.match(filter, /Pragma", "no-cache"/);
  assert.match(filter, /setDateHeader\("Expires", 0L\)/);
  assert.doesNotMatch(apiProduction, /@(?:Post|Put|Patch|Delete)Mapping/);
  assert.doesNotMatch(apiProduction, /@RequestBody|@CrossOrigin/);
  assert.doesNotMatch(apiProduction, /execute|retry|rollback|force|startReconciliation/i);
});

test('F2 documents exactly the required fault and security negative matrices', () => {
  const faultSection = hardening.slice(
    hardening.indexOf('## 2. Fault-injection matrix'),
    hardening.indexOf('## 3. Security negative-test matrix'),
  );
  const securitySection = hardening.slice(
    hardening.indexOf('## 3. Security negative-test matrix'),
    hardening.indexOf('## 4. Data-redaction policy'),
  );
  assert.equal((faultSection.match(/^\| \d+ \|/gm) ?? []).length, 24);
  assert.equal((securitySection.match(/^\| \d+ \|/gm) ?? []).length, 24);
  for (const invariant of [
    'UNKNOWN does not initiate another migration call',
    'Reconciliation performs one read-only observation',
    'Completion success is impossible without exact verification',
    'Observability failure is non-authoritative',
  ]) {
    assert.match(hardening, new RegExp(invariant));
  }
});

test('F2 metric catalog and alert baseline remain bounded and environment configured', () => {
  for (const metric of [
    'approval.migration.operations.read',
    'approval.migration.operations.read.latency',
    'approval.migration.safety.event',
    'approval.migration.safety.feature.enabled',
  ]) {
    assert.match(catalog, new RegExp(metric.replaceAll('.', '\\.')));
  }
  for (const feature of [
    'execution',
    'worker',
    'orchestration',
    'aggregation',
    'automatic_reconciliation',
    'kill_switch',
  ]) {
    assert.match(catalog, new RegExp(`\\b${feature}\\b`));
    assert.match(safetyGauge, new RegExp(`"${feature}"`));
  }
  assert.match(catalog, /not.*background cross-tenant scanner/is);
  assert.match(catalog, /not.*emitted as gauges labelled by tenant or plan/is);
  assert.equal((alerts.match(/^  - id:/gm) ?? []).length, 14);
  assert.match(alerts, /production_execution: NOT_AUTHORIZED/);
  assert.match(
    alerts,
    /approval_migration_safety_feature_enabled\{feature="kill_switch"\}/,
  );
  assert.match(alerts, /\$\{M5_ALERT_/);
  assert.doesNotMatch(alerts, /(?:password|token|secret|authorization):\s*[^$\s]/i);
  assert.doesNotMatch(alerts, /https?:\/\/(?!127\.0\.0\.1)/i);
});

test('F2 actuator CORS workflow migration and Flowable boundaries remain governed', () => {
  assert.match(application, /include: health,info,metrics,prometheus/);
  assert.doesNotMatch(
    application,
    /include:.*(?:env|configprops|heapdump|threaddump|loggers|mappings|shutdown)/,
  );
  assert.doesNotMatch(apiProduction, /Access-Control-Allow-Origin|allowedOrigins\("\*"\)/);
  assert.doesNotMatch(
    [telemetryProduction, coreProduction, apiProduction].join('\n'),
    /\bACT_[A-Z0-9_]+\b/,
  );
  assert.doesNotMatch(
    [telemetryProduction, coreProduction, apiProduction].join('\n'),
    /V49__/,
  );
  assert.equal((workflow.match(/pull_request:/g) ?? []).length, 1);
  assert.equal((workflow.match(/push:/g) ?? []).length, 1);
});
