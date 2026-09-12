import assert from 'node:assert/strict';
import { stripTypeScriptTypes } from 'node:module';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const paths = ['../../apps/web/overlay/apps/web-ele/src/platform/approval/evaluation-session.ts',
  '../../apps/mobile/overlay/src/platform/approval/evaluation-session.ts'];
const actors = ['demo-employee', 'demo-manager', 'demo-finance-reviewer',
  'demo-finance-approver-a', 'demo-finance-approver-b'];
const session = { actorId: actors[0], actors: actors.map(id => ({ id, displayName: id })),
  csrfToken: 'a'.repeat(43), expiresInSeconds: 60,
  businessAccess: 'PURCHASE_PAYMENT_WORKFLOW', scope: 'DISPOSABLE_EVALUATION_CONTROL_PLANE' };
const files = paths.map(path => readFileSync(new URL(path, import.meta.url), 'utf8'));

test('PC and H5 use the same corrected protocol source', () => assert.equal(files[0], files[1]));
for (const [index, source] of files.entries()) {
  const kind = index === 0 ? 'PC' : 'H5';
  const module = await import('data:text/javascript;base64,' + Buffer.from(stripTypeScriptTypes(source)).toString('base64'));
  function fixture(t) {
    const calls = [];
    const client = module.createEvaluationBrowserSession({ origin: 'https://evaluation.invalid', now: () => 1000,
      fetch: async (target, init) => {
        calls.push({ target, init });
        return new Response(JSON.stringify(target === '/evaluation/session' ? session : { ok: true }));
      } });
    t.after(() => client.dispose());
    return { client, calls };
  }
  test(`${kind}: rejected paths do not reach session or business transport`, async t => {
    const { client, calls } = fixture(t);
    for (const target of [undefined, null, 5, {}, '', '/approval/%2e%2e/admin', '/approval/a%2Fb',
      '/approval/a%3fb', '/approval/../admin', '/approval/a#fragment', '//external.invalid/api',
      '/approval//tasks', '/approval/\\tasks']) {
      await assert.rejects(client.fetch(target), error => error.code === 'EVALUATION_PATH_REJECTED');
    }
    assert.equal(calls.length, 0);
  });
  test(`${kind}: encoded query values remain unchanged and cannot change the destination`, async t => {
    const { client, calls } = fixture(t);
    const target = '/approval/tasks/pending?keyword=a%20b';
    assert.equal((await client.fetch(target)).status, 200);
    const business = calls.filter(call => call.target !== '/evaluation/session');
    assert.equal(business.length, 1); assert.equal(business[0].target, '/api' + target);
    assert.equal(business[0].init.credentials, 'same-origin');
    assert.equal(business[0].init.headers.get('X-Evaluation-CSRF'), session.csrfToken);
  });
  test(`${kind}: Headers.forEach still rejects authority headers and FormData preserves bytes`, async t => {
    const { client, calls } = fixture(t);
    await assert.rejects(client.fetch('/approval/tasks/pending', { headers: { 'X-Tenant-Id': 'spoofed' } }),
      error => error.code === 'EVALUATION_HEADER_REJECTED');
    assert.equal(calls.length, 0);
    const form = new FormData(); form.append('file', new File(['invoice bytes'], 'invoice.txt', { type: 'text/plain' }));
    const response = await client.fetch('/approval/attachments', { method: 'POST', body: form,
      headers: { 'Idempotency-Key': 'attachment-native-regression' } });
    assert.equal(response.status, 200);
    const upload = calls.find(call => call.target === '/api/approval/attachments');
    assert.notEqual(upload.init.body, form); assert.equal(upload.init.headers.has('Content-Type'), false);
    assert.equal(await upload.init.body.get('file').text(), 'invoice bytes');
  });
}
