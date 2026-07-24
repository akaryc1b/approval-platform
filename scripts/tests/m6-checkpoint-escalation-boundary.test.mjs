import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../..', import.meta.url));
const javaRoot = join(
  root,
  'integrations/host-sdk/src/main/java/io/github/akaryc1b/approval/sdk/v1',
);
const tsRoot = join(root, 'packages/approval-sdk/src');

async function sources() {
  const paths = [
    join(javaRoot, 'SdkAggregateExportCheckpointV1.java'),
    join(javaRoot, 'SdkReconciliationEscalationV1.java'),
    join(tsRoot, 'aggregate-export-checkpoint.ts'),
    join(tsRoot, 'reconciliation-escalation.ts'),
  ];
  return Promise.all(paths.map(async (path) => ({
    path,
    content: await readFile(path, 'utf8'),
  })));
}

function forbid(content, pattern) {
  assert.doesNotMatch(content, pattern);
}

test('checkpoint and escalation use deterministic bounded fake stores only', async () => {
  const all = (await sources()).map(({ content }) => content).join('\n');
  assert.match(all, /ScriptedAggregateCheckpointStore/);
  assert.match(all, /ScriptedReconciliationEscalationStore/);
  assert.match(all, /checkpointOrdinal/);
  assert.match(all, /evaluationOrdinal/);
  forbid(
    all,
    /\b(?:System\.currentTimeMillis|Instant\.now|Thread\.sleep|Date\.now|setTimeout|setInterval)\b/,
  );
  forbid(
    all,
    /\b(?:Kafka|RabbitMQ|Rabbit|JMS|JdbcTemplate|EntityManager|CREATE\s+TABLE|OpenTelemetry)\b/i,
  );
  forbid(
    all,
    /\b(?:java\.net\.http|HttpClient|HttpURLConnection|XMLHttpRequest|axios|undici)\b|\bfetch\s*\(/,
  );
});

test('aggregate checkpoint is reference-only and rejects partial export', async () => {
  const java = await readFile(join(javaRoot, 'SdkAggregateExportCheckpointV1.java'), 'utf8');
  const typescript = await readFile(join(tsRoot, 'aggregate-export-checkpoint.ts'), 'utf8');
  assert.match(java, /aggregate_checkpoint_partial_export/);
  assert.match(typescript, /aggregate_checkpoint_partial_export/);
  assert.match(java, /SNAPSHOT_REUSE_REJECTED/);
  assert.match(typescript, /snapshot_reuse_rejected/);
  const javaCheckpoint = java.slice(
    java.indexOf('public record AggregateExportCheckpoint('),
    java.indexOf('public record AggregateExportCheckpointResult('),
  );
  const tsCheckpoint = typescript.slice(
    typescript.indexOf('export interface AggregateExportCheckpoint {'),
    typescript.indexOf('export interface AggregateExportCheckpointResult'),
  );
  const forbidden = /\b(?:requestId|traceId|bindingId|authenticationContextId|tenantId|operatorId|permission|credential|secret|password|url|uri|host|address)\b/i;
  forbid(javaCheckpoint, forbidden);
  forbid(tsCheckpoint, forbidden);
});

test('only recorded acknowledged reconciliation can carry finalization checkpoint', async () => {
  const java = await readFile(join(javaRoot, 'SdkReconciliationEscalationV1.java'), 'utf8');
  const typescript = await readFile(join(tsRoot, 'reconciliation-escalation.ts'), 'utf8');
  assert.match(java, /ACKNOWLEDGED_CONFIRMED/);
  assert.match(java, /ReconciliationEscalationLevel\.RESOLVED/);
  assert.match(typescript, /classification === 'acknowledged_confirmed'/);
  assert.match(typescript, /level === 'resolved'/);
  assert.match(java, /reconciliation_finalization_checkpoint_recorded/);
  assert.match(typescript, /reconciliation_finalization_checkpoint_recorded/);
  const forbidden = /\b(?:auditRecords|tenantId|operatorId|permissionSnapshotHash|credentialLease|bearerToken|privateKey|url|uri|host|address)\b/i;
  forbid(java, forbidden);
  forbid(typescript, forbidden);
});

test('capacity and scripted failure cannot partially commit checkpoint or finalization', async () => {
  const javaCheckpoint = await readFile(
    join(javaRoot, 'SdkAggregateExportCheckpointV1.java'),
    'utf8',
  );
  const javaEscalation = await readFile(
    join(javaRoot, 'SdkReconciliationEscalationV1.java'),
    'utf8',
  );
  const tsCheckpoint = await readFile(join(tsRoot, 'aggregate-export-checkpoint.ts'), 'utf8');
  const tsEscalation = await readFile(join(tsRoot, 'reconciliation-escalation.ts'), 'utf8');
  assert.match(javaCheckpoint, /checkpoints\.add[\s\S]*latestByStream\.put/);
  assert.match(tsCheckpoint, /checkpointsInternal\.push[\s\S]*latestByStream\.set/);
  assert.match(javaEscalation, /values\.add[\s\S]*finalizationByHandoff\.put/);
  assert.match(tsEscalation, /values\.push[\s\S]*finalizationByHandoff\.set/);
  for (const source of [javaCheckpoint, javaEscalation, tsCheckpoint, tsEscalation]) {
    assert.match(source, /capacity/i);
    assert.match(source, /failed/i);
  }
});
