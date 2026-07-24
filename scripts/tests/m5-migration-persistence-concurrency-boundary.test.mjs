import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';

const root = process.cwd();
const javaTest = path.join(
  root,
  'server-modules/approval-persistence-jdbc/src/test/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcApprovalMigrationProtocolConcurrencyIntegrationTest.java',
);
const intentCreator = path.join(
  root,
  'server-modules/approval-persistence-jdbc/src/main/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcApprovalMigrationIntentCreator.java',
);
const attemptCreator = path.join(
  root,
  'server-modules/approval-persistence-jdbc/src/main/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcApprovalMigrationAttemptCreator.java',
);
const protocolDocument = path.join(
  root,
  'docs/M5_B_CONCURRENT_REPLAY_AND_CONFLICT_PROTOCOL.md',
);

async function text(file) { return readFile(file, 'utf8'); }

test('M5-B2 permanently requires real concurrent replay and bounded conflict evidence', async () => {
  const java = await text(javaTest);
  for (const token of [
    'Executors.newFixedThreadPool(2)',
    'CountDownLatch ready',
    'CountDownLatch start',
    'independentStore()',
    'identicalIntentCreationHasOneInsertAndOneAuthoritativeReplay',
    'reusedIntentIdempotencyKeyHasOneWinnerAndNoSecondEvent',
    'sharedPlanHashHasOneIntentWinnerAndLoserFailsClosed',
    'concurrentIntentsCannotOwnTheSameActiveInstance',
    'identicalAttemptCreationHasOneInsertAndOneReplay',
    'changedAttemptIdentityHasOneWinnerAndNoOrphanEvent',
    'verificationIdentityReplayAndChangedPayloadAreClosedUnderConcurrency',
    'verificationSequenceCompetitionHasOneWinnerAndNoGap',
    'reconciliationIdentityReplayAndChangedPayloadAreClosedUnderConcurrency',
    'reconciliationSequenceCompetitionHasOneWinnerAndNoGap',
    'terminalReconciliationAndCompetingEvidenceProduceAClosedResult',
    'MigrationProtocolConflictException',
  ]) assert.ok(java.includes(token), `concurrency evidence omits ${token}`);

  assert.doesNotMatch(java, /Thread\.sleep|@Scheduled|ProcessMigrationService|ACT_[A-Z0-9_]+/);
  assert.doesNotMatch(java, /executeMigration|forceMigration|rollbackMigration|MigrationWorker/);
});

test('M5-B2 exact replay survives competing unique constraints without weakening conflicts', async () => {
  const [intent, attempt] = await Promise.all([text(intentCreator), text(attemptCreator)]);
  for (const source of [intent, attempt]) {
    assert.ok(source.includes('catch (DataIntegrityViolationException exception)'));
    assert.ok(source.includes('concurrentReplay'));
    assert.ok(source.includes('value.equals(concurrentReplay)'));
    assert.ok(source.includes('MigrationProtocolConflictException'));
    assert.doesNotMatch(source, /Thread\.sleep|while\s*\(|for\s*\(;;\)|@Retryable/);
  }
  assert.ok(intent.includes('findByIdempotencyKey'));
  assert.ok(attempt.includes('value.attemptId()'));
});

test('M5-B2 protocol freezes exact outcomes without opening execution authority', async () => {
  const protocol = await text(protocolDocument);
  for (const token of [
    'M5-B2 — Concurrent Replay and Conflict Protocol',
    'one insert and one exact replay',
    'one winner and one bounded conflict',
    'no orphan initial event',
    'no sequence gap',
    'terminal evidence cannot advance',
    'PostgreSQL Testcontainers',
    'No V36 is required by this slice',
    'Run `30078875680` / #517',
    'post-rollback authoritative read',
    'M5-B remains IN_PROGRESS',
    'M5-C is not authorized',
  ]) assert.ok(protocol.includes(token), `M5-B2 protocol omits ${token}`);

  assert.doesNotMatch(protocol, /production ready|execution enabled|M5-C authorized/);
});
