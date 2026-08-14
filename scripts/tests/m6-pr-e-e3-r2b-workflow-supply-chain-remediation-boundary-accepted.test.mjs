import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import { verifyWorkflowSupplyChainRemediation } from '../security/m6-pr-e-e3-verify-workflow-supply-chain-remediation-accepted.mjs';
import { applyWorkflowSupplyChainReviews } from '../security/m6-pr-e-e3-apply-workflow-supply-chain-reviews.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const planPath = path.join(root, 'docs/m6/m6-pr-e-e3-r2b-workflow-supply-chain-remediation.json');
const i4ReviewPath = path.join(root, 'docs/m6/m6-pr-e-e3-i4-reviewed-findings.json');
const contractPath = path.join(root, 'docs/m6/M6_PR_E_E3_R2B_WORKFLOW_SUPPLY_CHAIN_REMEDIATION.md');
const workflowRoot = path.join(root, '.github/workflows');
const load = (file) => readFileSync(file, 'utf8');
const sha256 = (value) => createHash('sha256').update(value).digest('hex');
const gitBlobSha = (value) => createHash('sha1').update(`blob ${Buffer.byteLength(value)}\0`).update(value).digest('hex');
const clone = (value) => JSON.parse(JSON.stringify(value));
const expectedSourceBlobs = {
  '.github/workflows/approval-platform-validation.yml': 'f906302f96b352df54cb3cc903832e743be192d2',
  '.github/workflows/backend-ci.yml': 'cc99299ff1d1db1c42f91b75e62b058d0a67a243',
  '.github/workflows/connector-smoke-ci.yml': 'ac4c1182bc2a9399e447395a634d007d314d9422',
  '.github/workflows/frontend-ci.yml': 'fe46c261d7425f66c4db9860a949b9bd90d89f54',
  '.github/workflows/generic-spring-host-ci.yml': '4899db6b69aaad97d90d05d58fcada10e8bdd1a0',
  '.github/workflows/mobile-ci.yml': 'cda05d993e0442b07eb609ad22bc21713faac15b',
  '.github/workflows/ruoyi5-host-ci.yml': '1780ac0867ff1033588a674dbc2b9e77764f962a',
  '.github/workflows/ruoyi6-host-ci.yml': '7b2dec12bcb281fdaf4aff5b276973c3bd5adf65',
  '.github/workflows/web-ci.yml': '4cbf29d043bd78a940cc9f9acf05a6c1c97f6286',
};
const expectedTargetBlobs = {
  '.github/workflows/approval-platform-validation.yml': '041705d7b2bf148658576b9d5b6d01e64e2d4e9d',
  '.github/workflows/backend-ci.yml': '14fe5e155cae9da738d5cee6217968d3459d547f',
  '.github/workflows/connector-smoke-ci.yml': 'e5605b789d1ae83fb2986e9a279e34957c7a5310',
  '.github/workflows/frontend-ci.yml': '2bd2a2f2829351a5e6904933ac2645a416681f26',
  '.github/workflows/generic-spring-host-ci.yml': 'a217d87f76d9c1ffa09da74e070194459e676c7d',
  '.github/workflows/mobile-ci.yml': '16fcc93d1ff9bd343012af59c52e5a4f3480ff9c',
  '.github/workflows/ruoyi5-host-ci.yml': 'b9b8064f78277b47ddb230c08be210bcc099d5e8',
  '.github/workflows/ruoyi6-host-ci.yml': '7d7bab6dcf0f58a86e843d69f71e5d93c422fa1c',
  '.github/workflows/web-ci.yml': '72167454bc270c8923fb305a8d705c682079c74e',
};

