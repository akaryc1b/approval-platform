import { randomUUID } from 'node:crypto';
import { canonicalJson } from './canonical-json.mjs';
import { redact } from './redaction.mjs';
import { decodeSecret, signRequest } from './signing.mjs';

const DEFAULT_TIMEOUT_MS = 10_000;

export class ConnectorHttpError extends Error {
  constructor(message, options = {}) {
    super(message, options.cause ? { cause: options.cause } : undefined);
    this.name = 'ConnectorHttpError';
    this.status = options.status ?? 0;
    this.code = options.code ?? 'CONNECTOR_REQUEST_FAILED';
    this.retryable = options.retryable ?? (
      this.status === 0 || this.status === 408 || this.status === 425
      || this.status === 429 || this.status >= 500
    );
    this.requestId = options.requestId ?? null;
    this.details = options.details == null ? null : redact(options.details);
  }
}

export function loadConfiguration(environment = process.env) {
  const baseUrl = required(environment.APPROVAL_HOST_URL, 'APPROVAL_HOST_URL');
  const tenantId = required(environment.APPROVAL_TENANT_ID, 'APPROVAL_TENANT_ID');
  const keyId = required(environment.APPROVAL_KEY_ID, 'APPROVAL_KEY_ID');
  const secretText = required(environment.APPROVAL_HOST_SECRET, 'APPROVAL_HOST_SECRET');
  const source = required(environment.APPROVAL_SOURCE, 'APPROVAL_SOURCE');
  const timeoutMs = optionalPositiveInteger(
    environment.APPROVAL_TIMEOUT_MS,
    DEFAULT_TIMEOUT_MS,
    'APPROVAL_TIMEOUT_MS',
  );

  const url = new URL(baseUrl);
  if (url.username || url.password || url.search || url.hash
    || (url.protocol !== 'https:' && !isLoopback(url.hostname))) {
    throw new TypeError(
      'APPROVAL_HOST_URL must use HTTPS without credentials/query/fragment, except loopback HTTP for local development.',
    );
  }
  url.pathname = url.pathname.replace(/\/+$/u, '');

  return {
    baseUrl: url,
    tenantId,
    keyId,
    source,
    timeoutMs,
    secretText,
  };
}

export class ConnectorClient {
  #baseUrl;
  #tenantId;
  #keyId;
  #source;
  #timeoutMs;
  #secret;
  #fetch;
  #now;
  #nonce;
  #requestId;

  constructor(config, dependencies = {}) {
    this.#baseUrl = new URL(config.baseUrl);
    this.#tenantId = required(config.tenantId, 'tenantId');
    this.#keyId = required(config.keyId, 'keyId');
    this.#source = required(config.source, 'source');
    this.#timeoutMs = optionalPositiveInteger(config.timeoutMs, DEFAULT_TIMEOUT_MS, 'timeoutMs');
    this.#secret = decodeSecret(config.secretText);
    this.#fetch = dependencies.fetchImpl ?? globalThis.fetch;
    this.#now = dependencies.now ?? (() => Math.floor(Date.now() / 1_000));
    this.#nonce = dependencies.nonce ?? randomUUID;
    this.#requestId = dependencies.requestId ?? randomUUID;
    if (typeof this.#fetch !== 'function') {
      throw new TypeError('A Fetch API implementation is required.');
    }
  }

  get source() {
    return this.#source;
  }

  async call({ path, operation, body }) {
    const timestamp = this.#now();
    const nonce = required(this.#nonce(), 'nonce');
    const requestId = required(this.#requestId(), 'requestId');
    const jsonBody = canonicalJson(body ?? {});
    const signature = signRequest({
      secret: this.#secret,
      timestamp,
      nonce,
      body: jsonBody,
    });
    const normalizedPath = required(path, 'path').replace(/^\/+/u, '');
    const base = `${this.#baseUrl.toString().replace(/\/+$/u, '')}/`;
    const url = new URL(normalizedPath, base);

    let response;
    try {
      response = await this.#fetch(url, {
        method: 'POST',
        redirect: 'manual',
        signal: AbortSignal.timeout(this.#timeoutMs),
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          'X-Approval-Key-Id': this.#keyId,
          'X-Approval-Timestamp': String(timestamp),
          'X-Approval-Nonce': nonce,
          'X-Approval-Signature': signature,
          'X-Approval-Operation': operation,
          'X-Tenant-Id': this.#tenantId,
          'X-Request-Id': requestId,
        },
        body: jsonBody,
      });
    } catch (error) {
      throw new ConnectorHttpError('Connector host could not be reached.', {
        cause: error,
        requestId,
        retryable: true,
      });
    }

    const responseText = await response.text();
    const responseBody = parseResponse(responseText);
    const providerRequestId = response.headers.get('x-request-id') ?? requestId;
    if (!response.ok) {
      throw new ConnectorHttpError(
        `Connector host returned HTTP ${response.status}.`,
        {
          status: response.status,
          code: responseBody?.code ?? 'CONNECTOR_HTTP_ERROR',
          retryable: response.status === 408 || response.status === 425
            || response.status === 429 || response.status >= 500,
          requestId: providerRequestId,
          details: responseBody,
        },
      );
    }
    if (responseBody === null || typeof responseBody !== 'object') {
      throw new ConnectorHttpError('Connector host returned invalid JSON.', {
        status: response.status,
        requestId: providerRequestId,
        details: responseText.slice(0, 2_000),
      });
    }
    return responseBody;
  }

  destroy() {
    this.#secret.fill(0);
  }
}

function parseResponse(text) {
  if (text.length === 0) {
    return {};
  }
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function required(value, name) {
  if (typeof value !== 'string' || value.trim().length === 0) {
    throw new TypeError(`${name} must not be empty.`);
  }
  return value.trim();
}

function optionalPositiveInteger(value, fallback, name) {
  if (value === undefined || value === null || value === '') {
    return fallback;
  }
  const parsed = typeof value === 'number' ? value : Number.parseInt(value, 10);
  if (!Number.isSafeInteger(parsed) || parsed < 1) {
    throw new TypeError(`${name} must be a positive integer.`);
  }
  return parsed;
}

function isLoopback(hostname) {
  return hostname === 'localhost' || hostname === '127.0.0.1' || hostname === '::1';
}
