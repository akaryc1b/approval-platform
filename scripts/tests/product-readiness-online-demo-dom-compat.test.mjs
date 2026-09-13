import assert from 'node:assert/strict';
import { test } from 'node:test';
import { stripTypeScriptTypes } from 'node:module';
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

// UniApp does not enable DOM.Iterable. Exercise native forEach operations on
// actual client source without iterator methods or any application tsconfig change.
test('session multipart and header validation work without DOM iterators', async t => {
  const directory = mkdtempSync(resolve(tmpdir(), 'evaluation-dom-compat-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const source = readFileSync(new URL('../../apps/mobile/overlay/src/platform/approval/evaluation-session.ts', import.meta.url), 'utf8');
  const target = resolve(directory, 'client.mjs');
  writeFileSync(target, stripTypeScriptTypes(source.replaceAll('import.meta.env', '({})')));
  const { createEvaluationBrowserSession } = await import(pathToFileURL(target));
  const NativeHeaders = globalThis.Headers; const NativeFormData = globalThis.FormData;
  class NonIterableHeaders extends NativeHeaders {}
  class NonIterableFormData extends NativeFormData {}
  Object.defineProperty(NonIterableHeaders.prototype, Symbol.iterator, { value: undefined });
  Object.defineProperty(NonIterableFormData.prototype, Symbol.iterator, { value: undefined });
  globalThis.Headers = NonIterableHeaders; globalThis.FormData = NonIterableFormData;
  t.after(() => { globalThis.Headers = NativeHeaders; globalThis.FormData = NativeFormData; });
  const ids = ['demo-employee', 'demo-manager', 'demo-finance-reviewer', 'demo-finance-approver-a', 'demo-finance-approver-b'];
  const session = { actorId: ids[0], actors: ids.map(id => ({ id, displayName: id })), csrfToken: 'a'.repeat(43),
    expiresInSeconds: 60, businessAccess: 'PURCHASE_PAYMENT_WORKFLOW', scope: 'DISPOSABLE_EVALUATION_CONTROL_PLANE' };
  const calls = [];
  const client = createEvaluationBrowserSession({ origin: 'https://evaluation.example.invalid', now: () => 1000,
    fetch: async (path, init) => { calls.push({ path, init });
      return new Response(JSON.stringify(path === '/evaluation/session' ? session : { ok: true })); } });
  t.after(() => client.dispose());
  const form = new FormData();
  form.append('file', new File(['without DOM.Iterable'], 'invoice.txt', { type: 'text/plain' }));
  await client.fetch('/approval/attachments', { method: 'POST', body: form,
    headers: { 'Idempotency-Key': 'no-iterator' } });
  const sent = calls.find(c => c.path.startsWith('/api')).init;
  assert.equal(await sent.body.get('file').text(), 'without DOM.Iterable');
  assert.equal(sent.headers.get('Idempotency-Key'), 'no-iterator');
  assert.equal(sent.headers.has('X-Operator-Id'), false);
  await assert.rejects(client.fetch('/approval/tasks/pending', { headers: { 'X-Operator-Id': 'untrusted' } }));
});
