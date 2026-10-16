import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const harness = fileURLToPath(new URL('./fixtures/assembly-enforcer-lang-compatibility.py', import.meta.url));
function python(body) {
  const result = spawnSync('python3', ['-I', '-S', '-B', '-c', `import http.server,ssl,runpy,subprocess,socket\ndef blocked(*args,**kwargs): raise AssertionError('inert harness attempted child process or socket')\nsubprocess.Popen=socket.socket=blocked\ns=runpy.run_path(${JSON.stringify(harness)})\ndef rejects(fn):\n try: fn()\n except (ValueError,AssertionError,KeyError): return\n raise AssertionError('unexpected acceptance')\n${body}`],
  { encoding: 'utf8', timeout: 15000, maxBuffer: 1024 * 1024 });
  assert.equal(result.status, 0, result.stdout + result.stderr);
}

test('inert plan admits only 18 offline goals and one bounded compilation', () => {
  python(String.raw`
from pathlib import Path
assert s['PLAN']['actualGoalCount']==18 and s['PLAN']['compileCount']==1
assert s['PLAN']['aggregateTimeoutSeconds']==1200 and s['PLAN']['outerTimeoutSeconds']==1230
assert s['PLAN']['commandTimeoutSeconds']==60 and s['PLAN']['compileTimeoutSeconds']==30
assert len(s['CASES']['assembly'])==6 and len(s['CASES']['enforcer'])==3
assert s['PLAN']['offline'] and not s['PLAN']['sockets']
for owner in ('assembly','enforcer'):
 command=s['maven_command']({'maven':'/runtime/bin/mvn','repository':'/cache'},Path('/fixture'),Path('/fixture/pom.xml'),owner)
 assert command[-1]==s['GOALS'][owner]
 for flag in ('-o','-N','-X','--strict-checksums','-s','-gs','-t','-gt'):assert flag in command
 for flag in ('-t','-gt'):assert command[command.index(flag)+1]=='/fixture/empty-toolchains.xml'
 assert all(value not in command for value in ('verify','install','deploy'))
 rejects(lambda:s['maven_command']({'maven':'/runtime/bin/mvn','repository':'/cache'},Path('/fixture'),Path('/outside/pom.xml'),owner))
assert s['GOALS']['enforcer'].endswith(':3.5.0:enforce@enforce-toolchain')
for owner in ('release','verify','assembly:single','enforcer:enforce'):
 rejects(lambda:s['maven_command']({},Path('/fixture'),Path('/fixture/pom.xml'),owner))
rejects(lambda:s['execute']({}))
`);
});

test('all consumers have exact full descriptors and witnessed static uses', () => {
  python(String.raw`
import json
pins=s['read_pins']();expected=json.loads(s['CONSUMERS'].read_text())
assert len(pins['artifactHashes'])==29
assert expected['consumerCount']==10 and len(expected['consumers'])==10
assert expected['memberRowCount']==13 and len(expected['memberUses'])==13
assert expected['distinctLangMemberCount']==len({tuple(r[2:])for r in expected['memberUses']})==10
assert all(row[-1]=='static' for row in expected['memberUses'])
seal={'config':{'repository':'/cache'},'inventory':expected}
for owner,count in (('assembly',3),('enforcer',10)):
 rows=s['linkage_rows'](seal,owner)
 assert sum(row.startswith('member\t') for row in rows)==count
 assert all(len(row.split('\t'))==7 for row in rows if row.startswith('member\t'))
 assert all(len(row.split('\t')[-1])==64 for row in rows if row.startswith('consumer\t'))
seal['inventory']=dict(expected,memberUses=[])
rejects(lambda:s['linkage_rows'](seal,'assembly'))
`);
});

