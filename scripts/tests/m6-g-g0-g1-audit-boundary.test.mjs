import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

function read(relativePath) {
  const file = path.join(root, relativePath);
  assert.equal(existsSync(file), true, `${relativePath} must exist`);
  return readFileSync(file, 'utf8');
}

const g0 = read('docs/m6/M6_G_REBASELINE_AND_SCOPE_FREEZE.md');
const audit = read('docs/m6/M6_G_CROSS_WORKSTREAM_AUDIT.md');
const matrix = read('docs/m6/M6_G_G1_BLOCKER_MATRIX.md');

test('M6-G G0 and G1 bind the exact current-main baseline and separate decisions', () => {
  assert.match(g0, /0cf6572770953a46fe5b16d15ecdff78cf607855/);
  assert.match(g0, /31106899863/);
  for (const artifact of ['8970028695', '8969997953', '8969969278', '8969944075']) {
    assert.match(g0, new RegExp(artifact));
  }
  for (const marker of [
    'M6_G_SCOPE_FROZEN',
    'NO_NEW_M6_PRODUCT_CAPABILITY',
    'NO_PRODUCTION_PROMOTION',
    'CI_BATCHING_ENABLED',
  ]) {
    assert.match(g0, new RegExp(marker));
  }
  assert.match(audit, /M6_G_G1_AUDIT_COMPLETE/);
  assert.match(audit, /UNRESOLVED_CORRECTABLE_DEFECTS=0/);
  assert.match(audit, /M6_PRODUCTION_READINESS_BLOCKED/);
  assert.doesNotMatch(`${g0}\n${audit}\n${matrix}`, /\b(TODO|TBD|FIXME)\b/);
});

