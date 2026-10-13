import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import './boot-http-compatibility-harness.test.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const enabled = process.env.GITHUB_ACTIONS === 'true' || process.env.BOOT_HTTP_COMPATIBILITY === 'true';
const skip = enabled ? false : 'Boot live execution needs CI or BOOT_HTTP_COMPATIBILITY=true.';

async function runAdapter(command, args, options) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, options);
    const chunks = { stdout: [], stderr: [] };
    let bytes = 0;
    let failure;
    let grace;
    function terminate(reason) {
      if (failure) return;
      failure = reason;
      child.kill('SIGTERM');
      grace = setTimeout(() => child.kill('SIGKILL'), 15000);
    }
    const interrupts = Object.fromEntries(['SIGINT', 'SIGTERM', 'SIGHUP']
      .map(signal => [signal, () => terminate(`wrapper received ${signal}`)]));
    for (const [signal, handler] of Object.entries(interrupts)) process.on(signal, handler);
    function stopTimersAndHandlers() {
      clearTimeout(deadline); clearTimeout(grace);
      for (const [signal, handler] of Object.entries(interrupts)) process.off(signal, handler);
    }
    const deadline = setTimeout(() => terminate('outer deadline'), 1230000);
    for (const name of ['stdout', 'stderr']) child[name].on('data', data => {
      bytes += data.length;
      if (bytes > 16 * 1024 * 1024) terminate('adapter output budget exceeded');
      else chunks[name].push(data);
    });
    child.on('error', error => {
      stopTimersAndHandlers(); reject(error);
    });
    child.on('close', (status, signal) => {
      stopTimersAndHandlers();
      resolve({ status, signal, failure, ...Object.fromEntries(Object.entries(chunks)
        .map(([name, parts]) => [name, Buffer.concat(parts).toString('utf8')])) });
    });
  });
}

export async function emitBootAdapterOutput(result, stream = process.stdout) {
  // Serialize the outer log through one pipe. Label the original channels and
  // preserve their bytes; result.stdout/result.stderr remain separate internally.
  for (const name of ['stdout', 'stderr']) {
    if (!result[name]) continue;
    const output = `BOOT_ADAPTER_CHANNEL ${name}\n` + result[name] + (result[name].endsWith('\n') ? '' : '\n');
    await new Promise((resolve, reject) => stream.write(output, error => error ? reject(error) : resolve()));
  }
}

async function live() {
  const repository = process.env.M6_PR_E_E2_MAVEN_REPOSITORY;
  const evidence = process.env.BOOT_HTTP_EVIDENCE_DIRECTORY;
  const result = await runAdapter('/usr/bin/python3', ['-I', '-S', '-B',
    path.join(root, 'scripts/tests/fixtures/run_boot_live_compatibility.py'), '--execute',
    ...(repository ? [`--repository=${repository}`] : []),
    ...(evidence ? [`--evidence-directory=${evidence}`] : [])], {
    cwd: root, env: { ...process.env, BOOT_HTTP_COMPATIBILITY: 'true' }, stdio: ['ignore', 'pipe', 'pipe'],
  });
  await emitBootAdapterOutput(result);
  assert.equal(result.failure, undefined, result.failure);
  assert.equal(result.status, 0, `Boot compatibility: status=${result.status}; signal=${result.signal}`);
  const lines = result.stdout.split('\n').filter(line => line.startsWith('BOOT_LIVE_RESULT '));
  assert.equal(lines.length, 1);
  const receipt = JSON.parse(lines[0].slice('BOOT_LIVE_RESULT '.length));
  return assertBootLiveReceipt(receipt);
}

