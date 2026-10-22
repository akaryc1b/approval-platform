import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { gzipSync } from 'node:zlib';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { decodeProtocol, liveInvocation, maximumReceiptBytes, portableMarker, readReceipt, resultMarker,
  retainReceipts, validateReceipt } from './fixtures/resources-flatten-live-support.mjs';

const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const fixture = fileURLToPath(new URL('./fixtures/resources_flatten_compatibility.py', import.meta.url));
const fixtureDirectory = fileURLToPath(new URL('./fixtures/', import.meta.url));
function python(body) {
  const prefix = String.raw`import sys,runpy,subprocess,socket,os
from pathlib import Path

def blocked(*args,**kwargs):raise AssertionError('pure RF test attempted process or network')
subprocess.Popen=socket.socket=blocked
s=runpy.run_path(sys.argv[1])
root=Path(sys.argv[2]);here=Path(sys.argv[3])
def rejects(fn):
 try:fn()
 except (ValueError,AssertionError,KeyError,TimeoutError):return
 raise AssertionError('unexpected acceptance')
`;
  const run = spawnSync('/usr/bin/python3', ['-I', '-S', '-B', '-c', prefix + body, fixture, ROOT, fixtureDirectory],
    { encoding: 'utf8', timeout: 30000, maxBuffer: 2 * 1024 * 1024 });
  assert.equal(run.status, 0, run.stdout + run.stderr + (run.error?.message || ''));
}

test('Resources/Flatten retains the actually compiled observer and accepted observation protocol bytes', () => {
  for (const [name, expected] of [
    ['ResourcesFlattenRealmObserver.java', 'e64f00f6052c17e6de2ebc22f21d9a2b00afecbaf27f04c5a24cf4c992fdfea2'],
    ['ResourcesFlattenCompletionSpy.java', 'aa151623bdcdce1dfbdd262bab9f0f425a67c2ec11ba206727cef3eee4e1a3a1'],
    ['resources_flatten_observations.py', '036a55d5cd2ed1934e479f37090dd7dfc474644d4e85495949879b960848043a'],
  ]) assert.equal(createHash('sha256').update(readFileSync(new URL('./fixtures/' + name, import.meta.url))).digest('hex'), expected);
});

test('Resources/Flatten import and exact 20-goal plan are inert and retain the shared budget', () => {
  python(String.raw`
p=s['PLAN']
assert p['execution']=='NOT_RUN' and p['mavenProcessCount']==20 and p['compileCount']==1
assert p['commandTimeoutSeconds']==90 and p['compileTimeoutSeconds']==30
assert p['aggregateTimeoutSeconds']==1800 and p['outerTimeoutSeconds']==1830
assert p['offline'] and not p['downloadAllowed'] and not p['scannerEvidence']
assert len(p['casesPerVariant'])==len(set(p['casesPerVariant']))==10
assert p['casesPerVariant'][-1]=='sdk-production-context'
assert p['receiptMaximumBytes']==16*1024*1024 and p['protocolMaximumBytes']==1024*1024
assert p['protocolCompressedMaximumBytes']==256*1024 and p['protocolRowMaximum']==2000
assert p['protocolTotalMaximumBytes']==8*1024*1024
for value in (None,'false','1','TRUE'):
 if value is None:os.environ.pop('RESOURCES_FLATTEN_COMPATIBILITY',None)
 else:os.environ['RESOURCES_FLATTEN_COMPATIBILITY']=value
 rejects(lambda:s['execute']({}))
`);
});

