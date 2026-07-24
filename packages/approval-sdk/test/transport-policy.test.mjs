import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import {
  ScriptedConformanceAdapter,
  UnsupportedTransportPolicyVersionError,
  classifyTransportResponse,
  decideTransportRetry,
  executeTransportConformance,
  validateTransportPolicy,
} from '../dist/public.js';

const fixturePath = new URL('../../../contracts/sdk/v1/fixtures/transport-policy-v1.json', import.meta.url);
const fixture = JSON.parse(await readFile(fixturePath, 'utf8'));

function response(overrides = {}) {
  return {
    statusCode: 503,
    payload: null,
    errorCode: 'temporary_failure',
    errorMessage: 'Retry later',
    retryAfterMillis: null,
    ...overrides,
  };
}

test('cross-language transport fixture produces the exact deterministic trace', () => {
  const adapter = new ScriptedConformanceAdapter(fixture.script);
  const result = executeTransportConformance(fixture.request, fixture.policy, adapter);
  assert.deepEqual(result, {
    status: fixture.expectations.status,
    value: fixture.expectations.value,
    error: fixture.expectations.error,
    attempts: fixture.expectations.attempts,
    totalElapsedMillis: fixture.expectations.totalElapsedMillis,
  });
  assert.deepEqual(
    adapter.invocations.map((attempt) => attempt.elapsedMillis),
    fixture.expectations.invocationElapsedMillis,
  );
  assert.ok(adapter.invocations.every((attempt) => attempt.request === fixture.request));
});

test('unknown policy versions, duplicate status codes and unbounded budgets fail closed', () => {
  assert.throws(
    () => validateTransportPolicy({ ...fixture.policy, policyVersion: '2' }),
    UnsupportedTransportPolicyVersionError,
  );
  assert.throws(
    () => validateTransportPolicy({ ...fixture.policy, retryableStatusCodes: [503, 503] }),
    /Duplicate retryable status code/,
  );
  assert.throws(
    () => validateTransportPolicy({
      ...fixture.policy,
      budget: { ...fixture.policy.budget, totalBudgetMillis: 300_001 },
    }),
    /totalBudgetMillis/,
  );
});

test('response mapping is structured and defaults unknown permanent failures', () => {
  const retryable = classifyTransportResponse(fixture.policy, response({ statusCode: 429 }), 'req-1');
  assert.equal(retryable.category, 'retryable');
  assert.equal(retryable.error.category, 'retryable');

  const unauthorized = classifyTransportResponse(fixture.policy, response({ statusCode: 403 }), 'req-1');
  assert.equal(unauthorized.category, 'unauthorized');
  assert.equal(unauthorized.error.category, 'unauthorized');

  const conflict = classifyTransportResponse(fixture.policy, response({ statusCode: 409 }), 'req-1');
  assert.equal(conflict.category, 'conflict');

  const unsupported = classifyTransportResponse(fixture.policy, response({ statusCode: 426 }), 'req-1');
  assert.equal(unsupported.category, 'unsupported_version');

  const permanent = classifyTransportResponse(
    fixture.policy,
    response({ statusCode: 422, errorCode: null, errorMessage: null }),
    'req-1',
  );
  assert.deepEqual(permanent.error, {
    code: 'transport_status_422',
    message: 'Transport response status 422',
    category: 'permanent',
    requestId: 'req-1',
  });
});

test('retry requires both an idempotent policy and an idempotency key', () => {
  const classification = classifyTransportResponse(fixture.policy, response(), 'req-1');
  assert.equal(decideTransportRetry(
    { ...fixture.policy, retryMode: 'never' },
    { attempt: 1, elapsedMillis: 100, idempotencyKeyPresent: true },
    classification,
  ).reason, 'retry_disabled');
  assert.equal(decideTransportRetry(
    fixture.policy,
    { attempt: 1, elapsedMillis: 100, idempotencyKeyPresent: false },
    classification,
  ).reason, 'idempotency_required');
});

test('retry-after cannot exceed the remaining total request budget', () => {
  const classification = classifyTransportResponse(
    fixture.policy,
    response({ retryAfterMillis: 4900 }),
    'req-1',
  );
  const decision = decideTransportRetry(
    fixture.policy,
    { attempt: 1, elapsedMillis: 200, idempotencyKeyPresent: true },
    classification,
  );
  assert.equal(decision.action, 'fail');
  assert.equal(decision.reason, 'budget_exhausted');
});

test('attempt timeouts are retryable but max attempts remain bounded', () => {
  const policy = {
    ...fixture.policy,
    budget: {
      maxAttempts: 2,
      totalBudgetMillis: 3000,
      attemptTimeoutMillis: 1000,
      baseBackoffMillis: 50,
      maxBackoffMillis: 50,
    },
  };
  const timeoutExchange = {
    durationMillis: 1200,
    response: response({ statusCode: 200, payload: { ignored: true } }),
  };
  const adapter = new ScriptedConformanceAdapter([timeoutExchange, timeoutExchange]);
  const result = executeTransportConformance(fixture.request, policy, adapter);
  assert.equal(result.status, 'attempts_exhausted');
  assert.equal(result.error.code, 'transport_attempts_exhausted');
  assert.deepEqual(result.attempts.map((attempt) => attempt.durationMillis), [1000, 1000]);
  assert.deepEqual(result.attempts.map((attempt) => attempt.category), ['retryable', 'retryable']);
});

test('operation mismatch is rejected and transport policy exposes no trusted evidence fields', () => {
  assert.throws(
    () => executeTransportConformance(
      { ...fixture.request, operation: 'approval.task.complete' },
      fixture.policy,
      new ScriptedConformanceAdapter(fixture.script),
    ),
    /does not match transport policy/,
  );
  const policyKeys = Object.keys(fixture.policy).sort();
  for (const forbidden of ['tenantId', 'operator', 'permission', 'authority', 'auditEvidence', 'endpoint', 'token']) {
    assert.equal(policyKeys.includes(forbidden), false);
  }
});
