import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';

const root = process.cwd();
const read = (path) => readFileSync(join(root, path), 'utf8');
const protocol = read('docs/M5_D_SERVER_SIDE_EXECUTION_PROTOCOL.md');
const evidence = read('docs/M5_D2_SHARED_COMMAND_FENCE_CLAIM_EVIDENCE.md');

test('M5-D2 governance stops at explicit acceptance without advancing D3', () => {
  assert.ok(protocol.includes('- M5-D2: `COMPLETE_PENDING_EXPLICIT_ACCEPTANCE`'));
  assert.ok(protocol.includes('- M5-D3 through M5-D8: not started'));
  assert.ok(protocol.includes('- Current M5-D overall result: `IN_PROGRESS`'));
  assert.ok(protocol.includes('- Production migration execution: `NOT_AUTHORIZED`'));
  assert.ok(evidence.includes('- M5-D2 implementation slice: `COMPLETE_PENDING_EXPLICIT_ACCEPTANCE`'));
  assert.ok(evidence.includes('- M5-D3 through M5-D8: not started'));
  assert.ok(evidence.includes('This is not an acceptance decision'));
  assert.ok(!protocol.includes('- M5-D2: `ACCEPTED`'));
  assert.ok(!evidence.includes('- M5-D2 implementation slice: `ACCEPTED`'));
});

test('M5-D2 evidence freezes the exact successful implementation run', () => {
  for (const required of [
    'run ID: `30187205016`',
    'run number: `#563`',
    'head: `1eedb7ee75060b8c6e1d06bbf8504432ce782462`',
    'Maven aggregate: 586 tests, 0 failures, 0 errors, 0 skipped',
    'approval-application: 140/140',
    'approval-persistence-jdbc: 250/250',
    'D1/D2 permanent Node governance boundaries: 34/34',
    '`8627434660`',
    'd1e772445838afd0e5215c86ab32def7cef3576004d76bfd449342a1029e09a3',
    '`8627394734`',
    'e1da0047758b6c0376e4b25d073a45fef5fedab258ae6f713101e2c294c6e759',
    '`8627390472`',
    '1a308f7e5d714d1f841533ac1e6f373838666af724400b9ee853d1dff2b588e9',
    '`8627384038`',
    'f3e1adf64a05f57e45a1e046bc725c04771ee9230dab3eaea54f1f53170cefc5',
  ]) {
    assert.ok(evidence.includes(required), `missing frozen evidence: ${required}`);
  }
});

test('M5-D2 evidence freezes V40 claim fencing and scale guarantees', () => {
  for (const required of [
    'Flyway latest: `V40`',
    '`ap_process_migration_claim_batch`',
    '`ap_approval_instance_command_fence`',
    '`ap_approval_instance_command_fence_event`',
    '`idx_process_migration_attempt_claim_v40`',
    '`FOR UPDATE SKIP LOCKED`',
    '5,000 migration attempts',
    '`EXPLAIN (FORMAT JSON)`',
    'contained no `Seq Scan`',
    'same-owner renewal',
    'takeover only at or after expiry',
    'stale-owner rejection',
  ]) {
    assert.ok(evidence.includes(required), `missing D2 guarantee: ${required}`);
  }
});

test('M5-D2 evidence retains the closed execution and security boundary', () => {
  for (const required of [
    'does not read or write Flowable `ACT_*` tables',
    'D2 invokes no Flowable migration API',
    'no definition-wide or batch migration exists',
    'no `UNKNOWN` automatic retry exists',
    'no force-success or fake rollback exists',
    'no runtime-binding mutation exists in D2',
    'no public execution Controller, REST route or endpoint exists',
    'no Web or Mobile execution control exists',
    'no resident scheduler or always-on worker exists',
    'remain disabled by default',
    'production execution remains disabled and not authorized',
  ]) {
    assert.ok(evidence.includes(required), `missing retained limitation: ${required}`);
  }
  for (const run of ['#550', '#552', '#554', '#555', '#560', '#562']) {
    assert.ok(evidence.includes(`Run ${run}`), `missing retained failed run ${run}`);
  }
});
