import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import {
  InMemoryNonceReplayGuard,
  parseEventEnvelope,
  verifyWebhook,
} from '../dist/index.js';
import { negotiateCompatibility } from '../dist/public.js';

const eventFixture = JSON.parse(await readFile(
  new URL('../../../contracts/sdk/v1/fixtures/event-envelope-v1.json', import.meta.url),
  'utf8',
));
const compatibilityFixture = JSON.parse(await readFile(
  new URL('../../../contracts/sdk/v1/fixtures/sdk-compatibility-v1.json', import.meta.url),
  'utf8',
));
const rawEvent = JSON.stringify(eventFixture.event);
const secret = new TextEncoder().encode(eventFixture.webhook.secretUtf8);

function webhookInput(guard, nowEpochSeconds) {
  return {
    rawPayload: rawEvent,
    headers: eventFixture.webhook,
    nowEpochSeconds,
    allowedClockSkewSeconds: 300,
    resolveKey: (reference) => reference === eventFixture.webhook.keyReference ? secret : undefined,
    replayGuard: guard,
  };
}

test('nonce replay stays reserved through the exact accepted clock-skew boundary', async () => {
  const guard = new InMemoryNonceReplayGuard();
  const timestamp = eventFixture.webhook.timestampEpochSeconds;
  assert.equal(await verifyWebhook(webhookInput(guard, timestamp)), 'verified');
  assert.equal(await verifyWebhook(webhookInput(guard, timestamp + 300)), 'nonce_replay');
});

test('nonce replay composite identity cannot collide through delimiters', () => {
  const guard = new InMemoryNonceReplayGuard();
  assert.equal(guard.reserve('tenant:key', 'nonce', 100, 0), true);
  assert.equal(guard.reserve('tenant', 'key:nonce', 100, 0), true);
  assert.equal(guard.reserve('tenant:key', 'nonce', 100, 0), false);
});

test('calendar-invalid UTC event timestamps fail closed instead of normalizing', async () => {
  const invalid = structuredClone(eventFixture.event);
  invalid.occurredAt = '2025-02-30T00:00:00Z';
  await assert.rejects(() => parseEventEnvelope(JSON.stringify(invalid)), TypeError);
});

test('calendar-invalid compatibility timestamps fail closed instead of normalizing', () => {
  const invalidSupport = {
    ...compatibilityFixture.server,
    supportedUntil: '2025-02-30T00:00:00Z',
  };
  assert.throws(
    () => negotiateCompatibility(
      compatibilityFixture.client,
      invalidSupport,
      compatibilityFixture.evaluatedAt,
    ),
    TypeError,
  );

  const invalidDeprecation = {
    ...compatibilityFixture.server,
    deprecations: compatibilityFixture.server.deprecations.map((notice, index) => index === 0
      ? { ...notice, sunsetAt: '2027-02-30T00:00:00Z' }
      : notice),
  };
  assert.throws(
    () => negotiateCompatibility(
      compatibilityFixture.client,
      invalidDeprecation,
      compatibilityFixture.evaluatedAt,
    ),
    TypeError,
  );
});