test('exact production SDK fixture preserves independent source bytes, original parent shape and direct goal output oracle', () => {
  python(String.raw`
import tempfile,hashlib,xml.etree.ElementTree as ET
f=runpy.run_path(str(here/'resources_flatten_fixtures.py'))
with tempfile.TemporaryDirectory(prefix='rf-production-fixture-') as temp:
 base=Path(temp)
 for variant in ('baseline','candidate'):
  source=base/(variant+'-source');(source/'integrations/host-sdk').mkdir(parents=True)
  for name,old in [('pom.xml','docs/operations/assembly-enforcer-lang-evidence/candidate-root-pom.xml'),
                   ('integrations/host-sdk/pom.xml','docs/operations/resources-flatten-evidence/accepted-main905-host-sdk-pom.xml')]:
   (source/name).write_bytes((root/(old if variant=='baseline' else name)).read_bytes())
  work=base/variant;case=f['production_sdk_case'](work,source,variant)
  assert case['project']==work/'integrations/host-sdk/pom.xml' and len(case['projects'])==1
  for name in ('pom.xml','integrations/host-sdk/pom.xml'):
   assert (work/name).read_bytes()==(source/name).read_bytes()
  row=case['projects'][str(work/'integrations/host-sdk')]
  current=ET.fromstring(row['source']);oldfields,_=runpy.run_path(str(here/'resources_flatten_observations.py'))['source_state'](row['source'])
  assert oldfields['originalParentVersion']=='$'+'{revision}' and oldfields['originalParentRelativePath']=='../../pom.xml'
  revision=ET.parse(source/'pom.xml').getroot().findtext('m:properties/m:revision',namespaces=f['NS'])
  assert row['flattened']==row['source'].replace(b'$'+b'{revision}',revision.encode())
  assert b'$'+b'{revision}' not in row['flattened']
  assert set(case['sourcePoms'])=={str(work/'pom.xml'),str(work/'integrations/host-sdk/pom.xml')}
  for parent in (work,work/'integrations/host-sdk'):assert (parent/'neighbor-sentinel.txt').read_bytes()==b'keep neighbor\n'
  # Fixture generation is not an execution claim: no flattened file is created.
  assert not Path(row['target']).exists()
`);
});

