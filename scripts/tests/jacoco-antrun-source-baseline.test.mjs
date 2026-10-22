import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { retainedJacocoAntRunSource } from './fixtures/jacoco-antrun-retained-source.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const adapter = path.join(root, 'scripts/ci/jacoco_antrun_contract.py');
const retained = retainedJacocoAntRunSource(root);
const current = retained.currentDirectory ?? root;
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const python = args => spawnSync('/usr/bin/python3', ['-I', '-S', '-B', ...args], {
  encoding: 'utf8', timeout: 20000, maxBuffer: 32 * 1024 * 1024,
  env: { PATH: '/usr/bin:/bin', LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8', TZ: 'UTC' },
});
function temporary(t) {
  const directory = mkdtempSync(path.join(tmpdir(), 'jacoco-antrun-guard-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  return directory;
}
function rejected(result, message = /ValueError:|ParseError:/) {
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, message);
}

test('pair source view restores accepted utilities bytes and leaves all historical contracts unchanged', () => {
  assert.ok(retained.projection);
  assert.equal(retained.jarProjection.suppliedSourceRoot, path.resolve(root));
  assert.equal(retained.jarProjection.suppliedRootPomSha256, '7aaef759cbdebaafde2383186cbb90861c23f51bd4fe3219614ed0fa8c22a9ab');
  assert.equal(retained.jarProjection.schemaVersion, 'RESOURCES_FLATTEN_RETAINED_ASSEMBLY_SOURCE_VIEW_V1');
  assert.equal(retained.jarProjection.sourceFileCount, 53);
  assert.equal(retained.jarProjection.suppliedSdkPomSha256, '2d933d9c89c7a0ee99b4870091a880ac086b295501914297c9bb6a21dd751078');
  assert.equal(retained.jarProjection.retainedSdkPomSha256, 'e755e3a2abd6d2df15b7e5739b10cd81f9a59476d8c21e6dc231961145b2d701');
  assert.deepEqual(Object.keys(retained.jarProjection.suppliedSourceHashes).filter(name =>
    retained.jarProjection.suppliedSourceHashes[name] !== retained.jarProjection.retainedSourceHashes[name]).sort(),
  ['integrations/host-sdk/pom.xml', 'pom.xml']);
  assert.equal(retained.jarProjection.retainedSourceRoot, current);
  const p = retained.projection;
  assert.deepEqual(Object.keys(p.suppliedSourceHashes).filter(name =>
    p.suppliedSourceHashes[name] !== p.retainedSourceHashes[name]), ['pom.xml']);
  assert.equal(sha(readFileSync(path.join(retained.directory, 'pom.xml'))),
    '2f22ec8cae77cdfb519b81a8e48417fc75632416bcf28989f589f33d39261579');
  for (const [name, hash] of Object.entries(p.retainedSourceHashes))
    assert.equal(sha(readFileSync(path.join(retained.directory, name))), hash, name);
  const checked = python([adapter, `--root=${current}`]);
  assert.equal(checked.status, 0, checked.stderr);
  const result = JSON.parse(checked.stdout);
  assert.equal(result.sourcePomCount, 28);
  assert.equal(result.canonicalCiEvidence, false);
  assert.equal(result.compatibilityAcceptanceClaimed, false);
});

for (const [name, file, mutate] of [
  ['AntRun lifecycle activation', 'pom.xml', s => s.replace('<version>3.1.0</version>', '<version>3.1.0</version><executions><execution><goals><goal>run</goal></goals></execution></executions>')],
  ['changed JaCoCo version', 'pom.xml', s => s.replace('<jacoco.version>0.8.15', '<jacoco.version>0.8.14')],
  ['changed JaCoCo execution', 'pom.xml', s => s.replace('<goal>prepare-agent</goal>', '<goal>prepare-agent-integration</goal>')],
  ['changed candidate Plexus', 'pom.xml', s => s.replaceAll('<version>3.6.1</version>', '<version>3.5.1</version>')],
  ['project dependency escape', 'pom.xml', s => s.replace('</project>', '<dependencies><dependency><groupId>org.codehaus.plexus</groupId><artifactId>plexus-utils</artifactId><version>3.6.1</version></dependency></dependencies></project>')],
  ['unrelated Boot change', 'pom.xml', s => s.replace('<version>5.6.3</version>', '<version>5.6.2</version>')],
  ['module override', 'apps/server/pom.xml', s => s + '\n'],
  ['overlay drift', 'integrations/ruoyi6-host-starter/overlay/ruoyi-extend/ruoyi-approval-host-starter/pom.xml', s => s + '\n'],
  ['historical utilities rewrite', 'scripts/ci/boot_utilities_contract.py', s => s + '\n'],
]) test(`pair source rejects ${name}`, t => {
  const directory = temporary(t);
  for (const name of Object.keys(retained.jarProjection?.retainedSourceHashes ?? retained.projection.suppliedSourceHashes)) {
    const destination = path.join(directory, name); mkdirSync(path.dirname(destination), { recursive: true });
    copyFileSync(path.join(current, name), destination);
  }
  const pristine = python([adapter, `--root=${directory}`]);
  assert.equal(pristine.status, 0, `Copied historical source must pass before mutation: ${pristine.stderr}`);
  const target = path.join(directory, file), before = readFileSync(target, 'utf8'), after = mutate(before);
  assert.notEqual(after, before); writeFileSync(target, after);
  rejected(python([adapter, `--root=${directory}`]), file === 'scripts/ci/boot_utilities_contract.py'
    ? /ValueError: Pair source bytes drift: scripts\/ci\/boot_utilities_contract\.py/
    : new RegExp('ValueError: Assembly source bytes drift: ' + file.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
});

const generate = String.raw`
import copy,json,runpy,sys
from pathlib import Path
import xml.etree.ElementTree as ET
current,prior=Path(sys.argv[1]),Path(sys.argv[2]);ns={'m':'http://maven.apache.org/POM/4.0.0'};p='{'+ns['m']+'}'
contract=runpy.run_path(str(current/'scripts/ci/jacoco_antrun_contract.py'))
clean=runpy.run_path(str(current/'scripts/ci/clean_plugin_contract.py'))
result={}
for label,source in [('before',prior),('after',current)]:
    raw=(source/'pom.xml').read_bytes()
    if b'<artifactId>maven-assembly-plugin</artifactId>' in raw:
        # This is the historical pair model fixture; validate and reverse only
        # the separately tested Assembly addition before generating its models.
        assembly=runpy.run_path(str(current/'scripts/ci/assembly_plexus_contract.py'))
        assembly['verify_source'](source);raw=assembly['reverse_source'](raw)
    template=ET.fromstring(raw);props=template.find('m:properties',ns)
    pins={n.tag.removeprefix(p):n.text for n in props}
    if label=='before':
        managed=template.find('m:build/m:pluginManagement/m:plugins',ns)
        ant=contract['fixture_plugins'](current,True)['antrun']
        index=next(i+1 for i,x in enumerate(managed) if x.findtext('m:artifactId',namespaces=ns)=='maven-dependency-plugin')
        managed.insert(index,ant)
    document=ET.Element(p+'projects')
    for artifact in clean['REACTOR'].values():
        model=ET.SubElement(document,p+'project')
        for key,value in [('groupId',clean['PROJECT_GROUP']),('artifactId',artifact),('version','0.1.0-SNAPSHOT')]:ET.SubElement(model,p+key).text=value
        model.append(copy.deepcopy(props));model.append(copy.deepcopy(template.find('m:dependencyManagement',ns)))
        build=copy.deepcopy(template.find('m:build',ns));model.append(build)
        if label=='after':
            jacoco=next(x for x in build.findall('m:plugins/m:plugin',ns) if x.findtext('m:artifactId',namespaces=ns)=='jacoco-maven-plugin')
            ET.SubElement(jacoco.find('m:dependencies/m:dependency',ns),p+'scope').text='compile'
    raw=ET.tostring(document,encoding='unicode')
    for key,value in pins.items():raw=raw.replace('$'+'{'+key+'}',value)
    result[label]=raw
print(json.dumps(result))
`;
const generated = python(['-c', generate, current, retained.directory]);
assert.equal(generated.status, 0, generated.stderr);
const models = JSON.parse(generated.stdout);
function compare(t, candidate = models.after, baseline = models.before) {
  const directory = temporary(t), after = path.join(directory, 'candidate.xml'), before = path.join(directory, 'baseline.xml');
  writeFileSync(after, candidate); writeFileSync(before, baseline);
  return python([adapter, `--root=${current}`, `--effective-pom=${after}`,
    `--baseline-effective-pom=${before}`, `--baseline-root=${retained.directory}`]);
}
test('pair parser accepts only the synthetic 26-model two-owner delta without claiming actual execution', t => {
  const result = compare(t); assert.equal(result.status, 0, result.stderr);
  const receipt = JSON.parse(result.stdout);
  assert.equal(receipt.modelCount, 26); assert.equal(receipt.activeJacocoModelCount, 26);
  assert.equal(receipt.managedAntRunModelCount, 26); assert.equal(receipt.antRunExecutionCount, 0);
  assert.equal(receipt.allOtherModelFieldsUnchanged, true); assert.equal(receipt.canonicalCiEvidence, false);
});
const defaultAntRunGroup = text => text.replaceAll(
  '<ns0:groupId>org.apache.maven.plugins</ns0:groupId><ns0:artifactId>maven-antrun-plugin</ns0:artifactId>',
  '<ns0:artifactId>maven-antrun-plugin</ns0:artifactId>').replaceAll(
  /<ns0:groupId>org.apache.maven.plugins<\/ns0:groupId>(\s*<ns0:artifactId>maven-antrun-plugin<\/ns0:artifactId>)/g, '$1');
test('pair model parser preserves matching omitted Maven default groups only on managed AntRun', t => {
  const current = defaultAntRunGroup(models.after), prior = defaultAntRunGroup(models.before);
  assert.notEqual(current, models.after); assert.notEqual(prior, models.before);
  const result = compare(t, current, prior); assert.equal(result.status, 0, result.stderr);
});
test('pair model comparison does not erase absent versus explicit AntRun group differences', t => {
  rejected(compare(t, defaultAntRunGroup(models.after), models.before));
});
for (const [name, mutate] of [
  ['JaCoCo execution change', s => s.replace('>prepare-agent<', '>prepare-agent-integration<')],
  ['JaCoCo plugin version change', s => s.replace('>0.8.15<', '>0.8.14<')],
  ['private Plexus drift', s => s.replaceAll('>3.6.1<', '>3.5.1<')],
  ['unrelated runtime BOM drift', s => s.replace('>4.0.8<', '>4.0.9<')],
  ['unexpected dependency scope', s => s.replace('>compile<', '>runtime<')],
  ['AntRun activation', s => s.replace('<ns0:artifactId>maven-antrun-plugin</ns0:artifactId>', '<ns0:artifactId>maven-antrun-plugin</ns0:artifactId><ns0:executions><ns0:execution><ns0:goals><ns0:goal>run</ns0:goal></ns0:goals></ns0:execution></ns0:executions>')],
  ['missing model', s => s.replace(/<ns0:project>[\s\S]*?<\/ns0:project>/, '')],
  ['missing JaCoCo group', s => s.replace('<ns0:groupId>org.jacoco</ns0:groupId>', '')],
  ['missing Plexus dependency group', s => s.replace('<ns0:groupId>org.codehaus.plexus</ns0:groupId>', '')],
  ['foreign AntRun group', s => s.replace(/(<ns0:groupId>)org.apache.maven.plugins(<\/ns0:groupId>\s*<ns0:artifactId>maven-antrun-plugin<\/ns0:artifactId>)/, '$1foreign.plugins$2')],
  ['empty AntRun group', s => s.replace(/(<ns0:groupId>)org.apache.maven.plugins(<\/ns0:groupId>\s*<ns0:artifactId>maven-antrun-plugin<\/ns0:artifactId>)/, '$1$2')],
  ['duplicate AntRun group', s => s.replace(/(<ns0:groupId>org.apache.maven.plugins<\/ns0:groupId>)(\s*<ns0:artifactId>maven-antrun-plugin<\/ns0:artifactId>)/, '$1$1$2')],
]) test(`pair model comparison rejects ${name}`, t => {
  const altered = mutate(models.after); assert.notEqual(altered, models.after);
  rejected(compare(t, altered));
});
