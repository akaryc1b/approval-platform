import assert from 'node:assert/strict';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import test from 'node:test';
import { executeEvaluationBrowserRepeat } from '../product-readiness/online-demo/evaluation-browser-repeat.mjs';

const source = { commitSha: 'a'.repeat(40), treeSha: 'b'.repeat(40) };
const id = n => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
function passing(ordinal) {
  return { kind: 'EVALUATION_BROWSER_BUSINESS_REHEARSAL', source: { ...source },
    status: 'TWO_BROWSER_PC_H5_BUSINESS_RESET_PASSED', checksPassed: true,
    cleanup: { status: 'PASSED' }, browserCleanup: { status: 'PASSED' }, elapsedMs: 100,
    created: { a: { instanceId: id(ordinal * 10), attachmentId: id(ordinal * 10 + 1) },
      b: { instanceId: id(ordinal * 10 + 2), attachmentId: id(ordinal * 10 + 3) } },
    paymentA: { status: 'EXACT_SIGNED_SANDBOX_PAYMENT_DELIVERED', eventIds: [id(ordinal * 10 + 4)],
      before: { generation: String(ordinal * 2).repeat(32) } },
    paymentB: { status: 'EXACT_SIGNED_SANDBOX_PAYMENT_DELIVERED', eventIds: [id(ordinal * 10 + 5)],
      before: { generation: String(ordinal * 2 + 1).repeat(32) } } };
}
// Real filesystem and actual repeat coordinator; Docker/Chromium/business results are fixtures.
function fixture(t) {
  const directory = mkdtempSync(resolve(tmpdir(), 'evaluation-repeat-test-'));
  t.after(() => {
    rmSync(directory, { recursive: true, force: true });
    rmSync(directory + '-browser-repeat-2', { recursive: true, force: true });
  });
  const calls = []; let time = 1000;
  const options = { smoke: { source }, directory, scenario: { fixture: true }, applicationRoots: {}, deadline: 500000 };
  const read = () => JSON.parse(readFileSync(resolve(directory, 'evaluation-browser-repeat.json'), 'utf8'));
  const rehearse = async input => {
    calls.push(input);
    const receipt = passing(calls.length);
    writeFileSync(resolve(input.directory, 'evaluation-browser-rehearsal.json'), JSON.stringify(receipt));
    return receipt;
  };
  return { options, calls, read, rehearse, setTime: value => { time = value; }, now: () => time,
    run(override = {}) { return executeEvaluationBrowserRepeat(options, { rehearse, now: () => time, ...override }); } };
}

