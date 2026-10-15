import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import './assembly-plexus-receipt-retention.test.mjs';

const harness = fileURLToPath(new URL('./fixtures/assembly-plexus-compatibility.py', import.meta.url));
function python(body) {
  const result = spawnSync('python3', ['-I', '-S', '-B', '-c', `import runpy\ns=runpy.run_path(${JSON.stringify(harness)})\ndef rejects(fn):\n try: fn()\n except (ValueError,AssertionError,KeyError): return\n raise AssertionError('unexpected acceptance')\n${body}`],
    { encoding: 'utf8', timeout: 15000, maxBuffer: 1024 * 1024 });
  assert.equal(result.status, 0, result.stdout + result.stderr);
}

test('Assembly plan is inert, six exact pairs and bounded offline direct goals', () => {
  python(String.raw`
from pathlib import Path
assert s['PLAN']['actualGoalCount']==12 and len(s['CASES'])==6
assert s['PLAN']['aggregateTimeoutSeconds']==900 and s['PLAN']['commandTimeoutSeconds']==60
assert s['PLAN']['offline'] and not s['PLAN']['sockets']
cmd=s['maven_command']({'maven':'/runtime/bin/mvn','repository':'/cache'},Path('/fixture'),Path('/fixture/pom.xml'))
assert cmd[-1]=='org.apache.maven.plugins:maven-assembly-plugin:3.7.1:single'
for key in ('-o','-N','--strict-checksums','-s','-gs','-t','-gt'):assert key in cmd
for key in ('-t','-gt'):assert cmd[cmd.index(key)+1]=='/fixture/empty-toolchains.xml'
rejects(lambda:s['maven_command']({'maven':'/runtime/bin/mvn','repository':'/cache'},Path('/fixture'),Path('/outside/pom.xml')))
for goal in ('verify','assembly:single','deploy','exec:exec'):rejects(lambda:s['maven_command']({},Path('/fixture'),Path('/fixture/pom.xml'),goal))
`);
});

test('reviewed consumer definition includes all direct, cross-type and descriptor-only references', () => {
  python(String.raw`
import json
pins=s['read_pins'](); consumers=json.loads(s['CONSUMERS'].read_text())
assert len(pins['variants']['baseline'])==21 and len(pins['artifactHashes'])==22
assert consumers['consumerCount']==55 and consumers['directAssemblyConsumerCount']==12
assert len(consumers['consumers'])==55
assert any(not c['memberReferences'] and c['classAndDescriptorReferences'] for c in consumers['consumers'])
assert consumers['consumerCountsByJar']['org.codehaus.plexus:plexus-xml:jar:3.0.0']==20
`);
});

test('owned project covers all formats, independent discovery, default settings and four descriptor failures', () => {
  python(String.raw`
from pathlib import Path
import tempfile,xml.etree.ElementTree as ET
contract=s['run_path'](str(s['CONTRACT']))
for baseline in (True,False):
 plugin=contract['fixture_plugin'](s['ROOT'],baseline=baseline)
 with tempfile.TemporaryDirectory(prefix='assembly-input-test-') as name:
  root=Path(name)
  for case in s['CASES']:
   directory=root/case;fixed=s['write_case'](directory,plugin,case)
   assert b'<project xmlns="http://maven.apache.org/POM/4.0.0">' in (directory/'pom.xml').read_bytes()
   assert fixed and str(directory/'dependency.jar') in fixed
   doc=ET.parse(directory/'pom.xml').getroot();ns=s['NS']
   configuration=doc.find('m:build/m:plugins/m:plugin/m:configuration',ns)
   assert configuration.findtext('m:attach',namespaces=ns)=='false'
   assert configuration.findtext('m:outputTimestamp',namespaces=ns)==s['FIXED_TIME']
   if case=='discovery':
    assert configuration.find('m:descriptors',ns) is None
    assert configuration.find('m:descriptorSourceDirectory',ns) is not None
    assert (directory/'discovery/.gitignore/ignore-dir.xml').is_file()
    assert (directory/'discovery/nested/.gitignore').is_file()
   else:assert configuration.find('m:descriptorSourceDirectory',ns) is None
   if case=='missing-main':assert not (directory/'main.xml').exists()
   if case=='missing-component':assert not (directory/'component.xml').exists()
  desc=ET.parse(root/'archives/main.xml').getroot()
  assert [e.text for e in desc.findall('formats/format')]==['dir','zip','jar','tar']
  assert [e.findtext('useDefaultExcludes') for e in desc.findall('fileSets/fileSet')[:3]]==[None,'true','false']
  assert [e.findtext('unpackOptions/useDefaultExcludes') for e in desc.findall('dependencySets/dependencySet')[:3]]==[None,'true','false']
  assert desc.findtext('containerDescriptorHandlers/containerDescriptorHandler/handlerName')=='plexus'
`);
});

