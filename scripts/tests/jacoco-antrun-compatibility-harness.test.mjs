import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import vm from 'node:vm';

const fixtures = fileURLToPath(new URL('./fixtures/', import.meta.url));
const blocked = String.raw`
import http.server, ssl, runpy, subprocess, socket, sys
def blocked(*args, **kwargs): raise AssertionError('pure fixture test attempted a process or socket')
subprocess.Popen = socket.socket = blocked
s=runpy.run_path(sys.argv[1]+'/jacoco-antrun-compatibility.py')
def rejects(fn):
    try: fn()
    except (ValueError, KeyError): return
    raise AssertionError('malformed fixture input was accepted')
`;
function python(source) {
  const result = spawnSync('/usr/bin/python3', ['-I', '-S', '-B', '-c', blocked + source, fixtures], {
    env: { PATH: '/usr/bin:/bin', LANG: 'C.UTF-8' }, encoding: 'utf8', timeout: 30000, maxBuffer: 2 * 1024 * 1024,
  });
  assert.equal(result.status, 0, `${result.error?.message ?? ''}\n${result.stdout}\n${result.stderr}`);
  return result.stdout;
}

test('JaCoCo AntRun plan is inert and live execution requires explicit reviewed opt-in', () => {
  const plan = JSON.parse(python(String.raw`
rejects(lambda:s['execute']({}))
sys.argv=['jacoco-antrun-compatibility.py']
runpy.run_path(s['__file__'],run_name='__main__')
`));
  assert.equal(plan.execution, 'NOT_RUN');
  assert.equal(plan.offline, true);
  assert.equal(plan.sockets, false);
  assert.equal(plan.aggregateTimeoutSeconds, 360);
  assert.equal(plan.outerTimeoutSeconds, 420);
  assert.equal(plan.memberCount, 34);
  assert.match(plan.testKind, /not Surefire or JUnit/);
  assert.equal(plan.productionAntRunActivationClaim, false);
});

test('private utility pins reject arbitrary artifacts, changed bytes and changed owner scopes', () => {
  python(String.raw`
import copy,json,tempfile
from pathlib import Path
pins=s['read_pins']()
assert len(pins['artifactHashes'])==18
assert len(pins['owners']['jacoco']['baseline'])==13
assert len(pins['owners']['antrun']['baseline'])==4
with tempfile.TemporaryDirectory(prefix='pair-pins-') as d:
    file=Path(d)/'pins.json'
    for kind in ('hash','extra','missing','owner','version','order'):
        p=copy.deepcopy(pins)
        if kind=='hash':p['artifactHashes'][next(iter(p['artifactHashes']))]='0'*64
        if kind=='extra':p['artifactHashes']['arbitrary:jar:jar:1']='0'*64
        if kind=='missing':p['owners']['antrun']['candidate'].pop()
        if kind=='owner':p['owners']['antrun']['candidate']=p['owners']['jacoco']['candidate']
        if kind=='version':p['owners']['jacoco']['candidate'][1]='org.codehaus.plexus:plexus-utils:jar:4.0.3'
        if kind=='order':p['owners']['jacoco']['candidate'].reverse()
        file.write_text(json.dumps(p));rejects(lambda:s['read_pins'](file))
rejects(lambda:s['artifact_path'](Path('/cache'),'evil/../../:name:jar:1'))
`);
});

