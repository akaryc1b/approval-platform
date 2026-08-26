import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

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

test('runtime smoke exposes a read-only four-action plan and skips non-CI', () => {
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
    plan.stages.some(stage => stage.includes('reaches COMPLETED')),
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
  assert.match(playwrightSpec, /waitForCompletedInstance/u);
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
  assert.match(playwrightApi, /waitForCompletedInstance/u);
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

test('assignment evidence matches the authoritative deterministic seed', () => {
  assert.equal(goldenPath.tenant.id, 'demo-purchase-payment');
  assert.equal(goldenPath.request.businessKey, 'DEMO-PP-0001');
  assert.deepEqual(
    goldenPath.expectedWorkflow.map(step => ({
      actorIds: step.actorIds,
      mode: step.mode,
      taskDefinitionKey: step.taskDefinitionKey,
    })),
    [
      {
        actorIds: ['demo-manager'],
        mode: 'SINGLE',
        taskDefinitionKey: 'managerApproval',
      },
      {
        actorIds: ['demo-finance-reviewer'],
        mode: 'SINGLE',
        taskDefinitionKey: 'financeReview',
      },
      {
        actorIds: [
          'demo-finance-approver-a',
          'demo-finance-approver-b',
        ],
        mode: 'ALL',
        taskDefinitionKey: 'financeCountersign',
      },
    ],
  );
  assert.match(playwrightApi, /purchase-payment-golden-path\.json/u);
  assert.match(playwrightSpec, /pendingAssignments/u);
  assert.match(playwrightSpec, /operator-scoped real pending task visibility/u);
  assert.match(
    playwrightSpec,
    /countersignA\.taskId\)\.not\.toBe\(countersignB\.taskId/u,
  );
  assert.match(playwrightSpec, /expectOnlyActorTask/u);
  assert.match(playwrightSpec, /expectNoActorTasks/u);
});

test('machine evidence requires four distinct actions and final completion', () => {
  for (const actor of [
    'demo-manager',
    'demo-finance-reviewer',
    'demo-finance-approver-a',
    'demo-finance-approver-b',
  ]) {
    assert.equal(evidenceSupport.includes(actor), true, `validator missing ${actor}`);
  }
  assert.match(evidenceSupport, /evidence\.steps\?\.length !== expectedSteps\.length/u);
  assert.match(evidenceSupport, /new Set\(taskIds\)\.size !== taskIds\.length/u);
  assert.match(evidenceSupport, /completedTaskId !== step\.taskId/u);
  assert.match(evidenceSupport, /auditRequestId !== step\.request\.requestId/u);
  assert.match(evidenceSupport, /finalState\?\.status !== 'COMPLETED'/u);
  assert.match(evidenceSupport, /all four approval actions/u);
  assert.match(playwrightSpec, /auditRequestId/u);
  assert.match(playwrightSpec, /finalState: completedState/u);
});

test('runtime failures preserve diagnostics for every actor and remain fail-closed', () => {
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
