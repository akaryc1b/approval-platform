import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import test from 'node:test';

import {
  percentile,
  runBoundedPool,
  summarizeSamples,
} from '../product-readiness/capacity-recovery/statistics.mjs';

const root = resolve(import.meta.dirname, '../..');

function text(path) {
  return readFileSync(resolve(root, path), 'utf8');
}

const manifest = JSON.parse(text('config/demo/capacity-recovery.json'));
const launcher = text('scripts/product-readiness/capacity-recovery.mjs');
const contract = text('scripts/product-readiness/capacity-recovery/contract.mjs');
const runtime = text('scripts/product-readiness/capacity-recovery/runtime.mjs');
const evidence = text('scripts/product-readiness/capacity-recovery/evidence.mjs');
const ciScope = text('scripts/product-readiness/capacity-recovery/ci-scope.mjs');
const sharedCiScope = text('scripts/product-readiness/pc-h5-runtime/ci-scope.mjs');
const packageJson = JSON.parse(text('package.json'));
const aggregate = text('scripts/tests/m3-repository-hygiene.test.mjs');
const readinessIndex = text('docs/product-readiness/README.md');
const operatingEnvelope = text(
  'docs/product-readiness/CAPACITY_RECOVERY_ENVELOPE.md',
);

test('capacity plan declares three profiles and one executable initial point', () => {
  assert.equal(manifest.schemaVersion, 1);
  assert.equal(manifest.databaseVendor, 'PostgreSQL 16');
  assert.equal(manifest.applicationInstances, 1);
  assert.deepEqual(
    manifest.profiles.map(profile => [profile.id, profile.status]),
    [
      ['small-demo', 'EXECUTABLE'],
      ['standard-deployment', 'PLANNED'],
      ['large-tenant', 'PLANNED'],
    ],
  );
  assert.equal(
    manifest.nonClaims.includes('RPO_RTO_NOT_VERIFIED'),
    true,
  );
  assert.equal(
    manifest.nonClaims.includes('MYSQL_8_4_NOT_VERIFIED'),
    true,
  );
});

test('plan command is read-only and machine-readable', () => {
  const result = spawnSync(
    process.execPath,
    [
      resolve(root, 'scripts/product-readiness/capacity-recovery.mjs'),
      'plan',
      '--json',
    ],
    {
      cwd: root,
      encoding: 'utf8',
      env: { ...process.env, GITHUB_ACTIONS: 'false' },
      shell: false,
    },
  );
  assert.equal(result.status, 0, result.stderr || result.stdout);
  const plan = JSON.parse(result.stdout);
  assert.equal(plan.entrypoint, 'pnpm demo:runtime:capacity-recovery');
  assert.equal(plan.databaseVendor, 'PostgreSQL 16');
  assert.equal(plan.executableProfile, 'small-demo');
  assert.equal(plan.profiles.length, 3);
  assert.equal(plan.evidenceRoot, '.runtime/capacity-recovery/<run-id>/');
});

test('runtime reuses public product boundaries and accepted recovery lifecycle', () => {
  for (const marker of [
    "'scripts/product-readiness/demo-backend.mjs', 'start'",
    '/api/approval/attachments',
    '/api/approval/instances/purchase-payment',
    '/api/approval/tasks/pending',
    '/api/approval/tasks/${task.taskId}/approve',
    'runBoundedPool',
    'capturePostgresSnapshot',
    'captureProcessSnapshot',
    "'scripts/product-readiness/purchase-payment-e2e.mjs', 'run'",
    'outbox-pending-evidence.json',
    'outbox-delivered-evidence.json',
    'pendingToDeliveredEvidenceSeconds',
    'validateProfileThresholds',
    'finally',
    'small-demo-cleanup.json',
  ]) {
    assert.equal(runtime.includes(marker), true, `runtime missing ${marker}`);
  }
  assert.match(runtime, /AbortSignal\.timeout/u);
  assert.match(runtime, /remainingMilliseconds/u);
  assert.match(runtime, /two clean exact-Head recovery runs/iu);
  assert.doesNotMatch(runtime, /\bACT_[A-Z0-9_]+\b/u);
  assert.doesNotMatch(runtime, /\b(?:insert into|update ap_|delete from)\b/iu);
  assert.doesNotMatch(runtime, /waitForTimeout/u);
  assert.doesNotMatch(runtime, /catch\s*\([^)]*\)\s*\{\s*\}/u);
});