test('disposable POMs exercise assertion JVM and merge defaults while preserving source owners', () => {
  python(String.raw`
import xml.etree.ElementTree as ET
from pathlib import Path
import tempfile
contract=runpy.run_path(str(s['CONTRACT'])); ns=s['NS']
projection_directory=tempfile.TemporaryDirectory(prefix='pair-source-test-')
config={'source':str(s['ROOT']),'output':str(Path(projection_directory.name)/'goals')}
projection=s['prepare_source_projection'](config);root=s['source_for_contract'](config,projection)
for baseline in (True,False):
    plugins=contract['fixture_plugins'](root,baseline=baseline)
    xml=s['fixture_pom'](plugins,Path('/fixture/candidate'))
    document=ET.fromstring(xml)
    found=document.findall('m:build/m:plugins/m:plugin',ns)
    assert [p.findtext('m:artifactId',namespaces=ns) for p in found]==['jacoco-maven-plugin','maven-antrun-plugin']
    assert not document.findall('.//m:executions',ns)
    for p in found:
        dependencies=p.findall('m:dependencies/m:dependency',ns)
        assert len(dependencies)==(0 if baseline else 1)
        if dependencies:assert dependencies[0].findtext('m:version',namespaces=ns)=='3.6.1'
    target=found[1].find('m:configuration/m:target',ns)
    java=target.find('m:java',ns)
    assert java.get('fork')=='true' and java.get('failonerror')=='true' and java.get('timeout')=='15000'
    assert java.find('m:jvmarg',ns).get('line')=='$'+'{fixture.jacoco.argLine}'
    assert java.findall('m:jvmarg',ns)[1].get('line')=='$'+'{argLine}'
    assert target.find('m:attachartifact',ns).get('type') is None
    assert target.find('m:echo',ns).text==s['TEXT']
    for mode in ('merge-defaults','merge-explicit-excludes','merge-invalid-exec'):
        merged=ET.fromstring(s['fixture_pom'](plugins,Path('/fixture/candidate'),mode))
        jc=merged.find('m:build/m:plugins/m:plugin/m:configuration',ns)
        fs=jc.find('m:fileSets/m:fileSet',ns)
        assert fs.findtext('m:useDefaultExcludes',namespaces=ns)=='true'
        assert fs.findtext('m:followSymlinks',namespaces=ns)=='false'
        assert fs.findtext('m:includes/m:include',namespaces=ns)=='**'
        assert [e.text for e in fs.findall('m:excludes/m:exclude',ns)]==(['**/'+n for n in s['IGNORE']] if mode=='merge-explicit-excludes' else [])
        assert jc.findtext('m:includes/m:include',namespaces=ns)=='fixture/CoverageTarget*'
        assert jc.findtext('m:dataFile',namespaces=ns).endswith(mode+'.exec')
rejects(lambda:s['fixture_pom'](plugins,Path('/fixture'),'publish'))
s['source_for_contract'](config,projection)
projection_directory.cleanup()
`);
});

test('agent property survives initial Maven interpolation and preserves original JVM arguments', () => {
  python(String.raw`
import xml.etree.ElementTree as ET
from pathlib import Path
import tempfile
projection_directory=tempfile.TemporaryDirectory(prefix='pair-agent-source-test-')
config={'source':str(s['ROOT']),'output':str(Path(projection_directory.name)/'goals')}
projection=s['prepare_source_projection'](config);source=s['source_for_contract'](config,projection)
plugins=runpy.run_path(str(s['CONTRACT']))['fixture_plugins'](source,baseline=True)
raw=s['fixture_pom'](plugins,Path('/fixture/baseline')).decode();document=ET.fromstring(raw);ns=s['NS']
props={x.tag.split('}',1)[1]:x.text for x in document.find('m:properties',ns)}
assert props['argLine']=='-Dfixture.original=kept'
assert 'fixture.jacoco.argLine' not in props
assert document.findtext('m:build/m:plugins/m:plugin/m:configuration/m:propertyName',namespaces=ns)=='fixture.jacoco.argLine'
# Preserve the exact observed lifecycle boundary: initial model properties can
# resolve the original flag, while the agent property is populated only later.
for key,value in props.items():raw=raw.replace('$'+'{'+key+'}',value)
early=ET.fromstring(raw)
target=early.findall('m:build/m:plugins/m:plugin',ns)[1].find('m:configuration/m:target/m:java',ns)
lines=[x.get('line') for x in target.findall('m:jvmarg',ns) if x.get('line') is not None]
assert lines==['$'+'{fixture.jacoco.argLine}','-Dfixture.original=kept']
late=lines[0].replace('$'+'{fixture.jacoco.argLine}','-javaagent:/fixture/agent.jar=destfile=/fixture/jacoco.exec')
assert late.startswith('-javaagent:') and lines[1]=='-Dfixture.original=kept'
assert 'real JaCoCo agent argument missing or duplicated' in s['ASSERTIONS']
s['source_for_contract'](config,projection)
projection_directory.cleanup()
`);
});

