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

test('D8 uses a closed status vocabulary and unresolved evidence precedes completion', () => {
  for (const status of [
    'NOT_STARTED',
    'CANARY_PENDING',
    'CANARY_RUNNING',
    'BOUNDED_EXECUTION_RUNNING',
    'PAUSED',
    'KILL_SWITCH_BLOCKED',
    'UNKNOWN_PRESENT',
    'RECONCILIATION_PRESENT',
    'MANUAL_REVIEW_PRESENT',
    'TERMINAL_FAILURE_PRESENT',
    'PARTIALLY_COMPLETED',
    'ALL_INSTANCES_EXACTLY_COMPLETED',
    'COMPLETED_WITH_MANUAL_DISPOSITION',
    'COMPLETION_CONFLICT',
    'INVALID_INCOMPLETE_EVIDENCE',
  ]) {
    assert.match(evidence, new RegExp(`\\b${status}\\b`));
    assert.match(v48, new RegExp(`'${status}'`));
  }
  assert.ok(rules.indexOf('UNKNOWN_PRESENT') < rules.indexOf('ALL_INSTANCES_EXACTLY_COMPLETED'));
  assert.ok(rules.indexOf('COMPLETION_CONFLICT') < rules.indexOf('ALL_INSTANCES_EXACTLY_COMPLETED'));
  assert.match(rules, /signals\.incompleteEvidence\(\)/);
});

test('D8 derives counts and hashes from canonical immutable evidence only', () => {
  assert.match(jdbc, /order by selection\.sequence_no/);
  assert.match(jdbc, /m5-d8-input-evidence-v1/);
  assert.match(jdbc, /m5-d8-plan-aggregate-v1/);
  assert.match(jdbc, /m5-d8-plan-completion-v1/);
  assert.match(jdbc, /currentAggregateRevision/);
  assert.match(jdbc, /latestAggregateHash/);
  assert.match(serialized, /pg_advisory_lock/);
  assert.match(serialized, /request\.tenantId\(\).*request\.intentId\(\)/s);
  assert.doesNotMatch(jdbc, /client.*(?:count|status|result)|trusted.*client/i);
});

test('D8 persists aggregate event completion and audit atomically without execution side effects', () => {
  assert.match(v48, /create table ap_process_migration_plan_aggregate \(/);
  assert.match(v48, /create table ap_process_migration_plan_aggregate_event \(/);
  assert.match(v48, /create table ap_process_migration_plan_completion \(/);
  assert.match(v48, /M5-D8 evidence is append-only/);
  assert.match(jdbc, /insertAggregate\(aggregate\)/);
  assert.match(jdbc, /insertEvent\(event\)/);
  assert.match(jdbc, /insertCompletion\(completion\)/);
  assert.match(jdbc, /appendAudit\(aggregate, completion != null\)/);
  assert.doesNotMatch(jdbc + v48, /update ap_process_runtime_binding|insert into ap_process_runtime_binding/);
  assert.doesNotMatch(jdbc + v48, /insert into ap_process_migration_engine_request/);
});

test('D8 is internal default-disabled and has no Flowable or browser command surface', () => {
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
});

test('D8 advances only V48 and retains one automatic PR/main workflow', () => {
  assert.match(v48, /idx_process_migration_plan_aggregate_plan_v48/);
  assert.match(v48, /idx_process_migration_plan_completion_time_v48/);
  assert.equal((workflow.match(/pull_request:/g) ?? []).length, 1);
  assert.equal((workflow.match(/push:/g) ?? []).length, 1);
  assert.doesNotMatch(workflow, /m5-d8.*workflow/i);
});
