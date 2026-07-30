import type {
  ApprovalRequest,
  JsonValue,
  StructuredError,
} from './index.js';
import {
  executeTransportConformance,
  validateTransportPolicy,
} from './transport-policy.js';
import type {
  AdapterExchange,
  ConformanceAdapter,
  OperationTransportPolicy,
  TransportAttempt,
  TransportExecutionResult,
} from './transport-policy.js';

export const ADAPTER_BINDING_VERSION = '1' as const;

const SERVER_AUTH_CONTEXT: unique symbol = Symbol('server-auth-context');
const CREDENTIAL_LEASE: unique symbol = Symbol('credential-lease');

export type AdapterKind = 'generic_rest' | 'ruoyi5' | 'ruoyi6';
export type CredentialKind =
  | 'none'
  | 'mtls_reference'
  | 'oauth_client_reference'
  | 'signed_request_reference';
export type CredentialReleaseReason = 'completed' | 'binding_failed' | 'adapter_failed';
export type AdapterLifecycleState = 'created' | 'open' | 'closed';
export type AdapterBindingExecutionStatus = 'executed' | 'binding_failed';

export interface CredentialReference {
  readonly providerId: string;
  readonly credentialId: string;
  readonly kind: CredentialKind;
}

/** Logical server-owned endpoint metadata. It deliberately contains no address or trusted identity evidence. */
export interface LogicalEndpointDescriptor {
  readonly bindingVersion: '1';
  readonly endpointId: string;
  readonly adapterKind: AdapterKind;
  readonly audience: string;
  readonly supportedOperations: readonly string[];
  readonly credentialReference: CredentialReference;
}

export interface AuthenticationContextRequest {
  readonly endpointId: string;
  readonly operation: string;
  readonly correlation: {
    readonly requestId: string;
    readonly traceId: string;
  };
}

export interface ServerAuthenticationContextFields {
  readonly contextId: string;
  readonly tenantId: string;
  readonly operatorId: string;
  readonly permissionSnapshotHash: string;
  readonly auditReference: string;
  readonly authenticatedAtEpochSeconds: number;
  readonly expiresAtEpochSeconds: number;
}

/** Nominal server-issued context. Public SDK requests cannot construct or carry this value. */
export interface ServerAuthenticationContext extends ServerAuthenticationContextFields {
  readonly [SERVER_AUTH_CONTEXT]: true;
}

export abstract class AuthenticationContextResolver {
  abstract resolve(request: AuthenticationContextRequest): ServerAuthenticationContext;

  protected issue(fields: ServerAuthenticationContextFields): ServerAuthenticationContext {
    const validated = validateAuthenticationContextFields(fields);
    return { ...validated, [SERVER_AUTH_CONTEXT]: true };
  }
}

/** Deterministic server-side resolver for conformance tests only. */
export class StaticAuthenticationContextResolver extends AuthenticationContextResolver {
  readonly invocations: AuthenticationContextRequest[] = [];
  private readonly fields: ServerAuthenticationContextFields;

  constructor(fields: ServerAuthenticationContextFields) {
    super();
    this.fields = validateAuthenticationContextFields(fields);
  }

  resolve(request: AuthenticationContextRequest): ServerAuthenticationContext {
    this.invocations.push(validateAuthenticationContextRequest(request));
    return this.issue(this.fields);
  }
}

export interface CredentialLeaseFields {
  readonly leaseId: string;
  readonly providerId: string;
  readonly credentialId: string;
  readonly kind: CredentialKind;
  readonly endpointId: string;
  readonly authenticationContextId: string;
  readonly operation: string;
  readonly bindingId: string;
  readonly issuedAtEpochSeconds: number;
  readonly expiresAtEpochSeconds: number;
}

/** Nominal lease contains only references and binding evidence, never usable credential material. */
export interface CredentialLease extends CredentialLeaseFields {
  readonly [CREDENTIAL_LEASE]: true;
}

