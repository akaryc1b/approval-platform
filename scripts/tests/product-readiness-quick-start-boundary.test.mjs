import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { runInNewContext } from 'node:vm';
import { dirname, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import { relevantChangeSet } from '../product-readiness/pc-h5-runtime/ci-scope.mjs';
import { validateBrowserEvidence } from '../product-readiness/quick-start/runtime.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');

function text(path) {
  const absolute = resolve(root, path);
  assert.equal(existsSync(absolute), true, `missing ${path}`);
  return readFileSync(absolute, 'utf8');
}

function runQuickStart(...args) {
  return spawnSync(
    process.execPath,
    [resolve(root, 'scripts/product-readiness/demo-quickstart.mjs'), ...args],
    {
      cwd: root,
      encoding: 'utf8',
      env: { ...process.env, GITHUB_ACTIONS: 'false' },
      shell: false,
    },
  );
}

const launcher = text('scripts/product-readiness/demo-quickstart.mjs');
const contract = text('scripts/product-readiness/quick-start/contract.mjs');
const runtime = text('scripts/product-readiness/quick-start/runtime.mjs');
const evidence = text('scripts/product-readiness/quick-start/evidence.mjs');
const ciScope = text('scripts/product-readiness/pc-h5-runtime/ci-scope.mjs');
const browserSpec = text(
  'apps/web/overlay/playground/__tests__/e2e/product-readiness-quick-start-ready.spec.ts',
);
const browserContract = text(
  'scripts/product-readiness/pc-h5-runtime/contract.mjs',
);
const quickStart = JSON.parse(text('config/demo/quick-start.json'));
const crossClient = JSON.parse(text('config/demo/cross-client-local-demo.json'));
const packageJson = JSON.parse(text('package.json'));
const aggregate = text('scripts/tests/m3-repository-hygiene.test.mjs');

