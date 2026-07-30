export const SDK_COMPATIBILITY_MANIFEST_VERSION = '1' as const;

export type CompatibilityNegotiationStatus =
  | 'compatible'
  | 'contract_support_expired'
  | 'client_upgrade_required'
  | 'no_common_event_schema'
  | 'no_common_webhook_protocol'
  | 'required_capability_unavailable'
  | 'required_capability_sunset';

export interface CapabilityRequest {
  readonly name: string;
  readonly required: boolean;
}

export interface ClientCompatibilityProfile {
  readonly manifestVersion: string;
  readonly sdkVersion: string;
  readonly eventSchemaVersions: readonly string[];
  readonly webhookProtocolVersions: readonly string[];
  readonly capabilities: readonly CapabilityRequest[];
}

export interface DeprecationNotice {
  readonly capability: string;
  readonly deprecatedSince: string;
  readonly sunsetAt: string;
  readonly replacement: string;
}

export interface ServerCompatibilityProfile {
  readonly manifestVersion: string;
  readonly contractVersion: string;
  readonly minimumClientVersion: string;
  readonly supportedUntil: string;
  readonly eventSchemaVersions: readonly string[];
  readonly webhookProtocolVersions: readonly string[];
  readonly capabilities: readonly string[];
  readonly deprecations: readonly DeprecationNotice[];
}

export interface CompatibilityNegotiationResult {
  readonly status: CompatibilityNegotiationStatus;
  readonly contractVersion: string;
  readonly eventSchemaVersion: string | null;
  readonly webhookProtocolVersion: string | null;
  readonly enabledCapabilities: readonly string[];
  readonly warnings: readonly string[];
}

export class UnsupportedCompatibilityManifestError extends Error {
  constructor(readonly manifestVersion: unknown) {
    super(`Unsupported compatibility manifest version: ${String(manifestVersion)}`);
    this.name = 'UnsupportedCompatibilityManifestError';
  }
}

export interface SemanticVersion {
  readonly major: number;
  readonly minor: number;
  readonly patch: number;
}

const SEMANTIC_VERSION = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
const UTC_INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/;

export function parseSemanticVersion(value: string): SemanticVersion {
  requireString(value, 'semantic version');
  const match = SEMANTIC_VERSION.exec(value);
  if (!match) throw new TypeError(`Invalid semantic version: ${value}`);
  return {
    major: Number(match[1]),
    minor: Number(match[2]),
    patch: Number(match[3]),
  };
}

export function compareSemanticVersions(left: string, right: string): number {
  const a = parseSemanticVersion(left);
  const b = parseSemanticVersion(right);
  return a.major - b.major || a.minor - b.minor || a.patch - b.patch;
}

export function negotiateCompatibility(
  client: ClientCompatibilityProfile,
  server: ServerCompatibilityProfile,
  evaluatedAt: string,
): CompatibilityNegotiationResult {
  validateClient(client);
  validateServer(server);
  const evaluatedAtMillis = parseUtcInstant(evaluatedAt, 'evaluatedAt');
  const supportedUntilMillis = parseUtcInstant(server.supportedUntil, 'server.supportedUntil');

  if (evaluatedAtMillis >= supportedUntilMillis) {
    return result('contract_support_expired', server.contractVersion, null, null, [], [
      `contract support expired at ${server.supportedUntil}`,
    ]);
  }
  if (compareSemanticVersions(client.sdkVersion, server.minimumClientVersion) < 0) {
    return result('client_upgrade_required', server.contractVersion, null, null, [], [
      `minimum client version is ${server.minimumClientVersion}`,
    ]);
  }

  const eventSchemaVersion = firstCommon(server.eventSchemaVersions, client.eventSchemaVersions);
  if (eventSchemaVersion === null) {
    return result('no_common_event_schema', server.contractVersion, null, null, [], []);
  }
  const webhookProtocolVersion = firstCommon(server.webhookProtocolVersions, client.webhookProtocolVersions);
  if (webhookProtocolVersion === null) {
    return result('no_common_webhook_protocol', server.contractVersion, eventSchemaVersion, null, [], []);
  }

  const availableCapabilities = new Set(server.capabilities);
  const deprecations = new Map(server.deprecations.map((notice) => [notice.capability, notice] as const));
  const enabledCapabilities: string[] = [];
  const warnings: string[] = [];
  const seen = new Set<string>();

  for (const request of client.capabilities) {
    requireString(request.name, 'client.capabilities.name');
    if (seen.has(request.name)) throw new TypeError(`Duplicate capability request: ${request.name}`);
    seen.add(request.name);

    if (!availableCapabilities.has(request.name)) {
      if (request.required) {
        warnings.push(`required capability unavailable: ${request.name}`);
        return result(
          'required_capability_unavailable',
          server.contractVersion,
          eventSchemaVersion,
          webhookProtocolVersion,
          enabledCapabilities,
          warnings,
        );
      }
      warnings.push(`optional capability unavailable: ${request.name}`);
      continue;
    }

    const notice = deprecations.get(request.name);
    if (notice) {
      const deprecatedSince = parseUtcInstant(notice.deprecatedSince, `deprecation ${request.name}.deprecatedSince`);
      const sunsetAt = parseUtcInstant(notice.sunsetAt, `deprecation ${request.name}.sunsetAt`);
      if (sunsetAt <= deprecatedSince) throw new TypeError(`Deprecation sunset must follow deprecatedSince: ${request.name}`);
      if (evaluatedAtMillis >= sunsetAt) {
        const warning = `${request.required ? 'required' : 'optional'} capability sunset: ${request.name}; sunsetAt=${notice.sunsetAt}; replacement=${notice.replacement}`;
        warnings.push(warning);
        if (request.required) {
          return result(
            'required_capability_sunset',
            server.contractVersion,
            eventSchemaVersion,
            webhookProtocolVersion,
            enabledCapabilities,
            warnings,
          );
        }
        continue;
      }
      if (evaluatedAtMillis >= deprecatedSince) {
        warnings.push(`deprecated capability ${request.name}; sunsetAt=${notice.sunsetAt}; replacement=${notice.replacement}`);
      }
    }
    enabledCapabilities.push(request.name);
  }

  return result(
    'compatible',
    server.contractVersion,
    eventSchemaVersion,
    webhookProtocolVersion,
    enabledCapabilities,
    warnings,
  );
}

