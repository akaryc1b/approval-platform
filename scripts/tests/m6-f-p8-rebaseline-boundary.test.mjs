import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import './m6-f-p8-g1-audit-boundary.test.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const documentPath = 'docs/m6/M6_F_P8_R0_FINAL_REBASELINE_SCOPE_FREEZE.md';

function read(relativePath) {
  const file = path.join(root, relativePath);
  assert.equal(existsSync(file), true, `${relativePath} must exist`);
  return readFileSync(file, 'utf8');
}

const document = read(documentPath);

test('P8-R0 freezes the exact accepted P7 baseline without main drift', () => {
  assert.match(document, /P8_R0_IMPLEMENTED_PENDING_EXACT_HEAD_PERMANENT_VALIDATION/);
  assert.match(document, /492a428627d3be707d5723350506302ca04841b0/);
  assert.match(document, /71bfd111d4c73c9b467dd5702b56c87e29add51a/);
  assert.match(document, /ahead `163`, behind `0`/);
  assert.match(document, /merge base equals current `main`/);
  assert.match(document, /Open \/ Draft \/ mergeable \/ not merged/);
  assert.match(document, /commits[^\n]*`163`/i);
  assert.match(document, /changed files[^\n]*`139`/i);
  assert.match(document, /`27108 \/ 28`/);
  assert.doesNotMatch(document, /\b(TODO|TBD|FIXME)\b/);
});

test('P8-R0 binds final P7 run artifacts reviews and protected issues', () => {
  assert.match(document, /31079997571/);
  assert.match(document, /Run Number: `1304`/);
  for (const job of [
    '92546416981',
    '92546417507',
    '92546418976',
    '92546417534',
    '92546417140',
    '92546895038',
    '92546417245',
    '92546416976',
    '92546417059',
  ]) {
    assert.match(document, new RegExp(job));
  }
  for (const artifact of [
    '8959119034',
    '8959101188',
    '8959079296',
    '8959063576',
  ]) {
    assert.match(document, new RegExp(artifact));
  }
  assert.match(document, /Reviews[^\n]*none/);
  assert.match(document, /`REQUEST_CHANGES`[^\n]*none/);
  assert.match(document, /Unresolved Review Threads[^\n]*none/);
  for (const issue of ['Issue #81', 'Issue #82', 'Issue #62', 'Issue #13', 'Issue #14']) {
    assert.match(document, new RegExp(issue));
  }
  assert.match(document, /PR #83[^\n]*Merged \/ Closed/);
});

test('P8-R0 permanently freezes the empty whitelist and non-executing authority', () => {
  assert.match(document, /EMPTY_PENDING_EXISTING_COMMAND_AUDIT/);
  assert.match(document, /P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND/);
  assert.match(document, /AI_IS_NOT_AN_OPERATOR/);
  assert.match(document, /Provider -> direct command/);
  assert.match(document, /No executable production Action/);
  for (const prohibited of [
    'approve',
    'reject/return',
    'transfer',
    'withdraw',
    'terminate',
    'migrate',
    'automatic retry',
    'automatic retry, fallback, Incident notification or Retention Tombstone',
    'Queue, Worker, Scheduler, Listener, Polling or autonomous execution',
  ]) {
    assert.match(
      document,
      new RegExp(prohibited.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i'),
    );
  }
});

test('P8-R0 freezes exactly six tenant-scoped read-only operations endpoints', () => {
  for (const endpoint of [
    '/api/approval/management/ai-governance/snapshot',
    '/api/approval/management/ai-governance/change-plan',
    '/api/approval/management/ai-governance/control-health',
    '/api/approval/management/ai-governance/usage',
    '/api/approval/management/ai-governance/history',
    '/api/approval/management/ai-governance/incident-readiness',
  ]) {
    assert.match(
      document,
      new RegExp(endpoint.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')),
    );
  }
  assert.match(document, /tenant-scoped, GET-only, `no-store`, non-mutating and non-executing/);
});

test('P8-R0 permits only audit evidence and exact blocker corrections', () => {
  assert.match(document, /P8-G1\/G2 audit and Formal Acceptance documents/);
  assert.match(document, /minimal Correction/);
  assert.match(document, /fix only the proven root cause/);
  assert.match(document, /retain fail-closed behavior/);
  assert.match(document, /not expand the whitelist, command surface, Provider authority/);
  assert.match(document, /P8_G1_PROHIBITED/);
  assert.match(document, /READY_MERGE_ISSUE_CLOSURE_PROHIBITED/);
});

test('P8-R0 retains unique V50 and the sole automatic workflow', () => {
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
