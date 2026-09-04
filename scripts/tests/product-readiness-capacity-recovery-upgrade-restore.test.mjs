import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import test from 'node:test';

import {
  createRecorder,
  readConsistency,
} from '../product-readiness/capacity-recovery/upgrade-restore-http.mjs';

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
const processes = text(
  'scripts/product-readiness/pc-h5-runtime/processes.mjs',
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

test('exact-main backend commands execute from the detached base worktree', () => {
  assert.match(
    processes,
    /export function runNodeChecked\([\s\S]*?workingDirectory = repositoryRoot,[\s\S]*?cwd: workingDirectory/u,
  );
  assert.match(
    processes,
    /export function startManagedNode\([\s\S]*?workingDirectory = repositoryRoot,[\s\S]*?cwd: workingDirectory/u,
  );
  assert.match(
    runtime,
    /resetDisposableData\([\s\S]*?baseEnvironment\(\),[\s\S]*?worktree,[\s\S]*?\);/u,
  );
  assert.match(
    runtime,
    /baseBackend = startManagedNode\([\s\S]*?baseEnvironment\(\),\s*worktree,\s*\);/u,
  );
});

test('consistency reader follows public InstanceDetails and ApprovalTimeline shapes', {
  concurrency: false,
}, async () => {
  const originalFetch = globalThis.fetch;
  const instanceId = '11111111-1111-4111-8111-111111111111';
  const timestamp = '2026-09-04T01:00:00Z';
  const task = (suffix, key, assignee, status, completedAt = null) => ({
    taskId: `22222222-2222-4222-8222-2222222222${suffix}`,
    instanceId,
    engineTaskId: `engine-task-${suffix}`,
    taskDefinitionKey: key,
    name: key,
    assigneeId: assignee,
    status,
    version: 1,
    createdAt: timestamp,
    updatedAt: timestamp,
    completedAt,
  });
  const details = {
    instance: {
      instanceId,
      tenantId: 'demo-tenant',
      businessKey: 'DEMO-PP-UPGRADE-RESTORE-shape',
      engineInstanceId: 'engine-instance-1',
      definitionKey: 'purchase-payment',
      definitionVersion: 1,
      formKey: 'purchase-payment',
      formVersion: 1,
      compilerVersion: '1',
      contentHash: 'a'.repeat(64),
      releaseVersion: 1,
      releasePackageHash: 'b'.repeat(64),
      formPackageVersion: 1,
      formPackageHash: 'c'.repeat(64),
      uiSchemaVersion: 1,
      uiSchemaHash: 'd'.repeat(64),
      engineDefinitionId: 'purchase-payment:1:engine',
      initiatorId: 'demo-employee',
      amount: 1280.5,
      supplier: 'Demo Supplier',
      purchaseOrderReference: 'PO-UPGRADE-RESTORE-shape',
      attachmentIds: ['33333333-3333-4333-8333-333333333333'],
      assigneeSnapshot: {
        managerAssignee: 'demo-manager',
        financeReviewer: 'demo-finance-reviewer',
        financeApprovers: ['demo-finance-a', 'demo-finance-b'],
        attributes: {},
        identities: {},
      },
      requestHash: 'e'.repeat(64),
      status: 'RUNNING',
      version: 3,
      createdAt: timestamp,
      updatedAt: timestamp,
    },
    tasks: [
      task('21', 'managerApproval', 'demo-manager', 'COMPLETED', timestamp),
      task('22', 'financeReview', 'demo-finance-reviewer', 'COMPLETED', timestamp),
      task('23', 'financeCountersign', 'demo-finance-a', 'PENDING'),
      task('24', 'financeCountersign', 'demo-finance-b', 'PENDING'),
    ],
  };
  const timeline = {
    instanceId,
    items: [1, 2, 3].map(index => ({
      eventId: `44444444-4444-4444-8444-44444444444${index}`,
      action: index === 1 ? 'INSTANCE_STARTED' : 'TASK_APPROVED',
      schemaName: 'approval.audit.event',
      schemaVersion: 1,
      summary: `event-${index}`,
      operatorId: 'demo-employee',
      aggregateType: 'APPROVAL_INSTANCE',
      aggregateId: instanceId,
      requestId: `request-${index}`,
      traceId: `trace-${index}`,
      occurredAt: `2026-09-04T01:00:0${index}Z`,
      attributes: { sequence: String(index) },
    })),
  };
  const responses = [
    new Response(JSON.stringify({ data: details }), { status: 200 }),
    new Response(JSON.stringify({ data: timeline }), { status: 200 }),
  ];
  globalThis.fetch = async () => responses.shift();
  try {
    const shapeContract = {
      scenario: {
        tenant: { id: 'demo-tenant' },
        assigneeRules: {
          initiatorUserId: { value: 'demo-employee' },
        },
      },
    };
    const value = await readConsistency(
      createRecorder(shapeContract, 'shape'),
      shapeContract,
      instanceId,
    );
    assert.equal(value.instance.createdAt, timestamp);
    assert.equal(value.instance.updatedAt, timestamp);
    assert.equal(value.tasks.length, 4);
    assert.equal(value.activeTasks.length, 2);
    assert.deepEqual(
      value.activeTasks.map(item => item.taskDefinitionKey),
      ['financeCountersign', 'financeCountersign'],
    );
    assert.equal(value.timeline[0].action, 'INSTANCE_STARTED');
    assert.equal(value.timeline[0].operatorId, 'demo-employee');
    assert.deepEqual(value.timeline[0].attributes, { sequence: '1' });
  } finally {
    globalThis.fetch = originalFetch;
  }
  assert.match(http, /details\.tasks/u);
  assert.match(http, /instance\.createdAt/u);
  assert.match(http, /event\.action/u);
  assert.doesNotMatch(http, /details\.activeTasks/u);
  assert.doesNotMatch(http, /event\.eventType/u);
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