function workflowSnapshot(commitSha = '7'.repeat(40), e4CanonicalSha256 = 'a'.repeat(64)) {
  const workflows = {};
  for (const name of readdirSync(workflowRoot).filter((file) => /\.ya?ml$/.test(file)).sort()) {
    const content = load(path.join(workflowRoot, name));
    workflows[`.github/workflows/${name}`] = { content, blobSha: gitBlobSha(content) };
  }
  return {
    repository: 'akaryc1b/approval-platform',
    commitSha,
    currentE4CanonicalSha256: e4CanonicalSha256,
    dependabotBlobSha: '38ba75af261c084b5c8984c52fb1bf23439fd1a9',
    scannerExecutionCount: 1,
    suppressionPathsPresent: [],
    workflows,
  };
}

function e4Fixture(commitSha = '7'.repeat(40), contentSha256 = 'a'.repeat(64)) {
  return {
    repository: 'akaryc1b/approval-platform',
    commitSha,
    contentSha256,
    allScannersCompleted: true,
    rawScannerReportsRetained: false,
    candidateSecretMaterialRetained: false,
    totalFindingCount: 145,
    scanners: {
      osv: { scanCompleted: true, rawReportRetained: false, findingCount: 115, findings: Array.from({ length: 115 }, (_, index) => ({ findingId: `osv-${index}` })) },
      gitleaks: { scanCompleted: true, rawReportRetained: false, candidateSecretMaterialRetained: false, findingCount: 27, findings: Array.from({ length: 27 }, (_, index) => ({ findingId: `gitleaks-${index}` })) },
      semgrep: { scanCompleted: true, rawReportRetained: false, sourceSnippetRetained: false, findingCount: 3, findings: Array.from({ length: 3 }, (_, index) => ({ findingId: `semgrep-${index}` })) },
      zizmor: { scanCompleted: true, rawReportRetained: false, findingCount: 0, findings: [] },
    },
  };
}

function historicalR2B(plan) {
  return Object.entries(plan.historicalFindings).flatMap(([ruleId, rows]) => rows.map((row) => ({
    sourceClass: 'E4_ZIZMOR',
    findingId: row[0],
    ruleId,
    path: row[1],
    startLine: row[2],
    upstreamSeverity: row[3],
  })));
}

function i4Review(plan) {
  const all = [...plan.priorR2ARemediatedFindings, ...historicalR2B(plan)];
  const findingGroups = {};
  for (const finding of all) {
    (findingGroups[finding.ruleId] ||= []).push([
      finding.findingId,
      finding.path,
      finding.startLine,
      finding.upstreamSeverity,
    ]);
  }
  const trueReachability = Object.fromEntries(['packaged', 'loaded', 'invoked', 'externallyReachable'].map((key) => [key, { value: true, evidence: `${key} evidence` }]));
  const rulePolicies = Object.fromEntries(Object.keys(findingGroups).map((ruleId) => [ruleId, {
    disposition: 'APPLICABLE',
    reachability: trueReachability,
    exploitPreconditions: ['reviewed precondition'],
    mitigations: [],
    evidenceCode: 'R2B_REVIEWED',
    evidence: 'Historical I4 finding identity is retained and reconciled against exact current scanner evidence.',
    r2Action: 'Retain append-only remediation lineage.',
  }]));
  return {
    repository: plan.repository,
    reviewBasisHead: '1'.repeat(40),
    reviewBasisE4CanonicalSha256: '2'.repeat(64),
    reviewBasisI3CanonicalSha256: '3'.repeat(64),
    findingSetSha256: plan.priorI4FindingSetSha256,
    findingGroups,
    rulePolicies,
  };
}

function unresolved(sourceClass, findingId) {
  return {
    sourceClass,
    findingId,
    severityBand: 'UNKNOWN',
    disposition: 'UNRESOLVED',
    reachability: {
      packaged: { value: null, evidence: 'pending' },
      loaded: { value: null, evidence: 'pending' },
      invoked: { value: null, evidence: 'pending' },
      externallyReachable: { value: null, evidence: 'pending' },
    },
  };
}

