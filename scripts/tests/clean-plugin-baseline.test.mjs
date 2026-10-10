import assert from 'node:assert/strict';
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

// STATIC/SYNTHETIC CONTRACT TESTS ONLY. No Maven, Java, scanner or network work.
const root = fileURLToPath(new URL('../../', import.meta.url));
const checker = path.join(root, 'scripts/ci/verify-clean-plugin-baseline.py');
const releaseChecker = path.join(root, 'scripts/ci/verify-release-plugin-baseline.py');
const source = readFileSync(path.join(root, 'pom.xml'), 'utf8');
const inventory = spawnSync('git', ['ls-files', '-z', '--', 'pom.xml', '**/pom.xml'], { cwd: root, encoding: 'utf8' });
assert.equal(inventory.status, 0, inventory.stderr);
const sourcePoms = inventory.stdout.split('\0').filter(Boolean);
assert.equal(sourcePoms.length, 28, 'all 26 reactor POMs and both host overlays must be tested');
const cleanPattern = /<plugin>\s*(?:<groupId>org\.apache\.maven\.plugins<\/groupId>\s*)?<artifactId>maven-clean-plugin<\/artifactId>[\s\S]*?<\/plugin>/;
const releasePattern = /<plugin>\s*(?:<groupId>org\.apache\.maven\.plugins<\/groupId>\s*)?<artifactId>maven-release-plugin<\/artifactId>[\s\S]*?<\/plugin>/;
const clean = source.match(cleanPattern)?.[0];
assert.ok(clean, 'candidate Clean source declaration must exist');
const io = clean.match(/<dependency>[\s\S]*?<\/dependency>/)[0];
const execution = '<executions><execution><id>default-clean</id><phase>clean</phase><goals><goal>clean</goal></goals></execution></executions>';
const python = (args, options = {}) => spawnSync('python3', ['-B', ...args], { encoding: 'utf8', ...options });
const check = (directory = root, options = [], script = checker) => python([script, `--root=${directory}`, ...options]);
function temporary(t, prefix) {
  const directory = mkdtempSync(path.join(tmpdir(), prefix));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  return directory;
}
function sourceFixture(t, mutate, fileMutation) {
  const directory = temporary(t, 'clean-source-');
  for (const relative of sourcePoms) {
    const file = path.join(directory, relative);
    mkdirSync(path.dirname(file), { recursive: true });
    copyFileSync(path.join(root, relative), file);
  }
  const changed = mutate(source);
  if (!fileMutation) assert.notEqual(changed, source, 'mutation must change the source');
  writeFileSync(path.join(directory, 'pom.xml'), changed);
  if (fileMutation) fileMutation(directory);
  return directory;
}
function rejected(result) {
  assert.notEqual(result.status, 0, result.stdout);
  assert.match(result.stderr, /ValueError:|ParseError:/);
}
function sourceRejected(t, mutate, fileMutation) {
  const directory = sourceFixture(t, mutate, fileMutation);
  // The original Release gate must independently refuse the same invalid IO allowance.
  for (const script of [checker, releaseChecker]) rejected(check(directory, [], script));
}
const changeClean = (pom, mutate) => pom.replace(clean, mutate(clean));
const changeIo = (pom, mutate) => changeClean(pom, p => p.replace(io, mutate(io)));
const profile = body => `<profile><id>clean-drift</id>${body}</profile>`;

