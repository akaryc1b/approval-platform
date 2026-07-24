import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import {
  BoundedDiagnosticDeduplicationTracker,
  ScriptedAtomicAuditSink,
  ScriptedInMemoryDiagnosticSink,
  UnsupportedEmissionPolicyVersionError,
  diagnosticFingerprint,
  diagnosticSampleBucket,
  emitCompleteAuditBatch,
  emitDiagnostic,
  validateAuditCompleteness,
  validateDiagnosticEmissionPolicy,
} from '../dist/emission-policy.js';

const fixture = JSON.parse(await readFile(new URL('../../../contracts/sdk/v1/fixtures/emission-policy-v1.json', import.meta.url), 'utf8'));

function setupDiagnostic({ sinkCapacity = 4, failures = [] } = {}) {
  return {
    tracker: new BoundedDiagnosticDeduplicationTracker(fixture.policy.deduplicationCapacity),
    sink: new ScriptedInMemoryDiagnosticSink(sinkCapacity, failures),
  };
}

function request(overrides = {}) {
  return {
    ...fixture.emission,
    diagnostic: fixture.diagnostic,
    ...overrides,
  };
}

function auditRecords() {
  return structuredClone(fixture.auditRecords);
}

test('shared fixture produces deterministic diagnostic and audit decisions', async () => {
  const { tracker, sink } = setupDiagnostic();
  const result = await emitDiagnostic(fixture.policy, request(), tracker, sink);
  assert.equal(result.fingerprint, fixture.expectations.diagnosticFingerprint);
  assert.equal(result.sampleBucket, fixture.expectations.sampleBucket);
  assert.equal(result.status, fixture.expectations.diagnosticStatus);
  assert.equal(sink.size(), 1);

  const validation = await validateAuditCompleteness(fixture.auditPolicy, auditRecords());
  assert.equal(validation.complete, true);
  assert.equal(validation.proof.identityDigest, fixture.expectations.identityDigest);
  assert.equal(validation.proof.batchDigest, fixture.expectations.batchDigest);
  const auditSink = new ScriptedAtomicAuditSink(8);
  const emitted = await emitCompleteAuditBatch(fixture.auditPolicy, auditRecords(), auditSink);
  assert.equal(emitted.status, fixture.expectations.auditStatus);
  assert.equal(emitted.committedRecordCount, fixture.expectations.auditRecordCount);
  assert.equal(auditSink.size(), 4);
});

test('severity threshold and deterministic sampling suppress without mutating state', async () => {
  const { tracker, sink } = setupDiagnostic();
  const below = await emitDiagnostic(
    fixture.policy,
    request({ diagnostic: { ...fixture.diagnostic, severity: 'info' } }),
    tracker,
    sink,
  );
  assert.equal(below.status, 'below_threshold');
  const fingerprint = await diagnosticFingerprint(fixture.diagnostic);
  const bucket = await diagnosticSampleBucket(
    fixture.policy,
    fixture.expectations.sampledOutKey,
    fingerprint,
  );
  assert.equal(bucket, fixture.expectations.sampledOutBucket);
  const sampled = await emitDiagnostic(
    fixture.policy,
    request({ sampleKey: fixture.expectations.sampledOutKey, ordinal: 11 }),
    tracker,
    sink,
  );
  assert.equal(sampled.status, 'sampled_out');
  assert.equal(sink.size(), 0);
  assert.equal(tracker.size(), 0);
});

test('deduplication uses caller ordinals and bounded deterministic eviction', async () => {
  const { tracker, sink } = setupDiagnostic({ sinkCapacity: 8 });
  assert.equal((await emitDiagnostic(fixture.policy, request(), tracker, sink)).status, 'emitted');
  assert.equal((await emitDiagnostic(fixture.policy, request({ emissionId: 'diag-emission-002', ordinal: 12 }), tracker, sink)).status, 'duplicate');
  assert.equal((await emitDiagnostic(fixture.policy, request({ emissionId: 'diag-emission-003', ordinal: 16 }), tracker, sink)).status, 'emitted');
  assert.equal(sink.size(), 2);
  assert.throws(() => tracker.isDuplicate('other', 15, 5), /monotonic/);
});