function withMutatedWorkflow(plan, snapshot, workflowPath, mutate) {
  const nextPlan = clone(plan);
  const nextSnapshot = clone(snapshot);
  const current = nextSnapshot.workflows[workflowPath];
  current.content = mutate(current.content);
  current.blobSha = gitBlobSha(current.content);
  nextPlan.workflowInventory.targetBlobs[workflowPath] = current.blobSha;
  return { plan: nextPlan, snapshot: nextSnapshot };
}

test('R2B contract keeps remediation and release boundaries explicit', () => {
  assert.equal(existsSync(contractPath), true);
  const body = load(contractPath);
  for (const marker of [
    'REMEDIATION != HISTORY_REWRITE',
    'MUTABLE_REF -> REVIEWED_IMMUTABLE_SHA',
    'PERSISTED_CHECKOUT_CREDENTIAL -> PERSIST_CREDENTIALS_FALSE',
    'SHELL_TEMPLATE_EXPANSION -> STEP_ENV_DATA_BOUNDARY',
    'ZERO_ZIZMOR_FINDINGS != ZERO_SECURITY_FINDINGS',
    'CURRENT_SCAN_EXECUTION_COUNT == 1',
    'NO_SUPPRESSION',
    'NO_SEVERITY_DOWNGRADE',
    'NO_EXCEPTION',
    'M6_PR_E_E3_CLOSURE_NOT_ACCEPTED',
    'PRB_16_REMAINS_OPEN',
    'PRB_17_REMAINS_OPEN',
    'ISSUE_97_REMAINS_OPEN',
    'PR_98_REMAINS_OPEN_DRAFT_UNMERGED',
    'NO_READY',
    'NO_MERGE',
    'NO_DEPLOYMENT',
    'AI_IS_NOT_AN_OPERATOR',
  ]) assert.ok(body.includes(marker), marker);
});

test('R2B plan binds exact source and target workflow blobs plus all 58 historical identities', () => {
  const plan = JSON.parse(load(planPath));
  assert.equal(plan.repository, 'akaryc1b/approval-platform');
  assert.equal(plan.priorAcceptedHead, '05f422b4cdab397fc1126e6dc10f571b01cec8c5');
  assert.equal(plan.priorI4FindingSetSha256, 'd12463e28555e88fbed0e9ae73a83232296fb1879e2d7479a91f4e89255bc2fe');
  assert.equal(plan.priorR2ACanonicalSha256, '65e2036c1738f2b4eee56cc3bfc154eb9856401ec5ece2673268eb45c96270cc');
  assert.equal(plan.priorE4CanonicalSha256, '4e86049fd18fbfebd7397d0c131563e849064eb698baa66bc4f9bd2d9cffbf58');
  assert.equal(Object.keys(plan.workflowInventory.sourceBlobs).length, 9);
  assert.deepEqual(plan.workflowInventory.sourceBlobs, expectedSourceBlobs);
  assert.deepEqual(plan.workflowInventory.targetBlobs, expectedTargetBlobs);
  assert.deepEqual(Object.keys(plan.workflowInventory.sourceBlobs).sort(), Object.keys(plan.workflowInventory.targetBlobs).sort());
  for (const [workflowPath, expected] of Object.entries(plan.workflowInventory.targetBlobs)) {
    const content = load(path.join(root, workflowPath));
    assert.equal(gitBlobSha(content), expected, workflowPath);
    assert.notEqual(expected, plan.workflowInventory.sourceBlobs[workflowPath], workflowPath);
  }
  const counts = Object.fromEntries(Object.entries(plan.historicalFindings).map(([rule, rows]) => [rule, rows.length]));
  assert.deepEqual(counts, {
    'zizmor/artipacked': 14,
    'zizmor/template-injection': 1,
    'zizmor/unpinned-uses': 43,
  });
  const plannedFindings = [...plan.priorR2ARemediatedFindings, ...historicalR2B(plan)];
  const ids = plannedFindings.map((finding) => finding.findingId).sort();
  assert.equal(ids.length, 61);
  assert.equal(new Set(ids).size, 61);
  assert.equal(sha256(`${ids.join('\n')}\n`), plan.priorI4FindingSetSha256);

  const i4 = JSON.parse(load(i4ReviewPath));
  assert.equal(i4.repository, plan.repository);
  assert.equal(i4.findingSetSha256, plan.priorI4FindingSetSha256);
  assert.deepEqual(
    Object.fromEntries(Object.keys(plan.workflowInventory.sourceBlobs).map((workflowPath) => [workflowPath, i4.sourceBlobs[workflowPath]])),
    plan.workflowInventory.sourceBlobs,
  );
  assert.deepEqual(i4.actionResolutions, Object.fromEntries(Object.entries(plan.actionPins).map(([repository, pin]) => [
    `${repository}@${pin.priorSymbolicRef}`,
    pin.reviewedImmutableSha,
  ])));
  const authority = Object.entries(i4.findingGroups).flatMap(([ruleId, rows]) => rows.map(([findingId, workflowPath, startLine, upstreamSeverity]) => ({
    findingId,
    path: workflowPath,
    ruleId,
    sourceClass: 'E4_ZIZMOR',
    startLine,
    upstreamSeverity,
  })));
  const identity = (finding) => [finding.findingId, finding.path, finding.ruleId, finding.sourceClass, finding.startLine, finding.upstreamSeverity];
  assert.deepEqual(authority.map(identity).sort(), plannedFindings.map(identity).sort());
});