test('Clean source and original Release gate admit only the exact local IO owner across all 26 projects', () => {
  for (const script of [checker, releaseChecker]) {
    const result = check(root, [], script);
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /verified across 26 projects/);
  }
});
for (const [name, mutate] of [
  ['missing Clean', p => p.replace(clean, '')],
  ['duplicate Clean', p => p.replace(clean, clean.repeat(2))],
  ['missing plugin version', p => changeClean(p, c => c.replace('<version>${maven.clean.version}</version>', ''))],
  ['duplicate plugin version', p => changeClean(p, c => c.replace('</plugin>', '<version>3.2.0</version></plugin>'))],
  ['duplicate plugin artifact', p => changeClean(p, c => c.replace('</plugin>', '<artifactId>maven-clean-plugin</artifactId></plugin>'))],
  ['implicit plugin group', p => changeClean(p, c => c.replace('<groupId>org.apache.maven.plugins</groupId>', ''))],
  ['foreign plugin group', p => changeClean(p, c => c.replace('org.apache.maven.plugins', 'unrelated'))],
  ['duplicate plugin group', p => changeClean(p, c => c.replace('</plugin>', '<groupId>org.apache.maven.plugins</groupId></plugin>'))],
  ['literal plugin pin', p => changeClean(p, c => c.replace('${maven.clean.version}', '3.2.0'))],
  ['alias plugin pin', p => changeClean(p, c => c.replace('${maven.clean.version}', '${clean.version}'))],
  ['missing plugin property', p => p.replace('<maven.clean.version>3.2.0</maven.clean.version>', '')],
  ['changed plugin property', p => p.replace('<maven.clean.version>3.2.0', '<maven.clean.version>3.5.0')],
  ['duplicate plugin property', p => p.replace('<maven.clean.version>3.2.0</maven.clean.version>', '<maven.clean.version>3.2.0</maven.clean.version>'.repeat(2))],
  ['missing IO property', p => p.replace('<maven.clean.commons-io.version>2.20.0</maven.clean.commons-io.version>', '')],
  ['changed IO property', p => p.replace('<maven.clean.commons-io.version>2.20.0', '<maven.clean.commons-io.version>2.6')],
  ['duplicate IO property', p => p.replace('<maven.clean.commons-io.version>2.20.0</maven.clean.commons-io.version>', '<maven.clean.commons-io.version>2.20.0</maven.clean.commons-io.version>'.repeat(2))],
  ['missing IO', p => changeClean(p, c => c.replace(io, ''))],
  ['duplicate IO', p => changeClean(p, c => c.replace(io, io.repeat(2)))],
  ['literal IO pin', p => changeIo(p, d => d.replace('${maven.clean.commons-io.version}', '2.20.0'))],
  ['Release property alias for Clean IO', p => changeIo(p, d => d.replace('${maven.clean.commons-io.version}', '${maven.release.commons-io.version}'))],
  ['wrong IO group', p => changeIo(p, d => d.replace('<groupId>commons-io', '<groupId>other'))],
  ['wrong IO artifact', p => changeIo(p, d => d.replace('<artifactId>commons-io', '<artifactId>other'))],
  ['duplicate IO version', p => changeIo(p, d => d.replace('</dependency>', '<version>2.20.0</version></dependency>'))],
  ['duplicate dependencies container', p => changeClean(p, c => c.replace('</plugin>', '<dependencies/></plugin>'))],
  ['dependency attributes', p => changeIo(p, d => d.replace('<dependency>', '<dependency optional="true">'))],
  ['plugin attributes', p => changeClean(p, c => c.replace('<plugin>', '<plugin inherited="false">'))],
  ['dependency container attributes', p => changeClean(p, c => c.replace('<dependencies>', '<dependencies combine.self="override">'))],
  ['property attributes', p => p.replace('<maven.clean.version>', '<maven.clean.version value="3.2.0">')],
  ['nested scalar', p => changeClean(p, c => c.replace('<version>${maven.clean.version}', '<version><nested/>${maven.clean.version}'))],
  ['mixed plugin content', p => changeClean(p, c => c.replace('<plugin>', '<plugin>unexpected'))],
  ['foreign namespace plugin', p => changeClean(p, c => c.replace('<plugin>', '<plugin xmlns="urn:foreign">'))],
  ['foreign namespace field', p => changeIo(p, d => d.replace('<version>', '<version xmlns="urn:foreign">'))],
  ['unnamespaced property', p => p.replace('<maven.clean.version>', '<maven.clean.version xmlns="">')],
  ['active Clean declaration', p => p.replace('<plugins>\n            <plugin>', `<plugins>${clean}\n            <plugin>`)],
  ['profile Clean declaration', p => p.replace('<profiles>', `<profiles>${profile(`<build><plugins>${clean}</plugins></build>`)}`)],
  ['profile pin override', p => p.replace('<profiles>', `<profiles>${profile('<properties><maven.clean.version>3.2.0</maven.clean.version></properties>')}`)],
  ['reporting Clean', p => p.replace('</project>', `<reporting><plugins>${clean}</plugins></reporting></project>`)],
  ['global IO dependency', p => p.replace('<dependencyManagement>', `<dependencies>${io}</dependencies><dependencyManagement>`)],
  ['global IO management', p => p.replace('<dependencyManagement>\n        <dependencies>', `<dependencyManagement><dependencies>${io}`)],
  ['profile global IO', p => p.replace('<profiles>', `<profiles>${profile(`<dependencies>${io}</dependencies>`)}`)],
  ['unrelated plugin IO', p => p.replace('<artifactId>maven-dependency-plugin</artifactId>', `<artifactId>maven-dependency-plugin</artifactId><dependencies>${io}</dependencies>`)],
  ['aliased global IO', p => p.replace('</properties>', '<io.alias>commons-io</io.alias></properties>')
    .replace('<dependencyManagement>', '<dependencies><dependency><groupId>${io.alias}</groupId><artifactId>${io.alias}</artifactId><version>2.6</version></dependency></dependencies><dependencyManagement>')],
  ['aliased active Clean', p => p.replace('</properties>', '<plugin.alias>maven-clean-plugin</plugin.alias></properties>')
    .replace('<plugins>\n            <plugin>', '<plugins><plugin><artifactId>${plugin.alias}</artifactId><version>3.5.0</version></plugin>\n            <plugin>')],
  ['profile alias IO', p => p.replace('<profiles>', `<profiles>${profile('<properties><io.alias>commons-io</io.alias></properties><dependencies><dependency><groupId>${io.alias}</groupId><artifactId>${io.alias}</artifactId><version>2.6</version></dependency></dependencies>')}`)],
  ['Clean property reference outside owner', p => p.replace('<version>${maven.dependency.version}</version>', '<version>${maven.clean.version}</version>')],
  ['additional Clean behavior property', p => p.replace('</properties>', '<maven.clean.fast>true</maven.clean.fast></properties>')],
  ['legacy Clean behavior property', p => p.replace('</properties>', '<clean.skip>true</clean.skip></properties>')],
  ['malformed XML', p => p.replace('</project>', '</wrong-project>')],
  ['source identity drift', p => p.replace('<artifactId>approval-platform</artifactId>', '<artifactId>unrelated</artifactId>')],
  ['duplicate module', p => p.replace('<module>server-modules</module>', '<module>server-modules</module>'.repeat(2))],
  ['malformed module', p => p.replace('<module>server-modules</module>', '<module><unexpected/>server-modules</module>')],
]) test(`Clean and retained Release source gates reject ${name}`, t => sourceRejected(t, mutate));

