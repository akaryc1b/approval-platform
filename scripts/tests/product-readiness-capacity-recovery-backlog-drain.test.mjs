import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import test from 'node:test';
import './product-readiness-capacity-recovery-event-allowlist.test.mjs';

const root = resolve(import.meta.dirname, '../..');
const text = path => readFileSync(resolve(root, path), 'utf8');
const source = name => text(`scripts/product-readiness/capacity-recovery/${name}.mjs`);
const launcher = text('scripts/product-readiness/capacity-recovery.mjs');
const matrix = source('profile-matrix');
const drain = source('backlog-drain');
const contract = source('backlog-drain-contract');
const evidence = source('backlog-drain-evidence');
const lifecycle = source('backlog-drain-lifecycle');
const sandbox = text('apps/server/src/main/java/io/github/akaryc1b/approval/demo/PurchasePaymentDemoPaymentSandbox.java');
const configuration = text('apps/server/src/main/java/io/github/akaryc1b/approval/demo/PurchasePaymentDemoPaymentSandboxConfiguration.java');
const local = text('apps/server/src/main/resources/application-local.yml');

function ordered(value, ...markers) {
  let previous = -1;
  for (const marker of markers) {
    const position = value.indexOf(marker, previous + 1);
    assert.ok(position > previous, `missing or out-of-order marker: ${marker}`);
    previous = position;
  }
}

function uuid(value) {
  return `00000000-0000-4000-8000-${value.toString(16).padStart(12, '0')}`;
}

function deliveredObservation(eventType, deliveredCount) {
  return {
    rows: Array.from({ length: 96 }, (_, index) => {
      const eventId = uuid(index + 1001);
      const aggregateId = uuid(index + 2001);
      const delivered = index < deliveredCount;
      return {
        id: uuid(index + 1), eventId, aggregateId, eventType,
        idempotencyKey: `${eventType}:${aggregateId}`,
        status: delivered ? 'DELIVERED' : 'PENDING', attempts: 1,
        lastError: delivered ? null : 'HTTP 503: payment sandbox unavailable',
        responseCode: delivered ? 200 : null,
        providerRequestId: delivered ? `local-payment-sandbox-${eventId}` : null,
        deliveredAt: delivered ? '2026-09-05T02:00:00Z' : null,
      };
    }),
    sandbox: {
      available: true, deliveryAttempts: 96 + deliveredCount,
      acceptedPaymentResults: deliveredCount,
      lastHttpStatus: deliveredCount > 0 ? 200 : 503, failure: null,
    },
  };
}

test('backlog module loads with existing runtime dependencies', async () => {
  const module = await import('../product-readiness/capacity-recovery/backlog-drain.mjs');
  assert.equal(typeof module.backlogDrainPlan, 'function');
  assert.equal(typeof module.executeBacklogDrain, 'function');
});

test('matrix drains its original instances before its finally-owned cleanup', () => {
  assert.match(launcher, /value\.backlogDrain = backlog/u);
  assert.doesNotMatch(launcher, /executeBacklogDrain/u);
  ordered(launcher, 'await executeProfileMatrix(contract)', 'await executeUpgradeRestoreRehearsal(contract)');
  assert.equal([...launcher.matchAll(/executeUpgradeRestoreRehearsal\(contract\)/gu)].length, 1);
  ordered(matrix, 'large = await runProfile(', 'await stopManaged(backend);',
    'await waitForPortAvailable(8080);', 'backlogDrain = await executeBacklogDrain(contract,',
    '...standard.summary.instances, ...large.summary.instances', 'finally {',
    'cleanupEvidence = await cleanup(');
  assert.match(matrix, /APPROVAL_GENERIC_DISPATCH_ENABLED: 'false'/u);
  assert.match(matrix, /purchaseOrderReference: instance\.purchaseOrderReference/u);
  assert.doesNotMatch(drain, /executeUpgradeRestoreRehearsal|executeBacklogWorkflow|resetDisposableData|seededAttachmentIds/u);
});

test('drain validates original untouched rows before starting the dispatcher', () => {
  ordered(drain, 'validateBacklogHandoff(handoff, queryOutboxRows(instanceIds), expectedRows)',
    "'matrix-backlog-handoff.json'", 'backend = startManagedNode(');
  assert.match(drain, /requireSameBacklogIdentity\(original, value\.rows\)/u);
  assert.match(drain, /reusedProfileMatrixBacklog: true/u);
  assert.match(drain, /NO_NEW_APPROVAL_COMMANDS_REUSES_PROFILE_MATRIX_WORK/u);
  assert.match(contract, /dispatchBatchSize = 96/u);
  for (const marker of ['APPROVAL_GENERIC_CONNECTOR_ENABLED', 'APPROVAL_GENERIC_DISPATCH_ENABLED',
    'APPROVAL_DEMO_PAYMENT_SANDBOX_ENABLED', 'APPROVAL_DEMO_PAYMENT_SANDBOX_EVENT_ALLOWLIST_FILE']) {
    assert.ok(contract.includes(marker), `missing ${marker}`);
  }
  const active = [matrix, drain, evidence, lifecycle].join('\n');
  assert.doesNotMatch(active, /\bACT_[A-Z0-9_]+\b/u);
  assert.doesNotMatch(active, /\b(?:insert into|update ap_|delete from)\b/iu);
  assert.doesNotMatch(active, /waitForTimeout|catch\s*\([^)]*\)\s*\{\s*\}/u);
});

