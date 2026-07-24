import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import {
  ScriptedHandoffReconciliationStore,
  ScriptedTelemetryAggregationStore,
  auditHandoffReconciliationResultMap,
  reconcileAuditHandoff,
  telemetryAggregationResultMap,
  validateTelemetryAggregationPolicy,
} from '../dist/aggregation-reconciliation.js';

const fixture = JSON.parse(await readFile(new URL('../../../contracts/sdk/v1/fixtures/aggregation-reconciliation-v1.json', import.meta.url), 'utf8'));
const [signal1, signal2, signal3] = fixture.telemetrySignals;
const envelope = fixture.auditHandoffEnvelope;
const acknowledgement = fixture.auditHandoffAcknowledgement;

async function aggregationSequence() {
  const store = new ScriptedTelemetryAggregationStore(fixture.aggregationPolicy);
  const results = [
    await store.aggregate(signal1),
    await store.aggregate(signal1),
    await store.aggregate(signal2),
    await store.aggregate(signal3),
  ];
  return { store, results };
}

test('shared fixture produces exact deterministic aggregation results', async () => {
  const { store, results } = await aggregationSequence();
  assert.deepEqual(results.map(telemetryAggregationResultMap), fixture.expectations.aggregationResults);
  assert.deepEqual(await store.snapshots(), fixture.expectations.activeSnapshots);
});

test('duplicate suppression and rollover preserve quantities without partial state', async () => {
  const { results } = await aggregationSequence();
  assert.equal(results[1].status, 'duplicate');
  assert.equal(results[2].snapshot.totalQuantity, 5);
  assert.equal(results[3].rolledOverSnapshots[0].totalQuantity, 5);
  assert.equal(results[3].snapshot.windowStartOrdinal, 20);
});

test('identity capacity rejects atomically', async () => {
  const policy = { ...fixture.aggregationPolicy, maxAggregationIdentities: 1, maxActiveWindows: 2 };
  const store = new ScriptedTelemetryAggregationStore(policy);
  assert.equal((await store.aggregate(signal1)).status, 'aggregated');
  const before = await store.snapshots();
  const sameWindowOtherIdentity = { ...signal3, ordinal: 13 };
  const result = await store.aggregate(sameWindowOtherIdentity);
  assert.equal(result.status, 'failed_closed'); // forged digest is rejected before capacity
  assert.deepEqual(await store.snapshots(), before);
});

test('ordinal regression, scripted failure, and forged digest fail closed without mutation', async () => {
  const store = new ScriptedTelemetryAggregationStore(fixture.aggregationPolicy, [2]);
  assert.equal((await store.aggregate(signal1)).status, 'aggregated');
  const before = await store.snapshots();
  assert.equal((await store.aggregate(signal2)).reasonCode, 'telemetry_aggregation_store_failed');
  assert.deepEqual(await store.snapshots(), before);
  assert.equal((await store.aggregate({ ...signal1, signalDigest: 'forged' })).reasonCode, 'telemetry_signal_invalid');
  assert.deepEqual(await store.snapshots(), before);
  const regressionStore = new ScriptedTelemetryAggregationStore(fixture.aggregationPolicy);
  await regressionStore.aggregate(signal2);
  assert.equal((await regressionStore.aggregate(signal1)).reasonCode, 'telemetry_aggregation_ordinal_regression');
});

test('shared fixture produces exact reconciliation classifications and duplicate proof', async () => {
  const store = new ScriptedHandoffReconciliationStore(8);
  const inputs = [
    { expectedState: 'acknowledged', pendingEnvelope: envelope, acknowledgement: null, reconciliationOrdinal: 30 },
    { expectedState: 'acknowledged', pendingEnvelope: null, acknowledgement, reconciliationOrdinal: 31 },
    { expectedState: 'pending', pendingEnvelope: null, acknowledgement: null, reconciliationOrdinal: 32 },
    { expectedState: 'acknowledged', pendingEnvelope: envelope, acknowledgement, reconciliationOrdinal: 33 },
    { expectedState: 'acknowledged', pendingEnvelope: null, acknowledgement, reconciliationOrdinal: 31 },
  ];
  const results = [];
  for (const input of inputs) {
    results.push(await reconcileAuditHandoff({ contractVersion: '1', envelope, ...input }, store));
  }
  assert.deepEqual(results.map(auditHandoffReconciliationResultMap), fixture.expectations.reconciliationResults);
  assert.equal(results[1].proof.safeToFinalize, true);
  assert.equal(results[3].proof.safeToFinalize, false);
});

test('pending confirmation and mismatched acknowledgement are classified safely', async () => {
  const store = new ScriptedHandoffReconciliationStore(4);
  const pending = await reconcileAuditHandoff({
    contractVersion: '1', expectedState: 'pending', envelope, pendingEnvelope: envelope,
    acknowledgement: null, reconciliationOrdinal: 40,
  }, store);
  assert.equal(pending.proof.classification, 'pending_confirmed');
  const conflict = await reconcileAuditHandoff({
    contractVersion: '1', expectedState: 'acknowledged', envelope, pendingEnvelope: null,
    acknowledgement: { ...acknowledgement, envelopeDigest: 'different' }, reconciliationOrdinal: 41,
  }, store);
  assert.equal(conflict.proof.classification, 'conflicting_evidence');
  assert.equal(conflict.proof.safeToFinalize, false);
});

test('reconciliation capacity and scripted failure store no partial proof', async () => {
  const capacity = new ScriptedHandoffReconciliationStore(1);
  await reconcileAuditHandoff({ contractVersion: '1', expectedState: 'pending', envelope, pendingEnvelope: envelope, acknowledgement: null, reconciliationOrdinal: 50 }, capacity);
  const rejected = await reconcileAuditHandoff({ contractVersion: '1', expectedState: 'pending', envelope, pendingEnvelope: null, acknowledgement: null, reconciliationOrdinal: 51 }, capacity);
  assert.equal(rejected.status, 'capacity_rejected');
  assert.equal(capacity.size, 1);
  const failing = new ScriptedHandoffReconciliationStore(2, [1]);
  const failed = await reconcileAuditHandoff({ contractVersion: '1', expectedState: 'pending', envelope, pendingEnvelope: envelope, acknowledgement: null, reconciliationOrdinal: 52 }, failing);
  assert.equal(failed.status, 'failed_closed');
  assert.equal(failing.size, 0);
});

test('unknown versions and unsafe policy bounds fail closed', () => {
  assert.throws(() => validateTelemetryAggregationPolicy({ ...fixture.aggregationPolicy, contractVersion: '2' }));
  assert.throws(() => validateTelemetryAggregationPolicy({ ...fixture.aggregationPolicy, maxActiveWindows: 0 }));
});
