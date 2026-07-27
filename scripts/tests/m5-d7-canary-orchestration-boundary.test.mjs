import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';

const root = process.cwd();
const read = (path) => readFileSync(join(root, path), 'utf8');
const service = read(
  'server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/application/'
    + 'ApprovalMigrationBoundedOrchestrationService.java',
);
const pipeline = read(
  'server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/application/'
    + 'ApprovalMigrationAttemptPipelineService.java',
);
const store = read(
  'server-modules/approval-persistence-jdbc/src/main/java/io/github/akaryc1b/approval/persistence/jdbc/'
    + 'JdbcApprovalMigrationOrchestrationStore.java',
);
const evidence = read(
  'server-modules/approval-domain/src/main/java/io/github/akaryc1b/approval/domain/migration/'
    + 'ApprovalMigrationOrchestrationEvidence.java',
);
const configuration = read(
  'apps/server/src/main/java/io/github/akaryc1b/approval/config/'
    + 'ApprovalMigrationExecutionConfiguration.java',
);
const applicationYaml = read('apps/server/src/main/resources/application.yml');
const v47 = read(
  'server-modules/approval-persistence-jdbc/src/main/resources/db/migration/'
    + 'V47__create_canary_bounded_orchestration.sql',
);
const workflow = read('.github/workflows/approval-platform-validation.yml');

test('D7 selects only canonical sequence one and persists immutable evidence', () => {
  assert.match(evidence, /CANARY_ALGORITHM_VERSION = "CANONICAL_FIRST_V1"/);
  assert.match(evidence, /sequenceNo != 1/);
  assert.match(store, /selection\.sequence_no=1/);
  assert.match(v47, /algorithm_version='CANONICAL_FIRST_V1' and sequence_no=1/);
  assert.match(v47, /unique \(tenant_id,plan_id\)/);
  assert.match(v47, /M5-D7 evidence is append-only/);
});

test('D7 reuses D2 claim and D3-D5 one-instance services with a strict bound', () => {
  assert.match(service, /claimLimit = prepared\.run\(\)\.phase\(\) == OrchestrationPhase\.CANARY/);
  assert.match(service, /\? 1\s*: command\.limit\(\)/);
  assert.match(service, /for \(int index = 0; index < claimed\.attempts\(\)\.size\(\); index\+\+\)/);
  assert.match(pipeline, /executor\.execute/);
  assert.match(pipeline, /verifier\.verify/);
  assert.match(pipeline, /bindingCas\.complete/);
  assert.match(v47, /requested_limit between 1 and 100/);
  assert.doesNotMatch(service + pipeline, /while\s*\(|do\s*\{|@Scheduled/);
});

test('kill switch is checked before every dispatch and cannot cancel in-flight work', () => {
  assert.match(service, /killSwitch\.snapshot\(\)/);
  assert.match(service, /authorizeDispatch\(new DispatchRequest/);
  assert.match(v47, /dispatch_allowed=\(not switch_enabled and expected_revision=observed_revision\)/);
  assert.match(store, /KILL_SWITCH_ACTIVE/);
  assert.doesNotMatch(store + service, /cancelEngine|rollbackEngine|deleteEngine|forceSuccess/);
});

test('D7 is internal and all execution gates remain default disabled', () => {
  assert.match(configuration, /approval\.migration\.orchestration\.enabled:false/);
  assert.match(applicationYaml, /orchestration:\n\s+enabled: \$\{APPROVAL_MIGRATION_ORCHESTRATION_ENABLED:false\}/);
  assert.match(applicationYaml, /kill-switch:\n\s+enabled: \$\{APPROVAL_MIGRATION_KILL_SWITCH_ENABLED:false\}/);
  assert.doesNotMatch(
    service + pipeline + store + configuration,
    /@RestController|@Controller|@RequestMapping|@PostMapping|@Scheduled/,
  );
});

test('D7 adds V47 only and no Flowable internal-table access or second automatic workflow', () => {
  assert.match(v47, /create table ap_process_migration_orchestration_run/);
  assert.match(v47, /create table ap_process_migration_orchestration_batch/);
  assert.match(v47, /create table ap_process_migration_kill_switch_observation/);
  assert.doesNotMatch(service + pipeline + store + v47, /\bACT_[A-Z0-9_]+\b/);
  assert.equal((workflow.match(/pull_request:/g) ?? []).length, 1);
  assert.equal((workflow.match(/push:/g) ?? []).length, 1);
});
