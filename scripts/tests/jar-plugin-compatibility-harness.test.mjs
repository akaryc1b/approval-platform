import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const harness = fileURLToPath(new URL('./fixtures/jar-plugin-compatibility.py', import.meta.url));
function python(body) {
  const result = spawnSync('python3', ['-I', '-S', '-B', '-c', `import runpy\ns=runpy.run_path(${JSON.stringify(harness)})\ndef rejects(fn):\n try: fn()\n except (ValueError,AssertionError,KeyError): return\n raise AssertionError('unexpected acceptance')\n${body}`],
    { encoding: 'utf8', timeout: 20000, maxBuffer: 1024 * 1024 });
  assert.equal(result.status, 0, result.stdout + result.stderr);
}

test('JAR plan counts exact direct goals and isolates every invocation', () => {
  python(String.raw`
from pathlib import Path
p=s['PLAN']; assert p['mavenCommands']==16 and p['jarGoals']==28 and p['bootGoals']==2
assert p['javacCommands']==3 and p['executableMainCommands']==2 and p['offline'] and not p['sockets']
for variant,version in s['VERSIONS'].items():
 for case,goals in s['CASE_GOALS'].items():
  c=s['maven_command']({'maven':'/runtime/bin/mvn','repository':'/cache'},Path('/fixture'),Path('/fixture/project/pom.xml'),variant,case)
  for flag in ('-o','-N','--strict-checksums','-s','-gs','-t','-gt'): assert flag in c
  assert c[-len(goals):]==[s['BOOT']+':repackage' if goal=='boot:repackage' else s['JAR']+version+':'+goal for goal in goals]
rejects(lambda:s['maven_command']({},Path('/fixture'),Path('/outside/pom.xml'),'candidate','selection'))
rejects(lambda:s['maven_command']({},Path('/fixture'),Path('/fixture/pom.xml'),'candidate','deploy'))
`);
});

test('fixture owns default exclusion, empty, attachment and candidate Boot configuration', () => {
  python(String.raw`
import xml.etree.ElementTree as ET
for variant,version in s['VERSIONS'].items():
 for case in ('selection','empty','incremental','attachments','duplicate','boot'):
  root=ET.fromstring(s['fixture_pom'](variant,case)); ns=s['NS']
  plugin=root.find('m:build/m:plugins/m:plugin',ns)
  assert plugin.findtext('m:version',namespaces=ns)==version
  assert root.findtext('m:properties/m:project.build.outputTimestamp',namespaces=ns)==s['FIXED_TIME']
  executions={e.findtext('m:id',namespaces=ns):e.find('m:configuration',ns) for e in plugin.findall('m:executions/m:execution',ns)}
  if case=='selection':
   assert executions['omitted'].find('m:addDefaultExcludes',ns) is None
   assert executions['explicit'].findtext('m:addDefaultExcludes',namespaces=ns)=='true'
   assert executions['all'].findtext('m:addDefaultExcludes',namespaces=ns)=='false'
   assert [e.text for e in executions['all'].findall('m:excludes/m:exclude',ns)]==['**/*.tmp','excluded/**']
  if case=='empty': assert executions['empty-skip'].findtext('m:skipIfEmpty',namespaces=ns)=='true'
  if case=='boot': assert root.findall('m:build/m:plugins/m:plugin',ns)[1].findtext('m:version',namespaces=ns)=='4.0.8'
`);
});

test('JAR parser rejects unsafe members and timestamp drift, comparator preserves every member observation', () => {
  python(String.raw`
from pathlib import Path
import copy,tempfile,zipfile,warnings
with tempfile.TemporaryDirectory(prefix='jar-parser-test-') as name:
 path=Path(name)/'fixture.jar'
 def make(names,date=s['FIXED_ZIP_TIME']):
  with warnings.catch_warnings():
   warnings.simplefilter('ignore')
   with zipfile.ZipFile(path,'w') as z:
    manifest=zipfile.ZipInfo('META-INF/MANIFEST.MF',s['FIXED_ZIP_TIME']);manifest.external_attr=0o100644<<16
    z.writestr(manifest,b'Manifest-Version: 1.0\r\nFixture-Identity: jar-plugin\r\n\r\n')
    for n in names:
     item=zipfile.ZipInfo(n,date);item.external_attr=0o100644<<16;z.writestr(item,b'ordinary\n')
 make(['ordinary.txt']); before=s['check_payload'](path,{'ordinary.txt':b'ordinary\n'})
 other=copy.deepcopy(before);other['archiveSha256']='different'
 assert s['comparable'](other)==s['comparable'](before)
 for field,value in [('sha256','bad'),('mode',0o100777),('timestamp',[2020,1,1,0,0,0]),('crc32',0),('bytes',0)]:
  other=copy.deepcopy(before);other['members']['ordinary.txt'][field]=value
  assert s['comparable'](other)!=s['comparable'](before)
 for names,date in [(['../escape'],s['FIXED_ZIP_TIME']),(['/absolute'],s['FIXED_ZIP_TIME']),(['same','same'],s['FIXED_ZIP_TIME']),(['ordinary.txt'],(2020,1,1,0,0,0))]:
  make(names,date);rejects(lambda:s['archive_members'](path))
`);
});

