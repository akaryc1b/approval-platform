import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import vm from 'node:vm';

const root = fileURLToPath(new URL('../../', import.meta.url));
const fixtures = path.join(root, 'scripts/tests/fixtures');

function python(source) {
  const result = spawnSync('/usr/bin/python3', ['-I', '-S', '-B', '-c', source, fixtures], {
    cwd: root, env: { PATH: '/usr/bin:/bin', LANG: 'C.UTF-8' },
    encoding: 'utf8', timeout: 30000, maxBuffer: 2 * 1024 * 1024,
  });
  assert.equal(result.status, 0, `${result.error?.message ?? ''}\n${result.stdout}\n${result.stderr}`);
  return result.stdout;
}

test('Checkstyle plan starts no tools and requires explicit review opt-in', () => {
  const plan = JSON.parse(python(String.raw`
import runpy, subprocess, sys
sys.path.insert(0, sys.argv[1])
def blocked(*args, **kwargs):
    raise AssertionError('plan attempted a process')
subprocess.Popen = blocked
sys.argv = ['run_checkstyle_live_compatibility.py']
runpy.run_path(sys.path[0] + '/run_checkstyle_live_compatibility.py', run_name='__main__')
`));
  assert.equal(plan.execution, 'NOT_RUN');
  assert.equal(plan.cases.length, 15);
  assert.equal(plan.modelCount, 26);
  assert.equal(plan.langMemberReferenceExpectation, 58);
  assert.equal(plan.explicitOptInRequiredEvenInCi, true);
  assert.equal(plan.localAlwaysOffline, true);
  assert.deepEqual(plan.ciPreparation, {
    goal: 'org.apache.maven.plugins:maven-dependency-plugin:3.11.0:resolve',
    artifactCount: 60, reportResourceCount: 1, maximumCommands: 2,
    acceptanceGoal: false, commandTimeoutSeconds: 120,
  });
  assert.deepEqual(plan.goals, ['org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom',
    'org.apache.maven.plugins:maven-checkstyle-plugin:check@checkstyle',
    'org.apache.maven.plugins:maven-checkstyle-plugin:checkstyle']);
});

test('Checkstyle CI selection explicitly opts isolated children in without enabling ordinary local runs', () => {
  const url = new URL('./checkstyle-plugin-live-compatibility.test.mjs', import.meta.url);
  // Evaluate the actual harness with only its imports replaced by injected
  // built-ins and a mocked spawn. No Python, Maven, or live test is launched.
  const source = readFileSync(url, 'utf8').replace(/^import .+;\n/gm, '')
    .replaceAll('import.meta.url', JSON.stringify(url.href));
  for (const [environment, enabled] of [[{}, false], [{ GITHUB_ACTIONS: 'false' }, false],
    [{ GITHUB_ACTIONS: 'true' }, true], [{ CHECKSTYLE_PLUGIN_COMPATIBILITY: 'true' }, true],
    [{ GITHUB_ACTIONS: 'true', CHECKSTYLE_PLUGIN_COMPATIBILITY: 'false' }, true]]) {
    const tests = []; const calls = [];
    const receipt = { status: 'PASSED', stage: 'models', variant: 'candidate', scannerEvidence: false,
      vulnerableApiReachabilityClaim: false, coverageGaps: [], modelCount: 26,
      sourceHashes: Object.fromEntries(Array.from({ length: 29 }, (_, n) => [n, 'hash'])),
      baselineSourceHashes: { ...Object.fromEntries(Array.from({ length: 28 }, (_, n) => [n, 'hash'])),
        'pom.xml': '1569b0356f1bfc122066ced97c29038c3c38e891135ea277e7b7067726782e1a' },
      effectiveModelSha256: 'a'.repeat(64), baselineEffectiveModelSha256: 'b'.repeat(64),
      cachePreparation: { status: 'VERIFIED', acceptanceGoal: false, resolutionCommandsRun: 0,
        mode: environment.GITHUB_ACTIONS === 'true' ? 'ci-central-dependency-resolution' : 'offline-existing-cache',
        artifacts: Object.fromEntries(Array.from({ length: 60 }, (_, n) => [n, {}])), reportResources: { skin: {} } },
    };
    const context = vm.createContext({ assert, path, URL, fileURLToPath, process: { env: environment },
      console: { log() {}, error() {} }, tests,
      test(name, options, fn) { tests.push({ name, options, fn }); },
      spawnSync(...args) { calls.push(args); return { status: 0, stdout: `CHECKSTYLE_LIVE_RESULT ${JSON.stringify(receipt)}\n`, stderr: '' }; },
    });
    vm.runInContext(source, context, { timeout: 1000 });
    assert.equal(tests.length, 2);
    assert.equal(tests.every(item => item.options.skip === false), enabled);
    if (enabled) {
      vm.runInContext('tests[0].fn()', context, { timeout: 1000 });
      assert.equal(calls.length, 1);
      assert.equal(calls[0][0], '/usr/bin/python3');
      assert.equal(calls[0][2].env.CHECKSTYLE_PLUGIN_COMPATIBILITY, 'true');
      assert.equal(calls[0][2].env.GITHUB_ACTIONS, environment.GITHUB_ACTIONS);
    } else assert.equal(calls.length, 0);
  }
});

