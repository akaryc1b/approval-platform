import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import vm from 'node:vm';
import test from 'node:test';
const root = '../../apps/web/overlay/apps/web-ele/src/api/approval/';
const process = readFileSync(new URL(`${root}process-design.ts`, import.meta.url), 'utf8');
const form = readFileSync(new URL(`${root}form-design.ts`, import.meta.url), 'utf8');
function extract(source, name) {
  const start = source.indexOf(`export function ${name}(`);
  const end = source.indexOf('\nexport ', start + 1);
  assert.ok(start >= 0);
  return source.slice(start, end < 0 ? undefined : end).replace(/^export /, '');
}
function harness() {
  const calls = []; let ids = 0;
  const context = vm.createContext({
    approvalCommandHeaders: () => ({ 'Idempotency-Key': `command-${++ids}` }),
    approvalRequest: (path, options) => { calls.push({ path, ...options }); return { ok: true }; },
  });
  vm.runInContext(stripTypeScriptTypes([
    extract(process, 'approvalPublicationReason'),
    extract(process, 'preflightApprovalPublication'),
    extract(form, 'publishFormDesignDraft'),
  ].join('\n')), context);
  return { context, calls };
}
test('form publication and process preflight validate reasons before issuing requests', () => {
  const { context: api, calls } = harness();
  for (const reason of ['', 'short', 'x'.repeat(513), 'valid reason\ncontrol', '😀'.repeat(8), 'valid reason\ud800']) {
    assert.throws(() => api.publishFormDesignDraft('draft', 2, 1, reason));
    assert.throws(() => api.preflightApprovalPublication({ draftId: 'draft' }, reason));
  }
  assert.throws(() => api.preflightApprovalPublication({ draftId: 'draft' }));
  assert.equal(calls.length, 0);
});
test('form publication and preflight send independent idempotency keys and normalized explicit reasons', () => {
  const { context: api, calls } = harness();
  api.publishFormDesignDraft('draft', 2, 1, '  Ａccepted reason  ');
  api.preflightApprovalPublication({ draftId: 'draft', expectedRevision: 2 }, '  Ａccepted reason  ');
  assert.equal(calls.length, 2);
  for (const call of calls) {
    assert.equal(call.method, 'POST');
    assert.equal(call.headers['X-Approval-Operation-Reason'], 'Accepted reason');
    assert.ok(call.headers['Idempotency-Key']);
    assert.equal(JSON.parse(call.body).expectedRevision, 2);
  }
  assert.notEqual(calls[0].headers['Idempotency-Key'], calls[1].headers['Idempotency-Key']);
});