for (const field of ['configuration', 'executions', 'extensions', 'inherited', 'unknown']) {
  test(`source Clean rejects extra ${field}`, t => sourceRejected(t,
    p => changeClean(p, c => c.replace('</plugin>', `<${field}/></plugin>`))));
}
test('source Clean rejects even the exact default-clean execution allowed only in generated models', t =>
  sourceRejected(t, p => changeClean(p, c => c.replace('</plugin>', execution + '</plugin>'))));
for (const field of ['scope', 'type', 'classifier', 'optional', 'exclusions', 'systemPath', 'unknown']) {
  test(`Clean IO rejects extra ${field}`, t => sourceRejected(t,
    p => changeIo(p, d => d.replace('</dependency>', `<${field}/></dependency>`))));
}
for (const [name, addition] of [
  ['module active Clean', `<build><plugins>${clean}</plugins></build>`],
  ['module managed Clean', `<build><pluginManagement><plugins>${clean}</plugins></pluginManagement></build>`],
  ['module implicit-group Clean', '<build><plugins><plugin><artifactId>maven-clean-plugin</artifactId><version>3.2.0</version></plugin></plugins></build>'],
  ['module profile Clean', `<profiles>${profile(`<build><plugins>${clean}</plugins></build>`)}</profiles>`],
  ['module Clean property', '<properties><maven.clean.version>3.2.0</maven.clean.version></properties>'],
  ['module profile IO property', `<profiles>${profile('<properties><maven.clean.commons-io.version>2.20.0</maven.clean.commons-io.version></properties>')}</profiles>`],
  ['module fast property', '<properties><maven.clean.fastDir>/tmp/outside</maven.clean.fastDir></properties>'],
  ['module global IO', `<dependencies>${io}</dependencies>`],
  ['module alias IO', '<properties><io.alias>commons-io</io.alias></properties><dependencies><dependency><groupId>${io.alias}</groupId><artifactId>${io.alias}</artifactId><version>2.6</version></dependency></dependencies>'],
  ['module profile alias Clean', `<profiles>${profile('<properties><plugin.alias>maven-clean-plugin</plugin.alias></properties><build><plugins><plugin><artifactId>${plugin.alias}</artifactId><version>3.5.0</version></plugin></plugins></build>')}</profiles>`],
]) test(`source rejects ${name}`, t => sourceRejected(t, p => p, directory => {
  const file = path.join(directory, 'server-modules/approval-domain/pom.xml');
  writeFileSync(file, readFileSync(file, 'utf8').replace('</project>', addition + '</project>'));
}));
for (const version of [5, 6]) test(`source includes the RuoYi ${version} overlay ownership boundary`, t =>
  sourceRejected(t, p => p, directory => {
    const file = path.join(directory, `integrations/ruoyi${version}-host-starter/overlay/ruoyi-extend/ruoyi-approval-host-starter/pom.xml`);
    writeFileSync(file, readFileSync(file, 'utf8').replace('</project>', `<build><plugins>${clean}</plugins></build></project>`));
  }));