test('R2B verifier proves immutable pins, checkout boundary, shell env boundary and zero current zizmor findings', () => {
  const plan = JSON.parse(load(planPath));
  const e4 = e4Fixture();
  const snapshot = workflowSnapshot();
  const evidence = verifyWorkflowSupplyChainRemediation(e4, plan, snapshot);
  assert.equal(evidence.commitSha, e4.commitSha);
  assert.equal(evidence.sourceE4CanonicalSha256, e4.contentSha256);
  assert.equal(evidence.actionUseCount, 43);
  assert.equal(evidence.checkoutCredentialBoundaryCount, 14);
  assert.equal(evidence.templateInjectionBoundaryCount, 1);
  assert.equal(evidence.historicalFindingCount, 58);
  assert.equal(evidence.remediatedFindings.length, 58);
  assert.equal(evidence.currentZizmorFindingCount, 0);
  assert.deepEqual(evidence.currentScannerCounts, { gitleaks: 27, osv: 115, semgrep: 3, zizmor: 0 });
  assert.equal(evidence.totalFindingCount, 145);
  assert.equal(evidence.automaticWorkflowCount, 1);
  assert.equal(evidence.physicalJobCount, 9);
  assert.deepEqual(evidence.permanentArtifactClasses, ['Hygiene', 'Maven', 'Mobile', 'Vben']);
  assert.equal(evidence.workflowSupplyChainRemediationValidated, true);
  assert.equal(evidence.releaseBlocked, true);
  assert.equal(evidence.authoritativeGitHubInventoryComplete, false);
  assert.match(evidence.contentSha256, /^[0-9a-f]{64}$/);
});

