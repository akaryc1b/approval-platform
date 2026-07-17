import { createHmac, timingSafeEqual } from 'node:crypto';

export function decodeSecret(value) {
  if (typeof value !== 'string' || value.length === 0) {
    throw new TypeError('APPROVAL_HOST_SECRET must not be empty.');
  }
  const secret = value.startsWith('base64:')
    ? Buffer.from(value.slice('base64:'.length), 'base64')
    : Buffer.from(value, 'utf8');
  if (secret.length < 32) {
    secret.fill(0);
    throw new TypeError('APPROVAL_HOST_SECRET must contain at least 32 bytes.');
  }
  return secret;
}

export function signRequest({ secret, timestamp, nonce, body }) {
  if (!Buffer.isBuffer(secret)) {
    throw new TypeError('secret must be a Buffer.');
  }
  if (!Number.isSafeInteger(timestamp) || timestamp < 0) {
    throw new TypeError('timestamp must be a non-negative epoch second.');
  }
  if (typeof nonce !== 'string' || nonce.length === 0) {
    throw new TypeError('nonce must not be empty.');
  }
  if (typeof body !== 'string') {
    throw new TypeError('body must be a string.');
  }

  const digest = createHmac('sha256', secret)
    .update(`${timestamp}\n${nonce}\n${body}`, 'utf8')
    .digest('hex');
  return `v1=${digest}`;
}

export function verifySignature(arguments_) {
  const expected = signRequest(arguments_);
  const actual = arguments_.signature;
  if (typeof actual !== 'string') {
    return false;
  }
  const expectedBuffer = Buffer.from(expected, 'utf8');
  const actualBuffer = Buffer.from(actual, 'utf8');
  return expectedBuffer.length === actualBuffer.length
    && timingSafeEqual(expectedBuffer, actualBuffer);
}
