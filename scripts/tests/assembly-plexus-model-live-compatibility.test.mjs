import assert from 'node:assert/strict';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
const root = fileURLToPath(new URL('../../', import.meta.url));
const runner = path.join(root, 'scripts/tests/fixtures/run_boot_http_model_compatibility.py');
const enabled = process.env.GITHUB_ACTIONS === 'true' || process.env.ASSEMBLY_PLEXUS_MODEL_COMPATIBILITY === 'true';
const python = source => spawnSync('/usr/bin/python3', ['-I', '-S', '-B', '-c', source, runner, root], {
  encoding: 'utf8', timeout: 20000, maxBuffer: 1024 * 1024,
  env: { PATH: '/usr/bin:/bin', LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8', TZ: 'UTC' },
});

test('Assembly model plan requires exactly two bounded opt-in model calls', () => {
  const r = spawnSync('/usr/bin/python3', ['-I', '-S', '-B', runner, '--profile=assembly'], { encoding: 'utf8', timeout: 20000 });
  assert.equal(r.status, 0, r.stderr);
  const p = JSON.parse(r.stdout);
  assert.equal(p.execution, 'NOT_RUN'); assert.equal(p.dependencyProfile, 'assembly');
  assert.equal(p.actualModelCommands, 2); assert.equal(p.modelCountPerCommand, 26);
  assert.equal(p.goal, 'org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom');
  assert.equal(p.lifecycle, false); assert.equal(p.localAlwaysOffline, true);
});

test('Assembly preparation binds current source, exact predecessor and no process execution', () => {
  const r = python(String.raw`
import runpy,sys,tempfile
from pathlib import Path
from unittest.mock import patch
s=runpy.run_path(sys.argv[1]);root=Path(sys.argv[2])
class NoCommands:
    def __init__(self,sandbox,environment,seconds):
        self.environment=environment;self.results=[]
    def run(self,*args,**kwargs):raise AssertionError('Unexpected process execution')
with tempfile.TemporaryDirectory(prefix='assembly-model-preparation-') as directory:
    sandbox=Path(directory);repository=sandbox/'cache';repository.mkdir()
    with patch.dict(s['ModelRun'].__init__.__globals__,{'Commands':NoCommands,'python_origins':lambda:({},[]),'runtime_files':lambda *args:[]}):
        m=s['ModelRun'](root,sandbox,repository,Path('/reviewed/jdk'),Path('/reviewed/maven/bin/mvn'),True,'assembly')
    assert m.source==Path(m.jar_source_projection['retainedSourceRoot'])
    assert m.jar_source_projection['suppliedSourceRoot']==str(root)
    assert m.jar_source_projection['suppliedRootPomSha256']==s['JAR_RETAINED']['LANG_POM']
    assert m.jar_source_projection['schemaVersion']=='ASSEMBLY_ENFORCER_LANG_RETAINED_ASSEMBLY_SOURCE_VIEW_V1'
    assert m.jar_source_projection['retainedRootPomSha256']==s['ASSEMBLY']['CURRENT_POM']
    assert m.report['historicalJarSourceProjection']==m.jar_source_projection
    assert m.assembly_source_projection is None and m.pair_source_projection is None
    assert 'HOME' not in m.commands.environment and m.commands.results==[]
    assert m.projection['suppliedRootPomSha256']==s['ASSEMBLY']['CURRENT_POM']
    assert m.projection['retainedRootPomSha256']==s['ASSEMBLY']['PRIOR_POM']
    assert s['ASSEMBLY']['reverse_source']((m.source/'pom.xml').read_bytes())==(m.retained/'pom.xml').read_bytes()
    m.containment()
    original=(m.retained/'pom.xml').read_bytes()
    (m.retained/'pom.xml').write_bytes(original.replace(b'<maven.release.version>3.3.1',b'<maven.release.version>3.3.0'))
    try:m.containment()
    except ValueError:pass
    else:raise AssertionError('Retained source corruption accepted')
`);
  assert.equal(r.status, 0, r.stderr);
});

test('CI compares Assembly private dependency across all26 actual default-profile models', {
  skip: enabled ? false : 'Local model execution needs explicit reviewed offline cache and opt-in.', timeout: 720000,
}, () => {
  const repo = process.env.M6_PR_E_E2_MAVEN_REPOSITORY, evidence = process.env.ASSEMBLY_PLEXUS_MODEL_EVIDENCE_DIRECTORY;
  const r = spawnSync('/usr/bin/timeout', ['--signal=TERM', '--kill-after=15s', '675s',
    '/usr/bin/python3', '-I', '-S', '-B', runner, '--execute', '--profile=assembly', `--source=${root}`,
    ...(repo ? [`--repository=${repo}`] : []), ...(evidence ? [`--evidence-directory=${evidence}`] : [])], {
    cwd: root, encoding: 'utf8', timeout: 710000, maxBuffer: 8 * 1024 * 1024,
    env: { ...process.env, ASSEMBLY_PLEXUS_MODEL_COMPATIBILITY: 'true' },
  });
  if (r.stdout) console.log(r.stdout.trim()); if (r.stderr) console.error(r.stderr.trim());
  assert.equal(r.status, 0, `Assembly actual model gate failed: ${r.error?.message ?? r.signal ?? r.status}`);
  const rows = r.stdout.split('\n').filter(line => line.startsWith('ASSEMBLY_PLEXUS_MODELS_RESULT '));
  assert.equal(rows.length, 1); const receipt = JSON.parse(rows[0].slice('ASSEMBLY_PLEXUS_MODELS_RESULT '.length));
  assert.equal(receipt.status, 'PASSED'); assert.equal(receipt.schemaVersion, 'ASSEMBLY_PLEXUS_ACTUAL_MODELS_V1');
  assert.equal(receipt.actualDefaultProfileModelEvidence, true); assert.equal(receipt.modelCount, 26);
  assert.equal(receipt.managedAssemblyModelCount, 26); assert.equal(receipt.assemblyExecutionCount, 0);
  assert.equal(receipt.allOtherModelFieldsUnchanged, true); assert.equal(receipt.sourcePomCount, 28);
  for (const key of ['sourceInputsStable', 'executionInputsStable', 'runtimeInputsStable', 'cacheExistingJarPomBytesStable']) assert.equal(receipt[key], true, key);
  assert.equal(receipt.offline, process.env.GITHUB_ACTIONS !== 'true');
  assert.equal(receipt.scannerEvidence, false); assert.equal(receipt.canonicalCiEvidence, false);
  assert.equal(receipt.historicalJarSourceProjection.suppliedRootPomSha256, 'ddb286b44b1976dd19ad74b06f3ceef86e19d0fbee87dd27dc87985ffb1f7932');
  assert.equal(receipt.historicalJarSourceProjection.schemaVersion, 'ASSEMBLY_ENFORCER_LANG_RETAINED_ASSEMBLY_SOURCE_VIEW_V1');
  assert.equal(receipt.historicalJarSourceProjection.retainedRootPomSha256, '26f93ea126fad31dad2646d84ca6945b68e68c277396bf37cf4f9cf815a11629');
  assert.equal(receipt.historicalJarSourceProjection.sourcePomCount, 28);
  assert.equal(receipt.actualModelSourceRoots.candidate, receipt.historicalJarSourceProjection.retainedSourceRoot);
  assert.equal(receipt.historicalAssemblySourceProjection, null); assert.equal(receipt.historicalPairSourceProjection, null);
  assert.deepEqual(receipt.commands.map(row => row.case), ['assembly-model-maven-version', 'assembly-baseline-effective-models', 'assembly-candidate-effective-models']);
  for (const command of receipt.commands) {
    assert.equal(command.exitStatus, 0); assert.equal(command.captureComplete, true); assert.equal(command.processGroupCleaned, true);
  }
});
