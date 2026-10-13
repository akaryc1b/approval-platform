import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const fixtures = fileURLToPath(new URL('./fixtures/', import.meta.url));

function python(source) {
  const result = spawnSync('/usr/bin/python3', ['-I', '-S', '-B', '-c', source, fixtures], {
    env: { PATH: '/usr/bin:/bin', LANG: 'C.UTF-8' },
    encoding: 'utf8', timeout: 30000, maxBuffer: 2 * 1024 * 1024,
  });
  assert.equal(result.status, 0, `${result.error?.message ?? ''}\n${result.stdout}\n${result.stderr}`);
  return result.stdout;
}

test('Boot HTTP plan starts no processes and never opts itself into execution', () => {
  const plan = JSON.parse(python(String.raw`
import runpy, subprocess, sys
def blocked(*args, **kwargs): raise AssertionError('plan launched a process')
subprocess.Popen = blocked
fixture = sys.argv[1]
sys.argv = ['run_boot_http_compatibility.py']
runpy.run_path(fixture + '/run_boot_http_compatibility.py', run_name='__main__')
`));
  assert.equal(plan.execution, 'NOT_RUN');
  assert.equal(plan.explicitOptIn, 'BOOT_HTTP_COMPATIBILITY=true');
  assert.equal(plan.offline, true);
  assert.equal(plan.aggregateTimeoutSeconds, 240);
  assert.equal(plan.commandTimeoutSeconds.httpProbe, 45);
  assert.deepEqual(plan.variants, { baseline: ['5.5.2', '5.3.6'], candidate: ['5.6.3', '5.4.3'] });
});

test('Boot HTTP input validation rejects wrong pair, duplicate JARs and changed actual bytes', () => {
  python(String.raw`
import copy, hashlib, json, runpy, sys, tempfile
from pathlib import Path
s = runpy.run_path(sys.argv[1] + '/run_boot_http_compatibility.py')
def rejects(callback):
    try: callback()
    except ValueError: return
    raise AssertionError('invalid input accepted')
with tempfile.TemporaryDirectory(prefix='boot-http-mock-') as directory:
    root = Path(directory); report = root / 'plugins.txt'; report.write_text('inert mocked owner report')
    coordinates = [s['OWNER'], 'org.springframework.boot:spring-boot-buildpack-platform:jar:4.0.8',
        'org.apache.httpcomponents.client5:httpclient5:jar:5.5.2',
        'org.apache.httpcomponents.core5:httpcore5:jar:5.3.6',
        'org.apache.httpcomponents.core5:httpcore5-h2:jar:5.3.6',
        'tools.jackson.core:jackson-core:jar:3.1.7', 'tools.jackson.core:jackson-databind:jar:3.1.7']
    artifacts = []
    for coordinate in coordinates:
        group, name, kind, version = coordinate.split(':')
        jar = root / (name + '-' + version + '.jar'); jar.write_bytes(b'inert test bytes')
        artifacts.append({'coordinate': coordinate, 'path': str(jar), 'sha256': s['digest'](jar)})
    data = {'schemaVersion': 'BOOT_HTTP_RESOLVED_REALM_V1', 'owner': s['OWNER'],
        'sourceOwnerReport': {'path': str(report), 'sha256': s['digest'](report)}, 'artifacts': artifacts, 'importedArtifacts': []}
    manifest = root / 'realm.json'
    def load(value, variant='baseline'):
        manifest.write_text(json.dumps(value)); return s['realm'](manifest, variant)
    assert load(data) == artifacts
    rejects(lambda: load(data, 'candidate'))
    duplicate = copy.deepcopy(data); duplicate['artifacts'].append(duplicate['artifacts'][0])
    rejects(lambda: load(duplicate))
    wrong = copy.deepcopy(data); wrong['owner'] = 'other:owner:jar:1'
    rejects(lambda: load(wrong))
    Path(artifacts[2]['path']).write_bytes(b'changed')
    rejects(lambda: load(data))
    Path(artifacts[2]['path']).write_bytes(b'inert test bytes')
    report.write_text('changed owner report')
    rejects(lambda: load(data))
`);
});