export interface CredentialLeaseRequest {
  readonly endpoint: LogicalEndpointDescriptor;
  readonly authenticationContext: ServerAuthenticationContext;
  readonly operation: string;
  readonly nowEpochSeconds: number;
}

export abstract class CredentialLeaseProvider {
  abstract acquire(request: CredentialLeaseRequest): CredentialLease;
  abstract release(lease: CredentialLease, reason: CredentialReleaseReason): void;

  protected issue(fields: CredentialLeaseFields): CredentialLease {
    const validated = validateCredentialLeaseFields(fields);
    return { ...validated, [CREDENTIAL_LEASE]: true };
  }
}

/** Deterministic lease provider for conformance tests only. */
export class StaticCredentialLeaseProvider extends CredentialLeaseProvider {
  readonly acquisitions: CredentialLeaseRequest[] = [];
  readonly releases: Array<{ readonly leaseId: string; readonly reason: CredentialReleaseReason }> = [];
  private readonly fields: CredentialLeaseFields;

  constructor(fields: CredentialLeaseFields) {
    super();
    this.fields = validateCredentialLeaseFields(fields);
  }

  acquire(request: CredentialLeaseRequest): CredentialLease {
    const validated = validateCredentialLeaseRequest(request);
    this.acquisitions.push(validated);
    const reference = validated.endpoint.credentialReference;
    if (
      reference.providerId !== this.fields.providerId
      || reference.credentialId !== this.fields.credentialId
      || reference.kind !== this.fields.kind
      || validated.endpoint.endpointId !== this.fields.endpointId
      || validated.authenticationContext.contextId !== this.fields.authenticationContextId
      || validated.operation !== this.fields.operation
    ) {
      throw new TypeError('Credential lease fixture does not match the server binding request');
    }
    return this.issue(this.fields);
  }

  release(lease: CredentialLease, reason: CredentialReleaseReason): void {
    validateCredentialLease(lease);
    requireReleaseReason(reason);
    this.releases.push({ leaseId: lease.leaseId, reason });
  }
}

export interface AdapterOpenContext {
  readonly endpoint: LogicalEndpointDescriptor;
  readonly authenticationContext: ServerAuthenticationContext;
  readonly credentialLease: CredentialLease;
  readonly nowEpochSeconds: number;
}

export interface SecurityBoundAttempt extends TransportAttempt {
  readonly endpoint: LogicalEndpointDescriptor;
  readonly authenticationContext: ServerAuthenticationContext;
  readonly credentialLease: CredentialLease;
}

export interface SecurityBoundAdapter {
  lifecycleState(): AdapterLifecycleState;
  lifecycleEvents(): readonly AdapterLifecycleState[];
  open(context: AdapterOpenContext): void;
  exchange(attempt: SecurityBoundAttempt): AdapterExchange;
  close(context: AdapterOpenContext): void;
}

/** Deterministic, lifecycle-aware adapter with no endpoint resolution, credential material or I/O. */
export class ScriptedSecurityBoundAdapter implements SecurityBoundAdapter {
  readonly invocations: SecurityBoundAttempt[] = [];
  private readonly events: AdapterLifecycleState[] = ['created'];
  private readonly script: AdapterExchange[];
  private state: AdapterLifecycleState = 'created';
  private openContext: AdapterOpenContext | null = null;

  constructor(script: readonly AdapterExchange[]) {
    if (script.length === 0) throw new TypeError('script must contain at least one exchange');
    this.script = script.map(copyExchange);
  }

  lifecycleState(): AdapterLifecycleState {
    return this.state;
  }

  lifecycleEvents(): readonly AdapterLifecycleState[] {
    return [...this.events];
  }

  open(context: AdapterOpenContext): void {
    if (this.state !== 'created') throw new TypeError('Adapter can only open from created state');
    this.openContext = validateOpenContext(context);
    this.state = 'open';
    this.events.push('open');
  }