test('Checkstyle CI cache preparation separates Lang versions and fails before repairing changed bytes', () => {
  python(String.raw`
import hashlib, runpy, sys, tempfile
from pathlib import Path
from unittest.mock import Mock, patch
import xml.etree.ElementTree as ET
sys.path.insert(0, sys.argv[1])
import checkstyle_live_support as s
Run = runpy.run_path(sys.path[0] + '/run_checkstyle_live_compatibility.py')['Run']
def rejects(callback):
    try:
        callback()
    except ValueError:
        return
    raise AssertionError('invalid preparation accepted')
with tempfile.TemporaryDirectory(prefix='checkstyle-preparation-mock-') as directory:
    sandbox = Path(directory); repository = sandbox / 'repository'; source = sandbox / 'checkout'
    rejects(lambda: s.prepare_repository(repository, source, True))
    assert not repository.exists()
    rejects(lambda: s.prepare_repository(source / 'nested-cache', source, False))
    rejects(lambda: s.prepare_repository(sandbox, source, False))
    assert not source.exists()
    s.prepare_repository(repository, source, False)
    assert repository.is_dir()
    s.prepare_repository(repository, source, True)
    projects = []
    for baseline in (True, False):
        project = ET.fromstring(s.preparation_xml(baseline)); projects.append(project)
        assert [e.tag.removeprefix(s.P) for e in project] == ['modelVersion', 'groupId', 'artifactId', 'version', 'packaging', 'dependencies']
        dependencies = project.findall('m:dependencies/m:dependency', s.NS)
        assert len(dependencies) == (59 if baseline else 2)
        coordinates = []
        for dependency in dependencies:
            coordinates.append(':'.join(dependency.findtext('m:' + n, namespaces=s.NS) for n in ['groupId', 'artifactId', 'type', 'version']))
            assert dependency.findtext('m:exclusions/m:exclusion/m:groupId', namespaces=s.NS) == '*'
            assert dependency.findtext('m:exclusions/m:exclusion/m:artifactId', namespaces=s.NS) == '*'
        expected = s.artifact_hashes(True) if baseline else {s.LANG: s.ARTIFACT_HASHES[s.LANG], **s.REPORT_SKIN}
        assert set(coordinates) == set(expected)
        assert len({':'.join(c.split(':')[:3]) for c in coordinates}) == len(coordinates)
        command = s.preparation_command(Path('/fixed/mvn'), sandbox, repository, False, baseline)
        assert command[-2:] == [s.PREPARE_GOAL, '-DexcludeTransitive=true'] and '-C' in command and '-N' in command and '-o' not in command
        assert command.count('-s') == command.count('-gs') == 1
        assert not any(c in command for c in ['clean', 'validate', 'verify', 'install', 'deploy'])
        rejects(lambda: s.preparation_command(Path('/fixed/mvn'), sandbox, repository, True, baseline))
    settings = ET.fromstring(s.CENTRAL_SETTINGS)
    assert len(settings) == 1 and len(settings[0]) == 1
    assert [c.text for c in settings[0][0]] == ['central', '*', 'https://repo.maven.apache.org/maven2']
    # Exercise real bounded byte validation with a tiny inert file.
    file = repository / 'local/mock/1/mock-1.jar'; file.parent.mkdir(parents=True); file.write_bytes(b'valid')
    pins = {'local:mock:jar:1': hashlib.sha256(b'valid').hexdigest()}
    assert len(s.cached_files(repository, pins, True)) == 1
    file.write_bytes(b'changed')
    rejects(lambda: s.cached_files(repository, pins, True))
    assert s.cached_files(repository, {'local:absent:jar:1': '0' * 64}, True) == {}
    rejects(lambda: s.cached_files(repository, {'local:absent:jar:1': '0' * 64}, False))
    globals_ = Run.prepare_artifacts.__globals__
    for offline, initially_complete, corrupt in [(False, False, False), (False, True, False),
                                                (True, True, False), (True, False, False), (False, False, True)]:
        run = Run.__new__(Run); run.sandbox, run.repository = sandbox, repository
        run.maven, run.offline, run.report, run.fixed = Path('/fixed/mvn'), offline, {}, {}
        events = []; state = {'complete': initially_complete}
        def artifacts(repository_, baseline=False, allow_missing=False):
            events.append(('hash', baseline, allow_missing))
            if corrupt: raise ValueError('artifact bytes differ')
            result = {c: {'sha256': h} for c, h in s.artifact_hashes(baseline).items()}
            if baseline and not state['complete']:
                if not allow_missing: raise ValueError('missing artifact')
                del result['org.apache.commons:commons-lang3:jar:3.17.0']
            return result
        def skin(repository_, allow_missing=False):
            events.append(('skin-hash', allow_missing)); return dict(s.REPORT_SKIN)
        def resolve(label, command, seconds, environment):
            assert not offline and seconds == 120 and command[-2:] == [s.PREPARE_GOAL, '-DexcludeTransitive=true']
            events.append(('resolve', label)); state['complete'] = True
        run.run = Mock(side_effect=resolve)
        with patch.dict(globals_, {'cached_artifacts': artifacts, 'cached_report_skin': skin}):
            if corrupt or (offline and not initially_complete):
                rejects(run.prepare_artifacts); run.run.assert_not_called()
            else:
                run.prepare_artifacts()
                preparation = run.report['cachePreparation']
                assert preparation['status'] == 'VERIFIED' and not preparation['acceptanceGoal']
                assert len(preparation['artifacts']) == 60 and len(preparation['reportResources']) == 1
                assert preparation['resolutionCommandsRun'] == (0 if initially_complete else 2)
                if not initially_complete:
                    assert events[:3] == [('hash', True, True), ('hash', False, True), ('skin-hash', True)]
                    assert [e[1] for e in events if e[0] == 'resolve'] == ['prepare-artifacts-baseline', 'prepare-artifacts-extras']
                    assert events[-3:] == [('hash', True, False), ('hash', False, False), ('skin-hash', False)]
`);
});