test('Boot HTTP reviewed execution fails closed on absent opt-in and incomplete seal', () => {
  python(String.raw`
import os, runpy, sys
s = runpy.run_path(sys.argv[1] + '/run_boot_http_compatibility.py')
for callback in (lambda: s['execute']({}), lambda: s['check_seal']({'schemaVersion': 'wrong'})):
    try: callback()
    except ValueError: pass
    else: raise AssertionError('unreviewed execution accepted')
assert 'BOOT_HTTP_COMPATIBILITY' not in os.environ
`);
});

test('Boot fixture initialization closes already constructed listeners without shutting down unstarted servers', () => {
  python(String.raw`
import runpy, sys
from pathlib import Path
from unittest.mock import patch
s = runpy.run_path(sys.argv[1] + '/run_boot_http_compatibility.py')
events = []
class Server:
    server_address = ('127.0.0.1', 1234)
    def __init__(self, *args): self.closed = False; events.append('constructed')
    def server_close(self): self.closed = True; events.append('closed')
    def shutdown(self): events.append('shutdown')
    def fileno(self): return -1 if self.closed else 4
    def serve_forever(self, **kwargs): pass
class Thread:
    def __init__(self, **kwargs): self.started = False
    def start(self): self.started = True
    def join(self, **kwargs): pass
    def is_alive(self): return False
def fail(*args): raise OSError('mocked Unix listener construction failure')
with patch.dict(s['Fixtures'].__init__.__globals__, {'TcpServer': Server, 'UnixServer': fail}), patch('threading.Thread', Thread):
    try: s['Fixtures'](Path('/tmp/nonexistent-boot-http-mock'), Path('/tmp/unused'))
    except OSError: pass
    else: raise AssertionError('fixture failure was swallowed')
assert events == ['constructed', 'shutdown', 'closed'], events
events.clear()
class NoStart(Thread):
    def start(self): raise OSError('mocked thread start failure')
with patch.dict(s['Fixtures'].__init__.__globals__, {'TcpServer': Server}), patch('threading.Thread', NoStart):
    try: s['Fixtures'](Path('/tmp/nonexistent-boot-http-mock'), Path('/tmp/unused'))
    except OSError: pass
    else: raise AssertionError('thread start failure was swallowed')
assert events == ['constructed', 'closed'], events
`);
});

test('Boot goal fixtures copy only the actual managed plugin with a narrow baseline reversal', () => {
  python(String.raw`
import runpy, sys
import xml.etree.ElementTree as ET
s = runpy.run_path(sys.argv[1] + '/run_boot_goal_compatibility.py')
for variant in ('baseline', 'candidate'):
    xml = s['fixture_pom'](variant); root = ET.fromstring(xml)
    ns = s['NS']
    assert root.find('m:parent', ns) is None
    assert root.find('m:dependencies', ns) is None
    assert root.find('m:repositories', ns) is None
    assert root.find('m:profiles', ns) is None
    plugins = root.findall('m:build/m:plugins/m:plugin', ns)
    assert len(plugins) == 1
    plugin = plugins[0]
    assert plugin.findtext('m:artifactId', namespaces=ns) == 'spring-boot-maven-plugin'
    assert plugin.findtext('m:version', namespaces=ns) == '4.0.8'
    assert plugin.find('m:executions', ns) is None
    deps = {d.findtext('m:artifactId', namespaces=ns): d.findtext('m:version', namespaces=ns)
            for d in plugin.findall('m:dependencies/m:dependency', ns)}
    expected = {'jackson-core': '3.1.7', 'jackson-databind': '3.1.7'}
    if variant == 'candidate': expected['httpclient5'] = '5.6.3'
    assert deps == expected
    assert '$' + '{' not in xml
coordinates = ['org.apache.httpcomponents.client5:httpclient5:jar:5.6.3',
    'org.apache.httpcomponents.core5:httpcore5:jar:5.4.3', 'org.apache.httpcomponents.core5:httpcore5-h2:jar:5.4.3',
    'tools.jackson.core:jackson-core:jar:3.1.7', 'tools.jackson.core:jackson-databind:jar:3.1.7']
artifacts = [{'coordinate': coordinate} for coordinate in coordinates]
marker = '[DEBUG] Populating class realm plugin>org.springframework.boot:spring-boot-maven-plugin:4.0.8\n'
stdout = marker + ''.join('[DEBUG]   Included: ' + coordinate + '\n' for coordinate in coordinates)
assert s['boot_realm'](stdout, artifacts) == coordinates
for changed in (stdout + stdout, stdout.replace('5.6.3', '5.5.2'), stdout.replace('spring-boot-maven-plugin', 'another-plugin'),
        stdout + '[DEBUG]   Included: org.apache.httpcomponents.client5:httpclient5:jar:5.5.2\n'):
    try: s['boot_realm'](changed, artifacts)
    except ValueError: pass
    else: raise AssertionError('wrong actual Boot goal realm accepted')
reverse = {new: old for old, new in s['HTTP_REPLACEMENTS'].items()}
baseline = [reverse.get(value, value) for value in coordinates]
assert s['compare_realms'](baseline, coordinates) == {'membershipChangedOnlyByHttpTrio': True,
    'nonHttpOrderEqual': True, 'normalizedFullOrderEqual': True}
assert s['compare_realms'](baseline, list(reversed(coordinates)))['nonHttpOrderEqual'] is False
for changed in (coordinates + ['extra:artifact:jar:1'], [value.replace('3.1.7', '3.1.8') for value in coordinates]):
    try: s['compare_realms'](baseline, changed)
    except ValueError: pass
    else: raise AssertionError('unreviewed full realm drift accepted')
`);
});