  exchange(attempt: SecurityBoundAttempt): AdapterExchange {
    if (this.state !== 'open' || this.openContext === null) {
      throw new TypeError('Adapter exchange requires open state');
    }
    const validated = validateSecurityBoundAttempt(attempt);
    assertSameBinding(this.openContext, validated);
    this.invocations.push(validated);
    return this.script.shift() ?? {
      durationMillis: 0,
      response: {
        statusCode: 0,
        payload: null,
        errorCode: 'adapter_script_exhausted',
        errorMessage: 'Adapter script has no response for this attempt',
        retryAfterMillis: null,
      },
    };
  }

  close(context: AdapterOpenContext): void {
    if (this.state !== 'open' || this.openContext === null) {
      throw new TypeError('Adapter can only close from open state');
    }
    const validated = validateOpenContext(context);
    assertSameOpenContext(this.openContext, validated);
    this.state = 'closed';
    this.events.push('closed');
  }
}

export interface AdapterBindingExecutionResult {
  readonly status: AdapterBindingExecutionStatus;
  readonly endpointId: string;
  readonly authenticationContextId: string | null;
  readonly credentialLeaseId: string | null;
  readonly transport: TransportExecutionResult | null;
  readonly error: StructuredError | null;
  readonly lifecycle: readonly AdapterLifecycleState[];
  readonly credentialReleased: boolean;
}

export class UnsupportedAdapterBindingVersionError extends Error {
  constructor(readonly bindingVersion: unknown) {
    super(`Unsupported adapter binding version: ${String(bindingVersion)}`);
    this.name = 'UnsupportedAdapterBindingVersionError';
  }
}

export function credentialLeaseBindingId(
  endpointId: string,
  authenticationContextId: string,
  credentialId: string,
  operation: string,
): string {
  requireString(endpointId, 'endpointId');
  requireString(authenticationContextId, 'authenticationContextId');
  requireString(credentialId, 'credentialId');
  requireString(operation, 'operation');
  return `${endpointId}\n${authenticationContextId}\n${credentialId}\n${operation}`;
}

export function validateLogicalEndpointDescriptor(
  descriptor: LogicalEndpointDescriptor,
): LogicalEndpointDescriptor {
  if (descriptor.bindingVersion !== ADAPTER_BINDING_VERSION) {
    throw new UnsupportedAdapterBindingVersionError(descriptor.bindingVersion);
  }
  requireString(descriptor.endpointId, 'endpoint.endpointId');
  if (!['generic_rest', 'ruoyi5', 'ruoyi6'].includes(descriptor.adapterKind)) {
    throw new TypeError('endpoint.adapterKind is unsupported');
  }
  requireString(descriptor.audience, 'endpoint.audience');
  const operations = uniqueStrings(descriptor.supportedOperations, 'endpoint.supportedOperations');
  const credentialReference = validateCredentialReference(descriptor.credentialReference);
  return { ...descriptor, supportedOperations: operations, credentialReference };
}

