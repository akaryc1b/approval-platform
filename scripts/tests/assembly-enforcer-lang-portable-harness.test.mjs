import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { resultMarker, portableMarker, readReceipt, retainReceipts, validateReceipt, validatePortable } from './fixtures/assembly-enforcer-lang-live-support.mjs';

const portable = fileURLToPath(new URL('./fixtures/assembly-enforcer-lang-portable.py', import.meta.url));
const pins = JSON.parse(readFileSync(new URL('./fixtures/assembly-enforcer-lang-pins.json', import.meta.url)));
function python(body) {
  const result = spawnSync('python3', ['-I', '-S', '-B', '-c', `import http.server,ssl,runpy,subprocess,socket\ndef blocked(*args,**kwargs): raise AssertionError('portable harness attempted child process or socket')\nsubprocess.Popen=socket.socket=blocked\np=runpy.run_path(${JSON.stringify(portable)})\nf=runpy.run_path(str(p['HERE']/'assembly-enforcer-lang-compatibility.py'))\ndef rejects(fn):\n try: fn()\n except (ValueError,AssertionError,KeyError,TimeoutError): return\n raise AssertionError('unexpected acceptance')\n${body}`],
    { encoding: 'utf8', timeout: 15000, maxBuffer: 1024 * 1024 });
  assert.equal(result.status, 0, result.stdout + result.stderr);
}

test('portable import/plan are inert; execution requires both explicit hosted gates', () => {
  python(String.raw`
import os
assert p['PLAN']['execution']=='NOT_RUN' and p['PLAN']['resolutionCommandsMaximum']==2
assert p['PLAN']['offlineGoalCount']==18 and p['PLAN']['outerTimeoutSeconds']==1500
for ci,optin in [(None,None),('true',None),(None,'true'),('false','true')]:
 for key,value in [('GITHUB_ACTIONS',ci),('ASSEMBLY_ENFORCER_LANG_COMPATIBILITY',optin)]:
  if value is None:os.environ.pop(key,None)
  else:os.environ[key]=value
 rejects(p['run'])
`);
});

test('preparation retains only the two exact owner declarations and official Central mirror', () => {
  python(String.raw`
from pathlib import Path
import xml.etree.ElementTree as ET
for variant in ('baseline','candidate'):
 root=ET.fromstring(p['preparation_pom'](f,f['ROOT'],variant));ns=f['NS']
 assert root.findtext('m:packaging',namespaces=ns)=='pom' and root.find('m:parent',ns) is None
 plugins=root.findall('m:build/m:plugins/m:plugin',ns)
 assert len(plugins)==2
 for owner,plugin in zip(('assembly','enforcer'),plugins):
  assert f['normalized'](plugin)==f['normalized'](f['variant_plugin'](f['ROOT'],owner,variant))
 command=p['preparation_command']({'maven':'/runtime/bin/mvn','repository':'/cache'},Path('/sandbox'),variant,True)
 assert command[-3:]==[p['RESOLVE'],'-DincludeArtifactIds=maven-assembly-plugin,maven-enforcer-plugin','-DexcludeTransitive=false']
 assert all(value not in command for value in ('install','deploy','verify','-o'))
 for flag in ('-s','-gs'):assert command[command.index(flag)+1]=='/sandbox/central-settings.xml'
 for flag in ('-t','-gt'):assert command[command.index(flag)+1]=='/sandbox/empty-toolchains.xml'
 rejects(lambda:p['preparation_command']({},Path('/sandbox'),variant,False))
rejects(lambda:p['preparation_command']({},Path('/sandbox'),'other',True))
assert 'https://repo.maven.apache.org/maven2' in p['CENTRAL_SETTINGS'] and '<mirrorOf>*</mirrorOf>' in p['CENTRAL_SETTINGS']
assert '<servers>' not in p['CENTRAL_SETTINGS'] and len(f['read_pins']()['artifactHashes'])==29
`);
});