test('fixture retains actual source executions and changes only Lang or one failure range', () => {
  python(String.raw`
import copy,tempfile,xml.etree.ElementTree as ET
from pathlib import Path
ns=s['NS'];P=s['P'];source=s['ROOT']
plugins=s['fixture_plugins'](source)
for owner in s['OWNERS']:
 baseline=s['variant_plugin'](source,owner,'baseline');candidate=s['variant_plugin'](source,owner,'candidate')
 changed=copy.deepcopy(baseline)
 lang=[d for d in changed.findall('m:dependencies/m:dependency',ns) if d.findtext('m:artifactId',namespaces=ns)=='commons-lang3'][0]
 assert lang.findtext('m:version',namespaces=ns)=='3.14.0'
 lang.find('m:version',ns).text='3.18.0'
 assert s['normalized'](changed)==s['normalized'](candidate)
with tempfile.TemporaryDirectory(prefix='lang-inert-projects-') as temporary:
 root=Path(temporary)
 for case in s['CASES']['assembly']:
  directory=root/case;s['A']['write_case'](directory,plugins['assembly'],case)
  doc=ET.parse(directory/'pom.xml').getroot()
  assert doc.find('m:build/m:plugins/m:plugin',ns) is not None
 for case in s['CASES']['enforcer']:
  directory=root/case;s['enforcer_case'](directory,plugins['enforcer'],case)
  doc=ET.parse(directory/'pom.xml').getroot()
  actual=doc.find('m:build/m:plugins/m:plugin',ns)
  changed=copy.deepcopy(plugins['enforcer'])
  rules=changed.find('m:executions/m:execution/m:configuration/m:rules',ns)
  if case=='bad-maven':rules.find('m:requireMavenVersion/m:version',ns).text='[4,)'
  if case=='bad-java':rules.find('m:requireJavaVersion/m:version',ns).text='[22,23)'
  assert s['normalized'](actual)==s['normalized'](changed)
`);
});

test('exact comparison rejects any archived bytes, member or metadata change', () => {
  python(String.raw`
import copy
variants={variant:{owner:{case:{'behavior':{'files':{'keep':{'sha256':'a'*64,'mode':0o640}},'archiveSha256':'b'*64,'metadata':{'time':[2024,1,2,3,4,6]}}} for case in cases} for owner,cases in s['CASES'].items()}for variant in s['VERSIONS']}
for variant in variants:variants[variant]['assembly']['archives']['controlledResourceCopy']={'sha256':'c'*64}
assert s['compare_pairs'](variants)['archiveBytesAndMetadataEqual']
for owner,cases in s['CASES'].items():
 for case in cases:
  for field in ('files','archiveSha256','metadata'):
   altered=copy.deepcopy(variants);altered['candidate'][owner][case]['behavior'][field]='changed'
   rejects(lambda:s['compare_pairs'](altered))
altered=copy.deepcopy(variants);altered['candidate']['assembly']['archives']['controlledResourceCopy']={'sha256':'d'*64}
rejects(lambda:s['compare_pairs'](altered))
text=s['Path'](s['__file__']).read_text()
assert "A['archive_result'](directory, 'candidate')" in text
assert "A['compare_archives']" not in text
`);
});

test('controlled resource copy requires exact branch evidence and rejects the original missing scenario', () => {
  python(String.raw`
from pathlib import Path
import tempfile,hashlib
with tempfile.TemporaryDirectory(prefix='lang-resource-control-') as temporary:
 root=Path(temporary);relative='controlled-resources/nested/value.txt';output=root/relative;output.parent.mkdir(parents=True)
 payload=(s['A']['TEXT']+'\n').encode();output.write_bytes(payload)
 stdout='[INFO] Copying 1 resource from filter to controlled-resources/nested\n'
 terminal='PLUGIN_LANG_GOAL_TERMINAL\tMojoSucceeded'
 begin='PLUGIN_LANG_CONTROLLED_RESOURCE_BEGIN\tfilter/value.txt\t'+relative
 origin='PLUGIN_LANG_TYPE\tCONTROLLED\torg/apache/commons/lang3/StringUtils\tloader\trealm\tfile:/cache/commons-lang3.jar\t'+'a'*64
 end='PLUGIN_LANG_CONTROLLED_RESOURCE_END\t'+relative+'\t'+hashlib.sha256(payload).hexdigest()
 complete='PLUGIN_LANG_CONTROLLED_COMPLETE\tPASSED'
 stderr='\n'.join((terminal,begin,origin,end,complete))
 assert s['controlled_resource_result'](stdout,stderr,root)['phase']=='controlled API after real goal'
 for bad_stdout in ('','[INFO] Copying 1 resource\n',stdout.replace('1 resource','0 resources'),stdout.replace('from filter','from other'),stdout.replace('nested','wrong'),stdout+stdout):
  rejects(lambda:s['controlled_resource_result'](bad_stdout,stderr,root))
 for bad_stderr in (stderr.replace(begin,''),stderr.replace(origin,''),stderr.replace(end,''),stderr.replace('TYPE\tCONTROLLED','TYPE\tNATURAL'),stderr.replace(begin,begin+'\n'+begin),'\n'.join((begin,terminal,origin,end,complete)),stderr.replace(hashlib.sha256(payload).hexdigest(),'b'*64)):
  rejects(lambda:s['controlled_resource_result'](stdout,bad_stderr,root))
 output.write_text('unfiltered $'+'{fixture.value}\n');rejects(lambda:s['controlled_resource_result'](stdout,stderr,root))
 output.write_bytes(payload);(output.parent/'extra.txt').write_text('extra');rejects(lambda:s['controlled_resource_result'](stdout,stderr,root))
 # The original successful archive goal produced neither the copy log nor a
 # resource-call marker. Later Lang loads alone cannot replace this evidence.
 rejects(lambda:s['controlled_resource_result']('[INFO] Copying files to target/fixture-real\n','\n'.join((terminal,origin,complete)),root))
`);
});

