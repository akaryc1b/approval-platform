import { relevantChangeSet } from '../product-readiness/pc-h5-runtime/ci-scope.mjs';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { runInNewContext } from 'node:vm';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');

function text(path) {
  const absolute = resolve(root, path);
  assert.equal(existsSync(absolute), true, `missing ${path}`);
  return readFileSync(absolute, 'utf8');
}

function runSmoke(...args) {
  return spawnSync(
    process.execPath,
    [resolve(root, 'scripts/product-readiness/pc-h5-runtime-smoke.mjs'), ...args],
    {
      cwd: root,
      encoding: 'utf8',
      env: { ...process.env, GITHUB_ACTIONS: 'false' },
      shell: false,
    },
  );
}

const smoke = text('scripts/product-readiness/pc-h5-runtime-smoke.mjs');
const runtimeContract = text(
  'scripts/product-readiness/pc-h5-runtime/contract.mjs',
);
const evidenceSupport = text(
  'scripts/product-readiness/pc-h5-runtime/evidence.mjs',
);
const ciScope = text(
  'scripts/product-readiness/pc-h5-runtime/ci-scope.mjs',
);
const processSupport = [
  text('scripts/product-readiness/pc-h5-runtime/processes.mjs'),
  ciScope,
].join('\n');
const playwrightConfig = text(
  'apps/web/overlay/playground/product-readiness.playwright.config.ts',
);
const playwrightSpec = text(
  'apps/web/overlay/playground/__tests__/e2e/product-readiness-pc-h5-runtime.spec.ts',
);
const playwrightApi = text(
  'apps/web/overlay/playground/__tests__/e2e/product-readiness-pc-h5-runtime-api.ts',
);
const playwrightUi = text(
  'apps/web/overlay/playground/__tests__/e2e/product-readiness-pc-h5-runtime-ui.ts',
);
const playwrightDiagnostics = text(
  'apps/web/overlay/playground/__tests__/e2e/product-readiness-pc-h5-runtime-diagnostics.ts',
);
const packageJson = JSON.parse(text('package.json'));
const guide = text('docs/product-readiness/PC_H5_RUNTIME_SMOKE.md');
const goldenPath = JSON.parse(
  text('config/demo/purchase-payment-golden-path.json'),
);
const aggregate = text('scripts/tests/m3-repository-hygiene.test.mjs');

test('runtime smoke exposes a read-only four-action payment-handoff plan and skips non-CI', () => {
  const planned = runSmoke('plan', '--json');
  assert.equal(planned.status, 0, planned.stderr || planned.stdout);
  const plan = JSON.parse(planned.stdout);
  assert.equal(plan.claim, 'PC_H5_APPROVAL_HANDOFF_PASSED');
  assert.equal(plan.evidenceKind, 'PC_H5_BROWSER_APPROVAL_HANDOFF_V1');
  assert.equal(
    plan.stages.some(stage =>
      stage.includes('two distinct financeCountersign tasks')),
    true,
  );
  assert.equal(
    plan.stages.some(stage =>
      stage.includes('remains RUNNING at paymentConfirmation')),
    true,
  );
  assert.equal(
    plan.stages.some(stage =>
      stage.includes('governed WeChat actor')),
    true,
  );
  assert.deepEqual(plan.nonClaims, [
    'PURCHASE_APPROVAL_E2E_NOT_EXECUTED',
    'WECHAT_MINI_PROGRAM_RUNTIME_NOT_EXECUTED',
    'PC_H5_WECHAT_RUNTIME_NOT_EXECUTED',
    'BROWSER_COMPATIBILITY_NOT_VERIFIED',
    'ACCESSIBILITY_NOT_VERIFIED',
    'PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED',
    'PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED',
    'QUICK_START_10_MINUTES_NOT_EXECUTED',
  ]);

  const ci = runSmoke('ci');
  assert.equal(ci.status, 0, ci.stderr || ci.stdout);
  assert.match(ci.stdout, /PC_H5_RUNTIME_SMOKE_SKIPPED_NON_CI/u);
});