test('offline direct-goal commands reject lifecycle and foreign goal expansion', () => {
  python(String.raw`
from pathlib import Path
config={'maven':'/runtime/bin/mvn','repository':'/cache'}; sandbox=Path('/fixture')
command=s['maven_command'](config,sandbox,sandbox/'candidate/pom.xml',[s['JACOCO']+'prepare-agent',s['ANTRUN'],s['JACOCO']+'report'])
for option in ('-o','-N','--strict-checksums','-s','-gs','-t','-gt'):assert option in command
for option in ('-t','-gt'):assert command[command.index(option)+1]=='/fixture/empty-toolchains.xml'
for goals in (['verify'],['deploy'],['org.jacoco:jacoco-maven-plugin:0.8.15:dump'],['exec:exec'],[]):
    rejects(lambda:s['maven_command'](config,sandbox,sandbox/'pom.xml',goals))
rejects(lambda:s['maven_command'](config,sandbox,Path('/outside/pom.xml'),[s['ANTRUN']]))
`);
});

test('natural realms require matching owner membership, loader, private origin and JVM witness', () => {
  python(String.raw`
from pathlib import Path
pins=s['read_pins']();config={'repository':'/cache','maven':'/runtime/bin/mvn'};resolved=pins['owners']['jacoco']['baseline']
expected=s['natural_members']('jacoco',resolved)
assert len(resolved)==13 and len(expected)==12 and set(resolved)-set(expected)=={s['SHARED_SLF4J']}
assert s['natural_members']('antrun',pins['owners']['antrun']['baseline'])==pins['owners']['antrun']['baseline']
rejects(lambda:s['natural_members']('jacoco',expected))
rejects(lambda:s['natural_members']('antrun',pins['owners']['antrun']['baseline']+[s['SHARED_SLF4J']]))
owner='org.jacoco:jacoco-maven-plugin:0.8.15'
imports=''.join('[DEBUG]   Imported: '+name+' < plexus.core\n' for name in s['SLF4J_PACKAGES'])
imports+='[DEBUG] Importing foreign packages into class realm plugin>'+owner+'\n[DEBUG]   Imported:  < maven.api\n'
stdout=imports+'[DEBUG] Populating class realm plugin>'+owner+'\n'+''.join('[DEBUG]   Included: '+c+'\n' for c in expected)
assert s['realm'](stdout,'jacoco',resolved)==expected
rejects(lambda:s['realm'](stdout+stdout,'jacoco',resolved))
rejects(lambda:s['realm'](stdout.replace('3.0.24','3.6.1'),'jacoco',resolved))
rejects(lambda:s['realm'](stdout+'[DEBUG]   Included: '+s['SHARED_SLF4J']+'\n','jacoco',resolved))
rejects(lambda:s['realm'](stdout.replace('[DEBUG]   Included: '+expected[-1]+'\n',''),'jacoco',resolved))
plugin=str(s['artifact_path'](Path('/cache'),expected[0]));utility=str(s['artifact_path'](Path('/cache'),expected[1]))
rows=[('org/jacoco/maven/ReportMojo',plugin),('org/codehaus/plexus/util/DirectoryScanner',utility)]
stderr=''.join('[0.01s][info][class,load] '+n.replace('/','.')+' source: file:'+p+'\nJAC_ANTRUN_ORIGIN\t'+n+'\t1\tClassRealm[plugin>'+owner+']\tfile:'+p+'\n' for n,p in rows)
stderr+='[0.01s][info][class,load] org.slf4j.Logger source: file:/runtime/lib/slf4j-api-1.7.36.jar\n'
stderr+='[0.01s][info][class,load] org.slf4j.LoggerFactory source: file:/runtime/lib/slf4j-api-1.7.36.jar\n'
stderr+=''.join('JAC_ANTRUN_REALM_URL\t1\tfile:'+str(s['artifact_path'](Path('/cache'),c))+'\n' for c in expected)
required={'jacoco':{n for n,p in rows}}
assert len(s['natural_origins'](stdout,stderr,config,'baseline',required))==2
wrong=str(s['artifact_path'](Path('/cache'),'org.codehaus.plexus:plexus-utils:jar:3.4.1'))
for altered in (stderr.replace(utility,wrong),stderr.replace('[class,load]','[other]'),
                stderr.replace('JAC_ANTRUN_ORIGIN\t'+rows[1][0]+'\t1','JAC_ANTRUN_ORIGIN\t'+rows[1][0]+'\t2'),
                stderr+'JAC_ANTRUN_REALM_URL\t1\tfile:'+plugin+'\n'):
    rejects(lambda:s['natural_origins'](stdout,altered,config,'baseline',required))
for altered in (stdout.replace('org.slf4j.spi.*','org.slf4j.other.*'),stdout.replace(' < maven.api',' < other.api'),stdout+imports):
    rejects(lambda:s['natural_origins'](altered,stderr,config,'baseline',required))
rejects(lambda:s['natural_origins'](stdout,stderr.replace('/runtime/lib/slf4j-api-1.7.36.jar','/cache/slf4j-api-1.7.36.jar'),config,'baseline',required))
`);
});