test('Enforcer rejects exact wrong versions without swallowing failures', () => {
  python(String.raw`
from pathlib import Path
import tempfile
with tempfile.TemporaryDirectory(prefix='lang-diagnostic-') as temporary:
 jdk=Path(temporary);(jdk/'release').write_text('JAVA_VERSION="21.0.12.1"\n');config={'jdk':str(jdk)}
 maven='org.apache.maven.enforcer.rules.version.RequireMavenVersion';java='org.apache.maven.enforcer.rules.version.RequireJavaVersion'
 good=f'[INFO] Rule 0: {maven} passed\n[INFO] Rule 1: {java} passed\n[INFO] BUILD SUCCESS\n'
 assert s['enforcer_result'](good,0,'success',config)['exitStatus']==0
 bad_maven=f'[ERROR] Rule 0: {maven} failed with message:\n[ERROR] Detected Maven Version: 3.9.16 is not in the allowed range [4,).\n[INFO] Rule 1: {java} passed\n[INFO] BUILD FAILURE\n'
 bad_java=f'[INFO] Rule 0: {maven} passed\n[ERROR] Rule 1: {java} failed with message:\n[ERROR] Detected JDK version 21.0.12-1 (JAVA_HOME={jdk}) is not in the allowed range [22,23).\n[INFO] BUILD FAILURE\n'
 for case,text in [('bad-maven',bad_maven),('bad-java',bad_java)]:
  assert s['enforcer_result'](text,1,case,config)['exitStatus']==1
  rejects(lambda:s['enforcer_result'](text,0,case,config))
  rejects(lambda:s['enforcer_result'](text.replace('3.9.16','3.9.15').replace('21.0.12-1','21.0.12.1'),1,case,config))
  rejects(lambda:s['enforcer_result'](text.replace('[4,)','[5,)').replace('[22,23)','[23,24)'),1,case,config))
  rejects(lambda:s['enforcer_result'](text.replace('failed','passed'),1,case,config))
 rejects(lambda:s['enforcer_result'](bad_java.replace(str(jdk),'/wrong/jdk'),1,'bad-java',config))
 rejects(lambda:s['enforcer_result'](good.replace('Rule 1:','Rule 2:'),0,'success',config))
`);
});

test('observer keeps controlled APIs after terminal and symbolic coverage separate', () => {
  python(String.raw`
text=s['OBSERVER'].read_text();spy=s['SPY'].read_text()
assert 'phase = "CONTROLLED"' in text and 'phase = "SYMBOLIC"' in text
assert 'String relative = "filter/value.txt"' in text and '!new java.io.File(relative).isAbsolute()' in text
assert 'resourcesType.getMethod("filterResources", executionType).invoke(resources, execution)' in text
assert 'event.getProject(), event.getSession()' in spy
assert 'name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1024' in text
assert 'expected.getCause() instanceof ClassNotFoundException' in text
assert 'getClassLoader() == realm' in text and 'signature.returnType()' in text
assert 'Modifier.isStatic(member.getModifiers())' in text and 'accessible(consumer' in text
assert 'missingLangFixtureMethod' in text and 'defined.get(' in text
assert 'enforce-toolchain' in spy and 'MojoFailed' in spy and 'MojoSucceeded' in spy
assert 'StackOverflowError' not in text and 'repeat(256)' not in text
`);
});

