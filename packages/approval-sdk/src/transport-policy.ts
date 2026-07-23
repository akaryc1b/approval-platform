import type {
  ApprovalRequest,
  ErrorCategory,
  JsonValue,
  StructuredError,
} from './index.js';

export const TRANSPORT_POLICY_VERSION = '1' as const;
const MAX_ATTEMPTS_LIMIT = 10;
const MAX_TOTAL_BUDGET_MILLIS = 300_000;

export type RetryMode = 'never' | 'idempotent';
export type TransportResponseCategory =
  | 'success'
  | 'retryable'
  | 'permanent'
  | 'unauthorized'
  | 'conflict'
  | 'expired'
  | 'unsupported_version'
  | 'malformed_response';
export type RetryDecisionAction = 'complete' | 'retry' | 'fail';
export type RetryDecisionReason =
  | 'success'
  | 'retry_scheduled'
  | 'non_retryable'
  | 'retry_disabled'
  | 'idempotency_required'
  | 'max_attempts_exhausted'
  | 'budget_exhausted';
export type TransportExecutionStatus =
  | 'succeeded'
  | 'failed'
  | 'budget_exhausted'
  | 'attempts_exhausted';

export interface RequestBudget {
  readonly maxAttempts: number;
  readonly totalBudgetMillis: number;
  readonly attemptTimeoutMillis: number;
  readonly baseBackoffMillis: number;
  readonly maxBackoffMillis: number;
}

/** Trusted tenant, operator, permission, authority and audit evidence are deliberately absent. */
export interface OperationTransportPolicy {
  readonly policyVersion: '1';
  readonly operation: string;
  readonly retryMode: RetryMode;
  readonly budget: RequestBudget;
  readonly retryableStatusCodes: readonly number[];
}

/** Adapter response metadata is untrusted and is never promoted to server authority evidence. */
export interface AdapterResponse {
  readonly statusCode: number;
  readonly payload: JsonValue | null;
  readonly errorCode: string | null;
  readonly errorMessage: string | null;
  readonly retryAfterMillis: number | null;
}

export interface TransportResponseClassification {
  readonly category: TransportResponseCategory;
  readonly retryable: boolean;
  readonly value: JsonValue | null;
  readonly error: StructuredError | null;
  readonly retryAfterMillis: number | null;
}

export interface AttemptContext {
  readonly attempt: number;
  readonly elapsedMillis: number;
  readonly idempotencyKeyPresent: boolean;
}

export interface RetryDecision {
  readonly action: RetryDecisionAction;
  readonly reason: RetryDecisionReason;
  readonly nextAttempt: number;
  readonly delayMillis: number;
  readonly timeoutMillis: number;
  readonly terminalCategory: TransportResponseCategory | null;
}

export interface TransportAttempt {
  readonly request: ApprovalRequest;
  readonly attempt: number;
  readonly timeoutMillis: number;
  readonly elapsedMillis: number;
}

export interface AdapterExchange {
  readonly response: AdapterResponse;
  readonly durationMillis: number;
}

export interface ConformanceAdapter {
  exchange(attempt: TransportAttempt): AdapterExchange;
}

export interface AttemptTrace {
  readonly attempt: number;
  readonly timeoutMillis: number;
  readonly durationMillis: number;
  readonly elapsedAfterAttemptMillis: number;
  readonly category: TransportResponseCategory;
  readonly scheduledDelayMillis: number;
}

export interface TransportExecutionResult {
  readonly status: TransportExecutionStatus;
  readonly value: JsonValue | null;
  readonly error: StructuredError | null;
  readonly attempts: readonly AttemptTrace[];
  readonly totalElapsedMillis: number;
}

export class UnsupportedTransportPolicyVersionError extends Error {
  constructor(readonly policyVersion: unknown) {
    super(`Unsupported transport policy version: ${String(policyVersion)}`);
    this.name = 'UnsupportedTransportPolicyVersionError';
  }
}

/** Deterministic, in-memory conformance adapter; it never performs I/O. */
export class ScriptedConformanceAdapter implements ConformanceAdapter {
  readonly invocations: TransportAttempt[] = [];
  private readonly script: AdapterExchange[];

  constructor(script: readonly AdapterExchange[]) {
    if (script.length === 0) throw new TypeError('script must contain at least one exchange');
    this.script = script.map(validateExchange);
  }