test('source rejects a different reactor path even when count and artifact IDs are retained', t =>
  sourceRejected(t, p => p, directory => {
    const file = path.join(directory, 'server-modules/pom.xml');
    writeFileSync(file, readFileSync(file, 'utf8').replace('<module>approval-domain</module>', '<module>replacement-domain</module>'));
    renameSync(path.join(directory, 'server-modules/approval-domain'), path.join(directory, 'server-modules/replacement-domain'));
  }));

const releaseDependencies = source.match(releasePattern)[0].match(/<dependency>[\s\S]*?<\/dependency>/g);
assert.equal(releaseDependencies.length, 8);
for (const [index, dependency] of releaseDependencies.entries()) {
  test(`Clean allowance retains Release override ${index + 1} and its order/fields`, t => sourceRejected(t,
    p => p.replace(releasePattern, r => r.replace(dependency, dependency.replace('</dependency>', '<scope>test</scope></dependency>')))));
}

// These models deliberately come from XML transforms, never Maven. They prove
// parsing/rejection behavior only and are discarded after each named test.
const generated = python(['-c', `
import copy, runpy, sys, xml.etree.ElementTree as E
from pathlib import Path
c = runpy.run_path(sys.argv[1]); projects = c['reactor'](Path(sys.argv[2])); ns = c['NS']; prefix = '{'+ns['m']+'}'
root = projects[0][1]; properties = root.find('m:properties', ns); pins = {p.tag.split('}')[-1]: p.text for p in properties}
models = E.Element(prefix+'projects')
for _, source in projects:
    project = E.SubElement(models, prefix+'project'); parent = source.find('m:parent', ns)
    group = c['value'](parent, 'groupId') if parent is not None else ''
    for name, text in [('groupId', c['value'](source, 'groupId') or group), ('artifactId', c['value'](source, 'artifactId'))]:
        E.SubElement(project, prefix+name).text = text
    project.append(copy.deepcopy(properties)); build = copy.deepcopy(root.find('m:build', ns)); project.append(build)
    clean = next(p for p in build.findall('m:pluginManagement/m:plugins/m:plugin', ns) if c['value'](p, 'artifactId') == 'maven-clean-plugin')
    active = copy.deepcopy(clean); E.SubElement(active.find('m:dependencies/m:dependency', ns), prefix+'scope').text = 'compile'
    active.append(E.fromstring('<executions xmlns="'+ns['m']+'"><execution><id>default-clean</id><phase>clean</phase><goals><goal>clean</goal></goals></execution></executions>'))
    build.find('m:plugins', ns).append(active)
    for item in project.iter():
        if item.text:
            for key, value in pins.items(): item.text = item.text.replace(chr(36)+'{'+key+'}', value)
E.register_namespace('', ns['m']); print(E.tostring(models, encoding='unicode'))
`, releaseChecker, root]);
assert.equal(generated.status, 0, generated.stderr);
const effective = generated.stdout;
const managedClean = effective.match(cleanPattern)[0];
const allClean = [...effective.matchAll(new RegExp(cleanPattern.source, 'g'))];
assert.equal(allClean.length, 52, 'synthetic models must contain both managed and active entries in all 26 projects');
const activeClean = allClean[1][0];
const modelIo = managedClean.match(/<dependency>[\s\S]*?<\/dependency>/)[0];
function checkEffective(t, xml) {
  const file = path.join(temporary(t, 'clean-model-'), 'synthetic.xml');
  writeFileSync(file, xml);
  return check(root, [`--effective-pom=${file}`]);
}
test('Clean model contract accepts all26 synthetic models with only generated default-clean executions', t => {
  const result = checkEffective(t, effective);
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /all 26 projects/);
});
test('Clean effective models permit Maven default plugin-group omission', t => {
  const result = checkEffective(t, effective.replaceAll('<groupId>org.apache.maven.plugins</groupId>', ''));
  assert.equal(result.status, 0, result.stderr);
});
test('Clean models permit no active declaration where Maven supplies none', t => {
  const result = checkEffective(t, effective.replaceAll(activeClean, ''));
  assert.equal(result.status, 0, result.stderr);
});
const baseline = effective
  .replaceAll('<maven.clean.version>3.2.0</maven.clean.version>', '')
  .replaceAll('<maven.clean.commons-io.version>2.20.0</maven.clean.commons-io.version>', '')
  .replaceAll(managedClean, '')
  .replaceAll(activeClean, activeClean.replace(/<dependencies>[\s\S]*?<\/dependencies>/, ''));