export function assertBootLiveReceipt(receipt, { ci = process.env.GITHUB_ACTIONS === 'true' } = {}) {
  const variants = ['baseline', 'candidate'];
  const transports = ['http', 'tls', 'unix'];
  const mojoNames = ['help', 'repackage'];
  const keys = (object, expected) => assert.deepEqual(Object.keys(object).sort(), expected);
  assert.equal(receipt.status, 'PASSED');
  assert.equal(receipt.scannerEvidence, false);
  assert.deepEqual(receipt.coverageGaps, []);
  assert.equal(receipt.cachePreparation.status, 'VERIFIED');
  assert.equal(receipt.cachePreparation.acceptanceGoal, false);
  assert.equal(Object.keys(receipt.cachePreparation.artifacts).length, 42);
  assert.equal(receipt.cachePreparation.resolutionCommandsRun, ci ? 2 : 0);
  assert.equal(receipt.cachePreparation.mode, ci
    ? 'ci-central-plugin-resolution' : 'offline-existing-cache');
  keys(receipt.ownerReports, variants);
  keys(receipt.stages, ['goals', 'transport']);
  for (const owner of Object.values(receipt.ownerReports)) {
    assert.equal(owner.freshOfflineCapture, true);
    assert.equal(owner.orderedCoordinates.length, 39);
    assert.equal(new Set(owner.orderedCoordinates).size, 39);
    assert.match(owner.sha256, /^[0-9a-f]{64}$/);
  }
  {
    const result = receipt.stages.transport;
    assert.equal(result.status, 'PASSED');
    assert.equal(result.linkageOnly, false);
    assert.equal(result.temporaryTlsRemoved, true);
    assert.match(result.linkageNegativeControl, /^PASSED:/);
    keys(result.variants, variants);
    const baseline = result.variants.baseline;
    const candidate = result.variants.candidate;
    for (const [name, variant] of Object.entries(result.variants)) {
      assert.equal(variant.status, 'PASSED');
      assert.equal(variant.compiledAgainst, 'baseline');
      assert.equal(variant.existingJacksonProbe, 'PASSED');
      assert.equal(variant.artifacts.length, 39);
      assert.deepEqual(variant.artifacts.map(item => item.coordinate), receipt.ownerReports[name].orderedCoordinates);
      keys(variant.fixtureObservations, transports);
      keys(variant.fixtureCleanup, transports);
      for (const [name, count] of [['http', 15], ['unix', 15], ['tls', 2]]) {
        assert.equal(variant.fixtureObservations[name].events.length, count, name);
        assert.deepEqual(variant.fixtureCleanup[name], {
          listenerClosed: true, workerThreadsStopped: true, activeConnections: 0,
        });
      }
      for (const assertion of ['HTTP complete', 'Unix complete', 'TLS trusted localhost',
        'TLS repeated request', 'TLS hostname mismatch rejected', 'TLS untrusted certificate rejected']) {
        assert.ok(variant.passedAssertions.includes(assertion), assertion);
      }
      const linkage = variant.staticLinkage;
      assert.deepEqual(linkage.inventory, {
        inspectedBootClassFiles: 260, nonArrayHcClassReferences: 32,
        memberReferences: 49, staticInstanceUses: 49,
        memberSetSha256: 'b0cc9e98b73157c751911403e5f7cc09103ce25796ee6ff53f36ee745bac9300',
      });
      assert.equal(linkage.members.length, linkage.inventory.memberReferences);
    }
    assert.deepEqual(candidate.staticLinkage, baseline.staticLinkage);
    const jackson = variant => variant.artifacts.filter(item => item.coordinate.includes('jackson'));
    assert.deepEqual(jackson(candidate), jackson(baseline));
    assert.equal(jackson(candidate).length, 3);
  }
  {
    const result = receipt.stages.goals;
    assert.equal(result.status, 'PASSED');
    keys(result.variants, variants);
    for (const variant of Object.values(result.variants)) {
      assert.equal(variant.status, 'PASSED');
      assert.equal(variant.archiveMain, 'BOOT_GOAL_MAIN_OK');
      keys(variant.actualBootRealms, mojoNames);
      for (const realm of Object.values(variant.actualBootRealms)) {
        assert.equal(realm.length, 36);
        assert.equal(new Set(realm).size, 36);
      }
      assert.equal(variant.naturalOrigins.length, 3);
      assert.match(variant.jarSha256, /^[0-9a-f]{64}$/);
    }
    keys(result.naturalRealmComparison, mojoNames);
    for (const comparison of Object.values(result.naturalRealmComparison)) {
      assert.equal(comparison.membershipChangedOnlyByHttpTrio, true);
      assert.equal(comparison.nonHttpOrderEqual, true);
    }
    // Maven's private goal realm is distinct from the 39-JAR owner report.
    // Preserve its actual order; no synthetic complete-order assertion is made.
  }
  return receipt;
}

test('reviewed Boot full HTTP/TLS/Unix, linkage/Jackson and separate real help/repackage smoke', { skip, timeout: 1250000 }, async () => {
  await live();
});
