import assert from 'node:assert/strict';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = fileURLToPath(new URL('../../', import.meta.url));
const runner = path.join(root, 'scripts/tests/fixtures/run_boot_http_model_compatibility.py');
const enabled = process.env.GITHUB_ACTIONS === 'true' || process.env.JACOCO_ANTRUN_MODEL_COMPATIBILITY === 'true';

test('pair model runner defaults to a bounded nonexecuting plan', () => {
  const result = spawnSync('/usr/bin/python3', ['-I', '-S', '-B', runner, '--profile=jacoco-antrun'], {
    encoding: 'utf8', timeout: 20000, maxBuffer: 1024 * 1024,
    env: { PATH: '/usr/bin:/bin', LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8', TZ: 'UTC' },
  });
  assert.equal(result.status, 0, result.stderr);
  const plan = JSON.parse(result.stdout);
  assert.equal(plan.dependencyProfile, 'jacoco-antrun'); assert.equal(plan.execution, 'NOT_RUN');
  assert.equal(plan.actualModelCommands, 2); assert.equal(plan.modelCountPerCommand, 26);
  assert.equal(plan.goal, 'org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom');
  assert.equal(plan.lifecycle, false); assert.equal(plan.localAlwaysOffline, true);
});

test('pair model preparation keeps the candidate source and omits HOME without running Maven', () => {
  const source = String.raw`
import runpy,sys,tempfile
from pathlib import Path
from unittest.mock import patch
runner=Path(sys.argv[1]);root=Path(sys.argv[2]);s=runpy.run_path(str(runner))
class NoCommands:
    def __init__(self,sandbox,environment,seconds):
        self.environment=environment;self.results=[]
        assert seconds==660
    def run(self,*args,**kwargs):raise AssertionError('Preparation attempted execution')
with tempfile.TemporaryDirectory(prefix='pair-model-preparation-') as directory:
    sandbox=Path(directory);repository=sandbox/'cache';repository.mkdir()
    with patch.dict(s['ModelRun'].__init__.__globals__,{
        'Commands':NoCommands,'python_origins':lambda:({},[]),'runtime_files':lambda *args:[]}):
        model=s['ModelRun'](root,sandbox,repository,Path('/reviewed/jdk'),Path('/reviewed/maven/bin/mvn'),True,'jacoco-antrun')
    assert 'HOME' not in model.commands.environment
    assert model.source==Path(model.assembly_source_projection['retainedSourceRoot'])
    assert model.jar_source_projection['suppliedSourceRoot']==str(root)
    assert model.jar_source_projection['suppliedRootPomSha256']==s['JAR_RETAINED']['LANG_POM']
    assert model.jar_source_projection['schemaVersion']=='ASSEMBLY_ENFORCER_LANG_RETAINED_ASSEMBLY_SOURCE_VIEW_V1'
    assert model.jar_source_projection['retainedRootPomSha256']==s['ASSEMBLY']['CURRENT_POM']
    assert model.assembly_source_projection['suppliedSourceRoot']==model.jar_source_projection['retainedSourceRoot']
    assert model.report['historicalJarSourceProjection']==model.jar_source_projection
    assert model.assembly_source_projection['retainedRootPomSha256']==s['PAIR']['CURRENT_POM']
    assert model.pair_source_projection is None
    assert model.projection['suppliedRootPomSha256']==s['PAIR']['CURRENT_POM']
    assert model.projection['retainedRootPomSha256']==s['PAIR']['PRIOR_POM']
    assert model.report['schemaVersion']=='JACOCO_ANTRUN_ACTUAL_MODELS_V1'
    assert model.report['commands']==[]
`;
  const result = spawnSync('/usr/bin/python3', ['-I', '-S', '-B', '-c', source, runner, root], {
    encoding: 'utf8', timeout: 20000, maxBuffer: 1024 * 1024,
    env: { PATH: '/usr/bin:/bin', LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8', TZ: 'UTC' },
  });
  assert.equal(result.status, 0, result.stderr);
});

test('CI compares actual JaCoCo and inactive AntRun models with the exact accepted source across all26 projects', {
  skip: enabled ? false : 'Local model execution needs explicit reviewed offline cache and opt-in.', timeout: 720000,
}, () => {
  const repository = process.env.M6_PR_E_E2_MAVEN_REPOSITORY;
  const evidence = process.env.JACOCO_ANTRUN_MODEL_EVIDENCE_DIRECTORY;
  const result = spawnSync('/usr/bin/timeout', ['--signal=TERM', '--kill-after=15s', '675s',
    '/usr/bin/python3', '-I', '-S', '-B', runner, '--execute', '--profile=jacoco-antrun', `--source=${root}`,
    ...(repository ? [`--repository=${repository}`] : []), ...(evidence ? [`--evidence-directory=${evidence}`] : [])], {
    cwd: root, encoding: 'utf8', timeout: 710000, maxBuffer: 8 * 1024 * 1024,
    env: { ...process.env, JACOCO_ANTRUN_MODEL_COMPATIBILITY: 'true' },
  });
  if (result.stdout) console.log(result.stdout.trim());
  if (result.stderr) console.error(result.stderr.trim());
  assert.equal(result.status, 0, `Pair actual model gate failed: ${result.error?.message ?? result.signal ?? result.status}`);
  const lines = result.stdout.split('\n').filter(line => line.startsWith('JACOCO_ANTRUN_MODELS_RESULT '));
  assert.equal(lines.length, 1);
  const receipt = JSON.parse(lines[0].slice('JACOCO_ANTRUN_MODELS_RESULT '.length));
  assert.equal(receipt.schemaVersion, 'JACOCO_ANTRUN_ACTUAL_MODELS_V1');
  assert.equal(receipt.dependencyProfile, 'jacoco-antrun'); assert.equal(receipt.status, 'PASSED');
  assert.equal(receipt.actualDefaultProfileModelEvidence, true); assert.equal(receipt.offline, process.env.GITHUB_ACTIONS !== 'true');
  assert.equal(receipt.modelCount, 26); assert.equal(receipt.activeJacocoModelCount, 26);
  assert.equal(receipt.managedAntRunModelCount, 26); assert.equal(receipt.antRunExecutionCount, 0);
  assert.equal(receipt.sourcePomCount, 28); assert.equal(receipt.allOtherModelFieldsUnchanged, true);
  for (const key of ['sourceInputsStable', 'executionInputsStable', 'runtimeInputsStable', 'cacheExistingJarPomBytesStable'])
    assert.equal(receipt[key], true, key);
  assert.equal(receipt.historicalJarSourceProjection.suppliedRootPomSha256, 'ddb286b44b1976dd19ad74b06f3ceef86e19d0fbee87dd27dc87985ffb1f7932');
  assert.equal(receipt.historicalJarSourceProjection.schemaVersion, 'ASSEMBLY_ENFORCER_LANG_RETAINED_ASSEMBLY_SOURCE_VIEW_V1');
  assert.equal(receipt.historicalJarSourceProjection.retainedRootPomSha256, '26f93ea126fad31dad2646d84ca6945b68e68c277396bf37cf4f9cf815a11629');
  assert.equal(receipt.historicalJarSourceProjection.retainedSourceRoot, receipt.historicalAssemblySourceProjection.suppliedSourceRoot);
  assert.equal(receipt.historicalAssemblySourceProjection.suppliedRootPomSha256, '26f93ea126fad31dad2646d84ca6945b68e68c277396bf37cf4f9cf815a11629');
  assert.equal(receipt.historicalAssemblySourceProjection.retainedRootPomSha256, 'd36a115813577c7ce997c3adb990cd17247b060071fce25a201d1c73d392042c');
  assert.equal(receipt.historicalPairSourceProjection, null); assert.equal(receipt.historicalUtilitiesSourceProjection, null);
  assert.equal(receipt.pairSourceProjection.suppliedRootPomSha256, 'd36a115813577c7ce997c3adb990cd17247b060071fce25a201d1c73d392042c');
  assert.equal(receipt.pairSourceProjection.retainedRootPomSha256, '2f22ec8cae77cdfb519b81a8e48417fc75632416bcf28989f589f33d39261579');
  assert.equal(receipt.cleanupGraceSeconds, 15); assert.equal(receipt.cacheSnapshots.length, 3);
  assert.ok(Object.keys(receipt.runtimeHashes).length > 100); assert.ok(receipt.cacheInputsAfter.jarPomCount > 100);
  assert.deepEqual(receipt.commands.map(row => row.case), [
    'jacoco-antrun-model-maven-version', 'jacoco-antrun-baseline-effective-models', 'jacoco-antrun-candidate-effective-models']);
  for (const command of receipt.commands) {
    assert.equal(command.exitStatus, 0); assert.equal(command.captureComplete, true); assert.equal(command.processGroupCleaned, true);
  }
  for (const key of ['effectiveModelSha256', 'baselineEffectiveModelSha256']) assert.match(receipt[key], /^[0-9a-f]{64}$/);
  assert.equal(receipt.scannerEvidence, false); assert.equal(receipt.transportCompatibilityClaimed, false);
});