function compareModels(t, candidate, prior = baseline) {
  const directory = temporary(t, 'clean-model-delta-');
  const currentFile = path.join(directory, 'synthetic-candidate.xml');
  const baselineFile = path.join(directory, 'synthetic-baseline.xml');
  writeFileSync(currentFile, candidate); writeFileSync(baselineFile, prior);
  return check(root, [`--effective-pom=${currentFile}`, `--baseline-effective-pom=${baselineFile}`,
    '--baseline-root=/synthetic/baseline']);
}
test('all26 paired model comparison reverses only exact Clean additions and checkout paths', t => {
  const candidate = effective.replace('<build>', `<build><directory>${root.replace(/\/$/, '')}/target</directory>`);
  const prior = baseline.replace('<build>', '<build><directory>/synthetic/baseline/target</directory>');
  const result = compareModels(t, candidate, prior);
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /All 26 baseline model semantics preserved/);
});
for (const [name, mutate] of [
  ['retained configuration', x => x.replace('<parameters>true</parameters>', '<parameters>false</parameters>')],
  ['retained execution', x => x.replace('<id>enforce-toolchain</id>', '<id>changed-toolchain</id>')],
  ['new project dependency', x => x.replace('</project>', '<dependencies><dependency><groupId>other</groupId><artifactId>new</artifactId><version>1</version></dependency></dependencies></project>')],
  ['new project management', x => x.replace('</project>', '<dependencyManagement><dependencies><dependency><groupId>other</groupId><artifactId>new</artifactId><version>1</version></dependency></dependencies></dependencyManagement></project>')],
  ['unrelated property', x => x.replace('<java.version>21</java.version>', '<java.version>22</java.version>')],
  ['new build directory', x => x.replace('<build>', '<build><directory>/outside/target</directory>')],
  ['significant config text', x => x.replace('<parameters>true</parameters>', '<parameters>true</parameters>extra-text')],
]) test(`all26 model comparison rejects ${name}`, t => {
  const changed = mutate(effective);
  assert.notEqual(changed, effective, 'paired-model mutation must change input');
  rejected(compareModels(t, changed));
});
test('paired-model verification requires both baseline models and source root', () => {
  rejected(check(root, ['--baseline-root=/synthetic/baseline']));
  rejected(check(root, ['--baseline-effective-pom=/synthetic-not-read.xml']));
});
for (const [name, mutate] of [
  ['missing project', x => x.replace(/<project>[\s\S]*?<\/project>/, '')],
  ['duplicate project', x => x.replace('</projects>', x.match(/<project>[\s\S]*?<\/project>/)[0] + '</projects>')],
  ['same-count identity replacement', x => x.replace('<artifactId>approval-platform</artifactId>', '<artifactId>unrelated</artifactId>')],
  ['same-count duplicate identity', x => x.replace('<artifactId>approval-platform</artifactId>', '<artifactId>approval-domain</artifactId>')],
  ['wrong group', x => x.replace('<groupId>io.github.akaryc1b.approval</groupId>', '<groupId>unrelated</groupId>')],
  ['missing managed Clean', x => x.replace(managedClean, '')],
  ['duplicate managed Clean', x => x.replace(managedClean, managedClean.repeat(2))],
  ['duplicate active Clean', x => x.replace(activeClean, activeClean.repeat(2))],
  ['managed execution', x => x.replace(managedClean, activeClean)],
  ['active execution missing', x => x.replace(activeClean, managedClean)],
  ['wrong Clean pin', x => x.replace(managedClean, managedClean.replace('<version>3.2.0', '<version>3.5.0'))],
  ['unresolved Clean pin', x => x.replace(managedClean, managedClean.replace('<version>3.2.0', '<version>${maven.clean.version}'))],
  ['wrong managed IO pin', x => x.replace(managedClean, managedClean.replace('<version>2.20.0', '<version>2.6'))],
  ['wrong active IO pin', x => x.replace(activeClean, activeClean.replace('<version>2.20.0', '<version>2.6'))],
  ['wrong active IO scope', x => x.replace(activeClean, activeClean.replace('<scope>compile</scope>', '<scope>test</scope>'))],
  ['missing generated active IO scope', x => x.replace(activeClean, activeClean.replace('<scope>compile</scope>', ''))],
  ['duplicate active IO scope', x => x.replace(activeClean, activeClean.replace('<scope>compile</scope>', '<scope>compile</scope>'.repeat(2)))],
  ['active IO extra type', x => x.replace(activeClean, activeClean.replace('</dependency>', '<type>jar</type></dependency>'))],
  ['unresolved IO pin', x => x.replace(managedClean, managedClean.replace('<version>2.20.0', '<version>${maven.clean.commons-io.version}'))],
  ['IO extra field', x => x.replace(managedClean, managedClean.replace('</dependency>', '<scope>compile</scope></dependency>'))],
  ['IO duplicate', x => x.replace(managedClean, managedClean.replace(modelIo, modelIo.repeat(2)))],
  ['plugin configuration', x => x.replace(managedClean, managedClean.replace('</plugin>', '<configuration /></plugin>'))],
  ['plugin duplicate version', x => x.replace(managedClean, managedClean.replace('</plugin>', '<version>3.2.0</version></plugin>'))],
  ['foreign namespace', x => x.replace(managedClean, managedClean.replace('<plugin>', '<plugin xmlns="urn:foreign">'))],
  ['property downgrade', x => x.replace('<maven.clean.commons-io.version>2.20.0', '<maven.clean.commons-io.version>2.6')],
  ['property duplicate', x => x.replace('<maven.clean.version>3.2.0</maven.clean.version>', '<maven.clean.version>3.2.0</maven.clean.version>'.repeat(2))],
  ['new behavior property', x => x.replace('</properties>', '<maven.clean.skip>true</maven.clean.skip></properties>')],
  ['profile property', x => x.replace('</project>', `<profiles>${profile('<properties><maven.clean.version>3.2.0</maven.clean.version></properties>')}</profiles></project>`)],
  ['profile Clean declaration', x => x.replace('</project>', `<profiles>${profile(`<build><plugins>${managedClean}</plugins></build>`)}</profiles></project>`)],
  ['global IO', x => x.replace('</project>', `<dependencies>${modelIo}</dependencies></project>`)],
  ['global managed IO', x => x.replace('</project>', `<dependencyManagement><dependencies>${modelIo}</dependencies></dependencyManagement></project>`)],
  ['unrelated plugin IO', x => x.replace('<artifactId>maven-dependency-plugin</artifactId>', `<artifactId>maven-dependency-plugin</artifactId><dependencies>${modelIo}</dependencies>`)],
  ['wrong lifecycle phase', x => x.replace('<phase>clean</phase>', '<phase>verify</phase>')],
  ['wrong execution id', x => x.replace('<id>default-clean</id>', '<id>other</id>')],
  ['wrong goal', x => x.replace('<goal>clean</goal>', '<goal>help</goal>')],
  ['extra goal', x => x.replace('<goal>clean</goal>', '<goal>clean</goal><goal>help</goal>')],
  ['duplicate execution', x => x.replace(/<execution><id>default-clean[\s\S]*?<\/execution>/, e => e.repeat(2))],
  ['execution configuration', x => x.replace('<id>default-clean</id>', '<id>default-clean</id><configuration />')],
  ['execution attributes', x => x.replace('<execution><id>default-clean', '<execution inherited="false"><id>default-clean')],
  ['retained Release dependency', x => x.replace(releasePattern, r => r.replace('<version>2.20.0', '<version>2.6'))],
  ['retained Release SSHD', x => x.replace(releasePattern, r => r.replace('<version>2.16.0', '<version>2.7.0'))],
  ['retained Site property', x => x.replace('<maven.site.version>3.22.0', '<maven.site.version>3.12.1')],
  ['retained Dependency pin', x => x.replace('<version>3.11.0</version>', '<version>3.7.0</version>')],
  ['retained Boot override', x => x.replace('<version>3.1.7</version>', '<version>3.1.6</version>')],
  ['malformed XML', x => x.replace('</projects>', '</wrong>')],
]) test(`Clean effective-model contract rejects ${name}`, t => {
  const changed = mutate(effective);
  assert.notEqual(changed, effective, 'model mutation must change synthetic input');
  rejected(checkEffective(t, changed));
});

