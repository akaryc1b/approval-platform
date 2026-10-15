import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const ci = process.env.GITHUB_ACTIONS === 'true';
test('real offline Assembly 3.7.1 / Plexus 4.0.0 and 4.0.3 six-pair compatibility', {
  skip: !ci && process.env.ASSEMBLY_PLEXUS_COMPATIBILITY !== 'true' ? 'explicit reviewed Assembly fixture opt-in required' : false,
}, () => {
  const seal = process.env.ASSEMBLY_PLEXUS_COMPATIBILITY_SEAL;
  if (!ci) assert.ok(seal, 'reviewed sealed source/runtime/cache inputs required');
  const fixture = fileURLToPath(new URL(ci ? './fixtures/assembly-plexus-portable.py' : './fixtures/assembly-plexus-compatibility.py', import.meta.url));
  const run = spawnSync('python3', ['-I', '-S', '-B', fixture, ...(ci ? [] : ['--execute-seal', seal])],
    { encoding: 'utf8', timeout: ci ? 1200000 : 930000, maxBuffer: 4 * 1024 * 1024,
      env: { ...process.env, ASSEMBLY_PLEXUS_COMPATIBILITY: 'true' } });
  assert.equal(run.status, 0, run.stdout + run.stderr);
  const parsed = run.stdout.split('\n').filter(line => line.startsWith('ASSEMBLY_PLEXUS_RESULT '));
  assert.equal(parsed.length, 1);
  const summary = JSON.parse(parsed[0].slice('ASSEMBLY_PLEXUS_RESULT '.length));
  const receipt = JSON.parse(readFileSync(summary.path, 'utf8'));
  if (ci) {
    const rows = run.stdout.split('\n').filter(line => line.startsWith('ASSEMBLY_PLEXUS_PORTABLE_RESULT '));
    assert.equal(rows.length, 1);
    const portable = JSON.parse(readFileSync(JSON.parse(rows[0].slice('ASSEMBLY_PLEXUS_PORTABLE_RESULT '.length)).path, 'utf8'));
    assert.equal(portable.status, 'PASSED'); assert.ok(portable.resolutionCommandsRun <= 2);
    assert.equal(portable.existingArtifactHashesUnchanged, true);
  }
  assert.equal(receipt.status, 'PASSED'); assert.equal(receipt.inputSealsUnchanged, true);
  assert.equal(receipt.scannerEvidence, false); assert.equal(receipt.productionActivationClaim, false);
  assert.equal(receipt.commands.filter(c => c.command.at(-1) === receipt.plan.goal).length, 12);
  assert.ok(receipt.commands.every(c => c.status === 'EXITED' && c.processGroupCleaned && c.captureComplete));
  for (const variant of ['baseline', 'candidate']) {
    assert.equal(receipt.variants[variant].coverage.symbolicallyResolvedConsumers, 55);
    for (const name of receipt.plan.cases) {
      assert.equal(receipt.variants[variant][name].missingMemberControl, 'PASSED');
      if (name !== 'archives') assert.deepEqual(receipt.variants.baseline[name].behavior, receipt.variants.candidate[name].behavior);
    }
  }
  assert.equal(Object.keys(receipt.expectedArchiveDelta).length, 4);
  for (const delta of Object.values(receipt.expectedArchiveDelta)) {
    assert.equal(delta.removedRegularFiles.length, 8); assert.equal(delta.explicitFalseUnchanged, true);
    assert.equal(delta.retainedFilesEqual, true); assert.equal(delta.allOtherMetadataEqual, true);
  }
});
