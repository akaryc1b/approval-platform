import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const repositoryRoot = fileURLToPath(new URL('../..', import.meta.url));
const javaRoot = join(repositoryRoot, 'integrations/host-sdk/src/main/java/io/github/akaryc1b/approval/sdk/v1');
const tsRoot = join(repositoryRoot, 'packages/approval-sdk/src');

function forbid(source, words) {
  for (const word of words) assert.doesNotMatch(source, new RegExp(`\\b${word}\\b`, 'i'));
}

test('aggregation and reconciliation use bounded scripted in-memory stores only', async () => {
  const javaAggregation = await readFile(join(javaRoot, 'SdkTelemetryAggregationV1.java'), 'utf8');
  const javaReconciliation = await readFile(join(javaRoot, 'SdkAuditHandoffReconciliationV1.java'), 'utf8');
  const tsAggregation = await readFile(join(tsRoot, 'telemetry-aggregation.ts'), 'utf8');
  const tsReconciliation = await readFile(join(tsRoot, 'audit-handoff-reconciliation.ts'), 'utf8');
  assert.match(javaAggregation, /class ScriptedTelemetryAggregationStore/);
  assert.match(javaReconciliation, /class ScriptedHandoffReconciliationStore/);
  assert.match(tsAggregation, /class ScriptedTelemetryAggregationStore/);
  assert.match(tsReconciliation, /class ScriptedHandoffReconciliationStore/);
  assert.match(javaAggregation, /windowStartOrdinal/);
  assert.match(tsAggregation, /windowStartOrdinal/);
  assert.match(javaReconciliation, /ACKNOWLEDGEMENT_MISSING/);
  assert.match(tsReconciliation, /acknowledgement_missing/);
});

test('aggregation and reconciliation contain no clock, network, broker, logger or persistence implementation', async () => {
  const java = `${await readFile(join(javaRoot, 'SdkTelemetryAggregationV1.java'), 'utf8')}\n${await readFile(join(javaRoot, 'SdkAuditHandoffReconciliationV1.java'), 'utf8')}`;
  const typescript = `${await readFile(join(tsRoot, 'telemetry-aggregation.ts'), 'utf8')}\n${await readFile(join(tsRoot, 'audit-handoff-reconciliation.ts'), 'utf8')}`;
  assert.doesNotMatch(java, /\b(?:System\.currentTimeMillis|Instant\.now|Thread\.sleep|System\.getenv|System\.getProperty|Files\.|Path\.|Logger|Kafka|Rabbit|JMS|JdbcTemplate|EntityManager|Socket|URI|URL|System\.out)\b/);
  assert.doesNotMatch(typescript, /\b(?:Date\.now|setTimeout|setInterval|process\.env|Deno\.env|Bun\.env|readFile|writeFile|console\.|localStorage|sessionStorage|indexedDB|Kafka|Rabbit|JMS|WebSocket|EventSource|fetch)\b/);
});

test('aggregate snapshots and reconciliation proofs remain reference-only', async () => {
  const javaAggregation = await readFile(join(javaRoot, 'SdkTelemetryAggregationV1.java'), 'utf8');
  const javaReconciliation = await readFile(join(javaRoot, 'SdkAuditHandoffReconciliationV1.java'), 'utf8');
  const tsAggregation = await readFile(join(tsRoot, 'telemetry-aggregation.ts'), 'utf8');
  const tsReconciliation = await readFile(join(tsRoot, 'audit-handoff-reconciliation.ts'), 'utf8');
  const javaSnapshot = javaAggregation.slice(javaAggregation.indexOf('public record TelemetryAggregationSnapshot('), javaAggregation.indexOf('public record TelemetryAggregationResult('));
  const tsSnapshot = tsAggregation.slice(tsAggregation.indexOf('export interface TelemetryAggregationSnapshot'), tsAggregation.indexOf('export interface TelemetryAggregationResult'));
  const javaProof = javaReconciliation.slice(javaReconciliation.indexOf('public record AuditHandoffReconciliationProof('), javaReconciliation.indexOf('public record AuditHandoffReconciliationResult('));
  const tsProof = tsReconciliation.slice(tsReconciliation.indexOf('export interface AuditHandoffReconciliationProof'), tsReconciliation.indexOf('export interface AuditHandoffReconciliationResult'));
  const forbidden = ['requestId', 'traceId', 'bindingId', 'authenticationContextId', 'tenantId', 'operatorId', 'permissionSnapshotHash', 'auditReference', 'credentialReference', 'credentialLease', 'secret', 'password', 'privateKey', 'bearerToken', 'url', 'uri', 'host', 'address', 'records'];
  forbid(javaSnapshot, forbidden);
  forbid(tsSnapshot, forbidden);
  forbid(javaProof, forbidden);
  forbid(tsProof, forbidden);
});

test('only acknowledged confirmation can authorize handoff finalization', async () => {
  const java = await readFile(join(javaRoot, 'SdkAuditHandoffReconciliationV1.java'), 'utf8');
  const typescript = await readFile(join(tsRoot, 'audit-handoff-reconciliation.ts'), 'utf8');
  assert.match(java, /safeToFinalize[\s\S]*ACKNOWLEDGED_CONFIRMED/);
  assert.match(typescript, /safeToFinalize:[\s\S]*acknowledged_confirmed/);
  assert.match(java, /CONFLICTING_EVIDENCE/);
  assert.match(typescript, /conflicting_evidence/);
});