test('report parser requires real named branch coverage', () => {
  python(String.raw`
import tempfile
from pathlib import Path
good='<report><package name="fixture"><class name="fixture/CoverageTarget"/></package><counter type="BRANCH" covered="2" missed="0"/><counter type="CLASS" covered="1" missed="0"/><counter type="INSTRUCTION" covered="10" missed="3"/></report>'
with tempfile.TemporaryDirectory(prefix='pair-report-') as d:
    file=Path(d)/'report.xml';file.write_text(good);assert s['report_result'](file)['counters']['BRANCH']['covered']==2
    for value in ('',good.replace('covered="2"','covered="0"'),good.replace('fixture/CoverageTarget','fixture/Fake'),good.replace('</package>','<class name="fixture/Extra"/></package>')):
        file.write_text(value);rejects(lambda:s['report_result'](file))
`);
});

test('Maven shared API filtering requires its declared export and exact SLF4J bytes', () => {
  python(String.raw`
import copy,hashlib,tempfile,zipfile
from pathlib import Path
pins=copy.deepcopy(s['read_pins']());payload=b'explicit mock shared API artifact'
pins['artifactHashes'][s['SHARED_SLF4J']]=hashlib.sha256(payload).hexdigest()
s['shared_maven_api'].__globals__['read_pins']=lambda:pins
xml='<extension><exportedPackages>'+''.join('<exportedPackage>'+name+'</exportedPackage>' for name in s['SLF4J_PACKAGES'])+'</exportedPackages><exportedArtifacts><exportedArtifact>org.slf4j:slf4j-api</exportedArtifact></exportedArtifacts></extension>'
with tempfile.TemporaryDirectory(prefix='pair-shared-api-') as name:
    root=Path(name);library=root/'lib';library.mkdir();jar=library/'slf4j-api-1.7.36.jar';jar.write_bytes(payload)
    def core(text):
        with zipfile.ZipFile(library/'maven-core-3.9.16.jar','w') as archive:archive.writestr('META-INF/maven/extension.xml',text)
    core(xml);result=s['shared_maven_api'](root/'bin/mvn')
    assert result['coordinate']==s['SHARED_SLF4J'] and len(result['requiredClassLoadOrigins'])==2
    for changed in (xml.replace('org.slf4j:slf4j-api','other:artifact'),xml.replace('org.slf4j.spi.*','org.slf4j.other.*'),
                    xml.replace('</exportedArtifacts>','<exportedArtifact>org.codehaus.plexus:plexus-utils</exportedArtifact></exportedArtifacts>')):
        core(changed);rejects(lambda:s['shared_maven_api'](root/'bin/mvn'))
    core(xml);jar.write_bytes(b'changed');rejects(lambda:s['shared_maven_api'](root/'bin/mvn'))
`);
});

