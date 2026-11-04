import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { formProcessReceiptMarkdown } from '../../apps/web/overlay/playground/__tests__/e2e/product-readiness-form-process-receipt.ts';

const uuid = '12345678-1234-4234-8234-123456789abc';
const receipt = () => ({ formKey: `browser-form-${uuid}`, definitionKey: `browser-form-${uuid}`,
  formDraftId: uuid, processDraftId: uuid, packageVersion: 1,
  formHash: 'a'.repeat(64), uiSchemaHash: 'b'.repeat(64), packageHash: 'c'.repeat(64) });
test('closed synthetic receipt emits only reviewed identifiers/version/hashes as markdown', () => {
  const data = receipt();
  assert.equal(formProcessReceiptMarkdown(data), '# Isolated form/process artifact receipt\n\n'
    + ['formKey','formDraftId','processDraftId','definitionKey','packageVersion','formHash','uiSchemaHash','packageHash']
      .map(key => `- ${key}: ${data[key]}`).join('\n') + '\n');
});
test('malformed, unexpected, inherited and accessor fields cannot reach receipt attachment', () => {
  const canary = 'PRIVATE_CANARY password=do-not-publish';
  const cases = [null, [], {}, { ...receipt(), unexpected: canary }, { ...receipt(), [Symbol('extra')]: canary },
    Object.assign(Object.create({ secret: canary }), receipt())];
  for (const key of Object.keys(receipt())) {
    cases.push({ ...receipt(), [key]: canary });
    const missing = receipt(); delete missing[key]; cases.push(missing);
  }
  cases.push({ ...receipt(), packageVersion: 2 }, { ...receipt(), formHash: 'A'.repeat(64) });
  const getter = receipt(); Object.defineProperty(getter, 'formKey', { get() { throw new Error(canary); } }); cases.push(getter);
  for (const value of cases) assert.throws(() => formProcessReceiptMarkdown(value), /^Error: Invalid form\/process receipt$/);
});
test('runtime attaches bounded markdown only, never the arbitrary lifecycle result objects', () => {
  const source = readFileSync(new URL('../../apps/web/overlay/playground/__tests__/e2e/product-readiness-pc-h5-runtime.spec.ts', import.meta.url), 'utf8');
  assert.match(source, /formProcessReceiptMarkdown\(\{/);
  assert.match(source, /attach\('form-process-lifecycle', \{ body: receipt, contentType: 'text\/markdown' \}\)/);
  assert.doesNotMatch(source, /JSON\.stringify\(\{ form, process \}\)|contentType: 'application\/json'/);
});

test('all runtime tests have closed flow phases and new helpers retain operation checkpoints', () => {
  const root = new URL('../../apps/web/overlay/playground/__tests__/e2e/', import.meta.url);
  const source = readFileSync(new URL('product-readiness-pc-h5-runtime.spec.ts', root), 'utf8');
  for (const phase of ['RUNTIME_HANDOFF', 'RUNTIME_COLLABORATION', 'RUNTIME_BUSINESS_LOW', 'RUNTIME_BUSINESS_HIGH', 'PC_DELEGATION'])
    assert.ok(source.includes(phase), phase);
  for (const [file, phases] of [
    ['admin-calendar', ['PC_CALENDAR_SETUP', 'PC_CALENDAR_CREATE', 'PC_CALENDAR_SAVE', 'PC_CALENDAR_VERSIONS', 'PC_POLICY_CREATE', 'PC_POLICY_VERSIONS']],
    ['form-package', ['PC_FORM_SETUP', 'PC_FORM_CREATE', 'PC_FORM_EDIT', 'PC_FORM_PREVIEW', 'PC_FORM_PUBLISH', 'PC_FORM_RUNTIME']],
    ['process-design', ['PC_DESIGNER_SETUP', 'PC_DESIGNER_CREATE', 'PC_DESIGNER_SAVE_RACE', 'PC_DESIGNER_PREFLIGHT', 'PC_DESIGNER_PUBLISH']],
  ]) {
    const helper = readFileSync(new URL(`product-readiness-pc-${file}.ts`, root), 'utf8');
    for (const phase of phases) assert.ok(helper.includes(`phase.enter('${phase}')`), phase);
    assert.doesNotMatch(helper.slice(helper.lastIndexOf('finally')), /phase.enter/);
  }
});
