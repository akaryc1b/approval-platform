import { canonicalJson, sha256Hex, type JsonValue } from './index.js';

export const DIAGNOSTICS_AUDIT_VERSION = '1' as const;
const encoder = new TextEncoder();
const REDACTED = '[REDACTED]';
const SENSITIVE_KEY = /(?:authorization|token|password|secret|private[._-]?key|certificate|credential[._-]?(?:value|material))/i;

export type ConfigurationClassification = 'public' | 'sensitive';
export type DiagnosticSeverity = 'info' | 'warning' | 'error';
export type AuditOutcome = 'succeeded' | 'failed' | 'rejected';

export interface ConfigurationEntry {
  readonly key: string;
  readonly value: string;
  readonly classification: ConfigurationClassification;
}

export interface FakeConfigurationSnapshot {
  readonly contractVersion: '1';
  readonly sourceKind: 'fixture';
  readonly sourceId: string;
  readonly revision: string;
  readonly loadedAtEpochSeconds: number;
  readonly entries: readonly ConfigurationEntry[];
}

export interface ConfigurationProvenance {
  readonly contractVersion: '1';
  readonly sourceKind: 'fixture';
  readonly sourceId: string;
  readonly revision: string;
  readonly loadedAtEpochSeconds: number;
  readonly contentDigest: string;
  readonly publicKeys: readonly string[];
  readonly sensitiveKeys: readonly string[];
}

export interface RawDiagnostic {
  readonly contractVersion: '1';
  readonly code: string;
  readonly severity: DiagnosticSeverity;
  readonly message: string;
  readonly context: Readonly<Record<string, string>>;
}

export interface SafeDiagnostic {
  readonly contractVersion: '1';
  readonly code: string;
  readonly severity: DiagnosticSeverity;
  readonly message: string;
  readonly context: Readonly<Record<string, string>>;
  readonly redactionCount: number;
  readonly provenanceDigest: string;
}

export interface AdapterAuditInput {
  readonly contractVersion: '1';
  readonly eventId: string;
  readonly eventType: string;
  readonly endpointId: string;
  readonly operation: string;
  readonly requestId: string;
  readonly traceId: string;
  readonly bindingId: string;
  readonly authenticationContextId: string;
  readonly outcome: AuditOutcome;
  readonly reasonCode: string;
  readonly occurredAtEpochSeconds: number;
}

export interface AdapterAuditEvent extends AdapterAuditInput {
  readonly provenanceDigest: string;
}

export interface ExceptionDiagnosticInput {
  readonly code: string;
  readonly severity: DiagnosticSeverity;
  readonly requestId: string;
  readonly error: unknown;
}

const secretRegistry = new WeakMap<ResolvedConfiguration, readonly string[]>();

export class UnsupportedDiagnosticsAuditVersionError extends Error {
  constructor(readonly contractVersion: unknown) {
    super(`Unsupported diagnostics/audit contract version: ${String(contractVersion)}`);
    this.name = 'UnsupportedDiagnosticsAuditVersionError';
  }
}

export class ResolvedConfiguration {
  private constructor(
    readonly publicValues: Readonly<Record<string, string>>,
    readonly sensitiveReferences: Readonly<Record<string, string>>,
    readonly provenance: ConfigurationProvenance,
  ) {}

  static create(
    publicValues: Readonly<Record<string, string>>,
    sensitiveReferences: Readonly<Record<string, string>>,
    provenance: ConfigurationProvenance,
    sensitiveLiterals: readonly string[],
  ): ResolvedConfiguration {
    const resolved = new ResolvedConfiguration(
      Object.freeze({ ...publicValues }),
      Object.freeze({ ...sensitiveReferences }),
      provenance,
    );
    secretRegistry.set(resolved, [...sensitiveLiterals]);
    return resolved;
  }
}

export class FakeConfigurationSource {
  constructor(private readonly snapshot: FakeConfigurationSnapshot) {}

  async load(): Promise<ResolvedConfiguration> {
    return resolveFakeConfiguration(this.snapshot);
  }
}

export class InMemoryAdapterAuditSink {
  readonly events: AdapterAuditEvent[] = [];

  append(event: AdapterAuditEvent): void {
    this.events.push(validateAuditEvent(event));
  }
}