test('mocked preparation skips complete caches, resolves missing POMs, and fails closed before execution', () => {
  python(String.raw`
import contextlib,hashlib,io,json,tempfile
from pathlib import Path
g=p['execute_portable'].__globals__
for mode in ('empty','complete','missing-pom','wrong-jar','failed-resolution','changed-cache','changed-input','changed-adapter','changed-seal'):
 with tempfile.TemporaryDirectory(prefix='lang-portable-mock-') as temporary:
  sandbox=Path(temporary);repository=sandbox/'cache';repository.mkdir()
  config={'source':str(f['ROOT']),'maven':'/runtime/bin/mvn','jdk':'/runtime/jdk','repository':str(repository),'output':str(sandbox/'offline-goals')}
  data={'baseline':b'baseline','candidate':b'candidate'}
  mockpins={'artifactHashes':{k:hashlib.sha256(v).hexdigest() for k,v in data.items()},
   'owners':{o:{v:[v] for v in data}for o in ('assembly','enforcer')}}
  def artifact(repo,c):return repo/(c+'.jar')
  def put(c):
   artifact(repository,c).write_bytes(data[c]);artifact(repository,c).with_suffix('.pom').write_text('<project/>')
  if mode in ('complete','missing-pom'):
   for c in data:put(c)
  if mode=='missing-pom':artifact(repository,'candidate').with_suffix('.pom').unlink()
  if mode=='wrong-jar':artifact(repository,'baseline').write_bytes(b'wrong')
  old=repository/'unrelated.pom';old.write_text('retained')
  calls=[];prepared=[];executed=[];changed=[False]
  original_adapter=g['adapter_hashes']
  def fake_adapter(source):return {'adapter':'b'*64 if changed[0] else 'a'*64}
  if mode=='changed-adapter':g['adapter_hashes']=fake_adapter
  class FakeCommands:
   def __init__(self,work,environment,seconds):
    assert seconds==240 and environment['MAVEN_SKIP_RC']=='1' and set(environment)=={'JAVA_HOME','PATH','LANG','MAVEN_SKIP_RC','HOME','TMPDIR','XDG_CONFIG_HOME','MAVEN_BASEDIR','MAVEN_OPTS'}
    self.results=[]
   def run(self,label,command,seconds):
    assert seconds==120 and command[-3]==p['RESOLVE'];calls.append(label);self.results.append({'case':label})
    if mode=='failed-resolution':return 1,'',''
    put(label.removeprefix('prepare-'))
    if mode=='changed-cache':old.write_text('mutated')
    if mode=='changed-input':(sandbox/'central-settings.xml').write_text('mutated')
    if mode=='changed-adapter':changed[0]=True
    return 0,'',''
  def prepare(c):
   assert c==config;prepared.append(c);return {'config':c}
  def execute(seal):
   assert seal=={'config':config};executed.append(seal)
   if mode=='changed-seal':(sandbox/'execution-seal.json').write_text('mutated')
  fixture=dict(f,checked_paths=lambda c:{k:Path(v)for k,v in c.items()},read_pins=lambda:mockpins,
   artifact_path=artifact,source_hashes=lambda source:{'source':'c'*64},runtime_hashes=lambda *args:{'runtime':'d'*64},prepare=prepare,execute=execute)
  g['Commands']=FakeCommands
  output=io.StringIO()
  try:
   with contextlib.redirect_stdout(output):
    if mode in ('empty','complete','missing-pom'):p['execute_portable'](fixture,config,sandbox)
    else:rejects(lambda:p['execute_portable'](fixture,config,sandbox))
   receipt=json.loads((sandbox/'portable-result.json').read_text())
   assert len(output.getvalue().splitlines())==1 and output.getvalue().startswith('ASSEMBLY_ENFORCER_LANG_PORTABLE_RESULT ')
   if mode in ('empty','complete','missing-pom'):
    assert receipt['status']=='PASSED' and len(prepared)==len(executed)==1
    assert calls==([]if mode=='complete'else['prepare-candidate']if mode=='missing-pom'else['prepare-baseline','prepare-candidate'])
   else:
    assert receipt['status']=='FAILED'
    assert len(executed)==(1 if mode=='changed-seal'else 0)
    if mode=='wrong-jar':assert not calls
  finally:g['adapter_hashes']=original_adapter
`);
});

