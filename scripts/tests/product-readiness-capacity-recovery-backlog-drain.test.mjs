import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import test from 'node:test';

const root = resolve(import.meta.dirname, '../..');

function text(path) {
  return readFileSync(resolve(root, path), 'utf8');
}

function deterministicUuid(value) {
  return `00000000-0000-4000-8000-${value.toString(16).padStart(12, '0')}`;
}

function deliveredObservation(completedEventType, deliveredRows) {
  const rows = Array.from({ length: 96 }, (_, index) => {
    const eventId = deterministicUuid(index + 1_001);
    const aggregateId = deterministicUuid(index + 2_001);
    const delivered = index < deliveredRows;
    return {
      id: deterministicUuid(index + 1),
      eventId,
      eventType: completedEventType,
      aggregateId,
      status: delivered ? 'DELIVERED' : 'PENDING',
      attempts: 1,
      lastError: delivered
        ? null
        : 'HTTP 503: payment sandbox unavailable',
      responseCode: delivered ? 200 : null,
      providerRequestId: delivered
        ? `local-payment-sandbox-${eventId}`
        : null,
      requestId: `backlog-request-${index + 1}`,
      traceId: `backlog-trace-${index + 1}`,
      idempotencyKey: `${completedEventType}:${aggregateId}`,
      availableAt: '2026-09-03 12:44:08+00',
      deliveredAt: delivered ? '2026-09-03 12:44:18+00' : null,
    };
  });
  return {
    rows,
    sandbox: {
      available: true,
      deliveryAttempts: 96 + deliveredRows,
      acceptedPaymentResults: deliveredRows,
      lastHttpStatus: deliveredRows > 0 ? 200 : 503,
      failure: null,
    },
  };
}

const launcher = text('scripts/product-readiness/capacity-recovery.mjs');
const backlogDrain = text(
  'scripts/product-readiness/capacity-recovery/backlog-drain.mjs',
);
const backlogContract = text(
  'scripts/product-readiness/capacity-recovery/backlog-drain-contract.mjs',
);
const backlogEvidence = text(
  'scripts/product-readiness/capacity-recovery/backlog-drain-evidence.mjs',
);
const backlogHttp = text(
  'scripts/product-readiness/capacity-recovery/backlog-drain-http.mjs',
);
const backlogLifecycle = text(
  'scripts/product-readiness/capacity-recovery/backlog-drain-lifecycle.mjs',
);
const backlogSources = [
  backlogDrain,
  backlogContract,
  backlogEvidence,
  backlogHttp,
  backlogLifecycle,
].join('\n');
const sandbox = text(
  'apps/server/src/main/java/io/github/akaryc1b/approval/demo/'
    + 'PurchasePaymentDemoPaymentSandbox.java',
);
const sandboxConfiguration = text(
  'apps/server/src/main/java/io/github/akaryc1b/approval/demo/'
    + 'PurchasePaymentDemoPaymentSandboxConfiguration.java',
);
const localConfiguration = text(
  'apps/server/src/main/resources/application-local.yml',
);

test('backlog drain module loads with repository runtime dependencies', async () => {
  const module = await import(
    new URL(
      '../product-readiness/capacity-recovery/backlog-drain.mjs',
      import.meta.url,
    ),
  );
  assert.equal(typeof module.backlogDrainPlan, 'function');
  assert.equal(typeof module.executeBacklogDrain, 'function');
});

test('launcher executes configured-volume backlog drain after profile matrix', () => {
  assert.match(
    launcher,
    /backlogDrainPlan,[\s\S]*?executeBacklogDrain,[\s\S]*?from '\.\/capacity-recovery\/backlog-drain\.mjs'/u,
  );
  assert.match(launcher, /await executeBacklogDrain\(contract\)/u);
  assert.match(launcher, /value\.backlogDrain = backlog/u);
  assert.match(
    launcher,
    /OUTBOX_CONNECTOR_BACKLOG_DRAIN_VOLUME_NOT_VERIFIED/u,
  );
  assert.equal(
    launcher.indexOf('await executeProfileMatrix(contract)')
      < launcher.indexOf('await executeBacklogDrain(contract)'),
    true,
  );
  assert.equal(
    launcher.indexOf('retryEvidence.restore()')
      < launcher.indexOf('await executeBacklogDrain(contract)'),
    true,
  );
  assert.equal(
    [...launcher.matchAll(/executeUpgradeRestoreRehearsal\(contract\)/gu)].length,
    1,
    'upgrade/restore rehearsal must execute exactly once from the top-level launcher',
  );
  assert.doesNotMatch(backlogDrain, /executeUpgradeRestoreRehearsal/u);
});

