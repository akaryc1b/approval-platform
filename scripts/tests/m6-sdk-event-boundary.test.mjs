import assert from 'node:assert/strict';
import { access, readdir, readFile } from 'node:fs/promises';
import { extname, join, relative } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const repositoryRoot = fileURLToPath(new URL('../..', import.meta.url));
const sdkRoots = [
  join(repositoryRoot, 'integrations/host-sdk/src/main/java/io/github/akaryc1b/approval/sdk/v1'),
  join(repositoryRoot, 'packages/approval-sdk'),
  join(repositoryRoot, 'contracts/sdk/v1'),
];

async function exists(path) {
  try { await access(path); return true; } catch { return false; }
}

async function files(root) {
  if (!await exists(root)) return [];
  const entries = await readdir(root, { withFileTypes: true });
  const output = [];
  for (const entry of entries) {
    const path = join(root, entry.name);
    if (entry.isDirectory()) output.push(...await files(path));
    else output.push(path);
  }
  return output;
}

async function sdkSources() {
  const paths = (await Promise.all(sdkRoots.map(files))).flat()
    .filter((path) => ['.java', '.ts', '.mjs'].includes(extname(path)));
  return Promise.all(paths.map(async (path) => ({ path, content: await readFile(path, 'utf8') })));
}

function display(path) { return relative(repositoryRoot, path); }

test('SDK source exposes no Flowable or M5 migration execution API', async () => {
  const offenders = (await sdkSources()).filter(({ content }) =>
    /\b(?:RuntimeService|TaskService|ProcessMigrationService|ACT_[A-Z_]+|migration(?:Execute|Force|Rollback)|forceMigration|rollbackMigration)\b/i.test(content),
  ).map(({ path }) => display(path));
  assert.deepEqual(offenders, []);
});