export function executeSecurityBoundTransportConformance(input: {
  readonly request: ApprovalRequest;
  readonly policy: OperationTransportPolicy;
  readonly endpoint: LogicalEndpointDescriptor;
  readonly authenticationContextResolver: AuthenticationContextResolver;
  readonly credentialLeaseProvider: CredentialLeaseProvider;
  readonly adapter: SecurityBoundAdapter;
  readonly nowEpochSeconds: number;
}): AdapterBindingExecutionResult {
  const {
    request,
    policy: rawPolicy,
    endpoint: rawEndpoint,
    authenticationContextResolver,
    credentialLeaseProvider,
    adapter,
    nowEpochSeconds,
  } = input;
  requireSafeInteger(nowEpochSeconds, 'nowEpochSeconds');
  const endpoint = validateLogicalEndpointDescriptor(rawEndpoint);
  const policy = validateTransportPolicy(rawPolicy);
  const requestId = requireRequestIdentity(request);

  if (policy.operation !== request.operation || !endpoint.supportedOperations.includes(request.operation)) {
    return bindingFailure(
      endpoint.endpointId,
      null,
      null,
      adapter.lifecycleEvents(),
      false,
      requestId,
      'adapter_operation_not_supported',
      'The logical endpoint does not support the requested operation',
      'permanent',
    );
  }

  let authenticationContext: ServerAuthenticationContext;
  try {
    authenticationContext = validateAuthenticationContext(authenticationContextResolver.resolve({
      endpointId: endpoint.endpointId,
      operation: request.operation,
      correlation: { ...request.correlation },
    }));
  } catch {
    return bindingFailure(
      endpoint.endpointId,
      null,
      null,
      adapter.lifecycleEvents(),
      false,
      requestId,
      'authentication_context_resolution_failed',
      'Server authentication context resolution failed',
      'unauthorized',
    );
  }
  if (!isActive(authenticationContext.authenticatedAtEpochSeconds, authenticationContext.expiresAtEpochSeconds, nowEpochSeconds)) {
    return bindingFailure(
      endpoint.endpointId,
      authenticationContext.contextId,
      null,
      adapter.lifecycleEvents(),
      false,
      requestId,
      'authentication_context_expired',
      'Server authentication context is outside its active interval',
      'expired',
    );
  }

  let credentialLease: CredentialLease;
  try {
    credentialLease = validateCredentialLease(credentialLeaseProvider.acquire({
      endpoint,
      authenticationContext,
      operation: request.operation,
      nowEpochSeconds,
    }));
  } catch {
    return bindingFailure(
      endpoint.endpointId,
      authenticationContext.contextId,
      null,
      adapter.lifecycleEvents(),
      false,
      requestId,
      'credential_lease_acquisition_failed',
      'Server credential lease acquisition failed',
      'unauthorized',
    );
  }

  const expectedBindingId = credentialLeaseBindingId(
    endpoint.endpointId,
    authenticationContext.contextId,
    endpoint.credentialReference.credentialId,
    request.operation,
  );
  if (
    credentialLease.endpointId !== endpoint.endpointId
    || credentialLease.authenticationContextId !== authenticationContext.contextId
    || credentialLease.operation !== request.operation
    || credentialLease.providerId !== endpoint.credentialReference.providerId
    || credentialLease.credentialId !== endpoint.credentialReference.credentialId
    || credentialLease.kind !== endpoint.credentialReference.kind
    || credentialLease.bindingId !== expectedBindingId
  ) {
    return releaseAndFail(
      endpoint,
      authenticationContext,
      credentialLease,
      credentialLeaseProvider,
      adapter,
      requestId,
      'credential_binding_mismatch',
      'Credential lease is not bound to the logical endpoint, context and operation',
      'permanent',
    );
  }
  if (!isActive(credentialLease.issuedAtEpochSeconds, credentialLease.expiresAtEpochSeconds, nowEpochSeconds)) {
    return releaseAndFail(
      endpoint,
      authenticationContext,
      credentialLease,
      credentialLeaseProvider,
      adapter,
      requestId,
      'credential_lease_expired',
      'Credential lease is outside its active interval',
      'expired',
    );
  }

  const openContext: AdapterOpenContext = {
    endpoint,
    authenticationContext,
    credentialLease,
    nowEpochSeconds,
  };
  try {
    adapter.open(openContext);
  } catch {
    return releaseAndFail(
      endpoint,
      authenticationContext,
      credentialLease,
      credentialLeaseProvider,
      adapter,
      requestId,
      'adapter_open_failed',
      'Security-bound adapter failed to open',
      'permanent',
      'adapter_failed',
    );
  }

  const conformanceAdapter: ConformanceAdapter = {
    exchange(attempt: TransportAttempt): AdapterExchange {
      const currentEpochSeconds = nowEpochSeconds + Math.floor(attempt.elapsedMillis / 1000);
      if (
        currentEpochSeconds >= authenticationContext.expiresAtEpochSeconds
        || currentEpochSeconds >= credentialLease.expiresAtEpochSeconds
      ) {
        return {
          durationMillis: 0,
          response: {
            statusCode: 401,
            payload: null,
            errorCode: 'security_binding_expired',
            errorMessage: 'Authentication context or credential lease expired before the attempt',
            retryAfterMillis: null,
          },
        };
      }
      return adapter.exchange({
        ...attempt,
        endpoint,
        authenticationContext,
        credentialLease,
      });
    },
  };

  let transport: TransportExecutionResult | null = null;
  let closeFailure = false;
  try {
    transport = executeTransportConformance(request, policy, conformanceAdapter);
  } catch {
    closeFailure = true;
  }
  try {
    adapter.close(openContext);
  } catch {
    closeFailure = true;
  }

  let released = false;
  try {
    credentialLeaseProvider.release(
      credentialLease,
      closeFailure ? 'adapter_failed' : 'completed',
    );
    released = true;
  } catch {
    closeFailure = true;
  }

  if (closeFailure || transport === null) {
    return bindingFailure(
      endpoint.endpointId,
      authenticationContext.contextId,
      credentialLease.leaseId,
      adapter.lifecycleEvents(),
      released,
      requestId,
      'adapter_lifecycle_failed',
      'Security-bound adapter lifecycle did not complete cleanly',
      'permanent',
      transport,
    );
  }
  return {
    status: 'executed',
    endpointId: endpoint.endpointId,
    authenticationContextId: authenticationContext.contextId,
    credentialLeaseId: credentialLease.leaseId,
    transport,
    error: null,
    lifecycle: adapter.lifecycleEvents(),
    credentialReleased: released,
  };
}

