import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const documentPath = 'docs/m6/M6_F_FORMAL_ACCEPTANCE.md';

function read(relativePath) {
  const file = path.join(root, relativePath);
  assert.equal(existsSync(file), true, `${relativePath} must exist`);
  return readFileSync(file, 'utf8');
}

function escaped(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

const document = read(documentPath);

test('P8-G2 records the exact base accepted functional head and repository metadata', () => {
  for (const required of [
    'M6-F Formal Acceptance',
    'akaryc1b/approval-platform',
    '492a428627d3be707d5723350506302ca04841b0',
    '31278be0243f9ddb80d76dbd009631d8e844ea88',
    'agent/m6-f-controlled-automation-and-ai-governance',
    'agent/m6-f-p8-g2-formal-acceptance',
    '#88 — M6-F: controlled automation and AI governance',
    '#81 — [M6-F] Controlled Automation and AI Governance',
    'ahead `174`, behind `0`',
    'Open / Draft / mergeable / not merged',
    'Commit count at G2 start',
    '`174`',
    'Changed files at G2 start',
    '`143`',
    '`28245 / 28`',
    'unique `V50`; no `V51+`',
    '.github/workflows/approval-platform-validation.yml',
  ]) {
    assert.match(document, new RegExp(escaped(required)));
  }
  assert.match(document, /avoids a circular self-referencing commit/i);
  assert.match(document, /exact accepted G2 validation Head/i);
});

test('P8-G2 enumerates every accepted M6-F stage without authorizing M6-G', () => {
  for (const stage of [
    'R0',
    'P0',
    'P1',
    'P2',
    'P3',
    'P4',
    'P6-A',
    'P6-B',
    'P6-C',
    'P6-D',
    'P6-E',
    'P6-F',
    'P7-R0',
    'P7-A',
    'P7-B',
    'P7-C',
    'P7-D',
    'P8-R0',
    'P8-G1',
    'P8-G2',
  ]) {
    assert.match(document, new RegExp(`\\b${escaped(stage)}\\b`));
  }
  assert.match(document, /does not develop unrelated M6-G or M7 capability/i);
  assert.match(document, /M6-G implementation: prohibited/);
});

test('P8-G2 permanently binds the authority chain empty whitelist and skipped P5', () => {
  for (const required of [
    'AI advisory -> typed non-executable proposal -> fresh server policy/precondition evaluation -> fresh authorization preview -> explicit human confirmation -> existing application command service -> immutable audited result',
    'Provider -> direct command',
    'AI_IS_NOT_AN_OPERATOR',
    'EMPTY_PENDING_EXISTING_COMMAND_AUDIT',
    'P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND',
    'NON_EXECUTABLE_PROPOSAL',
    'NON_EXECUTABLE_CONFIRMATION',
    'ACTION_NOT_WHITELISTED',
  ]) {
    assert.match(document, new RegExp(escaped(required)));
  }
  assert.match(document, /does not invent, test-authorize or document-authorize a command/i);
});

test('P8-G2 accepts fresh evaluation explicit confirmation and durable lineage guarantees', () => {
  for (const required of [
    'trusted tenant context',
    'trusted operator context',
    'source evidence existence, identity and integrity',
    'current Action Whitelist version and exact Action definition',
    'kill-switch posture',
    'separation of duties',
    'command preconditions',
    'Production Reauthentication status',
    'explicit click intent',
    'single-use evidence',
    'registration idempotency',
    'exact Replay versus Conflict',
    'row locking',
    'revision CAS',
    'terminal winner uniqueness',
    'Cancellation with zero command attempt',
    'SUCCESS, FAILURE, PARTIAL and UNKNOWN with exactly one bounded attempt',
    'automatic_retry_allowed=false',
  ]) {
    assert.match(document, new RegExp(escaped(required), 'i'));
  }
  assert.match(document, /UNKNOWN remains terminal and non-retryable/);
  assert.match(document, /PARTIAL cannot be converted to SUCCESS/);
});

test('P8-G2 accepts versioned Provider safety and one shared Runtime control plane', () => {
  for (const required of [
    'Provider ID and version',
    'model Provider ID, model ID and model version',
    'Prompt template ID, version and hash',
    'policy ID, version and hash',
    'output-schema ID and version',
    'DNS and verified TLS before Secret material access',
    'one synchronous Provider attempt at most',
    'post-dispatch ambiguity becomes terminal UNKNOWN',
    'no raw response body in durable evidence',
    'one shared Runtime control plane',
    'Circuit Breaker',
    'RateLimiter',
    'Usage Ledger',
  ]) {
    assert.match(document, new RegExp(escaped(required), 'i'));
  }
  assert.match(document, /CI uses no real external Provider and no real Secret/);
  assert.match(document, /do not construct a second Runtime, Circuit, RateLimiter or Usage Ledger/);
});

test('P8-G2 accepts exactly six tenant READ GET-only no-store operations endpoints', () => {
  for (const endpoint of [
    'GET /api/approval/management/ai-governance/snapshot',
    'GET /api/approval/management/ai-governance/change-plan?operation=<CANARY|ROLLOUT|ROLLBACK>',
    'GET /api/approval/management/ai-governance/control-health',
    'GET /api/approval/management/ai-governance/usage',
    'GET /api/approval/management/ai-governance/history?from=<canonical Instant>&to=<canonical Instant>',
    'GET /api/approval/management/ai-governance/incident-readiness?from=<canonical Instant>&to=<canonical Instant>',
  ]) {
    assert.match(document, new RegExp(escaped(endpoint)));
  }
  for (const posture of [
    'RUNTIME_NOT_CONFIGURED',
    'OBSERVATION_READY_ADVISORY_ONLY',
    'ACTION_REQUIRED',
    'INCIDENT_BLOCKED',
  ]) {
    assert.match(document, new RegExp(posture));
  }
  assert.match(document, /management `READ` only/);
  assert.match(document, /GET-only/);
  assert.match(document, /no-store/);
  assert.match(document, /no request body/);
  assert.match(document, /no method override/);
  assert.match(document, /unable to create a Runtime Binding/);
  assert.match(document, /unable to call a Provider/);
});

test('P8-G2 accepts V49 V50 upgrade and PostgreSQL ownership without V51', () => {
  for (const required of [
    'V49 durable advisory evidence',
    'immutable hash-only approval-assistance evidence',
    'ACTIVE or TOMBSTONED state',
    'deferred Event/State consistency constraints',
    'V50 controlled-automation Lineage',
    'controlled-automation append-only Events',
    'one terminal transition',
    'fresh installation reaches unique V50',
    'historical upgrade paths reach V50',
    'five-thousand-instance/task upgrade rehearsal remains bounded',
    'no V51 exists',
  ]) {
    assert.match(document, new RegExp(escaped(required), 'i'));
  }

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
});

test('P8-G2 records fault concurrency and all nine manual incident rehearsals', () => {
  for (const required of [
    'DNS, TLS, connection, Secret, Admission, Cost and Rate pre-dispatch failures with zero Usage',
    'one exchange only',
    'exactly one HALF_OPEN probe',
    'read-only `REPEATABLE_READ` History',
    'PostgreSQL Lineage event/state rollback',
    'unique terminal winner',
    'same-tenant and multi-tenant Usage admission',
    'monotonic Circuit generation',
    'retry-splice rejection',
    'Runtime not configured',
    'healthy advisory Runtime',
    'Circuit OPEN',
    'Circuit HALF_OPEN',
    'tenant rate saturation',
    'global rate saturation',
    'durable-history version drift',
    'retention due',
    'post-dispatch timeout',
  ]) {
    assert.match(document, new RegExp(escaped(required), 'i'));
  }
  assert.match(document, /Rollback plans are review documents only/);
  assert.match(document, /do not rely on random probability or `Thread\.sleep`/);
});

test('P8-G2 records Web Mobile workflow and repository acceptance', () => {
  for (const required of [
    'PC and Mobile',
    'disabled Confirmation control',
    'Confirmation success is not command success',
    'Template comments, implementation notes and test markers are not rendered',
    'Java 21 / Maven core',
    'Persistence JDBC shard 0',
    'Java 21 / Maven / PostgreSQL aggregate',
    'Vben TypeScript / production build',
    'UniApp TypeScript / H5 / WeChat',
    'Repository hygiene',
    'read-only repository permissions',
    'no broad test skip',
    'no direct `main` modification',
  ]) {
    assert.match(document, new RegExp(escaped(required), 'i'));
  }

  const workflowRoot = path.join(root, '.github/workflows');
  const automatic = readdirSync(workflowRoot)
    .filter((name) => /\.ya?ml$/.test(name))
    .filter((name) => {
      const content = readFileSync(path.join(workflowRoot, name), 'utf8');
      return /^\s{0,4}(pull_request|push):\s*$/m.test(content);
    });
  assert.deepEqual(automatic, ['approval-platform-validation.yml']);
});

test('P8-G2 records exact P7 R0 G1 run job artifact and rebuilt evidence', () => {
  for (const exact of [
    '31070932544',
    '31078769144',
    '31079997571',
    '31080901527',
    '31082175396',
    '31084456213',
    '31084843048',
    '31085215490',
    '92562951620',
    '92562951685',
    '92562951853',
    '92562951671',
    '92562951681',
    '92563531928',
    '92562951702',
    '92562951628',
    '92562951626',
    '8961168294',
    '8961142159',
    '8961125127',
    '8961101718',
    '3d95726a57c1fdad573063dc0d15869d8b9e0fee5689499de2839cc0f3abc2ef',
    '6e2de7f94e2b38b6f273e3a5bf7b556665302901184c913f9d9f2c99fda43d93',
    'e48e71e687483955059744a7ff1834c68194bd04060db08d9c75707f4f792565',
    '22ce5d6ac871a54ecf0dc96b24a83e3ebd699c031013d1ccb2209c9d52e99b8c',
    '5201607304',
    '5201612285',
    '5201740440',
    '5202364889',
    '5202368056',
  ]) {
    assert.match(document, new RegExp(exact));
  }
  for (const count of [
    'Maven core: `1463 / 0 failures / 0 errors / 0 skipped`',
    'Persistence JDBC: `318 / 0 / 0 / 0`',
    'aggregate: `1781 / 0 / 0 / 0`',
    'architecture: `159/159`',
    'server: `266/266`',
    'AI Core: `204/204`',
    'OpenAI: `102/102`',
    'permanent M6 transport/G1 boundary: `163/163`',
    'JDBC selected classes: `79`',
    'Surefire reports: `78`',
    'duplicate selections: `0`',
  ]) {
    assert.match(document, new RegExp(escaped(count), 'i'));
  }
  assert.match(document, /All failures remain visible/);
  assert.match(document, /classification `Test Bug`/);
});

test('P8-G2 honestly records every permanent limitation', () => {
  for (const limitation of [
    'a non-empty production Action Whitelist',
    'an executable production Action',
    'Production Reauthentication',
    'approve, reject/return, transfer, withdraw, terminate or migrate commands',
    'automatic Retry or fallback',
    'automatic Rollback',
    'automatic Incident Notification',
    'automatic Retention Tombstone',
    'actual Provider billing',
    'durable P6-D cost-upper-bound history',
    'durable Circuit or Control Health time-series',
    'Canary, rollout, deployment or traffic mutation',
    'Provider, model, Prompt, Policy or Secret mutation',
    'direct Flowable or `ACT_*` access',
    'arbitrary HTTP, SQL, Shell, script or Connector execution',
    'Queue, Worker, Scheduler, Listener, Polling or autonomous execution',
  ]) {
    assert.match(document, new RegExp(escaped(limitation), 'i'));
  }
  assert.match(document, /accepted and permanent safety limitation/i);
});

test('P8-G2 defines exact Ready Merge post-main and Issue closure gates', () => {
  for (const required of [
    'P8-G3 is prohibited',
    'merge_method=merge',
    'expected_head_sha',
    'no squash',
    'no rebase',
    'no auto-merge',
    'no force merge',
    'no direct push to `main`',
    'push -> main',
    'Old PR Artifacts cannot substitute for main Artifacts',
    'Issue #81 must close with state reason `completed`',
    'Issue #82 may be unblocked only after Issue #81 is re-read as Closed / Completed',
    'P8_G2_DOCUMENTED_PENDING_EXACT_HEAD_PERMANENT_VALIDATION',
    'READY_MERGE_ISSUE_CLOSURE_PROHIBITED',
  ]) {
    assert.match(document, new RegExp(escaped(required), 'i'));
  }
  assert.doesNotMatch(document, /\b(TODO|TBD|FIXME)\b/);
});