test('Quick Start exposes a measured local-only plan and skips non-CI execution', () => {
  const planned = runQuickStart('plan', '--json');
  assert.equal(planned.status, 0, planned.stderr || planned.stdout);
  const plan = JSON.parse(planned.stdout);
  assert.equal(plan.entrypoint, 'pnpm demo:quickstart');
  assert.equal(plan.maximumReadySeconds, 600);
  assert.equal(plan.tenantId, 'demo-purchase-payment');
  assert.equal(plan.businessKey, 'DEMO-PP-0001');
  assert.match(plan.pcUrl, /demoOperator=demo-manager/u);
  assert.match(plan.h5Url, /demoOperator=demo-manager#\/pages\/task\/list/u);
  assert.equal(
    plan.stages.some(stage => stage.includes('reuse demo-backend.mjs')),
    true,
  );
  assert.equal(
    plan.stages.some(stage => stage.includes('reuse demo-client.mjs')),
    true,
  );
  assert.deepEqual(
    plan.claimsAfterTwoConsecutiveCleanRuns,
    quickStart.claimsAfterTwoConsecutiveCleanRuns,
  );
  assert.deepEqual(plan.nonClaims, quickStart.nonClaims);

  const ci = runQuickStart('ci');
  assert.equal(ci.status, 0, ci.stderr || ci.stdout);
  assert.match(ci.stdout, /PC_H5_RUNTIME_SMOKE_SKIPPED_NON_CI/u);
  assert.doesNotMatch(ci.stdout, /QUICK_START_10_MINUTES_PASSED/u);
});

test('governed Quick Start identity and deadline are explicit', () => {
  assert.equal(quickStart.maximumReadySeconds, 600);
  assert.equal(quickStart.clients.pc.actorId, 'demo-manager');
  assert.equal(quickStart.clients.h5.actorId, 'demo-manager');
  assert.equal(
    crossClient.clients.pc.allowedActors.includes(quickStart.clients.pc.actorId),
    true,
  );
  assert.equal(
    crossClient.clients.h5.allowedActors.includes(quickStart.clients.h5.actorId),
    true,
  );
  assert.equal(quickStart.clients.pc.port, crossClient.clients.pc.defaultPort);
  assert.equal(quickStart.clients.h5.port, crossClient.clients.h5.defaultPort);
  assert.match(contract, /maximumReadySeconds must be between 60 and 600/u);
  assert.match(contract, /Quick Start PC and H5 must show the same governed pending task/u);
});

test('orchestrator reuses existing backend, clients and generated workspaces', () => {
  for (const marker of [
    "'scripts/product-readiness/demo-preflight.mjs'",
    "'scripts/product-readiness/demo-backend.mjs'",
    "'scripts/product-readiness/demo-client.mjs'",
    "['web:install']",
    "['mobile:install']",
    'startManagedNode',
    'waitForMarker',
    'waitForHttp',
    'BACKEND_LOCAL_START_VERIFIED',
    'PURCHASE_PAYMENT_DEMO_SEED_APPLIED',
    'product-readiness-quick-start-ready.spec.ts',
  ]) {
    assert.equal(runtime.includes(marker), true, `runtime missing ${marker}`);
  }
  assert.match(
    runtime,
    /try \{\s*runNodeChecked\(\s*'Run read-only Quick Start preflight before disposable reset',[\s\S]*?'scripts\/product-readiness\/demo-preflight\.mjs'[\s\S]*?\);\s*runtimeMutationStarted = true;\s*resetDisposableData\(environment\);/u,
  );
  assert.match(runtime, /let runtimeMutationStarted = false;/u);
  assert.match(
    runtime,
    /if \(runtimeMutationStarted\) \{[\s\S]*?resetDisposableData\(environment\);[\s\S]*?\} else \{[\s\S]*?skipped-reset:preflight-failed-before-runtime-mutation/u,
  );
  assert.match(
    runtime,
    /cleanup\([\s\S]*?runDirectory,[\s\S]*?runtimeMutationStarted,[\s\S]*?\)/u,
  );
  assert.match(runtime, /remainingMilliseconds\(deadline/u);
  assert.match(runtime, /AbortSignal\.timeout/u);
  assert.match(runtime, /finally/u);
  assert.match(
    runtime,
    /for \(const port of \[5432, 5777, 6379, 8080, 9000\]\)/u,
  );
  assert.doesNotMatch(runtime, /\bACT_[A-Z0-9_]+\b/u);
  assert.doesNotMatch(runtime, /\b(?:insert into|update ap_|delete from)\b/iu);
  assert.doesNotMatch(runtime, /\/api\/approval\/tasks\/[^\s]*\/approve/u);
  assert.doesNotMatch(runtime, /setTimeout\([^,]+,\s*\d{4,}/u);
  assert.doesNotMatch(runtime, /catch\s*\([^)]*\)\s*\{\s*\}/u);
});

test('browser evidence proves the seeded request is visible without approving it', () => {
  assert.match(browserSpec, /ensurePcLogin/u);
  assert.match(browserSpec, /locator\('\.task-item'\)/u);
  assert.match(browserSpec, /locator\('\.task-card'\)/u);
  assert.match(browserSpec, /quick-start-pc\.png/u);
  assert.match(browserSpec, /quick-start-h5\.png/u);
  assert.match(browserSpec, /QUICK_START_BROWSER_READY_V1/u);
  assert.doesNotMatch(browserSpec, /clickPcApproval/u);
  assert.doesNotMatch(browserSpec, /clickH5Approval/u);
  assert.doesNotMatch(browserSpec, /request\.post\(/u);
  assert.doesNotMatch(browserSpec, /waitForTimeout/u);
});

test('evidence binds source, environment, timing, screenshots, cleanup and two runs', () => {
  for (const marker of [
    'QUICK_START_SOURCE_IDENTITY_V1',
    'environment.json',
    'backend-health.json',
    'startup-summary.json',
    'cleanup-evidence.json',
    'runtime-summary.json',
    'successfulRunIds',
    'claimsDeclared',
    'appendCiEvidenceEnvelope',
  ]) {
    assert.equal(runtime.includes(marker), true, `runtime evidence missing ${marker}`);
  }
  for (const marker of [
    'QUICK_START_CONSECUTIVE_CLEAN_RUNS_V1',
    'QUICK_START_CI_ARTIFACT_ENVELOPE_V1',
    'quick-start-pc.png',
    'quick-start-h5.png',
    'trace.zip',
    'sha256',
    'browserExecutable',
  ]) {
    assert.equal(evidence.includes(marker), true, `evidence support missing ${marker}`);
  }
  assert.equal(
    (launcher.match(/await executeWithLedgerReset\(\{ keepAlive: false \}\)/gu) || []).length,
    2,
  );
  assert.match(launcher, /QUICK_START_SECOND_CLEAN_RUN_STARTING/u);
  assert.match(launcher, /APPROVAL_DEMO_COMMAND_TIMEOUT_MS/u);
  assert.match(
    launcher,
    /resetLedger\(sourceIdentity\(\), launcherFailureId\(\)\)/u,
  );
});

test('Chrome discovery supports explicit, Linux, macOS and Windows paths', () => {
  assert.match(browserContract, /APPROVAL_DEMO_CHROME_PATH/u);
  assert.match(browserContract, /\/Applications\/Google Chrome\.app/u);
  assert.match(browserContract, /Microsoft\/Edge\/Application\/msedge\.exe/u);
  assert.match(browserContract, /\/usr\/bin\/chromium/u);
});

test('package and path-scoped CI expose the Quick Start without a second workflow', () => {
  assert.equal(
    packageJson.scripts['demo:quickstart'],
    'node scripts/product-readiness/demo-quickstart.mjs start',
  );
  assert.equal(
    packageJson.scripts['demo:quickstart:plan'],
    'node scripts/product-readiness/demo-quickstart.mjs plan --json',
  );
  assert.equal(
    packageJson.scripts['demo:quickstart:check'],
    'node --test scripts/tests/product-readiness-quick-start-boundary.test.mjs',
  );
  const clientBoundary = packageJson.scripts['web:test:client-boundary'];
  const quickStartIndex = clientBoundary.indexOf(
    'node scripts/product-readiness/demo-quickstart.mjs ci',
  );
  const pcH5Index = clientBoundary.indexOf(
    'node scripts/product-readiness/pc-h5-runtime-smoke.mjs ci',
  );
  const purchasePaymentIndex = clientBoundary.indexOf(
    'node scripts/product-readiness/purchase-payment-e2e.mjs ci',
  );
  assert.notEqual(quickStartIndex, -1);
  assert.notEqual(pcH5Index, -1);
  assert.notEqual(purchasePaymentIndex, -1);
  assert.equal(
    quickStartIndex < pcH5Index && pcH5Index < purchasePaymentIndex,
    true,
    'timed Quick Start must run before the heavier approval E2E can warm its dependencies',
  );
  const normalizedCiScope = ciScope.replaceAll(String.raw`\/`, '/');
  assert.match(normalizedCiScope, /scripts\/product-readiness\/quick-start\//u);
  assert.match(ciScope, /product-readiness-quick-start-ready/u);
  assert.match(
    aggregate,
    /import '\.\/product-readiness-quick-start-boundary\.test\.mjs';/u,
  );
});


test('first H5 capture rejects unresolved components and missing component styles', () => {
  const helper = text(
    'apps/web/overlay/playground/__tests__/e2e/product-readiness-h5-components.ts',
  );
  const inspect = runInNewContext(
    `${stripTypeScriptTypes(helper).replace('export function', 'function')}\ninspectH5TaskComponents`,
    { getComputedStyle: element => element.style },
  );
  function element(style = {}, children = {}) {
    return {
      style: {
        display: 'block', visibility: 'visible', opacity: '1', ...style,
      },
      getBoundingClientRect: () => ({ width: 120, height: 24 }),
      querySelector: selector => children[selector] ?? null,
    };
  }
  function fixture() {
    const content = element({ display: 'flex', alignItems: 'center' });
    const button = element({}, { '.wd-button__content': content });
    const searchControl = element();
    const field = element({ position: 'relative' }, {
      '.wd-search__cover .wd-icon, input': searchControl,
    });
    const search = element({ display: 'flex' }, { '.wd-search__field': field });
    const tag = element({ borderTopStyle: 'solid', borderTopWidth: '1px' }, {
      '.wd-tag__text': element(),
    });
    const page = element({}, { '.search-card .wd-search': search });
    page.querySelectorAll = selector => selector === 'uni-button.wd-button'
      ? [button, button, button, button] : [];
    const task = element({}, { '.wd-tag': tag });
    task.closest = () => page;
    return { content, field, page, search, searchControl, tag, task };
  }
  const expected = {
    buttonsRendered: true, searchRendered: true, taskTagRendered: true,
    stylesApplied: true, unresolvedTags: 0,
  };
  const result = task => JSON.parse(JSON.stringify(inspect(task)));
  assert.deepEqual(result(fixture().task), expected);

  const unresolved = fixture();
  unresolved.page.querySelectorAll = selector => selector === 'uni-button.wd-button'
    ? [] : [element(), element(), element()];
  unresolved.page.querySelector = () => null;
  unresolved.task.querySelector = () => null;
  assert.deepEqual(result(unresolved.task), {
    buttonsRendered: false, searchRendered: false, taskTagRendered: false,
    stylesApplied: false, unresolvedTags: 3,
  }, 'plain task text and literal wd-* nodes cannot qualify as ready');

  for (const [name, style] of [
    ['content', { display: 'block' }],
    ['search', { display: 'block' }],
    ['field', { position: 'static' }],
    ['tag', { borderTopStyle: 'none', borderTopWidth: '0px' }],
  ]) {
    const missingStyle = fixture();
    Object.assign(missingStyle[name].style, style);
    assert.equal(result(missingStyle.task).stylesApplied, false, `${name} style`);
  }
  const invisible = fixture();
  invisible.searchControl.getBoundingClientRect = () => ({ width: 0, height: 0 });
  assert.equal(result(invisible.task).searchRendered, false);
  const missingStructure = fixture();
  missingStructure.tag.querySelector = () => null;
  assert.equal(result(missingStructure.task).taskTagRendered, false);
  const unresolvedIcon = fixture();
  unresolvedIcon.page.querySelectorAll = selector => selector === 'uni-button.wd-button'
    ? [element(), element(), element(), element()] : [element()];
  assert.equal(result(unresolvedIcon.task).unresolvedTags, 1);
});

test('Quick Start asserts positive component evidence before its original H5 screenshot', () => {
  const inspection = browserSpec.indexOf('h5Task.evaluate(inspectH5TaskComponents');
  const assertion = browserSpec.indexOf('expect(h5Components).toEqual(');
  const screenshot = browserSpec.indexOf("path: resolve(evidenceDirectory, 'quick-start-h5.png')");
  const receipt = browserSpec.indexOf('components: h5Components');
  assert.ok(inspection > 0 && inspection < assertion && assertion < screenshot && screenshot < receipt);
  assert.equal((browserSpec.match(/await h5.goto\(/gu) ?? []).length, 1);
  assert.doesNotMatch(browserSpec, /\.reload\(|waitForTimeout|setTimeout/u);
});


test('component guard changes select the existing governed browser run', () => {
  for (const path of [
    'apps/web/overlay/playground/__tests__/e2e/product-readiness-h5-components.ts',
    'scripts/tests/mobile-cold-start-components.test.mjs',
    'scripts/upstream/unibest-compatibility.mjs',
  ]) assert.equal(relevantChangeSet([path]), true, path);
});

test('Quick Start receipts cannot certify missing or failed component evidence', () => {
  const contract = {
    scenario: { tenant: { id: 'fixture' }, request: { businessKey: 'fixture' } },
    clients: { pc: { actorId: 'manager' }, h5: { actorId: 'manager' } },
  };
  const identity = { commitSha: 'a'.repeat(40) };
  const value = {
    schemaVersion: 1, evidenceKind: 'QUICK_START_BROWSER_READY_V1', status: 'PASSED',
    commitSha: identity.commitSha, tenantId: 'fixture', businessKey: 'fixture',
    pc: { actorId: 'manager', businessKeyVisible: true },
    h5: { actorId: 'manager', businessKeyVisible: true, components: {
      buttonsRendered: true, searchRendered: true, taskTagRendered: true,
      stylesApplied: true, unresolvedTags: 0,
    } },
  };
  assert.equal(validateBrowserEvidence(value, contract, identity), value);
  for (const key of Object.keys(value.h5.components)) {
    const missing = structuredClone(value);
    delete missing.h5.components[key];
    assert.throws(() => validateBrowserEvidence(missing, contract, identity), /inconsistent/u);
    const failed = structuredClone(value);
    failed.h5.components[key] = key === 'unresolvedTags' ? 1 : false;
    assert.throws(() => validateBrowserEvidence(failed, contract, identity), /inconsistent/u);
  }
});