test('mocked orchestration keeps all original calls plus exact SDK pair and fails closed without executing tools', () => {
  python(String.raw`
import contextlib,io,json,tempfile,types,hashlib
original_f=runpy.run_path(str(here/'resources_flatten_fixtures.py'))
g=s['execute'].__globals__;saved={key:g[key] for key in ('check_seal','support','run_path')}
try:
 for mode in ('complete','goal-exit','wrong-sdk-cause','changed-fixture','storage','incomplete-capture','cleanup-final','goal-exit-cleanup-final','command-error-cleanup'):
  with tempfile.TemporaryDirectory(prefix='rf-command-mock-') as temp:
   base=Path(temp);output=base/'result';calls=[];fresh=[]
   config={k:str(base/k) for k in ('baseline','candidate','maven','jdk','repository')};config['output']=str(output)
   config['maven']=str(base/'maven/bin/mvn')
   for variant in ('baseline','candidate'):
    source=Path(config[variant]);(source/'integrations/host-sdk').mkdir(parents=True)
    for name,old in [('pom.xml','docs/operations/assembly-enforcer-lang-evidence/candidate-root-pom.xml'),
                     ('integrations/host-sdk/pom.xml','docs/operations/resources-flatten-evidence/accepted-main905-host-sdk-pom.xml')]:
     (source/name).write_bytes((root/(old if variant=='baseline' else name)).read_bytes())
   inv={'pins':{},'owners':{owner:{'effectiveLinkageRows':['consumer\tmock\tfile:/mock\t'+'0'*64]}for owner in ('resources','flatten')}}
   seal={'config':config,'source':{'scope':'pure command mock'},'harnessHashes':{},'runtimeHashes':{},'cacheHashes':{},'inventory':inv}
   class Commands:
    def __init__(self,work,environment,seconds):assert seconds==1800;self.results=[]
    def run(self,label,command,seconds,extra=None):
     calls.append((label,command,seconds,extra))
     if mode=='command-error-cleanup' and label=='baseline-resources-success':raise ValueError('mock command error')
     exit_code=0;stdout='';stderr='RF_MOCK_ONLY\n'
     if label=='compile-observer':
      assert seconds==30 and command[0].endswith('/bin/javac') and '-proc:none' in command
      destination=Path(command[command.index('-d')+1])
      for name in ('ResourcesFlattenRealmObserver.class','ResourcesFlattenCompletionSpy.class'):(destination/name).write_bytes(b'mock, never JVM bytecode')
     else:
      assert seconds==90 and command[0]==config['maven']
      opts=extra['MAVEN_OPTS'];log=Path(opts.split('-Xlog:class+load=info:file=',1)[1].split(':uptime',1)[0]);log.write_text('mock class log\n')
      if label.endswith('resources-failure'):exit_code=1;stdout='BOGUS-FIXTURE-CHARSET'
      if label.endswith('sdk-flatten'):
       exit_code=1;stdout="Non-resolvable parent POM local.resources.flatten:fixture-parent:pom:1.2.3.g123-SNAPSHOT 'parent.relativePath' points at wrong local POM"
       if mode=='wrong-sdk-cause':stdout='unrelated failure'
      if label.endswith('flatten-failure'):exit_code=1;stdout='blocked'
      if mode in ('goal-exit','goal-exit-cleanup-final') and label=='baseline-resources-success':exit_code=1
      if mode=='changed-fixture' and label=='baseline-resources-success':Path(command[command.index('-f')+1]).write_text('changed')
     self.results.append({'case':label,'command':command,'captureComplete':mode!='incomplete-capture','processGroupCleaned':True,'status':'EXITED','exitStatus':exit_code})
     return exit_code,stdout,stderr
   class Guard:
    def __init__(self,*args):self.failure='mock violation' if mode=='storage' else None;self.stops=0
    def start(self):pass
    def stop(self):
     self.stops+=1
     if (mode=='cleanup-final' and self.stops==22) or (mode=='goal-exit-cleanup-final' and self.stops==3):raise ValueError('mock cleanup failure')
     if mode=='command-error-cleanup' and self.stops==2:raise ValueError('mock command cleanup failure')
    def scan(self):pass
    def report(self):return {'scope':'pure command mock','failure':self.failure}
   common=types.SimpleNamespace(Commands=Commands,isolated_env=lambda *a:{})
   g['check_seal']=lambda value,full=False:fresh.append((value,full))
   g['support']=lambda source:{'checkstyle_live_support':common,'jar_plugin_storage':types.SimpleNamespace(StorageGuard=Guard)}
   f=dict(original_f,check_resources=lambda *a:{'scope':'mock'},check_flatten_files=lambda *a:None)
   def mocked_run_path(path):
    if Path(path).name=='resources_flatten_fixtures.py':return f
    if Path(path).name=='resources_flatten_observations.py':return {'check':lambda *a:{'scope':'mocked protocol, no JVM evidence'}}
    raise AssertionError('unexpected module load '+str(path))
   g['run_path']=mocked_run_path;os.environ['RESOURCES_FLATTEN_COMPATIBILITY']='true'
   stream=io.StringIO()
   with contextlib.redirect_stdout(stream):
    if mode=='complete':s['execute'](seal)
    else:rejects(lambda:s['execute'](seal))
   result=json.loads((output/'result.json').read_text())
   assert stream.getvalue().startswith('RESOURCES_FLATTEN_RESULT ')
   if mode!='complete':
    assert result['status']=='FAILED' and result['error']
    if mode=='cleanup-final':assert result['error']=='mock cleanup failure' and result['cleanupError']=='mock cleanup failure'
    if mode=='goal-exit-cleanup-final':assert 'real goal exit differs: baseline-resources-success' in result['error'] and result['cleanupError']=='mock cleanup failure'
    if mode=='command-error-cleanup':assert result['error']=='mock command error' and result['commandCleanupError']['error']=='mock command cleanup failure'
    continue
   assert result['status']=='PASSED_CURRENT_RUNTIME_PAIRED_GOALS_AND_COMPLETE_SYMBOLIC_LINKAGE'
   assert result['schemaVersion']=='RESOURCES_FLATTEN_COMPAT_V1'
   assert len(calls)==21 and calls[0][0]=='compile-observer'
   assert [row[0] for row in calls[1:]]==[v+'-'+n for v in ('baseline','candidate') for n in s['PLAN']['casesPerVariant']]
   for variant in ('baseline','candidate'):
    label=variant+'-sdk-production-context';command=next(row[1] for row in calls if row[0]==label)
    assert command[-2:]==['org.codehaus.mojo:flatten-maven-plugin:1.7.3:flatten@flatten','org.codehaus.mojo:flatten-maven-plugin:1.7.3:clean@flatten-clean']
    assert '-N' in command and command[command.index('-f')+1].endswith('/'+label+'/integrations/host-sdk/pom.xml')
    for flag in ('-o','--strict-checksums','-s','-gs','-t','-gt'):assert flag in command
    value=result['variants'][variant]
    assert set(value)==set(s['PLAN']['casesPerVariant'])
    assert value['sdk-flatten']['knownBaselineLimitation']['productionRevisionParentRepeatProven'] is False
    assert value['sdk-production-context']['productionRepeatProven'] is False
    for name in ('resources-failure','sdk-flatten','flatten-failure'):
     assert value[name]['actualFailureAttributionVerified']
     assert len(value[name]['failureAttributionLines'])==len(value[name]['expectedFailure']['requiredFragments'])
   assert len(fresh)==44 and fresh[0][1] is True and fresh[-1][1] is True
   assert result['productionRepeatProven'] is False and result['fullProductionBuildClaim'] is False
finally:
 for key,value in saved.items():g[key]=value
`);
});