// Append to the existing Boot harness after review. These fixtures are synthetic.
import { assertBootLiveReceipt, emitBootAdapterOutput } from './boot-http-plugin-live-compatibility.test.mjs';

test('Boot HTTP failure witness retains only bounded allowlisted diagnostic fields', () => {
  python(String.raw`
import http.server, runpy, socket, ssl, subprocess, sys
def blocked(*args, **kwargs): raise AssertionError('diagnostic mock launched a process/socket')
subprocess.Popen = socket.socket = blocked
s = runpy.run_path(sys.argv[1] + '/run_boot_http_compatibility.py')
marker = 'BOOT_HTTP_FAILURE\thttp\thead-empty-response\tjava.lang.NullPointerException'
expected = {'markerStatus': 'RECORDED', 'stage': 'http', 'assertion': 'head-empty-response',
            'exceptionClasses': ['java.lang.NullPointerException']}
assert s['probe_failure']('unretained arbitrary text\n' + marker + '\n') == expected
assert s['probe_failure']('exception message containing private data') == {'markerStatus': 'ABSENT'}
for value in (marker + '\n' + marker, marker + '\tprivate-value', marker.replace('http\t', 'private-endpoint\t'),
              marker.replace('head-empty-response', '/private/path'), marker.replace('java.lang.NullPointerException', 'private.Token'),
              marker + ',OTHER' * 8, marker + 'x' * 1024):
    assert s['probe_failure'](value) == {'markerStatus': 'INVALID'}, value
chain = 'BOOT_HTTP_FAILURE\ttls-hostname\ttls-rejection\t' + ','.join([
    'org.springframework.boot.buildpack.platform.docker.transport.DockerConnectionException',
    'javax.net.ssl.SSLHandshakeException', 'OTHER'])
assert s['probe_failure'](chain)['exceptionClasses'][-1] == 'OTHER'
`);
});

