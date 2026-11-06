import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import vm from 'node:vm';
import test from 'node:test';
import { cloneApprovalDesignerJson, snapshotApprovalDesignerDraft } from '../../apps/web/overlay/apps/web-ele/src/views/approval/designer/designer-conflict.ts';

const source = readFileSync(new URL('../../apps/web/overlay/apps/web-ele/src/views/approval/designer/use-approval-designer.ts', import.meta.url), 'utf8');
const snapshot = draft => ({ name: draft.name, formPackageVersion: draft.formPackage.packageVersion, definition: structuredClone(draft.definition) });
const makeDraft = (id = 'A') => ({ draftId: id, name: `draft-${id}`, revision: 1, status: 'DRAFT', definition: { startNodeId: 'start', nodes: [{id:'start',kind:'START'}] }, formPackage: { packageVersion: 1 } });
function deferred() { let resolve, reject; const promise = new Promise((yes,no) => {resolve=yes;reject=no;}); return {promise,resolve,reject}; }
function functionSource(name, next) { const start = source.indexOf(`  ${name}`); const end = source.indexOf(`\n  ${next}`, start); assert.ok(start >= 0 && end > start); return source.slice(start,end); }
function harness() {
  const writes=[], reads=[], errors=[], successes=[]; let scheduled=0;
  class ApiError extends Error { constructor(code) { super(code);this.code=code; } }
  const context=vm.createContext({
    draft:{value:makeDraft()}, editable:{value:true}, saveState:{value:'dirty'}, saveTimer:undefined,
    pendingSaveRequest:undefined, documentGeneration:0, suspended:false,
    baseSnapshot:{value:undefined}, conflict:{value:undefined}, selectedNodeId:{value:'start'},
    decisions:{value:{}},undoStack:{value:[]},redoStack:{value:[]},nodeSearch:{value:''},nodeKindFilter:{value:[]},nodeRenderLimit:{value:120},collapsedBranchNodeIds:{value:new Set()},
    structuredClone,clearTimeout, snapshotApprovalDesignerDraft:snapshot,
    updateApprovalDesignDraft(id,payload) {const result=deferred();writes.push({id,payload,...result});return result.promise;},
    findApprovalDesignDraft(id) {const result=deferred();reads.push({id,...result});return result.promise;},
    loadDrafts:async()=>{},confirmDiscardChanges:async()=>true,clearResults(){},
    ElMessage:{success:message=>successes.push(message),warning:message=>errors.push(message)},
    showError:error=>errors.push(error.message),ApprovalDesignApiError:ApiError,
    analyzeDesignerConflict:()=>({canAutoMerge:true}),
    markChanged(){context.saveState.value='dirty';scheduled+=1;},
    applySnapshot(value){context.draft.value.name=value.name;context.draft.value.definition=structuredClone(value.definition);context.draft.value.formPackage.packageVersion=value.formPackageVersion;},
  });
  const blocks=[functionSource('async function save(', 'async function reload()'),functionSource('async function openDraft(', 'async function createDraft('),functionSource('async function captureConflict(', 'async function ensureSaved()')];
  if(source.includes('  function invalidateDocument()')) blocks.push(functionSource('function invalidateDocument()', 'async function loadDrafts()'));
  vm.runInContext(stripTypeScriptTypes(blocks.join('\n')),context);
  return {context,writes,reads,errors,successes,ApiError,get scheduled(){return scheduled;}};
}
function saved(write) {return {...makeDraft(write.id),name:write.payload.name,definition:structuredClone(write.payload.definition),revision:2};}

