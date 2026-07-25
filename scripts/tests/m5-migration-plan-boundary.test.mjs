import assert from 'node:assert/strict';
import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';

const root = process.cwd();
const read = (file) => readFile(path.join(root, file), 'utf8');
const app = 'server-modules/approval-application/src/main/java/io/github/akaryc1b/approval';
const jdbc = 'server-modules/approval-persistence-jdbc/src';

test('M5-C1 is permanently validated plan-only evidence below execution authority', async () => {
  const evidence = await read('docs/M5_C_IMMUTABLE_MIGRATION_PLAN_PROTOCOL.md');
  for (const value of [
    'M5-C stage status: `IN_PROGRESS`',
    'M5-C1 status: `PERMANENTLY_VALIDATED`',
    'M5-C1 evidence freeze status: `IMPLEMENTED_AWAITING_FINAL_VALIDATION`',
    'M5-B governance decision remains `ACCEPTED`',
    'It does not create an execution intent, invoke Flowable, mutate runtime bindings',
    'Run ID: `30113635674`',
    'run number: `#532`',
    'Run ID: `30136606769`',
    'run number: `#533`',
    'Run ID: `30136814277`',
    'run number: `#534`',
    'Maven aggregate: `560` tests',
    'M5-C1 domain/application/JDBC total: `20/20`',
    'M5 permanent Node boundaries: `35/35`',
    'dd00cb2d11bf04ed947a292bbf59e9101b94e686306f600cdd2050268903ee37',
    'M5-D and\nproduction execution remain `NOT_AUTHORIZED`',
  ]) assert.ok(evidence.includes(value), `missing ${value}`);
  assert.doesNotMatch(evidence, /M5-C stage status: `ACCEPTED`/);
  assert.doesNotMatch(evidence, /M5-D stage authorization: `AUTHORIZED`/);
});

test('V38 creates four guarded plan tables without changing M5-B intent', async () => {
  const migrationDir = path.join(
    root,
    'server-modules/approval-persistence-jdbc/src/main/resources/db/migration',
  );
  const javaDir = path.join(
    root,
    'server-modules/approval-persistence-jdbc/src/main/java/db/migration',
  );
  const javaFiles = await readdir(javaDir);
  assert.ok(javaFiles.includes('V38__Create_immutable_process_migration_plans.java'));
  assert.ok(javaFiles.every((file) => !/^V(?:39|[4-9][0-9])__/.test(file)));
  const migration = await read(path.join(
    'server-modules/approval-persistence-jdbc/src/main/java/db/migration',
    'V38__Create_immutable_process_migration_plans.java',
  ));
  for (const marker of [
    'extends BaseJavaMigration',
    'public Integer getChecksum()',
    'statement.execute(readSql())',
    'db/migration/v38/part-01.sqlpart',
  ]) assert.ok(migration.includes(marker), `V38 Java migration missing ${marker}`);
  const partDir = path.join(migrationDir, 'v38');
  const parts = (await readdir(partDir)).sort();
  assert.deepEqual(parts, [
    'part-01.sqlpart', 'part-02.sqlpart', 'part-03.sqlpart', 'part-04.sqlpart',
    'part-05.sqlpart', 'part-06.sqlpart', 'part-07.sqlpart',
  ]);
  const sql = (await Promise.all(parts.map((file) => read(path.join(
    'server-modules/approval-persistence-jdbc/src/main/resources/db/migration/v38',
    file,
  ))))).join('');
  for (const value of [
    'create table ap_process_migration_plan (',
    'create table ap_process_migration_plan_instance (',
    'create table ap_process_migration_plan_authorization (',
    'create table ap_process_migration_plan_event (',
    'target_deployment_record_id uuid not null',
    'target_engine_deployment_id varchar(256) not null',
    'target_engine_definition_id varchar(256) not null',
    'target_engine_version integer not null',
    'migration plan target deployment identity is not current and exact',
    'migration plan canonical content is immutable',
    'migration plan selected instance count must match immutable plan',
    'migration plan authorization does not match current immutable plan',
    'migration plan current row requires matching durable event',
    "old.status='PROPOSED' and new.status='AUTHORIZED'",
  ]) assert.ok(sql.includes(value), `V38 missing ${value}`);
  assert.doesNotMatch(sql, /ACT_[A-Z0-9_]+|alter table ap_process_migration_intent/);
});

