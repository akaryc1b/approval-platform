import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import {
  ScriptedTelemetryExporter,
  UnsupportedTelemetryHandoffVersionError,
  createReferenceTelemetrySignal,
  exportTelemetryBatch,
  telemetryExportResultMap,
  telemetrySignalMap,
  validateTelemetryAttributePolicy,
} from '../dist/telemetry-signal.js';
import {
  ScriptedAuditHandoffQueue,
  auditHandoffEnvelopeMap,
  auditHandoffResultMap,
  createAuditHandoffEnvelope,
} from '../dist/audit-handoff.js';

const fixture = JSON.parse(await readFile(new URL('../../../contracts/sdk/v1/fixtures/telemetry-handoff-v1.json', import.meta.url), 'utf8'));

async function setupTelemetry() {
  const signals = [];
  for (const input of fixture.telemetrySignals) {
    signals.push(await createReferenceTelemetrySignal(fixture.telemetryPolicy, input));
  }
  return signals;
}

async function setupHandoff(outcomes = fixture.auditHandoff.scriptedOutcomes, capacity = fixture.auditHandoff.queueCapacity) {
  const envelope = await createAuditHandoffEnvelope({
    contractVersion: fixture.auditHandoff.contractVersion,
    handoffId: fixture.auditHandoff.handoffId,
    destinationReference: fixture.auditHandoff.destinationReference,
    handoffOrdinal: fixture.auditHandoff.handoffOrdinal,
    proof: fixture.auditProof,
  });
  return { envelope, queue: new ScriptedAuditHandoffQueue(capacity, outcomes) };
}

test('shared fixture produces exact reference-only telemetry and handoff acknowledgement', async () => {
  const signals = await setupTelemetry();
  const exporter = new ScriptedTelemetryExporter(fixture.telemetryExporter.capacity);
  const telemetryExport = await exportTelemetryBatch(fixture.telemetryPolicy, signals, exporter);
  const { envelope, queue } = await setupHandoff();
  const handoffResults = [];
  for (let index = 0; index < 4; index += 1) handoffResults.push(await queue.handoff(envelope));
  assert.deepEqual(signals.map(telemetrySignalMap), fixture.expectations.telemetrySignals);
  assert.deepEqual(telemetryExportResultMap(telemetryExport), fixture.expectations.telemetryExport);
  assert.deepEqual(auditHandoffEnvelopeMap(envelope), fixture.expectations.auditHandoffEnvelope);
  assert.deepEqual(handoffResults.map(auditHandoffResultMap), fixture.expectations.auditHandoffResults);
});

test('unknown versions and unapproved signal names fail closed', async () => {
  assert.throws(
    () => validateTelemetryAttributePolicy({ ...fixture.telemetryPolicy, contractVersion: '2' }),
    UnsupportedTelemetryHandoffVersionError,
  );
  await assert.rejects(
    createReferenceTelemetrySignal(fixture.telemetryPolicy, {
      ...fixture.telemetrySignals[0],
      signalName: 'approval.unapproved',
    }),
    /not allowed/,
  );
});

test('telemetry attributes are allowlisted and trusted or sensitive keys are rejected', async () => {
  await assert.rejects(
    createReferenceTelemetrySignal(fixture.telemetryPolicy, {
      ...fixture.telemetrySignals[0],
      attributes: { token: 'not-allowed' },
    }),
    /not allowed|Forbidden/,
  );
  assert.throws(
    () => validateTelemetryAttributePolicy({
      ...fixture.telemetryPolicy,
      allowedAttributeKeys: [...fixture.telemetryPolicy.allowedAttributeKeys, 'tenantId'],
      maxAttributeCount: 4,
    }),
    /Forbidden/,
  );
});

test('telemetry exporter capacity and scripted failure are atomic degraded outcomes', async () => {
  const signals = await setupTelemetry();
  const capacity = new ScriptedTelemetryExporter(1);
  const capacityResult = await exportTelemetryBatch(fixture.telemetryPolicy, signals, capacity);
  assert.equal(capacityResult.status, 'degraded');
  assert.equal(capacityResult.reasonCode, 'telemetry_exporter_capacity');
  assert.equal(capacity.size, 0);

  const failed = new ScriptedTelemetryExporter(2, [1]);
  const failedResult = await exportTelemetryBatch(fixture.telemetryPolicy, signals, failed);
  assert.equal(failedResult.status, 'degraded');
  assert.equal(failedResult.reasonCode, 'telemetry_exporter_failed');
  assert.equal(failed.size, 0);
});