const digest = data => createHash('sha256').update(data).digest('hex');
const packed = rows => {
  const bytes = Buffer.from(rows.join('\n') + '\n');
  return { encoding: 'gzip+base64', bytes: bytes.length, sha256: digest(bytes), rowCount: rows.length,
    data: gzipSync(bytes, { mtime: 0 }).toString('base64') };
};
function runPointerMutationChecks() {
  const dir = mkdtempSync(path.join(tmpdir(), 'rf-receipt-mock-'));
  try {
    const first = path.join(dir, 'failed-goal.json'), second = path.join(dir, 'failed-preparation.json');
    // Preserve lexical integers and JSON strings, including escaped newlines.
    writeFileSync(first, '{\n "status":"FAILED", "large":9007199254740993, "error":"first\\nsecond"\n}\n');
    writeFileSync(second, JSON.stringify({ status: 'FAILED', error: 'cleanup failed', commands: [] }));
    const pointer = (marker, file, status = 'FAILED') => marker + ' ' + JSON.stringify({ path: file, status });
    const a = pointer(resultMarker, first), b = pointer(portableMarker, second);
    const emitted = [];
    const records = retainReceipts(a + '\n' + b, true, row => emitted.push(row));
    assert.equal(emitted.length, 2); assert.ok(emitted[0].includes('9007199254740993'));
    assert.ok(emitted[0].includes('first\\nsecond')); assert.equal(records[resultMarker].status, 'FAILED');
    assert.throws(() => validateReceipt(records[resultMarker], {}));
    assert.throws(() => readReceipt('', resultMarker));
    assert.throws(() => readReceipt(a + '\n' + a, resultMarker));
    assert.throws(() => readReceipt(pointer(resultMarker, first, 'PASSED'), resultMarker));
    const linked = path.join(dir, 'linked.json'); symlinkSync(first, linked);
    assert.throws(() => readReceipt(pointer(resultMarker, linked), resultMarker));
    const oversized = path.join(dir, 'oversized.json'); writeFileSync(oversized, Buffer.alloc(maximumReceiptBytes + 1));
    assert.throws(() => readReceipt(pointer(resultMarker, oversized), resultMarker));
    const preserved = [];
    assert.throws(() => retainReceipts(a + '\n' + a + '\n' + b, true, row => preserved.push(row)));
    assert.equal(preserved.length, 1); assert.ok(preserved[0].startsWith(portableMarker + ' '));
    assert.throws(() => retainReceipts(pointer(resultMarker, first) + '\n' + pointer(portableMarker, first), true, () => {}));
  } finally { rmSync(dir, { recursive: true, force: true }); }
}

