import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const read = (relative) => readFileSync(path.join(root, relative), 'utf8');

const portPath = 'server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/application/port/ApprovalMigrationRuntimeBindingCasStore.java';
const servicePath = 'server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/application/ApprovalMigrationRuntimeBindingCasService.java';
const storePath = 'server-modules/approval-persistence-jdbc/src/main/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcApprovalMigrationRuntimeBindingCasStore.java';
const serializedPath = 'server-modules/approval-persistence-jdbc/src/main/java/io/github/akaryc1b/approval/persistence/jdbc/PostgresSerializedApprovalMigrationRuntimeBindingCasStore.java';
const configPath = 'apps/server/src/main/java/io/github/akaryc1b/approval/config/ApprovalMigrationExecutionConfiguration.java';
const migrationPath = 'server-modules/approval-persistence-jdbc/src/main/resources/db/migration/V44__complete_exact_migration_runtime_binding.sql';
const jdbcTestPath = 'server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcApprovalMigrationRuntimeBindingCasStoreIntegrationTest.java';
const sharedFixturePath = 'server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcRuntimeBindingStartTestFixture.java';

for (const file of [
  portPath,
  servicePath,
  storePath,
  serializedPath,
  configPath,
  migrationPath,
  jdbcTestPath,
  sharedFixturePath,
]) {
  assert.equal(existsSync(path.join(root, file)), true, `${file} must exist`);
}

const port = read(portPath);
const service = read(servicePath);
const store = read(storePath);
const serialized = read(serializedPath);
const config = read(configPath);
const migration = read(migrationPath);
const jdbcTest = read(jdbcTestPath);
const sharedFixture = read(sharedFixturePath);

test('D5 accepts only exact lineage and revisions from the worker', () => {
  const request = port.match(/record CompletionRequest\([\s\S]*?\n    \}/)?.[0] ?? '';
  for (const required of [
    'tenantId',
    'attemptId',
    'verificationId',
    'workerId',
    'expectedAttemptRevision',
    'expectedFenceRevision',
    'expectedBindingRevision',
    'requestId',
  ]) {
    assert.match(request, new RegExp(required));
  }
  assert.doesNotMatch(request, /targetRelease|targetPackage|targetDefinition|completed|success/i);
  assert.match(store, /requireAttempt\(attempt, request\)/);
  assert.match(store, /lockVerification\(request, attempt\)/);
  assert.match(store, /lockPlan\(attempt\)/);
  assert.match(store, /lockFence\(attempt\.tenantId\(\), attempt\.attemptId\(\)\)/);
});

test('D5 completes binding projection evidence attempt fence and audit in one transaction', () => {
  assert.match(store, /transactions\.execute\(status -> completeOnce\(request\)\)/);
  assert.match(store, /updateBinding\(/);
  assert.match(store, /updateInstance\(/);
  assert.match(store, /insertCompletion\(/);
  assert.match(store, /releaseFence\(/);
  assert.match(store, /AttemptStatus\.SUCCEEDED/);
  assert.match(store, /PROCESS_MIGRATION_INSTANCE_COMPLETED/);
  assert.doesNotMatch(store, /ProcessMigrationService|RuntimeService|migrateOne\s*\(/);
});

test('D5 records stale CAS conflict without mutating the binding', () => {
  assert.match(store, /casAuthorityMatches\(/);
  assert.match(store, /recordConflict\(/);
  assert.match(store, /insertConflict\(/);
  assert.match(store, /AttemptStatus\.RECONCILING/);
  assert.match(store, /PROCESS_MIGRATION_BINDING_CAS_CONFLICT_RECORDED/);
  assert.match(port, /RECONCILIATION_REQUIRED/);
  assert.match(port, /REPLAYED_CONFLICT/);
});

test('D5 replay is cross-node serialized and pooled sessions are explicitly unlocked', () => {
  assert.match(serialized, /pg_advisory_lock\(hashtextextended\(\?, 0\)\)/);
  assert.match(serialized, /pg_advisory_unlock\(hashtextextended\(\?, 0\)\)/);
  assert.match(serialized, /request\.tenantId\(\)/);
  assert.match(serialized, /request\.attemptId\(\)/);
  assert.match(serialized, /release\(connection, lockKey\)/);
  assert.match(config, /new PostgresSerializedApprovalMigrationRuntimeBindingCasStore/);
  assert.match(jdbcTest, /concurrentSerializedCasProducesOneCompletionAndOneExactReplay/);
  assert.match(jdbcTest, /BindingCasDisposition\.REPLAYED_COMPLETION/);
});

test('V44 evidence is immutable revisioned and tied to exact verification', () => {
  for (const required of [
    'binding_revision',
    'previous_binding_evidence_hash',
    'verification_id',
    'verification_evidence_hash',
    'ap_process_migration_instance_completion',
    'ap_process_migration_binding_cas_conflict',
    'runtime binding evidence is append-only',
    'migration completion evidence is append-only',
    'binding CAS conflict evidence is append-only',
  ]) {
    assert.match(migration, new RegExp(required, 'i'));
  }
  assert.match(migration, /new\.binding_revision<>old\.binding_revision\+1/);
  assert.match(migration, /EXACT_TARGET_RUNTIME/);
});

test('D5 has no test-schema compatibility or public execution surface', () => {
  const fixtures = `${jdbcTest}\n${sharedFixture}`;
  assert.doesNotMatch(fixtures, /alter\s+table\s+ap_approval_instance/i);
  assert.doesNotMatch(fixtures, /add\s+column\s+if\s+not\s+exists\s+engine_instance_id/i);
  assert.doesNotMatch(fixtures, /current_task_key/);
  for (const content of [service, store, serialized, config]) {
    assert.doesNotMatch(content, /@Scheduled|SchedulingConfigurer|TaskScheduler/);
    assert.doesNotMatch(content, /@RestController|@Controller|PostMapping|PutMapping|PatchMapping/);
  }
  assert.match(config, /approval\.migration\.execution\.enabled:false/);
  assert.match(config, /approval\.migration\.worker\.enabled:false/);
  assert.match(service, /class OneShotRunner/);
});
