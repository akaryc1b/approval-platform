import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve } from 'node:path';
import test from 'node:test';
import { projectReviewedWorkflow, reviewedWorkflowDeltas, workflowBlobSha } from '../security/workflow-evolution.mjs';
import { acceptedE2GraphProjection, resolveGitHubActionsEvidence } from '../security/m6-pr-e-e2-generate-sbom.mjs';
import { verifyWorkflowSupplyChainRemediation } from '../security/m6-pr-e-e3-verify-workflow-supply-chain-remediation-accepted.mjs';

const root = resolve(import.meta.dirname, '../..');
const workflowPath = '.github/workflows/approval-platform-validation.yml';
const currentSource = readFileSync(resolve(root, workflowPath), 'utf8');
const priorSha = '041705d7b2bf148658576b9d5b6d01e64e2d4e9d';
const currentSha = '35f61934083abd086de4833f627568270b91528b';
const projected = projectReviewedWorkflow(workflowPath, currentSource);
const pins = {
  'actions/checkout': '11d5960a326750d5838078e36cf38b85af677262',
  'actions/setup-node': '49933ea5288caeca8642d1e84afbd3f7d6820020',
  'actions/setup-java': 'cf277c60eb25467037889841efdb72551f06f6c3',
  'actions/download-artifact': 'd3f86a106a0bac45b974a628896c90dbdf5c8093',
  'actions/upload-artifact': 'ea165f8d65b6e75b540449e92b4886f43607fa02',
  'actions/upload-artifact/merge': 'ea165f8d65b6e75b540449e92b4886f43607fa02',
};
const json = value => `${JSON.stringify(value, null, 2)}\n`;
const clone = value => structuredClone(value);
function write(directory, path, content) {
  const file = resolve(directory, path);
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, content);
}
const actionStep = name => `      - uses: ${name}@${pins[name]} # v4\n`
  + (name === 'actions/checkout' ? '        with:\n          persist-credentials: false\n' : '');