test('orchestrator uses fixed executables, local services and path-gated CI', () => {
  assert.match(processSupport, /spawnSync\(pnpmExecutable\(\), args/u);
  assert.match(processSupport, /spawn\(process\.execPath, args/u);
  assert.match(processSupport, /spawnSync\(gitExecutable\(\), args/u);
  assert.match(processSupport, /shell: false/gu);
  assert.match(processSupport, /GITHUB_EVENT_PATH/u);
  assert.match(processSupport, /PC_H5_RUNTIME_SCOPE=/u);
  assert.match(processSupport, /product-readiness-pc-h5-runtime.*diagnostics/u);
  assert.match(smoke, /http:\/\/127\.0\.0\.1:8080/u);
  assert.match(runtimeContract, /JAVA_HOME_21_X64/u);
  assert.match(runtimeContract, /PATH: `\$\{javaBin\}\$\{delimiter\}/u);
  assert.equal(ciScope.includes('apps\\/mobile\\/overlay\\/'), true);
  assert.equal(ciScope.includes('apps\\/mobile\\/upstream\\.json'), true);
  assert.equal(ciScope.includes('bootstrap-unibest'), true);
  assert.doesNotMatch(processSupport, /spawn(?:Sync)?\(command/u);
  assert.doesNotMatch(processSupport, /shell:\s*true/u);
  assert.doesNotMatch(processSupport, /\bexec\s*\(/u);
  assert.doesNotMatch(
    `${smoke}\n${processSupport}`,
    /X-Approval-Trusted-Permissions/u,
  );
  assert.doesNotMatch(
    `${smoke}\n${processSupport}`,
    /X-Approval-Worker-Id/u,
  );
});

test('runtime evidence retains and validates the actual checkout identity', () => {
  for (const marker of [
    'workflowSourceIdentity',
    "['rev-parse', '--verify', revision]",
    'checkedOutSha',
    'checkedOutTreeSha',
    'exactHeadSha',
    'exactHeadTreeSha',
    'sourceTreeMatchesExactHead',
    'source-identity.json',
    'PC_H5_RUNTIME_SOURCE_IDENTITY_V1',
  ]) {
    assert.equal(smoke.includes(marker), true, `source identity missing ${marker}`);
  }
  assert.match(smoke, /checked-out source tree does not match/u);
  assert.match(smoke, /verifyRetainedRuntimeEvidence\(evidence, exactHeadSha\)/u);
});

test('browser test performs one PC and three H5 approvals through visible controls', () => {
  assert.equal((playwrightSpec.match(/await clickPcApproval\(/gu) || []).length, 1);
  assert.equal((playwrightSpec.match(/await clickH5Approval\(/gu) || []).length, 3);
  assert.match(playwrightUi, /getByRole\('button', \{ name: '同意'/u);
  assert.match(playwrightUi, /locator\('\.action-bar'\)/u);
  assert.match(playwrightUi, /stageLabel: '财务会签' \| '财务审核'/u);
  assert.match(playwrightUi, /uni-modal__btn_primary/u);
  assert.match(playwrightSpec, /PC_H5_APPROVAL_HANDOFF_PASSED/u);
  assert.match(playwrightSpec, /financeCountersign/u);
  assert.match(playwrightSpec, /paymentHandoff/u);
  assert.match(playwrightSpec, /waitForPendingForActor/u);
  assert.doesNotMatch(playwrightSpec, /waitForCompletedInstance/u);
  assert.match(playwrightSpec, /instanceOrigin: 'DETERMINISTIC_BACKEND_SEED'/u);
  assert.match(playwrightApi, /taskActionResult/u);
  assert.doesNotMatch(
    `${playwrightSpec}\n${playwrightApi}`,
    /request\.post\(/u,
  );
  assert.doesNotMatch(
    `${playwrightSpec}\n${playwrightUi}`,
    /X-Approval-Trusted-Permissions/u,
  );
  assert.doesNotMatch(
    playwrightSpec,
    /PRODUCTION_PAYMENT_INTEGRATION_VERIFIED/u,
  );
});

test('pending waits precede every navigation and bind the exact runtime identity', () => {
  assert.match(
    playwrightSpec,
    /Promise\.all\(\[\s*pendingResponse\(pc,[\s\S]*pc\.goto\(pcUrl/u,
  );
  for (const page of ['h5Reviewer', 'h5CountersignA', 'h5CountersignB']) {
    assert.match(
      playwrightSpec,
      new RegExp(
        `Promise\\.all\\(\\[\\s*pendingResponse\\(${page},[\\s\\S]*${page}\\.goto\\(`,
        'u',
      ),
    );
  }
  for (const marker of [
    "request.method() !== 'GET'",
    "candidate.status() !== 200",
    "headers['x-tenant-id'] !== tenantId",
    "headers['x-operator-id'] !== expectation.actorId",
    'task.businessKey === businessKey',
    'task.taskDefinitionKey === expectation.taskDefinitionKey',
    'task.instanceId === expectation.processInstanceId',
    "new URL(url).pathname === expectedPath",
  ]) {
    assert.equal(playwrightApi.includes(marker), true, `API wait missing ${marker}`);
  }
  assert.match(playwrightApi, /waitForPendingForActor/u);
  assert.match(playwrightApi, /waitForPendingTaskToDisappear/u);
  assert.match(playwrightApi, /waitForStartedInstance/u);
  assert.match(playwrightApi, /setTimeout\(resolvePromise, milliseconds\)/u);
  assert.match(
    playwrightApi,
    /demoHeaders\(authoritativeActors\.initiator, 'timeline'\)/u,
  );
  assert.doesNotMatch(playwrightSpec, /waitForTimeout/u);
  assert.doesNotMatch(playwrightUi, /waitForTimeout/u);
});

test('H5 actor selection remains stable before hash navigation', () => {
  assert.match(playwrightApi, /url\.searchParams\.set\('demoOperator', actorId\)/u);
  assert.match(playwrightApi, /url\.hash = hashPath/u);
  assert.match(playwrightSpec, /h5UrlForActor\(authoritativeActors\.financeReview\)/u);
  assert.match(
    playwrightSpec,
    /h5UrlForActor\(authoritativeActors\.financeCountersign\[0\]\)/u,
  );
  assert.match(
    playwrightSpec,
    /h5UrlForActor\(authoritativeActors\.financeCountersign\[1\]\)/u,
  );
  assert.match(smoke, /http:\/\/127\.0\.0\.1:9000\/#\/pages\/task\/list/u);
  assert.doesNotMatch(
    smoke,
    /#\/pages\/task\/list\?demoOperator=/u,
  );
});

test('assignment evidence is derived from the authoritative four-stage manifest', () => {
  assert.equal(goldenPath.tenant.id, 'demo-purchase-payment');
  assert.equal(goldenPath.request.businessKey, 'DEMO-PP-0001');
  assert.deepEqual(
    goldenPath.expectedWorkflow.map(step => ({
      actorIds: step.actorIds,
      client: step.client,
      mode: step.mode,
      taskDefinitionKey: step.taskDefinitionKey,
    })),
    [
      {
        actorIds: ['demo-manager'],
        client: 'pc',
        mode: 'SINGLE',
        taskDefinitionKey: 'managerApproval',
      },
      {
        actorIds: ['demo-finance-reviewer'],
        client: 'h5',
        mode: 'SINGLE',
        taskDefinitionKey: 'financeReview',
      },
      {
        actorIds: [
          'demo-finance-approver-a',
          'demo-finance-approver-b',
        ],
        client: 'h5',
        mode: 'ALL',
        taskDefinitionKey: 'financeCountersign',
      },
      {
        actorIds: ['demo-employee'],
        client: 'wechat',
        mode: 'SINGLE',
        taskDefinitionKey: 'paymentConfirmation',
      },
    ],
  );
  assert.match(playwrightApi, /purchase-payment-golden-path\.json/u);
  assert.match(playwrightApi, /governedScenario\.expectedWorkflow/u);
  assert.match(playwrightSpec, /pendingAssignments/u);
  assert.match(playwrightSpec, /operator-scoped real pending task visibility/u);
  assert.match(
    playwrightSpec,
    /countersignA\.taskId\)\.not\.toBe\(countersignB\.taskId/u,
  );
  assert.match(playwrightSpec, /expectOnlyActorTask/u);
  assert.match(playwrightSpec, /authoritativeActors\.paymentConfirmation/u);
});

test('machine evidence requires four processed actions and an independent payment handoff', () => {
  for (const marker of [
    'scenario.expectedWorkflow',
    "governedStage('paymentConfirmation')",
    'paymentHandoff',
    'payment confirmation task ID must be independent',
    "finalState?.status !== 'RUNNING'",
    'awaiting-payment state',
  ]) {
    assert.equal(evidenceSupport.includes(marker), true, `validator missing ${marker}`);
  }
  assert.match(evidenceSupport, /evidence\.steps\?\.length !== expectedSteps\.length/u);
  assert.match(evidenceSupport, /new Set\(processedTaskIds\)\.size/u);
  assert.match(evidenceSupport, /completedTaskId !== step\.taskId/u);
  assert.match(evidenceSupport, /auditRequestId !== step\.request\.requestId/u);
  assert.match(playwrightSpec, /auditRequestId/u);
  assert.match(playwrightSpec, /finalState: awaitingPaymentState/u);
  assert.match(playwrightSpec, /paymentTask\.taskId/u);
});

test('runtime failures preserve diagnostics for every browser actor and remain fail-closed', () => {
  assert.ok(
    playwrightSpec.indexOf('attachPageRuntimeDiagnostics')
      < playwrightSpec.indexOf('ensurePcLogin'),
  );
  for (const event of ['request', 'response', 'requestfailed', 'console', 'pageerror']) {
    assert.match(playwrightDiagnostics, new RegExp(`page\\.on\\('${event}'`, 'u'));
  }
  for (const marker of [
    'runtime-diagnostics.json',
    'runtime-diagnostics.md',
    'pc-demo-manager-runtime-failure.png',
    'h5-demo-finance-reviewer-runtime-failure.png',
    'h5-demo-finance-approver-a-runtime-failure.png',
    'h5-demo-finance-approver-b-runtime-failure.png',
    '/api/approval/tasks/pending',
    '/api/approval/instances/started',
    '/timeline',
    'consoleErrors',
    'pageErrors',
    'failedRequests',
    'taskDefinitionKey',
    'authoritativeActors',
    'processState',
    'processTimeline',
  ]) {
    const source = marker.endsWith('.png') ? smoke : playwrightDiagnostics;
    assert.equal(source.includes(marker), true, `runtime diagnostics missing ${marker}`);
  }
  assert.match(
    playwrightDiagnostics,
    /exactApprovalApiPath\([\s\S]*response\.url\(\)[\s\S]*\/api\/approval\/tasks\/pending/u,
  );
  assert.match(playwrightDiagnostics, /diagnosticScreenshotFile/u);
  assert.match(playwrightSpec, /throw error;/u);
  assert.match(playwrightSpec, /runtime diagnostics failed/u);
  assert.doesNotMatch(playwrightSpec, /catch\s*\([^)]*\)\s*\{\s*\}/u);
});

test('failure diagnostics read raw instance and timeline state as the governed initiator', () => {
  for (const [start, end, operation] of [
    ['const processState =', 'const processTimeline =', 'instance'],
    ['const processTimeline =', '\n  return {', 'timeline'],
  ]) {
    const startIndex = playwrightDiagnostics.indexOf(start);
    const endIndex = playwrightDiagnostics.indexOf(end, startIndex);
    assert.ok(startIndex >= 0 && endIndex > startIndex, `missing ${operation} diagnostic`);
    const read = playwrightDiagnostics.slice(startIndex, endIndex);
    assert.match(
      read,
      new RegExp(`readJson\\(\\s*request,\\s*authoritativeActors\\.initiator,\\s*'${operation}'`, 'u'),
    );
    assert.doesNotMatch(read, /demo-admin/u);
  }
});

test('success and failure evidence are retained in the permanent Vben artifact', () => {
  for (const marker of [
    'APPROVAL_PC_H5_RUNTIME_EVIDENCE_ENVELOPE_BEGIN',
    'APPROVAL_PC_H5_RUNTIME_EVIDENCE_ENVELOPE_END',
    'root-install.log',
    'PC_H5_BROWSER_RUNTIME_CI_ARTIFACT_ENVELOPE_V1',
    'source-identity.json',
    'h5-countersign-a-before.png',
    'h5-countersign-b-after.png',
    'trace.zip',
    'error-context.md',
    'sha256',
    'base64',
    'APPROVAL_DEMO_EXACT_HEAD_SHA',
    'pull_request?.head?.sha',
  ]) {
    assert.equal(smoke.includes(marker), true, `smoke missing ${marker}`);
  }
  assert.match(smoke, /appendCiEvidenceEnvelope\('PASSED'/u);
  assert.match(smoke, /appendCiEvidenceEnvelope\('FAILED'/u);
  assert.match(smoke, /CI runtime evidence retention failed/u);
  assert.match(smoke, /finalInstanceStatus=/u);
  assert.match(playwrightSpec, /APPROVAL_DEMO_EXACT_HEAD_SHA/u);
  assert.match(playwrightDiagnostics, /APPROVAL_DEMO_EXACT_HEAD_SHA/u);
});

test('system Chromium and passing trace configuration are explicit', () => {
  assert.match(playwrightConfig, /APPROVAL_DEMO_CHROME_PATH/u);
  assert.match(playwrightConfig, /APPROVAL_DEMO_EVIDENCE_DIR/u);
  assert.match(playwrightConfig, /resolve\(evidenceDirectory, 'playwright'\)/u);
  assert.match(playwrightConfig, /executablePath/u);
  assert.match(playwrightConfig, /--no-sandbox/u);
  assert.match(playwrightConfig, /workers: 1/u);
  assert.match(playwrightConfig, /trace: 'on'/u);
  assert.doesNotMatch(playwrightConfig, /install-deps/u);
  assert.doesNotMatch(playwrightConfig, /playwright install/u);
});

test('package, guide and permanent Hygiene expose the bounded smoke', () => {
  assert.equal(
    packageJson.scripts?.['demo:runtime:pc-h5:plan'],
    'node scripts/product-readiness/pc-h5-runtime-smoke.mjs plan --json',
  );
  assert.equal(
    packageJson.scripts?.['demo:runtime:pc-h5'],
    'node scripts/product-readiness/pc-h5-runtime-smoke.mjs run',
  );
  assert.equal(
    packageJson.scripts?.['demo:runtime:pc-h5:check'],
    'node --test scripts/tests/product-readiness-pc-h5-runtime-boundary.test.mjs',
  );
  assert.match(
    packageJson.scripts?.['web:test:client-boundary'] || '',
    /pc-h5-runtime-smoke\.mjs ci/u,
  );

  for (const marker of [
    'PC_H5_APPROVAL_HANDOFF_PASSED',
    'PURCHASE_APPROVAL_E2E_NOT_EXECUTED',
    'WECHAT_MINI_PROGRAM_RUNTIME_NOT_EXECUTED',
    'PC_H5_WECHAT_RUNTIME_NOT_EXECUTED',
    'demo-finance-approver-a',
    'demo-finance-approver-b',
    'runtime-diagnostics.json',
    'trace.zip',
    'PC_H5_BROWSER_RUNTIME_CI_ARTIFACT_ENVELOPE_V1',
  ]) {
    assert.equal(guide.includes(marker), true, `guide missing ${marker}`);
  }
  assert.doesNotMatch(guide, /^PURCHASE_APPROVAL_E2E_PASSED$/mu);
  assert.doesNotMatch(guide, /^PC_H5_WECHAT_RUNTIME_PASSED$/mu);
  assert.match(
    aggregate,
    /import '\.\/product-readiness-pc-h5-runtime-boundary\.test\.mjs';/u,
  );
});


test('shared backend startup helper alone selects this CI scope with exact-path boundaries', () => {
  const path = 'scripts/product-readiness/demo-backend-startup.mjs';
  assert.equal(relevantChangeSet([path]), true);
  for (const nearby of [
    'scripts/product-readiness/demo-backend-startup-extra.mjs',
    'scripts/product-readiness/demo-backend-startup.mjs.bak',
    'scripts/product-readiness/demo-backend-startup.js',
    'other/scripts/product-readiness/demo-backend-startup.mjs',
  ]) {
    assert.equal(relevantChangeSet([nearby]), false, nearby);
  }
});

test('isolated collaboration regression leaves golden approvals and receipts browser-owned', () => {
  const helper = text('apps/web/overlay/playground/__tests__/e2e/product-readiness-pc-h5-collaboration.ts');
  const testName = "test('PC/H5 collaboration notifications open read-only summaries for a pure participant'";
  const goldenBody = playwrightSpec.slice(playwrightSpec.indexOf("test('PC manager"), playwrightSpec.indexOf(testName));
  assert.doesNotMatch(goldenBody, /runCollaborationNotificationRegression|createCollaborationNotificationFixture/u);
  assert.equal((playwrightSpec.match(/await runCollaborationNotificationRegression\(/gu) || []).length, 1);
  assert.match(playwrightSpec.slice(playwrightSpec.indexOf(testName)), /testCaptureBudget\(testInfo, captureStartedAt\)/u);
  for (const required of [
    'const token = randomUUID()', 'COLLAB-NOTIFICATION-${token}',
    'expect(fixtureBusinessKey).not.toBe(businessKey)',
    '/api/approval/attachments', 'multipart:', 'attachmentIds: [attachmentId]',
    '/api/approval/instances/purchase-payment', 'assigneeRules: scenario.assigneeRules',
    'taskDefinitionKey: authoritativeTaskKeys.managerApproval',
    'assigneeId: authoritativeActors.managerApproval',
    'mode: \'ANY\'', 'TASK_COLLABORATION_ASSIGNED', 'TASK_COLLABORATION_RESULT',
    'await fixture.satisfy()', 'decidedAt: null',
    "await clickNotification(pc, 'pc'", "await clickNotification(h5, 'h5'",
    "'.notification-main' : '.card-main').click(",
    'actorResponse(response, `/api/approval/tasks/${fixture.taskId}/collaboration`)',
    "selectedHeaders(response).operatorId === observerId", "route.searchParams.get('taskId') === fixture.taskId",
    "page.locator('.notification-summary')", 'summary.locator(',
    'APPROVAL_RESOURCE_NOT_FOUND', 'APPROVAL_TASK_COLLABORATION_UNAUTHORIZED',
    'APPROVAL_TASK_COLLABORATION_STATE_CONFLICT',
    'expect(await readPolicy()).toEqual(before)',
    '/api/approval/instances/${instanceId}/withdraw', 'await fixture.cleanup()', 'pcContext.close()', 'h5Context.close()',
    'viewport: matrix.viewports.h5', 'config/demo/browser-accessibility-matrix.json',
    "item.channel !== 'IN_APP' && item.enabled", 'budget.remaining(',
  ]) assert.ok(helper.includes(required), `collaboration protection missing: ${required}`);
  assert.doesNotMatch(helper, /\/(?:approve|reject|resubmit|transfer|retrieve)(?:['"`/]|$)/u);
  assert.doesNotMatch(helper, /writeEvidence|publishCaptureReceipt|writeFileSync|\.route\(|route\.fulfill|addInitScript|setExtraHTTPHeaders|X-Approval-Trusted-Permissions|X-Approval-Worker-Id/u);
  assert.doesNotMatch(helper, /test\.setTimeout|captureBudget\(/u);
  assert.match(helper, /if \(!instanceId\) return/u);
});

test('a collaboration-helper-only change selects the existing PC/H5 runtime', () => {
  assert.equal(relevantChangeSet([
    'apps/web/overlay/playground/__tests__/e2e/product-readiness-pc-h5-collaboration.ts',
  ]), true);
  assert.equal(relevantChangeSet([
    'apps/web/overlay/playground/__tests__/e2e/product-readiness-pc-h5-collaboration-unrelated.ts',
  ]), false);
});

test('collaboration fixture writes stay on the explicit synthetic-resource allowlist', () => {
  const helper = text('apps/web/overlay/playground/__tests__/e2e/product-readiness-pc-h5-collaboration.ts');
  // Capture every direct API/wrapper POST call, including TypeScript generics.
  // The count also rejects a new call using an unreviewed dynamic first argument.
  const calls = [...helper.matchAll(/\b(?:request\.post|post)(?:<[\s\S]*?>)?\(/gu)];
  const paths = [...helper.matchAll(/\b(?:request\.post|post)(?:<[\s\S]*?>)?\(\s*([`'"])([\s\S]*?)\1/gu)]
    .map(match => match[2]);
  const allowed = [
    '${backendOrigin}${path}', // The single private, status-checked POST wrapper.
    '/api/approval/instances/${instanceId}/withdraw',
    '${backendOrigin}/api/approval/attachments',
    '/api/approval/instances/purchase-payment',
    '/api/approval/tasks/${taskId}/collaboration',
    '/api/approval/collaboration/participants/${participant(deciderId).participantId}/decide',
    '/api/approval/tasks/${taskId}/collaboration/participants', // Expected 403 owner-only probe.
    '/api/approval/collaboration/participants/${participant(observerId).participantId}/decide', // Expected 409 late-decision probe.
  ];
  assert.equal(calls.length, allowed.length);
  assert.deepEqual(paths.sort(), allowed.sort());
  assert.doesNotMatch(helper, /\b(?:fetch\(|request\.(?:fetch|put|patch|delete)\(|request\[)/u);
  assert.match(helper, /instanceId = started\.instanceId/u);
  assert.match(helper, /taskId = started\.activeTasks\[0\]\.taskId/u);
  assert.match(helper, /policy\.participants\.filter\(item => item\.participantUserId === actorId\)/u);
});


test('collaboration pending actions use exact accessible names within the fixture row and retain visibility and enabled checks', async () => {
  const helper = text('apps/web/overlay/playground/__tests__/e2e/product-readiness-pc-h5-collaboration.ts');
  const start = helper.indexOf('    const pendingRow =');
  const end = helper.indexOf('\n  }\n}\n\nexport async function runCollaborationNotificationRegression', start);
  assert.ok(start >= 0 && end > start);
  const body = helper.slice(start, end);
  const reason = 'Notification regression bounded-fixture';
  // Trace-shaped locator doubles exercise the actual helper branch. This is a
  // selection/assertion contract test, not a browser accessibility-tree replay.
  for (const client of ['pc', 'h5']) {
    for (const outcome of ['valid', 'wrong-name', 'other-row', 'disabled', 'hidden']) {
      const controls = () => ['同意', '拒绝'].map(name => ({
        role: 'button', name, text: ` ${name} `, visible: true, enabled: true,
      }));
      const target = { reason, controls: controls() };
      const other = { reason: 'Unrelated fixture row', controls: controls() };
      if (outcome === 'wrong-name') target.controls[0].name = '同意其他事项';
      if (outcome === 'other-row') target.controls = [];
      if (outcome === 'disabled') target.controls[0].enabled = false;
      if (outcome === 'hidden') target.controls[0].visible = false;
      const observed = [];
      const page = {
        locator(selector) {
          assert.equal(selector, client === 'pc' ? '.el-table__row' : '.item-card');
          return {
            filter(options) {
              assert.equal(options.hasText, reason);
              const rows = [target, other].filter(row => row.reason.includes(options.hasText));
              return {
                getByRole(role, options) {
                  assert.equal(role, 'button');
                  assert.equal(options.exact, true);
                  assert.ok(['同意', '拒绝'].includes(options.name));
                  observed.push(`select:${options.name}`);
                  return rows.flatMap(row => row.controls)
                    .filter(control => control.role === role && control.name === options.name);
                },
              };
            },
          };
        },
      };
      const expectation = controls => ({
        async toBeVisible(options) {
          assert.equal(options.timeout, 15_000);
          assert.equal(controls.length, 1, 'expected one control within the fixture row');
          assert.equal(controls[0].visible, true, 'control must be visible');
          observed.push(`visible:${controls[0].name}`);
        },
        async toBeEnabled(options) {
          assert.equal(options.timeout, 15_000);
          assert.equal(controls.length, 1, 'expected one control within the fixture row');
          assert.equal(controls[0].enabled, true, 'control must be enabled');
          observed.push(`enabled:${controls[0].name}`);
        },
      });
      const run = runInNewContext(`(async function () {${body}\n})`, {
        page, client, fixture: { reason }, expect: expectation,
        budget: { remaining(milliseconds) { assert.equal(milliseconds, 15_000); return milliseconds; } },
      }, { timeout: 1000 });
      if (outcome === 'valid') {
        await run();
        assert.deepEqual(observed, [
          'select:同意', 'visible:同意', 'enabled:同意',
          'select:拒绝', 'visible:拒绝', 'enabled:拒绝',
        ]);
      } else {
        await assert.rejects(run, /expected one control|control must be visible|control must be enabled/u);
      }
    }
  }
});

test('core H5 approval pages use governed narrow viewports without resizing PC', () => {
  const governed = JSON.parse(text('config/demo/browser-accessibility-matrix.json'));
  assert.deepEqual(governed.viewports.h5, { width: 390, height: 844 });
  const verify = (source) => {
    const golden = source.split('// A fresh fixture runs after the seeded handoff')[0];
    assert.match(golden, /readFileSync\(resolve\(\s*process\.env\.APPROVAL_DEMO_REPOSITORY_ROOT!, 'config\/demo\/browser-accessibility-matrix\.json'/u);
    assert.match(golden, /\}\s*\)\.viewports\.h5;/u);
    assert.match(golden, /expect\(h5Viewport\)\.toEqual\(\{ width: 390, height: 844 \}\);/u);
    assert.equal((golden.match(/browser\.newContext\(\)/gu) || []).length, 1);
    assert.equal((golden.match(/\.setViewportSize\(/gu) || []).length, 3);
    assert.doesNotMatch(golden, /pc\.setViewportSize|context\.setViewportSize/u);
    const start = golden.indexOf('  try {\n    const pcViewport = pc.viewportSize();');
    const navigation = golden.indexOf('    await ensurePcLogin(pc);');
    assert.ok(start >= 0 && navigation > start);
    for (const page of ['h5Reviewer', 'h5CountersignA', 'h5CountersignB']) {
      const setter = golden.indexOf(`await ${page}.setViewportSize(h5Viewport);`);
      const actual = golden.indexOf(`expect(${page}.viewportSize()).toEqual(h5Viewport);`);
      assert.ok(setter > start && actual > setter && actual < navigation, `${page} narrow setup precedes navigation`);
    }
    const pcCheck = golden.indexOf('expect(pc.viewportSize()).toEqual(pcViewport);');
    assert.ok(pcCheck > start && pcCheck < navigation);
  };
  verify(playwrightSpec);
  for (const [label, mutated] of [
    ['wrong dimensions', playwrightSpec.replace('width: 390, height: 844', 'width: 1280, height: 720')],
    ['missing config check', playwrightSpec.replace('expect(h5Viewport).toEqual({ width: 390, height: 844 });', '')],
    ['missing finance page', playwrightSpec.replace('await h5CountersignB.setViewportSize(h5Viewport);', '')],
    ['resized PC', playwrightSpec.replace('await h5Reviewer.setViewportSize(h5Viewport);', 'await pc.setViewportSize(h5Viewport);')],
    ['late setup', playwrightSpec.replace('    await ensurePcLogin(pc);', '').replace('    const pcViewport = pc.viewportSize();', '    await ensurePcLogin(pc);\n    const pcViewport = pc.viewportSize();')],
    ['missing actual assertion', playwrightSpec.replace('expect(h5Reviewer.viewportSize()).toEqual(h5Viewport);', '')],
    ['missing PC preservation', playwrightSpec.replace('expect(pc.viewportSize()).toEqual(pcViewport);', '')],
  ]) assert.throws(() => verify(mutated), undefined, label);
});