test('recovery publishes exact authorization before control and checks the accepted ledger', () => {
  ordered(drain, 'const unavailable = await waitForState(', 'const allowlist = publishExactEventAllowlist(',
    "'payment-sandbox-allowlist.json'", 'const recoveryStartedAt = performance.now()',
    "writeFileSync(environment.APPROVAL_DEMO_PAYMENT_SANDBOX_CONTROL_FILE, 'recover\\n'");
  assert.match(drain, /validateDelivered\(value, expectedRows\)[\s\S]*?verifyExactAcceptedPayments\(value\.sandbox, allowlist\)/u);
  assert.match(drain, /const stable = readRecoveryState\(\);[\s\S]*?if \(!complete\(stable\)\)/u);
  assert.match(drain, /stableObservations: 5/u);
  ordered(sandbox, 'ValidatedEvent event = validateRequest(request);',
    'if (!currentAvailability())', 'eventAllowlist.contains(identity)', 'paymentResults.putIfAbsent(');
});

test('the matrix alone deletes its disposable volume on success or failure', () => {
  assert.match(drain, /finally \{[\s\S]*?cleanup\(backend, environment, runDirectory, Boolean\(backend\), 'PROFILE_MATRIX'\)/u);
  assert.match(lifecycle, /if \(volumeOwner === 'PROFILE_MATRIX'\)[\s\S]*?volume-cleanup-owned-by:profile-matrix-finally[\s\S]*?else if \(mutated\)/u);
  assert.match(lifecycle, /scope: volumeOwner === 'PROFILE_MATRIX' \? 'BACKEND_ONLY'/u);
  assert.match(matrix, /finally \{[\s\S]*?cleanupEvidence = await cleanup\(/u);
  assert.match(matrix, /resetDisposableData\(environment, 15 \* 60_000\)/u);
});

test('passed drain evidence requires handoff, whitelist, identity, samples and cleanup', () => {
  for (const name of ['source-identity.json', 'matrix-backlog-handoff.json',
    'backlog-drain-instances.json', 'backlog-drain-command-attempts.json',
    'payment-sandbox-allowlist.json', 'outbox-backlog-unavailable.json',
    'outbox-backlog-delivered.json', 'outbox-backlog-observations.json',
    'backlog-drain-cleanup.json', 'outbox-backlog-drain-summary.json']) {
    assert.ok(evidence.slice(evidence.indexOf("if (status === 'PASSED')")).includes(name), name);
  }
  ordered(evidence, "row.status !== 'DELIVERED'", "requireUnique(value.rows, 'providerRequestId'");
  assert.match(evidence, /idempotencyKey !== `\$\{eventType\(\)\}:\$\{row\.aggregateId\}`/u);
});

test('delivery polling tolerates partial delivery and accepts the complete transport state', async () => {
  const { validateDelivered } = await import('../product-readiness/capacity-recovery/backlog-drain-evidence.mjs');
  const { eventType } = await import('../product-readiness/purchase-payment-e2e/contract.mjs');
  assert.equal(validateDelivered(deliveredObservation(eventType(), 48), 96), false);
  assert.equal(validateDelivered(deliveredObservation(eventType(), 96), 96), true);
});

test('sandbox authorization has no prefixes and preserves local signed exact-value defaults', () => {
  assert.doesNotMatch([sandbox, configuration, local, contract, source('upgrade-restore-contract')].join('\n'),
    /businessKeyPrefix|purchaseOrderReferencePrefix|matchesExpected|validateVolumePrefixes|SANDBOX_BUSINESS_KEY_PREFIX|SANDBOX_PURCHASE_ORDER_REFERENCE_PREFIX/u);
  assert.match(sandbox, /eventAllowlistFile == null[\s\S]*?scenario\.request\(\)\.businessKey\(\)\.equals\(businessKey\)/u);
  assert.match(sandbox, /scenario\.request\(\)\.purchaseOrderReference\(\)\.equals\(purchaseOrderReference\)/u);
  assert.match(sandbox, /verifier\.verify\(/u);
  assert.match(sandbox, /scenario\.tenantId\(\)\.equals\(requireHeader\(headers, "X-Tenant-Id"\)\)/u);
  assert.match(sandbox, /PurchasePaymentDemoEventAllowlist\.load\(/u);
  assert.match(sandbox, /idempotent payment replay payload changed/u);
  assert.match(configuration, /@Profile\("local"\)/u);
  assert.match(configuration, /payment sandbox endpoint must use loopback HTTP/u);
  assert.match(local, /APPROVAL_DEMO_PAYMENT_SANDBOX_EVENT_ALLOWLIST_FILE/u);
});
