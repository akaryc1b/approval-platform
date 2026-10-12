import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import './checkstyle-plugin-live-harness.test.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const runner = path.join(root, 'scripts/tests/fixtures/run_checkstyle_live_compatibility.py');
const enabled = process.env.GITHUB_ACTIONS === 'true' || process.env.CHECKSTYLE_PLUGIN_COMPATIBILITY === 'true';
const skip = enabled ? false : 'Checkstyle live execution needs CI or explicit local opt-in.';

function live(transition, stage, timeout, variant = 'candidate') {
  const repository = process.env.M6_PR_E_E2_MAVEN_REPOSITORY;
  const evidence = process.env.CHECKSTYLE_PLUGIN_EVIDENCE_DIRECTORY;
  const result = spawnSync('/usr/bin/python3', ['-I', '-S', '-B', runner, '--execute', `--stage=${stage}`,
    `--variant=${variant}`, `--transition=${transition}`, `--source=${root}`, ...(repository ? [`--repository=${repository}`] : []),
    ...(evidence ? [`--evidence-directory=${evidence}`] : [])], {
    cwd: root, env: { ...process.env, CHECKSTYLE_PLUGIN_COMPATIBILITY: 'true' },
    encoding: 'utf8', timeout, maxBuffer: 8 * 1024 * 1024,
  });
  if (result.stdout) console.log(result.stdout.trim());
  if (result.stderr) console.error(result.stderr.trim());
  assert.equal(result.status, 0, `Checkstyle ${stage}/${variant}: status=${result.status}; signal=${result.signal}; ${result.error?.message ?? ''}`);
  const lines = result.stdout.split('\n').filter(line => line.startsWith('CHECKSTYLE_LIVE_RESULT '));
  assert.equal(lines.length, 1);
  const receipt = JSON.parse(lines[0].slice('CHECKSTYLE_LIVE_RESULT '.length));
  assert.equal(receipt.status, 'PASSED');
  assert.equal(receipt.transition, transition);
  assert.equal(receipt.stage, stage); assert.equal(receipt.variant, variant);
  assert.equal(receipt.scannerEvidence, false); assert.equal(receipt.vulnerableApiReachabilityClaim, false);
  assert.equal(receipt.cachePreparation.status, 'VERIFIED');
  assert.equal(receipt.cachePreparation.acceptanceGoal, false);
  assert.equal(Object.keys(receipt.cachePreparation.artifacts).length, transition === 'guava' ? 63 : 60);
  assert.equal(Object.keys(receipt.cachePreparation.reportResources).length, 1);
  assert.ok([0, 2].includes(receipt.cachePreparation.resolutionCommandsRun));
  assert.equal(receipt.cachePreparation.mode, process.env.GITHUB_ACTIONS === 'true'
    ? 'ci-central-dependency-resolution' : 'offline-existing-cache');
  assert.deepEqual(receipt.coverageGaps, []);
  return receipt;
}

