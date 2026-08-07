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

const formal = read('docs/m6/M6_OVERALL_FORMAL_ACCEPTANCE.md');
const blockers = read('docs/m6/M6_G_PRODUCTION_READINESS_BLOCKER_MATRIX.md');
const g0 = read('docs/m6/M6_G_REBASELINE_AND_SCOPE_FREEZE.md');
const g1 = read('docs/m6/M6_G_CROSS_WORKSTREAM_AUDIT.md');
const g2 = read('docs/m6/M6_G_END_TO_END_PRODUCTION_READINESS_ACCEPTANCE.md');
const whitelist = read('docs/m6/M6_F_ACTION_WHITELIST_DECISION.md');
const reauthentication = read(
  'server-modules/approval-ai-core/src/main/java/io/github/akaryc1b/approval/ai/core/'
    + 'ControlledAutomationReauthenticationVerifier.java',
);
const workflow = read('.github/workflows/approval-platform-validation.yml');
const aggregator = read('scripts/tests/m6-ai-transport-review-boundary.test.mjs');

test('G3 binds exact repository, branch, PR, issues and planned final compare', () => {
  for (const token of [
    '0cf6572770953a46fe5b16d15ecdff78cf607855',
    '007c973eeffdc07c94ee46602afb8827be2dc231',
    'agent/m6-g-overall-formal-acceptance-and-production-readiness',
    'Pull Request | `#93`',
    'Tracking Issue | `#82`',
    'Parent Issue | `#62`',
    'Parallel database blocker | `#91` / Draft PR `#92`',
    'Planned G3 final commit count relative to base | `10`',
    'Planned G3 final changed-file count | `10`',
  ]) assert.equal(formal.includes(token), true, `missing exact G3 identity: ${token}`);
  assert.match(formal, /Exact Final Head binding/);
  assert.match(formal, /G3_FINAL_RUN_EXTERNAL_BINDING_REQUIRED/);
});

test('G3 formal acceptance covers all M6 workstreams and corrections', () => {
  for (const heading of [
    'M6-A Connector acceptance',
    'M6-B SDK and Event acceptance',
    'M6-C Template and Component acceptance',
    'M6-D AI Foundation acceptance',
    'M6-E Governed AI Approval Assistance acceptance',
    'M6-F Controlled Automation and Governance acceptance',
  ]) assert.match(formal, new RegExp(heading));
  for (const pr of ['#67', '#68', '#69', '#70', '#83', '#88', '#74', '#75', '#77', '#89', '#90']) {
    assert.equal(formal.includes(pr), true, `${pr} must remain recorded`);
  }
});

test('G3 freezes authority, whitelist, P5 and reauthentication boundaries', () => {
  for (const token of [
    'Provider -> direct command',
    'AI_IS_NOT_AN_OPERATOR',
    'EMPTY_PENDING_EXISTING_COMMAND_AUDIT',
    'P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND',
    'Production Reauthentication honestly `UNAVAILABLE`',
    'no qualifying executable Application Command',
    'no automatic Retry, Rollback, Notification or Retention',
  ]) assert.equal(formal.toLowerCase().includes(token.toLowerCase()), true, token);
  assert.match(whitelist, /Action count: `0`/);
  assert.match(whitelist, /EMPTY_PENDING_EXISTING_COMMAND_AUDIT/);
  assert.match(whitelist, /P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND/);
  assert.match(reauthentication, /static ControlledAutomationReauthenticationVerifier unavailable\(\)/);
  assert.match(reauthentication, /Verification\.unavailable\(\)/);
});

test('G3 covers isolation, evidence, persistence, operations, clients and faults', () => {
  for (const heading of [
    'Tenant, Secret, Event and Template integrity',
    'Data minimization and durable evidence',
    'Persistence, clean install and upgrade',
    'Dual-database commitment re-read',
    'Rollback and Incident Response',
    'Operations and observability',
    'Web and Mobile',
    'Fault, concurrency and PostgreSQL acceptance',
    'Workflow and repository acceptance',
    'Review and security status',
  ]) assert.match(formal, new RegExp(heading));
  assert.match(formal, /controlled-automation PostgreSQL concurrency remains `8\/8`/i);
  assert.match(formal, /nearest-microsecond regression remains `7\/7`/i);
});