function validateClient(client: ClientCompatibilityProfile): void {
  requireManifest(client.manifestVersion);
  parseSemanticVersion(client.sdkVersion);
  requireUniqueStrings(client.eventSchemaVersions, 'client.eventSchemaVersions');
  requireUniqueStrings(client.webhookProtocolVersions, 'client.webhookProtocolVersions');
  if (!Array.isArray(client.capabilities)) throw new TypeError('client.capabilities must be an array');
}

function validateServer(server: ServerCompatibilityProfile): void {
  requireManifest(server.manifestVersion);
  parseSemanticVersion(server.contractVersion);
  parseSemanticVersion(server.minimumClientVersion);
  parseUtcInstant(server.supportedUntil, 'server.supportedUntil');
  requireUniqueStrings(server.eventSchemaVersions, 'server.eventSchemaVersions');
  requireUniqueStrings(server.webhookProtocolVersions, 'server.webhookProtocolVersions');
  requireUniqueStrings(server.capabilities, 'server.capabilities');
  const seen = new Set<string>();
  for (const notice of server.deprecations) {
    requireString(notice.capability, 'server.deprecations.capability');
    requireString(notice.replacement, 'server.deprecations.replacement');
    const deprecatedSince = parseUtcInstant(notice.deprecatedSince, `deprecation ${notice.capability}.deprecatedSince`);
    const sunsetAt = parseUtcInstant(notice.sunsetAt, `deprecation ${notice.capability}.sunsetAt`);
    if (sunsetAt <= deprecatedSince) throw new TypeError(`Deprecation sunset must follow deprecatedSince: ${notice.capability}`);
    if (seen.has(notice.capability)) throw new TypeError(`Duplicate deprecation notice: ${notice.capability}`);
    seen.add(notice.capability);
  }
}

function requireManifest(value: unknown): void {
  if (value !== SDK_COMPATIBILITY_MANIFEST_VERSION) throw new UnsupportedCompatibilityManifestError(value);
}

function firstCommon(preferred: readonly string[], accepted: readonly string[]): string | null {
  const acceptedSet = new Set(accepted);
  return preferred.find((value) => acceptedSet.has(value)) ?? null;
}

function requireUniqueStrings(values: readonly string[], field: string): void {
  if (!Array.isArray(values) || values.length === 0) throw new TypeError(`${field} must be a non-empty array`);
  const seen = new Set<string>();
  for (const value of values) {
    requireString(value, field);
    if (seen.has(value)) throw new TypeError(`${field} contains duplicate value: ${value}`);
    seen.add(value);
  }
}

function requireString(value: unknown, field: string): asserts value is string {
  if (typeof value !== 'string' || value.length === 0) throw new TypeError(`${field} must be a non-empty string`);
}

function parseUtcInstant(value: string, field: string): number {
  requireString(value, field);
  if (!UTC_INSTANT.test(value)) throw new TypeError(`${field} must be an RFC 3339 UTC instant`);
  const parsed = Date.parse(value);
  if (!Number.isFinite(parsed)) throw new TypeError(`${field} must be an RFC 3339 UTC instant`);
  return parsed;
}

function result(
  status: CompatibilityNegotiationStatus,
  contractVersion: string,
  eventSchemaVersion: string | null,
  webhookProtocolVersion: string | null,
  enabledCapabilities: readonly string[],
  warnings: readonly string[],
): CompatibilityNegotiationResult {
  return {
    status,
    contractVersion,
    eventSchemaVersion,
    webhookProtocolVersion,
    enabledCapabilities: [...enabledCapabilities],
    warnings: [...warnings],
  };
}