test('merge observations separate source loads from the report consumer and require exact goal boundaries', () => {
  python(String.raw`
from pathlib import Path
merge='[INFO] --- jacoco:0.8.15:merge (default-cli) @ jacoco-antrun ---\n'
report='[INFO] --- jacoco:0.8.15:report (default-cli) @ jacoco-antrun ---\n'
prefix='[INFO] Loading execution data file '
inputs=prefix+'/fixture/merge-input/.gitignore\n'+prefix+'/fixture/merge-input/normal.exec\n'
report_load=prefix+'/fixture/target/merge-defaults.exec\n'
log=merge+inputs+report+report_load
parse=lambda value:s['merge_inputs'](value,Path('/fixture/merge-input'),Path('/fixture/target/merge-defaults.exec'))
assert parse(log)==['.gitignore','normal.exec']
for changed in (
    log.replace('/fixture/merge-input/.gitignore','/outside/data.exec'),
    log.replace('/fixture/merge-input/.gitignore','/fixture/merge-input/normal.exec'),
    log.replace(merge,''),log.replace(report,''),merge+log,log+report,
    report+inputs+merge+report_load,
    inputs+log,log.replace(report_load,''),log+report_load,
    log.replace(report_load,prefix+'/fixture/target/wrong.exec\n'),
    log.replace(report,report+'[INFO] --- jacoco:0.8.15:other (default-cli) @ jacoco-antrun ---\n')):
    rejects(lambda:parse(changed))
`);
});

test('API receipts bind collected support origins and reject missing members, forged scanner and extraction receipts', () => {
  python(String.raw`
import copy,tempfile,zipfile
from pathlib import Path
variant='candidate';h='a'*64;artifact={'path':'/mock/plexus.jar','sha256':h};artifacts={'plexus-utils':artifact}
names=['org.jacoco.maven.FileFilter','org.apache.maven.shared.model.fileset.util.FileSetManager','org.apache.maven.ant.tasks.AttachArtifactTask','org.apache.maven.ant.tasks.support.VersionMapper','org.codehaus.plexus.util.Expand']+['fixture.F'+str(i) for i in range(8)]
origins=['JAC_ANTRUN_API_ORIGIN\t'+n+'\t'+artifact['path']+'\t'+h for n in names]
with tempfile.TemporaryDirectory(prefix='pair-api-support-') as value:
    root=Path(value);(root/'lib').mkdir();file=root/'lib/maven-core-3.9.16.jar'
    with zipfile.ZipFile(file,'w') as archive:archive.writestr('META-INF/maven/org.apache.maven/maven-core/pom.properties','artifactId=maven-core\nversion=3.9.16\n')
    support,basis=s['maven_support_artifacts'](root/'bin/mvn',artifacts)
    assert set(support['maven-core'])=={'path','sha256'} and basis=={'maven-core':'embedded Maven pom.properties'}
    artifacts.update(support)
support_names=['org.apache.maven.project.MavenProject','org.apache.maven.project.MavenProjectHelper']
for name in support_names:origins.append('JAC_ANTRUN_API_ORIGIN\t'+name+'\t'+support['maven-core']['path']+'\t'+support['maven-core']['sha256'])
members=s['MEMBERS'].read_text().splitlines()
rows=origins+['JAC_ANTRUN_MEMBER\t'+row for row in members]+[
 'JAC_ANTRUN_LINKAGE\tcandidate\t34\t'+s['digest'](s['MEMBERS']),
 'JAC_ANTRUN_COMPLETE\tcandidate\t400\tapi-replay-only',
 'JAC_ANTRUN_SCANNER\tcandidate\tdefaultIgnoreFilesIncluded=4\texplicitIgnoreFilesIncluded=0\tscmFilesIncluded=0',
 'JAC_ANTRUN_FILE_FILTER\tcandidate\tignoreFilesIncluded=4\tfiles=10',
 'JAC_ANTRUN_DIRECT_API\tcopyFile\tcopyDirectoryStructure\tcloseInput\tcloseOutput',
 'JAC_ANTRUN_ANT_CONSUMERS\tVersionMapper\tAttachArtifactTask\tattachmentType=txt\tattachments=1',
 'JAC_ANTRUN_EXPAND\tcandidate\trelative\trejected-no-sibling\tdirect-api-only',
 'JAC_ANTRUN_EXPAND\tcandidate\tabsolute\trejected-no-sibling\tdirect-api-only']
stdout='\n'.join(rows);stderr='\n'.join(n+' source: file:'+artifact['path'] for n in names)
stderr+='\n'+'\n'.join(name+' source: file:'+support['maven-core']['path'] for name in support_names)
assert s['api_result'](stdout,stderr,variant,artifacts)['defaultIgnoreFilesIncluded']==4
bad=copy.deepcopy(artifacts);bad['maven-core']['identityBasis']=basis['maven-core']
rejects(lambda:s['api_result'](stdout,stderr,variant,bad))
rejects(lambda:s['api_result'](stdout.replace(support['maven-core']['sha256'],'b'*64),stderr,variant,artifacts))
for old,new in [('defaultIgnoreFilesIncluded=4','defaultIgnoreFilesIncluded=0'),('ignoreFilesIncluded=4','ignoreFilesIncluded=0'),('JAC_ANTRUN_COMPLETE','MISSING'),('JAC_ANTRUN_MEMBER\t'+members[0],'MISSING'),('rejected-no-sibling','baseline-owned-sibling'),('JAC_ANTRUN_EXPAND','MISSING')]:
    rejects(lambda:s['api_result'](stdout.replace(old,new),stderr,variant,artifacts))
rejects(lambda:s['api_result'](stdout,'',variant,artifacts))
`);
});

