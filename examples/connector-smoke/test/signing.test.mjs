import assert from 'node:assert/strict';
import test from 'node:test';
import { canonicalJson } from '../src/canonical-json.mjs';
import { redact, safeErrorMessage } from '../src/redaction.mjs';
import { decodeSecret, signRequest, verifySignature } from '../src/signing.mjs';

test('canonical JSON sorts nested object keys and expands exponents', () => {
  assert.equal(
    canonicalJson({ z: 2, nested: { z: 'last', a: true }, a: 1 }),
    '{"a":1,"nested":{"a":true,"z":"last"},"z":2}',
  );
  assert.equal(canonicalJson({ small: 1e-7, large: 1e21 }),
    '{"large":1000000000000000000000,"small":0.0000001}');
});

test('HMAC signature matches the Java Host SDK vector', () => {
  const secret = decodeSecret('0123456789abcdef0123456789abcdef');
  const body = '{"a":1,"nested":{"a":true,"z":"last"},"z":2}';
  const signature = signRequest({
    secret,
    timestamp: 1_760_000_000,
    nonce: 'nonce-1',
    body,
  });

  assert.equal(
    signature,
    'v1=3fe89ea752bfced61c9e728f1eebc534b21e97c0a126e129972754ea2bace7fc',
  );
  assert.equal(verifySignature({
    secret,
    timestamp: 1_760_000_000,
    nonce: 'nonce-1',
    body,
    signature,
  }), true);
  assert.equal(verifySignature({
    secret,
    timestamp: 1_760_000_000,
    nonce: 'nonce-1',
    body: `${body} `,
    signature,
  }), false);
  secret.fill(0);
});

test('secrets must contain at least 32 bytes', () => {
  assert.throws(() => decodeSecret('short'), /at least 32 bytes/u);
  const encoded = Buffer.from('0123456789abcdef0123456789abcdef').toString('base64');
  const secret = decodeSecret(`base64:${encoded}`);
  assert.equal(secret.length, 32);
  secret.fill(0);
});

test('diagnostics redact sensitive keys and literal values', () => {
  assert.deepEqual(redact({
    credential: 'token-value',
    nested: { signature: 'v1=abc', username: 'alice' },
  }), {
    credential: '[REDACTED]',
    nested: { signature: '[REDACTED]', username: 'alice' },
  });
  assert.equal(
    safeErrorMessage(new Error('failed token-value secret-value'), ['token-value', 'secret-value']),
    'failed [REDACTED] [REDACTED]',
  );
});