test('two clean runs reuse the same image/asset input but keep separate evidence paths and identities', async t => {
  const f = fixture(t); const result = await f.run();
  assert.equal(result.status, 'TWO_CLEAN_BROWSER_RUNS_PASSED'); assert.equal(result.runs.length, 2);
  assert.equal(f.calls.length, 2); assert.equal(result.runs[0].evidenceDirectory, '.');
  assert.equal(resolve(f.options.directory, result.runs[1].evidenceDirectory), f.options.directory + '-browser-repeat-2');
  assert.equal(f.calls[0].directory, f.options.directory);
  assert.equal(f.calls[1].directory, f.options.directory + '-browser-repeat-2');
  for (const call of f.calls) {
    assert.equal(call.smoke, f.options.smoke); assert.equal(call.applicationRoots, f.options.applicationRoots);
    assert.equal(call.scenario, f.options.scenario); assert.equal(call.browser, true); assert.equal(call.maximumMs, 360000);
    assert.ok(existsSync(resolve(call.directory, 'evaluation-browser-rehearsal.json')));
  }
  assert.deepEqual(f.read(), result);
});
test('the next run waits for the previous clean completion rather than starting in parallel', async t => {
  const f = fixture(t); let release; let active = 0;
  const work = f.run({ rehearse: async input => {
    assert.equal(active++, 0);
    if (!f.calls.length) await new Promise(done => { release = done; });
    const receipt = await f.rehearse(input); active--; return receipt;
  } });
  await new Promise(done => setImmediate(done));
  assert.equal(active, 1); assert.equal(f.calls.length, 0);
  assert.equal(existsSync(f.options.directory + '-browser-repeat-2'), false);
  release(); await work; assert.equal(f.calls.length, 2); assert.equal(active, 0);
});
for (const failureAt of [1, 2]) {
  test(`run ${failureAt} failure stops immediately without retry and retains earlier results`, async t => {
    const f = fixture(t); let attempts = 0;
    await assert.rejects(f.run({ rehearse: async input => {
      attempts++;
      if (attempts === failureAt) {
        writeFileSync(resolve(input.directory, 'evaluation-browser-trace.json'), '{"failure":"SANITIZED"}');
        throw new Error('do not retain token=private');
      }
      return f.rehearse(input);
    } }), /^Error: BROWSER_REPEAT_FAILED$/u);
    assert.equal(attempts, failureAt); assert.equal(f.read().status, 'FAILED');
    assert.equal(f.read().runs.length, failureAt - 1); assert.equal(f.read().activeRun, failureAt);
    assert.doesNotMatch(JSON.stringify(f.read()), /token|private/u);
    const failedDirectory = failureAt === 1 ? f.options.directory : f.options.directory + '-browser-repeat-2';
    assert.ok(existsSync(resolve(failedDirectory, 'evaluation-browser-trace.json')));
  });
}
for (const mutate of [
  value => { value.cleanup.status = 'FAILED'; },
  value => { value.browserCleanup.status = 'FAILED'; },
  value => { value.checksPassed = false; },
  value => { value.source.treeSha = 'c'.repeat(40); },
  value => { value.source.commitSha = 'c'.repeat(40); },
  value => { value.status = 'TWO_SESSION_REAL_BUSINESS_API_RESET_PASSED'; },
  value => { delete value.created; },
  value => { value.paymentA.eventIds = []; },
]) {
  test('incomplete, wrong-source, API-only or unclean receipt cannot authorize another run', async t => {
    const f = fixture(t); let calls = 0;
    await assert.rejects(f.run({ rehearse: async () => { calls++; const receipt = passing(1); mutate(receipt); return receipt; } }), /BROWSER_REPEAT_FAILED/u);
    assert.equal(calls, 1); assert.equal(f.read().runs.length, 0); assert.equal(f.read().status, 'FAILED');
  });
}
for (const reused of ['instance', 'attachment', 'event', 'generation']) {
  test(`second run cannot reuse a prior ${reused}`, async t => {
    const f = fixture(t); let calls = 0;
    await assert.rejects(f.run({ rehearse: async () => {
      const value = passing(++calls); const first = passing(1);
      if (calls === 2) {
        if (reused === 'instance') value.created.a.instanceId = first.created.a.instanceId;
        if (reused === 'attachment') value.created.a.attachmentId = first.created.a.attachmentId;
        if (reused === 'event') value.paymentA.eventIds = first.paymentA.eventIds;
        if (reused === 'generation') value.paymentA.before.generation = first.paymentA.before.generation;
      }
      return value;
    } }), /BROWSER_REPEAT_FAILED/u);
    assert.equal(calls, 2); assert.equal(f.read().runs.length, 1); assert.equal(f.read().status, 'FAILED');
  });
}
test('remaining budget is recalculated and expired budget is not replaced by a fresh timeout', async t => {
  const f = fixture(t); f.options.deadline = 5000;
  await assert.rejects(f.run({ rehearse: async input => {
    assert.equal(input.maximumMs, 4000); const value = await f.rehearse(input); f.setTime(4500); return value;
  } }), /BROWSER_REPEAT_FAILED/u);
  assert.equal(f.calls.length, 1); assert.equal(f.read().activeRun, 2);
});
test('completion after the common deadline cannot be labeled a passing run', async t => {
  const f = fixture(t);
  await assert.rejects(f.run({ rehearse: async input => { const value = await f.rehearse(input); f.setTime(500001); return value; } }), /BROWSER_REPEAT_FAILED/u);
  assert.equal(f.calls.length, 1); assert.equal(f.read().runs.length, 0);
});
test('rerunning in an existing evidence directory cannot overwrite its passing summary', async t => {
  const f = fixture(t); await f.run(); const before = f.read();
  await assert.rejects(f.run(), { code: 'EEXIST' }); assert.deepEqual(f.read(), before); assert.equal(f.calls.length, 2);
});
test('a pre-existing browser receipt is preserved rather than silently reused', async t => {
  const f = fixture(t); const file = resolve(f.options.directory, 'evaluation-browser-rehearsal.json');
  writeFileSync(file, 'prior evidence'); await assert.rejects(f.run(), /BROWSER_REPEAT_FAILED/u);
  assert.equal(f.calls.length, 0); assert.equal(readFileSync(file, 'utf8'), 'prior evidence');
});
test('a pre-existing second-run directory stops before its contents are touched', async t => {
  const f = fixture(t); const directory = f.options.directory + '-browser-repeat-2'; mkdirSync(directory);
  writeFileSync(resolve(directory, 'keep.txt'), 'prior');
  await assert.rejects(f.run(), /BROWSER_REPEAT_FAILED/u); assert.equal(f.calls.length, 1);
  assert.equal(readFileSync(resolve(directory, 'keep.txt'), 'utf8'), 'prior');
});