test('five metadata-free Maven auxiliary identities require exact filenames and bytes', () => {
  python(String.raw`
import tempfile,zipfile
from pathlib import Path
fn=s['maven_support_identity']; original_digest=fn.__globals__['digest']
with tempfile.TemporaryDirectory(prefix='pair-support-') as value:
    root=Path(value)
    def jar(name, entries=()):
        file=root/name
        with zipfile.ZipFile(file,'w') as archive:
            for entry,text in entries:archive.writestr(entry,text)
        return file
    normal=jar('example-1.jar',[('META-INF/maven/group/example/pom.properties','artifactId=example\nversion=1\n')])
    assert fn(normal)==('example','embedded Maven pom.properties')
    ambiguous=jar('ambiguous-1.jar',[(f'META-INF/maven/g{i}/ambiguous/pom.properties','artifactId=ambiguous\nversion=1\n') for i in range(2)])
    rejects(lambda:fn(ambiguous))
    rejects(lambda:fn(jar('unrecognized-1.jar')))
    for name,(artifact,expected,evidence) in s['MAVEN_AUXILIARY_IDENTITIES'].items():
        file=jar(name)
        rejects(lambda:fn(file))
        # Model only the hash function here; actual five files are pinned by the
        # offline runner and runtime seal before any API probe is permitted.
        fn.__globals__['digest']=lambda path:expected
        assert fn(file)==(artifact,evidence)
        rejects(lambda:fn(jar('renamed-'+name)))
        jar(name,[(f'META-INF/maven/mock/{artifact}/pom.properties',f'artifactId={artifact}\nversion='+name[len(artifact)+1:-4]+'\n')])
        rejects(lambda:fn(file))
        fn.__globals__['digest']=original_digest
    assert len(s['MAVEN_AUXILIARY_IDENTITIES'])==5
`);
});

test('cold-cache provisioning is limited to two owner resolutions on hosted CI', () => {
  python(String.raw`
from pathlib import Path
config={'maven':'/runtime/bin/mvn','repository':'/cache'}; sandbox=Path('/fixture')
for variant in ('baseline','candidate'):
    c=s['preparation_command'](config,sandbox,variant,True)
    assert c.count('org.apache.maven.plugins:maven-dependency-plugin:3.11.0:resolve-plugins')==1
    assert '-DincludeArtifactIds=jacoco-maven-plugin,maven-antrun-plugin' in c
    assert '--strict-checksums' in c and '-o' not in c
    assert c[c.index('-s')+1]==c[c.index('-gs')+1]=='/fixture/central-settings.xml'
    assert c[c.index('-t')+1]==c[c.index('-gt')+1]=='/fixture/empty-toolchains.xml'
    assert not any(x in c for x in s['GOALS'])
    rejects(lambda:s['preparation_command'](config,sandbox,variant,False))
rejects(lambda:s['preparation_command'](config,sandbox,'arbitrary',True))
assert '<mirrorOf>*</mirrorOf>' in s['CENTRAL_SETTINGS']
assert 'https://repo.maven.apache.org/maven2' in s['CENTRAL_SETTINGS']
assert s['PORTABLE_PLAN']['prepareCommandsMaximum']==2
assert s['PORTABLE_PLAN']['localAlwaysOffline'] is True
`);
});

