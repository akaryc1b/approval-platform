import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const read = (relative) => readFileSync(path.join(root, relative), 'utf8');

const snapshotPath = 'server-modules/approval-domain/src/main/java/io/github/akaryc1b/approval/domain/migration/ApprovalMigrationEngineSnapshot.java';
const classificationPath = 'server-modules/approval-domain/src/main/java/io/github/akaryc1b/approval/domain/migration/ApprovalMigrationExactVerification.java';
const portPath = 'server-modules/approval-engine-spi/src/main/java/io/github/akaryc1b/approval/engine/ProcessInstanceVerificationPort.java';
const adapterPath = 'server-modules/approval-engine-flowable/src/main/java/io/github/akaryc1b/approval/engine/flowable/FlowableProcessInstanceVerificationAdapter.java';
const servicePath = 'server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/application/ApprovalMigrationExactVerificationService.java';
const storePath = 'server-modules/approval-persistence-jdbc/src/main/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcApprovalMigrationExactVerificationStore.java';
const configPath = 'apps/server/src/main/java/io/github/akaryc1b/approval/config/ApprovalMigrationExecutionConfiguration.java';
const migrationPath = 'server-modules/approval-persistence-jdbc/src/main/resources/db/migration/V43__create_exact_migration_verification_evidence.sql';

for (const file of [snapshotPath, classificationPath, portPath, adapterPath, servicePath, storePath, configPath, migrationPath]) {
  assert.equal(existsSync(path.join(root, file)), true, `${file} must exist`);
}

const snapshot = read(snapshotPath);
const classification = read(classificationPath);
const port = read(portPath);
const adapter = read(adapterPath);
const service = read(servicePath);
const store = read(storePath);
const config = read(configPath);
const migration = read(migrationPath);

test('D4 exposes one read-only instance command and accepts no client result', () => {
  assert.match(port, /ApprovalMigrationEngineSnapshot\s+readOne\s*\(/);
  assert.match(port, /String\s+engineInstanceId/);
  assert.doesNotMatch(port, /classification|verificationResult|verifiedSuccess|runtimeBinding/i);
  assert.match(service, /record VerificationRequest\(/);
  assert.doesNotMatch(service.match(/record VerificationRequest\([\s\S]*?\n    \}/)?.[0] ?? '', /classification|snapshot|result/i);
});

test('D4 engine read is outside the two short store transactions', () => {
  const prepare = service.indexOf('verificationStore.prepare');
  const readOne = service.indexOf('engineVerification.readOne');
  const finalize = service.indexOf('verificationStore.finalizeVerification');
  assert.ok(prepare >= 0 && readOne > prepare && finalize > readOne);
  assert.doesNotMatch(service, /@Transactional|TransactionTemplate|PlatformTransactionManager/);
  assert.match(store, /transactions\.execute\(status -> prepareOnce/);
  assert.match(store, /transactions\.execute\(status -> finalizeOnce/);
  assert.doesNotMatch(store, /RuntimeService|HistoryService|readOne\s*\(/);
});

test('Flowable verification uses only bounded public APIs', () => {
  assert.match(adapter, /createProcessInstanceQuery\(\)/);
  assert.match(adapter, /createHistoricProcessInstanceQuery\(\)/);
  assert.match(adapter, /createTaskQuery\(\)/);
  assert.match(adapter, /createExecutionQuery\(\)/);
  assert.match(adapter, /createJobQuery\(\)/);
  assert.match(adapter, /createTimerJobQuery\(\)/);
  assert.match(adapter, /createSuspendedJobQuery\(\)/);
  assert.match(adapter, /createDeadLetterJobQuery\(\)/);
  assert.match(adapter, /createEventSubscriptionQuery\(\)/);
  assert.match(adapter, /listPage\(0, READ_LIMIT\)/);
  assert.match(adapter, /MAX_ITEMS = 64/);
  assert.match(adapter, /READ_LIMIT = MAX_ITEMS \+ 1/);
  assert.doesNotMatch(adapter, /ACT_[A-Z_]+|org\.flowable\..*\.impl\./);
});

test('exact target rejects residual source-bound evidence and truncation', () => {
  for (const required of [
    'EXACT_TARGET_RUNTIME',
    'EXACT_SOURCE_RUNTIME',
    'SOURCE_HISTORY_TERMINAL',
    'TARGET_HISTORY_TERMINAL',
    'MIXED_SOURCE_TARGET_EVIDENCE',
    'MISSING_NO_EVIDENCE',
    'STALE_OR_CONTRADICTORY_EVIDENCE',
    'TRUNCATED_MANUAL_REVIEW_REQUIRED',
    'READ_FAILURE_RECONCILIATION_REQUIRED',
    'INCOMPLETE_RECONCILIATION_REQUIRED',
  ]) {
    assert.match(classification, new RegExp(required));
  }
  assert.match(classification, /if \(sourceObserved && targetObserved\)/);
  assert.match(classification, /if \(snapshot\.truncated\(\)\)/);
  assert.match(snapshot, /List<JobEvidence> jobs/);
  assert.match(snapshot, /List<SubscriptionEvidence> subscriptions/);
  assert.match(snapshot, /List<TaskEvidence> activeTasks/);
  assert.match(snapshot, /List<DefinitionEvidence> executions/);
});

test('verification evidence is bounded hashed and append-only', () => {
  assert.match(snapshot, /allowlistedVariableHashes/);
  assert.match(snapshot, /identityLinkHashes/);
  assert.match(snapshot, /boundedDeleteReason/);
  assert.match(snapshot, /snapshotHash/);
  assert.match(migration, /ap_process_migration_exact_verification/);
  assert.match(migration, /verification evidence is append-only/);
  assert.match(migration, /engine_request_id/);
  assert.match(migration, /engine_outcome_id/);
  assert.match(migration, /expected_fence_revision/);
  assert.match(migration, /verification_evidence_hash/);
});

test('D4 never mutates runtime binding and exact target waits for D5', () => {
  const d4 = [classification, port, adapter, service, store, config, migration].join('\n');
  assert.doesNotMatch(d4, /update\s+ap_process_runtime_binding|insert\s+into\s+ap_process_runtime_binding/i);
  assert.doesNotMatch(d4, /ApprovalRuntimeBindingStore|JdbcApprovalRuntimeBindingStore/);
  assert.match(store, /if \(derived != ExactClassification\.EXACT_TARGET_RUNTIME\)/);
  assert.doesNotMatch(store, /AttemptStatus\.SUCCEEDED/);
});

test('D4 remains default disabled without scheduler or public controller', () => {
  assert.match(config, /approval\.migration\.execution\.enabled:false/);
  assert.match(config, /approval\.migration\.worker\.enabled:false/);
  assert.match(service, /class OneShotRunner/);
  for (const content of [config, service, adapter, store]) {
    assert.doesNotMatch(content, /@Scheduled|SchedulingConfigurer|TaskScheduler/);
    assert.doesNotMatch(content, /@RestController|@Controller|PostMapping|PutMapping|PatchMapping/);
  }
});