test('backlog drain uses existing public purchase-payment and Connector paths', () => {
  for (const marker of [
    "'scripts/product-readiness/demo-backend.mjs', 'start'",
    '/api/approval/instances/purchase-payment',
    '/api/approval/tasks/${task.taskId}/approve',
    'APPROVAL_GENERIC_CONNECTOR_ENABLED',
    'APPROVAL_GENERIC_DISPATCH_ENABLED',
    'APPROVAL_DEMO_PAYMENT_SANDBOX_ENABLED',
    'payment-sandbox-recover.control',
    'config/demo/purchase-payment-demo-seed.json',
    'seededAttachmentIds',
    'HTTP 503: payment sandbox unavailable',
    "row.status !== 'PENDING'",
    "row.status !== 'DELIVERED'",
    'acceptedPaymentResults === expectedRows',
    'local-payment-sandbox-${row.eventId}',
    'OUTBOX_CONNECTOR_BACKLOG_DRAIN_LOCAL_CONFIGURED_VOLUME_PASSED',
    'backlog-drain-cleanup.json',
    'appendEvidenceEnvelope',
    'finally',
  ]) {
    assert.equal(backlogSources.includes(marker), true, `missing ${marker}`);
  }
  assert.match(backlogContract, /dispatchBatchSize = 96/u);
  assert.match(backlogHttp, /AbortSignal\.timeout/u);
  assert.match(backlogHttp, /runBoundedPool/u);
  assert.doesNotMatch(backlogSources, /\bACT_[A-Z0-9_]+\b/u);
  assert.doesNotMatch(
    backlogSources,
    /\b(?:insert into|update ap_|delete from)\b/iu,
  );
  assert.doesNotMatch(backlogSources, /waitForTimeout/u);
  assert.doesNotMatch(
    backlogSources,
    /catch\s*\([^)]*\)\s*\{\s*\}/u,
  );
});

test('backlog evidence verifies identities, 503, delivery and cleanup', () => {
  for (const marker of [
    'CAPACITY_BACKLOG_DRAIN_CI_ARTIFACT_ENVELOPE_V1',
    'source-identity.json',
    'backlog-drain-contract.json',
    'backlog-drain-instances.json',
    'backlog-drain-command-attempts.json',
    'outbox-backlog-unavailable.json',
    'outbox-backlog-delivered.json',
    'outbox-backlog-drain-summary.json',
    'requireUnique',
    'idempotencyKey !== `${eventType()}:${row.aggregateId}`',
    'providerRequestId !== `local-payment-sandbox-${row.eventId}`',
    'stableObservations: 5',
    'PRODUCTION_OUTBOX_DRAIN_RATE_NOT_VERIFIED',
    'PRODUCTION_RTO_NOT_VERIFIED',
  ]) {
    assert.equal(backlogSources.includes(marker), true, `missing ${marker}`);
  }
  assert.equal(
    backlogEvidence.indexOf("row.status !== 'DELIVERED'")
      < backlogEvidence.indexOf(
        "requireUnique(value.rows, 'providerRequestId'",
      ),
    true,
    'provider request uniqueness must be checked only after every row is delivered',
  );
});

test('delivered polling tolerates partial delivery and accepts the complete state', async () => {
  const [{ validateDelivered }, { eventType }] = await Promise.all([
    import(new URL(
      '../product-readiness/capacity-recovery/backlog-drain-evidence.mjs',
      import.meta.url,
    )),
    import(new URL(
      '../product-readiness/purchase-payment-e2e/contract.mjs',
      import.meta.url,
    )),
  ]);
  const completedEventType = eventType();
  assert.equal(
    validateDelivered(deliveredObservation(completedEventType, 48), 96),
    false,
  );
  assert.equal(
    validateDelivered(deliveredObservation(completedEventType, 96), 96),
    true,
  );
});

test('local sandbox volume mode remains explicit and bounded', () => {
  for (const marker of [
    'businessKeyPrefix',
    'purchaseOrderReferencePrefix',
    'matchesExpected',
    'sandbox volume prefixes must be configured together',
  ]) {
    assert.equal(sandbox.includes(marker), true, `sandbox missing ${marker}`);
  }
  for (const marker of [
    'validateVolumePrefixes',
    'getBusinessKeyPrefix',
    'getPurchaseOrderReferencePrefix',
    'must extend the governed business key',
    'volume prefixes exceed bounded lengths',
  ]) {
    assert.equal(
      sandboxConfiguration.includes(marker),
      true,
      `sandbox configuration missing ${marker}`,
    );
  }
  assert.match(
    localConfiguration,
    /APPROVAL_DEMO_PAYMENT_SANDBOX_BUSINESS_KEY_PREFIX/u,
  );
  assert.match(
    localConfiguration,
    /APPROVAL_DEMO_PAYMENT_SANDBOX_PURCHASE_ORDER_REFERENCE_PREFIX/u,
  );
});