test('effective MR class entries obey the manifest and the sealed JDK21 target', () => {
  python(String.raw`
from pathlib import Path
import tempfile,zipfile
with tempfile.TemporaryDirectory(prefix='jar-mr-test-') as name:
 path=Path(name)/'fixture.jar'
 for multi in (False,True):
  with zipfile.ZipFile(path,'w') as z:
   z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\n'+('Multi-Release: true\n' if multi else '')+'\n')
   for entry in ('x/Foo.class','META-INF/versions/11/x/Foo.class','META-INF/versions/22/x/Foo.class'):z.writestr(entry,b'class')
  with zipfile.ZipFile(path) as z: assert s['effective_entry'](z,'x/Foo')==('META-INF/versions/11/x/Foo.class' if multi else 'x/Foo.class')
`);
});

test('JAR-only storage accounting rejects aggregate byte, entry and symlink violations', () => {
  python(String.raw`
from pathlib import Path
import tempfile
with tempfile.TemporaryDirectory(prefix='jar-storage-test-') as name:
 root=Path(name);(root/'a').write_bytes(b'1234');(root/'nested').mkdir();(root/'nested/b').write_bytes(b'56')
 guard=s['StorageGuard'](root,maximum_bytes=6,maximum_entries=3)
 assert guard.scan()==(6,3) and guard.report()['sampledBound']
 rejects(lambda:s['StorageGuard'](root,maximum_bytes=5).scan())
 rejects(lambda:s['StorageGuard'](root,maximum_entries=2).scan())
 rejects(lambda:s['StorageGuard'](root,file_limits={'a':3}).scan())
 (root/'link').symlink_to(root/'a');rejects(lambda:s['StorageGuard'](root).scan())
`);
});

test('separate JVM corroboration preserves exact agent hashes and refuses mixed stderr records', () => {
  python(String.raw`
from pathlib import Path
import tempfile,zipfile,hashlib
with tempfile.TemporaryDirectory(prefix='jar-definition-test-') as name:
 path=Path(name)/'fixture.jar'; data=b'exact class bytes'; sha=hashlib.sha256(data).hexdigest()
 with zipfile.ZipFile(path,'w') as z:z.writestr('x/Foo.class',data)
 config={'maven':'/runtime/bin/mvn'}; coordinates={str(path):'fixture:fixture:jar:1'}
 agent='JAR_NATURAL\tx/Foo\tloader\t'+path.as_uri()+'\t'+sha
 native='[1.654s][info][class,load] x.Foo source: file:'+str(path)
 phase,row=s['definition_origin'](agent,native,config,coordinates)
 assert phase=='JAR_NATURAL' and row['sha256']==sha and row['entry']=='x/Foo.class'
 rejects(lambda:s['definition_origin'](agent+native,native,config,coordinates))
 rejects(lambda:s['definition_origin'](agent,'',config,coordinates))
 rejects(lambda:s['definition_origin'](agent[:-64]+'0'*64,native,config,coordinates))
 rejects(lambda:s['definition_origin'](agent,native.replace(str(path),'/wrong.jar'),config,coordinates))
`);
});

test('hosted preparation resolves only paired JAR and accepted Boot owners through fixed Central settings', () => {
  python(String.raw`
from pathlib import Path
import runpy,xml.etree.ElementTree as ET
p=runpy.run_path(str(s['HERE']/'jar-plugin-portable.py')); ns=s['NS']
for variant,version in s['VERSIONS'].items():
 root=ET.fromstring(p['preparation_pom'](s,variant))
 assert root.findtext('m:packaging',namespaces=ns)=='pom' and root.find('m:parent',ns) is None
 plugins=root.findall('m:build/m:plugins/m:plugin',ns)
 assert [(v.findtext('m:artifactId',namespaces=ns),v.findtext('m:version',namespaces=ns)) for v in plugins]==[('maven-jar-plugin',version)]+([('spring-boot-maven-plugin','4.0.8')] if variant=='candidate' else [])
 command=p['preparation_command']({'maven':'/runtime/bin/mvn','repository':'/cache'},Path('/owned'),variant,True)
 assert command[-3]==p['RESOLVE'] and '-N' in command and '--strict-checksums' in command
 assert command[-2]=='-DincludeArtifactIds='+('maven-jar-plugin,spring-boot-maven-plugin' if variant=='candidate' else 'maven-jar-plugin')
 rejects(lambda:p['preparation_command']({'maven':'/runtime/bin/mvn','repository':'/cache'},Path('/owned'),variant,False))
assert 'https://repo.maven.apache.org/maven2' in p['CENTRAL_SETTINGS'] and '<mirrorOf>*</mirrorOf>' in p['CENTRAL_SETTINGS']
`);
});