test('G1 covers every M6 workstream and A-L audit surface', () => {
  for (const heading of [
    'A — M6-A Connector Foundation',
    'B — M6-B SDK and Event Ecosystem',
    'C — M6-C Template and Component Ecosystem',
    'D — M6-D AI Foundation',
    'E — Governed AI Approval Assistance',
    'F — Controlled Automation and AI Governance',
    'G — Cross-workstream Authority Audit',
    'H — Persistence and Migration Audit',
    'I — Operations and Observability',
    'J — Web and Mobile Audit',
    'K — Workflow, Repository and Dependency/Security Audit',
    'L — Classification and Gate Decision',
  ]) {
    assert.match(audit, new RegExp(heading.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
  }
  for (const classification of [
    'PASS',
    'NON_BLOCKING_LIMITATION',
    'CORRECTABLE_DEFECT',
    'PRODUCTION_READINESS_BLOCKER',
    'PARENT_CLOSURE_BLOCKER',
  ]) {
    assert.match(matrix, new RegExp(classification));
  }
  assert.match(matrix, /Unresolved `CORRECTABLE_DEFECT` count: `0`/);
});

test('M6-A through M6-D retain connector event template and AI safety boundaries', () => {
  const a = read('docs/m6/M6_A_FINAL_ACCEPTANCE.md');
  const aBlockers = read('docs/m6/M6_A_PRODUCTION_BLOCKER_CATALOG.md');
  const b = read('docs/m6/M6_B_FINAL_ACCEPTANCE.md');
  const c = read('docs/m6/M6_C_FORMAL_ACCEPTANCE.md');
  const d = read('docs/m6/M6_D_FORMAL_ACCEPTANCE.md');

  assert.match(a, /PRODUCTION_CONNECTOR_EXECUTION_NOT_AUTHORIZED/);
  assert.match(aBlockers, /B01/);
  assert.match(aBlockers, /B20/);
  assert.match(aBlockers, /Production connector execution remains `BLOCKED`/);
  assert.match(b, /durable subscription|event[^\n]*delivery|production event persistence/i);
  assert.match(b, /real HTTP|network transport/i);
  assert.match(c, /tenant-local [`]?DRAFT/i);
  assert.match(c, /dynamic|remote component/i);
  assert.match(d, /ADVISORY/);
  assert.match(d, /UNVERIFIED_ADVISORY/);
  assert.match(d, /needsHumanReview/);
});

test('M6-E performs one attempt and revalidates task before evidence', () => {
  const service = read(
    'apps/server/src/main/java/io/github/akaryc1b/approval/api/ApprovalAssistanceGenerationService.java',
  );
  const v49 = read(
    'server-modules/approval-persistence-jdbc/src/main/resources/db/migration/V49__create_ai_approval_assistance_durable_evidence.sql',
  );
  const post = service.indexOf('Optional<PendingTaskDetails> postInvocation');
  const evidence = service.indexOf('ApprovalAssistanceDurableEvidence evidence');
  const store = service.indexOf('evidenceStore.store(evidence)');
  assert.ok(post >= 0 && evidence > post && store > evidence);
  assert.match(service, /GenerationStatus\.STALE_TASK/);
  assert.match(v49, /provider_attempts between 0 and 1/);
  assert.match(v49, /not retry_attempted and not post_invocation_fallback_attempted/);
  assert.match(v49, /evidence events are append-only/);
  assert.doesNotMatch(v49, /\b(raw_request|raw_response|prompt_body|secret_value|advisory_text)\b/i);
});

test('M6-F stays empty-whitelist and preserves PostgreSQL rounding', () => {
  const whitelist = read('docs/m6/M6_F_ACTION_WHITELIST_DECISION.md');
  const lineage = read(
    'server-modules/approval-persistence-jdbc/src/main/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcControlledAutomationLineageStore.java',
  );
  const confirmation = read(
    'apps/web/overlay/apps/web-ele/src/components/approval/ControlledAutomationConfirmationBoundary.vue',
  );
  assert.match(whitelist, /EMPTY_PENDING_EXISTING_COMMAND_AUDIT/);
  assert.match(whitelist, /P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND/);
  assert.match(whitelist, /AI_IS_NOT_AN_OPERATOR/);
  assert.match(lineage, /NANOS_PER_MICROSECOND = 1_000L/);
  assert.match(lineage, /HALF_MICROSECOND_NANOS/);
  assert.match(lineage, /remainder < HALF_MICROSECOND_NANOS/);
  assert.doesNotMatch(lineage, /truncatedTo\(ChronoUnit\.MICROS\)/);
  assert.match(confirmation, /UNAVAILABLE/);
  assert.match(confirmation, /disabled/);
});

test('PC and Mobile generation remain explicit and advisory-only', () => {
  for (const file of [
    'apps/web/overlay/apps/web-ele/src/components/approval/ApprovalAssistancePanel.vue',
    'apps/mobile/overlay/src/components/approval/ApprovalAssistancePanel.vue',
  ]) {
    const panel = read(file);
    assert.match(panel, /ADVISORY/);
    assert.match(panel, /UNVERIFIED_ADVISORY/);
    assert.match(panel, /needsHumanReview/);
    assert.match(panel, /generateApprovalAssistance/);
    assert.match(panel, /@click/);
    assert.equal(
      [...panel.matchAll(/generateApprovalAssistance\s*\(/g)].length,
      1,
      `${file} must have one explicit generation call`,
    );
  }
});

test('migration and workflow boundaries remain V50 and one automatic workflow', () => {
  const resourceRoot = path.join(
    root,
    'server-modules/approval-persistence-jdbc/src/main/resources',
  );
  const versions = [
    path.join(resourceRoot, 'db/migration'),
    path.join(resourceRoot, 'm6f/db/migration'),
  ].flatMap((directory) => readdirSync(directory))
    .map((name) => /^V(\d+)__.+\.sql$/.exec(name))
    .filter(Boolean)
    .map((match) => Number(match[1]));
  assert.equal(Math.max(...versions), 50);
  assert.equal(versions.filter((version) => version === 49).length, 1);
  assert.equal(versions.filter((version) => version === 50).length, 1);
  assert.equal(versions.some((version) => version >= 51), false);

  const workflowRoot = path.join(root, '.github/workflows');
  const automatic = readdirSync(workflowRoot)
    .filter((name) => /\.ya?ml$/.test(name))
    .filter((name) => {
      const content = readFileSync(path.join(workflowRoot, name), 'utf8');
      return /^\s{0,4}(pull_request|push):\s*$/m.test(content);
    });
  assert.deepEqual(automatic, ['approval-platform-validation.yml']);
});

test('G1 records Dependabot maintenance without inventing zero security alerts', () => {
  for (const pr of ['#1', '#2', '#3', '#4', '#5', '#6', '#7', '#72', '#73', '#84']) {
    assert.match(g0, new RegExp(pr.replace('#', '#')));
    assert.match(audit, new RegExp(pr.replace('#', '#')));
  }
  assert.match(
    audit,
    /dedicated Code Scanning, Secret Scanning and Dependabot Security Alert inventories are not exposed/,
  );
  assert.match(matrix, /Dedicated security alert inventory/);
  assert.match(matrix, /PRODUCTION_READINESS_BLOCKER/);
});

test('G1 freezes production blockers rather than implementing them', () => {
  for (const blocker of [
    'EMPTY_PENDING_EXISTING_COMMAND_AUDIT',
    'P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND',
    'Production Reauthentication',
    'actual Provider billing',
    'durable cost history',
    'durable Circuit',
    'Canary',
    'traffic mutation',
  ]) {
    assert.match(`${audit}\n${matrix}`, new RegExp(blocker, 'i'));
  }
  assert.match(audit, /NO_NEW_M6_PRODUCT_CAPABILITY/);
  assert.match(audit, /NO_PRODUCTION_PROMOTION/);
  assert.match(audit, /ISSUE_82_REMAINS_OPEN/);
  assert.match(audit, /ISSUE_62_REMAINS_OPEN/);
});