function releaseAndFail(
  endpoint: LogicalEndpointDescriptor,
  authenticationContext: ServerAuthenticationContext,
  credentialLease: CredentialLease,
  provider: CredentialLeaseProvider,
  adapter: SecurityBoundAdapter,
  requestId: string,
  code: string,
  message: string,
  category: StructuredError['category'],
  reason: CredentialReleaseReason = 'binding_failed',
): AdapterBindingExecutionResult {
  let released = false;
  try {
    provider.release(credentialLease, reason);
    released = true;
  } catch {
    // A release failure is reported by credentialReleased=false without exposing provider details.
  }
  return bindingFailure(
    endpoint.endpointId,
    authenticationContext.contextId,
    credentialLease.leaseId,
    adapter.lifecycleEvents(),
    released,
    requestId,
    code,
    message,
    category,
  );
}

function bindingFailure(
  endpointId: string,
  authenticationContextId: string | null,
  credentialLeaseId: string | null,
  lifecycle: readonly AdapterLifecycleState[],
  credentialReleased: boolean,
  requestId: string,
  code: string,
  message: string,
  category: StructuredError['category'],
  transport: TransportExecutionResult | null = null,
): AdapterBindingExecutionResult {
  return {
    status: 'binding_failed',
    endpointId,
    authenticationContextId,
    credentialLeaseId,
    transport,
    error: { code, message, category, requestId },
    lifecycle: [...lifecycle],
    credentialReleased,
  };
}

function validateCredentialReference(reference: CredentialReference): CredentialReference {
  requireString(reference.providerId, 'credentialReference.providerId');
  requireString(reference.credentialId, 'credentialReference.credentialId');
  if (!['none', 'mtls_reference', 'oauth_client_reference', 'signed_request_reference'].includes(reference.kind)) {
    throw new TypeError('credentialReference.kind is unsupported');
  }
  return { ...reference };
}