test('transport boundary registers both private Plexus compatibility gates once', () => {
  const boundary = readFileSync(new URL('./m6-ai-transport-review-boundary.test.mjs', import.meta.url), 'utf8');
  for (const name of ['jacoco-antrun-compatibility-harness.test.mjs', 'jacoco-antrun-plugin-live-compatibility.test.mjs']) {
    assert.equal(boundary.split(`import './${name}';`).length - 1, 1);
  }
});

test('hosted CI explicitly selects cold-cache preparation and ordinary local imports stay inert', () => {
  const url = new URL('./jacoco-antrun-plugin-live-compatibility.test.mjs', import.meta.url);
  const source = readFileSync(url, 'utf8').replace(/^import .+;\n/gm, '').replaceAll('import.meta.url', JSON.stringify(url.href));
  for (const env of [{}, { GITHUB_ACTIONS: 'true' }, { JACOCO_ANTRUN_COMPATIBILITY: 'true', JACOCO_ANTRUN_COMPATIBILITY_SEAL: '/reviewed/seal.json' }]) {
    const tests = []; const calls = [];
    const receipt = { status: 'PASSED', inputSealsUnchanged: true, scannerEvidence: false, productionAntRunActivationClaim: false,
      apiReplay: { missingMemberNegativeControl: 'PASSED', variants: { 'jacoco-baseline': {}, 'antrun-baseline': {}, candidate: {} } },
      variants: Object.fromEntries(['baseline', 'candidate'].map(variant => [variant, {
        assertionJvmTests: 3, report: { counters: { BRANCH: { covered: 2, missed: 0 } } },
        merge: { 'merge-defaults': { inputNames: variant === 'baseline' ? ['normal.exec'] : ['normal.exec', '.cvsignore', '.gitignore', '.gitattributes', '.hgignore'] },
          'merge-explicit-excludes': { inputNames: ['normal.exec'] } }, negativeControls: { invalidExec: 'PASSED', antFailureReader: 'PASSED' },
      }])), commands: [{ status: 'EXITED', processGroupCleaned: true, captureComplete: true }] };
    const adapter = { status: 'PASSED', cachePreparation: { status: 'VERIFIED', acceptanceGoal: false,
      resolutionCommandsRun: 2, existingArtifactHashesUnchanged: true, artifacts: Object.fromEntries(Array.from({ length: 18 }, (_, i) => [i, {}])) } };
    const context = vm.createContext({ assert, URL, fileURLToPath, process: { env }, console: { log() {} },
      test(name, options, fn) { tests.push({ name, options, fn }); },
      spawnSync(...args) { calls.push(args); return { status: 0, stdout: 'JAC_ANTRUN_RESULT ' + JSON.stringify(receipt)
        + '\nJAC_ANTRUN_PORTABLE_RESULT ' + JSON.stringify(adapter) + '\n', stderr: '' }; } });
    vm.runInContext(source, context, { timeout: 1000 });
    assert.equal(tests.length, 1);
    if (!Object.keys(env).length) { assert.notEqual(tests[0].options.skip, false); assert.equal(calls.length, 0); continue; }
    assert.equal(tests[0].options.skip, false); tests[0].fn(); assert.equal(calls.length, 1);
    assert.equal(calls[0][2].env.JACOCO_ANTRUN_COMPATIBILITY, 'true');
    if (env.GITHUB_ACTIONS === 'true') {
      assert.ok(calls[0][1].includes('--portable')); assert.equal(calls[0][2].timeout, 720000);
    } else {
      assert.ok(calls[0][1].includes('--execute-seal')); assert.ok(calls[0][1].includes('/reviewed/seal.json'));
      assert.equal(calls[0][2].timeout, 420000);
    }
  }
});
