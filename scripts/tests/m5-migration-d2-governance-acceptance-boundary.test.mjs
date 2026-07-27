import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';

const root = process.cwd();
const read = (path) => readFileSync(join(root, path), 'utf8');
const protocol = read('docs/M5_D_SERVER_SIDE_EXECUTION_PROTOCOL.md');
const evidence = read('docs/M5_D2_SHARED_COMMAND_FENCE_CLAIM_EVIDENCE.md');
const acceptance = read('docs/M5_D2_GOVERNANCE_ACCEPTANCE.md');

test('M5-D2 remains accepted while later D slices advance through separate gates', () => {
  assert.ok(protocol.includes('- M5-D2: `ACCEPTED / PERMANENTLY_VALIDATED`'));
  assert.ok(protocol.includes('- M5-D3: `COMPLETE / PERMANENTLY_VALIDATED`'));
  assert.ok(protocol.includes('- M5-D4: `COMPLETE / PERMANENTLY_VALIDATED`'));
  assert.ok(protocol.includes('- M5-D5: `COMPLETE / PERMANENTLY_VALIDATED`'));
  const beforeD6 = protocol.includes('- M5-D6 through M5-D8: not started');
  const afterD6 = protocol.includes('- M5-D6: `COMPLETE / PERMANENTLY_VALIDATED`')
    && protocol.includes('- M5-D7 through M5-D8: not started');
  const afterD7 = protocol.includes('- M5-D6: `COMPLETE / PERMANENTLY_VALIDATED`')
    && protocol.includes('- M5-D7: `COMPLETE / PERMANENTLY_VALIDATED`')
    && protocol.includes('- M5-D8: not started');
  assert.ok(beforeD6 || afterD6 || afterD7);
  assert.ok(
    protocol.includes('- Current M5-D overall result: `IN_PROGRESS`')
      || protocol.includes('- M5-D overall: `IN_PROGRESS`'),
  );
  assert.ok(protocol.includes('- Production migration execution: `NOT_AUTHORIZED`'));
  assert.ok(evidence.includes('- M5-D2 implementation slice: `COMPLETE_PENDING_EXPLICIT_ACCEPTANCE`'));
  assert.ok(evidence.includes('This is not an acceptance decision'));
  assert.ok(acceptance.includes('- M5-D2: `ACCEPTED / PERMANENTLY_VALIDATED`'));
  assert.ok(acceptance.includes('- Production migration execution: `NOT_AUTHORIZED`'));
  assert.ok(acceptance.includes('Only M5-D3'));
});

test('M5-D2 formal acceptance freezes the exact committed-head run and artifacts', () => {
  for (const required of [
    '`1e27dcc69d9c899b3593f9bb464fc1847a595513`',
    'Run ID: `30187720943`',
    'run number: `#564`',
    '`8627594481`',
    'eaf01b7066017017aba1fc4930b2e65cced72b1d83e32c8e98a935f5af464e30',
    '`8627556607`',
    'ca9ac648ef32d88fb87f50ef15b5bc763e7722aa216c2caf4b93bea2e9e5aa45',
    '`8627551054`',
    '33dc4a82ea38c52c5be3f2ecc95e76463113f532454ad8a30e4c0897af7a7d4e',
    '`8627544056`',
    '891dbcb714ea6ca33b8e5058fdd6cc2f79f45d821782a1848a9b53cc40af96a4',
  ]) {
    assert.ok(acceptance.includes(required), `missing acceptance evidence: ${required}`);
  }
});

test('M5-D2 implementation evidence freezes the exact successful implementation run', () => {
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