function validateAuthenticationContextRequest(
  request: AuthenticationContextRequest,
): AuthenticationContextRequest {
  requireString(request.endpointId, 'authenticationContextRequest.endpointId');
  requireString(request.operation, 'authenticationContextRequest.operation');
  requireString(request.correlation.requestId, 'authenticationContextRequest.correlation.requestId');
  requireString(request.correlation.traceId, 'authenticationContextRequest.correlation.traceId');
  return {
    endpointId: request.endpointId,
    operation: request.operation,
    correlation: { ...request.correlation },
  };
}

function validateAuthenticationContextFields(
  fields: ServerAuthenticationContextFields,
): ServerAuthenticationContextFields {
  requireString(fields.contextId, 'authenticationContext.contextId');
  requireString(fields.tenantId, 'authenticationContext.tenantId');
  requireString(fields.operatorId, 'authenticationContext.operatorId');
  requireString(fields.permissionSnapshotHash, 'authenticationContext.permissionSnapshotHash');
  requireString(fields.auditReference, 'authenticationContext.auditReference');
  requireSafeInteger(fields.authenticatedAtEpochSeconds, 'authenticationContext.authenticatedAtEpochSeconds');
  requireSafeInteger(fields.expiresAtEpochSeconds, 'authenticationContext.expiresAtEpochSeconds');
  if (fields.expiresAtEpochSeconds <= fields.authenticatedAtEpochSeconds) {
    throw new TypeError('Authentication context expiry must follow authentication time');
  }
  return { ...fields };
}

function validateAuthenticationContext(
  context: ServerAuthenticationContext,
): ServerAuthenticationContext {
  if (context[SERVER_AUTH_CONTEXT] !== true) throw new TypeError('Authentication context is not server-issued');
  validateAuthenticationContextFields(context);
  return context;
}

function validateCredentialLeaseFields(fields: CredentialLeaseFields): CredentialLeaseFields {
  requireString(fields.leaseId, 'credentialLease.leaseId');
  requireString(fields.providerId, 'credentialLease.providerId');
  requireString(fields.credentialId, 'credentialLease.credentialId');
  if (!['none', 'mtls_reference', 'oauth_client_reference', 'signed_request_reference'].includes(fields.kind)) {
    throw new TypeError('credentialLease.kind is unsupported');
  }
  requireString(fields.endpointId, 'credentialLease.endpointId');
  requireString(fields.authenticationContextId, 'credentialLease.authenticationContextId');
  requireString(fields.operation, 'credentialLease.operation');
  requireString(fields.bindingId, 'credentialLease.bindingId');
  requireSafeInteger(fields.issuedAtEpochSeconds, 'credentialLease.issuedAtEpochSeconds');
  requireSafeInteger(fields.expiresAtEpochSeconds, 'credentialLease.expiresAtEpochSeconds');
  if (fields.expiresAtEpochSeconds <= fields.issuedAtEpochSeconds) {
    throw new TypeError('Credential lease expiry must follow issue time');
  }
  return { ...fields };
}

function validateCredentialLease(lease: CredentialLease): CredentialLease {
  if (lease[CREDENTIAL_LEASE] !== true) throw new TypeError('Credential lease is not provider-issued');
  validateCredentialLeaseFields(lease);
  return lease;
}

function validateCredentialLeaseRequest(request: CredentialLeaseRequest): CredentialLeaseRequest {
  const endpoint = validateLogicalEndpointDescriptor(request.endpoint);
  const authenticationContext = validateAuthenticationContext(request.authenticationContext);
  requireString(request.operation, 'credentialLeaseRequest.operation');
  requireSafeInteger(request.nowEpochSeconds, 'credentialLeaseRequest.nowEpochSeconds');
  return { endpoint, authenticationContext, operation: request.operation, nowEpochSeconds: request.nowEpochSeconds };
}

function validateOpenContext(context: AdapterOpenContext): AdapterOpenContext {
  return {
    endpoint: validateLogicalEndpointDescriptor(context.endpoint),
    authenticationContext: validateAuthenticationContext(context.authenticationContext),
    credentialLease: validateCredentialLease(context.credentialLease),
    nowEpochSeconds: requireSafeInteger(context.nowEpochSeconds, 'adapterOpenContext.nowEpochSeconds'),
  };
}