test('candidate static regression rejects a direct recursive call or wrong method identity', () => {
  python(String.raw`
import tempfile,struct,zipfile
from pathlib import Path
def class_bytes(code=b'\x01\xb0',name='getClass',access=9):
 utf=lambda value:b'\x01'+struct.pack('>H',len(value.encode()))+value.encode()
 descriptor='(Ljava/lang/ClassLoader;Ljava/lang/String;Z)Ljava/lang/Class;'
 pool=[utf('org/apache/commons/lang3/ClassUtils'),b'\x07\x00\x01',utf(name),utf(descriptor),utf('Code'),b'\x0c\x00\x03\x00\x04',b'\x0a\x00\x02\x00\x06']
 attribute=struct.pack('>HHI',2,3,len(code))+code+b'\x00\x00\x00\x00'
 method=struct.pack('>HHHH',access,3,4,1)+struct.pack('>HI',5,len(attribute))+attribute
 return b'\xca\xfe\xba\xbe\x00\x00\x00\x34'+struct.pack('>H',len(pool)+1)+b''.join(pool)+struct.pack('>HHHHHH',1,2,0,0,0,1)+method+b'\x00\x00'
with tempfile.TemporaryDirectory(prefix='lang-static-control-') as temporary:
 root=Path(temporary);path=s['artifact_path'](root,s['LANG_PREFIX']+'3.18.0');path.parent.mkdir(parents=True)
 def write(data):
  with zipfile.ZipFile(path,'w') as archive:archive.writestr('org/apache/commons/lang3/ClassUtils.class',data)
 write(class_bytes());assert s['classutils_static'](root)['directSelfInvocation'] is False
 for data in (class_bytes(b'\xb8\x00\x07\xb0'),class_bytes(name='other'),class_bytes(access=1)):
  write(data);rejects(lambda:s['classutils_static'](root))
`);
});

test('source seal binds the complete executed run_path closure with canonical paths', () => {
  python(String.raw`
from pathlib import Path
recorded=[];original=runpy.run_path
def traced(path,*args,**kwargs):
 recorded.append(str(Path(path).resolve()))
 return original(path,*args,**kwargs)
runpy.run_path=traced
isolated=runpy.run_path(str(s['HERE']/'assembly-enforcer-lang-compatibility.py'))
sealed=isolated['source_hashes'](isolated['ROOT'])
assert recorded and set(recorded)<=set(sealed),(recorded,sealed.keys())
assert all(Path(name).is_absolute() and Path(name).resolve()==Path(name) for name in sealed)
assert str(s['ROOT']/'scripts/ci/jar_plugin_retained_source.py') in sealed
assert str(s['HERE']/'jar_plugin_storage.py') in sealed
`);
});

test('class mismatch diagnostics preserve exact rejection and redact malformed fields', () => {
  python(String.raw`
import hashlib,json
expected='a'*64;name='org/apache/commons/lang3/ClassUtils';origin='repository/org/apache/commons/commons-lang3/3.18.0/commons-lang3-3.18.0.jar'
def check(value,**changes):
 context=dict(variant='candidate',owner='assembly',case='discovery',phase='NATURAL',name=name,origin=origin,entry=name+'.class');context.update(changes)
 return s['require_class_hash'](value,expected,**context)
assert check(expected) is None
for value,category in [('b'*64,'different-sha256'),('malformed-secret','malformed-digest'),(expected+'[1.654s][info][class,load] secret.Host source: private-path','expected-digest-with-suffix')]:
 try:check(value)
 except s['ClassBytesMismatch'] as failure:
  assert isinstance(failure,ValueError) and str(failure)=='observed effective class bytes differ'
  record=failure.diagnostic;assert record['expectedSha256']==expected and record['category']==category
  assert record['expectedPrefixMatches']==value.startswith(expected)
  assert record['class']==name and record['origin']==origin and record['entry']==name+'.class'
  if category=='different-sha256':assert record['observedSha256']==value and 'observedFieldSha256' not in record
  else:
   assert 'observedSha256' not in record and record['observedFieldBytes']==len(value.encode())
   assert record['observedFieldSha256']==hashlib.sha256(value.encode()).hexdigest()
   assert 'secret' not in json.dumps(record) and 'private-path' not in json.dumps(record)
 else:raise AssertionError('mismatch accepted')
try:check('invalid',name='secret\nvalue',origin='/private/host.jar',entry='../../secret.class')
except s['ClassBytesMismatch'] as failure:
 assert all(failure.diagnostic[key].startswith('REDACTED_') for key in ('class','origin','entry'))
 assert 'secret' not in json.dumps(failure.diagnostic) and '/private' not in json.dumps(failure.diagnostic)
else:raise AssertionError('unsafe mismatch accepted')
`);
});

