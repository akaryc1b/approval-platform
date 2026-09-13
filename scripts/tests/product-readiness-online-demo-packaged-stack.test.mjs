import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { classifyEvaluationStartupLog, observeEvaluationStartupFailure }
  from '../product-readiness/online-demo/evaluation-startup-diagnostics.mjs';

const frame = '\tat io.github.akaryc1b.approval.demo.PurchasePaymentDemoSeeder.requireDefinitionProjection(PurchasePaymentDemoSeeder.java:498)';
for (const suffix of ['', ' ~[!/:0.1.0-SNAPSHOT]', ' [approval-server.jar:0.1.0-SNAPSHOT]']) {
  test(`retain the same allowlisted source frame with packaging suffix ${suffix || '(none)'}`, () => {
    assert.deepEqual(classifyEvaluationStartupLog(frame + suffix).sourceFrames,
      [{ file: 'PurchasePaymentDemoSeeder.java', line: 498 }]);
  });
}
test('classify known Seed invariant failure without exporting arbitrary exception text or JAR metadata', () => {
  const secret = 'must-not-appear-in-the-receipt';
  const log = 'Application run failed\njava.lang.IllegalStateException: '
    + 'demo definition projection conflicts with governed Release Package deployment '
    + secret + '\n' + frame + ' ~[!/' + secret + ':1]';
  const result = classifyEvaluationStartupLog(log);
  assert.deepEqual(result.signals, ['APPLICATION_START_FAILED', 'RUNTIME_VALIDATION_FAILURE',
    'SEED_DEFINITION_PROJECTION_MISMATCH']);
  assert.equal(JSON.stringify(result).includes(secret), false);
  assert.deepEqual(result.sourceFrames, [{ file: 'PurchasePaymentDemoSeeder.java', line: 498 }]);
});
test('reject unapproved frames and unbounded log input', () => {
  for (const line of [frame.replace('PurchasePaymentDemoSeeder.java', 'Untrusted.java'),
    frame.replace(':498)', ':0)'), frame + ' arbitrary-exception-text',
    frame.replace('io.github.akaryc1b.approval.', 'untrusted.')]) {
    assert.equal(classifyEvaluationStartupLog(line).sourceFrames, undefined);
  }
  assert.equal(classifyEvaluationStartupLog('x'.repeat(262_145)).logs, 'UNAVAILABLE');
});
test('owned Docker collector retains packaged frame via explicit safe receipt fields', async () => {
  const namespace = 'a'.repeat(32), generation = 'b'.repeat(32), id = 'c'.repeat(64);
  const name = `ap-evaluation-${namespace}-slot-a-backend`;
  const result = await observeEvaluationStartupFailure({
    slot: { namespace, generation, slotId: 'slot-a', containers: { backend: { id, name } } },
    run: async (args, options) => {
      assert.equal(options.timeoutMs, 1500);
      if (args[0] === 'logs') return frame + ' ~[!/:0.1.0-SNAPSHOT]';
      return JSON.stringify([{ Id: id, Name: '/' + name,
        Config: { Env: ['SECRET=not-returned'], Labels: {
          'io.approval.evaluation.owner': namespace, 'io.approval.evaluation.slot': 'slot-a',
          'io.approval.evaluation.generation': generation, 'io.approval.evaluation.role': 'backend',
        } }, State: { Running: false, OOMKilled: false, ExitCode: 1 } }]);
    },
  });
  assert.deepEqual(Object.keys(result).sort(), ['beans', 'inspection', 'kind', 'logs', 'signals', 'sourceFrames', 'state']);
  assert.deepEqual(result.sourceFrames, [{ file: 'PurchasePaymentDemoSeeder.java', line: 498 }]);
  assert.equal(JSON.stringify(result).includes('SECRET'), false);
  const source = readFileSync(new URL('../product-readiness/online-demo/evaluation-startup-diagnostics.mjs', import.meta.url), 'utf8');
  assert.doesNotMatch(source, /Object\.assign/u);
});
