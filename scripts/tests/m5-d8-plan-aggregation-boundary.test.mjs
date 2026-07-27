import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';

const root = process.cwd();
const read = (path) => readFileSync(join(root, path), 'utf8');
const service = read(
  'server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/application/'
    + 'ApprovalMigrationPlanAggregationService.java',
);
const port = read(
  'server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/application/port/'
    + 'ApprovalMigrationPlanAggregationStore.java',
);
const evidence = read(
  'server-modules/approval-domain/src/main/java/io/github/akaryc1b/approval/domain/migration/'
    + 'ApprovalMigrationPlanAggregationEvidence.java',
);
const rules = read(
  'server-modules/approval-domain/src/main/java/io/github/akaryc1b/approval/domain/migration/'
    + 'ApprovalMigrationPlanAggregationRules.java',
);
const jdbc = read(
  'server-modules/approval-persistence-jdbc/src/main/java/io/github/akaryc1b/approval/persistence/jdbc/'
    + 'JdbcApprovalMigrationPlanAggregationStore.java',
);
const serialized = read(
  'server-modules/approval-persistence-jdbc/src/main/java/io/github/akaryc1b/approval/persistence/jdbc/'
    + 'PostgresSerializedApprovalMigrationPlanAggregationStore.java',
);
const configuration = read(
  'apps/server/src/main/java/io/github/akaryc1b/approval/config/'
    + 'ApprovalMigrationPlanAggregationConfiguration.java',
);
const applicationYaml = read('apps/server/src/main/resources/application.yml');
const v48 = read(
  'server-modules/approval-persistence-jdbc/src/main/resources/db/migration/'
    + 'V48__create_process_migration_plan_aggregation.sql',
);
const workflow = read('.github/workflows/approval-platform-validation.yml');

const production = [service, port, evidence, rules, jdbc, serialized, configuration, v48].join('\n');

test('D8 uses closed status outcome and pause vocabularies', () => {
  for (const status of [
    'NOT_STARTED',
    'CANARY_PENDING',
    'CANARY_IN_PROGRESS',
    'BOUNDED_EXECUTION_IN_PROGRESS',
    'PAUSED',
    'UNRESOLVED',
    'TERMINAL_FAILURE_PRESENT',
    'PARTIALLY_COMPLETED',
    'COMPLETED_SUCCEEDED',
    'COMPLETED_WITH_TERMINAL_FAILURE',
    'INVALID_OR_INCOMPLETE_EVIDENCE',
  ]) {
    assert.match(evidence, new RegExp(`\\b${status}\\b`));
    assert.match(v48, new RegExp(`'${status}'`));
  }
  for (const outcome of [
    'SUCCEEDED',
    'COMPLETED_WITH_TERMINAL_FAILURE',
    'UNRESOLVED',
    'INVALID_EVIDENCE',
  ]) {
    assert.match(evidence, new RegExp(`\\b${outcome}\\b`));
    assert.match(v48, new RegExp(`'${outcome}'`));
  }
  for (const reason of [
    'KILL_SWITCH',
    'UNKNOWN',
    'RECONCILIATION',
    'MANUAL_REVIEW',
    'BINDING_CONFLICT',
    'STALE_AUTHORITY',
    'INCOMPLETE_EVIDENCE',
  ]) {
    assert.match(evidence, new RegExp(`\\b${reason}\\b`));
    assert.match(v48, new RegExp(`'${reason}'`));
  }
  assert.match(rules, /signals\.incompleteEvidence\(\)/);
  assert.match(rules, /AggregateStatus\.COMPLETED_SUCCEEDED/);
  assert.match(rules, /AggregateStatus\.COMPLETED_WITH_TERMINAL_FAILURE/);
});

