import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';

const root = process.cwd();
const advicePath = path.join(
  root,
  'apps/server/src/main/java/io/github/akaryc1b/approval/api/ApprovalReleaseGovernanceObservabilityAdvice.java',
);
const transportPath = path.join(
  root,
  'apps/web/overlay/apps/web-ele/src/api/approval/transport.ts',
);

async function text(file) {
  return readFile(file, 'utf8');
}

test('release governance errors preserve bounded trace evidence', async () => {
  const advice = await text(advicePath);
  const transport = await text(transportPath);

  for (const field of [
    'String errorCode',
    'String message',
    'String requestId',
    'String traceId',
    'Instant timestamp',
    'Map<String, String> details',
  ]) {
    assert.match(advice, new RegExp(field));
  }
  assert.match(advice, /MAX_MESSAGE_CODE_POINTS = 512/);
  assert.match(advice, /MAX_EVIDENCE_CODE_POINTS = 128/);
  assert.match(advice, /MDC\.get\("requestId"\)/);
  assert.match(advice, /MDC\.get\("traceId"\)/);
  assert.match(advice, /Map\.of\("failureClass", failureClass\.metricValue\(\)\)/);
  assert.doesNotMatch(advice, /stackTrace|SQLException|ACT_[A-Z0-9_]+/);

  assert.match(transport, /errorCode\?: string/);
  assert.match(transport, /timestamp\?: string/);
  assert.match(transport, /traceId\?: string/);
  assert.match(transport, /payload\.errorCode \|\| payload\.code/);
  assert.match(transport, /payload\.timestamp \|\| payload\.occurredAt/);
});

test('release governance metrics use closed low-cardinality tags only', async () => {
  const advice = await text(advicePath);
  assert.match(advice, /approval\.release\.lifecycle\.operation/);
  assert.match(advice, /approval\.release\.migration\.assessment/);

  const counterBlocks = [...advice.matchAll(/meters\.counter\(([\s\S]*?)\)\.increment\(\)/g)]
    .map((match) => match[1]);
  assert.equal(counterBlocks.length, 4, 'expected two success and two failure counter paths');

  const allowedTagKeys = new Set([
    'operation',
    'result',
    'failure_class',
    'status',
    'completeness',
  ]);
  const forbiddenTagKeys = new Set([
    'tenantId',
    'operatorId',
    'definitionKey',
    'releaseVersion',
    'instanceId',
    'taskId',
    'packageHash',
    'reportHash',
    'requestId',
    'traceId',
    'reason',
  ]);
  for (const block of counterBlocks) {
    const tagKeys = [...block.matchAll(/^\s*"([a-z_]+)"\s*,/gm)]
      .map((match) => match[1]);
    assert.ok(tagKeys.length >= 3, `metric block has too few tags: ${block}`);
    for (const key of tagKeys) {
      assert.ok(allowedTagKeys.has(key), `unbounded metric tag key: ${key}`);
      assert.ok(!forbiddenTagKeys.has(key), `forbidden metric tag key: ${key}`);
    }
  }

  for (const operation of ['publish', 'activate', 'rollback', 'deprecate', 'retire']) {
    assert.match(advice, new RegExp(`\\b${operation.toUpperCase()}\\(\\"${operation}\\"\\)`));
  }
  assert.doesNotMatch(advice, /"tenantId"\s*,|"operatorId"\s*,|"definitionKey"\s*,/);
});

test('release observability never exposes a migration execution route', async () => {
  const advice = await text(advicePath);
  assert.match(advice, /\/migration-dry-run/);
  assert.doesNotMatch(
    advice,
    /execute-migration|migration-execution|force-migration|rollback-migration/i,
  );
});