test('statistics produce bounded interpolated latency and error summaries', () => {
  assert.equal(percentile([10, 20, 30, 40], 0.5), 25);
  assert.equal(percentile([10, 20, 30, 40], 0.95), 38.5);
  const summary = summarizeSamples([
    { ok: true, latencyMs: 10 },
    { ok: true, latencyMs: 20 },
    { ok: false, latencyMs: 30 },
  ], 1000);
  assert.equal(summary.requests, 3);
  assert.equal(summary.successful, 2);
  assert.equal(summary.failed, 1);
  assert.equal(summary.errorRate, 0.333);
  assert.equal(summary.throughputPerSecond, 2);
  assert.equal(summary.latencyMs.p50, 15);
});

test('bounded pool never exceeds configured concurrency', async () => {
  let active = 0;
  let maximum = 0;
  const result = await runBoundedPool(
    [1, 2, 3, 4, 5, 6],
    2,
    async (value) => {
      active += 1;
      maximum = Math.max(maximum, active);
      await new Promise(resolvePromise => setImmediate(resolvePromise));
      active -= 1;
      return value * 2;
    },
  );
  assert.equal(maximum, 2);
  assert.deepEqual(result, [2, 4, 6, 8, 10, 12]);
});

test('evidence is bounded, exact-Head bound and retained in the existing artifact', () => {
  for (const marker of [
    'CAPACITY_RECOVERY_CI_ARTIFACT_ENVELOPE_V1',
    'root-install.log',
    'maximumFileBytes',
    'maximumTotalBytes',
    'source-identity.json',
    'small-demo-profile.json',
    'recovery-summary.json',
    'runtime-summary.json',
  ]) {
    assert.equal(evidence.includes(marker), true, `evidence missing ${marker}`);
  }
  assert.match(contract, /sourceIdentity/u);
  assert.match(contract, /PostgreSQL 16/u);
  assert.match(contract, /STANDARD_DEPLOYMENT_CAPACITY_NOT_VERIFIED/u);
});

test('capacity CI is path-scoped and documentation-only changes stay cheap', () => {
  assert.match(launcher, /shouldRunInCi/u);
  assert.match(ciScope, /CAPACITY_RECOVERY_SCOPE/u);
  assert.match(ciScope, /capacity-recovery/u);
  assert.doesNotMatch(ciScope, /docs\\\//u);
  assert.match(sharedCiScope, /capacity-recovery/u);
});

test('package scripts and aggregate expose the initial capacity slice', () => {
  assert.equal(
    packageJson.scripts['demo:runtime:capacity-recovery'],
    'node scripts/product-readiness/capacity-recovery.mjs run',
  );
  assert.equal(
    packageJson.scripts['demo:runtime:capacity-recovery:plan'],
    'node scripts/product-readiness/capacity-recovery.mjs plan --json',
  );
  assert.equal(
    packageJson.scripts['demo:runtime:capacity-recovery:check'],
    'node --test scripts/tests/'
      + 'product-readiness-capacity-recovery-boundary.test.mjs',
  );
  assert.equal(
    packageJson.scripts['demo:runtime:capacity-recovery:ci'],
    'node scripts/product-readiness/capacity-recovery.mjs ci',
  );
  assert.match(
    packageJson.scripts['web:test:client-boundary'],
    /product-readiness-capacity-recovery-boundary/u,
  );
  assert.match(
    packageJson.scripts['web:test:client-boundary'],
    /capacity-recovery\.mjs ci/u,
  );
  assert.match(
    aggregate,
    /product-readiness-capacity-recovery-boundary\.test\.mjs/u,
  );
});

test('documentation publishes only the measured initial boundary', () => {
  assert.match(readinessIndex, /CAPACITY_RECOVERY_ENVELOPE\.md/u);
  assert.match(readinessIndex, /Small Demo/u);
  assert.match(operatingEnvelope, /PASSED_AT_CONFIGURED_POINT_ONLY/u);
  assert.match(operatingEnvelope, /STANDARD_DEPLOYMENT_CAPACITY_NOT_VERIFIED/u);
  assert.match(operatingEnvelope, /RPO_RTO_NOT_VERIFIED/u);
  assert.doesNotMatch(operatingEnvelope, /production supported/iu);
});