test('R2B verifier fails closed on blob drift, scanner duplication, suppression or new current zizmor findings', () => {
  const plan = JSON.parse(load(planPath));
  const e4 = e4Fixture();
  const snapshot = workflowSnapshot();
  const blobDrift = clone(snapshot);
  blobDrift.workflows['.github/workflows/backend-ci.yml'].blobSha = '1'.repeat(40);
  assert.throws(() => verifyWorkflowSupplyChainRemediation(e4, plan, blobDrift), /content\/blob mismatch|target workflow blob mismatch/);
  assert.throws(() => verifyWorkflowSupplyChainRemediation(e4, plan, { ...snapshot, scannerExecutionCount: 2 }), /exactly once/);
  assert.throws(() => verifyWorkflowSupplyChainRemediation(e4, plan, { ...snapshot, suppressionPathsPresent: ['.semgrepignore'] }), /suppression\/ignore/);
  assert.throws(() => verifyWorkflowSupplyChainRemediation({ ...e4, allScannersCompleted: false }, plan, snapshot), /complete redacted current E4 evidence/);
  const truncated = clone(e4);
  truncated.scanners.osv.findings.pop();
  assert.throws(() => verifyWorkflowSupplyChainRemediation(truncated, plan, snapshot), /complete current osv evidence/);
  const currentFinding = clone(e4);
  currentFinding.scanners.zizmor = {
    scanCompleted: true,
    rawReportRetained: false,
    findingCount: 1,
    findings: [{ sourceClass: 'E4_ZIZMOR', findingId: 'f'.repeat(64), ruleId: 'zizmor/unpinned-uses' }],
  };
  currentFinding.totalFindingCount = 146;
  assert.throws(() => verifyWorkflowSupplyChainRemediation(currentFinding, plan, snapshot), /must be zero/);
});

test('R2B verifier independently rejects mutable refs, credential persistence, direct template expansion and automatic workflow drift', () => {
  const basePlan = JSON.parse(load(planPath));
  const e4 = e4Fixture();
  const baseSnapshot = workflowSnapshot();
  const validationPath = '.github/workflows/approval-platform-validation.yml';

  let changed = withMutatedWorkflow(basePlan, baseSnapshot, '.github/workflows/backend-ci.yml', (source) => source.replace(
    'actions/setup-java@cf277c60eb25467037889841efdb72551f06f6c3 # v4',
    'actions/setup-java@v4',
  ));
  assert.throws(() => verifyWorkflowSupplyChainRemediation(e4, changed.plan, changed.snapshot), /immutable SHA/);

  changed = withMutatedWorkflow(basePlan, baseSnapshot, '.github/workflows/backend-ci.yml', (source) => source.replace(
    'persist-credentials: false\n',
    '',
  ));
  assert.throws(() => verifyWorkflowSupplyChainRemediation(e4, changed.plan, changed.snapshot), /persist-credentials false/);

  changed = withMutatedWorkflow(basePlan, baseSnapshot, validationPath, (source) => source
    .replace('        env:\n          SELECTED_TESTS: ${{ steps.selection.outputs.tests }}\n', '')
    .replace('-Dtest="$SELECTED_TESTS"', '-Dtest="${{ steps.selection.outputs.tests }}"'));
  assert.throws(() => verifyWorkflowSupplyChainRemediation(e4, changed.plan, changed.snapshot), /direct template expression|environment boundary/);

  changed = withMutatedWorkflow(basePlan, baseSnapshot, '.github/workflows/backend-ci.yml', (source) => source.replace(
    'on:\n  workflow_dispatch:',
    'on:\n  pull_request:\n    branches:\n      - main\n  workflow_dispatch:',
  ));
  assert.throws(() => verifyWorkflowSupplyChainRemediation(e4, changed.plan, changed.snapshot), /automatic workflow inventory/);

  changed = withMutatedWorkflow(basePlan, baseSnapshot, validationPath, (source) => source.replace('          - 3\n', ''));
  assert.throws(() => verifyWorkflowSupplyChainRemediation(e4, changed.plan, changed.snapshot), /physical Job count drift/);
});