// Eight small manual workflow fixtures plus the REAL committed validation source
// exercise both full inventory consumers. Scanner results below are unit fixtures,
// not a claim that a security scanner executed or that the current scan is clean.
function fixture(t, source = currentSource) {
  const directory = mkdtempSync(resolve(tmpdir(), 'approval-workflow-evolution-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const workflows = { [workflowPath]: { content: source, blobSha: workflowBlobSha(source) } };
  for (let index = 0; index < 8; index += 1) {
    const path = `.github/workflows/fixture-${index}.yml`;
    const content = 'name: Manual fixture\non:\n  workflow_dispatch:\npermissions:\n  contents: read\njobs:\n  fixture:\n    runs-on: ubuntu-latest\n    steps:\n'
      + ['actions/checkout', 'actions/setup-node', 'actions/upload-artifact',
        ...(index === 0 ? ['actions/setup-java'] : [])].map(actionStep).join('');
    workflows[path] = { content, blobSha: workflowBlobSha(content) };
  }
  const sourceBlobs = Object.fromEntries(Object.keys(workflows).map((path, index) => [
    path, (index + 1).toString(16).repeat(40),
  ]));
  const targetBlobs = Object.fromEntries(Object.entries(workflows).map(([path, value]) => [
    path, path === workflowPath ? priorSha : value.blobSha,
  ]));
  const baseline = {
    schemaVersion: 'M6_PR_E_E2_ACTION_RESOLUTION_BASELINE_V1', repository: 'akaryc1b/approval-platform',
    sourceHead: '1'.repeat(40), maintenancePullRequests: [], interpretation: { fixture: true },
    workflowFiles: Object.keys(workflows).map(path => ({ path, blobSha: sourceBlobs[path], automatic: path === workflowPath })),
    actionRefs: Object.fromEntries(Object.entries(pins).map(([name, sha]) => [`${name}@v4`, sha])),
  };
  const actionPins = Object.fromEntries(Object.entries(pins).map(([name, sha]) => [name, {
    reviewedImmutableSha: sha, symbolicRefResolvedSha: sha, priorSymbolicRef: 'v4',
    versionComment: 'v4', upstreamSymbolicRefDrift: false,
    repositoryIdentityVerified: true, commitExists: true,
  }]));
  let identity = 0;
  const historicalFindings = Object.fromEntries([
    ['zizmor/unpinned-uses', 43], ['zizmor/artipacked', 14], ['zizmor/template-injection', 1],
  ].map(([rule, count]) => [rule, Array.from({ length: count }, () => [
    (++identity).toString(16).padStart(64, '0'), workflowPath, identity, 'warning',
  ])]));
  const plan = {
    schemaVersion: 'M6_PR_E_E3_R2B_WORKFLOW_SUPPLY_CHAIN_REMEDIATION_PLAN_V1',
    repository: baseline.repository, actionPins, dependabotBlobShaRetained: 'd'.repeat(40),
    priorAcceptedHead: '2'.repeat(40), priorI4FindingSetSha256: '3'.repeat(64),
    priorI4CanonicalSha256: '4'.repeat(64), priorR2ACanonicalSha256: '5'.repeat(64),
    workflowInventory: { sourceBlobs, targetBlobs, expectedFileCount: 9, automaticWorkflowPath: workflowPath },
    invariants: { physicalJobCount: 9, permanentArtifactClasses: ['Hygiene', 'Maven', 'Mobile', 'Vben'] },
    historicalFindings,
    expectedCurrentRuleCounts: { 'zizmor/unpinned-uses': 0, 'zizmor/artipacked': 0, 'zizmor/template-injection': 0 },
    expectedHistoricalFindingCounts: { total: 58, 'zizmor/unpinned-uses': 43, 'zizmor/artipacked': 14, 'zizmor/template-injection': 1 },
  };
  const e4 = {
    repository: baseline.repository, commitSha: '7'.repeat(40), contentSha256: 'a'.repeat(64),
    allScannersCompleted: true, rawScannerReportsRetained: false, candidateSecretMaterialRetained: false,
    totalFindingCount: 145,
    scanners: Object.fromEntries([['osv', 115], ['gitleaks', 27], ['semgrep', 3], ['zizmor', 0]].map(([name, count]) => [name, {
      scanCompleted: true, rawReportRetained: false, candidateSecretMaterialRetained: false, sourceSnippetRetained: false,
      findingCount: count, findings: Array.from({ length: count }, (_, index) => ({ findingId: `${name}-fixture-${index}` })),
    }])),
  };
  const snapshot = {
    repository: baseline.repository, commitSha: e4.commitSha, currentE4CanonicalSha256: e4.contentSha256,
    dependabotBlobSha: plan.dependabotBlobShaRetained, scannerExecutionCount: 1, suppressionPathsPresent: [], workflows,
  };
  for (const [path, value] of Object.entries(workflows)) write(directory, path, value.content);
  write(directory, 'docs/m6/m6-pr-e-e2-action-resolution-baseline.json', json(baseline));
  write(directory, 'docs/m6/m6-pr-e-e3-r2b-workflow-supply-chain-remediation.json', json(plan));
  return { directory, plan, snapshot, e4 };
}
function replaceWorkflow(value, source) {
  value.snapshot.workflows[workflowPath] = { content: source, blobSha: workflowBlobSha(source) };
  write(value.directory, workflowPath, source);
}
const external = graph => graph.workflows.flatMap(item => item.actions).filter(item => item.declared.startsWith('actions/'));
const e2 = githubActions => ({ maven: { fixture: true }, pnpm: { fixture: true }, githubActions });

test('exact current workflow projects to byte-exact prior workflow, without touching current source', () => {
  assert.equal(workflowBlobSha(currentSource), currentSha);
  assert.equal(workflowBlobSha(projected.priorSource), priorSha);
  assert.equal(projected.priorBlobSha, priorSha);
  assert.equal(projected.evolution.addedJob, 'online-images');
  assert.equal(currentSource, `${projected.priorSource}\n  online-images:\n${currentSource.split('\n\n  online-images:\n')[1]}`);
  assert.deepEqual(reviewedWorkflowDeltas([projected.evolution], [{ path: workflowPath, blobSha: currentSha }]), {
    actions: 3, checkouts: 1, jobs: 1, artifactClasses: ['Online-images'],
  });
});
test('historical workflow has no evolution and keeps historical counts', () => {
  assert.deepEqual(projectReviewedWorkflow(workflowPath, projected.priorSource), {
    priorSource: projected.priorSource, priorBlobSha: priorSha, evolution: null,
  });
  assert.deepEqual(reviewedWorkflowDeltas([], [{ path: workflowPath, blobSha: priorSha }]), {
    actions: 0, checkouts: 0, jobs: 0, artifactClasses: [],
  });
});
test('exact extension cannot authorize a different path or a substituted historical baseline', () => {
  for (const [path, expected] of [[workflowPath, 'f'.repeat(40)], ['.github/workflows/other.yml', priorSha]]) {
    const value = projectReviewedWorkflow(path, currentSource, expected);
    assert.equal(value.evolution, null); assert.equal(value.priorSource, currentSource);
  }
});
test('E2 preserves historical graph while reporting all current action occurrences and blobs', t => {
  const current = fixture(t);
  const historical = fixture(t, projected.priorSource);
  const oldGraph = resolveGitHubActionsEvidence(historical.directory);
  const graph = resolveGitHubActionsEvidence(current.directory);
  assert.equal(external(oldGraph).length, 43); assert.equal(external(graph).length, 46);
  assert.equal(graph.workflows.find(item => item.path === workflowPath).blobSha, currentSha);
  assert.equal(oldGraph.workflows.find(item => item.path === workflowPath).blobSha, priorSha);
  assert.equal(external(graph).every(item => item.mutableRef === false), true);
  assert.deepEqual(graph.acceptedDependencyGraph, oldGraph.acceptedDependencyGraph);
  assert.deepEqual(acceptedE2GraphProjection(e2(graph)), acceptedE2GraphProjection(e2(oldGraph)));
  assert.equal(Object.hasOwn(oldGraph, 'reviewedWorkflowEvolutions'), false);
  assert.deepEqual(graph.reviewedWorkflowEvolutions, [projected.evolution]);
});
test('R2B inspects and reports the full current inventory without rewriting historical findings', t => {
  const current = fixture(t); const old = fixture(t, projected.priorSource);
  const originalPlan = json(current.plan);
  const evidence = verifyWorkflowSupplyChainRemediation(current.e4, current.plan, current.snapshot);
  const previous = verifyWorkflowSupplyChainRemediation(old.e4, old.plan, old.snapshot);
  assert.equal(evidence.actionUseCount, 46); assert.equal(evidence.checkoutCredentialBoundaryCount, 15);
  assert.equal(evidence.physicalJobCount, 10); assert.equal(evidence.automaticWorkflowCount, 1);
  assert.equal(evidence.workflowBlobs[workflowPath], currentSha);
  assert.deepEqual(evidence.permanentArtifactClasses, ['Hygiene', 'Maven', 'Mobile', 'Online-images', 'Vben']);
  assert.equal(previous.actionUseCount, 43); assert.equal(previous.checkoutCredentialBoundaryCount, 14);
  assert.equal(previous.physicalJobCount, 9); assert.equal(Object.hasOwn(previous, 'reviewedWorkflowEvolutions'), false);
  assert.deepEqual(evidence.remediatedFindings, previous.remediatedFindings);
  assert.equal(evidence.historicalFindingCount, 58); assert.equal(evidence.releaseBlocked, true);
  assert.equal(evidence.authoritativeGitHubInventoryComplete, false);
  assert.deepEqual(evidence.reviewedWorkflowEvolutions, [projected.evolution]);
  assert.equal(json(current.plan), originalPlan);
});

const mutations = [
  ['existing timeout', source => source.replace('timeout-minutes: 45', 'timeout-minutes: 46')],
  ['existing matrix', source => source.replace('          - 3\n', '')],
  ['new mutable Action', source => source.replaceAll(`actions/setup-node@${pins['actions/setup-node']} # v4`, 'actions/setup-node@v4')],
  ['new permission', source => source.replace('    permissions:\n      contents: read', '    permissions:\n      contents: write')],
  ['new checkout credentials', source => source.replace('fetch-depth: 0\n          persist-credentials: false\n      - uses: actions/setup-node', 'fetch-depth: 0\n          persist-credentials: true\n      - uses: actions/setup-node')],
  ['new checkout identity', source => source.replace('ref: ${{ github.event.pull_request.head.sha || github.sha }}', 'ref: main')],
  ['new artifact widening', source => source.replace('.runtime/online-demo-images/*/image-build.json', '.runtime/**')],
  ['new command', source => source.replace('online-demo-images-runtime.mjs ci', 'online-demo-images-runtime.mjs run')],
  ['new task rename', source => source.replace('  online-images:', '  another-job:')],
  ['extra task', source => `${source}\n  other:\n    runs-on: ubuntu-latest\n    steps:\n      - run: echo unexpected\n`],
  ['trailing mutation', source => `${source}# unreviewed byte change\n`],
];
for (const [name, mutate] of mutations) {
  test(`unreviewed ${name} fails closed in both live workflow consumers`, t => {
    const value = fixture(t); const changed = mutate(currentSource);
    assert.notEqual(changed, currentSource, 'mutation must apply');
    assert.equal(projectReviewedWorkflow(workflowPath, changed).evolution, null);
    replaceWorkflow(value, changed);
    assert.throws(() => resolveGitHubActionsEvidence(value.directory), /workflow blob drift/u);
    assert.throws(() => verifyWorkflowSupplyChainRemediation(value.e4, value.plan, value.snapshot), /target workflow blob mismatch/u);
  });
}
for (const field of ['path', 'priorBlobSha', 'currentBlobSha', 'addedJob', 'addedActionUseCount', 'addedCheckoutCount', 'addedPhysicalJobCount', 'addedArtifactClass']) {
  test(`serialized evolution cannot supply unreviewed ${field}`, t => {
    const value = fixture(t); const graph = resolveGitHubActionsEvidence(value.directory);
    graph.reviewedWorkflowEvolutions[0][field] = 'substituted';
    assert.throws(() => acceptedE2GraphProjection(e2(graph)), /unreviewed workflow evolution/u);
  });
}
test('serialized evidence rejects missing or duplicate evolution and mismatching workflow identity', t => {
  const value = fixture(t); const original = resolveGitHubActionsEvidence(value.directory);
  for (const mutate of [
    graph => { delete graph.reviewedWorkflowEvolutions; },
    graph => { graph.reviewedWorkflowEvolutions.push(clone(projected.evolution)); },
    graph => { graph.reviewedWorkflowEvolutions[0].unreviewed = true; },
    graph => { graph.workflows.find(item => item.path === workflowPath).blobSha = priorSha; },
    graph => { graph.workflows.push(clone(graph.workflows.find(item => item.path === workflowPath))); },
  ]) {
    const graph = clone(original); mutate(graph);
    assert.throws(() => acceptedE2GraphProjection(e2(graph)), /evolution/u);
  }
  const truncated = clone(original); truncated.workflows.find(item => item.path === workflowPath).actions.pop();
  assert.throws(() => acceptedE2GraphProjection(e2(truncated)), /current Action state mismatch/u);
});
test('evolution objects returned by a call cannot modify future authorization', () => {
  const first = projectReviewedWorkflow(workflowPath, currentSource);
  first.evolution.addedActionUseCount = 100;
  assert.equal(projectReviewedWorkflow(workflowPath, currentSource).evolution.addedActionUseCount, 3);
});
test('new job cannot transfer historical clean scanner fixtures onto incomplete or nonzero current results', t => {
  const value = fixture(t);
  const incomplete = clone(value.e4); incomplete.allScannersCompleted = false;
  assert.throws(() => verifyWorkflowSupplyChainRemediation(incomplete, value.plan, value.snapshot), /complete redacted/u);
  const finding = clone(value.e4);
  finding.scanners.zizmor.findingCount = 1; finding.scanners.zizmor.findings = [{ findingId: 'f'.repeat(64) }]; finding.totalFindingCount += 1;
  assert.throws(() => verifyWorkflowSupplyChainRemediation(finding, value.plan, value.snapshot), /must be zero/u);
  const truncated = clone(value.e4); truncated.scanners.osv.findings.pop();
  assert.throws(() => verifyWorkflowSupplyChainRemediation(truncated, value.plan, value.snapshot), /complete current osv/u);
  assert.throws(() => verifyWorkflowSupplyChainRemediation(value.e4, value.plan, { ...value.snapshot, scannerExecutionCount: 2 }), /exactly once/u);
  assert.throws(() => verifyWorkflowSupplyChainRemediation(value.e4, value.plan, { ...value.snapshot, suppressionPathsPresent: ['.zizmor.yml'] }), /suppression/u);
});
test('independent semantic checks still reject unsafe changes even when a fixture plan updates a blob', t => {
  const value = fixture(t); const path = '.github/workflows/fixture-0.yml';
  for (const [mutate, expected] of [
    [source => source.replace(`actions/setup-node@${pins['actions/setup-node']} # v4`, 'actions/setup-node@v4'), /immutable SHA/u],
    [source => source.replace('persist-credentials: false', 'persist-credentials: true'), /persist-credentials false/u],
    [source => source.replace('contents: read', 'contents: write'), /permission widening/u],
  ]) {
    const snapshot = clone(value.snapshot); const plan = clone(value.plan);
    const content = mutate(snapshot.workflows[path].content);
    snapshot.workflows[path] = { content, blobSha: workflowBlobSha(content) };
    plan.workflowInventory.targetBlobs[path] = snapshot.workflows[path].blobSha;
    assert.throws(() => verifyWorkflowSupplyChainRemediation(value.e4, plan, snapshot), expected);
  }
});
test('client tools do not install the absent root lock or weaken the PC frozen workspace', () => {
  const recipe = readFileSync(resolve(root, 'deploy/online-demo/images/clients.Dockerfile'), 'utf8');
  const tools = recipe.split('FROM client-tools AS pc-build')[0];
  assert.doesNotMatch(tools, /pnpm\s+(?:install|i)\b/u);
  assert.match(tools, /corepack prepare/u);
  assert.match(recipe, /pnpm --dir \.upstream\/vben install --frozen-lockfile/u);
  assert.match(recipe, /pnpm -C \.upstream\/unibest install --no-frozen-lockfile/u);
  const contract = readFileSync(resolve(root, 'scripts/product-readiness/online-demo/images-contract.mjs'), 'utf8');
  assert.match(contract, /root: 'NOT_REQUIRED_FOR_CLIENT_IMAGE_BUILDS', pc: 'FROZEN_LOCKFILE'/u);
});
test('historical fixture suite imports live evolution tests and verifier inspects current, not projected, content', () => {
  const legacy = readFileSync(resolve(root, 'scripts/tests/m6-pr-e-e3-r2b-workflow-supply-chain-remediation-boundary-accepted.test.mjs'), 'utf8');
  assert.match(legacy, /import '\.\/workflow-evolution\.test\.mjs'/u);
  const verifier = readFileSync(resolve(root, 'scripts/security/m6-pr-e-e3-verify-workflow-supply-chain-remediation-accepted.mjs'), 'utf8');
  assert.match(verifier, /inspectWorkflow\(path, current\.content, plan\.actionPins\)/u);
  assert.doesNotMatch(verifier, /inspectWorkflow\(path, projection\.priorSource/u);
});
