import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync, chmodSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import test from 'node:test';
import { classifyEvaluationStartupLog, observeEvaluationStartupFailure }
  from '../product-readiness/online-demo/evaluation-startup-diagnostics.mjs';
import { runEvaluationDocker } from '../product-readiness/online-demo/evaluation-docker-slots.mjs';
import { executeEvaluationBusinessRehearsal }
  from '../product-readiness/online-demo/evaluation-business-rehearsal.mjs';

function scope() {
  const namespace = 'a'.repeat(32); const slotId = 'slot-a';
  const slot = { namespace, slotId, generation: 'b'.repeat(32),
    containers: { backend: { name: `ap-evaluation-${namespace}-${slotId}-backend`, id: 'c'.repeat(64) } } };
  const actual = { Id: slot.containers.backend.id, Name: '/' + slot.containers.backend.name,
    Config: { Env: ['SECRET=not-for-receipts'], Labels: {
      'io.approval.evaluation.owner': namespace, 'io.approval.evaluation.slot': slotId,
      'io.approval.evaluation.generation': slot.generation, 'io.approval.evaluation.role': 'backend' } },
    State: { Running: false, OOMKilled: false, Restarting: false, ExitCode: 1, Error: 'secret-state-error' } };
  return { slot, actual };
}

test('diagnostic classification emits only fixed categories and known bean names', () => {
  const log = "APPLICATION FAILED TO START\nError creating bean with name 'onlineEvaluationBusinessIdentity'\n"
    + 'Caused by: org.springframework.beans.factory.BeanCurrentlyInCreationException: bearer TOP-SECRET\n'
    + 'jdbc:postgresql://private-host/db?password=SECRET\nCookie: opaque-private-session\n';
  const value = classifyEvaluationStartupLog(log);
  assert.deepEqual(value, { logs: 'CLASSIFIED', signals: ['APPLICATION_START_FAILED', 'BEAN_CYCLE'],
    beans: ['onlineEvaluationBusinessIdentity'] });
  assert.doesNotMatch(JSON.stringify(value), /SECRET|private-host|Cookie|password|opaque-private/u);
  assert.deepEqual(classifyEvaluationStartupLog('private arbitrary failure with no known class'),
    { logs: 'CLASSIFIED', signals: [], beans: [] });
  for (const text of [null, {}, 'x'.repeat(262145)]) {
    assert.deepEqual(classifyEvaluationStartupLog(text), { logs: 'UNAVAILABLE', signals: [], beans: [] });
  }
});

test('collector inspects exact ownership before bounded read-only logs', async () => {
  const { slot, actual } = scope(); const calls = [];
  const value = await observeEvaluationStartupFailure({ slot, run: async (args, options) => {
    calls.push({ args, options });
    return args[0] === 'container' ? JSON.stringify([actual]) : 'java.lang.OutOfMemoryError: secret';
  } });
  assert.equal(value.inspection, 'OWNED');
  assert.deepEqual(value.state, { running: false, oomKilled: false, restarting: false, exitCode: 1 });
  assert.deepEqual(value.signals, ['OUT_OF_MEMORY']);
  assert.deepEqual(calls, [
    { args: ['container', 'inspect', slot.containers.backend.id], options: { timeoutMs: 1500 } },
    { args: ['logs', '--tail', '160', slot.containers.backend.id], options: { timeoutMs: 1500 } },
  ]);
  assert.doesNotMatch(JSON.stringify(value), /secret|Env|Labels|password/u);
});

test('foreign owner, generation, name or container ID prevents log access', async () => {
  for (const mutate of [v => { v.Id = 'd'.repeat(64); }, v => { v.Name = '/foreign'; },
    v => { v.Config.Labels['io.approval.evaluation.generation'] = 'd'.repeat(32); },
    v => { v.Config.Labels['io.approval.evaluation.owner'] = 'd'.repeat(32); },
    v => { v.Config.Labels['io.approval.evaluation.slot'] = 'slot-b'; },
    v => { v.Config.Labels['io.approval.evaluation.role'] = 'postgres'; }]) {
    const { slot, actual } = scope(); mutate(actual); let calls = 0;
    const result = await observeEvaluationStartupFailure({ slot, run: async args => {
      calls += 1; assert.equal(args[0], 'container'); return JSON.stringify([actual]);
    } });
    assert.equal(calls, 1); assert.equal(result.inspection, 'OWNERSHIP_REJECTED');
    assert.equal(result.logs, 'NOT_READ'); assert.deepEqual(result.signals, []);
  }
});

test('invalid input and unavailable diagnostics cannot introduce a false success or raw error', async () => {
  for (const slot of [null, {}, { ...scope().slot, namespace: '0'.repeat(32) }]) {
    const result = await observeEvaluationStartupFailure({ slot, run: () => assert.fail('no command') });
    assert.equal(result.inspection, 'UNAVAILABLE');
  }
  const result = await observeEvaluationStartupFailure({ slot: scope().slot,
    run: async () => { throw new Error('credential-must-not-escape'); } });
  assert.equal(result.inspection, 'UNAVAILABLE'); assert.doesNotMatch(JSON.stringify(result), /credential/u);
});

test('actual child runner collects both log streams without exposing stderr for normal commands', async t => {
  const directory = mkdtempSync(resolve(tmpdir(), 'evaluation-diagnostic-runner-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const executable = resolve(directory, 'docker');
  writeFileSync(executable, `#!${process.execPath}\nprocess.stdout.write('stdout'); process.stderr.write('stderr');\n`);
  chmodSync(executable, 0o700);
  const prior = process.env.PATH;
  process.env.PATH = `${directory}:${prior}`;
  try {
    assert.equal(await runEvaluationDocker(['info']), 'stdout');
    assert.equal(await runEvaluationDocker(['logs', '--tail', '160', 'c'.repeat(64)]), 'stdout\nstderr');
  } finally { process.env.PATH = prior; }
});

for (const status of ['PASSED', 'FAILED', 'NOT_CREATED', undefined, 'secret-unknown-status']) {
  test(`failed startup cleanup preserves an acknowledged status, never a passing rehearsal: ${String(status)}`, async t => {
    const directory = mkdtempSync(resolve(tmpdir(), 'evaluation-startup-rehearsal-'));
    t.after(() => rmSync(directory, { recursive: true, force: true }));
    const source = { commitSha: 'a'.repeat(40), treeSha: 'b'.repeat(40) };
    const smoke = { source, status: 'LOCAL_IMAGE_STARTUP_SMOKE_PASSED', cleanup: { status: 'PASSED' },
      build: { status: 'LOCAL_IMAGES_BUILT_NOT_RUNTIME_ACCEPTED', source, images: [{ component: 'backend' }] } };
    await assert.rejects(executeEvaluationBusinessRehearsal({ smoke, directory, scenario: {}, maximumMs: 5000 }, {
      createRuntime: async () => { const error = new Error('private-startup-error'); error.cleanupStatus = status; throw error; },
    }), /EVALUATION_BUSINESS_API_REHEARSAL_FAILED/u);
    const receipt = JSON.parse(readFileSync(resolve(directory, 'evaluation-business-rehearsal.json')));
    assert.equal(receipt.status, 'FAILED');
    assert.equal(receipt.cleanup.status, ['PASSED', 'FAILED', 'NOT_CREATED'].includes(status) ? status : 'UNKNOWN');
    assert.doesNotMatch(JSON.stringify(receipt), /private-startup|secret-unknown/u);
  });
}