function runCompressionMutationChecks() {
  const protocol = packed(['RF_SESSION_COMPLETE\tflatten\t2']);
  assert.deepEqual(decodeProtocol(protocol), ['RF_SESSION_COMPLETE\tflatten\t2']);
  for (const change of [p => p.bytes++, p => p.rowCount++, p => p.sha256 = '0'.repeat(64),
    p => p.encoding = 'base64', p => p.data += '!', p => p.bytes = 1024 * 1024 + 1,
    p => p.rowCount = 2001, p => p.data = Buffer.alloc(256 * 1024 + 1).toString('base64')]) {
    const mutated = structuredClone(protocol); change(mutated); assert.throws(() => decodeProtocol(mutated));
  }
  const inflated = Buffer.alloc(1024 * 1024 + 1, 65);
  assert.throws(() => decodeProtocol({ encoding: 'gzip+base64', bytes: 1, sha256: digest(inflated), rowCount: 1,
    data: gzipSync(inflated).toString('base64') }));
}

function runLiveSpawnMockChecks() {
  let calls = [];
  const mockSpawn = (file, args, options) => { calls.push({ file, args, options }); return { status: 0, stdout: '', stderr: '' }; };
  const invoke = env => {
    const invocation = liveInvocation(env, '/review/fixtures');
    if (invocation.enabled) mockSpawn(invocation.executable, invocation.arguments, invocation.options);
    return invocation;
  };
  assert.equal(invoke({}).enabled, false); assert.equal(calls.length, 0);
  assert.equal(invoke({ GITHUB_ACTIONS: 'false' }).enabled, false); assert.equal(calls.length, 0);
  const hosted = invoke({ GITHUB_ACTIONS: 'true' }); assert.equal(hosted.enabled, true); assert.equal(calls.length, 1);
  assert.deepEqual(calls[0].args, ['--signal=TERM', '--kill-after=10s', '2090s', 'python3', '-I', '-S', '-B', '/review/fixtures/resources_flatten_portable.py', '--execute']);
  assert.equal(calls[0].file, '/usr/bin/timeout'); assert.equal(calls[0].options.timeout, 2110000);
  assert.equal(calls[0].options.env.GITHUB_ACTIONS, 'true'); assert.equal(calls[0].options.env.RESOURCES_FLATTEN_COMPATIBILITY, 'true');
  calls = [];
  assert.throws(() => invoke({ RESOURCES_FLATTEN_COMPATIBILITY: 'true' })); assert.equal(calls.length, 0);
  const local = invoke({ RESOURCES_FLATTEN_COMPATIBILITY: 'true', RESOURCES_FLATTEN_COMPATIBILITY_SEAL: '/review/seal.json' });
  assert.equal(local.ci, false); assert.equal(calls.length, 1);
  assert.deepEqual(calls[0].args, ['--signal=TERM', '--kill-after=10s', '1820s', 'python3', '-I', '-S', '-B', '/review/fixtures/resources_flatten_compatibility.py', '--execute-seal', '/review/seal.json']);
  assert.equal(calls[0].options.timeout, 1840000);
}