test('separate class logs require fresh regular bounded stable bytes', () => {
  python(String.raw`
import hashlib,os,tempfile
from pathlib import Path
with tempfile.TemporaryDirectory(prefix='lang-class-log-') as temporary:
 root=Path(temporary);logs=root/'class-load';logs.mkdir()
 path=s['class_log_path'](root,'candidate','assembly','discovery')
 try:s['read_class_log'](path)
 except FileNotFoundError:pass
 else:raise AssertionError('missing log accepted')
 path.symlink_to(logs/'missing');rejects(lambda:s['class_log_path'](root,'candidate','assembly','discovery'));rejects(lambda:s['read_class_log'](path));path.unlink()
 os.mkfifo(path);rejects(lambda:s['read_class_log'](path));path.unlink()
 path.write_bytes(b'');rejects(lambda:s['read_class_log'](path))
 path.write_bytes(b'\xff');rejects(lambda:s['read_class_log'](path))
 path.write_bytes(b'x'*(s['PLAN']['classLoadMaximumBytes']+1));rejects(lambda:s['read_class_log'](path))
 payload=b'[1.000s][info][class,load] x.Foo source: file:/approved.jar\n';path.write_bytes(payload)
 text,record=s['read_class_log'](path)
 assert text==payload.decode() and record=={'path':'class-load/candidate-assembly-discovery.log','bytes':len(payload),'sha256':hashlib.sha256(payload).hexdigest()}
 rejects(lambda:s['class_log_path'](root,'candidate','assembly','discovery'))
 guard=s['StorageGuard'](logs,maximum_bytes=len(payload),maximum_entries=1,file_limits={path.name:len(payload)})
 assert guard.scan()==(len(payload),1)
 path.write_bytes(payload+b'x');rejects(guard.scan)
 path.write_bytes(payload);(logs/'foreign.log').write_bytes(b'x');rejects(guard.scan)
 (logs/'foreign.log').unlink();path.unlink();path.symlink_to(root/'outside');rejects(guard.scan)
`);
});

test('separate native corroboration rejects missing foreign truncated and coalesced lines', () => {
  python(String.raw`
name='org/apache/commons/lang3/ClassUtils';uri='file:/approved/commons-lang3.jar'
line='[1.654s][info][class,load] '+name.replace('/','.')+' source: '+uri
s['require_class_corroboration'](line+'\n'+line+'\n',name,uri)
for value in ('',line[:-1],line.replace('approved','foreign'),'PLUGIN_LANG_LOAD\t'+line,line+'trailing',line.replace('[info]','[debug]')):
 rejects(lambda:s['require_class_corroboration'](value,name,uri))
`);
});

