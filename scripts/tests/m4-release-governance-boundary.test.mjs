import assert from 'node:assert/strict';
import { readdir, readFile } from 'node:fs/promises';
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
const protocolPath = path.join(
  root,
  'docs/M4_RELEASE_MIGRATION_EXECUTION_PROTOCOL_DRAFT.md',
);

async function text(file) {
  return readFile(file, 'utf8');
}

async function filesUnder(directory, extensions) {
  const result = [];
  async function visit(current) {
    for (const entry of await readdir(current, { withFileTypes: true })) {
      const next = path.join(current, entry.name);
      if (entry.isDirectory()) {
        await visit(next);
      } else if (extensions.has(path.extname(entry.name))) {
        result.push(next);
      }
    }
  }
  await visit(directory);
  return result;
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

test('future release migration protocol remains design-only unavailable and fail-closed', async () => {
  const protocol = await text(protocolPath);

  for (const status of [
    'UNAVAILABLE',
    'DISABLED',
    'FAIL CLOSED',
    'NOT EXPOSED TO BROWSER',
  ]) {
    assert.match(protocol, new RegExp(status, 'i'));
  }
  for (const phase of [
    'Assessment',
    'Immutable migration plan',
    'Approval and authorization',
    'Execution intent',
    'Per-instance migration attempt',
    'Verification',
    'Reconciliation',
    'Immutable completion evidence',
  ]) {
    assert.match(protocol, new RegExp(`#{3} .*${phase}`, 'i'));
  }
  for (const binding of [
    'tenantId',
    'definitionKey',
    'sourceReleaseVersion',
    'sourcePackageHash',
    'targetReleaseVersion',
    'targetPackageHash',
    'assessmentReportHash',
    'selectedInstanceIds',
    'expectedBindingEvidenceHashes',
    'operationReason',
    'requestedBy',
    'authorizationEvidenceHash',
    'expiresAt',
    'planHash',
  ]) {
    assert.ok(protocol.includes(`\`${binding}\``), `protocol omits immutable binding ${binding}`);
  }
  for (const staleCheck of [
    'assessment report exists and is not expired',
    'source release identity and source package hash are unchanged',
    'target release identity and target package hash are unchanged',
    'active task definition keys exactly match',
    'runtime-binding evidence hash exactly matches',
    'require a new detect-only assessment',
  ]) {
    assert.match(protocol, new RegExp(staleCheck, 'i'));
  }
  assert.match(protocol, /each instance has an independent intent\/attempt boundary/i);
  assert.match(protocol, /external engine calls occur after claim commit/i);
  assert.match(protocol, /official supported public runtime\/service API/i);
  assert.match(protocol, /Unknown outcomes must enter reconciliation/i);
  assert.match(protocol, /No public or internal execution port exists/i);
  assert.match(protocol, /No UI or API execution surface exists/i);

  const serverApi = path.join(root, 'apps/server/src/main/java/io/github/akaryc1b/approval/api');
  const clientRoots = [
    path.join(root, 'apps/web/overlay/apps/web-ele/src'),
    path.join(root, 'apps/mobile/overlay/src'),
  ];
  const productionFiles = [
    ...(await filesUnder(serverApi, new Set(['.java']))),
  ];
  for (const clientRoot of clientRoots) {
    productionFiles.push(...await filesUnder(clientRoot, new Set(['.ts', '.vue'])));
  }

  const mutationMapping = /@(?:Post|Put|Patch|Delete)Mapping\s*\(\s*"([^"]*migration[^"]*)"/gi;
  const clientApiPath = /[\`'"](\/api\/approval\/[^\`'"]*migration[^\`'"]*)[\`'"]/gi;
  for (const file of productionFiles) {
    const content = await text(file);
    for (const match of content.matchAll(mutationMapping)) {
      assert.match(
        match[1],
        /migration-dry-run$/,
        `${path.relative(root, file)} exposes a migration mutation route: ${match[1]}`,
      );
    }
    for (const match of content.matchAll(clientApiPath)) {
      assert.match(
        match[1],
        /migration-dry-run/,
        `${path.relative(root, file)} calls a migration execution API: ${match[1]}`,
      );
    }
  }
});