test('Checkstyle synthetic commands, copied XML and origin binding reject drift', () => {
  python(String.raw`
import re, sys
from pathlib import Path
import xml.etree.ElementTree as ET
sys.path.insert(0, sys.argv[1])
import checkstyle_live_support as s
def rejects(callback):
    try:
        callback()
    except ValueError:
        return
    raise AssertionError('invalid input accepted')
sandbox = Path('/tmp/checkstyle-mock')
repository = Path('/tmp/checkstyle-repository')
maven = Path('/tmp/maven/bin/mvn')
rejects(lambda: s.execution_mode({'GITHUB_ACTIONS': 'true'}, repository))
rejects(lambda: s.execution_mode({'CHECKSTYLE_PLUGIN_COMPATIBILITY': 'true'}, None))
assert s.execution_mode({'CHECKSTYLE_PLUGIN_COMPATIBILITY': 'true'}, repository)
assert not s.execution_mode({'CHECKSTYLE_PLUGIN_COMPATIBILITY': 'true', 'GITHUB_ACTIONS': 'true'}, repository)
command = s.maven_command(maven, sandbox, repository, True, 'fixtures', sandbox / 'child/pom.xml')
assert command[-3:] == ['-N', '-X', s.GOAL] and '-o' in command and '-s' in command and '-gs' in command
assert not any(value in command for value in ['clean', 'validate', 'verify', 'install', 'deploy'])
rejects(lambda: s.maven_command(maven, sandbox, repository, True, 'deploy', sandbox / 'child/pom.xml'))
rejects(lambda: s.maven_command(maven, sandbox, repository, True, 'fixtures', repository / 'pom.xml'))
rejects(lambda: s.maven_command(maven, sandbox, repository, True, 'models', repository / 'pom.xml', repository / 'output.xml'))
env = s.isolated_env(Path('/opt/reviewed-jdk'), sandbox)
assert set(env) == {'JAVA_HOME', 'PATH', 'LANG', 'MAVEN_SKIP_RC', 'HOME', 'TMPDIR', 'XDG_CONFIG_HOME'}
assert env['JAVA_HOME'] == '/opt/reviewed-jdk' and env['MAVEN_SKIP_RC'] == '1'
model = ET.fromstring('<project xmlns="' + s.NS['m'] + '"><properties>'
    '<maven.checkstyle.version>3.6.0</maven.checkstyle.version>'
    '<maven.checkstyle.commons-lang3.version>3.18.0</maven.checkstyle.commons-lang3.version>'
    '<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties></project>')
# Keep the dollar and brace in separate Python literals. This preserves exact
# Maven placeholder bytes without JavaScript interpolation or a raw backslash.
plugin = ET.fromstring('<plugin xmlns="' + s.NS['m'] + '"><artifactId>maven-checkstyle-plugin</artifactId>'
    '<version>$' + '{maven.checkstyle.version}</version><executions><execution><id>checkstyle</id>'
    '<phase>validate</phase><goals><goal>check</goal></goals></execution></executions>'
    '<configuration><includeTestSourceDirectory>true</includeTestSourceDirectory></configuration></plugin>')
parent = ET.fromstring(s.parent_xml(model, plugin))
copied = parent.find('m:build/m:plugins/m:plugin', s.NS)
assert s.normalized(copied) == s.normalized(plugin)
assert copied.findtext('m:version', namespaces=s.NS) == '$' + '{maven.checkstyle.version}'
assert len(s.ARTIFACT_HASHES) == 59 and all(re.fullmatch('[0-9a-f]{64}', value) for value in s.ARTIFACT_HASHES.values())
artifacts = {c: {'path': '/cache/' + c.split(':')[1] + '-' + c.split(':')[3] + '.jar', 'sha256': h}
             for c, h in s.ARTIFACT_HASHES.items()}
approved = {c: a for c, a in artifacts.items() if c not in s.CORE_IMPORTS}
realm = '[DEBUG] Populating class realm plugin>org.apache.maven.plugins:maven-checkstyle-plugin:3.6.0\n'
realm += ''.join('[DEBUG]   Included: ' + c + '\n' for c in approved)
assert s.realm_artifacts(realm, artifacts) == approved
rejects(lambda: s.realm_artifacts(realm + realm, artifacts))
rejects(lambda: s.realm_artifacts(realm.replace(s.LANG, 'org.apache.commons:commons-lang3:jar:3.17.0'), artifacts))
imports = '[DEBUG] Importing foreign packages into class realm plugin>org.apache.maven.plugins:maven-checkstyle-plugin:3.6.0\n[DEBUG]   Imported:  < maven.api\n'
assert s.realm_imports(imports) == ['< maven.api']
rejects(lambda: s.realm_imports(imports.replace('maven.api', 'foreign.realm')))
names = [('org/apache/maven/plugins/checkstyle/CheckstyleViolationCheckMojo', s.OWNER),
         ('com/puppycrawl/tools/checkstyle/Checker', s.ENGINE),
         ('com/puppycrawl/tools/checkstyle/TreeWalker', s.ENGINE),
         ('org/apache/commons/lang3/StringUtils', s.LANG)]
stderr = ''.join('CHECKSTYLE_ORIGIN\t' + name + '\tloader1\tClassRealm[plugin>org.apache.maven.plugins:maven-checkstyle-plugin:3.6.0]\tfile:' + approved[c]['path'] + '\n'
    + '[0.5s][info][class,load] ' + name.replace('/', '.') + ' source: file:' + approved[c]['path'] + '\n' for name, c in names)
stderr += ''.join('CHECKSTYLE_REALM_URL\tloader1\tfile:' + a['path'] + '\n' for a in approved.values())
assert len(s.natural_origins(realm, stderr, approved)['pluginRealmObservations']) == 4
rejects(lambda: s.natural_origins(realm, stderr.replace('commons-lang3-3.18.0.jar', 'commons-lang3-3.17.0.jar'), approved))
rejects(lambda: s.natural_origins(realm, stderr.replace('CHECKSTYLE_REALM_URL', 'NO_URL'), approved))
rejects(lambda: s.natural_origins(realm, stderr.replace('[0.5s][info][class,load]', 'NO_JVM'), approved))
rejects(lambda: s.natural_origins(realm + stderr, stderr, approved))
missing = dict(approved); del missing[s.LANG]
rejects(lambda: s.natural_origins(realm, stderr, missing))
`);
});

