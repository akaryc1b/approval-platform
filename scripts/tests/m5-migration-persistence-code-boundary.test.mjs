import assert from 'node:assert/strict';
import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';

const root = process.cwd();
const domainDir = path.join(
  root,
  'server-modules/approval-domain/src/main/java/io/github/akaryc1b/approval/domain/migration',
);
const jdbcDir = path.join(
  root,
  'server-modules/approval-persistence-jdbc/src/main/java/io/github/akaryc1b/approval/persistence/jdbc',
);
async function text(file) { return readFile(file, 'utf8'); }
async function javaText(directory) {
  const files = (await readdir(directory)).filter((file) => file.endsWith('.java')).sort();
  return Promise.all(files.map((file) => text(path.join(directory, file))));
}

test('domain port and JDBC stores model durable CAS evidence without engine execution', async () => {
  const domain = (await javaText(domainDir)).join('\n');
  const port = await text(path.join(
    root,
    'server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/application/port/ApprovalMigrationProtocolStore.java',
  ));
  const jdbc = (await javaText(jdbcDir))
    .filter((value) => value.includes('ApprovalMigration'))
    .join('\n');
  for (const operation of [
    'IntentStatus', 'AttemptStatus', 'EngineOutcome', 'VerificationOutcome',
    'ReconciliationStatus', 'requireIntentTransition', 'requireAttemptTransition',
    'requireReconciliationTransition', 'UNKNOWN requires durable engine-outcome-unknown evidence',
  ]) assert.ok(domain.includes(operation), `domain omits ${operation}`);
  for (const operation of [
    'createIntent', 'transitionIntent', 'createAttempt', 'transitionAttempt',
    'appendVerification', 'appendReconciliation', 'MigrationProtocolConflictException',
  ]) assert.ok(port.includes(operation), `port omits ${operation}`);
  for (const operation of [
    'TransactionTemplate', 'expectedRevision',
    'insert into ap_process_migration_intent_event',
    'insert into ap_process_migration_attempt_event',
    'on conflict (tenant_id,verification_id) do nothing',
    'on conflict (tenant_id,reconciliation_id) do nothing',
  ]) assert.ok(jdbc.includes(operation), `JDBC stores omit ${operation}`);
  for (const content of [domain, port, jdbc]) {
    assert.doesNotMatch(content, /ACT_[A-Z0-9_]+/);
    assert.doesNotMatch(content, /ProcessMigrationService|ProcessInstanceMigrationBuilder/);
    assert.doesNotMatch(content, /@Scheduled|MigrationWorker|executeMigration|forceMigration/);
  }
});

test('permanent M5-B tests cover domain CAS idempotency lineage and immutable evidence', async () => {
  const domainTests = (await javaText(path.join(
    root,
    'server-modules/approval-domain/src/test/java/io/github/akaryc1b/approval/domain/migration',
  ))).join('\n');
  const jdbcTests = (await javaText(path.join(
    root,
    'server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/persistence/jdbc',
  ))).join('\n');
  for (const testName of [
    'intentUsesClosedTransitionsAndImmutableRevisionProgression',
    'attemptMakesLeaseUnknownAndReconciliationExplicit',
    'verificationCanonicalizesTaskKeysAndRejectsFalseConfirmation',
  ]) assert.ok(domainTests.includes(testName), `domain tests omit ${testName}`);
  for (const testName of [
    'intentCreationIsIdempotentTenantScopedAndPayloadConflictFailsClosed',
    'intentTransitionUsesRevisionCasAndAtomicEventAppend',
    'attemptTransitionsUseCasExclusiveOwnershipAndRetryLineage',
    'verificationAndReconciliationAreIdempotentGapFreeAppendOnlyEvidence',
    'databaseRejectsDirectLifecycleBypassAndEvidenceMutation',
  ]) assert.ok(jdbcTests.includes(testName), `JDBC tests omit ${testName}`);
});
