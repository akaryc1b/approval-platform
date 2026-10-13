import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const ci = process.env.GITHUB_ACTIONS === 'true';
const enabled = ci || process.env.JACOCO_ANTRUN_COMPATIBILITY === 'true';
test('reviewed private Plexus pair runs actual agent assertion JVM reports merges and AntRun goals', {
  skip: enabled ? false : 'Requires CI or JACOCO_ANTRUN_COMPATIBILITY=true with reviewed local inputs.', timeout: 730000,
}, () => {
  const seal = process.env.JACOCO_ANTRUN_COMPATIBILITY_SEAL;
  if (seal) assert.ok(seal.startsWith('/'), 'An execution seal must use an absolute path.');
  const fixture = fileURLToPath(new URL('./fixtures/jacoco-antrun-compatibility.py', import.meta.url));
  const portable = ci || !seal;
  const selection = portable ? ['--portable',
    ...(process.env.M6_PR_E_E2_MAVEN_REPOSITORY ? [`--repository=${process.env.M6_PR_E_E2_MAVEN_REPOSITORY}`] : []),
    ...(process.env.JACOCO_ANTRUN_EVIDENCE_DIRECTORY ? [`--evidence-directory=${process.env.JACOCO_ANTRUN_EVIDENCE_DIRECTORY}`] : [])]
    : ['--execute-seal', seal];
  const run = spawnSync('/usr/bin/python3', ['-I', '-S', '-B', fixture, ...selection], {
    env: { ...process.env, JACOCO_ANTRUN_COMPATIBILITY: 'true' },
    encoding: 'utf8', timeout: portable ? 720000 : 420000, maxBuffer: 32 * 1024 * 1024,
  });
  assert.equal(run.status, 0, `${run.error?.message ?? ''}\n${run.stdout}\n${run.stderr}`);
  const receipts = run.stdout.split('\n').filter(line => line.startsWith('JAC_ANTRUN_RESULT '));
  assert.equal(receipts.length, 1);
  const receipt = JSON.parse(receipts[0].slice('JAC_ANTRUN_RESULT '.length));
  let adapter;
  if (portable) {
    const records = run.stdout.split('\n').filter(line => line.startsWith('JAC_ANTRUN_PORTABLE_RESULT '));
    assert.equal(records.length, 1);
    adapter = JSON.parse(records[0].slice('JAC_ANTRUN_PORTABLE_RESULT '.length));
    assert.equal(adapter.status, 'PASSED');
    assert.equal(adapter.cachePreparation.status, 'VERIFIED');
    assert.equal(adapter.cachePreparation.acceptanceGoal, false);
    assert.equal(adapter.cachePreparation.resolutionCommandsRun, ci ? 2 : 0);
    assert.equal(Object.keys(adapter.cachePreparation.artifacts).length, 18);
    assert.equal(adapter.cachePreparation.existingArtifactHashesUnchanged, true);
  }
  assert.equal(receipt.status, 'PASSED');
  assert.equal(receipt.inputSealsUnchanged, true);
  assert.equal(receipt.scannerEvidence, false);
  assert.equal(receipt.productionAntRunActivationClaim, false);
  assert.equal(receipt.apiReplay.missingMemberNegativeControl, 'PASSED');
  assert.deepEqual(Object.keys(receipt.variants), ['baseline', 'candidate']);
  for (const [variant, value] of Object.entries(receipt.variants)) {
    assert.equal(value.assertionJvmTests, 3);
    assert.deepEqual(value.report.counters.BRANCH, { covered: 2, missed: 0 });
    assert.equal(value.merge['merge-defaults'].inputNames.length, variant === 'baseline' ? 1 : 5);
    assert.deepEqual(value.merge['merge-explicit-excludes'].inputNames, ['normal.exec']);
    assert.equal(value.negativeControls.invalidExec, 'PASSED');
    assert.equal(value.negativeControls.antFailureReader, 'PASSED');
  }
  assert.equal(Object.keys(receipt.apiReplay.variants).length, 3);
  assert.ok(receipt.commands.every(command => command.status === 'EXITED' && command.processGroupCleaned && command.captureComplete));
  console.log('JAC_ANTRUN_RESULT ' + JSON.stringify(receipt));
  if (adapter) {
    const { offlineResult, ...preparation } = adapter;
    assert.equal(offlineResult, undefined, 'Portable receipt must not duplicate the offline result.');
    console.log('JAC_ANTRUN_PORTABLE_RESULT ' + JSON.stringify(preparation));
  }
  console.log('JaCoCo/AntRun private Plexus compatibility verified: actual assertion JVM, reports, merges, XML and bounded API consumers.');
});
