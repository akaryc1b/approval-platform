import assert from 'node:assert/strict';
import test from 'node:test';

import {
  createBacklogDrainObservations,
} from '../product-readiness/capacity-recovery/backlog-drain-observations.mjs';

function uuid(value) {
  return `00000000-0000-4000-8000-${value.toString(16).padStart(12, '0')}`;
}

function rows(deliveredCount = 0, count = 96) {
  return Array.from({ length: count }, (_, index) => {
    const eventId = uuid(index + 1_001);
    const aggregateId = uuid(index + 2_001);
    const delivered = index < deliveredCount;
    return {
      id: uuid(index + 1),
      eventId,
      eventType: 'purchase-payment.completed.v1',
      aggregateId,
      idempotencyKey: `purchase-payment.completed.v1:${aggregateId}`,
      requestId: `request-${index}`,
      traceId: `trace-${index}`,
      status: delivered ? 'DELIVERED' : 'PENDING',
      attempts: 1,
      lastError: delivered ? null : 'HTTP 503: payment sandbox unavailable',
      responseCode: delivered ? 200 : null,
      providerRequestId: delivered ? `local-payment-sandbox-${eventId}` : null,
      // Deliberately unrelated wall time: latency must use the monotonic input.
      deliveredAt: delivered ? '2099-01-01T00:00:00.000Z' : null,
    };
  });
}

function observer() {
  return createBacklogDrainObservations(rows(), 96);
}

test('records 96 first observations and interpolated latency without counting stability time', () => {
  const value = observer();
  assert.throws(() => value.metrics(), /incomplete drain/u);
  value.observe(rows(24), 100);
  value.observe(rows(48).reverse(), 200);
  value.observe(rows(96), 400);
  const expected = value.metrics();
  assert.deepEqual(expected.latencyMs, {
    minimum: 100, p50: 300, p95: 400, p99: 400, maximum: 400,
  });
  assert.equal(expected.samples, 96);
  assert.equal(expected.firstDeliveredAfterRecoveryMs, 100);
  assert.equal(expected.allDeliveredAfterRecoveryMs, 400);
  assert.equal(expected.deliveredPerSecond, 240);
  assert.equal(expected.clock, 'NODE_PERFORMANCE_MONOTONIC');
  for (let index = 1; index <= 5; index += 1) {
    value.observe(rows(96), 400 + index * 1000);
  }
  assert.deepEqual(value.metrics(), expected);
  const evidence = value.evidence();
  assert.equal(evidence.status, 'ALL_DELIVERED_OBSERVED');
  assert.equal(evidence.observations, 8);
  assert.equal(evidence.samples.length, 96);
  assert.equal(evidence.maximumObservationGapMs, 1000);
  assert.equal(evidence.lastObservedElapsedMs, 5400);
});

test('retains incomplete samples without publishing a completed-drain metric', () => {
  const value = observer();
  value.observe(rows(48), 150);
  const evidence = value.evidence();
  assert.equal(evidence.status, 'INCOMPLETE');
  assert.equal(evidence.observedDeliveredRows, 48);
  assert.equal(evidence.metrics, null);
  assert.throws(() => value.metrics(), /incomplete drain/u);
});

for (const field of ['id', 'eventId', 'eventType', 'aggregateId', 'idempotencyKey', 'requestId', 'traceId']) {
  test(`rejects replacement of ${field} between PENDING and DELIVERED`, () => {
    const value = observer();
    const changed = rows(96);
    changed[95][field] += '-replaced';
    assert.throws(() => value.observe(changed, 100), /changed after the PENDING snapshot/u);
    assert.equal(value.evidence().observedDeliveredRows, 0);
    assert.equal(value.evidence().status, 'FAILED');
    assert.throws(() => value.metrics(), /failed observations/u);
    assert.throws(() => value.observe(rows(96), 200), /cannot resume/u);
  });
}

for (const field of ['id', 'eventId', 'aggregateId', 'idempotencyKey']) {
  test(`rejects duplicate ${field} in baseline and observed rows`, () => {
    const pending = rows();
    pending[95][field] = pending[0][field];
    assert.throws(() => createBacklogDrainObservations(pending, 96), /must be unique/u);
    const changed = rows(96);
    changed[95][field] = changed[0][field];
    assert.throws(() => observer().observe(changed, 100), /must be unique/u);
  });
}

test('rejects missing, additional, sparse or malformed rows', () => {
  for (const changed of [rows().slice(1), [...rows(), rows()[0]], new Array(96), null]) {
    assert.throws(() => observer().observe(changed, 100), /expected exactly|row must be/u);
  }
  const changed = rows();
  changed[0].eventId = '';
  assert.throws(() => observer().observe(changed, 100), /must be nonempty/u);
});

test('bounds the configured row count and requires an HTTP-503 pending baseline', () => {
  for (const count of [0, -1, 97, 1.5, NaN, Infinity, '96']) {
    assert.throws(() => createBacklogDrainObservations(rows(), count), /row count must/u);
  }
  assert.throws(() => createBacklogDrainObservations(rows(1), 96), /baseline must/u);
  const pending = rows();
  pending[0].lastError = 'unrelated error';
  assert.throws(() => createBacklogDrainObservations(pending, 96), /baseline must/u);
});