test('production code has exact plan gates and no execution surface', async () => {
  const files = [
    `${app}/application/ApprovalMigrationPlanService.java`,
    `${app}/application/ApprovalMigrationPlanEvidenceValidator.java`,
    `${app}/application/ApprovalMigrationPlanSupport.java`,
    `${app}/application/port/ApprovalMigrationPlanAuthorizationGate.java`,
    `${app}/application/port/ApprovalMigrationPlanStore.java`,
    `${jdbc}/main/java/io/github/akaryc1b/approval/persistence/jdbc/`
      + 'JdbcApprovalMigrationPlanStore.java',
    `${jdbc}/main/java/io/github/akaryc1b/approval/persistence/jdbc/`
      + 'JdbcApprovalMigrationPlanRepository.java',
    `${jdbc}/main/java/io/github/akaryc1b/approval/persistence/jdbc/`
      + 'JdbcApprovalMigrationPlanWriter.java',
  ];
  const code = (await Promise.all(files.map(read))).join('\n');
  for (const value of [
    'createPlan(',
    'authorizePlan(',
    'requireAuthorizedPlan(',
    'authorizationGate.requireAuthorization(',
    'must not trust browser or mobile authorization evidence',
    'requester cannot authorize',
    'complete READY detect-only assessment',
    'targetDeployment.deploymentRecordId()',
    'targetDeployment.engineDeploymentId().equals(plan.targetEngineDeploymentId())',
    'targetDeployment.engineDefinitionId().equals(plan.targetEngineDefinitionId())',
    'current.targetEngineDefinitionId()',
    'values.add(item.expectedActiveTaskDefinitionKeys().size())',
  ]) assert.ok(code.includes(value), `code missing ${value}`);
  for (const forbidden of [
    /createIntent\(/,
    /transitionIntent\(/,
    /import org\.flowable/,
    /RuntimeService/,
    /MigrationBuilder/,
    /ACT_[A-Z0-9_]+/,
    /@RestController/,
    /@Scheduled/,
    /Thread\.sleep/,
  ]) assert.doesNotMatch(code, forbidden);
});

test('permanent PostgreSQL tests cover replay concurrency tenancy and tamper rejection', async () => {
  const files = [
    `${jdbc}/test/java/io/github/akaryc1b/approval/persistence/jdbc/`
      + 'JdbcApprovalMigrationPlanStoreIntegrationTest.java',
    `${jdbc}/test/java/io/github/akaryc1b/approval/persistence/jdbc/`
      + 'JdbcApprovalMigrationPlanSecurityIntegrationTest.java',
  ];
  const source = (await Promise.all(files.map(read))).join('\n');
  for (const name of [
    'createsImmutablePlanWithSelectionAndExactReplay',
    'idempotencyAndCanonicalPlanHashConflictsFailClosed',
    'sameStableIdentitiesCoexistAcrossTenantsAndReadsStayScoped',
    'exactIndependentAuthorizationOpensAuthorizedReadGate',
    'mismatchedAuthorizationAndStaleRevisionFailClosed',
    'concurrentAuthorizationProducesOneRevisionOwner',
    'targetDeploymentIdentityDriftRejectsAuthorizationAtDatabaseBoundary',
    'directCurrentAndAppendOnlyEvidenceTamperingIsRejected',
    'crossTenantAuthorizationReferenceCannotBindAnotherTenantPlan',
  ]) assert.ok(source.includes(name), `PostgreSQL tests omit ${name}`);
  assert.match(source, /CountDownLatch/);
  assert.match(source, /Future<Boolean>/);
  assert.doesNotMatch(source, /Thread\.sleep|@Retryable|@Scheduled/);
});