test('R2B extension preserves the accepted legacy R2A/V2 canonical shape', () => {
  const plan = JSON.parse(load(planPath));
  const historical = historicalR2B(plan);
  const e4 = e4Fixture();
  e4.totalFindingCount = 203;
  e4.scanners.zizmor = {
    scanCompleted: true,
    rawReportRetained: false,
    findingCount: historical.length,
    findings: historical.map(({ sourceClass, findingId, ruleId, path, startLine, upstreamSeverity }) => ({
      sourceClass,
      findingId,
      ruleId,
      path,
      startLine,
      upstreamSeverity,
    })),
  };
  const triage = {
    repository: plan.repository,
    commitSha: e4.commitSha,
    contentSha256: '8'.repeat(64),
    cumulativeReviewedFindingCount: 7,
    remediatedHistoricalFindingCount: 2,
    decisions: historical.map((finding) => unresolved(finding.sourceClass, finding.findingId)),
  };
  const r2a = {
    repository: plan.repository,
    commitSha: e4.commitSha,
    contentSha256: plan.priorR2ACanonicalSha256,
    priorI4FindingSetSha256: plan.priorI4FindingSetSha256,
    remediatedFindings: plan.priorR2ARemediatedFindings,
  };
  const out = applyWorkflowSupplyChainReviews(triage, e4, i4Review(plan), r2a);
  assert.equal(out.schemaVersion, 'M6_PR_E_E3_I4_TRIAGE_V2');
  assert.equal(Object.hasOwn(out, 'sourceWorkflowRemediationCanonicalSha256'), false);
  assert.equal(out.reviewedFindingCount, 58);
  assert.equal(out.remediatedHistoricalFindingCount, 3);
  assert.equal(out.cumulativeReviewedFindingCount, 68);
  assert.equal(out.historicallyRemediatedFindingCount, 5);
  assert.equal(out.contentSha256, 'e59f52fb6e9b9b37f95f6b16c654c4c67adf247d16de163816dd30b6fd7ae6e4');
});

test('R2B remediation composes with R2A and retains the exact historical 61-finding I4 set', () => {
  const plan = JSON.parse(load(planPath));
  const e4 = e4Fixture();
  const snapshot = workflowSnapshot();
  const r2b = verifyWorkflowSupplyChainRemediation(e4, plan, snapshot);
  const r2a = {
    repository: plan.repository,
    commitSha: e4.commitSha,
    contentSha256: plan.priorR2ACanonicalSha256,
    priorI4FindingSetSha256: plan.priorI4FindingSetSha256,
    remediatedFindings: plan.priorR2ARemediatedFindings,
  };
  const review = i4Review(plan);
  const decisions = [
    ...Array.from({ length: 3 }, (_, index) => ({
      sourceClass: 'E4_SEMGREP',
      findingId: `not-applicable-${index}`,
      severityBand: 'UNKNOWN',
      disposition: 'NOT_APPLICABLE',
    })),
    ...Array.from({ length: 142 }, (_, index) => unresolved('E4_OSV_SCANNER', `unresolved-${index}`)),
  ];
  const triage = {
    repository: plan.repository,
    commitSha: e4.commitSha,
    contentSha256: '8'.repeat(64),
    cumulativeReviewedFindingCount: 7,
    remediatedHistoricalFindingCount: 2,
    decisions,
  };
  const out = applyWorkflowSupplyChainReviews(triage, e4, review, r2a, r2b);
  assert.equal(out.schemaVersion, 'M6_PR_E_E3_I4_TRIAGE_V3');
  assert.equal(out.historicalReviewedFindingCount, 61);
  assert.equal(out.reviewedFindingCount, 0);
  assert.equal(out.remediatedHistoricalFindingCount, 61);
  assert.equal(out.cumulativeReviewedFindingCount, 68);
  assert.equal(out.historicallyRemediatedFindingCount, 63);
  assert.equal(out.summary.dispositionCounts.APPLICABLE || 0, 0);
  assert.equal(out.summary.dispositionCounts.NOT_APPLICABLE, 3);
  assert.equal(out.summary.dispositionCounts.UNRESOLVED, 142);
  assert.equal(out.summary.releaseBlocked, true);
  assert.equal(out.summary.reasonCodes.includes('E3_APPLICABLE_FINDINGS_REQUIRE_REMEDIATION'), false);
});