test('separate observation channels retain repeated loads and reject every differing digest', () => {
  python(String.raw`
import hashlib,tempfile,zipfile
from pathlib import Path
with tempfile.TemporaryDirectory(prefix='lang-origins-') as temporary:
 root=Path(temporary);repo=root/'repository';maven=root/'maven/bin/mvn';lib=maven.parent.parent/'lib';lib.mkdir(parents=True)
 with zipfile.ZipFile(lib/'maven-core-3.9.16.jar','w') as archive:archive.writestr('META-INF/maven/extension.xml','<extension><exportedArtifacts/></extension>')
 expected=s['read_pins']()['owners']['assembly']['candidate'];realm='test-loader';realm_name='plugin>'+s['OWNERS']['assembly']
 natural=['org/apache/maven/plugins/assembly/mojos/SingleAssemblyMojo','org/apache/maven/plugins/assembly/io/DefaultAssemblyReader','org/apache/maven/plugins/assembly/format/ReaderFormatter','org/apache/maven/shared/filtering/DefaultMavenReaderFilter','org/apache/maven/shared/filtering/DefaultMavenResourcesFiltering']
 target='org/apache/commons/lang3/ClassUtils';assembly=s['artifact_path'](repo,'org.apache.maven.plugins:maven-assembly-plugin:jar:3.7.1');lang=s['artifact_path'](repo,s['LANG_PREFIX']+'3.18.0')
 for path,names in [(assembly,natural),(lang,[target])]:
  path.parent.mkdir(parents=True,exist_ok=True)
  with zipfile.ZipFile(path,'w') as archive:
   archive.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\n')
   for name in names:archive.writestr(name+'.class',name.encode())
 def fields(name,path):return '\t'.join([name,realm,realm_name,path.as_uri(),hashlib.sha256(name.encode()).hexdigest()])
 loads=['PLUGIN_LANG_NATURAL_LOAD\t'+fields(name,assembly) for name in natural]
 stdout='[DEBUG] Populating class realm '+realm_name+'\n'+''.join('[DEBUG] Included: '+c+'\n' for c in expected)
 urls=['PLUGIN_LANG_REALM_URL\t'+realm+'\t'+s['artifact_path'](repo,c).as_uri() for c in expected]
 tail=['PLUGIN_LANG_GOAL_TERMINAL\tMojoSucceeded','PLUGIN_LANG_CONTROLLED_COMPLETE\tSKIPPED','PLUGIN_LANG_TYPE\tSYMBOLIC\t'+fields(target,lang),'PLUGIN_LANG_MISSING_MEMBER\tPASSED','PLUGIN_LANG_LINKAGE_COMPLETE\t0\t0\t0\t'+hashlib.sha256(b'\n').hexdigest()]
 native='\n'.join('[1.000s][info][class,load] '+name.replace('/','.')+' source: '+path.as_uri() for path,names in [(assembly,natural),(lang,[target])] for name in names)
 config={'repository':str(repo),'maven':str(maven)}
 def observe(rows,corroboration=native):return s['observations'](stdout,'\n'.join(urls+rows+tail),corroboration,config,'candidate','assembly',[],'discovery')
 assert len(observe(loads)['naturalOrigins'])==5
 assert len(observe(loads+[loads[0]],native+'\n'+native)['naturalOrigins'])==6
 rejects(lambda:observe(loads+[native],''))
 for value in ('b'*64,'malformed',hashlib.sha256(natural[0].encode()).hexdigest()+'[1.000s][info][class,load] joined'):
  bad='\t'.join(loads[0].split('\t')[:-1])+ '\t'+value
  try:observe(loads+[bad])
  except s['ClassBytesMismatch']:pass
  else:raise AssertionError('differing repeated definition accepted')
`);
});