test('late successful save preserves subsequent local edits and current server revision',async()=>{const h=harness();const p=h.context.save();h.context.draft.value.name='new-local';h.context.draft.value.definition.nodes[0].name='new-node';h.context.saveState.value='dirty';h.writes[0].resolve(saved(h.writes[0]));assert.equal(await p,false);assert.equal(h.context.draft.value.name,'new-local');assert.equal(h.context.draft.value.definition.nodes[0].name,'new-node');assert.equal(h.context.draft.value.revision,2);assert.equal(h.context.saveState.value,'dirty');assert.equal(h.scheduled,1);assert.equal(h.context.baseSnapshot.value.name,'draft-A');});
test('save request snapshot cannot be mutated by later editing',async()=>{const h=harness();const p=h.context.save();h.context.draft.value.definition.nodes[0].name='later';assert.equal(h.writes[0].payload.definition.nodes[0].name,undefined);h.writes[0].resolve(saved(h.writes[0]));await p;});
test('dirty watcher cannot start overlapping save while first request is pending',async()=>{const h=harness();const p=h.context.save();h.context.saveState.value='dirty';const second=h.context.save();assert.equal(h.writes.length,1);assert.equal(await second,false);h.writes[0].resolve(saved(h.writes[0]));await p;});
test('navigation invalidates late save success without replacing new draft',async()=>{const h=harness();const p=h.context.save();h.context.invalidateDocument();h.context.draft.value=makeDraft('B');h.context.saveState.value='saved';h.writes[0].resolve(saved(h.writes[0]));await p;assert.equal(h.context.draft.value.draftId,'B');assert.equal(h.context.saveState.value,'saved');assert.deepEqual(h.successes,[]);});
test('invalidated request failure cannot contaminate the current document',async()=>{const h=harness();const p=h.context.save();h.context.invalidateDocument();h.context.draft.value=makeDraft('B');h.context.saveState.value='saved';h.writes[0].reject(new Error('obsolete'));await p;assert.equal(h.context.saveState.value,'saved');assert.deepEqual(h.errors,[]);});
test('newest open request wins even if older response arrives last',async()=>{const h=harness();const a=h.context.openDraft('A',{force:true});const b=h.context.openDraft('B',{force:true});h.reads[1].resolve(makeDraft('B'));assert.equal(await b,true);h.reads[0].resolve(makeDraft('A'));assert.equal(await a,false);assert.equal(h.context.draft.value.draftId,'B');});
test('normal save updates clean document once',async()=>{const h=harness();const p=h.context.save();h.writes[0].resolve(saved(h.writes[0]));assert.equal(await p,true);assert.equal(h.context.draft.value.revision,2);assert.equal(h.context.saveState.value,'saved');assert.equal(h.successes.length,1);});
test('current revision conflict still fetches server version for governed merge',async()=>{const h=harness();const p=h.context.save();h.writes[0].reject(new h.ApiError('APPROVAL_DESIGN_REVISION_CONFLICT'));await new Promise(resolve=>setImmediate(resolve));assert.equal(h.reads.length,1);h.reads[0].resolve({...makeDraft(),revision:3});assert.equal(await p,false);assert.equal(h.context.saveState.value,'conflict');assert.equal(h.context.conflict.value.serverDraft.revision,3);});

test('JSON protocol snapshot accepts reactive proxies and detaches all nested edits',()=>{
  const raw=makeDraft(); const proxied=new Proxy(raw,{get(target,key){const value=target[key];return value&&typeof value==='object'?new Proxy(value,{}):value;}});
  const result=snapshotApprovalDesignerDraft(proxied);
  assert.deepEqual(result,snapshot(raw));
  raw.definition.nodes[0].name='after-snapshot';assert.equal(result.definition.nodes[0].name,undefined);
});
test('unmount invalidates the shared document request generation',()=>{
  const lifecycle=source.slice(source.indexOf('  onBeforeUnmount(() => {'),source.indexOf('  function invalidateDocument()'));
  assert.match(lifecycle,/invalidateDocument\(\)/);
});

test('proxy-safe JSON cloning retains prototype/property rejection',()=>{
  for (const key of ['__proto__','constructor','prototype']) {
    assert.throws(()=>cloneApprovalDesignerJson(new Proxy(JSON.parse(`{"${key}":{}}`),{})),/不安全的属性名/);
    assert.throws(()=>cloneApprovalDesignerJson({toJSON(){return JSON.parse(`{"${key}":{}}`);}}),/不安全的属性名/);
  }
});

test('client snapshot application failure restores change tracking and remains retryable',async()=>{
  const h=harness();const p=h.context.save();h.context.draft.value.name='new-local';
  h.context.applySnapshot=()=>{throw new Error('client application failure');};
  h.writes[0].resolve(saved(h.writes[0]));assert.equal(await p,false);
  assert.equal(h.context.suspended,false);assert.equal(h.context.saveState.value,'failed');
  assert.equal(h.context.draft.value.name,'new-local');assert.equal(h.context.draft.value.revision,1);
  assert.equal(h.context.pendingSaveRequest,undefined);assert.deepEqual(h.errors,['client application failure']);
  const retry=h.context.save();assert.equal(h.writes.length,2);h.writes[1].resolve(saved(h.writes[1]));await retry;
});
test('failed navigation after invalidating a save leaves old document retryable with unchanged CAS',async()=>{
  const h=harness();const p=h.context.save();const open=h.context.openDraft('B',{force:true});
  h.reads[0].reject(new Error('replacement read failed'));assert.equal(await open,false);
  assert.equal(h.context.draft.value.draftId,'A');assert.equal(h.context.saveState.value,'failed');
  assert.equal(h.context.pendingSaveRequest,undefined);assert.equal(h.context.suspended,false);
  h.writes[0].resolve(saved(h.writes[0]));assert.equal(await p,false);
  assert.equal(h.context.draft.value.revision,1);assert.equal(h.context.saveState.value,'failed');
  const retry=h.context.save();assert.equal(h.writes[1].payload.expectedRevision,1);
  h.writes[1].reject(new h.ApiError('APPROVAL_DESIGN_REVISION_CONFLICT'));
  await new Promise(resolve=>setImmediate(resolve));h.reads[1].resolve({...makeDraft(),revision:2});
  assert.equal(await retry,false);assert.equal(h.context.saveState.value,'conflict');
  assert.equal(h.context.conflict.value.serverDraft.revision,2);
});