test('Boot adapter serializes labelled channels on one stream without changing producer bytes', async () => {
  const writes = [];
  let finishStdout;
  const stream = {
    write(text, callback) {
      writes.push(text);
      if (text.startsWith('BOOT_ADAPTER_CHANNEL stdout\n')) finishStdout = callback;
      else callback();
    },
  };
  const stdout = '  BOOT_HTTP_RESULT ' + JSON.stringify({ status: 'FAILED', retained: 'x'.repeat(128 * 1024) }) + '\n';
  const stderr = 'Traceback: synthetic stderr\n';
  const emission = emitBootAdapterOutput({ stdout, stderr }, stream);
  assert.deepEqual(writes, ['BOOT_ADAPTER_CHANNEL stdout\n' + stdout]);
  finishStdout();
  await emission;
  assert.deepEqual(writes, ['BOOT_ADAPTER_CHANNEL stdout\n' + stdout, 'BOOT_ADAPTER_CHANNEL stderr\n' + stderr]);
  writes.length = 0;
  await emitBootAdapterOutput({ stdout: '', stderr: 'last line' }, stream);
  assert.deepEqual(writes, ['BOOT_ADAPTER_CHANNEL stderr\nlast line\n']);
  await assert.rejects(emitBootAdapterOutput({ stdout: 'failed', stderr }, {
    write(text, callback) {
      assert.ok(text.startsWith('BOOT_ADAPTER_CHANNEL stdout\n'), 'stderr written after failed stdout write');
      callback(new Error('synthetic write failure'));
    },
  }), /synthetic write failure/);
});

test('Boot portable adapter plan and absent opt-in start no process or socket', () => {
  const plan = JSON.parse(python(String.raw`
import http.server, os, runpy, socket, ssl, subprocess, sys
def blocked(*args, **kwargs): raise AssertionError('inert adapter launched a process/socket')
subprocess.Popen = socket.socket = blocked
fixture = sys.argv[1]
adapter = runpy.run_path(fixture + '/run_boot_live_compatibility.py')
os.environ.pop('BOOT_HTTP_COMPATIBILITY', None)
try: adapter['execute'](None)
except ValueError as error: assert 'opt-in' in str(error)
else: raise AssertionError('unreviewed execution accepted')
sys.argv = ['run_boot_live_compatibility.py']
runpy.run_path(fixture + '/run_boot_live_compatibility.py', run_name='__main__')
`));
  assert.equal(plan.execution, 'NOT_RUN');
  assert.equal(plan.localAlwaysOffline, true);
  assert.equal(plan.unixFailureIsFatal, true);
  assert.equal(plan.freshOfflineOwnerReports, 2);
  assert.equal(plan.prepareCommandsMaximum, 2);
});

test('Boot portable pins and actual owner parsing reject shape, byte, membership and order drift', () => {
  python(String.raw`
import copy, http.server, json, runpy, socket, ssl, subprocess, sys, tempfile
from pathlib import Path
def blocked(*args, **kwargs): raise AssertionError('mock attempted a process/socket')
subprocess.Popen = socket.socket = blocked
s = runpy.run_path(sys.argv[1] + '/run_boot_live_compatibility.py')
pins = s['read_pins']()
assert len(pins['artifactHashes']) == 42
def rejects(callback):
    try: callback()
    except ValueError: return
    raise AssertionError('invalid input accepted')
with tempfile.TemporaryDirectory(prefix='boot-adapter-mock-') as directory:
    root = Path(directory); pinfile = root / 'pins.json'; report = root / 'owner.txt'
    def check(value):
        pinfile.write_text(json.dumps(value)); return s['read_pins'](pinfile)
    assert check(pins) == pins
    for kind in ('owner', 'extra-variant', 'missing-variant', 'duplicate', 'bad-hash', 'extra-hash'):
        changed = copy.deepcopy(pins)
        if kind == 'owner': changed['owner'] = 'other:plugin:jar:1'
        if kind == 'extra-variant': changed['variants']['extra'] = changed['variants']['baseline']
        if kind == 'missing-variant': del changed['variants']['candidate']
        if kind == 'duplicate': changed['variants']['baseline'][1] = changed['variants']['baseline'][0]
        if kind == 'bad-hash': changed['artifactHashes'][next(iter(changed['artifactHashes']))] = 'no'
        if kind == 'extra-hash': changed['artifactHashes']['other:jar:jar:1'] = '0' * 64
        rejects(lambda: check(changed))
    for variant in ('baseline', 'candidate'):
        expected = pins['variants'][variant]
        header = '   ' + s['http'].OWNER + '\n'
        text = header + ''.join('      ' + item + '\n' for item in expected)
        report.write_text(text + '   other:plugin:jar:1\n      other:jar:jar:1\n')
        assert s['owner_coordinates'](report, expected) == expected
        for changed in (text + text, text.replace(header, ''), text.replace(expected[1], expected[0]),
                        header + ''.join('      ' + item + '\n' for item in reversed(expected)),
                        header + ''.join('      ' + item + '\n' for item in expected[:-1])):
            report.write_text(changed)
            rejects(lambda: s['owner_coordinates'](report, expected))
    jar = root / 'mock/artifact/1/artifact-1.jar'; jar.parent.mkdir(parents=True); jar.write_bytes(b'inert JAR fixture')
    digest = s['digest'](jar)
    assert s['cached_files'](root, {'mock:artifact:jar:1': digest}, False)
    rejects(lambda: s['cached_files'](root, {'mock:artifact:jar:1': '0' * 64}, False))
    jar.write_bytes(b'tampered fixture')
    rejects(lambda: s['cached_files'](root, {'mock:artifact:jar:1': digest}, False))
`);
});