  exchange(attempt: TransportAttempt): AdapterExchange {
    this.invocations.push(validateAttempt(attempt));
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
}

export function validateTransportPolicy(policy: OperationTransportPolicy): OperationTransportPolicy {
  if (policy.policyVersion !== TRANSPORT_POLICY_VERSION) {
    throw new UnsupportedTransportPolicyVersionError(policy.policyVersion);
  }
  requireString(policy.operation, 'policy.operation');
  if (policy.retryMode !== 'never' && policy.retryMode !== 'idempotent') {
    throw new TypeError('policy.retryMode is unsupported');
  }
  const budget = validateBudget(policy.budget);
  const statuses = uniqueStatusCodes(policy.retryableStatusCodes);
  return {
    policyVersion: TRANSPORT_POLICY_VERSION,
    operation: policy.operation,
    retryMode: policy.retryMode,
    budget,
    retryableStatusCodes: statuses,
  };
}

export function classifyTransportResponse(
  rawPolicy: OperationTransportPolicy,
  rawResponse: AdapterResponse,
  requestId: string,
): TransportResponseClassification {
  const policy = validateTransportPolicy(rawPolicy);
  const response = validateResponse(rawResponse);
  requireString(requestId, 'requestId');
  const status = response.statusCode;
  if (status < 100 || status > 599) {
    return failureClassification(
      'malformed_response',
      false,
      response,
      requestId,
      'permanent',
      'transport_malformed_response',
      'Adapter response status is outside the supported range',
    );
  }
  if (status >= 200 && status <= 299) {
    return {
      category: 'success',
      retryable: false,
      value: response.payload,
      error: null,
      retryAfterMillis: null,
    };
  }
  if (policy.retryableStatusCodes.includes(status)) {
    return failureClassification(
      'retryable',
      true,
      response,
      requestId,
      'retryable',
      defaultCode(response, status),
      defaultMessage(response, status),
    );
  }
  if (status === 401 || status === 403) {
    return failureClassification(
      'unauthorized',
      false,
      response,
      requestId,
      'unauthorized',
      defaultCode(response, status),
      defaultMessage(response, status),
    );
  }
  if (status === 409) {
    return failureClassification(
      'conflict',
      false,
      response,
      requestId,
      'conflict',
      defaultCode(response, status),
      defaultMessage(response, status),
    );
  }
  if (status === 410) {
    return failureClassification(
      'expired',
      false,
      response,
      requestId,
      'expired',
      defaultCode(response, status),
      defaultMessage(response, status),
    );
  }
  if (status === 426) {
    return failureClassification(
      'unsupported_version',
      false,
      response,
      requestId,
      'unsupported_version',
      defaultCode(response, status),
      defaultMessage(response, status),
    );
  }
  return failureClassification(
    'permanent',
    false,
    response,
    requestId,
    'permanent',
    defaultCode(response, status),
    defaultMessage(response, status),
  );
}

export function decideTransportRetry(
  rawPolicy: OperationTransportPolicy,
  context: AttemptContext,
  classification: TransportResponseClassification,
): RetryDecision {
  const policy = validateTransportPolicy(rawPolicy);
  validateAttemptContext(context);
  if (classification.category === 'success') {
    return decision('complete', 'success', 0, 0, 0, 'success');
  }
  if (!classification.retryable) {
    return failedDecision('non_retryable', classification.category);
  }
  if (policy.retryMode === 'never') {
    return failedDecision('retry_disabled', classification.category);
  }
  if (!context.idempotencyKeyPresent) {
    return failedDecision('idempotency_required', classification.category);
  }
  if (context.attempt >= policy.budget.maxAttempts) {
    return failedDecision('max_attempts_exhausted', classification.category);
  }

  let delay = exponentialBackoff(policy.budget, context.attempt);
  if (classification.retryAfterMillis !== null) {
    delay = Math.max(delay, classification.retryAfterMillis);
  }
  const remainingBeforeDelay = remaining(policy.budget, context.elapsedMillis);
  if (delay >= remainingBeforeDelay) {
    return failedDecision('budget_exhausted', classification.category);
  }
  const timeout = Math.min(
    policy.budget.attemptTimeoutMillis,
    remainingBeforeDelay - delay,
  );
  if (timeout < 1) return failedDecision('budget_exhausted', classification.category);
  return decision(
    'retry',
    'retry_scheduled',
    context.attempt + 1,
    delay,
    timeout,
    null,
  );
}

export function executeTransportConformance(
  request: ApprovalRequest,
  rawPolicy: OperationTransportPolicy,
  adapter: ConformanceAdapter,
): TransportExecutionResult {
  const policy = validateTransportPolicy(rawPolicy);
  validateApprovalRequest(request);
  if (policy.operation !== request.operation) {
    throw new TypeError('Request operation does not match transport policy');
  }

  let elapsed = 0;
  let attempt = 1;
  const traces: AttemptTrace[] = [];
  for (;;) {
    const timeout = Math.min(
      policy.budget.attemptTimeoutMillis,
      remaining(policy.budget, elapsed),
    );
    if (timeout < 1) {
      return terminalResult(
        'budget_exhausted',
        request,
        traces,
        elapsed,
        'transport_budget_exhausted',
        'Transport request budget is exhausted',
        'expired',
      );
    }

    const transportAttempt: TransportAttempt = {
      request,
      attempt,
      timeoutMillis: timeout,
      elapsedMillis: elapsed,
    };
    let exchange: AdapterExchange;
    try {
      exchange = validateExchange(adapter.exchange(transportAttempt));
    } catch {
      exchange = {
        durationMillis: 0,
        response: {
          statusCode: 0,
          payload: null,
          errorCode: 'adapter_exception',
          errorMessage: 'Adapter raised an exception',
          retryAfterMillis: null,
        },
      };
    }

    let duration: number;
    let classification: TransportResponseClassification;
    if (exchange.durationMillis > timeout) {
      duration = timeout;
      classification = timeoutClassification(request.correlation.requestId);
    } else {
      duration = exchange.durationMillis;
      classification = classifyTransportResponse(
        policy,
        exchange.response,
        request.correlation.requestId,
      );
    }
    elapsed += duration;
    const retryDecision = decideTransportRetry(
      policy,
      {
        attempt,
        elapsedMillis: elapsed,
        idempotencyKeyPresent: request.idempotencyKey.length > 0,
      },
      classification,
    );
    traces.push({
      attempt,
      timeoutMillis: timeout,
      durationMillis: duration,
      elapsedAfterAttemptMillis: elapsed,
      category: classification.category,
      scheduledDelayMillis: retryDecision.action === 'retry' ? retryDecision.delayMillis : 0,
    });

    if (retryDecision.action === 'complete') {
      return {
        status: 'succeeded',
        value: classification.value,
        error: null,
        attempts: traces,
        totalElapsedMillis: elapsed,
      };
    }
    if (retryDecision.action === 'retry') {
      elapsed += retryDecision.delayMillis;
      attempt = retryDecision.nextAttempt;
      continue;
    }
    if (retryDecision.reason === 'budget_exhausted') {
      return terminalResult(
        'budget_exhausted',
        request,
        traces,
        elapsed,
        'transport_budget_exhausted',
        'Transport request budget cannot accommodate another attempt',
        'expired',
      );
    }
    if (retryDecision.reason === 'max_attempts_exhausted') {
      return terminalResult(
        'attempts_exhausted',
        request,
        traces,
        elapsed,
        'transport_attempts_exhausted',
        'Transport retry attempts are exhausted',
        'retryable',
      );
    }
    return {
      status: 'failed',
      value: null,
      error: classification.error,
      attempts: traces,
      totalElapsedMillis: elapsed,
    };
  }
}

function validateBudget(budget: RequestBudget): RequestBudget {
  requireInteger(budget.maxAttempts, 'budget.maxAttempts');
  requireInteger(budget.totalBudgetMillis, 'budget.totalBudgetMillis');
  requireInteger(budget.attemptTimeoutMillis, 'budget.attemptTimeoutMillis');
  requireInteger(budget.baseBackoffMillis, 'budget.baseBackoffMillis');
  requireInteger(budget.maxBackoffMillis, 'budget.maxBackoffMillis');
  if (budget.maxAttempts < 1 || budget.maxAttempts > MAX_ATTEMPTS_LIMIT) {
    throw new TypeError(`maxAttempts must be between 1 and ${MAX_ATTEMPTS_LIMIT}`);
  }
  if (budget.totalBudgetMillis < 1 || budget.totalBudgetMillis > MAX_TOTAL_BUDGET_MILLIS) {
    throw new TypeError(`totalBudgetMillis must be between 1 and ${MAX_TOTAL_BUDGET_MILLIS}`);
  }
  if (budget.attemptTimeoutMillis < 1 || budget.attemptTimeoutMillis > budget.totalBudgetMillis) {
    throw new TypeError('attemptTimeoutMillis must fit within totalBudgetMillis');
  }
  if (budget.baseBackoffMillis < 0 || budget.maxBackoffMillis < budget.baseBackoffMillis) {
    throw new TypeError('backoff bounds are invalid');
  }
  return { ...budget };
}

function validateResponse(response: AdapterResponse): AdapterResponse {
  requireInteger(response.statusCode, 'response.statusCode');
  if (response.retryAfterMillis !== null) {
    requireInteger(response.retryAfterMillis, 'response.retryAfterMillis');
    if (response.retryAfterMillis < 0) throw new TypeError('retryAfterMillis cannot be negative');
  }
  if (response.errorCode !== null) requireString(response.errorCode, 'response.errorCode');
  if (response.errorMessage !== null) requireString(response.errorMessage, 'response.errorMessage');
  return response;
}

function validateExchange(exchange: AdapterExchange): AdapterExchange {
  requireInteger(exchange.durationMillis, 'exchange.durationMillis');
  if (exchange.durationMillis < 0) throw new TypeError('durationMillis cannot be negative');
  return { durationMillis: exchange.durationMillis, response: validateResponse(exchange.response) };
}

function validateAttempt(attempt: TransportAttempt): TransportAttempt {
  validateApprovalRequest(attempt.request);
  requireInteger(attempt.attempt, 'attempt.attempt');
  requireInteger(attempt.timeoutMillis, 'attempt.timeoutMillis');
  requireInteger(attempt.elapsedMillis, 'attempt.elapsedMillis');
  if (attempt.attempt < 1 || attempt.timeoutMillis < 1 || attempt.elapsedMillis < 0) {
    throw new TypeError('Transport attempt values are invalid');
  }
  return attempt;
}

function validateAttemptContext(context: AttemptContext): void {
  requireInteger(context.attempt, 'context.attempt');
  requireInteger(context.elapsedMillis, 'context.elapsedMillis');
  if (context.attempt < 1 || context.elapsedMillis < 0) {
    throw new TypeError('Attempt context values are invalid');
  }
}

function validateApprovalRequest(request: ApprovalRequest): void {
  requireString(request.operation, 'request.operation');
  requireString(request.correlation.requestId, 'request.correlation.requestId');
  requireString(request.correlation.traceId, 'request.correlation.traceId');
  requireString(request.idempotencyKey, 'request.idempotencyKey');
}

function timeoutClassification(requestId: string): TransportResponseClassification {
  return {
    category: 'retryable',
    retryable: true,
    value: null,
    error: structuredError(
      'transport_attempt_timeout',
      'Transport attempt exceeded its timeout budget',
      'retryable',
      requestId,
    ),
    retryAfterMillis: null,
  };
}

function terminalResult(
  status: Exclude<TransportExecutionStatus, 'succeeded'>,
  request: ApprovalRequest,
  attempts: readonly AttemptTrace[],
  elapsed: number,
  code: string,
  message: string,
  category: ErrorCategory,
): TransportExecutionResult {
  return {
    status,
    value: null,
    error: structuredError(code, message, category, request.correlation.requestId),
    attempts: [...attempts],
    totalElapsedMillis: elapsed,
  };
}

function failureClassification(
  category: Exclude<TransportResponseCategory, 'success'>,
  retryable: boolean,
  response: AdapterResponse,
  requestId: string,
  errorCategory: ErrorCategory,
  code: string,
  message: string,
): TransportResponseClassification {
  return {
    category,
    retryable,
    value: null,
    error: structuredError(code, message, errorCategory, requestId),
    retryAfterMillis: response.retryAfterMillis,
  };
}

function structuredError(
  code: string,
  message: string,
  category: ErrorCategory,
  requestId: string,
): StructuredError {
  return { code, message, category, requestId };
}

function decision(
  action: RetryDecisionAction,
  reason: RetryDecisionReason,
  nextAttempt: number,
  delayMillis: number,
  timeoutMillis: number,
  terminalCategory: TransportResponseCategory | null,
): RetryDecision {
  return { action, reason, nextAttempt, delayMillis, timeoutMillis, terminalCategory };
}

function failedDecision(
  reason: RetryDecisionReason,
  category: TransportResponseCategory,
): RetryDecision {
  return decision('fail', reason, 0, 0, 0, category);
}

function defaultCode(response: AdapterResponse, status: number): string {
  return response.errorCode ?? `transport_status_${status}`;
}

function defaultMessage(response: AdapterResponse, status: number): string {
  return response.errorMessage ?? `Transport response status ${status}`;
}

function exponentialBackoff(budget: RequestBudget, completedAttempt: number): number {
  let value = budget.baseBackoffMillis;
  for (let index = 1; index < completedAttempt && value < budget.maxBackoffMillis; index += 1) {
    value = Math.min(value * 2, budget.maxBackoffMillis);
  }
  return value;
}

function remaining(budget: RequestBudget, elapsedMillis: number): number {
  return elapsedMillis >= budget.totalBudgetMillis ? 0 : budget.totalBudgetMillis - elapsedMillis;
}

function uniqueStatusCodes(values: readonly number[]): readonly number[] {
  const unique = new Set<number>();
  const output: number[] = [];
  for (const value of values) {
    requireInteger(value, 'policy.retryableStatusCodes');
    if (value < 100 || value > 599) throw new TypeError(`Retryable status code is outside the supported range: ${value}`);
    if (unique.has(value)) throw new TypeError(`Duplicate retryable status code: ${value}`);
    unique.add(value);
    output.push(value);
  }
  return output;
}

function requireString(value: unknown, field: string): asserts value is string {
  if (typeof value !== 'string' || value.length === 0) throw new TypeError(`${field} must be a non-empty string`);
}

function requireInteger(value: unknown, field: string): asserts value is number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value)) throw new TypeError(`${field} must be a safe integer`);
}