export async function resolveFakeConfiguration(
  snapshot: FakeConfigurationSnapshot,
): Promise<ResolvedConfiguration> {
  validateVersion(snapshot.contractVersion);
  if (snapshot.sourceKind !== 'fixture') throw new TypeError('Only fixture configuration sources are supported in this safe slice');
  requireString(snapshot.sourceId, 'configuration.sourceId');
  requireString(snapshot.revision, 'configuration.revision');
  requireNonNegativeInteger(snapshot.loadedAtEpochSeconds, 'configuration.loadedAtEpochSeconds');
  if (!Array.isArray(snapshot.entries) || snapshot.entries.length === 0) {
    throw new TypeError('configuration.entries must be non-empty');
  }

  const seen = new Set<string>();
  const entries = [...snapshot.entries].map((entry) => {
    requireString(entry.key, 'configuration.entry.key');
    requireString(entry.value, 'configuration.entry.value');
    if (entry.classification !== 'public' && entry.classification !== 'sensitive') {
      throw new TypeError(`Unsupported configuration classification: ${String(entry.classification)}`);
    }
    if (seen.has(entry.key)) throw new TypeError(`Duplicate configuration key: ${entry.key}`);
    seen.add(entry.key);
    return entry;
  }).sort((left, right) => left.key.localeCompare(right.key));

  const digestEntries: JsonValue[] = [];
  const publicValues: Record<string, string> = {};
  const sensitiveReferences: Record<string, string> = {};
  const publicKeys: string[] = [];
  const sensitiveKeys: string[] = [];
  const sensitiveLiterals: string[] = [];

  for (const entry of entries) {
    const valueDigest = await sha256Hex(encoder.encode(entry.value));
    digestEntries.push({
      classification: entry.classification,
      key: entry.key,
      valueDigest,
    });
    if (entry.classification === 'public') {
      publicKeys.push(entry.key);
      publicValues[entry.key] = entry.value;
    } else {
      sensitiveKeys.push(entry.key);
      sensitiveLiterals.push(entry.value);
      sensitiveReferences[entry.key] = `sensitive:${snapshot.sourceId}:${entry.key}:${valueDigest.slice(0, 16)}`;
    }
  }

  const provenanceInput: JsonValue = {
    contractVersion: DIAGNOSTICS_AUDIT_VERSION,
    entries: digestEntries,
    loadedAtEpochSeconds: snapshot.loadedAtEpochSeconds,
    revision: snapshot.revision,
    sourceId: snapshot.sourceId,
    sourceKind: snapshot.sourceKind,
  };
  const contentDigest = await sha256Hex(encoder.encode(canonicalJson(provenanceInput)));
  const provenance: ConfigurationProvenance = Object.freeze({
    contractVersion: DIAGNOSTICS_AUDIT_VERSION,
    sourceKind: 'fixture',
    sourceId: snapshot.sourceId,
    revision: snapshot.revision,
    loadedAtEpochSeconds: snapshot.loadedAtEpochSeconds,
    contentDigest,
    publicKeys: Object.freeze(publicKeys),
    sensitiveKeys: Object.freeze(sensitiveKeys),
  });
  return ResolvedConfiguration.create(publicValues, sensitiveReferences, provenance, sensitiveLiterals);
}

export function renderSafeDiagnostic(
  configuration: ResolvedConfiguration,
  diagnostic: RawDiagnostic,
): SafeDiagnostic {
  validateVersion(diagnostic.contractVersion);
  requireString(diagnostic.code, 'diagnostic.code');
  requireSeverity(diagnostic.severity);
  requireString(diagnostic.message, 'diagnostic.message');
  if (diagnostic.context === null || typeof diagnostic.context !== 'object' || Array.isArray(diagnostic.context)) {
    throw new TypeError('diagnostic.context must be an object');
  }
  const secrets = secretRegistry.get(configuration) ?? [];
  let redactionCount = 0;
  const redactText = (value: string): string => {
    let output = value;
    for (const secret of secrets) {
      if (secret.length === 0) continue;
      const occurrences = countOccurrences(output, secret);
      if (occurrences > 0) {
        redactionCount += occurrences;
        output = output.split(secret).join(REDACTED);
      }
    }
    return output;
  };

  const context: Record<string, string> = {};
  for (const key of Object.keys(diagnostic.context).sort()) {
    requireString(key, 'diagnostic.context key');
    const value = diagnostic.context[key];
    requireString(value, `diagnostic.context.${key}`);
    if (SENSITIVE_KEY.test(key) || configuration.provenance.sensitiveKeys.includes(key)) {
      context[key] = REDACTED;
      redactionCount += 1;
    } else {
      context[key] = redactText(value);
    }
  }

  const safe: SafeDiagnostic = Object.freeze({
    contractVersion: DIAGNOSTICS_AUDIT_VERSION,
    code: diagnostic.code,
    severity: diagnostic.severity,
    message: redactText(diagnostic.message),
    context: Object.freeze(context),
    redactionCount,
    provenanceDigest: configuration.provenance.contentDigest,
  });
  assertNoSensitiveLiteral(configuration, canonicalJson(safe as unknown as JsonValue));
  return safe;
}