test('Boot tiny projects and Central-only preparation commands remain bounded and separate from offline owners', () => {
  python(String.raw`
import http.server, runpy, socket, ssl, subprocess, sys, tempfile
import xml.etree.ElementTree as ET
from pathlib import Path
def blocked(*args, **kwargs): raise AssertionError('mock attempted a process/socket')
subprocess.Popen = socket.socket = blocked
s = runpy.run_path(sys.argv[1] + '/run_boot_live_compatibility.py')
for variant in ('baseline', 'candidate'):
    project = ET.fromstring(s['project_text'](variant)); ns = s['NS']
    assert project.findtext('m:packaging', namespaces=ns) == 'pom'
    assert all(project.find('m:' + field, ns) is None for field in ('parent', 'dependencies', 'repositories', 'profiles'))
    plugins = project.findall('m:build/m:plugins/m:plugin', ns)
    assert [p.findtext('m:artifactId', namespaces=ns) for p in plugins] == ['spring-boot-maven-plugin', 'maven-jar-plugin']
    assert plugins[1].findtext('m:version', namespaces=ns) == '3.5.0'
    assert all(p.find('m:executions', ns) is None for p in plugins)
settings = ET.fromstring(s['CENTRAL_SETTINGS']); namespace = {'s': settings.tag[1:].split('}')[0]}
assert settings.findtext('s:mirrors/s:mirror/s:mirrorOf', namespaces=namespace) == '*'
assert settings.findtext('s:mirrors/s:mirror/s:url', namespaces=namespace) == 'https://repo.maven.apache.org/maven2'
with tempfile.TemporaryDirectory(prefix='boot-command-mock-') as directory:
    sandbox = Path(directory); report = sandbox / 'owner.txt'; maven = Path('/mock/maven/bin/mvn'); repo = Path('/mock/cache')
    offline = s['resolve_command'](maven, sandbox, repo, 'baseline', report, False)
    online = s['resolve_command'](maven, sandbox, repo, 'candidate', report, True, ci=True)
    assert '-o' in offline and '-o' not in online
    assert s['RESOLVE'] in offline and s['RESOLVE'] in online
    for command, setting in ((offline, 'empty-settings.xml'), (online, 'central-settings.xml')):
        assert command[command.index('-s') + 1] == command[command.index('-gs') + 1] == str(sandbox / setting)
        assert '-C' in command and '-N' in command and '-DappendOutput=false' in command
        assert '-DexcludeTransitive=false' in command
        assert command[command.index('-t') + 1] == command[command.index('-gt') + 1] == str(sandbox / 'empty-toolchains.xml')
    assert '-DincludeArtifactIds=spring-boot-maven-plugin,maven-jar-plugin' in online
    assert '-DincludeArtifactIds=spring-boot-maven-plugin' in offline
    try: s['resolve_command'](maven, sandbox, repo, 'candidate', report, True)
    except ValueError: pass
    else: raise AssertionError('local online preparation accepted')
    report.write_text('existing')
    try: s['resolve_command'](maven, sandbox, repo, 'baseline', report, False)
    except ValueError: pass
    else: raise AssertionError('stale owner report accepted')
`);
});