test('failed observation retains safe mismatch and separate log hash before rethrowing', () => {
  python(String.raw`
import contextlib,io,json,os,signal,tempfile,threading
from pathlib import Path
with tempfile.TemporaryDirectory(prefix='lang-failure-retention-') as temporary:
 root=Path(temporary);output=root/'output';g=s['execute'].__globals__;calls=[]
 handlers={sig:signal.getsignal(sig) for sig in (signal.SIGALRM,signal.SIGTERM,signal.SIGINT)}
 class FakeCommands:
  def __init__(self,*args):self.results=[]
  def run(self,label,command,seconds,extra_env=None):
   calls.append(label);self.results.append({'case':label})
   if label=='compile-observer':
    for name in ('PluginLangRealmObserver','PluginLangRealmCompletionSpy'):(output/'observer'/(name+'.class')).write_bytes(b'fixture')
   else:
    assert '-Xlog:class+load=info:file=' in extra_env['MAVEN_OPTS'] and 'filecount=0' in extra_env['MAVEN_OPTS']
    assert ':stderr' not in extra_env['MAVEN_OPTS']
    (output/'class-load'/(label+'.log')).write_text('[1.000s][info][class,load] fixture.Type source: file:/approved.jar\n')
   return 0,'',''
 def bad_observation(*args):s['require_class_hash']('b'*64,'a'*64,'baseline','assembly','archives','NATURAL','fixture/Type','maven-lib/core.jar','fixture/Type.class')
 g['Commands']=FakeCommands;g['check_seal']=lambda *args,**kwargs:None;g['linkage_rows']=lambda *args:[];g['observations']=bad_observation
 os.environ['ASSEMBLY_ENFORCER_LANG_COMPATIBILITY']='true'
 seal={'config':{'source':str(s['ROOT']),'output':str(output),'jdk':str(root/'jdk'),'maven':str(root/'maven/bin/mvn'),'repository':str(root/'repository')}}
 for key in ('sourceHashes','runtimeHashes','cacheHashes','inventory','classUtilsStatic'):seal[key]={}
 with contextlib.redirect_stdout(io.StringIO()):
  try:s['execute'](seal)
  except s['ClassBytesMismatch']:pass
  else:raise AssertionError('observation failure swallowed')
 result=json.loads((output/'result.json').read_text())
 assert calls==['compile-observer','baseline-assembly-archives'] and result['status']=='FAILED'
 assert result['classObservationMismatch']['observedSha256']=='b'*64
 record=result['commands'][-1]['classLoadCorroborationLog'];assert record==s['read_class_log'](output/record['path'])[1]
 assert result['variants']['baseline']['assembly']=={} and result['classLoadStorage']['failure'] is None
 assert result['classLoadStorage']['scans']>=4
 assert not any(thread.name=='jar-storage-bound' for thread in threading.enumerate())
 assert all(signal.getsignal(sig)==handler for sig,handler in handlers.items()) and signal.alarm(0)==0
`);
});

test('final class log verification rejects late mutation and foreign or missing receipts', () => {
  python(String.raw`
import copy,tempfile
from pathlib import Path
with tempfile.TemporaryDirectory(prefix='lang-final-logs-') as temporary:
 output=Path(temporary);logs=output/'class-load';logs.mkdir();commands=[];variants={}
 for variant in s['VERSIONS']:
  variants[variant]={}
  for owner in s['OWNERS']:
   variants[variant][owner]={}
   for case in s['CASES'][owner]:
    label=variant+'-'+owner+'-'+case;path=logs/(label+'.log');path.write_bytes(b'original bytes\n')
    record=s['read_class_log'](path)[1];commands.append({'case':label,'classLoadCorroborationLog':record})
    variants[variant][owner][case]={'classLoadCorroborationLog':record}
 s['verify_class_logs'](output,commands,variants)
 first=logs/(commands[0]['case']+'.log');first.write_bytes(b'modified bytes\n')
 rejects(lambda:s['verify_class_logs'](output,commands,variants));first.write_bytes(b'original bytes\n')
 rejects(lambda:s['verify_class_logs'](output,commands[:-1],variants))
 altered=copy.deepcopy(variants);altered['baseline']['assembly']['archives']['classLoadCorroborationLog']['path']='class-load/foreign.log'
 rejects(lambda:s['verify_class_logs'](output,commands,altered))
 (logs/'foreign.log').write_bytes(b'foreign');rejects(lambda:s['verify_class_logs'](output,commands,variants))
`);
});

test('runtime seals exclude only the caller file already covered by source or command evidence', () => {
  python(String.raw`
import tempfile,sys
from pathlib import Path
with tempfile.TemporaryDirectory(prefix='lang-runtime-inputs-') as temporary:
 root=Path(temporary);maven=root/'maven/bin/mvn';jdk=root/'jdk'
 (maven.parent.parent/'lib').mkdir(parents=True);(maven.parent.parent/'lib/maven-core-3.9.16.jar').write_bytes(b'fixture')
 jdk.mkdir();(jdk/'release').write_text('JAVA_VERSION="21.0.12.1"\n')
 function=s['runtime_hashes'];globals=function.__globals__
 globals['runtime_files']=lambda j,m,files:files
 globals['hashes']=lambda files:files
 main=sys.modules['__main__'];old=getattr(main,'__file__',None)
 try:
  for caller in (root/'prepare.py',s['HERE']/'assembly-enforcer-lang-compatibility.py'):
   main.__file__=str(caller)
   globals['python_origins']=lambda:([],[caller,root/'imported.py'])
   assert function(maven,jdk)==[root/'imported.py']
 finally:
  if old is None:del main.__file__
  else:main.__file__=old
`);
});
