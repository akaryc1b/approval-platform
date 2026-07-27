import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';

const root = process.cwd();
const read = (path) => readFileSync(join(root, path), 'utf8');
const application = read(
  'server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/application/'
    + 'ApprovalMigrationReconciliationService.java',
);
const observation = read(
  'server-modules/approval-domain/src/main/java/io/github/akaryc1b/approval/domain/migration/'
    + 'ApprovalMigrationReconciliationObservation.java',
);
const store = read(
  'server-modules/approval-persistence-jdbc/src/main/java/io/github/akaryc1b/approval/persistence/jdbc/'
    + 'JdbcApprovalMigrationReconciliationExecutionStore.java',
);
const configuration = read(
  'apps/server/src/main/java/io/github/akaryc1b/approval/config/'
    + 'ApprovalMigrationExecutionConfiguration.java',
);
const v45 = read(
  'server-modules/approval-persistence-jdbc/src/main/resources/db/migration/'
    + 'V45__create_durable_unknown_reconciliation.sql',
);
const v46 = read(
  'server-modules/approval-persistence-jdbc/src/main/resources/db/migration/'
    + 'V46__preserve_ambiguous_terminal_request_lineage.sql',
);
const upgrade = read(
  'server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/'
    + 'persistence/jdbc/JdbcApprovalMigrationUpgradeIntegrationTest.java',
);

test('D6 uses one bounded public readback outside two short platform transactions', () => {
  assert.match(application, /reconciliationStore\.prepare\(new PrepareRequest/);
  assert.match(application, /engineVerification\.readOne\(prepared\.engineCommand\(\)\)/);
  assert.match(application, /reconciliationStore\.finalizeObservation\(new FinalizeRequest/);
  assert.match(store, /transactions\.execute\(status -> prepareOnce\(request\)\)/);
  assert.match(store, /transactions\.execute\(status -> finalizeOnce\(request\)\)/);
  assert.match(store, /AttemptStatus\.UNKNOWN/);
  assert.match(store, /AttemptStatus\.RECONCILING/);
  assert.match(store, /requireAmbiguousOutcome\(attempt\)/);
  assert.doesNotMatch(application + store, /ProcessInstanceMigrationPort|ProcessMigrationService|\.migrate\(/);
});

test('D6 classification is server-derived and source evidence never authorizes redispatch', () => {
  assert.match(observation, /reconciliation observation is not server-derived/);
  assert.match(observation, /EXACT_SOURCE_RUNTIME -> ReconciliationDisposition\.SOURCE_CONFIRMED_NO_RETRY/);
  assert.match(observation, /SOURCE_TERMINAL_CONFIRMED_NO_RETRY/);
  assert.match(observation, /TARGET_CONFIRMED_BINDING_CAS_REQUIRED/);
  assert.match(observation, /TARGET_TERMINAL_BINDING_CAS_REQUIRED/);
  assert.match(observation, /default -> ReconciliationDisposition\.MANUAL_REVIEW_REQUIRED/);
  assert.match(store, /migration redispatch is forbidden/);
  assert.doesNotMatch(store, /ap_process_runtime_binding\b|ap_process_migration_instance_completion\b/);
});

test('V45 and V46 persist independent lease and immutable observation evidence', () => {
  assert.match(v45, /create table ap_process_migration_reconciliation_lease \(/);
  assert.match(v45, /create table ap_process_migration_reconciliation_lease_event \(/);
  assert.match(v45, /create table ap_process_migration_reconciliation_observation \(/);
  assert.match(v45, /migration reconciliation observation is append-only/);
  assert.match(v45, /reconciliation lease event is append-only/);
  assert.match(
    v45,
    /create trigger trg_process_migration_reconciliation_lease_guard_v45[\s\S]*before insert or update or delete/,
  );
  assert.match(v45, /SOURCE_CONFIRMED_NO_RETRY/);
  assert.match(v45, /TARGET_CONFIRMED_BINDING_CAS_REQUIRED/);
  assert.match(v46, /drop constraint ck_process_migration_attempt_request_v37/);
  assert.match(v46, /ck_process_migration_attempt_request_v46/);
  assert.match(v46, /status in \('BLOCKED_STALE','FAILED_TERMINAL'\) and engine_outcome='UNKNOWN'/);
  assert.doesNotMatch(v45 + v46, /\bACT_[A-Z0-9_]+\b|ProcessMigrationService|\.migrate\(/);
});

test('D6 is internal one-shot and default disabled', () => {
  assert.match(application, /There is no polling loop, scheduler or migration redispatch/);
  assert.match(application, /!executionEnabled \|\| !workerEnabled \|\| !automaticReconciliationEnabled/);
  assert.match(configuration, /approval\.migration\.reconciliation\.automatic\.enabled:false/);
  assert.doesNotMatch(
    application + store + configuration,
    /@RestController|@Controller|@RequestMapping|@PostMapping|@Scheduled/,
  );
});

test('D6 upgrade matrix reaches V46 including explicit V45 upgrade', () => {
  assert.match(upgrade, /LATEST_VERSION = "46"/);
  assert.match(upgrade, /new UpgradeCase\("approval_latest_v44", "44"\)/);
  assert.match(upgrade, /new UpgradeCase\("approval_latest_v45", "45"\)/);
  assert.match(upgrade, /upgradesV27WithFiveThousandInstancesAndTasksWithoutChangingEvidence/);
  assert.match(upgrade, /assertD6Empty\(jdbc\)/);
});