test('Boot runtime inventory includes cached bytes and caps nested deadlines using mocked boundaries', () => {
  python(String.raw`
import http.server, runpy, socket, ssl, subprocess, sys, tempfile, types
from pathlib import Path
from unittest.mock import patch
def blocked(*args, **kwargs): raise AssertionError('mock attempted a process/socket')
subprocess.Popen = socket.socket = blocked
s = runpy.run_path(sys.argv[1] + '/run_boot_live_compatibility.py')
with tempfile.TemporaryDirectory(prefix='boot-runtime-mock-') as directory:
    root = Path(directory); source = root / 'mock.py'; cached = root / '__pycache__/mock.pyc'
    source.write_text('# inert source\n'); cached.parent.mkdir(); cached.write_bytes(b'inert cached bytes')
    module = types.SimpleNamespace(__file__=str(source), __cached__=str(cached), __spec__=types.SimpleNamespace(origin=str(source)))
    frozen = types.SimpleNamespace(__spec__=types.SimpleNamespace(origin='frozen'))
    with patch.dict(sys.modules, {'boot_inventory_mock': module, 'boot_frozen_mock': frozen}):
        origins, files = s['python_origins']()
    assert source.resolve() in files and cached.resolve() in files
    assert next(row for row in origins if row['module'] == 'boot_frozen_mock')['origin'] == 'frozen'
    assert cached in s['tree_files'](root, {'.pyc'})
    before = s['hashes']([cached]); cached.write_bytes(b'changed cached bytes')
    assert s['hashes']([cached]) != before
    def mocked_tree(path, suffixes=None):
        return [Path(path) / ('mock.pyc' if suffixes else 'mock-input')]
    with patch.dict(s['runtime_files'].__globals__, {'tree_files': mocked_tree}), \
            patch.object(Path, 'read_text', return_value='00-ff r-xp 0 00:00 0 /mock/native.so\n'):
        files = s['runtime_files'](root / 'jdk', root / 'maven/bin/mvn', [cached])
    assert cached in files and Path(sys.executable).resolve() in files
    for name in ('/usr/bin/openssl', '/usr/bin/uname', '/usr/bin/dirname', '/mock/native.so'):
        assert Path(name) in files
    assert root / 'jdk/bin/java' in files and root / 'jdk/bin/javac' in files
calls = []
with patch.object(s['time'], 'monotonic', return_value=100):
    assert s['bounded_deadline'](130, lambda seconds: calls.append(seconds) or 'handlers', 240) == 'handlers'
    s['bounded_deadline'](400, lambda seconds: calls.append(seconds), 240)
    try: s['bounded_deadline'](100, lambda seconds: calls.append(seconds), 240)
    except ValueError: pass
    else: raise AssertionError('expired deadline accepted')
assert calls == [30, 240]
`);
});

