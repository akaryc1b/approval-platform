const SERVER_TRUSTED_EVENT: unique symbol = Symbol('server-trusted-event');
const MAX_SAFE_INTEGER = Number.MAX_SAFE_INTEGER;
const encoder = new TextEncoder();

export type JsonValue = null | boolean | number | string | JsonValue[] | { [key: string]: JsonValue };

export interface TenantContext {
  readonly tenantId: string;
}

export interface ResourceReference {
  readonly resourceType: string;
  readonly resourceId: string;
  readonly version?: number;
}

export interface ProducerIdentity {
  readonly service: string;
  readonly instance: string;
}

export interface EventEnvelopeV1 {
  readonly [SERVER_TRUSTED_EVENT]: true;
  readonly schemaVersion: '1.0';
  readonly eventId: string;
  readonly eventType: string;
  readonly occurredAt: string;
  readonly tenant: TenantContext;
  readonly resource: ResourceReference;
  readonly requestId: string;
  readonly traceId: string;
  readonly payload: JsonValue;
  readonly payloadHash: string;
  readonly producer: ProducerIdentity;
  readonly orderingKey?: string;
  readonly causationId?: string;
  readonly correlationId?: string;
}

export class UnsupportedSchemaVersionError extends Error {
  constructor(readonly schemaVersion: unknown) {
    super(`Unsupported event schema version: ${String(schemaVersion)}`);
    this.name = 'UnsupportedSchemaVersionError';
  }
}

