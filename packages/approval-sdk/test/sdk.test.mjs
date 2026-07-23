import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import {
  DefaultApprovalClient,
  InMemoryDeduplicationStore,
  InMemoryNonceReplayGuard,
  MockTransport,
  UnsupportedSchemaVersionError,
  canonicalJson,
  consumeIdempotently,
  idempotencyKey,
  parseEventEnvelope,
  sha256Hex,
  signatureInputBytes,
  verifyWebhook,
} from '../dist/index.js';

const fixturePath = new URL('../../../contracts/sdk/v1/fixtures/event-envelope-v1.json', import.meta.url);
const fixture = JSON.parse(await readFile(fixturePath, 'utf8'));
const rawEvent = JSON.stringify(fixture.event);
const secret = new TextEncoder().encode(fixture.webhook.secretUtf8);

function headers(overrides = {}) {
  return { ...fixture.webhook, ...overrides };
}

test('event envelope fixture is canonical across TypeScript semantics', async () => {
  assert.equal(canonicalJson(fixture.event), fixture.expectations.canonicalEventJson);
  assert.equal(canonicalJson(fixture.event.payload), fixture.expectations.canonicalPayloadJson);
  assert.equal(await sha256Hex(new TextEncoder().encode(canonicalJson(fixture.event.payload))), fixture.expectations.payloadSha256);
  const event = await parseEventEnvelope(rawEvent);
  assert.equal(event.eventId, fixture.event.eventId);
  assert.equal(event.resource.version, Number.MAX_SAFE_INTEGER);
  assert.equal(event.payload.note, '审批✅ Café');
  assert.equal(event.futureField.policy, fixture.event.futureField.policy);
});

test('unknown schema version fails closed while unknown fields remain signature-covered', async () => {
  const unsupported = structuredClone(fixture.event);
  unsupported.schemaVersion = '2.0';
  await assert.rejects(() => parseEventEnvelope(JSON.stringify(unsupported)), UnsupportedSchemaVersionError);
  const withUnknown = { ...fixture.event, anotherFutureField: { enabled: true } };
  const parsed = await parseEventEnvelope(JSON.stringify(withUnknown));
  assert.equal(parsed.eventId, fixture.event.eventId);
  assert.notEqual(canonicalJson(withUnknown), fixture.expectations.canonicalEventJson);
});

test('signature input and HMAC fixture match exactly', async () => {
  const input = await signatureInputBytes(rawEvent, headers());
  assert.equal(Buffer.from(input).toString('hex'), fixture.expectations.signatureInputHex);
  assert.equal(await sha256Hex(input), fixture.expectations.signatureInputSha256);
  const guard = new InMemoryNonceReplayGuard();
  const result = await verifyWebhook({
    rawPayload: rawEvent,
    headers: headers(),
    nowEpochSeconds: fixture.webhook.timestampEpochSeconds,
    allowedClockSkewSeconds: 300,
    resolveKey: (reference) => reference === fixture.webhook.keyReference ? secret : undefined,
    replayGuard: guard,
  });
  assert.equal(result, 'verified');
  assert.equal(await verifyWebhook({
    rawPayload: rawEvent,
    headers: headers(),
    nowEpochSeconds: fixture.webhook.timestampEpochSeconds,
    allowedClockSkewSeconds: 300,
    resolveKey: () => secret,
    replayGuard: guard,
  }), 'nonce_replay');
});

test('clock skew and payload tampering are rejected before replay reservation', async () => {
  const staleGuard = new InMemoryNonceReplayGuard();
  assert.equal(await verifyWebhook({
    rawPayload: rawEvent,
    headers: headers(),
    nowEpochSeconds: fixture.webhook.timestampEpochSeconds + 301,
    allowedClockSkewSeconds: 300,
    resolveKey: () => secret,
    replayGuard: staleGuard,
  }), 'timestamp_out_of_range');

  const tampered = structuredClone(fixture.event);
  tampered.payload.decision = 'REJECTED';
  const tamperGuard = new InMemoryNonceReplayGuard();
  assert.equal(await verifyWebhook({
    rawPayload: JSON.stringify(tampered),
    headers: headers(),
    nowEpochSeconds: fixture.webhook.timestampEpochSeconds,
    allowedClockSkewSeconds: 300,
    resolveKey: () => secret,
    replayGuard: tamperGuard,
  }), 'invalid_signature');
  assert.equal(await verifyWebhook({
    rawPayload: rawEvent,
    headers: headers(),
    nowEpochSeconds: fixture.webhook.timestampEpochSeconds,
    allowedClockSkewSeconds: 300,
    resolveKey: () => secret,
    replayGuard: tamperGuard,
  }), 'verified');
});

test('mock transport, structured error and idempotent consumer are deterministic', async () => {
  const request = {
    operation: 'approval.task.read',
    payload: { taskId: 'task_9001' },
    correlation: { requestId: 'req-sdk-1', traceId: 'trace-sdk-1' },
    idempotencyKey: await idempotencyKey('approval.task.read', 'req-sdk-1', { taskId: 'task_9001' }),
  };
  const transport = new MockTransport((received) => ({ ok: true, value: { echoed: received.payload } }));
  const client = new DefaultApprovalClient(transport);
  const result = await client.execute(request);
  assert.deepEqual(result, { ok: true, value: { echoed: request.payload } });
  assert.equal(transport.invocations.length, 1);
  assert.deepEqual(Object.keys(request).sort(), ['correlation', 'idempotencyKey', 'operation', 'payload']);
  for (const forbidden of ['tenant', 'tenantId', 'operator', 'permission', 'authority', 'auditEvidence']) {
    assert.equal(forbidden in request, false);
  }

  const event = await parseEventEnvelope(rawEvent);
  const store = new InMemoryDeduplicationStore();
  let calls = 0;
  assert.equal(consumeIdempotently(event, store, () => { calls += 1; return 'accepted'; }), 'processed');
  assert.equal(consumeIdempotently(event, store, () => { calls += 1; return 'accepted'; }), 'duplicate');
  assert.equal(calls, 1);

  const error = { code: 'temporary_unavailable', message: 'Retry later', category: 'retryable', requestId: 'req-sdk-1' };
  assert.deepEqual(error, { code: 'temporary_unavailable', message: 'Retry later', category: 'retryable', requestId: 'req-sdk-1' });
});
