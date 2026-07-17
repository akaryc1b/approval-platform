import assert from 'node:assert/strict';
import test from 'node:test';
import {
  ConnectorClient,
  ConnectorHttpError,
  loadConfiguration,
} from '../src/client.mjs';
import { parseArguments } from '../src/commands.mjs';
import { decodeSecret, verifySignature } from '../src/signing.mjs';

const SECRET_TEXT = '0123456789abcdef0123456789abcdef';

function configuration(overrides = {}) {
  return {
    baseUrl: new URL('https://host.example'),
    tenantId: 'tenant-a',
    keyId: 'key-1',
    source: 'ruoyi6',
    timeoutMs: 5_000,
    secretText: SECRET_TEXT,
    ...overrides,
  };
}

test('client sends canonical body and signed protocol headers', async () => {
  let captured;
  const fetchImpl = async (url, options) => {
    captured = { url: url.toString(), options };
    return new Response(JSON.stringify({ data: { ok: true } }), {
      status: 200,
      headers: { 'X-Request-Id': 'provider-1' },
    });
  };
  const client = new ConnectorClient(configuration(), {
    fetchImpl,
    now: () => 1_760_000_000,
    nonce: () => 'nonce-1',
    requestId: () => 'request-1',
  });

  const result = await client.call({
    path: '/api/approval-connector/v1/organization/users/find',
    operation: 'organization.users.find.v1',
    body: {
      id: { value: '1', source: 'ruoyi6', objectType: 'user' },
    },
  });

  assert.deepEqual(result, { data: { ok: true } });
  assert.equal(
    captured.url,
    'https://host.example/api/approval-connector/v1/organization/users/find',
  );
  assert.equal(
    captured.options.body,
    '{"id":{"objectType":"user","source":"ruoyi6","value":"1"}}',
  );
  assert.equal(captured.options.headers['X-Tenant-Id'], 'tenant-a');
  assert.equal(captured.options.headers['X-Request-Id'], 'request-1');
  assert.equal(captured.options.headers['X-Approval-Operation'], 'organization.users.find.v1');

  const secret = decodeSecret(SECRET_TEXT);
  assert.equal(verifySignature({
    secret,
    timestamp: 1_760_000_000,
    nonce: 'nonce-1',
    body: captured.options.body,
    signature: captured.options.headers['X-Approval-Signature'],
  }), true);
  secret.fill(0);
  client.destroy();
});

test('HTTP failures expose redacted structured diagnostics', async () => {
  const client = new ConnectorClient(configuration(), {
    fetchImpl: async () => new Response(JSON.stringify({
      code: 'INVALID_CREDENTIAL',
      credential: 'must-not-leak',
      message: 'credential is invalid',
    }), {
      status: 401,
      headers: { 'X-Request-Id': 'provider-error' },
    }),
    now: () => 1_760_000_000,
    nonce: () => 'nonce-2',
    requestId: () => 'request-2',
  });

  await assert.rejects(
    client.call({ path: '/api/approval-connector/v1/authenticate', operation: 'authentication.authenticate.v1', body: {} }),
    (error) => {
      assert.ok(error instanceof ConnectorHttpError);
      assert.equal(error.status, 401);
      assert.equal(error.code, 'INVALID_CREDENTIAL');
      assert.equal(error.retryable, false);
      assert.equal(error.requestId, 'provider-error');
      assert.equal(error.details.credential, '[REDACTED]');
      return true;
    },
  );
  client.destroy();
});

test('configuration rejects unsafe host URLs', () => {
  const common = {
    APPROVAL_TENANT_ID: 'tenant-a',
    APPROVAL_KEY_ID: 'key-1',
    APPROVAL_HOST_SECRET: SECRET_TEXT,
    APPROVAL_SOURCE: 'ruoyi5',
  };
  assert.throws(() => loadConfiguration({
    ...common,
    APPROVAL_HOST_URL: 'http://example.com',
  }), /must use HTTPS/u);
  assert.throws(() => loadConfiguration({
    ...common,
    APPROVAL_HOST_URL: 'https://user:pass@example.com',
  }), /without credentials/u);
  assert.doesNotThrow(() => loadConfiguration({
    ...common,
    APPROVAL_HOST_URL: 'http://127.0.0.1:8080',
  }));
});

test('parser rejects sensitive command-line options', () => {
  assert.throws(
    () => parseArguments(['auth', '--credential', 'token-value']),
    /must be supplied through the environment/u,
  );
  assert.deepEqual(parseArguments(['user', '--user-id', '42']), {
    command: 'user',
    options: { 'user-id': '42' },
  });
});
