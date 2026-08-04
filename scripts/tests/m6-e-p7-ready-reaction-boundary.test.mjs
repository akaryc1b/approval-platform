import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

function source(relativePath) {
  const file = path.join(root, relativePath);
  assert.equal(existsSync(file), true, `${relativePath} must exist`);
  return readFileSync(file, 'utf8');
}

const correction = source(
  'docs/m6/M6_E_P7_READY_REACTION_STATUS_CORRECTION.md',
);

test('P7 Ready reaction correction records the exact blocked transition', () => {
  assert.match(correction, /b257346f06b9bde76633e0eb5b52af8ce023ca17/);
  assert.match(correction, /30898060255/);
  assert.match(correction, /438247587/);
  assert.match(correction, /chatgpt-codex-connector\[bot\]/);
  assert.match(correction, /reaction content: `eyes`/);
  assert.match(correction, /2026-08-04T10:02:24Z/);
  assert.match(correction, /merge was not performed/i);
  assert.match(correction, /returned to Draft immediately/i);
  assert.match(correction, /removal call was blocked/i);
  assert.match(correction, /does not claim that the reaction was removed/i);
});

test('P7 corrected gate permits only one exact connector status reaction', () => {
  assert.match(correction, /no human-authored PR reaction is permitted/i);
  assert.match(correction, /no reviewer, collaborator or other bot reaction is permitted/i);
  assert.match(correction, /no reaction content other than exact `eyes` is permitted/i);
  assert.match(correction, /at most one top-level reaction may exist/i);
  assert.match(correction, /actor must be exact `chatgpt-codex-connector\[bot\]`/i);
  assert.match(correction, /reaction ID must remain `438247587`/i);
  assert.match(correction, /Any additional reaction[\s\S]*blocks Ready or merge/i);
  assert.match(correction, /does not classify arbitrary bot reactions as acceptable/i);
});

test('P7 reaction correction preserves every substantive merge gate', () => {
  assert.match(correction, /exact branch Head and current `main`/i);
  assert.match(correction, /behind zero and mergeable true/i);
  assert.match(correction, /exact permanent workflow and artifacts/i);
  assert.match(correction, /no requested reviewer or requested change/i);
  assert.match(correction, /no actionable comment/i);
  assert.match(correction, /no unresolved review thread/i);
  assert.match(correction, /ordinary Merge Commit only/i);
  assert.match(correction, /mandatory natural post-main verification/i);
  assert.match(correction, /cannot influence AI Provider behavior or acquire repository authority/i);
});

test('P7 reaction correction requires new permanent validation and recheck', () => {
  assert.match(correction, /new natural four-job pull-request workflow/i);
  assert.match(correction, /All four artifacts must be independently SHA-256 exact/i);
  assert.match(correction, /marked Ready again/i);
  assert.match(correction, /exactly one or zero top-level PR reactions/i);
  assert.match(correction, /no human or additional bot reaction/i);
  assert.match(correction, /If the connector creates a second reaction[\s\S]*return to Draft/i);
  assert.match(correction, /not merge authorization by itself/i);
});

test('P7 reaction correction adds no product, migration or workflow capability', () => {
  assert.match(correction, /changes no production Java, TypeScript, migration or workflow/i);
  assert.match(
    correction,
    /no Provider, endpoint, Prompt, model, Secret, retry, fallback, Queue, Worker, Scheduler/i,
  );
  assert.match(correction, /no automation proposal or executable action/i);

  const migrationRoot = path.join(
    root,
    'server-modules/approval-persistence-jdbc/src/main/resources/db/migration',
  );
  const migrations = readdirSync(migrationRoot);
  assert.equal(migrations.filter(name => /^V49__/.test(name)).length, 1);
  assert.equal(migrations.some(name => /^V(?:5[0-9]|[6-9][0-9])__/.test(name)), false);

  const workflowRoot = path.join(root, '.github/workflows');
  const automatic = readdirSync(workflowRoot)
    .filter(name => /\.ya?ml$/.test(name))
    .filter(name => {
      const content = readFileSync(path.join(workflowRoot, name), 'utf8');
      return /^\s{0,4}(pull_request|push):\s*$/m.test(content);
    });
  assert.deepEqual(automatic, ['approval-platform-validation.yml']);
});

test('permanent transport review loads the Ready reaction correction boundary', () => {
  const aggregator = source('scripts/tests/m6-ai-transport-review-boundary.test.mjs');
  assert.match(
    aggregator,
    /import '\.\/m6-e-p7-ready-reaction-boundary\.test\.mjs';/,
  );
  assert.match(correction, /EXACT_CONNECTOR_EYES_ONLY_NON_ACTIONABLE/);
  assert.match(correction, /NO_HUMAN_OR_ADDITIONAL_REACTION_ALLOWED/);
  assert.match(correction, /AI_IS_NOT_AN_OPERATOR/);
});
