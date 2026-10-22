import assert from 'node:assert/strict';
import { lstatSync, readFileSync, realpathSync } from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = fileURLToPath(new URL('../../', import.meta.url));
const runner = path.join(root, 'scripts/tests/fixtures/resources_flatten_models.py');
const ci = process.env.GITHUB_ACTIONS === 'true';
const enabled = ci || process.env.RESOURCES_FLATTEN_MODEL_COMPATIBILITY === 'true';
const marker = 'RESOURCES_FLATTEN_MODELS_RESULT';
const labels = ['baseline-normal', 'candidate-normal', 'baseline-demo', 'candidate-demo'];
const maximumReceiptBytes = 8 * 1024 * 1024;

const prelude = String.raw`
import ast,base64,copy,gzip,hashlib,json,os,runpy,shutil,socket,subprocess,sys,tempfile,zlib
from pathlib import Path
from unittest.mock import patch
import xml.etree.ElementTree as ET
def no_process(event,args):
    if event in ('subprocess.Popen','os.system','os.exec','os.posix_spawn','socket.connect','socket.bind','socket.getaddrinfo'):
        raise AssertionError('No process or network allowed in pure model verification: '+event)
sys.addaudithook(no_process)
s=runpy.run_path(sys.argv[1]); root=Path(sys.argv[2]); c=s['contract']()
def rejected(fn):
    try: fn()
    except (ValueError,KeyError,TypeError,AssertionError,ET.ParseError): return
    raise AssertionError('Mutation unexpectedly accepted')
def candidate(directory):
    target=directory/'candidate'; target.mkdir()
    files,_=c['anchors'](root)
    for name in set(files)|set(c['ARCHIVES'].values())|{c['MANIFEST'],c['ADDITIONS']}:
        output=target/name;output.parent.mkdir(parents=True,exist_ok=True)
        output.write_bytes((root/name).read_bytes())
    return target
`;

