import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import vm from 'node:vm';
import test from 'node:test';

const source = readFileSync(new URL('../../apps/web/overlay/apps/web-ele/src/views/approval/delegations/index.vue', import.meta.url), 'utf8');
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
function harness(kind) {
  const pending = [];
  const errors = [];
  const state = { value: [] };
  const loading = { value: false };
  const api = () => { const item = deferred(); pending.push(item); return item.promise; };
  const context = vm.createContext({
    candidates: state, rules: state, candidateLoading: loading, loading,
    includeRevoked: { value: false }, findApprovalIdentityCandidates: api,
    findDelegationRules: api, ElMessage: { error: error => errors.push(error) },
    errorMessage: error => error.message,
  });
  const generations = source.match(/^let (?:candidateRequest|rulesRequest) = 0;$/gm) || [];
  const start = source.indexOf('async function loadCandidates(');
  const end = source.indexOf('async function openCreateDialog(');
  vm.runInContext(stripTypeScriptTypes(`${generations.join('\n')}\n${source.slice(start, end)}`), context);
  return { run: kind === 'candidates' ? context.loadCandidates : context.loadRules, pending, state, loading, errors };
}
for (const kind of ['candidates', 'rules']) {
  test(`${kind}: a stale success cannot replace the newer result`, async () => {
    const h = harness(kind);
    const old = h.run('old'); const latest = h.run('new');
    h.pending[1].resolve(['new']); await latest;
    h.pending[0].resolve(['old']); await old;
    assert.deepEqual(h.state.value, ['new']);
  });
  test(`${kind}: a stale error cannot clear results or surface an obsolete error`, async () => {
    const h = harness(kind);
    const old = h.run('old'); const latest = h.run('new');
    h.pending[1].resolve(['new']); await latest;
    h.pending[0].reject(new Error('old failure')); await old;
    assert.deepEqual(h.state.value, ['new']); assert.deepEqual(h.errors, []);
  });
  test(`${kind}: an old completion does not dismiss the newer loading state`, async () => {
    const h = harness(kind);
    const old = h.run('old'); const latest = h.run('new');
    h.pending[0].resolve(['old']); await old;
    assert.equal(h.loading.value, true);
    h.pending[1].resolve(['new']); await latest;
    assert.equal(h.loading.value, false);
  });
}