test('duplicate telemetry batches degrade without duplicate records and ordinal regression is rejected', async () => {
  const signals = await setupTelemetry();
  const exporter = new ScriptedTelemetryExporter(4);
  assert.equal((await exportTelemetryBatch(fixture.telemetryPolicy, signals, exporter)).status, 'exported');
  const duplicate = await exportTelemetryBatch(fixture.telemetryPolicy, signals, exporter);
  assert.equal(duplicate.reasonCode, 'telemetry_duplicate_batch');
  assert.equal(exporter.size, 2);
  await assert.rejects(
    exportTelemetryBatch(fixture.telemetryPolicy, [signals[1], signals[0]], new ScriptedTelemetryExporter(2)),
    /ordinals/,
  );
});

test('nack and timeout-like outcomes preserve pending handoff before atomic acknowledgement', async () => {
  const { envelope, queue } = await setupHandoff();
  const nack = await queue.handoff(envelope);
  const timeout = await queue.handoff(envelope);
  const ack = await queue.handoff(envelope);
  const duplicateAck = await queue.handoff(envelope);
  assert.deepEqual([nack.status, timeout.status, ack.status, duplicateAck.status], [
    'pending',
    'pending',
    'acknowledged',
    'duplicate_acknowledged',
  ]);
  assert.equal(nack.pendingCount, 1);
  assert.equal(timeout.pendingCount, 1);
  assert.equal(ack.pendingCount, 0);
  assert.equal(ack.acknowledgedCount, 1);
  assert.equal(duplicateAck.acknowledgement?.acknowledgementId, ack.acknowledgement?.acknowledgementId);
});

test('handoff queue capacity and scripted failure preserve no-loss pending state', async () => {
  const { envelope } = await setupHandoff();
  const failedQueue = new ScriptedAuditHandoffQueue(1, ['failure', 'ack']);
  const failed = await failedQueue.handoff(envelope);
  assert.equal(failed.status, 'failed_closed');
  assert.equal(failed.pendingCount, 1);
  assert.equal(failed.acknowledgedCount, 0);
  const recovered = await failedQueue.handoff(envelope);
  assert.equal(recovered.status, 'acknowledged');

  const secondEnvelope = await createAuditHandoffEnvelope({
    contractVersion: '1',
    handoffId: 'handoff-fixture-002',
    destinationReference: 'audit-queue-fixture',
    handoffOrdinal: 30,
    proof: fixture.auditProof,
  });
  const fullQueue = new ScriptedAuditHandoffQueue(1, ['nack']);
  await fullQueue.handoff(envelope);
  const capacity = await fullQueue.handoff(secondEnvelope);
  assert.equal(capacity.status, 'capacity_rejected');
  assert.equal(fullQueue.pendingCount, 1);
});

test('conflicting replay and incomplete proof fail closed without replacing acknowledged identity', async () => {
  const { envelope, queue } = await setupHandoff(['ack']);
  const ack = await queue.handoff(envelope);
  const conflict = await queue.handoff({ ...envelope, envelopeDigest: 'conflicting-digest' });
  assert.equal(conflict.status, 'failed_closed');
  assert.equal(conflict.reasonCode, 'audit_handoff_identity_conflict');
  assert.equal(queue.acknowledgedCount, 1);
  const duplicate = await queue.handoff(envelope);
  assert.equal(duplicate.status, 'duplicate_acknowledged');
  assert.equal(duplicate.acknowledgement?.acknowledgementId, ack.acknowledgement?.acknowledgementId);

  await assert.rejects(
    createAuditHandoffEnvelope({
      contractVersion: '1',
      handoffId: 'invalid',
      destinationReference: 'audit-queue-fixture',
      handoffOrdinal: 0,
      proof: { ...fixture.auditProof, recordCount: 3 },
    }),
    /recordCount/,
  );
});
