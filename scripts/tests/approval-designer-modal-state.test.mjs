import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import vm from 'node:vm';
import test from 'node:test';

const source = readFileSync(new URL('../../apps/web/overlay/apps/web-ele/src/views/approval/designer/use-approval-designer.ts', import.meta.url), 'utf8');
function deferred() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
const makeDraft = id => ({ draftId: id, revision: 1, status: 'DRAFT', name: id, formPackage: { packageVersion: 1 }, definition: { nodes: [{ id: 'start', next: 'node' }, { id: 'node', name: 'Review', kind: 'APPROVAL', next: 'end' }, { id: 'end' }] } });
function harness() {
  const document = makeDraft('A'), confirmations = [], writes = [], errors = []; let history = 0, refreshes = 0;
  const context = vm.createContext({ draft: { value: document }, editable: { value: true }, documentGeneration: 0,
    selectedNode: { value: document.definition.nodes[1] }, selectedNodeId: { value: 'node' }, saveState: { value: 'dirty' },
    topology: { value: { byId: new Map(document.definition.nodes.map(node => [node.id, node])) } },
    snapshotApprovalDesignerDraft: draft => JSON.parse(JSON.stringify(draft)),
    describeApprovalDesignerDeletion: () => ({ deletable: true, incomingNodeIds: ['start'] }),
    confirmDiscardChanges: async () => true,
    ElMessageBox: { confirm: () => { const d = deferred(); confirmations.push(d); return d.promise; } },
    ElMessage: { info() {} }, showError: error => errors.push(error),
    remember: () => history++, replaceTarget: (node, previous, next) => { assert.equal(node.next, previous); node.next = next; },
    archiveApprovalDesignDraft: (id, revision) => { const d = deferred(); writes.push({ id, revision, ...d }); return d.promise; },
    loadDrafts: async () => { refreshes++; },
  });
  for (const [name, next] of [['async function archive()', 'function addNode('], ['async function deleteNode()', 'function moveNode(']]) {
    const start = source.indexOf(`  ${name}`), end = source.indexOf(`\n  ${next}`, start);
    assert.ok(start >= 0 && end > start);
    vm.runInContext(stripTypeScriptTypes(source.slice(start, end)), context);
  }
  return { context, confirmations, writes, errors, document, get history() { return history; }, get refreshes() { return refreshes; } };
}
const tick = () => new Promise(resolve => setImmediate(resolve));

for (const action of ['deleteNode', 'archive']) {
  test(`${action}: navigation while confirmation pending does not mutate replacement`, async () => {
    const h = harness(), pending = h.context[action](); await tick();
    const replacement = makeDraft('B'); h.context.documentGeneration++; h.context.draft.value = replacement;
    h.confirmations[0].resolve(); await tick();
    if (h.writes.length) h.writes[0].resolve({ ...h.document, status: 'ARCHIVED' });
    await pending; assert.equal(h.context.draft.value, replacement); assert.equal(h.writes.length, 0); assert.equal(h.history, 0);
    assert.equal(replacement.definition.nodes.length, 3);
  });
  test(`${action}: editing during confirmation invalidates the described action`, async () => {
    const h = harness(), pending = h.context[action](); await tick(); h.document.name = 'new local edit';
    h.confirmations[0].resolve(); await tick(); if (h.writes.length) h.writes[0].resolve({ ...h.document, status: 'ARCHIVED' });
    await pending; assert.equal(h.writes.length, 0); assert.equal(h.history, 0); assert.equal(h.document.definition.nodes.length, 3);
  });
  test(`${action}: cancel is handled without mutation or error`, async () => {
    const h = harness(), pending = h.context[action](); await tick(); h.confirmations[0].reject('cancel');
    await pending; assert.equal(h.writes.length, 0); assert.equal(h.history, 0); assert.deepEqual(h.errors, []);
  });
}
test('delete applies one confirmed reconnect and history entry', async () => {
  const h = harness(), pending = h.context.deleteNode(); h.confirmations[0].resolve(); await pending;
  assert.equal(h.history, 1); assert.equal(h.document.definition.nodes.length, 2); assert.equal(h.document.definition.nodes[0].next, 'end');
});
test('archive response cannot replace a newer document or newer local edits', async () => {
  for (const navigate of [true, false]) {
    const h = harness(), pending = h.context.archive(); await tick(); h.confirmations[0].resolve(); await tick();
    assert.equal(h.writes.length, 1); assert.equal(h.writes[0].id, 'A'); assert.equal(h.writes[0].revision, 1);
    if (navigate) { h.context.documentGeneration++; h.context.draft.value = makeDraft('B'); }
    else h.context.draft.value.name = 'new local edit';
    const current = h.context.draft.value; h.writes[0].resolve({ ...h.document, status: 'ARCHIVED' }); await pending;
    assert.equal(h.context.draft.value, current); assert.equal(h.context.saveState.value, 'dirty');
  }
});
test('archive unchanged document accepts server response and refreshes list', async () => {
  const h = harness(), pending = h.context.archive(); await tick(); h.confirmations[0].resolve(); await tick();
  const archived = { ...h.document, status: 'ARCHIVED', revision: 2 }; h.writes[0].resolve(archived); await pending;
  assert.equal(h.context.draft.value, archived); assert.equal(h.refreshes, 1);
});

test('readonly or unmounted document cannot confirm pending delete/archive', async () => {
  for (const action of ['deleteNode', 'archive']) {
    for (const unmount of [true, false]) {
      const h = harness(), pending = h.context[action](); await tick();
      if (unmount) h.context.documentGeneration++; else h.context.editable.value = false;
      h.confirmations[0].resolve(); await pending;
      assert.equal(h.history, 0); assert.equal(h.writes.length, 0);
    }
  }
});
test('archive captures document before the separate unsaved-change confirmation', async () => {
  const h = harness(), discard = deferred(); h.context.confirmDiscardChanges = () => discard.promise;
  const pending = h.context.archive(); h.context.documentGeneration++; h.context.draft.value = makeDraft('B');
  discard.resolve(true); await pending; assert.equal(h.confirmations.length, 0); assert.equal(h.writes.length, 0);
});
test('obsolete archive failure does not contaminate a newer document', async () => {
  const h = harness(), pending = h.context.archive(); await tick(); h.confirmations[0].resolve(); await tick();
  h.context.documentGeneration++; h.context.draft.value = makeDraft('B');
  h.writes[0].reject(new Error('old request failed')); await pending; assert.deepEqual(h.errors, []);
});
test('current archive failure remains visible and leaves draft intact', async () => {
  const h = harness(), pending = h.context.archive(); await tick(); h.confirmations[0].resolve(); await tick();
  const error = new Error('server conflict'); h.writes[0].reject(error); await pending;
  assert.equal(h.context.draft.value, h.document); assert.deepEqual(h.errors, [error]);
});