test('copies the original identities rather than retaining mutable input objects', () => {
  const pending = rows();
  const value = createBacklogDrainObservations(pending, 96);
  pending[0].eventId = 'mutated-input';
  value.observe(rows(96), 100);
  assert.equal(value.metrics().samples, 96);
});

for (const elapsed of [0, -1, NaN, Infinity, -Infinity, '100']) {
  test(`rejects invalid elapsed time ${String(elapsed)}`, () => {
    assert.throws(() => observer().observe(rows(96), elapsed), /positive, finite and monotonic/u);
  });
}

test('rejects a clock regression but permits multiple observations at equal elapsed time', () => {
  const value = observer();
  value.observe(rows(0), 100);
  value.observe(rows(1), 100);
  assert.throws(() => value.observe(rows(2), 99), /monotonic/u);
  assert.equal(value.evidence().observedDeliveredRows, 1);
});

test('rejects invalid and decreasing retry attempt counts', () => {
  for (const attempts of [NaN, Infinity, -1, 0, 1.5, '1']) {
    const changed = rows(96);
    changed[0].attempts = attempts;
    assert.throws(() => observer().observe(changed, 100), /positive safe integer/u);
  }
  const value = observer();
  const retried = rows();
  retried[0].attempts = 2;
  value.observe(retried, 100);
  assert.throws(() => value.observe(rows(96), 200), /attempt count regressed/u);
});

test('rejects delivery status or timestamp regression during the stability window', () => {
  const value = observer();
  value.observe(rows(96), 100);
  assert.throws(() => value.observe(rows(95), 200), /DELIVERED status regressed/u);
  assert.equal(value.evidence().metrics, null);
  const second = observer();
  second.observe(rows(96), 100);
  const changed = rows(96);
  changed[0].deliveredAt = '2099-01-02T00:00:00.000Z';
  assert.throws(() => second.observe(changed, 200), /delivery timestamp changed/u);
});

for (const [field, replacement] of [
  ['responseCode', 500], ['providerRequestId', 'unrelated-provider'],
  ['lastError', 'failed'], ['deliveredAt', null], ['deliveredAt', ''],
]) {
  test(`rejects incomplete provider success evidence: ${field}=${String(replacement)}`, () => {
    const changed = rows(96);
    changed[95][field] = replacement;
    const value = observer();
    assert.throws(() => value.observe(changed, 100), /lacks successful provider evidence/u);
    assert.equal(value.evidence().observedDeliveredRows, 0);
  });
}

test('returned evidence and metrics cannot mutate the retained observations', () => {
  const value = observer();
  value.observe(rows(96), 123.456789);
  const first = value.evidence();
  first.samples[0].firstObservedElapsedMs = -1;
  first.samples.pop();
  first.metrics.latencyMs.p50 = -1;
  assert.equal(value.evidence().samples.length, 96);
  assert.equal(value.evidence().samples[0].firstObservedElapsedMs, 123.456789);
  assert.equal(value.metrics().latencyMs.p50, 123.457);
});

test('real drain uses the observer in recovery and stability reads and always retains it', async () => {
  const { readFileSync } = await import('node:fs');
  const source = readFileSync(new URL(
    '../product-readiness/capacity-recovery/backlog-drain.mjs', import.meta.url,
  ), 'utf8');
  assert.match(source, /createBacklogDrainObservations\(unavailable\.rows, expectedRows\)/u);
  assert.match(source, /const recoveryStartedAt = performance\.now\(\)/u);
  assert.match(source, /observations\.observe\(value\.rows, performance\.now\(\) - recoveryStartedAt\)/u);
  assert.match(source, /after sandbox recovery',[\s\S]*?readRecoveryState,/u);
  assert.match(source, /const stable = readRecoveryState\(\)/u);
  assert.match(source, /recoveryElapsedMs: observedDrain\.allDeliveredAfterRecoveryMs/u);
  assert.match(source, /finally \{[\s\S]*?outbox-backlog-observations\.json[\s\S]*?executionError \?\?= error[\s\S]*?cleanupEvidence = await cleanup/u);
  assert.doesNotMatch(source, /const recoveryStartedAt = Date\.now\(\)/u);
});

test('existing capacity check imports the tests and passed envelopes require observation evidence', async () => {
  const { readFileSync } = await import('node:fs');
  const source = readFileSync(new URL(
    '../product-readiness/capacity-recovery/backlog-drain-evidence.mjs', import.meta.url,
  ), 'utf8');
  assert.match(source, /if \(status === 'PASSED'\)[\s\S]*?'outbox-backlog-observations\.json'/u);
  const boundary = readFileSync(new URL(
    './product-readiness-capacity-recovery-boundary.test.mjs', import.meta.url,
  ), 'utf8');
  assert.match(boundary, /import '\.\/product-readiness-capacity-recovery-backlog-observations\.test\.mjs'/u);
});