function command(label, args, exitStatus = 0) {
  return { case: label, command: args, status: 'EXITED', exitStatus, captureComplete: true, processGroupCleaned: true,
    elapsedSeconds: 1, capturedBytes: 1, stdoutSha256: 'a'.repeat(64), stderrSha256: 'b'.repeat(64) };
}
function receipt() {
  const cases = { assembly: ['archives', 'discovery', 'missing-main', 'malformed-main', 'missing-component', 'malformed-component'],
    enforcer: ['success', 'bad-maven', 'bad-java'] };
  const goals = { assembly: 'org.apache.maven.plugins:maven-assembly-plugin:3.7.1:single',
    enforcer: 'org.apache.maven.plugins:maven-enforcer-plugin:3.5.0:enforce@enforce-toolchain' };
  const members = Array.from({ length: 10 }, (_, i) => ['enforcer', 'consumer', 'member' + i]);
  const value = { status: 'PASSED', inputSealsUnchanged: true, scannerEvidence: false, productionActivationClaim: false,
    plan: { variants: { baseline: '3.14.0', candidate: '3.18.0' }, goals, cases, offline: true, sockets: false,
      actualGoalCount: 18, compileCount: 1, commandTimeoutSeconds: 60, compileTimeoutSeconds: 30, aggregateTimeoutSeconds: 1200, outerTimeoutSeconds: 1230 },
    inventory: { consumers: Array(10).fill({}), memberUses: [...members, ...members.slice(0, 3)] },
    classUtilsStatic: { directSelfInvocation: false, classSha256: 'c'.repeat(64), codeSha256: 'd'.repeat(64) },
    comparison: { allNinePairsExactlyEqual: true, archiveBytesAndMetadataEqual: true, controlledResourceCopyEqual: true },
    commands: [command('compile-observer', ['/jdk/bin/javac', '-proc:none'])], variants: {}, coverage: {} };
  for (const key of ['sourceHashes', 'runtimeHashes', 'cacheHashes', 'generatedInputHashes']) value[key] = { file: 'e'.repeat(64) };
  for (const variant of ['baseline', 'candidate']) {
    value.variants[variant] = {}; value.coverage[variant] = {};
    for (const [owner, names] of Object.entries(cases)) {
      value.variants[variant][owner] = {};
      value.coverage[variant][owner] = { symbolicallyResolvedConsumers: owner === 'assembly' ? 3 : 7, naturalLoadIsMemberInvocationClaim: false };
      for (const name of names) {
        const exitStatus = ['archives', 'discovery', 'success'].includes(name) ? 0 : 1;
        value.commands.push(command(`${variant}-${owner}-${name}`, ['/maven/bin/mvn', '-o', '-N', '--strict-checksums', '-s', '-gs', '-t', '-gt', goals[owner]], exitStatus));
        value.variants[variant][owner][name] = { exitStatus, behavior: { name }, missingMemberControl: 'PASSED', symbolicResolutionIsInvocationClaim: false,
          linkageMemberCount: owner === 'assembly' ? 3 : 10, realm: pins.owners[owner][variant], naturalOrigins: [], controlledLoads: [], symbolicLoads: [], explicitOrigins: [] };
      }
    }
    value.variants[variant].assembly.archives.controlledResourceCopy = { phase: 'controlled API after real goal',
      log: '[INFO] Copying 1 resource from filter to controlled-resources/nested', path: 'controlled-resources/nested/value.txt',
      sha256: '30bf651f5f3d4bc94205fd3c00b2902cbf912a39a10b4bb58d7fbc148e36be1b', bytes: 27 };
  }
  return value;
}

