import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import test from 'node:test';

const root = resolve(import.meta.dirname, '../..');
const text = path => readFileSync(resolve(root, path), 'utf8');

const launcher = text('scripts/product-readiness/capacity-recovery.mjs');
const contract = text(
  'scripts/product-readiness/capacity-recovery/upgrade-restore-contract.mjs',
);
const runtime = text(
  'scripts/product-readiness/capacity-recovery/upgrade-restore.mjs',
);
const http = text(
  'scripts/product-readiness/capacity-recovery/upgrade-restore-http.mjs',
);
const sources = `${contract}\n${runtime}\n${http}`;

test('upgrade restore module loads and publishes one bounded local plan', async () => {
  const module = await import(
    new URL(
      '../product-readiness/capacity-recovery/upgrade-restore.mjs',
      import.meta.url,
    ),
  );
  assert.equal(typeof module.executeUpgradeRestoreRehearsal, 'function');
  const plan = module.upgradeRestorePlan();
  assert.equal(
    plan.claim,
    'LOCAL_IN_FLIGHT_POSTGRES_UPGRADE_RESTORE_REHEARSAL_PASSED',
  );
  assert.match(plan.rpoBoundary, /LOCAL_QUIESCED/u);
  assert.match(plan.rtoBoundary, /LOCAL_SINGLE_NODE/u);
});

test('launcher executes upgrade restore after configured-volume drain', () => {
  assert.match(
    launcher,
    /executeUpgradeRestoreRehearsal,[\s\S]*?upgradeRestorePlan/u,
  );
  assert.match(launcher, /await executeUpgradeRestoreRehearsal\(contract\)/u);
  assert.equal(
    launcher.indexOf('await executeBacklogDrain(contract)')
      < launcher.indexOf('await executeUpgradeRestoreRehearsal(contract)'),
    true,
  );
  assert.match(launcher, /value\.upgradeRestore = upgradeRestore/u);
});

test('rehearsal binds exact base and candidate refs and uses real PostgreSQL tools', () => {
  for (const marker of [
    'GITHUB_PULL_REQUEST_EVENT',
    "['merge-base', 'HEAD', 'origin/main']",
    "'worktree', 'add', '--detach'",
    "'pg_dump'",
    "'--format=custom'",
    "'pg_restore'",
    "'--exit-on-error'",
    'baseSha',
    'baseTreeSha',
    'candidateSha',
    'candidateTreeSha',
    'candidate source identity differs from exact PR Head',
  ]) {
    assert.equal(sources.includes(marker), true, `missing ${marker}`);
  }
});

test('rehearsal verifies exact in-flight business consistency and continuation', () => {
  for (const marker of [
    '/api/approval/instances/purchase-payment',
    '/api/approval/instances/${instanceId}',
    '/api/approval/instances/${instanceId}/timeline',
    '/api/approval/tasks/${task.taskId}/approve',
    'requireExactRestoredConsistency',
    'lostCommittedBusinessRecords: 0',
    'outageToFirstSuccessfulBusinessReadMs',
    'APPROVAL_INSTANCE_COMPLETED',
    'HTTP 503: payment sandbox unavailable',
    "status === 'PENDING'",
    "status === 'DELIVERED'",
    'acceptedPaymentResults === 1',
    'duplicateAcceptedPaymentResults: 0',
  ]) {
    assert.equal(sources.includes(marker), true, `missing ${marker}`);
  }
  assert.doesNotMatch(
    sources,
    /\b(?:insert into|update ap_|delete from)\b/iu,
  );
});

test('evidence is exact-Head, bounded, fail-closed and cleaned', () => {
  for (const marker of [
    'CAPACITY_UPGRADE_RESTORE_CI_ARTIFACT_ENVELOPE_V1',
    'source-identity.json',
    'upgrade-restore-contract.json',
    'pre-backup-consistency.json',
    'backup-manifest.json',
    'post-restore-consistency.json',
    'continuation-evidence.json',
    'outbox-pending-evidence.json',
    'outbox-delivered-evidence.json',
    'upgrade-restore-cleanup.json',
    'upgrade-restore-summary.json',
    'deleted:temporary-postgresql-backup',
    'removed:exact-main-baseline-worktree',
    'finally',
  ]) {
    assert.equal(runtime.includes(marker), true, `missing ${marker}`);
  }
  assert.doesNotMatch(runtime, /catch\s*\([^)]*\)\s*\{\s*\}/u);
});

test('published numbers remain explicitly local and non-production', () => {
  for (const marker of [
    'LOCAL_QUIESCED_POSTGRESQL_16_REHEARSAL_NOT_PRODUCTION_RPO_RTO',
    'ZERO_DOWNTIME_UPGRADE_NOT_VERIFIED',
    'ROLLBACK_REHEARSAL_NOT_VERIFIED',
    'PRODUCTION_RPO_NOT_VERIFIED',
    'PRODUCTION_RTO_NOT_VERIFIED',
    'MULTI_NODE_RECOVERY_NOT_VERIFIED',
    'PRODUCTION_BACKUP_RETENTION_NOT_VERIFIED',
  ]) {
    assert.equal(sources.includes(marker), true, `missing ${marker}`);
  }
});
