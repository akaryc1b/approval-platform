import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const verifierPath = resolve(root, 'scripts/product-readiness/verify-purchase-payment-demo.mjs');
const manifestPath = resolve(root, 'examples/purchase-payment/demo-manifest.json');
const guidePath = resolve(root, 'docs/product-readiness/PURCHASE_PAYMENT_DEMO.md');
const statusPath = resolve(root, 'docs/product-readiness/README.md');
const hygienePath = resolve(root, 'scripts/tests/m3-repository-hygiene.test.mjs');

function text(path) {
  assert.equal(existsSync(path), true, `missing ${path}`);
  return readFileSync(path, 'utf8');
}

test('deterministic purchase-payment repository contract passes without runtime claims', () => {
  const execution = spawnSync(process.execPath, [verifierPath, '--json'], {
    cwd: root,
    encoding: 'utf8',
  });
  assert.equal(execution.status, 0, execution.stderr || execution.stdout);
  const report = JSON.parse(execution.stdout);
  assert.equal(report.claim, 'PURCHASE_PAYMENT_DEMO_CONTRACT_PASSED');
  assert.equal(report.scenarioId, 'purchase-payment-high-value-demo');
  assert.equal(report.tenantId, 'demo-purchase-tenant');
  assert.equal(report.businessKey, 'PO-2026-0001');
  assert.equal(report.identityCount, 6);
  assert.equal(report.runtimeAcceptance, 'PURCHASE_APPROVAL_E2E_NOT_EXECUTED');
  assert.equal(
    report.paymentSandboxAcceptance,
    'PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED',
  );
  assert.equal(report.crossClientAcceptance, 'CROSS_CLIENT_RUNTIME_NOT_EXECUTED');
});

test('demo manifest contains deterministic non-production identities and no authority bypass', () => {
  const manifest = JSON.parse(text(manifestPath));
  assert.equal(manifest.identities.length, 6);
  assert.equal(manifest.safety.productionCredentials, false);
  assert.equal(manifest.safety.customerData, false);
  assert.equal(manifest.safety.authorizationBypass, false);
  assert.equal(manifest.safety.productionConnectorEnabled, false);
  assert.equal(manifest.safety.productionPaymentClaim, false);
  const serialized = JSON.stringify(manifest);
  assert.doesNotMatch(serialized, /password|privateKey|clientSecret|accessToken/iu);
});

test('guide and living status preserve the gap between a contract and product acceptance', () => {
  const guide = text(guidePath);
  const status = text(statusPath);
  assert.match(guide, /PURCHASE_PAYMENT_DEMO_STATUS=CONTRACT_PROVIDED_NOT_RUNTIME_SEEDED/u);
  assert.match(guide, /PURCHASE_APPROVAL_E2E_STATUS=NOT_YET_EXECUTED/u);
  assert.match(status, /CONTRACT_PROVIDED_NOT_RUNTIME_SEEDED/u);
  assert.match(status, /CONTRACT_PROVIDED_NOT_RUNTIME_EXECUTED/u);
  assert.doesNotMatch(guide, /^PURCHASE_APPROVAL_E2E_PASSED$/mu);
  assert.doesNotMatch(guide, /^PURCHASE_TO_PAYMENT_SANDBOX_E2E_PASSED$/mu);
  assert.doesNotMatch(guide, /^PRODUCTION_PAYMENT_INTEGRATION_VERIFIED$/mu);
});

test('permanent repository hygiene loads the purchase-payment demo boundary', () => {
  const hygiene = text(hygienePath);
  assert.match(
    hygiene,
    /import '\.\/product-readiness-purchase-payment-demo-boundary\.test\.mjs';/u,
  );
});