test('archive parser rejects stale timestamps, path escapes and duplicate members', () => {
  python(String.raw`
from pathlib import Path
import tempfile,zipfile,warnings
with tempfile.TemporaryDirectory(prefix='assembly-output-test-') as name:
 path=Path(name)/'out.zip'
 def make(names,date=(2024,1,2,3,4,6)):
  with warnings.catch_warnings():
   warnings.simplefilter('ignore')
   with zipfile.ZipFile(path,'w') as z:
    for name in names:
     item=zipfile.ZipInfo(name,date);item.external_attr=0o100640<<16;z.writestr(item,b'fixture')
 make(['ordinary.txt']);assert set(s['archive_members'](path)['files'])=={'ordinary.txt'}
 for names,date in [(['../escape'],(2024,1,2,3,4,6)),(['/absolute'],(2024,1,2,3,4,6)),(['same','same'],(2024,1,2,3,4,6)),(['ordinary'],(2020,1,1,0,0,0))]:
  make(names,date);rejects(lambda:s['archive_members'](path))
`);
});

test('symbolic rows require a witnessed invocation kind for every exact member', () => {
  python(String.raw`
member={'kind':'method','owner':'org/codehaus/plexus/util/FileUtils','name':'copyFile','descriptor':'(Ljava/io/File;Ljava/io/File;)V'}
consumer={'classEntry':'fixture/Consumer.class','classAndDescriptorReferences':[member['owner']],'memberReferences':[member]}
seal={'inventory':{'consumers':[consumer]},'memberUses':[['fixture/Consumer',member['kind'],member['owner'],member['name'],member['descriptor'],'static']]}
rows=s['linkage_rows'](seal);assert len(rows)==2 and rows[1].endswith('\tstatic')
seal['memberUses']=[];rejects(lambda:s['linkage_rows'](seal))
`);
});

test('hosted cache preparation permits only two owner resolutions and uses fixed Central settings', () => {
  python(String.raw`
from pathlib import Path
p=s['run_path'](str(s['HERE']/'assembly-plexus-portable.py'))
for variant in ('baseline','candidate'):
 c=p['preparation_command']({'maven':'/runtime/bin/mvn','repository':'/cache'},Path('/fixture'),variant,True)
 assert c.count('org.apache.maven.plugins:maven-dependency-plugin:3.11.0:resolve-plugins')==1
 assert '-DincludeArtifactIds=maven-assembly-plugin' in c and '--strict-checksums' in c
 for key in ('-s','-gs'):assert c[c.index(key)+1]=='/fixture/central-settings.xml'
 for key in ('-t','-gt'):assert c[c.index(key)+1]=='/fixture/empty-toolchains.xml'
 rejects(lambda:p['preparation_command']({},Path('/fixture'),variant,False))
rejects(lambda:p['preparation_command']({},Path('/fixture'),'other',True))
assert '<mirrorOf>*</mirrorOf>' in p['CENTRAL_SETTINGS'] and 'https://repo.maven.apache.org/maven2' in p['CENTRAL_SETTINGS']
`);
});

test('archive comparator permits only the measured regular-file and directory default-exclude delta', () => {
  python(String.raw`
import copy
removed={prefix+'/'+name for prefix in ('fs-omitted','fs-true','dep-omitted','dep-true') for name in ('.gitignore','nested/.gitignore')}
dirs={'fs-'+setting+'/dir-case/.gitignore' for setting in ('omitted','true')}
before={};after={}
for format in ('fixture-real','fixture-real.zip','fixture-real.jar','fixture-real.tar'):
 b={'files':{n:{'sha256':'a'*64,'bytes':1,'mode':0o640}for n in removed|{'keep'}},'directories':{n:0o750 for n in dirs|{'keep-dir'}},
    'metadata':{n:{'timestamp':123,'mode':0o640 if n in removed|{'keep'} else 0o750}for n in removed|{'keep'}|dirs|{'keep-dir'}},
    'selections':{prefix:['.gitignore','nested/.gitignore','ordinary.txt']for prefix in ('fs-omitted','fs-true','fs-false','dep-omitted','dep-true','dep-false')},
    'archiveSha256':None if format=='fixture-real' else 'a'*64}
 c=copy.deepcopy(b)
 for name in removed:del c['files'][name];del c['metadata'][name]
 for name in dirs:
  if format.endswith('.tar'):del c['directories'][name];del c['metadata'][name]
  else:c['directories'][name]=0o755;c['metadata'][name]['mode']=0o755
 for key in c['selections']:
  if not key.endswith('-false'):c['selections'][key]=['ordinary.txt']
 c['archiveSha256']=None if format=='fixture-real' else 'b'*64
 before[format]=b;after[format]=c
assert len(s['compare_archives'](before,after))==4
for field in ('payload','file-mode','directory','timestamp','false-selection','extra-file','extra-removal','extra-field'):
 changed=copy.deepcopy(after);c=changed['fixture-real.zip']
 if field=='payload':c['files']['keep']['sha256']='c'*64
 elif field=='file-mode':c['files']['keep']['mode']=0o777
 elif field=='directory':c['directories']['keep-dir']=0o777
 elif field=='timestamp':c['metadata']['keep']['timestamp']=124
 elif field=='false-selection':c['selections']['fs-false']=['ordinary.txt']
 elif field=='extra-file':c['files']['extra']={'sha256':'d'*64,'bytes':2,'mode':0o640}
 elif field=='extra-removal':del c['files']['keep']
 else:c['unreviewed']='extra'
 rejects(lambda:s['compare_archives'](before,changed))
`);
});