test('diagnostic sink capacity and scripted failure are stable degraded outcomes', async () => {
  const allPolicy = { ...fixture.policy, sampleNumerator: fixture.policy.sampleDenominator };
  const capacitySetup = setupDiagnostic({ sinkCapacity: 1 });
  assert.equal((await emitDiagnostic(allPolicy, request(), capacitySetup.tracker, capacitySetup.sink)).status, 'emitted');
  const different = { ...fixture.diagnostic, code: 'adapter.binding.other' };
  const capacity = await emitDiagnostic(
    allPolicy,
    request({ emissionId: 'diag-emission-004', ordinal: 11, diagnostic: different }),
    capacitySetup.tracker,
    capacitySetup.sink,
  );
  assert.equal(capacity.status, 'sink_capacity');
  assert.equal(capacitySetup.sink.size(), 1);

  const failedSetup = setupDiagnostic({ failures: [1] });
  const failed = await emitDiagnostic(allPolicy, request(), failedSetup.tracker, failedSetup.sink);
  assert.equal(failed.status, 'sink_failed');
  assert.equal(failed.reasonCode, 'diagnostic_sink_failed');
  assert.doesNotMatch(JSON.stringify(failed), /scripted diagnostic sink failure/);
  assert.equal(failedSetup.sink.size(), 0);
});

test('audit completeness rejects missing, reordered and identity-drifted records', async () => {
  const missing = auditRecords().slice(0, -1);
  assert.equal((await validateAuditCompleteness(fixture.auditPolicy, missing)).reason, 'record_count_mismatch');
  const reordered = auditRecords();
  reordered[1].sequence = 2;
  assert.equal((await validateAuditCompleteness(fixture.auditPolicy, reordered)).reason, 'sequence_mismatch');
  const drift = auditRecords();
  drift[2].event.requestId = 'req-drift';
  assert.equal((await validateAuditCompleteness(fixture.auditPolicy, drift)).reason, 'identity_mismatch');
  const sink = new ScriptedAtomicAuditSink(8);
  const result = await emitCompleteAuditBatch(fixture.auditPolicy, drift, sink);
  assert.equal(result.status, 'failed_closed');
  assert.equal(sink.size(), 0);
});

test('audit capacity, scripted failure and duplicate batch are atomic fail-closed outcomes', async () => {
  const capacitySink = new ScriptedAtomicAuditSink(3);
  const capacity = await emitCompleteAuditBatch(fixture.auditPolicy, auditRecords(), capacitySink);
  assert.equal(capacity.reasonCode, 'audit_sink_capacity');
  assert.equal(capacitySink.size(), 0);

  const failedSink = new ScriptedAtomicAuditSink(8, [1]);
  const failed = await emitCompleteAuditBatch(fixture.auditPolicy, auditRecords(), failedSink);
  assert.equal(failed.reasonCode, 'audit_sink_failed');
  assert.doesNotMatch(JSON.stringify(failed), /DO-NOT-LEAK/);
  assert.equal(failedSink.size(), 0);

  const duplicateSink = new ScriptedAtomicAuditSink(12);
  assert.equal((await emitCompleteAuditBatch(fixture.auditPolicy, auditRecords(), duplicateSink)).status, 'committed');
  const duplicate = await emitCompleteAuditBatch(fixture.auditPolicy, auditRecords(), duplicateSink);
  assert.equal(duplicate.reasonCode, 'audit_duplicate_batch');
  assert.equal(duplicateSink.size(), 4);
});

test('unsupported versions and unsafe bounds fail closed', () => {
  assert.throws(
    () => validateDiagnosticEmissionPolicy({ ...fixture.policy, contractVersion: '2' }),
    UnsupportedEmissionPolicyVersionError,
  );
  assert.throws(
    () => validateDiagnosticEmissionPolicy({ ...fixture.policy, sampleNumerator: 5 }),
    /cannot exceed/,
  );
  assert.throws(() => new BoundedDiagnosticDeduplicationTracker(0), /positive/);
  assert.throws(() => new ScriptedAtomicAuditSink(10_001), /cannot exceed/);
});