export function canonicalJson(value: JsonValue): string {
  if (value === null) return 'null';
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  if (typeof value === 'number') {
    if (!Number.isSafeInteger(value) || Math.abs(value) > MAX_SAFE_INTEGER) {
      throw new TypeError('Contract JSON numbers must be safe integers; encode decimals as strings');
    }
    return String(value);
  }
  if (typeof value === 'string') return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  const keys = Object.keys(value).sort();
  return `{${keys.map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key]!)}`).join(',')}}`;
}

export function canonicalPayloadBytes(rawJson: string): Uint8Array {
  return encoder.encode(canonicalJson(JSON.parse(rawJson) as JsonValue));
}

export async function sha256Hex(bytes: Uint8Array): Promise<string> {
  return bytesToHex(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)));
}

export async function parseEventEnvelope(rawJson: string): Promise<EventEnvelopeV1> {
  const value = JSON.parse(rawJson) as Record<string, unknown>;
  if (value.schemaVersion !== '1.0') throw new UnsupportedSchemaVersionError(value.schemaVersion);
  requireString(value.eventId, 'eventId');
  requireString(value.eventType, 'eventType');
  requireInstant(value.occurredAt, 'occurredAt');
  const tenant = requireObject(value.tenant, 'tenant');
  requireString(tenant.tenantId, 'tenant.tenantId');
  const resource = requireObject(value.resource, 'resource');
  requireString(resource.resourceType, 'resource.resourceType');
  requireString(resource.resourceId, 'resource.resourceId');
  if (resource.version !== undefined) requireSafeInteger(resource.version, 'resource.version');
  requireString(value.requestId, 'requestId');
  requireString(value.traceId, 'traceId');
  requireJson(value.payload, 'payload');
  const payloadHash = requireString(value.payloadHash, 'payloadHash');
  const producer = requireObject(value.producer, 'producer');
  requireString(producer.service, 'producer.service');
  requireString(producer.instance, 'producer.instance');
  optionalString(value.orderingKey, 'orderingKey');
  optionalString(value.causationId, 'causationId');
  optionalString(value.correlationId, 'correlationId');
  const actualHash = await sha256Hex(encoder.encode(canonicalJson(value.payload as JsonValue)));
  if (!constantTimeTextEqual(payloadHash, actualHash)) throw new Error('Event payload hash mismatch');
  return value as unknown as EventEnvelopeV1;
}

export type ErrorCategory =
  | 'retryable'
  | 'permanent'
  | 'unauthorized'
  | 'conflict'
  | 'expired'
  | 'unsupported_version';

export interface StructuredError {
  readonly code: string;
  readonly message: string;
  readonly category: ErrorCategory;
  readonly requestId: string;
}

export type Result<T> = { readonly ok: true; readonly value: T } | { readonly ok: false; readonly error: StructuredError };

export interface RequestCorrelation {
  readonly requestId: string;
  readonly traceId: string;
}

/** Trusted tenant, operator, authority and audit evidence are deliberately absent. */
export interface ApprovalRequest<TPayload extends JsonValue = JsonValue> {
  readonly operation: string;
  readonly payload: TPayload;
  readonly correlation: RequestCorrelation;
  readonly idempotencyKey: string;
}

export interface ApprovalTransport {
  exchange<TResult extends JsonValue>(request: ApprovalRequest): Promise<Result<TResult>>;
}

export interface ApprovalClient {
  execute<TResult extends JsonValue>(request: ApprovalRequest): Promise<Result<TResult>>;
}

export class DefaultApprovalClient implements ApprovalClient {
  constructor(private readonly transport: ApprovalTransport) {}

  execute<TResult extends JsonValue>(request: ApprovalRequest): Promise<Result<TResult>> {
    return this.transport.exchange<TResult>(request);
  }
}

export class MockTransport implements ApprovalTransport {
  readonly invocations: ApprovalRequest[] = [];

  constructor(private readonly handler: (request: ApprovalRequest) => Promise<Result<JsonValue>> | Result<JsonValue>) {}

  async exchange<TResult extends JsonValue>(request: ApprovalRequest): Promise<Result<TResult>> {
    this.invocations.push(request);
    return (await this.handler(request)) as Result<TResult>;
  }
}

export async function idempotencyKey(operation: string, requestId: string, payload: JsonValue): Promise<string> {
  requireString(operation, 'operation');
  requireString(requestId, 'requestId');
  return sha256Hex(encoder.encode(`${operation}\n${requestId}\n${canonicalJson(payload)}`));
}

export interface SignatureHeaders {
  readonly timestampEpochSeconds: number;
  readonly nonce: string;
  readonly algorithm: string;
  readonly keyReference: string;
  readonly signature: string;
}

export type VerificationResult =
  | 'verified'
  | 'timestamp_out_of_range'
  | 'nonce_replay'
  | 'unsupported_algorithm'
  | 'key_not_found'
  | 'invalid_signature'
  | 'invalid_payload';

export interface NonceReplayGuard {
  reserve(keyReference: string, nonce: string, expiresAtEpochSeconds: number, nowEpochSeconds: number): boolean;
}

export class InMemoryNonceReplayGuard implements NonceReplayGuard {
  private readonly reservations = new Map<string, number>();

  reserve(keyReference: string, nonce: string, expiresAt: number, now: number): boolean {
    for (const [key, expiry] of this.reservations) if (expiry <= now) this.reservations.delete(key);
    const key = `${keyReference}:${nonce}`;
    if (this.reservations.has(key)) return false;
    this.reservations.set(key, expiresAt);
    return true;
  }
}

export async function signatureInputBytes(rawPayload: string, headers: SignatureHeaders): Promise<Uint8Array> {
  const payloadBytes = canonicalPayloadBytes(rawPayload);
  const payloadHash = await sha256Hex(payloadBytes);
  const prefix = `approval-webhook-v1\n${headers.timestampEpochSeconds}\n${headers.nonce}\n${headers.algorithm}\n${headers.keyReference}\n${payloadHash}\n`;
  const prefixBytes = encoder.encode(prefix);
  const combined = new Uint8Array(prefixBytes.length + payloadBytes.length);
  combined.set(prefixBytes);
  combined.set(payloadBytes, prefixBytes.length);
  return combined;
}

export async function signWebhookHex(secret: Uint8Array, rawPayload: string, headers: SignatureHeaders): Promise<string> {
  const key = await crypto.subtle.importKey('raw', secret, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const signature = await crypto.subtle.sign('HMAC', key, await signatureInputBytes(rawPayload, headers));
  return bytesToHex(new Uint8Array(signature));
}

export async function verifyWebhook(input: {
  rawPayload: string;
  headers: SignatureHeaders;
  nowEpochSeconds: number;
  allowedClockSkewSeconds: number;
  resolveKey: (keyReference: string) => Uint8Array | undefined;
  replayGuard: NonceReplayGuard;
}): Promise<VerificationResult> {
  const { rawPayload, headers, nowEpochSeconds, allowedClockSkewSeconds, resolveKey, replayGuard } = input;
  if (headers.algorithm !== 'hmac-sha256') return 'unsupported_algorithm';
  if (!Number.isSafeInteger(headers.timestampEpochSeconds) || Math.abs(nowEpochSeconds - headers.timestampEpochSeconds) > allowedClockSkewSeconds) {
    return 'timestamp_out_of_range';
  }
  const key = resolveKey(headers.keyReference);
  if (!key) return 'key_not_found';
  let expected: string;
  try {
    expected = await signWebhookHex(key, rawPayload, headers);
  } catch {
    return 'invalid_payload';
  }
  if (!constantTimeTextEqual(expected, headers.signature)) return 'invalid_signature';
  if (!replayGuard.reserve(headers.keyReference, headers.nonce, headers.timestampEpochSeconds + allowedClockSkewSeconds, nowEpochSeconds)) {
    return 'nonce_replay';
  }
  return 'verified';
}

export type HandlingOutcome = 'accepted' | 'retryable_rejection' | 'permanent_rejection' | 'expired_event' | 'unsupported_schema_version';
export type DeliveryResult = 'processed' | 'duplicate' | Exclude<HandlingOutcome, 'accepted'>;

export interface DeduplicationStore {
  contains(eventId: string): boolean;
  markTerminal(eventId: string): void;
}

export class InMemoryDeduplicationStore implements DeduplicationStore {
  private readonly terminal = new Set<string>();
  contains(eventId: string): boolean { return this.terminal.has(eventId); }
  markTerminal(eventId: string): void { this.terminal.add(eventId); }
}

export function consumeIdempotently(
  event: EventEnvelopeV1,
  store: DeduplicationStore,
  handler: (event: EventEnvelopeV1) => HandlingOutcome,
): DeliveryResult {
  if (store.contains(event.eventId)) return 'duplicate';
  const outcome = handler(event);
  if (outcome === 'retryable_rejection') return outcome;
  store.markTerminal(event.eventId);
  return outcome === 'accepted' ? 'processed' : outcome;
}

function requireObject(value: unknown, field: string): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new TypeError(`${field} must be an object`);
  return value as Record<string, unknown>;
}
function requireString(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.length === 0) throw new TypeError(`${field} must be a non-empty string`);
  return value;
}
function optionalString(value: unknown, field: string): void {
  if (value !== undefined && value !== null) requireString(value, field);
}
function requireInstant(value: unknown, field: string): string {
  const string = requireString(value, field);
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/.test(string) || Number.isNaN(Date.parse(string))) {
    throw new TypeError(`${field} must be an RFC 3339 UTC instant`);
  }
  return string;
}
function requireSafeInteger(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value)) throw new TypeError(`${field} must be a safe integer`);
  return value;
}
function requireJson(value: unknown, field: string): asserts value is JsonValue {
  try { canonicalJson(value as JsonValue); } catch (error) { throw new TypeError(`${field} is not contract JSON`, { cause: error }); }
}
function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('');
}
function constantTimeTextEqual(left: string, right: string): boolean {
  const leftBytes = encoder.encode(left.toLowerCase());
  const rightBytes = encoder.encode(right.toLowerCase());
  let difference = leftBytes.length ^ rightBytes.length;
  const length = Math.max(leftBytes.length, rightBytes.length);
  for (let index = 0; index < length; index += 1) difference |= (leftBytes[index] ?? 0) ^ (rightBytes[index] ?? 0);
  return difference === 0;
}