export function renderExceptionDiagnostic(
  configuration: ResolvedConfiguration,
  input: ExceptionDiagnosticInput,
): SafeDiagnostic {
  requireString(input.code, 'exceptionDiagnostic.code');
  requireSeverity(input.severity);
  requireString(input.requestId, 'exceptionDiagnostic.requestId');
  const errorType = input.error instanceof Error && input.error.name.length > 0 ? input.error.name : 'UnknownError';
  return renderSafeDiagnostic(configuration, {
    contractVersion: DIAGNOSTICS_AUDIT_VERSION,
    code: input.code,
    severity: input.severity,
    message: 'Adapter operation failed',
    context: {
      exceptionType: errorType,
      requestId: input.requestId,
    },
  });
}

export function createAdapterAuditEvent(
  configuration: ResolvedConfiguration,
  input: AdapterAuditInput,
): AdapterAuditEvent {
  validateVersion(input.contractVersion);
  validateAuditInput(input);
  const event: AdapterAuditEvent = Object.freeze({
    ...input,
    provenanceDigest: configuration.provenance.contentDigest,
  });
  assertNoSensitiveLiteral(configuration, canonicalJson(event as unknown as JsonValue));
  return event;
}

export function emitAdapterAuditEvent(
  sink: InMemoryAdapterAuditSink,
  event: AdapterAuditEvent,
): void {
  sink.append(event);
}

export function assertNoSensitiveLiteral(configuration: ResolvedConfiguration, output: string): void {
  requireString(output, 'output');
  const secrets = secretRegistry.get(configuration) ?? [];
  for (const secret of secrets) {
    if (secret.length > 0 && output.includes(secret)) {
      throw new Error('Sensitive configuration literal escaped redaction');
    }
  }
}

function validateAuditEvent(event: AdapterAuditEvent): AdapterAuditEvent {
  validateAuditInput(event);
  requireString(event.provenanceDigest, 'audit.provenanceDigest');
  return event;
}

function validateAuditInput(input: AdapterAuditInput): void {
  for (const [field, value] of Object.entries({
    eventId: input.eventId,
    eventType: input.eventType,
    endpointId: input.endpointId,
    operation: input.operation,
    requestId: input.requestId,
    traceId: input.traceId,
    bindingId: input.bindingId,
    authenticationContextId: input.authenticationContextId,
    reasonCode: input.reasonCode,
  })) requireString(value, `audit.${field}`);
  if (!['succeeded', 'failed', 'rejected'].includes(input.outcome)) throw new TypeError('audit.outcome is unsupported');
  requireNonNegativeInteger(input.occurredAtEpochSeconds, 'audit.occurredAtEpochSeconds');
}

function validateVersion(value: unknown): asserts value is '1' {
  if (value !== DIAGNOSTICS_AUDIT_VERSION) throw new UnsupportedDiagnosticsAuditVersionError(value);
}

function requireSeverity(value: unknown): asserts value is DiagnosticSeverity {
  if (value !== 'info' && value !== 'warning' && value !== 'error') {
    throw new TypeError('diagnostic.severity is unsupported');
  }
}

function requireString(value: unknown, field: string): asserts value is string {
  if (typeof value !== 'string' || value.length === 0) throw new TypeError(`${field} must be a non-empty string`);
}

function requireNonNegativeInteger(value: unknown, field: string): asserts value is number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) {
    throw new TypeError(`${field} must be a non-negative safe integer`);
  }
}

function countOccurrences(value: string, needle: string): number {
  let count = 0;
  let offset = 0;
  while ((offset = value.indexOf(needle, offset)) >= 0) {
    count += 1;
    offset += needle.length;
  }
  return count;
}