for (const transition of ['lang', 'guava']) {
test(`reviewed Checkstyle ${transition} models compare two actual 26-model captures`, { skip, timeout: 690000 }, () => {
  const receipt = live(transition, 'models', 680000);
  assert.equal(receipt.modelCount, 26);
  assert.equal(Object.keys(receipt.sourceHashes).length, 29);
  assert.equal(Object.keys(receipt.baselineSourceHashes).length, 29);
  assert.equal(receipt.baselineSourceHashes['pom.xml'], transition === 'guava'
    ? 'd55f730aa8490c60ddf8c3d76d062fd6c4706be4816adf8db8936df15941a6fb'
    : '1569b0356f1bfc122066ced97c29038c3c38e891135ea277e7b7067726782e1a');
  assert.match(receipt.effectiveModelSha256, /^[0-9a-f]{64}$/);
  assert.match(receipt.baselineEffectiveModelSha256, /^[0-9a-f]{64}$/);
});

test(`reviewed Checkstyle ${transition} paired goals and observed-artifact replay preserve bounded behaviors`, { skip, timeout: 1500000 }, () => {
  const baseline = live(transition, 'fixtures', 740000, 'baseline');
  const candidate = live(transition, 'fixtures', 740000);
  assert.equal(Object.keys(candidate.cases).length, 15);
  assert.deepEqual(Object.keys(candidate.cases), Object.keys(baseline.cases));
  for (const [name, item] of Object.entries(candidate.cases)) {
    const prior = baseline.cases[name];
    assert.equal(item.status, 'PASSED', name); assert.equal(Object.keys(item.realmArtifacts).length, 54, name);
    assert.match(item.classLoadLogSha256, /^[0-9a-f]{64}$/);
    for (const field of ['exitStatus', 'expectedFailure', 'errorCount', 'expectedRule', 'htmlSummary', 'fixtureSourceHashes', 'actualForeignImports']) {
      assert.deepEqual(item[field], prior[field], `${name}: ${field}`);
    }
    for (const field of ['processedSourceFiles', 'expectedSelectedSourceFiles']) {
      assert.deepEqual(item[field]?.map(file => path.basename(file)), prior[field]?.map(file => path.basename(file)), `${name}: ${field}`);
    }
    const changed = transition === 'lang' ? ['org.apache.commons:commons-lang3:']
      : ['com.google.guava:guava:', 'org.checkerframework:checker-qual:',
        'com.google.errorprone:error_prone_annotations:', 'com.google.j2objc:j2objc-annotations:'];
    const unchanged = artifacts => Object.fromEntries(Object.entries(artifacts)
      .filter(([coordinate]) => !changed.some(prefix => coordinate.startsWith(prefix))));
    assert.equal(Object.keys(unchanged(item.realmArtifacts)).length, transition === 'lang' ? 53 : 50);
    assert.deepEqual(unchanged(item.realmArtifacts), unchanged(prior.realmArtifacts), `${name}: all other observed artifacts`);
  }
  for (const receipt of [baseline, candidate]) {
    assert.equal(receipt.cacheBehavior.changedTimestampRecheckedAndFailed, true);
    assert.equal(receipt.cacheBehavior.restoredSourceRecheckedAndPassed, true);
    assert.equal(receipt.cacheBehavior.deletedFileNotReported, true);
    assert.equal(receipt.apiProbe.status, 'PASSED'); assert.equal(receipt.apiProbe.goalReachabilityClaim, false);
    assert.equal(receipt.apiProbe[`${transition}MemberReferences`], transition === 'lang' ? 58 : 4);
    assert.equal(receipt.apiProbe.referenceSetSha256, transition === 'lang'
      ? 'd205262d8477778809dc6c3b8eea2a0edb047ca26223ae71fd19aa0677fe0d21'
      : 'e2c5e81005d7620b6fa48d4157f9e7413b05f9f8e07176c7e461ac9af2d2b833');
    const observedCase = transition === 'lang' ? 'passing' : 'invalid-configuration';
    assert.equal(receipt.apiProbe.observedGoalCase, observedCase);
    assert.equal(receipt.apiProbe.observedGoalStdoutSha256, receipt.cases[observedCase].stdoutSha256);
    if (transition === 'guava') {
      assert.equal(receipt.cases[observedCase].guavaDiscovery.status, 'EXPECTED_MISSING_MODULE_FAILURE');
      assert.equal(receipt.apiProbe.successfulCustomModuleNaturalGoalClaim, false);
      assert.match(receipt.apiProbe.invocationKindsSha256, /^[0-9a-f]{64}$/);
    }
    assert.deepEqual(Object.keys(receipt.apiProbe.negativeControls).sort(),
      ['changed-pin', `missing-${transition}`, `wrong-${transition}`]);
    for (const control of Object.values(receipt.apiProbe.negativeControls)) assert.equal(control.status, 'REJECTED');
  }
  assert.equal(candidate.apiProbe.behaviorContractSha256, baseline.apiProbe.behaviorContractSha256);
  assert.equal(candidate.apiProbe.behaviorAssertionCount, baseline.apiProbe.behaviorAssertionCount);
  assert.ok(candidate.apiProbe.behaviorAssertionCount >= (transition === 'lang' ? 20 : 12));
  if (transition === 'guava') assert.equal(candidate.apiProbe.invocationKindsSha256, baseline.apiProbe.invocationKindsSha256);
});
}