test('receipt pointers retain complete failures and reject stale, ambiguous or escaped records', runPointerMutationChecks);
test('complete protocol compression rejects hash, count and decompression bound mutations', runCompressionMutationChecks);
test('natural CI selects the exact hosted command while ordinary local imports remain inert', runLiveSpawnMockChecks);
test('portable Central preparation uses bounded mocks and preserves first failure receipts', () => {
  python(String.raw`
"""Unexecuted private review tests. Every command/runtime operation is a mock.

The parent can import run_checks(wrapper_path) into its no-process harness.
These tests create temporary protocol/cache DATA; they are never JVM evidence.
"""
import contextlib
import hashlib
import io
import json
from pathlib import Path
import runpy
import sys
import tempfile
from types import SimpleNamespace
import xml.etree.ElementTree as ET


def run_checks(wrapper_path):
    def prohibit(event, args):
        if event in ('subprocess.Popen', 'os.system', 'os.posix_spawn', 'socket.__new__', 'socket.connect'):
            raise AssertionError('real process/socket forbidden in pure wrapper checks: ' + event)
    sys.addaudithook(prohibit)
    module = runpy.run_path(str(wrapper_path))
    glob = module['execute_portable'].__globals__
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    tag = '{' + ns['m'] + '}'
    digest = lambda p: hashlib.sha256(Path(p).read_bytes()).hexdigest()
    saved = {name: glob[name] for name in ('run_path', 'adapter_hashes', 'signal', 'time', 'os')}
    results = {}
    try:
        # Pure command-vector gate tests never touch external processes.
        try:
            module['preparation_command']({}, Path('/mock'), 'baseline', False)
            raise AssertionError('local preparation was admitted')
        except ValueError:
            pass
        for mode in ('complete-cache', 'missing-pom', 'bad-jar', 'bad-pom-without-jar',
                     'failed-preparation', 'missing-pom-after-prep', 'cache-mutation', 'runtime-mutation',
                     'source-mutation', 'harness-mutation', 'adapter-mutation', 'seal-mutation',
                     'goal-failure', 'partial-output', 'command-timeout', 'cleanup-failure', 'cleanup-report-failure'):
            with tempfile.TemporaryDirectory(prefix='rf-portable-pure-') as temporary:
                root = Path(temporary).resolve()
                for name in ('baseline', 'candidate', 'jdk', 'repository', 'scratch', 'maven/bin'):
                    (root / name).mkdir(parents=True)
                maven = root / 'maven/bin/mvn'; maven.write_text('MOCK launcher data only\n')
                runtime_file = root / 'jdk/release'; runtime_file.write_text('JAVA_VERSION="21.MOCK"\n')
                source_file = root / 'candidate/source-marker'; source_file.write_text('MOCK source\n')
                harness_file = root / 'candidate/harness-marker'; harness_file.write_text('MOCK harness\n')
                adapter_file = root / 'candidate/adapter-marker'; adapter_file.write_text('MOCK adapter\n')
                for variant in ('baseline', 'candidate'):
                    (root / variant / 'pom.xml').write_text('<project xmlns="' + ns['m'] + '"><properties><flatten.maven.version>1.7.3</flatten.maven.version></properties></project>')
                repository = root / 'repository'
                coordinates = [f'mock.group:artifact-{i}:jar:1' for i in range(13)]
                pinned = {}
                pom_bytes = ('<project xmlns="' + ns['m'] + '"><modelVersion>4.0.0</modelVersion></project>').encode()
                for i, coordinate in enumerate(coordinates):
                    jar = module['artifact_path'](repository, coordinate); jar.parent.mkdir(parents=True)
                    jar.write_bytes(('MOCK artifact ' + str(i)).encode()); jar.with_suffix('.pom').write_bytes(pom_bytes)
                    pinned[coordinate] = digest(jar)
                pins = {'artifactHashes': pinned, 'owners': {
                    'resources': {'baseline': coordinates[:10], 'candidate': coordinates[:10]},
                    'flatten': {'baseline': coordinates[10:], 'candidate': coordinates[10:]}}}
                pins_file = root / 'candidate/pins.json'; pins_file.write_text(json.dumps(pins))
                first_jar = module['artifact_path'](repository, coordinates[0])
                if mode not in ('complete-cache', 'seal-mutation', 'goal-failure', 'partial-output', 'cleanup-failure', 'cleanup-report-failure'):
                    first_jar.with_suffix('.pom').unlink()
                if mode == 'bad-jar': first_jar.write_bytes(b'corrupt old JAR')
                if mode == 'bad-pom-without-jar':
                    first_jar.unlink(); first_jar.with_suffix('.pom').write_bytes(b'not XML')
                config = {name: str(root / name) for name in ('baseline', 'candidate', 'jdk', 'repository')}
                config.update(maven=str(maven), output=str(root / 'scratch/offline-goals'))
                calls, goals, signals = [], [], []
                def hash_paths(paths): return {str(p): digest(p) for p in paths}
                def snapshot(repo): return hash_paths(sorted(p for p in repo.rglob('*') if p.suffix in ('.jar', '.pom')))
                def source_pair(*_): return {'sourceHashes': {'baseline': {'source': digest(source_file)}, 'candidate': {'source': digest(source_file)}}}
                def plugin(source, owner, variant):
                    expected_source = root / variant
                    assert source == expected_source, 'each preparation reads its actual source variant'
                    value = ET.Element(tag + 'plugin')
                    for key, text in [('groupId', 'org.apache.maven.plugins' if owner == 'resources' else 'org.codehaus.mojo'),
                                      ('artifactId', 'maven-resources-plugin' if owner == 'resources' else 'flatten-maven-plugin'),
                                      ('version', '3.4.0' if owner == 'resources' else '$'+'{flatten.maven.version}')]:
                        ET.SubElement(value, tag + key).text = text
                    return value
                class Commands:
                    def __init__(self, sandbox, environment, seconds):
                        assert seconds == 240; self.results = []
                    def run(self, label, command, seconds):
                        assert seconds == 120 and command[-3:] == [module['RESOLVE'], '-DincludeArtifactIds=maven-resources-plugin,flatten-maven-plugin', '-DexcludeTransitive=false']
                        assert len(calls) < 2; calls.append(label)
                        if mode == 'command-timeout': raise TimeoutError('MOCK command deadline')
                        status = 1 if mode == 'failed-preparation' else 0
                        self.results.append({'case': label, 'command': command, 'status': 'EXITED', 'exitStatus': status,
                                             'captureComplete': True, 'processGroupCleaned': True})
                        if mode != 'missing-pom-after-prep':
                            for coordinate in coordinates:
                                module['artifact_path'](repository, coordinate).with_suffix('.pom').write_bytes(pom_bytes)
                        changed = {'cache-mutation': first_jar, 'runtime-mutation': runtime_file, 'source-mutation': source_file,
                                   'harness-mutation': harness_file, 'adapter-mutation': adapter_file}.get(mode)
                        if changed: changed.write_bytes(b'MOCK changed input')
                        return status, '', ''
                class Storage:
                    def __init__(self, *args): self.failure = None
                    def start(self): pass
                    def stop(self):
                        if mode == 'cleanup-failure': raise ValueError('MOCK cleanup failure')
                    def scan(self): pass
                    def report(self):
                        if mode == 'cleanup-report-failure': raise ValueError('MOCK cleanup report failure')
                        return {'failure': self.failure, 'mock': True}
                common = SimpleNamespace(Commands=Commands, CENTRAL_SETTINGS='<settings/>\n', isolated_env=lambda *_: {})
                runtime = SimpleNamespace(python_origins=lambda: ([], []), runtime_files=lambda *_: [runtime_file],
                                          hashes=hash_paths, cache_snapshot=snapshot)
                helpers = {'checkstyle_live_support': common, 'run_boot_live_compatibility': runtime,
                           'jar_plugin_storage': SimpleNamespace(StorageGuard=Storage)}
                def prepare(_):
                    assert module['existing_pins'](repository, pins) == set(pinned)
                    return {'source': source_pair(), 'harnessHashes': hash_paths([harness_file])}
                def execute(seal):
                    goals.append('MOCK execute')
                    output = Path(config['output']); output.mkdir()
                    status = module['STATUS'] if mode not in ('goal-failure', 'partial-output') else 'FAILED'
                    result = {'schemaVersion': 'RESOURCES_FLATTEN_COMPAT_V1', 'status': status, 'mock': True}
                    (output / 'result.json').write_text(json.dumps(result))
                    if mode == 'seal-mutation': (root / 'scratch/execution-seal.json').write_text('{}\n')
                    if mode == 'goal-failure': raise ValueError('MOCK actual goal failed')
                    return result
                fixture = {'private_hashes': lambda: hash_paths([harness_file]),
                           'source_contract': lambda: {'source_pair': source_pair}, 'support': lambda _: helpers,
                           'tool_pins': lambda _: None, 'PINS': pins_file, 'prepare': prepare,
                           'execute': execute, 'check_seal': lambda *args: None}
                glob.update(run_path=lambda _: {'plugin': plugin}, adapter_hashes=lambda _: hash_paths([adapter_file]),
                            os=SimpleNamespace(environ={'GITHUB_ACTIONS': 'true', 'RESOURCES_FLATTEN_COMPATIBILITY': 'true'}),
                            time=SimpleNamespace(monotonic=lambda: 0.0),
                            signal=SimpleNamespace(SIGALRM=14, SIGTERM=15, SIGINT=2, getsignal=lambda _: None,
                                                   signal=lambda *args: None, alarm=lambda seconds: signals.append(seconds)))
                capture = io.StringIO(); error = None
                with contextlib.redirect_stdout(capture):
                    try: module['execute_portable'](fixture, config, root / 'scratch')
                    except BaseException as caught: error = caught
                receipt = json.loads((root / 'scratch/portable-result.json').read_text())
                assert capture.getvalue().count('RESOURCES_FLATTEN_PORTABLE_RESULT ') == 1
                assert receipt['status'] == ('PASSED' if mode in ('complete-cache', 'missing-pom') else 'FAILED'), (mode, error)
                assert (error is None) == (mode in ('complete-cache', 'missing-pom'))
                if mode in ('bad-jar', 'bad-pom-without-jar'): assert not calls and not goals
                if mode == 'complete-cache': assert not calls and len(goals) == 1
                if mode == 'missing-pom': assert len(calls) == len(goals) == 1
                if mode in ('cleanup-failure', 'cleanup-report-failure'): assert receipt['cleanupComplete'] is False and 'cleanupError' in receipt
                assert len(calls) <= 2; results[mode] = {'mockCommandCount': len(calls), 'status': receipt['status']}
        return {'status': 'PURE_MOCK_ONLY_NOT_EXECUTION_EVIDENCE', 'cases': results}
    finally:
        glob.update(saved)

value=run_checks(here/'resources_flatten_portable.py')
assert value['status']=='PURE_MOCK_ONLY_NOT_EXECUTION_EVIDENCE' and len(value['cases'])==17
`);
});