const coordinates = ['org.apache.maven.plugins:maven-clean-plugin:jar:3.2.0',
  'org.apache.maven.shared:maven-shared-utils:jar:3.3.4', 'commons-io:commons-io:jar:2.20.0'];
const marker = '[DEBUG] Populating class realm plugin>org.apache.maven.plugins:maven-clean-plugin:3.2.0';
const realm = marker + '\n' + coordinates.map(c => `[DEBUG]   Included: ${c}\n`).join('')
  + '[DEBUG] Configuring mojo org.apache.maven.plugins:maven-clean-plugin:3.2.0:clean\n';
function checkRealm(t, log, repository) {
  const file = path.join(temporary(t, 'clean-realm-'), 'synthetic.log');
  writeFileSync(file, log);
  return check(root, [`--realm-log=${file}`, ...(repository ? [`--maven-repository=${repository}`] : [])]);
}
test('Clean realm parser accepts only the bounded synthetic three-entry set and ignores adjacent owners', t => {
  for (const log of [realm, realm + '[DEBUG] Populating class realm plugin>other:plugin:1\n[DEBUG]   Included: commons-io:commons-io:jar:2.6\n',
    realm.replace('[DEBUG] Configuring mojo org.apache.maven.plugins:maven-clean-plugin:3.2.0:clean',
      '[DEBUG] Loading mojo org.apache.maven.plugins:maven-clean-plugin:3.2.0:clean from plugin realm ClassRealm[plugin>org.apache.maven.plugins:maven-clean-plugin:3.2.0, parent: null]'),
    realm.replace('[DEBUG] Configuring mojo org.apache.maven.plugins:maven-clean-plugin:3.2.0:clean',
      '[DEBUG] Populating class realm plugin>other:plugin:1\n[DEBUG]   Included: other:extra:jar:1'),
    realm.replaceAll('[DEBUG]', '\u001b[36m[DEBUG]\u001b[0m')]) {
    const result = checkRealm(t, log);
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /not class-load or API evidence/);
  }
});
for (const [name, log] of [
  ['missing realm', 'unrelated'], ['duplicate realm', realm + realm],
  ['wrong Clean version', realm.replaceAll('3.2.0', '3.5.0')],
  ['second conflicting Clean realm', realm + realm.replaceAll('3.2.0', '3.5.0')],
  ['old IO', realm.replace('2.20.0', '2.6')],
  ['wrong Shared Utils', realm.replace('3.3.4', '3.4.2')],
  ['missing entry', realm.replace('[DEBUG]   Included: commons-io:commons-io:jar:2.20.0\n', '')],
  ['duplicate entry', realm.replace(marker, marker + '\n[DEBUG]   Included: commons-io:commons-io:jar:2.20.0')],
  ['extra entry', realm.replace(marker, marker + '\n[DEBUG]   Included: other:extra:jar:1')],
  ['classified IO', realm.replace('commons-io:commons-io:jar:2.20.0', 'commons-io:commons-io:jar:tests:2.20.0')],
  ['non-JAR IO', realm.replace('commons-io:commons-io:jar:', 'commons-io:commons-io:pom:')],
  ['malformed inclusion', realm.replace('[DEBUG] Configuring', '[DEBUG]   Included: unexpected extra fields\n[DEBUG] Configuring')],
  ['interrupted extra inclusion', realm.replace('[DEBUG] Configuring', '\n[DEBUG]   Included: evil:extra:jar:1\n[DEBUG] Configuring')],
  ['unexpected block terminator', realm.replace('[DEBUG] Configuring mojo org.apache.maven.plugins:maven-clean-plugin:3.2.0:clean', '[DEBUG] unrelated event')],
  ['truncated after approved coordinates', realm.slice(0, realm.indexOf('[DEBUG] Configuring'))],
]) test(`Clean realm contract rejects ${name}`, t => rejected(checkRealm(t, log)));
test('Clean realm byte binding rejects same-named fake JARs without loading them', t => {
  const repository = temporary(t, 'clean-fake-cache-');
  for (const coordinate of coordinates) {
    const [group, artifact, , version] = coordinate.split(':');
    const file = path.join(repository, ...group.split('.'), artifact, version, `${artifact}-${version}.jar`);
    mkdirSync(path.dirname(file), { recursive: true });
    writeFileSync(file, 'synthetic fake JAR bytes, never executed');
  }
  const result = checkRealm(t, realm, repository);
  rejected(result);
  assert.match(result.stderr, /SHA-256 mismatch/);
});
test('Clean JAR validation cannot run without a supplied realm', () =>
  rejected(check(root, ['--maven-repository=/synthetic-not-read'])));
test('the existing transport aggregate includes the named Clean synthetic test', () => {
  assert.match(readFileSync(path.join(root, 'scripts/tests/m6-ai-transport-review-boundary.test.mjs'), 'utf8'),
    /import '\.\/clean-plugin-baseline\.test\.mjs';/);
});
