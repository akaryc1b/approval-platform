import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const read = (relative) => readFileSync(path.join(root, relative), 'utf8');

const portPath = 'server-modules/approval-engine-spi/src/main/java/io/github/akaryc1b/approval/engine/ProcessInstanceMigrationPort.java';
const adapterPath = 'server-modules/approval-engine-flowable/src/main/java/io/github/akaryc1b/approval/engine/flowable/FlowableProcessInstanceMigrationAdapter.java';
const executorPath = 'server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/application/ApprovalMigrationSingleInstanceExecutor.java';
const storePath = 'server-modules/approval-persistence-jdbc/src/main/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcApprovalMigrationEngineExecutionStore.java';
const configPath = 'apps/server/src/main/java/io/github/akaryc1b/approval/config/ApprovalMigrationExecutionConfiguration.java';
const migrationPath = 'server-modules/approval-persistence-jdbc/src/main/resources/db/migration/V41__create_single_instance_engine_dispatch_evidence.sql';

for (const file of [portPath, adapterPath, executorPath, storePath, configPath, migrationPath]) {
  assert.equal(existsSync(path.join(root, file)), true, `${file} must exist`);
}

const port = read(portPath);
const adapter = read(adapterPath);
const executor = read(executorPath);
const store = read(storePath);
const config = read(configPath);
const migration = read(migrationPath);

function sourceFiles(relativeRoot, extensions) {
  const directory = path.join(root, relativeRoot);
  const files = [];
  const visit = (current) => {
    for (const entry of readdirSync(current, { withFileTypes: true })) {
      const next = path.join(current, entry.name);
      if (entry.isDirectory() && !['node_modules', 'dist'].includes(entry.name)) visit(next);
      else if (entry.isFile() && extensions.test(entry.name)) {
        files.push({
          relative: path.relative(root, next).split(path.sep).join('/'),
          content: readFileSync(next, 'utf8'),
        });
      }
    }
  };
  visit(directory);
  return files;
}

test('D3 port accepts only one exact instance and has no batch or rollback surface', () => {
  assert.match(port, /MigrationDispatchResult\s+migrateOne\s*\(/);
  assert.match(port, /UUID\s+approvalInstanceId/);
  assert.match(port, /UUID\s+attemptId/);
  assert.match(port, /String\s+engineInstanceId/);
  assert.doesNotMatch(port, /migrateProcessInstances|batchMigrate|definitionWide|rollback/i);
  assert.match(port, /CALL_RETURNED_AWAITING_VERIFICATION/);
  assert.match(port, /AmbiguousMigrationDispatchException/);
});

test('Flowable adapter uses public single-instance validation and dispatch only', () => {
  assert.match(adapter, /createProcessInstanceMigrationBuilder\s*\(\)/);
  assert.match(adapter, /validateMigration\(command\.engineInstanceId\(\)\)/);
  assert.match(adapter, /builder\.migrate\(command\.engineInstanceId\(\)\)/);
  assert.doesNotMatch(adapter, /migrateProcessInstances|batchMigrateProcessInstances/);
  assert.doesNotMatch(adapter, /ACT_[A-Z_]+|org\.flowable\..*\.impl\./);
  assert.match(adapter, /UNSUPPORTED_SOURCE_MODEL_SHAPE/);
  assert.match(adapter, /UNSAFE_JOB_OR_TIMER_STATE/);
  assert.match(adapter, /SUSPENDED_RUNTIME/);
  assert.match(adapter, /TARGET_DEPLOYMENT_DRIFT/);
});

test('platform transaction calls surround but never contain the engine call', () => {
  const prepare = executor.indexOf('executionStore.prepare');
  const engine = executor.indexOf('engineMigration.migrateOne');
  const finalize = executor.indexOf('executionStore.finalizeOutcome');
  assert.ok(prepare >= 0 && engine > prepare && finalize > engine);
  assert.doesNotMatch(executor, /@Transactional|TransactionTemplate|PlatformTransactionManager/);
  assert.match(store, /transactions\.execute\(status -> prepareOnce/);
  assert.match(store, /transactions\.execute\(status -> finalizeOnce/);
  assert.doesNotMatch(store, /ProcessMigrationService|migrateOne\s*\(/);
});

test('ambiguous dispatch is durable UNKNOWN and is not retried', () => {
  assert.match(executor, /FinalDisposition\.AMBIGUOUS_UNKNOWN/);
  assert.match(store, /AttemptStatus\.UNKNOWN/);
  assert.match(store, /EngineOutcome\.UNKNOWN/);
  assert.match(store, /FailureClass\.ENGINE_OUTCOME_UNKNOWN/);
  assert.doesNotMatch(executor, /while\s*\(|for\s*\(|@Retryable|retry\s*\(/i);
});

test('D3 evidence is append-only and fence-revision bound', () => {
  assert.match(migration, /ap_process_migration_engine_request/);
  assert.match(migration, /ap_process_migration_engine_outcome/);
  assert.match(migration, /evidence is append-only/);
  assert.match(migration, /fence_revision/);
  assert.match(migration, /lease_until<=new\.requested_at/);
  assert.match(migration, /lease_until<=new\.recorded_at/);
});

test('one-shot execution remains default disabled and adds no migration scheduler', () => {
  const application = read('apps/server/src/main/resources/application.yml');
  assert.match(application, /migration:\s*[\s\S]*execution:\s*[\s\S]*enabled:\s*\$\{APPROVAL_MIGRATION_EXECUTION_ENABLED:false\}/);
  assert.match(application, /worker:\s*[\s\S]*enabled:\s*\$\{APPROVAL_MIGRATION_WORKER_ENABLED:false\}/);
  assert.match(config, /approval\.migration\.execution\.enabled:false/);
  assert.match(config, /approval\.migration\.worker\.enabled:false/);
  assert.match(executor, /class OneShotRunner/);
  for (const content of [config, executor, adapter, store]) {
    assert.doesNotMatch(content, /@Scheduled|SchedulingConfigurer|TaskScheduler/);
  }
  const migrationNamedSources = sourceFiles('apps/server/src/main/java', /\.java$/)
    .filter(({ relative, content }) => /migration/i.test(relative) || /Migration/.test(content));
  assert.equal(migrationNamedSources.some(({ content }) => /@Scheduled|SchedulingConfigurer/.test(content)), false);
});

test('D3 adds no public execution controller or Web Mobile control', () => {
  const serverSources = sourceFiles('apps/server/src/main/java', /\.java$/);
  for (const { relative, content } of serverSources) {
    if (!/(Controller|Resource)\.java$/.test(relative)) continue;
    assert.doesNotMatch(content, /@(PostMapping|PutMapping|PatchMapping)\s*\([^)]*(migration[^)]*(execute|force|rollback|reconcile)|(execute|force|rollback|reconcile)[^)]*migration)/i);
  }
  assert.doesNotMatch(config, /@RestController|@Controller|RequestMapping|PostMapping/);
  for (const clientRoot of ['apps/web-antd', 'apps/mobile']) {
    const absolute = path.join(root, clientRoot);
    if (!existsSync(absolute)) continue;
    const clientSources = sourceFiles(clientRoot, /\.(vue|ts|tsx|js)$/);
    for (const { content } of clientSources) {
      assert.doesNotMatch(content, /migration.{0,80}(execute|force|rollback|reconcile)/i);
    }
  }
});