test('finite Python helper imports reject mutation during import and an incorrect defining file', () => {
  python(String.raw`
import tempfile,types
contract=runpy.run_path(str(root/'scripts/ci/resources_flatten_contract.py'))
assert len(contract['PYTHON_HELPERS'])==9
assert len({Path(name).stem for name in contract['PYTHON_HELPERS']})==9
g=s['support'].__globals__;saved={key:g[key] for key in ('source_contract','importlib')}
original_modules={Path(name).stem:sys.modules.get(Path(name).stem) for name in contract['PYTHON_HELPERS']}
original_path=list(sys.path)
try:
 for mode in ('complete','mutated-during-import','wrong-defining-file'):
  with tempfile.TemporaryDirectory(prefix='rf-import-boundary-') as temporary:
   candidate=Path(temporary)
   for name in contract['PYTHON_HELPERS']:
    target=candidate/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes((root/name).read_bytes())
   before=contract['python_helper_state'](candidate)
   assert len(before)==9
   def mocked_import(name):
    for relative in contract['PYTHON_HELPERS']:
     if relative.endswith('/assembly_plexus_inventory.py'):continue
     target=candidate/relative
     if mode=='wrong-defining-file' and relative.endswith('/boot_utilities_contract.py'):target=candidate/'foreign/boot_utilities_contract.py'
     sys.modules[Path(relative).stem]=types.SimpleNamespace(__file__=str(target))
    if mode=='mutated-during-import':
     target=candidate/'scripts/tests/fixtures/run_boot_http_compatibility.py'
     target.write_bytes(target.read_bytes()+b'\n# changed while another helper was imported\n')
    return sys.modules[name]
   g['source_contract']=lambda:contract;g['importlib']=types.SimpleNamespace(import_module=mocked_import)
   if mode=='complete':
    actual=s['support'](candidate)
    assert set(actual)=={'checkstyle_live_support','run_boot_live_compatibility','jar_plugin_storage'}
    assert contract['python_helper_state'](candidate)==before
   else:rejects(lambda:s['support'](candidate))
finally:
 g.update(saved);sys.path[:]=original_path
 for name,module in original_modules.items():
  if module is None:sys.modules.pop(name,None)
  else:sys.modules[name]=module
`);
});
