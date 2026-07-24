import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';

const root = process.cwd();
const testPath = path.join(
  root,
  'server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcApprovalMigrationTenantLineageTamperIntegrationTest.java',
);
const migrationPath = path.join(
  root,
  'server-modules/approval-persistence-jdbc/src/main/resources/db/migration/V36__strengthen_process_migration_tenant_lineage_tamper_guards.sql',
);
const evidencePath = path.join(root, 'docs/M5_B_TENANT_LINEAGE_TAMPER_RESISTANCE_PROTOCOL.md');
async function text(file) { return readFile(file, 'utf8'); }

test('M5-B3 permanently covers tenant isolation lineage and tamper resistance', async () => {
  const java = await text(testPath);
  for (const testName of [
    'sameStableUuidsRemainIndependentAcrossTenantsAndReadsNeverCrossTenant',
    'crossTenantIntentAttemptEvidenceAndParentBindingsFailClosed',
    'retryLineageRequiresImmediateRetryableParentInSameIntentAndInstance',
    'currentRowsRequireMatchingEventsAndCannotBeCreatedOrAdvancedAlone',
    'directStableIdentityPayloadHashRevisionAndDeletionTamperingFailsClosed',
    'appendOnlyEvidenceRejectsPayloadHashSequenceUpdateAndDeleteTampering',
    'terminalIntentAttemptAndReconciliationCannotReturnToActiveProgression',
    'changedEvidenceReplayConflictsInsteadOfReturningStoredObjects',
  ]) assert.ok(java.includes(testName), `M5-B3 JDBC tests omit ${testName}`);
  for (const primitive of [
    'OTHER_TENANT', 'insertAttemptDirect', 'insertIntentDirect',
    'MigrationProtocolConflictException', 'DataAccessException',
    'FAILED_RETRYABLE', 'RESOLVED_TERMINAL',
  ]) assert.ok(java.includes(primitive), `M5-B3 JDBC tests omit ${primitive}`);
});

test('V36 closes database lineage payload and event atomicity gaps without execution', async () => {
  const sql = await text(migrationPath);
  for (const rule of [
    'migration intent payload does not match durable columns',
    'migration attempt payload does not match durable columns',
    'migration intent stable payload is immutable',
    'migration attempt stable payload is immutable',
    'migration retry must follow the immediate retryable parent',
    'migration intent event chain is not contiguous',
    'migration attempt event chain is not contiguous',
    'deferrable initially deferred',
  ]) assert.ok(sql.includes(rule), `V36 omits ${rule}`);
  assert.doesNotMatch(sql, /ACT_[A-Z0-9_]+|ProcessMigrationService|@Scheduled|executeMigration/);
});

test('M5-B3 evidence remains persistence-only and does not authorize M5-C', async () => {
  const evidence = await text(evidencePath);
  for (const rule of [
    'M5-B3 status: `IMPLEMENTED_PENDING_PERMANENT_VALIDATION`',
    'tenant-scoped identities',
    'immediate `FAILED_RETRYABLE` parent',
    'current row requires a matching event',
    'does not authorize M5-C',
    'No worker',
    'No Flowable invocation',
    'PR #58 remains Open + Draft',
  ]) assert.ok(evidence.includes(rule), `M5-B3 evidence omits ${rule}`);
});