test('SDK source contains only abstractions and mock transport, never real network calls', async () => {
  const offenders = (await sdkSources()).filter(({ content }) =>
    /\b(?:java\.net\.http|HttpClient|HttpURLConnection|XMLHttpRequest|axios|undici)\b|\bfetch\s*\(/.test(content),
  ).map(({ path }) => display(path));
  assert.deepEqual(offenders, []);
});

test('SDK source contains no subscription persistence or delivery worker', async () => {
  const offenders = (await sdkSources()).filter(({ path, content }) =>
    /(?:subscription|delivery)[-_]?(?:repository|store|entity|table|worker)/i.test(path)
      || /@(?:Entity|Table)|CREATE\s+TABLE|JdbcTemplate|EntityManager|delivery\s+worker/i.test(content),
  ).map(({ path }) => display(path));
  assert.deepEqual(offenders, []);
});

test('public client requests cannot manufacture trusted server evidence', async () => {
  const java = await readFile(join(sdkRoots[0], 'ApprovalSdk.java'), 'utf8');
  const typescript = await readFile(join(sdkRoots[1], 'src/index.ts'), 'utf8');
  const javaRequest = java.slice(java.indexOf('public record Request('), java.indexOf('public record Correlation('));
  const tsRequest = typescript.slice(typescript.indexOf('export interface ApprovalRequest'), typescript.indexOf('export interface ApprovalTransport'));
  for (const forbidden of ['tenantId', 'operatorId', 'permission', 'authority', 'auditEvidence', 'credentialLease']) {
    assert.doesNotMatch(javaRequest, new RegExp(`\\b${forbidden}\\b`, 'i'));
    assert.doesNotMatch(tsRequest, new RegExp(`\\b${forbidden}\\b`, 'i'));
  }
});

test('transport policy uses virtual time and scripted adapters only', async () => {
  const java = await readFile(join(sdkRoots[0], 'SdkTransportPolicyV1.java'), 'utf8');
  const typescript = await readFile(join(sdkRoots[1], 'src/transport-policy.ts'), 'utf8');
  assert.match(java, /class ScriptedAdapter/);
  assert.match(java, /totalBudgetMillis/);
  assert.match(typescript, /class ScriptedConformanceAdapter/);
  assert.match(typescript, /totalBudgetMillis/);
  assert.doesNotMatch(java, /\b(?:Thread\.sleep|ScheduledExecutorService|CompletableFuture|URI|URL)\b/);
  assert.doesNotMatch(typescript, /\b(?:setTimeout|setInterval|AbortController|WebSocket|EventSource)\b/);
});

test('adapter binding uses logical endpoints and reference-only credential leases', async () => {
  const java = await readFile(join(sdkRoots[0], 'SdkAdapterBindingV1.java'), 'utf8');
  const typescript = await readFile(join(sdkRoots[1], 'src/adapter-binding.ts'), 'utf8');
  assert.match(java, /class AuthenticationContext/);
  assert.match(java, /private AuthenticationContext\(/);
  assert.match(java, /private CredentialLease\(/);
  assert.match(java, /class ScriptedSecurityBoundAdapter/);
  assert.match(typescript, /const SERVER_AUTH_CONTEXT: unique symbol/);
  assert.match(typescript, /const CREDENTIAL_LEASE: unique symbol/);
  assert.match(typescript, /class ScriptedSecurityBoundAdapter/);

  const javaEndpoint = java.slice(java.indexOf('public record EndpointDescriptor('), java.indexOf('public record AuthenticationContextRequest('));
  const tsEndpoint = typescript.slice(typescript.indexOf('export interface LogicalEndpointDescriptor'), typescript.indexOf('export interface AuthenticationContextRequest'));
  const javaContextRequest = java.slice(java.indexOf('public record AuthenticationContextRequest('), java.indexOf('public record AuthenticationContextFields('));
  const tsContextRequest = typescript.slice(typescript.indexOf('export interface AuthenticationContextRequest'), typescript.indexOf('export interface ServerAuthenticationContextFields'));
  const javaCredential = java.slice(java.indexOf('public record CredentialReference('), java.indexOf('public record EndpointDescriptor('));
  const tsCredential = typescript.slice(typescript.indexOf('export interface CredentialReference'), typescript.indexOf('export interface LogicalEndpointDescriptor'));

  for (const forbidden of ['url', 'uri', 'host', 'address', 'baseUrl', 'route']) {
    assert.doesNotMatch(javaEndpoint, new RegExp(`\\b${forbidden}\\b`, 'i'));
    assert.doesNotMatch(tsEndpoint, new RegExp(`\\b${forbidden}\\b`, 'i'));
  }
  for (const forbidden of ['tenantId', 'operatorId', 'permissionSnapshotHash', 'auditReference', 'credentialLease']) {
    assert.doesNotMatch(javaContextRequest, new RegExp(`\\b${forbidden}\\b`, 'i'));
    assert.doesNotMatch(tsContextRequest, new RegExp(`\\b${forbidden}\\b`, 'i'));
  }
  for (const forbidden of ['secret', 'password', 'privateKey', 'headerValue', 'credentialMaterial']) {
    assert.doesNotMatch(javaCredential, new RegExp(`\\b${forbidden}\\b`, 'i'));
    assert.doesNotMatch(tsCredential, new RegExp(`\\b${forbidden}\\b`, 'i'));
  }
  assert.doesNotMatch(java, /\b(?:System\.currentTimeMillis|Instant\.now|Thread\.sleep|URI|URL|Socket)\b/);
  assert.doesNotMatch(typescript, /\b(?:Date\.now|setTimeout|setInterval|AbortController|WebSocket|EventSource)\b/);
});

test('diagnostics and audit use fake configuration, redaction and memory sink only', async () => {
  const java = await readFile(join(sdkRoots[0], 'SdkDiagnosticsAuditV1.java'), 'utf8');
  const typescript = await readFile(join(sdkRoots[1], 'src/diagnostics-audit.ts'), 'utf8');
  assert.match(java, /class FakeConfigurationSource/);
  assert.match(java, /class InMemoryAdapterAuditSink/);
  assert.match(java, /\[REDACTED\]/);
  assert.match(typescript, /class FakeConfigurationSource/);
  assert.match(typescript, /class InMemoryAdapterAuditSink/);
  assert.match(typescript, /\[REDACTED\]/);
  assert.doesNotMatch(java, /\b(?:System\.getenv|System\.getProperty|Files\.|Path\.|Vault|SecretManager|Logger|printStackTrace|System\.out)\b/);
  assert.doesNotMatch(typescript, /\b(?:process\.env|Deno\.env|Bun\.env|readFile|writeFile|console\.|localStorage|sessionStorage)\b/);

  const javaProvenance = java.slice(java.indexOf('public record ConfigurationProvenance('), java.indexOf('public static final class ResolvedConfiguration'));
  const tsProvenance = typescript.slice(typescript.indexOf('export interface ConfigurationProvenance'), typescript.indexOf('export interface RawDiagnostic'));
  const javaAudit = java.slice(java.indexOf('public record AdapterAuditEvent('), java.indexOf('public static final class InMemoryAdapterAuditSink'));
  const tsAudit = typescript.slice(typescript.indexOf('export interface AdapterAuditEvent'), typescript.indexOf('export interface ExceptionDiagnosticInput'));
  for (const forbidden of ['value', 'tenantId', 'operatorId', 'permissionSnapshotHash', 'auditReference', 'credentialReference', 'credentialLease', 'secret', 'password', 'privateKey', 'bearerToken']) {
    assert.doesNotMatch(javaProvenance, new RegExp(`\\b${forbidden}\\b`, 'i'));
    assert.doesNotMatch(tsProvenance, new RegExp(`\\b${forbidden}\\b`, 'i'));
    assert.doesNotMatch(javaAudit, new RegExp(`\\b${forbidden}\\b`, 'i'));
    assert.doesNotMatch(tsAudit, new RegExp(`\\b${forbidden}\\b`, 'i'));
  }
});

test('emission policy uses deterministic bounded fake sinks and atomic audit batches', async () => {
  const javaDiagnostic = await readFile(join(sdkRoots[0], 'SdkDiagnosticEmissionV1.java'), 'utf8');
  const javaAudit = await readFile(join(sdkRoots[0], 'SdkAuditCompletenessV1.java'), 'utf8');
  const java = `${javaDiagnostic}\n${javaAudit}`;
  const tsDiagnostic = await readFile(join(sdkRoots[1], 'src/diagnostic-emission.ts'), 'utf8');
  const tsAudit = await readFile(join(sdkRoots[1], 'src/audit-completeness.ts'), 'utf8');
  const typescript = `${tsDiagnostic}\n${tsAudit}`;
  assert.match(java, /class BoundedDiagnosticDeduplicationTracker/);
  assert.match(java, /class ScriptedInMemoryDiagnosticSink/);
  assert.match(java, /class ScriptedAtomicAuditSink/);
  assert.match(java, /appendBatch/);
  assert.match(java, /FAILED_CLOSED/);
  assert.match(typescript, /class BoundedDiagnosticDeduplicationTracker/);
  assert.match(typescript, /class ScriptedInMemoryDiagnosticSink/);
  assert.match(typescript, /class ScriptedAtomicAuditSink/);
  assert.match(typescript, /appendBatch/);
  assert.match(typescript, /failed_closed/);
  assert.doesNotMatch(java, /\b(?:System\.currentTimeMillis|Instant\.now|Thread\.sleep|System\.getenv|System\.getProperty|Files\.|Path\.|Logger|JdbcTemplate|EntityManager|System\.out)\b/);
  assert.doesNotMatch(typescript, /\b(?:Date\.now|setTimeout|setInterval|process\.env|Deno\.env|Bun\.env|readFile|writeFile|console\.|localStorage|sessionStorage|indexedDB)\b/);

  const javaDiagnosticResult = javaDiagnostic.slice(javaDiagnostic.indexOf('public record DiagnosticEmissionResult('), javaDiagnostic.indexOf('public static final class BoundedDiagnosticDeduplicationTracker'));
  const tsDiagnosticResult = tsDiagnostic.slice(tsDiagnostic.indexOf('export interface DiagnosticEmissionResult'), tsDiagnostic.indexOf('export class UnsupportedEmissionPolicyVersionError'));
  const javaAuditResult = javaAudit.slice(javaAudit.indexOf('public record AuditBatchEmissionResult('), javaAudit.indexOf('public static final class ScriptedAtomicAuditSink'));
  const tsAuditResult = tsAudit.slice(tsAudit.indexOf('export interface AuditBatchEmissionResult'), tsAudit.indexOf('export class ScriptedAtomicAuditSink'));
  for (const forbidden of ['error', 'exception', 'stackTrace', 'rawMessage', 'tenantId', 'operatorId', 'permissionSnapshotHash', 'auditReference', 'credentialLease', 'secret', 'password', 'privateKey', 'bearerToken']) {
    assert.doesNotMatch(javaDiagnosticResult, new RegExp(`\\b${forbidden}\\b`, 'i'));
    assert.doesNotMatch(tsDiagnosticResult, new RegExp(`\\b${forbidden}\\b`, 'i'));
    assert.doesNotMatch(javaAuditResult, new RegExp(`\\b${forbidden}\\b`, 'i'));
    assert.doesNotMatch(tsAuditResult, new RegExp(`\\b${forbidden}\\b`, 'i'));
  }
});

test('Flyway remains frozen through V32', async () => {
  const migrationRoots = [
    join(repositoryRoot, 'apps/server/src/main/resources/db/migration'),
    join(repositoryRoot, 'server-modules'),
  ];
  const migrationFiles = (await Promise.all(migrationRoots.map(files))).flat();
  assert.deepEqual(migrationFiles.filter((path) => /(?:^|[/\\])V33__/.test(path)).map(display), []);
});

test('there is exactly one automatic PR/main validation workflow', async () => {
  const workflowRoot = join(repositoryRoot, '.github/workflows');
  if (!await exists(workflowRoot)) return;
  const workflows = (await files(workflowRoot)).filter((path) => /\.ya?ml$/.test(path));
  const automatic = [];
  for (const path of workflows) {
    const content = await readFile(path, 'utf8');
    if (/\bpull_request:\s*[\s\S]*?branches:\s*[\s\S]*?-\s*main\b/.test(content)) automatic.push(display(path));
  }
  assert.deepEqual(automatic, ['.github/workflows/approval-platform-validation.yml']);
});