test('live receipt checks reject missing goals, swallowed failures, wrong realm and changed paired behavior', () => {
  validateReceipt(receipt(), pins);
  for (const mutate of [v => v.commands.pop(), v => v.commands.push(v.commands[0]),
    v => { v.commands[3].exitStatus = 0; }, v => { v.commands[1].processGroupCleaned = false; },
    v => { v.commands[1].command.pop(); }, v => { v.commands[1].elapsedSeconds = 100; },
    v => { v.variants.candidate.assembly.archives.realm = pins.owners.assembly.baseline; },
    v => { v.variants.candidate.assembly.archives.behavior.name = 'changed'; },
    v => { v.variants.candidate.assembly.archives.controlledResourceCopy.log = 'Copying 1 resource'; },
    v => { v.variants.candidate.enforcer['bad-java'].missingMemberControl = 'FAILED'; },
    v => { v.inventory.memberUses.pop(); }, v => { v.inputSealsUnchanged = false; },
    v => { v.classUtilsStatic.directSelfInvocation = true; }]) {
    const changed = structuredClone(receipt()); mutate(changed); assert.throws(() => validateReceipt(changed, pins));
  }
});

test('complete receipt retention preserves large integer lexemes and emits failures once', () => {
  const directory = mkdtempSync(path.join(tmpdir(), 'lang-receipt-'));
  try {
    const file = path.join(directory, 'result.json');
    const text = '{\n "status": "FAILED", "mtimeNs": 9223372036854775807, "message": "line\\nnext"\n}\n';
    writeFileSync(file, text);
    const pointer = resultMarker + ' ' + JSON.stringify({ status: 'FAILED', path: file });
    const records = []; retainReceipts(pointer + '\n', false, line => records.push(line));
    assert.equal(records.length, 1); assert.ok(records[0].includes('9223372036854775807')); assert.ok(records[0].includes('line\\nnext'));
    assert.equal(records[0], resultMarker + ' ' + text.replace(/[\r\n\t]/g, ''));
    const partial = [];
    assert.throws(() => retainReceipts(pointer + '\n' + portableMarker + ' invalid-json\n', true, line => partial.push(line)));
    assert.deepEqual(partial, records, 'valid actual evidence survives a malformed portable receipt');
    assert.throws(() => readReceipt(pointer + '\n' + pointer, resultMarker));
    assert.throws(() => readReceipt('', resultMarker));
    assert.throws(() => readReceipt(pointer.replace('FAILED', 'PASSED'), resultMarker));
    const link = path.join(directory, 'link.json'); symlinkSync(file, link);
    assert.throws(() => readReceipt(pointer.replace(file, link), resultMarker));
    writeFileSync(file, ' '.repeat(8 * 1024 * 1024 + 1)); assert.throws(() => readReceipt(pointer, resultMarker));
    writeFileSync(file, '[]'); assert.throws(() => readReceipt(pointer, resultMarker));
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

test('portable receipt validation requires exact pins, bounded preparation and unchanged adapter', () => {
  const value = { status: 'PASSED', centralPreparationOnly: true, existingArtifactHashesUnchanged: true, adapterSealsUnchanged: true,
    scannerEvidence: false, productionActivationClaim: false, artifactHashes: pins.artifactHashes, owners: pins.owners,
    executionSealSha256: 'a'.repeat(64), resolutionCommandsRun: 0, commands: [],
    plan: { hostedOnly: true, resolutionCommandsMaximum: 2, preparationCommandTimeoutSeconds: 120, preparationAggregateTimeoutSeconds: 240,
      offlineGoalCount: 18, compileCount: 1, offlineAggregateTimeoutSeconds: 1200, outerTimeoutSeconds: 1500, repositoryUrl: 'https://repo.maven.apache.org/maven2' } };
  for (const key of ['sourceHashes', 'runtimeHashes', 'adapterHashes', 'fixedPreparationHashes']) value[key] = { file: 'a'.repeat(64) };
  validatePortable(value, pins);
  for (const mutate of [v => { v.resolutionCommandsRun = 3; }, v => { v.adapterSealsUnchanged = false; },
    v => { v.plan.repositoryUrl = 'https://example.com'; }, v => { delete v.artifactHashes[Object.keys(v.artifactHashes)[0]]; }]) {
    const changed = structuredClone(value); mutate(changed); assert.throws(() => validatePortable(changed, pins));
  }
  assert.notEqual(resultMarker, portableMarker);
});