test('Checkstyle owned process groups are cleaned after failed and successful exited leaders', () => {
  python(String.raw`
import io, signal, sys, tempfile
from pathlib import Path
from unittest.mock import MagicMock, patch
sys.path.insert(0, sys.argv[1])
import checkstyle_live_support as s
with tempfile.TemporaryDirectory(prefix='checkstyle-command-mock-') as directory:
    for failing in (True, False):
        process = MagicMock()
        process.pid, process.returncode = 43210, 0
        process.poll.return_value = 0
        process.stdout, process.stderr = io.BytesIO(), io.BytesIO()
        selector = MagicMock(); selector.__enter__.return_value = selector
        selector.get_map.return_value = {'lingering-descendant': True} if failing else {}
        if failing:
            selector.select.side_effect = ValueError('simulated timeout with an exited leader')
        with patch.object(s.subprocess, 'Popen', return_value=process), patch.object(s.selectors, 'DefaultSelector', return_value=selector), patch.object(s.os, 'killpg') as kill:
            commands = s.Commands(Path(directory), {}, 5)
            try:
                status, _, _ = commands.run('failure' if failing else 'success', ['/never-executed'], 1)
                assert not failing and status == 0
            except ValueError:
                assert failing
            kill.assert_called_once_with(43210, signal.SIGKILL)
            assert commands.results[0]['processGroupCleaned'] is True
            assert commands.results[0]['captureComplete'] is (not failing)
`);
});
