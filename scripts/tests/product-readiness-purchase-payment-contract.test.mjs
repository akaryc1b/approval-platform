import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  readScenario,
  validateScenario,
} from '../product-readiness/purchase-payment-scenario-contract.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const manifestPath = resolve(root, 'config/demo/purchase-payment-golden-path.json');
const validatorPath = resolve(
  root,
  'scripts/product-readiness/purchase-payment-scenario-contract.mjs',
);
const statusPath = resolve(root, 'docs/product-readiness/README.md');
const guidePath = resolve(root, 'docs/product-readiness/PURCHASE_PAYMENT_GOLDEN_PATH.md');
const packageJsonPath = resolve(root, 'package.json');
const hygieneAggregatePath = resolve(root, 'scripts/tests/m3-repository-hygiene.test.mjs');

function text(path) {
  assert.equal(existsSync(path), true, `missing ${path}`);
  return readFileSync(path, 'utf8');
}

test('deterministic purchase-payment scenario contract validates against the real API and template', () => {
  const execution = spawnSync(process.execPath, [validatorPath, '--json'], {
    cwd: root,
    encoding: 'utf8',
  });
  assert.equal(execution.status, 0, execution.stderr || execution.stdout);
  const report = JSON.parse(execution.stdout);
  assert.equal(report.claim, 'PURCHASE_PAYMENT_SCENARIO_CONTRACT_PASSED');
  assert.equal(report.seed, 'DETERMINISTIC_DEMO_SEED_NOT_APPLIED');
  assert.equal(report.execution, 'PURCHASE_APPROVAL_E2E_NOT_EXECUTED');
  assert.equal(report.sandbox, 'PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED');
  assert.equal(report.productionPayment, 'PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED');
  assert.equal(report.deterministicUserCount, 6);
  assert.equal(report.approvalStageCount, 3);
  assert.match(report.manifestSha256, /^[0-9a-f]{64}$/u);
});

test('manifest exercises the high-value manager, finance review and parallel countersign path', () => {
  const manifest = JSON.parse(text(manifestPath));
  assert.equal(manifest.request.amount, '12500.00');
  assert.deepEqual(
    manifest.expectedWorkflow.map((stage) => stage.taskDefinitionKey),
    ['managerApproval', 'financeReview', 'financeCountersign'],
  );
  assert.deepEqual(
    manifest.expectedWorkflow[2].actorIds,
    ['demo-finance-approver-a', 'demo-finance-approver-b'],
  );
  assert.equal(manifest.expectedWorkflow[2].mode, 'ALL');
  assert.equal(manifest.assigneeRules.maximumFinanceApprovers, 2);
});

test('contract fails closed when the scenario no longer exercises finance review', () => {
  const manifest = structuredClone(readScenario());
  manifest.request.amount = '9999.99';
  assert.throws(
    () => validateScenario(manifest),
    /must exercise the high-value finance-review branch/u,
  );
});

test('contract fails closed on duplicate identities and production sandbox claims', () => {
  const duplicateIdentity = structuredClone(readScenario());
  duplicateIdentity.directory.users[5].id = duplicateIdentity.directory.users[4].id;
  assert.throws(() => validateScenario(duplicateIdentity), /duplicate demo user id/u);

  const productionSandbox = structuredClone(readScenario());
  productionSandbox.sandbox.production = true;
  assert.throws(() => validateScenario(productionSandbox), /sandbox.production must remain false/u);
});

test('documents distinguish the read-only scenario check from the opt-in runtime seed', () => {
  const status = text(statusPath);
  const guide = text(guidePath);
  for (const source of [status, guide]) {
    assert.match(source, /PURCHASE_PAYMENT_SCENARIO_CONTRACT_PASSED/u);
    assert.match(source, /DETERMINISTIC_DEMO_SEED_IMPLEMENTED/u);
    assert.match(source, /BACKEND_LOCAL_START_VERIFIED/u);
    assert.match(source, /PURCHASE_APPROVAL_E2E_NOT_EXECUTED/u);
    assert.match(source, /PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED/u);
    assert.match(source, /PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED/u);
  }
  assert.match(guide, /DETERMINISTIC_DEMO_SEED_NOT_APPLIED/u);
  assert.doesNotMatch(guide, /^PURCHASE_APPROVAL_E2E_STATUS=PASSED$/mu);
  assert.doesNotMatch(guide, /^PRODUCTION_PAYMENT_INTEGRATION_STATUS=VERIFIED$/mu);
});

test('package entrypoint and permanent Hygiene aggregate load the scenario contract', () => {
  const packageJson = JSON.parse(text(packageJsonPath));
  assert.equal(
    packageJson.scripts?.['demo:scenario:check'],
    'node scripts/product-readiness/purchase-payment-scenario-contract.mjs',
  );
  const aggregate = text(hygieneAggregatePath);
  assert.match(
    aggregate,
    /import '\.\/product-readiness-purchase-payment-contract\.test\.mjs';/u,
  );
});