function validateSecurityBoundAttempt(attempt: SecurityBoundAttempt): SecurityBoundAttempt {
  requireSafeInteger(attempt.attempt, 'attempt.attempt');
  requireSafeInteger(attempt.timeoutMillis, 'attempt.timeoutMillis');
  requireSafeInteger(attempt.elapsedMillis, 'attempt.elapsedMillis');
  if (attempt.attempt < 1 || attempt.timeoutMillis < 1 || attempt.elapsedMillis < 0) {
    throw new TypeError('Security-bound attempt values are invalid');
  }
  requireRequestIdentity(attempt.request);
  return {
    ...attempt,
    endpoint: validateLogicalEndpointDescriptor(attempt.endpoint),
    authenticationContext: validateAuthenticationContext(attempt.authenticationContext),
    credentialLease: validateCredentialLease(attempt.credentialLease),
  };
}

function assertSameBinding(context: AdapterOpenContext, attempt: SecurityBoundAttempt): void {
  if (
    context.endpoint.endpointId !== attempt.endpoint.endpointId
    || context.authenticationContext.contextId !== attempt.authenticationContext.contextId
    || context.credentialLease.leaseId !== attempt.credentialLease.leaseId
  ) {
    throw new TypeError('Attempt does not match the open security binding');
  }
}

function assertSameOpenContext(left: AdapterOpenContext, right: AdapterOpenContext): void {
  if (
    left.endpoint.endpointId !== right.endpoint.endpointId
    || left.authenticationContext.contextId !== right.authenticationContext.contextId
    || left.credentialLease.leaseId !== right.credentialLease.leaseId
  ) {
    throw new TypeError('Close context does not match the open security binding');
  }
}

function isActive(issuedAt: number, expiresAt: number, now: number): boolean {
  return now >= issuedAt && now < expiresAt;
}

function requireRequestIdentity(request: ApprovalRequest): string {
  requireString(request.operation, 'request.operation');
  requireString(request.correlation.requestId, 'request.correlation.requestId');
  requireString(request.correlation.traceId, 'request.correlation.traceId');
  requireString(request.idempotencyKey, 'request.idempotencyKey');
  return request.correlation.requestId;
}

function uniqueStrings(values: readonly string[], field: string): readonly string[] {
  if (!Array.isArray(values) || values.length === 0) throw new TypeError(`${field} must be non-empty`);
  const seen = new Set<string>();
  const output: string[] = [];
  for (const value of values) {
    requireString(value, field);
    if (seen.has(value)) throw new TypeError(`${field} contains duplicate value: ${value}`);
    seen.add(value);
    output.push(value);
  }
  return output;
}

function copyExchange(exchange: AdapterExchange): AdapterExchange {
  requireSafeInteger(exchange.durationMillis, 'exchange.durationMillis');
  if (exchange.durationMillis < 0) throw new TypeError('exchange.durationMillis cannot be negative');
  return {
    durationMillis: exchange.durationMillis,
    response: {
      statusCode: exchange.response.statusCode,
      payload: exchange.response.payload as JsonValue | null,
      errorCode: exchange.response.errorCode,
      errorMessage: exchange.response.errorMessage,
      retryAfterMillis: exchange.response.retryAfterMillis,
    },
  };
}

function requireReleaseReason(reason: CredentialReleaseReason): void {
  if (!['completed', 'binding_failed', 'adapter_failed'].includes(reason)) {
    throw new TypeError('Credential release reason is unsupported');
  }
}

function requireString(value: unknown, field: string): asserts value is string {
  if (typeof value !== 'string' || value.length === 0) throw new TypeError(`${field} must be a non-empty string`);
}

function requireSafeInteger(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value)) throw new TypeError(`${field} must be a safe integer`);
  return value;
}