test('G3 records the new MySQL parent closure blocker without claiming support', () => {
  assert.match(formal, /Issue #91 and Draft PR #92/);
  assert.match(formal, /DUAL_DATABASE_COMMITMENT_RESTORED/);
  assert.match(formal, /MYSQL_8_4_NOT_YET_PRODUCTION_SUPPORTED/);
  assert.match(blockers, /PRB-15/);
  assert.match(blockers, /MySQL 8\.4 production compatibility/);
  assert.match(blockers, /`PARENT_CLOSURE_BLOCKER`/);
  assert.doesNotMatch(`${formal}\n${blockers}`, /MYSQL_8_4_PRODUCTION_SUPPORTED/);
});

test('G3 binds exact G0 G1 G2 runs, jobs, artifacts and reconstruction', () => {
  for (const run of ['31150986907', '31151203020', '31152842432', '31153142533']) {
    assert.match(formal, new RegExp(run));
  }
  for (const job of [
    '92786836119', '92786836133', '92786836145', '92786836150',
    '92786836163', '92786836171', '92786836179', '92786836202', '92787231533',
  ]) assert.match(formal, new RegExp(job));
  for (const artifact of ['8984120607', '8984103025', '8984087438', '8984069702']) {
    assert.match(formal, new RegExp(artifact));
  }
  for (const token of [
    'Maven Core `1463',
    'Persistence JDBC `325',
    'aggregate `1788',
    'architecture `159`',
    'server `266`',
    'AI Core `204`',
    'OpenAI `102`',
    'Node aggregate `218/218`',
    'JDBC selected/unique `80/80`',
    'Surefire reports `79`',
    'duplicate selections `0`',
    'non-abstract missing reports `0`',
  ]) assert.equal(formal.includes(token), true, token);
});

test('G3 retains failed evidence and independent corrections', () => {
  assert.match(formal, /Batch A Run #1329/);
  assert.match(formal, /Batch B Run #1337/);
  assert.match(formal, /Every failed Run and Artifact remains retained/);
  assert.match(formal, /Neither failed Head was rerun/);
  assert.match(formal, /No test was skipped/);
  assert.match(formal, /no permission, authority or capability was widened/i);
});

test('G3 blocker matrix keeps production and parent closure decisions separate', () => {
  const parentRows = blockers.split('\n')
    .filter(line => line.startsWith('| `PRB-') && line.includes('`PARENT_CLOSURE_BLOCKER`'));
  assert.equal(parentRows.length, 15);
  assert.match(blockers, /\| `PARENT_CLOSURE_BLOCKER` \| `15` \|/);
  assert.match(blockers, /\| `PRODUCTION_READINESS_BLOCKER` \| `2` \|/);
  assert.match(blockers, /\| `CORRECTABLE_DEFECT` \| `0` \|/);
  assert.match(blockers, /M6_G_ACCEPTANCE_PASSED/);
  assert.match(blockers, /M6_PRODUCTION_READINESS_BLOCKED/);
});

test('G3 decisions protect Issues 82, 62, 91, 13 and 14', () => {
  for (const marker of [
    'ISSUE_82_REMAINS_OPEN',
    'ISSUE_62_REMAINS_OPEN',
    'ISSUE_91_REMAINS_OPEN',
    'ISSUE_13_REMAINS_OPEN',
    'ISSUE_14_REMAINS_OPEN',
    'NO_NEW_M6_PRODUCT_CAPABILITY',
    'NO_PRODUCTION_PROMOTION',
  ]) {
    assert.match(formal, new RegExp(marker));
    assert.match(blockers, new RegExp(marker));
  }
  assert.doesNotMatch(`${formal}\n${blockers}`, /ISSUE_(?:82|62|91)_CLOSED/);
});

test('G3 preserves one automatic workflow and unique V49 V50', () => {
  const workflowRoot = path.join(root, '.github/workflows');
  const automatic = readdirSync(workflowRoot)
    .filter(name => /\.ya?ml$/.test(name))
    .filter(name => /^\s{0,4}(pull_request|push):\s*$/m.test(
      readFileSync(path.join(workflowRoot, name), 'utf8'),
    ));
  assert.deepEqual(automatic, ['approval-platform-validation.yml']);
  assert.match(workflow, /permissions:\s*\n\s*contents: read/);
  assert.match(workflow, /Verify M6 AI transport review boundaries/);

  const resourceRoot = path.join(
    root,
    'server-modules/approval-persistence-jdbc/src/main/resources',
  );
  const versions = [
    path.join(resourceRoot, 'db/migration'),
    path.join(resourceRoot, 'm6f/db/migration'),
  ].flatMap(directory => readdirSync(directory))
    .map(name => /^V(\d+)__.+\.sql$/.exec(name))
    .filter(Boolean)
    .map(match => Number(match[1]));
  assert.equal(versions.filter(version => version === 49).length, 1);
  assert.equal(versions.filter(version => version === 50).length, 1);
  assert.equal(versions.some(version => version >= 51), false);
});

test('G3 prerequisite records remain accepted and exact', () => {
  assert.match(g0, /M6_G_SCOPE_FROZEN/);
  assert.match(g1, /M6_G_G1_AUDIT_COMPLETE/);
  assert.match(g1, /UNRESOLVED_CORRECTABLE_DEFECTS=0/);
  assert.match(g2, /Scenario result: `30 \/ 30 PASS`/);
  assert.match(g2, /M6_G_END_TO_END_ACCEPTANCE_PASSED/);
  assert.match(g2, /M6_PRODUCTION_READINESS_BLOCKED/);
});

test('G3 boundary is permanently imported and contains no authority promotion', () => {
  assert.match(
    aggregator,
    /import '\.\/m6-g-overall-formal-acceptance-boundary\.test\.mjs';/,
  );
  const records = `${formal}\n${blockers}`;
  assert.doesNotMatch(records, /\b(TODO|TBD|FIXME)\s*:/);
  assert.doesNotMatch(records, /M6_PRODUCTION_READY(?!NESS_BLOCKED)/);
  assert.doesNotMatch(records, /PROVIDER_TO_COMMAND_AUTHORIZED|EXECUTABLE_ACTION_AUTHORIZED/);
  assert.match(formal, /G3 adds `scripts\/tests\/m6-g-overall-formal-acceptance-boundary\.test\.mjs` with exactly `12` permanent assertions/);
  assert.match(formal, /expected successful G3 Node aggregate is `230\/230`/);
});
