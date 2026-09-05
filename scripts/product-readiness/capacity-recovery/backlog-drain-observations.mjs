import { percentile } from './statistics.mjs';

const identityFields = Object.freeze([
  'id', 'eventId', 'eventType', 'aggregateId', 'idempotencyKey',
]);
const uniqueFields = Object.freeze([
  'id', 'eventId', 'aggregateId', 'idempotencyKey',
]);
const maximumRows = 96;

function requireCondition(condition, message) {
  if (!condition) throw new Error(`backlog observation: ${message}`);
}

function rounded(value) {
  return Number(value.toFixed(3));
}

function validateRows(rows, expectedRows) {
  requireCondition(
    Array.isArray(rows) && rows.length === expectedRows,
    `expected exactly ${expectedRows} rows`,
  );
  for (const row of rows) {
    requireCondition(row && typeof row === 'object', 'row must be an object');
    for (const field of identityFields) {
      requireCondition(
        typeof row[field] === 'string' && row[field].trim().length > 0,
        `${field} must be nonempty`,
      );
    }
    requireCondition(
      Number.isSafeInteger(row.attempts) && row.attempts >= 1,
      'attempts must be a positive safe integer',
    );
  }
  for (const field of uniqueFields) {
    requireCondition(
      new Set(rows.map(row => row[field])).size === expectedRows,
      `${field} must be unique`,
    );
  }
}

/**
 * Pins the already validated HTTP-503 backlog to its original identities.
 * Times are caller-supplied monotonic elapsed times AFTER each database read.
 * They measure first observation, not provider latency or database timestamps.
 */
export function createBacklogDrainObservations(pendingRows, expectedRows) {
  requireCondition(
    Number.isInteger(expectedRows) && expectedRows >= 1 && expectedRows <= maximumRows,
    `row count must be between 1 and ${maximumRows}`,
  );
  validateRows(pendingRows, expectedRows);
  const baseline = new Map();
  for (const row of pendingRows) {
    requireCondition(
      row.status === 'PENDING' && row.responseCode === null
        && row.providerRequestId === null && row.deliveredAt === null
        && row.lastError?.startsWith('HTTP 503: payment sandbox unavailable'),
      'baseline must contain only undelivered HTTP-503 PENDING rows',
    );
    baseline.set(row.id, Object.freeze({
      ...Object.fromEntries(identityFields.map(field => [field, row[field]])),
      requestId: row.requestId,
      traceId: row.traceId,
      attempts: row.attempts,
    }));
  }

  const samples = new Map();
  const attempts = new Map(pendingRows.map(row => [row.id, row.attempts]));
  let observations = 0;
  let lastElapsedMs = 0;
  let maximumObservationGapMs = 0;
  let completeElapsedMs = null;
  let failure = null;

  function observe(rows, elapsedMs) {
    requireCondition(failure === null, 'observer is failed and cannot resume');
    try {
      requireCondition(
        Number.isFinite(elapsedMs) && elapsedMs > 0 && elapsedMs >= lastElapsedMs,
        'elapsed time must be positive, finite and monotonic',
      );
      validateRows(rows, expectedRows);
      // Validate the whole snapshot before updating any retained observation.
      for (const row of rows) {
        const original = baseline.get(row.id);
        requireCondition(Boolean(original), 'Outbox id changed after the PENDING snapshot');
        for (const field of [...identityFields, 'requestId', 'traceId']) {
          requireCondition(
            row[field] === original[field],
            `${field} changed after the PENDING snapshot`,
          );
        }
        requireCondition(row.attempts >= attempts.get(row.id), 'attempt count regressed');
        requireCondition(
          !samples.has(row.id) || row.status === 'DELIVERED',
          'DELIVERED status regressed',
        );
        if (row.status === 'DELIVERED') {
          requireCondition(
            row.responseCode === 200 && row.lastError === null
              && row.providerRequestId === `local-payment-sandbox-${row.eventId}`
              && typeof row.deliveredAt === 'string'
              && row.deliveredAt.trim().length > 0,
            'DELIVERED row lacks successful provider evidence',
          );
          const previous = samples.get(row.id);
          requireCondition(
            !previous || previous.deliveredAt === row.deliveredAt,
            'delivery timestamp changed after first observation',
          );
        }
      }
      for (const row of rows) {
        attempts.set(row.id, row.attempts);
        if (row.status === 'DELIVERED' && !samples.has(row.id)) {
          samples.set(row.id, {
            ...Object.fromEntries(identityFields.map(field => [field, row[field]])),
            providerRequestId: row.providerRequestId,
            deliveredAt: row.deliveredAt,
            firstObservedElapsedMs: elapsedMs,
          });
        }
      }
      observations += 1;
      maximumObservationGapMs = Math.max(
        maximumObservationGapMs,
        elapsedMs - lastElapsedMs,
      );
      lastElapsedMs = elapsedMs;
      if (completeElapsedMs === null && samples.size === expectedRows) {
        completeElapsedMs = elapsedMs;
      }
    } catch (error) {
      failure = error instanceof Error ? error.message : String(error);
      throw error;
    }
  }

  function metrics() {
    requireCondition(failure === null, 'failed observations cannot publish metrics');
    requireCondition(completeElapsedMs !== null, 'cannot summarize an incomplete drain');
    const latencies = [...samples.values()].map(sample => sample.firstObservedElapsedMs);
    return {
      samples: latencies.length,
      firstDeliveredAfterRecoveryMs: rounded(Math.min(...latencies)),
      allDeliveredAfterRecoveryMs: rounded(completeElapsedMs),
      deliveredPerSecond: rounded(expectedRows / (completeElapsedMs / 1000)),
      latencyMs: {
        minimum: rounded(Math.min(...latencies)),
        p50: rounded(percentile(latencies, 0.5)),
        p95: rounded(percentile(latencies, 0.95)),
        p99: rounded(percentile(latencies, 0.99)),
        maximum: rounded(Math.max(...latencies)),
      },
      clock: 'NODE_PERFORMANCE_MONOTONIC',
      boundary: 'RECOVERY_CONTROL_WRITE_START_TO_FIRST_OBSERVED_DELIVERED',
      interpretation: 'POLL_OBSERVED_COMPLETION_NOT_PROVIDER_LATENCY',
    };
  }

  function evidence() {
    return {
      schemaVersion: 1,
      evidenceKind: 'CAPACITY_BACKLOG_DRAIN_OBSERVATIONS_V1',
      status: failure !== null ? 'FAILED'
        : completeElapsedMs === null ? 'INCOMPLETE' : 'ALL_DELIVERED_OBSERVED',
      expectedRows,
      observedDeliveredRows: samples.size,
      observations,
      maximumObservationGapMs: rounded(maximumObservationGapMs),
      lastObservedElapsedMs: rounded(lastElapsedMs),
      samePendingEventSetVerified: observations > 0 && failure === null,
      failure,
      metrics: failure === null && completeElapsedMs !== null ? metrics() : null,
      samples: [...samples.values()]
        .sort((left, right) => left.id.localeCompare(right.id))
        .map(sample => ({ ...sample })),
    };
  }

  return Object.freeze({ observe, metrics, evidence });
}