function python(body, seconds = 180) {
  const result = spawnSync('/usr/bin/python3', ['-I', '-S', '-B', '-c', prelude + body, runner, root], {
    cwd: root, encoding: 'utf8', timeout: seconds * 1000, maxBuffer: 2 * 1024 * 1024,
    env: { PATH: '/usr/bin:/bin', LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8', TZ: 'UTC' },
  });
  assert.equal(result.status, 0, `${result.error?.message || ''}\n${result.stderr || ''}\n${result.stdout || ''}`);
  return result.stdout;
}

test('Resources/Flatten default plan is inert and fixes one version plus four model commands', () => {
  python(String.raw`
p=s['PLAN']
assert p['execution']=='NOT_RUN' and p['actualModelCommands']==4 and p['versionCommands']==1 and p['totalCommands']==5
assert p['modelCountPerCommand']==26 and p['profiles']==['normal','demo']
assert p['goal']=='org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom'
assert p['aggregateTimeoutSeconds']==900 and p['outerTimeoutSeconds']==930
assert p['versionCommandTimeoutSeconds']==30 and p['modelCommandTimeoutSeconds']==300
assert p['sharedDeadlineIncludesPreparationAndVersion'] is True
assert p['localAlwaysOffline'] is True and p['lifecycle'] is False
with patch.object(sys,'argv',[sys.argv[1],'--plan']): assert s['main']()==0
with patch.dict(s['execute'].__globals__['os'].environ,{},clear=True):
    rejected(lambda:s['execute'](None))
`);
});

test('Resources/Flatten prepares exactly32 old source files and rereads34 actual current inputs', () => {
  python(String.raw`
with tempfile.TemporaryDirectory(prefix='rf-source-contract-') as value:
    directory=Path(value); source=candidate(directory)
    before=c['source_state'](source)
    assert before['checkedFileCount']==34 and len(before['sourceHashes'])==34 and before['sourceBytes']==136026
    materialized=c['materialize_baseline'](source,directory/'baseline')
    baseline=Path(materialized['retainedSourceRoot']); pair=c['source_pair'](baseline,source)
    assert pair==materialized['sourcePair']==c['source_pair'](baseline,source)
    assert c['source_state'](source)==before
    assert materialized['materializedFileCount']==32 and len([p for p in baseline.rglob('*') if p.is_file()])==32
    assert set(pair['sourceHashes']['baseline'])==set(c['anchors'](source)[0])
    assert len(pair['sourceHashes']['candidate'])==34 and pair['sourceBytes']['baseline']==111235
    assert pair['fullAcceptedSourceView'] is False and pair['historicalValidation']=='NOT_RUN'
    for name,archive in c['ARCHIVES'].items():
        assert (baseline/name).read_bytes()==(source/archive).read_bytes()
    for name in ['pom.xml','integrations/host-sdk/pom.xml',*c['ARCHIVES'].values(),c['PARSER'],c['REACTOR'],c['RULES'],c['WITNESS']]:
        file=source/name;old=file.read_bytes();file.write_bytes(old+b' ')
        rejected(lambda:c['source_pair'](baseline,source));file.write_bytes(old)
    for name in c['NEW']:
        file=source/name;old=file.read_bytes();file.write_bytes((source/c['ARCHIVES'][name]).read_bytes())
        rejected(lambda:c['source_state'](source));file.write_bytes(old)
    for name in set(pair['sourceHashes']['baseline'])-set(c['NEW'])-set(c['AUXILIARY']):
        file=source/name;old=file.read_bytes();file.write_bytes(old+b' ')
        rejected(lambda:c['source_pair'](baseline,source));file.write_bytes(old)
    sdk=source/'integrations/host-sdk/pom.xml';old=sdk.read_bytes();sdk.unlink()
    rejected(lambda:c['source_state'](source));sdk.write_bytes(old)
    sdk.unlink();sdk.symlink_to(baseline/'integrations/host-sdk/pom.xml')
    rejected(lambda:c['source_state'](source));sdk.unlink();sdk.write_bytes(old)
    conf=source/'integrations/.mvn';conf.symlink_to(directory/'missing')
    rejected(lambda:c['source_pair'](baseline,source));conf.unlink()
    archive=source/c['ARCHIVES']['pom.xml'];old=archive.read_bytes();archive.write_bytes(old+b' ')
    manifest=source/c['MANIFEST'];saved=manifest.read_bytes();data=json.loads(saved)
    data['files']['pom.xml']['sha256']=hashlib.sha256(archive.read_bytes()).hexdigest()
    manifest.write_text(json.dumps(data));rejected(lambda:c['source_state'](source))
    manifest.write_bytes(saved);archive.write_bytes(old)
    (baseline/'unreviewed.txt').write_text('extra')
    rejected(lambda:c['baseline_membership'](baseline,pair['sourceHashes']['baseline']))
    assert c['source_state'](source)==before
`);
});

test('Resources/Flatten seals the finite helper closure before and after its first import', () => {
  python(String.raw`
from types import SimpleNamespace
with tempfile.TemporaryDirectory(prefix='rf-helper-import-') as value:
    source=Path(value)
    for name in c['PYTHON_HELPERS']:
        file=source/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_bytes((root/name).read_bytes())
    before=c['python_helper_state'](source)
    assert len(before)==9 and all(Path(name).is_absolute() for name in before)
    changed=source/'scripts/tests/fixtures/run_boot_http_compatibility.py'
    def fake_import(name):
        if name=='checkstyle_live_support':changed.write_bytes(changed.read_bytes()+b' ')
        return SimpleNamespace(__file__=str(source/s['SHARED_MODULES'][name]))
    with patch.dict(s['execution_helpers'].__globals__,{'ROOT':source,'HERE':source/'scripts/tests/fixtures'}), \
         patch.object(s['execution_helpers'].__globals__['importlib'],'import_module',fake_import):
        rejected(lambda:s['execution_helpers']())
    rejected(lambda:c['python_helper_state'](source))
`);
});

test('Resources/Flatten model preparation uses exact offline or Central settings without a process', () => {
  python(String.raw`
import time
helpers=s['execution_helpers']();runtime=helpers['run_boot_live_compatibility'];shared=helpers['checkstyle_live_support']
class NoCommands:
    def __init__(self,sandbox,environment,seconds):self.environment=environment;self.results=[];self.seconds=seconds
    def run(self,*args,**kwargs):raise AssertionError('Unexpected JVM/process in preparation')
with tempfile.TemporaryDirectory(prefix='rf-model-prepare-') as value:
    directory=Path(value);repository=directory/'repository';repository.mkdir()
    jdk=directory/'jdk';(jdk/'bin').mkdir(parents=True);(jdk/'bin/java').write_bytes(b'not executed')
    maven=directory/'maven/bin/mvn';maven.parent.mkdir(parents=True);maven.write_bytes(b'not executed')
    for offline in (True,False):
        sandbox=directory/('offline' if offline else 'central');sandbox.mkdir();deadline=time.monotonic()+900
        with patch.object(shared,'Commands',NoCommands), \
             patch.object(runtime,'python_origins',lambda:({},[])), \
             patch.object(runtime,'runtime_files',lambda *args:[]), \
             patch.object(runtime,'cache_snapshot',lambda *args:{}):
            m=s['ModelRun'](root,sandbox,repository,jdk,maven,offline,deadline,helpers)
        assert m.commands.results==[] and m.commands.deadline==deadline and 0<m.commands.seconds<=900
        assert 'HOME' not in m.commands.environment and 'MAVEN_ARGS' not in m.commands.environment
        expected=b'<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"/>\n' if offline else shared.CENTRAL_SETTINGS.encode()
        assert (sandbox/'empty-settings.xml').read_bytes()==expected
        assert m.report['fixedSettingsHashes']['empty-settings.xml']==hashlib.sha256(expected).hexdigest()
        assert m.report['settingsPolicy']==('OFFLINE_EMPTY_SETTINGS' if offline else 'CENTRAL_MIRROR_ONLY')
        if not offline:assert b'<mirrorOf>*</mirrorOf>' in expected and b'https://repo.maven.apache.org/maven2' in expected
        assert m.report['helperImportHashesBefore']==m.report['helperImportHashesAfter']
        assert m.report['sourcePairBefore']['candidateCheckedFileCount']==34
`);
});

test('Resources/Flatten binds a mock version plus four model commands to exact roots and outputs', () => {
  python(String.raw`
with tempfile.TemporaryDirectory(prefix='rf-current-capture-mock-') as value:
    directory=Path(value);sandbox=directory/'output';sandbox.mkdir()
    materialized=c['materialize_baseline'](root,directory/'baseline');pair=materialized['sourcePair']
    paths=s['model_paths'](sandbox);maven=directory/'maven/bin/mvn';repository=directory/'repository'
    config={'maven':str(maven),'repository':str(repository)}
    common={'status':'EXITED','exitStatus':0,'captureComplete':True,'processGroupCleaned':True,'cwd':str(sandbox)}
    commands=[dict(common,case='resources-flatten-model-maven-version',command=[str(maven),'--version'])]
    hashes={}
    for label in s['LABELS']:
        variant,profile=label.split('-');model_root=Path(pair['sourceRoots'][variant]);output=paths[label]
        raw=('<projects><!--mock '+label+'--></projects>').encode();output.write_bytes(raw)
        hashes[label]=hashlib.sha256(raw).hexdigest()
        argv=[str(maven),'--strict-checksums','-t',str(sandbox/'empty-toolchains.xml'),
              '-gt',str(sandbox/'empty-toolchains.xml'),'-o','-B','-ntp',
              '-s',str(sandbox/'empty-settings.xml'),'-gs',str(sandbox/'empty-settings.xml'),
              '-Dmaven.repo.local='+str(repository),'-Dstyle.color=never',
              '-Dmaven.multiModuleProjectDirectory='+str(model_root),'-f',str(model_root/'pom.xml'),
              *(['-Pproduct-readiness-demo'] if profile=='demo' else []),
              'org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom','-Doutput='+str(output)]
        commands.append(dict(common,case=label+'-models',command=argv,mavenBasedir=str(model_root),
                             modelOutput={'name':output.name,'sha256':hashes[label],'bytes':len(raw)}))
    receipt={'actualModelSourceRoots':pair['sourceRoots'],'sourcePairBefore':pair,'sourcePairAfter':pair,
             'actualModelCommandCount':4,'totalCommandCount':5,'toolchain':{'maven':'3.9.16','javaMajor':21},
             'config':config,'sandbox':str(sandbox),'offline':True,'commands':commands,'effectiveModelHashes':hashes}
    result=s['verify_current_capture'](receipt,pair,paths)
    assert result['modelSourceRoots']==pair['sourceRoots'] and result['outputHashes']==hashes
    assert result['actualModelCommands']==4 and result['archivedModelEvidenceReused'] is False
    missing=copy.deepcopy(receipt);missing['commands'].pop()
    rejected(lambda:s['verify_current_capture'](missing,pair,paths))
    rebound=copy.deepcopy(receipt);rebound['commands'][1]['mavenBasedir']=pair['sourceRoots']['candidate']
    rejected(lambda:s['verify_current_capture'](rebound,pair,paths))
    rebound=copy.deepcopy(receipt);row=rebound['commands'][1]
    row['command'][row['command'].index('-f')+1]=str(root/'pom.xml')
    rejected(lambda:s['verify_current_capture'](rebound,pair,paths))
    rejected(lambda:s['verify_current_capture'](receipt,pair,dict(paths,**{'baseline-normal':paths['candidate-normal']})))
`);
});

test('Resources/Flatten preserves the first execution failure when storage cleanup also fails', () => {
  python(String.raw`
first=ValueError('first execution failure')
class BadCleanup:
    def start(self):pass
    def stop(self):raise RuntimeError('secondary cleanup failure')
    def report(self):return {'failure':None}
m=s['ModelRun'].__new__(s['ModelRun']);m.storage=BadCleanup();m.report={};m.maven=Path('/verified/bin/mvn')
def fail(*args,**kwargs):raise first
m.run=fail
try:m.execute()
except ValueError as failure:assert failure is first
else:raise AssertionError('Primary failure lost')
assert m.report['status']=='FAILED' and m.report['error']=='first execution failure'
assert m.report['cleanupError']==[{'operation':'storage.stop','errorType':'RuntimeError','error':'secondary cleanup failure'}]
`);
});

test('Resources/Flatten rechecks all four original model documents against source-bound archived roots', { timeout: 240000 }, () => {
  python(String.raw`
with tempfile.TemporaryDirectory(prefix='rf-archived-models-') as value:
    directory=Path(value);source=candidate(directory)
    projection=c['materialize_baseline'](source,directory/'baseline');baseline=Path(projection['retainedSourceRoot'])
    evidence=root/s['EVIDENCE'];paths=s['model_paths'](evidence)
    bound=s['verify_archived_capture'](evidence,projection['sourcePair'],paths)
    assert bound['modelSourceRoots']['baseline']!=str(baseline) and bound['modelSourceRoots']['candidate']!=str(source)
    result=c['verify_models'](baseline,source,paths,model_source_roots=bound['modelSourceRoots'])
    assert result['nonReactorOverlaysSourceBoundOnly']==2
    for profile,active in [('normal',1),('demo',26)]:
        row=result['models'][profile];counts=row['ownerDeclarationCounts']
        assert row['modelCount']==26 and row['allOtherModelFieldsUnchanged'] is True
        assert counts['project/build/pluginManagement/plugins:maven-resources-plugin']==26
        assert counts['project/build/plugins:flatten-maven-plugin']==active
    assert s['verify_archived_capture'](evidence,c['source_pair'](baseline,source),paths)==bound
    rejected(lambda:c['checked_model_roots']({'baseline':'/','candidate':'/tmp/candidate'}))
    rejected(lambda:c['checked_model_roots']({'baseline':'/tmp/parent','candidate':'/tmp/parent/child'}))
    changed=directory/'changed.xml';changed.write_bytes(paths['candidate-normal'].read_bytes()+b' ')
    rejected(lambda:s['verify_archived_capture'](evidence,projection['sourcePair'],dict(paths,**{'candidate-normal':changed})))
`);
});

test('Resources/Flatten semantic mutations exercise the projection independently of archive digest rejection', { timeout: 240000 }, () => {
  python(String.raw`
with tempfile.TemporaryDirectory(prefix='rf-model-mutations-') as value:
    directory=Path(value);source=candidate(directory)
    materialized=c['materialize_baseline'](source,directory/'baseline');baseline=Path(materialized['retainedSourceRoot'])
    original=s['model_paths'](root/s['EVIDENCE'])
    bound=s['verify_archived_capture'](root/s['EVIDENCE'],materialized['sourcePair'],original)
    ns=c['NS'];P=c['P']
    def plugin(project,artifact,managed=False):
        location='m:build/m:pluginManagement/m:plugins/m:plugin' if managed else 'm:build/m:plugins/m:plugin'
        return next(p for p in project.findall(location,ns) if p.findtext('m:artifactId',namespaces=ns)==artifact)
    def mutate(case):
        label='candidate-demo' if case=='demo-sdk-output' else 'candidate-normal'
        document=ET.fromstring(original[label].read_bytes());project=list(document)[0]
        sdk=next(p for p in document if p.findtext('m:artifactId',namespaces=ns)=='approval-host-sdk')
        resources=plugin(project,'maven-resources-plugin',True)
        dependency=resources.find('m:dependencies/m:dependency',ns)
        if case=='global-project-dependency':
            dependencies=project.find('m:dependencies',ns)
            if dependencies is None:dependencies=ET.SubElement(project,P+'dependencies')
            item=ET.SubElement(dependencies,P+'dependency')
            for key,value in [('groupId','unapproved'),('artifactId','injected'),('version','1')]: ET.SubElement(item,P+key).text=value
        elif case=='managed-execution':ET.SubElement(resources,P+'executions')
        elif case=='wrong-utils':dependency.find('m:version',ns).text='4.0.2'
        elif case=='duplicate-utils':resources.find('m:dependencies',ns).append(copy.deepcopy(dependency))
        elif case=='sdk-override-missing':
            owner=plugin(sdk,'flatten-maven-plugin');owner.remove(owner.find('m:dependencies',ns))
        elif case=='demo-sdk-output':sdk.find('m:build/m:directory',ns).text='/unapproved/output'
        elif case=='membership-loss':document.remove(sdk)
        else:raise AssertionError(case)
        file=directory/(case+'.xml');file.write_bytes(ET.tostring(document,encoding='utf-8'))
        paths=dict(original);paths[label]=file
        rejected(lambda:c['verify_models'](baseline,source,paths,model_source_roots=bound['modelSourceRoots']))
    for case in ['global-project-dependency','managed-execution','wrong-utils','duplicate-utils',
                 'sdk-override-missing','demo-sdk-output','membership-loss']:mutate(case)
    parser,reactor=c['helpers'](baseline)
    invalid=directory/'invalid.xml';invalid.write_bytes(b'<!DOCTYPE projects><projects/>')
    rejected(lambda:c['bounded_models'](invalid,parser,reactor))
    invalid.write_bytes(b'x'*(16*1024*1024+1));rejected(lambda:c['bounded_models'](invalid,parser,reactor))
`);
});

test('Resources/Flatten retention roundtrip preserves four labelled source-bound XML byte streams', { timeout: 240000 }, () => {
  console.log(python(String.raw`
paths=s['model_paths'](root/s['EVIDENCE']);rows=s['pack_outputs'](paths,s['ARCHIVED_MODELS'])
raw=s['unpack_outputs'](rows,s['ARCHIVED_MODELS'])
with tempfile.TemporaryDirectory(prefix='rf-retention-source-') as value:
    materialized=c['materialize_baseline'](root,Path(value)/'baseline')
    bound=s['verify_archived_capture'](root/s['EVIDENCE'],materialized['sourcePair'],paths)
assert set(raw)==set(s['LABELS'])
assert sum(len(v) for v in raw.values())<=64*1024*1024
assert sum(v['compressedBytes'] for v in rows.values())<=4*1024*1024
for label in s['LABELS']:
    variant,profile=label.split('-');row=rows[label]
    assert row['label']==label and row['variant']==variant and row['profile']==profile
    assert row['name']==s['MODEL_NAMES'][label] and raw[label]==paths[label].read_bytes()
    assert row['sha256']==s['ARCHIVED_MODELS'][label] and row['rawBytes']==len(raw[label])
    assert bound['outputs'][label]=={'name':row['name'],'bytes':row['rawBytes'],'sha256':row['sha256']}
    assert row['compressedBytes']<=1024*1024
def rejected_rows(change):
    changed=copy.deepcopy(rows);change(changed)
    rejected(lambda:s['unpack_outputs'](changed,s['ARCHIVED_MODELS']))
rejected_rows(lambda r:r.pop('candidate-demo'))
rejected_rows(lambda r:r['baseline-normal'].update(label='candidate-normal'))
rejected_rows(lambda r:r['baseline-normal'].update(variant='candidate'))
rejected_rows(lambda r:r['baseline-normal'].update(profile='demo'))
rejected_rows(lambda r:r['baseline-normal'].update(rawBytes=0))
rejected_rows(lambda r:r['baseline-normal'].update(sha256='0'*64))
rejected_rows(lambda r:r['baseline-normal'].update(compressedBytes=1024*1024+1))
def replace_blob(rows,kind):
    row=rows['baseline-normal'];packed=base64.b64decode(row['data'])
    if kind=='second-member':packed+=gzip.compress(b'extra',mtime=0)
    elif kind=='trailing':packed+=b'x'
    elif kind=='truncated':packed=packed[:-1]
    elif kind=='bomb':packed=gzip.compress(b'x'*(16*1024*1024+1),mtime=0)
    row.update(data=base64.b64encode(packed).decode(),compressedBytes=len(packed),compressedSha256=hashlib.sha256(packed).hexdigest())
for kind in ('second-member','trailing','truncated','bomb'):
    rejected_rows(lambda rows:replace_blob(rows,kind))
assert len(json.dumps(rows).encode())<8*1024*1024
print('RESOURCES_FLATTEN_ARCHIVED_ROUNDTRIP '+json.dumps({
    'schemaVersion':'RESOURCES_FLATTEN_ARCHIVED_ROUNDTRIP_V1','status':'PASSED',
    'captureReceiptSha256':bound['captureReceiptSha256'],'collectorSha256':bound['collectorSha256'],
    'modelSourceRoots':bound['modelSourceRoots'],'actualCaptureReexecuted':False,
    'outputs':{label:{key:value for key,value in row.items() if key!='data'} for label,row in rows.items()},
    'rawTotalBytes':sum(row['rawBytes'] for row in rows.values()),
    'compressedTotalBytes':sum(row['compressedBytes'] for row in rows.values())},sort_keys=True))
`));
});

function retainReceipt(stdout) {
  const rows = stdout.split('\n').filter(line => line.startsWith(marker + ' '));
  assert.equal(rows.length, 1, 'one complete result pointer required');
  const pointer = JSON.parse(rows[0].slice(marker.length + 1));
  assert.match(pointer.path, /^\/[^\0\r\n]+\/result\.json$/);
  const stat = lstatSync(pointer.path);
  assert.ok(stat.isFile() && !stat.isSymbolicLink() && stat.size <= maximumReceiptBytes);
  assert.equal(realpathSync(pointer.path), pointer.path);
  const raw = readFileSync(pointer.path, 'utf8');
  assert.ok(Buffer.byteLength(raw) <= maximumReceiptBytes);
  const value = JSON.parse(raw); assert.equal(pointer.status, value.status);
  // Preserve the complete four compressed XML byte streams in the uploaded M6 log.
  console.log(marker + ' ' + raw.replace(/[\r\n\t]/g, ''));
  return value;
}

test('hosted or explicitly enabled Resources/Flatten compares four actual26-project models', {
  skip: enabled ? false : 'Local actual model execution needs explicit opt-in and an existing offline cache.',
  timeout: 960000,
}, () => {
  const repository = process.env.M6_PR_E_E2_MAVEN_REPOSITORY;
  const evidence = process.env.RESOURCES_FLATTEN_MODEL_EVIDENCE_DIRECTORY;
  if (!ci) assert.ok(repository, 'local model execution requires an explicit reviewed offline cache');
  const run = spawnSync('/usr/bin/timeout', ['--signal=TERM', '--kill-after=15s', '915s',
    '/usr/bin/python3', '-I', '-S', '-B', runner, '--execute', `--source=${root}`,
    ...(repository ? [`--repository=${repository}`] : []), ...(evidence ? [`--evidence-directory=${evidence}`] : [])], {
    cwd: root, encoding: 'utf8', timeout: 945000, maxBuffer: 12 * 1024 * 1024,
    env: { ...process.env, RESOURCES_FLATTEN_MODEL_COMPATIBILITY: 'true' },
  });
  // Retain FAILED receipts before an assertion aborts; paths alone do not survive hosted cleanup.
  const receipt = run.stdout?.includes(marker + ' ') ? retainReceipt(run.stdout) : null;
  if (run.stderr) console.error(run.stderr.trim());
  assert.equal(run.status, 0, `${run.error?.message || run.signal || ''}\n${run.stdout || ''}\n${run.stderr || ''}`);
  assert.ok(receipt, 'complete result including four model byte streams required');
  assert.equal(receipt.schemaVersion, 'RESOURCES_FLATTEN_ACTUAL_MODELS_V1');
  assert.equal(receipt.status, 'PASSED'); assert.equal(receipt.actualModelCommandCount, 4);
  assert.equal(receipt.totalCommandCount, 5); assert.equal(receipt.retainedModelCount, 4);
  assert.deepEqual(receipt.commands.map(row => row.case), ['resources-flatten-model-maven-version', ...labels.map(label => `${label}-models`)]);
  assert.equal(receipt.plan.aggregateTimeoutSeconds, 900); assert.equal(receipt.plan.outerTimeoutSeconds, 930);
  assert.equal(receipt.offline, !ci); assert.equal(receipt.sourcePomCount, 28);
  assert.equal(receipt.baselineMaterialization.materializedFileCount, 32);
  assert.equal(receipt.sourcePairAfter.candidateCheckedFileCount, 34);
  assert.deepEqual(receipt.sourcePairBefore, receipt.sourcePairAfter);
  assert.equal(receipt.nonReactorOverlaysSourceBoundOnly, 2);
  for (const name of ['sourceInputsStable', 'executionInputsStable', 'runtimeInputsStable', 'cacheExistingJarPomBytesStable', 'allOtherModelFieldsUnchanged']) {
    assert.equal(receipt[name], true, name);
  }
  for (const name of ['archivedModelEvidenceReused', 'scannerEvidence', 'canonicalCiEvidence', 'compatibility', 'lifecycle']) {
    assert.equal(receipt[name], false, name);
  }
  assert.deepEqual(Object.keys(receipt.modelOutputs).sort(), [...labels].sort());
  for (const label of labels) {
    const [variant, profile] = label.split('-'); const row = receipt.modelOutputs[label];
    assert.equal(row.label, label); assert.equal(row.variant, variant); assert.equal(row.profile, profile);
    assert.equal(row.name, `${label}-effective-pom.xml`);
    assert.equal(row.sha256, receipt.effectiveModelHashes[label]);
    assert.ok(row.rawBytes <= 16 * 1024 * 1024 && row.compressedBytes <= 1024 * 1024);
  }
  assert.ok(Object.values(receipt.modelOutputs).reduce((sum, row) => sum + row.rawBytes, 0) <= 64 * 1024 * 1024);
  assert.ok(Object.values(receipt.modelOutputs).reduce((sum, row) => sum + row.compressedBytes, 0) <= 4 * 1024 * 1024);
  for (const [profile, active] of [['normal', 1], ['demo', 26]]) {
    const model = receipt.models.models[profile];
    assert.equal(model.modelCount, 26); assert.equal(model.allOtherModelFieldsUnchanged, true);
    assert.equal(model.ownerDeclarationCounts['project/build/pluginManagement/plugins:maven-resources-plugin'], 26);
    assert.equal(model.ownerDeclarationCounts['project/build/plugins:flatten-maven-plugin'], active);
  }
});
