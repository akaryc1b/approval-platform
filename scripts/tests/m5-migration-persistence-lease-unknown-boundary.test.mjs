import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';

const root = process.cwd();
async function text(file) { return readFile(path.join(root, file), 'utf8'); }

const migrationPath = 'server-modules/approval-persistence-jdbc/src/main/resources/db/migration/V37__strengthen_process_migration_lease_unknown_guards.sql';
const testPath = 'server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcApprovalMigrationLeaseUnknownIntegrationTest.java';
const portPath = 'server-modules/approval-application/src/main/java/io/github/akaryc1b/approval/application/port/ApprovalMigrationProtocolStore.java';
const transitionerPath = 'server-modules/approval-persistence-jdbc/src/main/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcApprovalMigrationAttemptTransitioner.java';
const eventPath = 'server-modules/approval-domain/src/main/java/io/github/akaryc1b/approval/domain/migration/ApprovalMigrationAttemptEvent.java';
const evidencePath = 'docs/M5_B_LEASE_OWNERSHIP_DURABLE_UNKNOWN_PROTOCOL.md';

test('M5-B4 records explicit lease actors and closed renewal or takeover rules', async () => {
  const [sql, port, transitioner, event] = await Promise.all([
    text(migrationPath), text(portPath), text(transitionerPath), text(eventPath),
  ]);
  for (const marker of [
    'String leaseActor',
    'lease-owner fencing is not implemented by this store',
  ]) assert.ok(port.includes(marker), `application port omits ${marker}`);
  for (const marker of [
    'resolveLeaseActor',
    'event.withDurableEvidence(next, effectiveActor)',
    'repository.update(next, expectedRevision, event, effectiveActor)',
  ]) assert.ok(transitioner.includes(marker), `transitioner omits ${marker}`);
  for (const marker of [
    'String leaseActor',
    'withDurableEvidence',
    'lease transition requires exactly one lease actor',
  ]) assert.ok(event.includes(marker), `event evidence omits ${marker}`);
  for (const marker of [
    'lease_actor varchar(200)',
    'migration lease renewal requires current owner before expiry and an extension',
    'migration lease takeover requires expiry and new-owner evidence',
    'migration attempt advance requires current unexpired lease owner',
  ]) assert.ok(sql.includes(marker), `V37 omits ${marker}`);
});

test('M5-B4 makes UNKNOWN request and failure evidence independently durable', async () => {
  const [sql, evidence] = await Promise.all([text(migrationPath), text(evidencePath)]);
  for (const marker of [
    'engine_request_reference varchar(256)',
    'failure_class varchar(32)',
    'error_summary varchar(1000)',
    'UNKNOWN requires preserved durable engine request evidence',
    'UNKNOWN requires open reconciliation before progression',
    'UNKNOWN-derived reconciliation requires terminal evidence before attempt closure',
  ]) assert.ok(sql.includes(marker), `V37 UNKNOWN protocol omits ${marker}`);
  for (const marker of [
    'M5-B4 status: `IMPLEMENTED_AWAITING_PERMANENT_VALIDATION`',
    'Never retry `UNKNOWN` automatically',
    'no worker, scheduler or polling loop',
    'does not authorize M5-C',
  ]) assert.ok(evidence.includes(marker), `M5-B4 evidence omits ${marker}`);
});

test('M5-B4 permanent PostgreSQL tests close lease and UNKNOWN outcomes without sleeps', async () => {
  const source = await text(testPath);
  for (const testName of [
    'currentOwnerClaimsAndRenewsLeaseWithDurableActorEvidence',
    'staleOrDifferentOwnerCannotRenewOrAdvanceAnUnexpiredClaim',
    'expiredOwnerCannotAdvanceAndTakeoverFencesTheFormerOwner',
    'concurrentExpiredTakeoverHasExactlyOneRevisionOwner',
    'unknownPersistsIndependentRequestFailureAndEventColumns',
    'unknownCannotProgressWithoutOpenReconciliationEvidence',
    'unknownDerivedAttemptRequiresTerminalReconciliationBeforeClosure',
    'directLeaseUnknownAndAppendOnlyTamperingFailsClosed',
  ]) assert.ok(source.includes(testName), `M5-B4 test omits ${testName}`);
  assert.match(source, /CountDownLatch/);
  assert.match(source, /Future<Boolean>/);
  assert.doesNotMatch(source, /Thread\.sleep|@Retryable|@Scheduled/);
});

test('M5-B4 remains persistence-only and exposes no migration execution authority', async () => {
  const contents = await Promise.all([
    text(portPath), text(transitionerPath), text(eventPath), text(evidencePath),
  ]);
  const combined = contents.join('\n');
  assert.doesNotMatch(combined, /ACT_[A-Z0-9_]+/);
  assert.doesNotMatch(combined, /ProcessMigrationService|RuntimeService|TaskService/);
  assert.doesNotMatch(combined, /@(?:Post|Put|Patch|Delete)Mapping/);
  assert.doesNotMatch(combined, /ScheduledExecutorService|Thread\.sleep|@Scheduled/);
});