function syntheticBootReceipt() {
  const hash = '0'.repeat(64);
  const artifacts = Array.from({ length: 39 }, (_, index) => ({
    coordinate: index < 3 ? `mock:jackson-${index}:jar:1` : `mock:artifact-${index}:jar:1`,
    path: `/mock/artifact-${index}.jar`, sha256: hash,
  }));
  const transport = {
    status: 'PASSED', compiledAgainst: 'baseline', existingJacksonProbe: 'PASSED', artifacts,
    fixtureObservations: Object.fromEntries([['http', 15], ['unix', 15], ['tls', 2]]
      .map(([name, count]) => [name, { events: Array(count).fill({}) }])),
    fixtureCleanup: Object.fromEntries(['http', 'unix', 'tls'].map(name => [name, {
      listenerClosed: true, workerThreadsStopped: true, activeConnections: 0,
    }])),
    passedAssertions: ['HTTP complete', 'Unix complete', 'TLS trusted localhost', 'TLS repeated request',
      'TLS hostname mismatch rejected', 'TLS untrusted certificate rejected'],
    staticLinkage: { members: Array(49).fill(['inert synthetic member']), inventory: {
      inspectedBootClassFiles: 260, nonArrayHcClassReferences: 32, memberReferences: 49,
      staticInstanceUses: 49, memberSetSha256: 'b0cc9e98b73157c751911403e5f7cc09103ce25796ee6ff53f36ee745bac9300',
    } },
  };
  const goals = { status: 'PASSED', archiveMain: 'BOOT_GOAL_MAIN_OK', jarSha256: hash,
    actualBootRealms: { help: artifacts.slice(0, 36).map(item => item.coordinate),
      repackage: artifacts.slice(0, 36).map(item => item.coordinate) }, naturalOrigins: [{}, {}, {}] };
  const pair = item => ({ baseline: structuredClone(item), candidate: structuredClone(item) });
  return {
    status: 'PASSED', scannerEvidence: false, coverageGaps: [],
    cachePreparation: { status: 'VERIFIED', acceptanceGoal: false, resolutionCommandsRun: 0,
      mode: 'offline-existing-cache', artifacts: Object.fromEntries(Array.from({ length: 42 }, (_, index) => [`mock-${index}`, {}])) },
    ownerReports: pair({ freshOfflineCapture: true, orderedCoordinates: artifacts.map(item => item.coordinate), sha256: hash }),
    stages: {
      transport: { status: 'PASSED', linkageOnly: false, temporaryTlsRemoved: true,
        linkageNegativeControl: 'PASSED: synthetic negative-control marker', variants: pair(transport) },
      goals: { status: 'PASSED', variants: pair(goals), naturalRealmComparison: {
        help: { membershipChangedOnlyByHttpTrio: true, nonHttpOrderEqual: true, normalizedFullOrderEqual: false },
        repackage: { membershipChangedOnlyByHttpTrio: true, nonHttpOrderEqual: true, normalizedFullOrderEqual: false },
      } },
    },
  };
}

test('Boot receipt verifier accepts only a complete synthetic shape, including differing full realm order', () => {
  const receipt = syntheticBootReceipt();
  assert.equal(assertBootLiveReceipt(receipt, { ci: false }), receipt);
});

for (const [name, mutate] of [
  ['diagnostic top status', value => { value.status = 'DIAGNOSTIC_PASSED'; }],
  ['diagnostic transport status', value => { value.stages.transport.status = 'DIAGNOSTIC_PASSED'; }],
  ['linkage-only mode', value => { value.stages.transport.linkageOnly = true; }],
  ['missing owner', value => { delete value.ownerReports.baseline; }],
  ['extra owner', value => { value.ownerReports.extra = value.ownerReports.baseline; }],
  ['missing transport stage', value => { delete value.stages.transport; }],
  ['missing goal stage', value => { delete value.stages.goals; }],
  ['missing transport variant', value => { delete value.stages.transport.variants.baseline; }],
  ['missing goal variant', value => { delete value.stages.goals.variants.candidate; }],
  ['member count mismatch', value => { value.stages.transport.variants.candidate.staticLinkage.inventory.memberReferences = 48; }],
  ['member digest mismatch', value => { value.stages.transport.variants.baseline.staticLinkage.inventory.memberSetSha256 = '0'.repeat(64); }],
  ['Unix omitted', value => { delete value.stages.transport.variants.baseline.fixtureObservations.unix; }],
  ['Unix assertion omitted', value => { value.stages.transport.variants.baseline.passedAssertions.splice(1, 1); }],
  ['cleanup omitted', value => { delete value.stages.transport.variants.candidate.fixtureCleanup.unix; }],
  ['cleanup incomplete', value => { value.stages.transport.variants.candidate.fixtureCleanup.http.activeConnections = 1; }],
  ['wrong natural realm size', value => { value.stages.goals.variants.baseline.actualBootRealms.help.pop(); }],
  ['non-HTTP natural order changed', value => { value.stages.goals.naturalRealmComparison.help.nonHttpOrderEqual = false; }],
]) test(`Boot receipt verifier rejects ${name}`, () => {
  const receipt = syntheticBootReceipt(); mutate(receipt);
  assert.throws(() => assertBootLiveReceipt(receipt, { ci: false }));
});