test('D8 accepts server-owned context exact plan and bounded reason only', () => {
  assert.match(port, /RequestContext context/);
  assert.match(port, /UUID planId/);
  assert.match(port, /expectedAggregateRevision/);
  assert.match(port, /String reason/);
  assert.match(port, /context\.tenantId\(\)/);
  assert.match(port, /context\.operatorId\(\)/);
  assert.match(port, /context\.idempotencyKey\(\)/);
  assert.match(service, /AggregateCommand\(\s*RequestContext context,\s*UUID planId/s);
  assert.doesNotMatch(service + port, /selectedCount|completedCount|aggregateState|engineOutcome/);
});

test('D8 derives detailed counts and hashes from canonical immutable evidence only', () => {
  assert.match(jdbc, /order by selection\.sequence_no/);
  for (const count of [
    'provisionedAttemptCount',
    'pendingCount',
    'claimedCount',
    'engineRequestedCount',
    'verifyingCount',
    'reconcilingCount',
    'unknownCount',
    'manualReviewCount',
    'bindingConflictCount',
    'blockedStaleCount',
    'terminalFailedCount',
    'exactSuccessCount',
    'unresolvedCount',
  ]) {
    assert.match(evidence, new RegExp(`\\b${count}\\b`));
  }
  assert.match(jdbc, /M5-D8-INPUT-EVIDENCE-V1/);
  assert.match(jdbc, /M5-D8-PLAN-AGGREGATE-V1/);
  assert.match(jdbc, /M5-D8-PLAN-COMPLETION-V1/);
  assert.match(jdbc, /StandardCharsets\.UTF_8/);
  assert.match(jdbc, /latestAggregate/);
  assert.match(jdbc, /authoritative aggregation input is unchanged/);
  assert.match(serialized, /pg_advisory_lock/);
  assert.match(serialized, /request\.tenantId\(\).*request\.planId\(\)/s);
  assert.match(serialized, /pg_advisory_unlock/);
  assert.doesNotMatch(jdbc, /client.*(?:count|status|result)|trusted.*client/i);
});

test('D8 requires exact D5 completion and persists aggregate event completion audit atomically', () => {
  assert.match(jdbc, /EXACT_TARGET_RUNTIME/);
  assert.match(jdbc, /verificationTruncated/);
  assert.match(jdbc, /bindingRevision\(\) > 1/);
  assert.match(v48, /create table ap_process_migration_plan_aggregate \(/);
  assert.match(v48, /create table ap_process_migration_plan_aggregate_event \(/);
  assert.match(v48, /create table ap_process_migration_plan_completion \(/);
  assert.match(v48, /M5-D8 evidence is append-only/);
  assert.match(jdbc, /insertAggregate\(aggregate\)/);
  assert.match(jdbc, /insertEvent\(event\)/);
  assert.match(jdbc, /insertCompletion\(completion\)/);
  assert.match(jdbc, /appendAudit\(auditEventId, aggregate, completion != null\)/);
  assert.match(jdbc, /aggregate\.operatorId\(\)/);
  assert.match(jdbc, /reasonHash/);
  assert.doesNotMatch(jdbc + v48, /update ap_process_runtime_binding|insert into ap_process_runtime_binding/);
  assert.doesNotMatch(jdbc + v48, /insert into ap_process_migration_engine_request/);
});

test('D8 is internal default-disabled and has no Flowable browser scheduler or M6 surface', () => {
  assert.match(configuration, /approval\.migration\.aggregation\.enabled:false/);
  assert.match(
    applicationYaml,
    /aggregation:\n\s+enabled: \$\{APPROVAL_MIGRATION_AGGREGATION_ENABLED:false\}/,
  );
  assert.doesNotMatch(
    production,
    /@RestController|@Controller|@RequestMapping|@PostMapping|@Scheduled/,
  );
  assert.doesNotMatch(production, /ProcessMigrationService|ProcessInstanceMigrationBuilder/);
  assert.doesNotMatch(production, /\bACT_[A-Z0-9_]+\b/);
  assert.doesNotMatch(production, /forceSuccess|fakeRollback|automaticRetry|retryUnknown/);
  assert.doesNotMatch(production, /\bM6[-_A-Z0-9]*\b/);
});

test('D8 advances only V48 and retains one automatic PR main workflow', () => {
  assert.match(v48, /idx_process_migration_plan_aggregate_plan_v48/);
  assert.match(v48, /idx_process_migration_plan_aggregate_unresolved_v48/);
  assert.match(v48, /idx_process_migration_plan_completion_time_v48/);
  assert.equal((workflow.match(/pull_request:/g) ?? []).length, 1);
  assert.equal((workflow.match(/push:/g) ?? []).length, 1);
  assert.doesNotMatch(workflow, /m5-d8.*workflow/i);
});
