import assert from 'node:assert/strict';
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { requirePinnedMaven } from '../ci/maven-toolchain.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const checker = path.join(root, 'scripts/ci/verify-release-plugin-baseline.py');
const source = readFileSync(path.join(root, 'pom.xml'), 'utf8');
const inventory = spawnSync('git', ['ls-files', '-z', '--', 'pom.xml', '**/pom.xml'], { cwd: root, encoding: 'utf8' });
assert.equal(inventory.status, 0, 'source POM inventory must be available');
const sourcePoms = inventory.stdout.split('\0').filter(Boolean);
const releasePlugin = source.match(/<plugin>\s*<groupId>org\.apache\.maven\.plugins<\/groupId>\s*<artifactId>maven-release-plugin<\/artifactId>[\s\S]*?<\/plugin>/)?.[0];
assert.ok(releasePlugin, 'managed Release source declaration must be available');
const mavenEnabled = process.env.GITHUB_ACTIONS === 'true' || process.env.RELEASE_PLUGIN_COMPATIBILITY === 'true';
const check = (directory = root, options = []) => spawnSync('python3', [checker, `--root=${directory}`, ...options], {
  encoding: 'utf8', env: { ...process.env, PYTHONDONTWRITEBYTECODE: '1' },
});
function temporary(t, prefix) {
  const directory = mkdtempSync(path.join(tmpdir(), prefix));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  return directory;
}
function sourceFixture(t, mutate, moduleMutation) {
  const directory = temporary(t, 'release-source-');
  assert.ok(sourcePoms.length >= 26);
  for (const relative of sourcePoms) {
    const file = path.join(directory, relative);
    mkdirSync(path.dirname(file), { recursive: true });
    copyFileSync(path.join(root, relative), file);
  }
  const changed = mutate(source);
  if (!moduleMutation) assert.notEqual(changed, source, 'mutation must change source');
  writeFileSync(path.join(directory, 'pom.xml'), changed);
  if (moduleMutation) {
    const file = path.join(directory, 'server-modules/approval-domain/pom.xml');
    const old = readFileSync(file, 'utf8');
    const updated = moduleMutation(old);
    assert.notEqual(updated, old, 'module mutation must change source');
    writeFileSync(file, updated);
  }
  return check(directory);
}
function rejected(result) {
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /ValueError:|ParseError:/);
}
function changePlugin(pom, mutate) {
  return pom.replace(releasePlugin, mutate(releasePlugin));
}

test('Release source is an exact root-only managed pin across 26 projects, retaining previous pins', () => {
  const result = check();
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /verified across 26 projects; not scanner or release-readiness evidence/);
});
for (const [name, mutate] of [
  ['downgrade', pom => pom.replace('<maven.release.version>3.3.1', '<maven.release.version>3.0.1')],
  ['missing management', pom => pom.replace(releasePlugin, '')],
  ['duplicate management', pom => pom.replace(releasePlugin, releasePlugin.repeat(2))],
  ['hardcoded version instead of shared pin', pom => changePlugin(pom, p => p.replace('${maven.release.version}', '3.3.1'))],
  ['duplicate version', pom => changePlugin(pom, p => p.replace('</plugin>', '<version>3.3.1</version></plugin>'))],
  ['duplicate group', pom => changePlugin(pom, p => p.replace('</plugin>', '<groupId>other</groupId></plugin>'))],
  ['duplicate property', pom => pom.replace('<maven.release.version>3.3.1</maven.release.version>', '<maven.release.version>3.3.1</maven.release.version>'.repeat(2))],
  ['configuration', pom => changePlugin(pom, p => p.replace('</plugin>', '<configuration><pushChanges>true</pushChanges></configuration></plugin>'))],
  ['lifecycle binding', pom => changePlugin(pom, p => p.replace('</plugin>', '<executions><execution><phase>verify</phase><goals><goal>prepare</goal></goals></execution></executions></plugin>'))],
  ['plugin dependency override', pom => changePlugin(pom, p => p.replace('</plugin>', '<dependencies><dependency><groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId><version>3.20.0</version></dependency></dependencies></plugin>'))],
  ['empty dependencies container', pom => changePlugin(pom, p => p.replace('</plugin>', '<dependencies/></plugin>'))],
  ['extensions', pom => changePlugin(pom, p => p.replace('</plugin>', '<extensions>true</extensions></plugin>'))],
  ['disabled inheritance', pom => changePlugin(pom, p => p.replace('</plugin>', '<inherited>false</inherited></plugin>'))],
  ['root active Release', pom => pom.replace('<plugins>\n            <plugin>', `<plugins>${releasePlugin}\n            <plugin>`)],
  ['root profile Release', pom => pom.replace('</project>', `<profiles><profile><id>drift</id><build><plugins>${releasePlugin}</plugins></build></profile></profiles></project>`)],
  ['root reporting Release', pom => pom.replace('</project>', `<reporting><plugins>${releasePlugin}</plugins></reporting></project>`)],
  ['profile property override', pom => pom.replace('</project>', '<profiles><profile><id>drift</id><properties><maven.release.version>3.0.1</maven.release.version></properties></profile></profiles></project>')],
  ['retained Site pin drift', pom => pom.replace('<maven.site.version>3.22.0', '<maven.site.version>3.12.1')],
  ['retained Dependency direct version drift', pom => pom.replace('${maven.dependency.version}</version>', '3.7.0</version>')],
  ['retained jsoup realm drift', pom => pom.replace('${maven.site.jsoup.version}</version>', '1.17.2</version>')],
  ['retained Boot Jackson realm drift', pom => pom.replace(/(<artifactId>jackson-core<\/artifactId>\s*<version>)\$\{jackson3-bom.version\}/, '$13.1.6')],
  ['duplicate reactor module', pom => pom.replace('<module>server-modules</module>', '<module>server-modules</module>'.repeat(2))],
  ['malformed XML', pom => pom.replace('</project>', '</wrong-project>')],
]) test(`Release source contract rejects ${name}`, t => rejected(sourceFixture(t, mutate)));
for (const [name, addition] of [
  ['module active Release', `<build><plugins>${releasePlugin}</plugins></build>`],
  ['module managed Release', `<build><pluginManagement><plugins>${releasePlugin}</plugins></pluginManagement></build>`],
  ['module implicit-group Release', '<build><plugins><plugin><artifactId>maven-release-plugin</artifactId><version>3.3.1</version></plugin></plugins></build>'],
  ['module profile Release', `<profiles><profile><id>drift</id><build><plugins>${releasePlugin}</plugins></build></profile></profiles>`],
  ['module reporting Release', `<reporting><plugins>${releasePlugin}</plugins></reporting>`],
  ['module property override', '<properties><maven.release.version>3.0.1</maven.release.version></properties>'],
  ['module profile property override', '<profiles><profile><id>drift</id><properties><maven.release.version>3.0.1</maven.release.version></properties></profile></profiles>'],
  ['module retained pin override', '<properties><maven.site.jsoup.version>1.17.2</maven.site.jsoup.version></properties>'],
]) test(`Release source contract rejects ${name}`, t => rejected(sourceFixture(t, p => p,
  p => p.replace('</project>', addition + '</project>'))));

const overrideDependency = /<dependency>\s*<groupId>org\.eclipse\.jgit<\/groupId>\s*<artifactId>org\.eclipse\.jgit<\/artifactId>[\s\S]*?<\/dependency>/;
for (const [name, mutate] of [
  ['missing approved JGit override', p => p.replace(overrideDependency, '')],
  ['duplicate approved JGit override', p => p.replace(overrideDependency, d => d.repeat(2))],
  ['test-scoped override', p => p.replace(overrideDependency, d => d.replace('</dependency>', '<scope>test</scope></dependency>'))],
  ['optional override', p => p.replace(overrideDependency, d => d.replace('</dependency>', '<optional>true</optional></dependency>'))],
  ['classified override', p => p.replace(overrideDependency, d => d.replace('</dependency>', '<classifier>tests</classifier></dependency>'))],
  ['excluded override', p => p.replace(overrideDependency, d => d.replace('</dependency>', '<exclusions/></dependency>'))],
  ['non-JAR override', p => p.replace(overrideDependency, d => d.replace('</dependency>', '<type>pom</type></dependency>'))],
]) test(`Release source rejects ${name}`, t => rejected(sourceFixture(t, p => changePlugin(p, mutate))));
for (const [property, pin, old] of [
  ['jgit', '5.13.5.202508271544-r', '5.13.3.202401111512-r'],
  ['sshd', '2.16.0', '2.7.0'], ['commons-io', '2.20.0', '2.11.0'], ['plexus-utils', '4.0.3', '4.0.2'],
]) test(`Release source rejects ${property} downgrade`, t => rejected(sourceFixture(t,
  p => p.replace(`<maven.release.${property}.version>${pin}`, `<maven.release.${property}.version>${old}`))));
test('Release source rejects an application-level copy of an approved plugin override', t => {
  const dep = releasePlugin.match(overrideDependency)[0];
  rejected(sourceFixture(t, p => p.replace('<dependencyManagement>', `<dependencies>${dep}</dependencies><dependencyManagement>`)));
});
test('Release source rejects another plugin receiving a Release override', t => {
  const dep = releasePlugin.match(overrideDependency)[0];
  rejected(sourceFixture(t, p => p.replace('<artifactId>maven-dependency-plugin</artifactId>', `<artifactId>maven-dependency-plugin</artifactId><dependencies>${dep}</dependencies>`)));
});

// These synthetic documents test rejection logic only; they are not Maven evidence.
function effectiveFixture() {
  const generated = spawnSync('python3', ['-c', `
import copy, importlib.util, re, sys, xml.etree.ElementTree as E
from pathlib import Path
spec = importlib.util.spec_from_file_location('release_checker', sys.argv[1])
c = importlib.util.module_from_spec(spec); spec.loader.exec_module(c)
projects = c.reactor(Path(sys.argv[2])); root = projects[0][1]
properties = root.find('m:properties', c.NS)
pins = {p.tag.split('}')[-1]: p.text for p in properties}
models = E.Element('{'+c.NS['m']+'}projects')
for _, source in projects:
    project = E.SubElement(models, '{'+c.NS['m']+'}project')
    parent = source.find('m:parent', c.NS)
    group = c.value(parent, 'groupId') if parent is not None else ''
    for name, text in [('groupId', c.value(source, 'groupId') or group), ('artifactId', c.value(source, 'artifactId'))]:
        E.SubElement(project, '{'+c.NS['m']+'}'+name).text = text
    project.append(copy.deepcopy(properties))
    project.append(copy.deepcopy(root.find('m:build', c.NS)))
    for item in project.iter():
        if item.text:
            for key, value in pins.items(): item.text = item.text.replace(chr(36) + '{' + key + '}', value)
E.register_namespace('', c.NS['m'])
print(E.tostring(models, encoding='unicode'))
`, checker, root], { encoding: 'utf8', env: { ...process.env, PYTHONDONTWRITEBYTECODE: '1' } });
  assert.equal(generated.status, 0, generated.stderr);
  return generated.stdout;
}
const effective = effectiveFixture();
const effectiveRelease = /<plugin>\s*<groupId>org\.apache\.maven\.plugins<\/groupId>\s*<artifactId>maven-release-plugin<\/artifactId>[\s\S]*?<\/plugin>/;
function checkEffective(t, xml) {
  const file = path.join(temporary(t, 'release-models-'), 'synthetic.xml');
  writeFileSync(file, xml);
  return check(root, [`--effective-pom=${file}`]);
}
test('effective checker accepts a complete synthetic 26-model set with retained pins', t => {
  const result = checkEffective(t, effective);
  assert.equal(result.status, 0, result.stderr);
});
for (const [name, mutate] of [
  ['Release downgrade', xml => xml.replace('<version>3.3.1</version>', '<version>3.0.1</version>')],
  ['Release unresolved property', xml => xml.replace('<version>3.3.1</version>', '<version>${maven.release.version}</version>')],
  ['missing managed Release', xml => xml.replace(effectiveRelease, '')],
  ['duplicate managed Release', xml => xml.replace(effectiveRelease, p => p.repeat(2))],
  ['active Release declaration', xml => xml.replace('</build>', `<plugins>${xml.match(effectiveRelease)[0]}</plugins></build>`) ],
  ['Release lifecycle binding', xml => xml.replace(effectiveRelease, p => p.replace('</plugin>', '<executions><execution><phase>verify</phase></execution></executions></plugin>'))],
  ['Release dependency override', xml => xml.replace(effectiveRelease, p => p.replace('</plugin>', '<dependencies/></plugin>'))],
  ['effective profile variant', xml => xml.replace('</project>', `<profiles><profile><build><plugins>${xml.match(effectiveRelease)[0]}</plugins></build></profile></profiles></project>`)],
  ['retained Site pin', xml => xml.replace('<version>3.22.0</version>', '<version>3.12.1</version>')],
  ['retained Dependency pin', xml => xml.replace('<version>3.11.0</version>', '<version>3.7.0</version>')],
  ['retained jsoup realm', xml => xml.replace('<version>1.23.2</version>', '<version>1.17.2</version>')],
  ['retained Boot Jackson realm', xml => xml.replace('<version>3.1.7</version>', '<version>3.1.6</version>')],
  ['retained compiler pin', xml => xml.replace('<version>3.14.0</version>', '<version>3.13.0</version>')],
  ['retained active checkstyle pin', xml => xml.replace('<version>3.6.0</version>', '<version>3.5.0</version>')],
  ['retained property', xml => xml.replace('<opentelemetry.version>1.62.0', '<opentelemetry.version>1.61.0')],
  ['missing project', xml => xml.replace(/<project>[\s\S]*?<\/project>/, '')],
  ['duplicate project', xml => xml.replace('</projects>', xml.match(/<project>[\s\S]*?<\/project>/)[0] + '</projects>')],
  ['wrong project identity', xml => xml.replace('<artifactId>approval-platform</artifactId>', '<artifactId>unrelated</artifactId>')],
  ['wrong group identity', xml => xml.replace('<groupId>io.github.akaryc1b.approval</groupId>', '<groupId>unrelated</groupId>')],
  ['wrong namespace', xml => xml.replace('http://maven.apache.org/POM/4.0.0', 'urn:wrong')],
]) test(`effective Release checker rejects ${name}`, t => {
  const changed = mutate(effective);
  assert.notEqual(changed, effective);
  rejected(checkEffective(t, changed));
});

for (const [name, mutate] of [
  ['JGit override downgrade', xml => xml.replace('<version>5.13.5.202508271544-r</version>', '<version>5.13.3.202401111512-r</version>')],
  ['SSHD override downgrade', xml => xml.replace('<version>2.16.0</version>', '<version>2.7.0</version>')],
  ['Commons IO override downgrade', xml => xml.replace('<version>2.20.0</version>', '<version>2.11.0</version>')],
  ['Plexus override downgrade', xml => xml.replace('<version>4.0.3</version>', '<version>4.0.2</version>')],
  ['duplicate override', xml => xml.replace(overrideDependency, d => d.repeat(2))],
  ['optional override', xml => xml.replace(overrideDependency, d => d.replace('</dependency>', '<optional>true</optional></dependency>'))],
]) test(`effective Release checker rejects ${name}`, t => rejected(checkEffective(t, mutate(effective))));
test('effective Release checker accepts Maven default plugin group omission', t => {
  const xml = effective.replaceAll('<groupId>org.apache.maven.plugins</groupId>', '');
  const result = checkEffective(t, xml);
  assert.equal(result.status, 0, result.stderr);
});

const releaseComponents = ['maven-release-api', 'maven-release-manager', 'maven-release-oddeven-policy', 'maven-release-semver-policy']
  .map(name => `org.apache.maven.release:${name}:jar:3.3.1`);
const realmComponents = [...releaseComponents, 'org.apache.commons:commons-lang3:jar:3.20.0',
  'org.apache.commons:commons-text:jar:1.14.0', 'org.apache.maven.scm:maven-scm-api:jar:2.2.1'];
const originalOverrides = ['org.eclipse.jgit:org.eclipse.jgit:jar:5.13.3.202401111512-r', 'org.eclipse.jgit:org.eclipse.jgit.ssh.apache:jar:5.13.3.202401111512-r', ...['sshd-osgi', 'sshd-sftp', 'sshd-core', 'sshd-common'].map(name => `org.apache.sshd:${name}:jar:2.7.0`), 'commons-io:commons-io:jar:2.11.0', 'org.codehaus.plexus:plexus-utils:jar:4.0.2'];
const approvedOverrides = originalOverrides.map(item => item.replace('5.13.3.202401111512-r', '5.13.5.202508271544-r').replace('jar:2.7.0', 'jar:2.16.0').replace('jar:2.11.0', 'jar:2.20.0').replace('jar:4.0.2', 'jar:4.0.3'));
const descriptor = `<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-release-plugin</artifactId><version>3.3.1</version><goalPrefix>release</goalPrefix><requiredJavaVersion>1.8</requiredJavaVersion><requiredMavenVersion>3.6.3</requiredMavenVersion><mojos><mojo><goal>help</goal><requiresProject>false</requiresProject><requiresOnline>false</requiresOnline><threadSafe>true</threadSafe><implementation>org.apache.maven.plugins.maven_release_plugin.HelpMojo</implementation></mojo></mojos><dependencies>${[...realmComponents, ...originalOverrides].map(item => {
  const [group, artifact, type, version] = item.split(':');
  return `<dependency><groupId>${group}</groupId><artifactId>${artifact}</artifactId><type>${type}</type><version>${version}</version></dependency>`;
}).join('')}</dependencies></plugin>`;
const realm = '[DEBUG] Populating class realm plugin>org.apache.maven.plugins:maven-release-plugin:3.3.1\n'
  + ['org.apache.maven.plugins:maven-release-plugin:jar:3.3.1', ...realmComponents, ...approvedOverrides].map(c => `[DEBUG]   Included: ${c}\n`).join('')
  + '[DEBUG] Configuring mojo org.apache.maven.plugins:maven-release-plugin:3.3.1:help\n';
function descriptorFixture(t, xml = descriptor, log) {
  const directory = temporary(t, 'release-descriptor-');
  const file = path.join(directory, 'synthetic.jar');
  const archive = spawnSync('python3', ['-c', 'import sys,zipfile; z=zipfile.ZipFile(sys.argv[1], "w"); z.writestr("META-INF/maven/plugin.xml", sys.stdin.read()); z.close()', file], { input: xml, encoding: 'utf8' });
  assert.equal(archive.status, 0, archive.stderr);
  const options = [`--plugin-jar=${file}`];
  if (log !== undefined) {
    const logFile = path.join(directory, 'synthetic.log');
    writeFileSync(logFile, log);
    options.push(`--realm-log=${logFile}`);
  }
  return check(root, options);
}
test('descriptor checker accepts only expected toolchain and read-only help requirements', t => {
  const result = descriptorFixture(t);
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /Java >= 8, Maven >= 3\.6\.3/);
});
for (const [name, mutate] of [
  ['wrong plugin version', xml => xml.replace('<version>3.3.1</version>', '<version>3.0.1</version>')],
  ['wrong Maven requirement', xml => xml.replace('<requiredMavenVersion>3.6.3', '<requiredMavenVersion>4.0.0')],
  ['wrong Java requirement', xml => xml.replace('<requiredJavaVersion>1.8', '<requiredJavaVersion>25')],
  ['requires project', xml => xml.replace('<requiresProject>false', '<requiresProject>true')],
  ['requires online', xml => xml.replace('<requiresOnline>false', '<requiresOnline>true')],
  ['missing help goal', xml => xml.replace('<goal>help</goal>', '<goal>other</goal>')],
  ['duplicate help goal', xml => xml.replace('</mojos>', xml.match(/<mojo>[\s\S]*?<\/mojo>/)[0] + '</mojos>')],
  ['lifecycle fork', xml => xml.replace('</mojo>', '<executePhase>verify</executePhase></mojo>')],
  ['goal fork', xml => xml.replace('</mojo>', '<executeGoal>prepare</executeGoal></mojo>')],
  ['goal binding', xml => xml.replace('</mojo>', '<phase>verify</phase></mojo>')],
]) test(`Release descriptor rejects ${name}`, t => rejected(descriptorFixture(t, mutate(descriptor))));
test('realm checker isolates Release dependencies from adjacent plugin realms', t => {
  const adjacent = '[DEBUG] Populating class realm plugin>other:plugin:1\n[DEBUG]   Included: org.apache.commons:commons-lang3:jar:3.12.0\n';
  const result = descriptorFixture(t, descriptor, adjacent + realm + adjacent);
  assert.equal(result.status, 0, result.stderr);
});
for (const [name, mutate] of [
  ['absent marker', log => log.replace('Populating class realm plugin>', 'Unknown class realm plugin>')],
  ['duplicate realm', log => log + log],
  ['empty realm', log => log.split('\n')[0]],
  ['duplicate artifact', log => log.replace('[DEBUG] Configuring', '[DEBUG]   Included: org.apache.commons:commons-lang3:jar:3.20.0\n[DEBUG] Configuring')],
  ['version drift', log => log.replace('commons-lang3:jar:3.20.0', 'commons-lang3:jar:3.12.0')],
  ['missing Release manager', log => log.replace('[DEBUG]   Included: org.apache.maven.release:maven-release-manager:jar:3.3.1\n', '')],
  ['injected dependency', log => log.replace('[DEBUG] Configuring', '[DEBUG]   Included: unrelated:injected:jar:1\n[DEBUG] Configuring')],
]) test(`Release realm rejects ${name}`, t => rejected(descriptorFixture(t, descriptor, mutate(realm))));

for (const [name, replacement] of [
  ['JGit', ['org.eclipse.jgit:jar:5.13.5.202508271544-r', 'org.eclipse.jgit:jar:5.13.3.202401111512-r']],
  ['SSHD', ['sshd-osgi:jar:2.16.0', 'sshd-osgi:jar:2.7.0']],
  ['Commons IO', ['commons-io:jar:2.20.0', 'commons-io:jar:2.11.0']],
  ['Plexus', ['plexus-utils:jar:4.0.3', 'plexus-utils:jar:4.0.2']],
]) test(`actual realm checker rejects stale ${name} despite original descriptor`, t => {
  rejected(descriptorFixture(t, descriptor, realm.replace(...replacement)));
});

function maven(args, cwd = root, timeout = 240000) {
  const goals = args.filter(arg => !arg.startsWith('-'));
  assert.equal(goals.length, 1, 'Compatibility must invoke exactly one read-only goal');
  assert.ok(['org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom',
    'org.apache.maven.plugins:maven-release-plugin:help'].includes(goals[0]),
  'Release compatibility must never invoke a lifecycle, SCM or publishing goal');
  const repository = process.env.M6_PR_E_E2_MAVEN_REPOSITORY;
  // Compatibility invokes only help/effective-pom. Local MAVEN_ARGS can enforce
  // offline/cache use; CI may resolve official artifacts. No SCM or publication occurs.
  const result = spawnSync('mvn', ['-B', '-ntp', '-Dstyle.color=never',
    ...(repository ? [`-Dmaven.repo.local=${repository}`] : []), ...args], {
    cwd, encoding: 'utf8', timeout, maxBuffer: 16 * 1024 * 1024,
  });
  assert.equal(result.status, 0, `${result.error ?? ''}\n${result.stdout}\n${result.stderr}`);
  return result.stdout.replace(/\u001b\[[0-9;]*m/g, '');
}
function requireToolchain() {
  requirePinnedMaven();
  const result = spawnSync('java', ['-version'], { encoding: 'utf8', timeout: 30000 });
  assert.equal(result.status, 0, result.error?.message);
  assert.match(result.stderr + result.stdout, /(?:openjdk|java) version "21(?:\.|"|\+)/, 'Compatibility must use the actual Java 21 runtime');
}
function actualJar(log) {
  const setting = log.match(/^\[DEBUG\] Using local repository at (.+)$/m)?.[1];
  const repository = process.env.M6_PR_E_E2_MAVEN_REPOSITORY ?? setting ?? path.join(homedir(), '.m2/repository');
  const file = path.join(repository, 'org/apache/maven/plugins/maven-release-plugin/3.3.1/maven-release-plugin-3.3.1.jar');
  assert.ok(existsSync(file), 'Actual Release plugin JAR must be present in the resolved Maven repository');
  return file;
}
function verifyActualRealm(t, log) {
  const file = path.join(temporary(t, 'release-real-realm-'), 'help.log');
  writeFileSync(file, log);
  const result = check(root, [`--plugin-jar=${actualJar(log)}`, `--realm-log=${file}`]);
  assert.equal(result.status, 0, result.stderr);
  assert.match(log, /--- release:3\.3\.1:help /);
  assert.match(log, /release:help/);
  assert.doesNotMatch(log, /--- release:3\.3\.1:(?:prepare|perform|branch|rollback|stage|clean) /);
  console.log(result.stdout.trim());
}

test('CI verifies actual inherited Release pin and retained pins in all 26 effective POMs', {
  skip: !mavenEnabled, timeout: 300000,
}, t => {
  requireToolchain();
  const file = path.join(temporary(t, 'release-real-models-'), 'effective-pom.xml');
  maven(['org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom', `-Doutput=${file}`]);
  const result = check(root, [`--effective-pom=${file}`]);
  assert.equal(result.status, 0, result.stderr);
  console.log(result.stdout.trim());
});
test('CI runs only Release help with the actual root managed version and isolated realm', {
  skip: !mavenEnabled, timeout: 300000,
}, t => {
  requireToolchain();
  // Deliberately omit the goal version: this must resolve the root managed pin.
  const log = maven(['-N', '-X', 'org.apache.maven.plugins:maven-release-plugin:help', '-Ddetail=true', '-Dgoal=help']);
  verifyActualRealm(t, log);
});
test('CI runs Release help from a disposable no-SCM child with the exact candidate management', {
  skip: !mavenEnabled, timeout: 300000,
}, t => {
  requireToolchain();
  const directory = temporary(t, 'release-no-scm-');
  const parent = path.join(directory, 'parent');
  const child = path.join(directory, 'child');
  mkdirSync(parent); mkdirSync(child);
  // The real repository declares SCM metadata. Copy only its exact properties and
  // Release pluginManagement into a local parent, so this fixture cannot inherit
  // an external SCM/distribution URL. The full26 test covers real reactor inheritance.
  const properties = source.match(/<properties>[\s\S]*?<\/properties>/)[0];
  const parentPom = `<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>local.compatibility</groupId><artifactId>release-parent</artifactId><version>1</version><packaging>pom</packaging>${properties}<build><pluginManagement><plugins>${releasePlugin}</plugins></pluginManagement></build></project>`;
  const pom = `<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><parent><groupId>local.compatibility</groupId><artifactId>release-parent</artifactId><version>1</version><relativePath>../parent/pom.xml</relativePath></parent><artifactId>release-no-scm-fixture</artifactId><packaging>pom</packaging></project>`;
  assert.doesNotMatch(parentPom + pom, /<(?:scm|distributionManagement)>/);
  writeFileSync(path.join(parent, 'pom.xml'), parentPom);
  writeFileSync(path.join(child, 'pom.xml'), pom);
  const log = maven(['-N', '-X', 'org.apache.maven.plugins:maven-release-plugin:help', '-Ddetail=true', '-Dgoal=help'], child);
  verifyActualRealm(t, log);
  assert.equal(readFileSync(path.join(child, 'pom.xml'), 'utf8'), pom);
  assert.equal(readFileSync(path.join(parent, 'pom.xml'), 'utf8'), parentPom);
  assert.deepEqual(readdirSync(child), ['pom.xml'], 'help must not create SCM/release/build artifacts');
  assert.deepEqual(readdirSync(parent), ['pom.xml'], 'help must not modify the local parent');
  runLocalProbe(t, log);
  console.log('RELEASE_COMPATIBILITY_COMPLETE: Java 21 / Maven 3.9.16; inherited 3.3.1 help, exact eight overrides, no SCM fixture writes. Not release-workflow or scanner evidence.');
});

function runLocalProbe(t, log) {
  const directory = temporary(t, 'release-local-probe-');
  const jar = actualJar(log);
  const repository = path.resolve(path.dirname(jar), '../../../../../..');
  const marker = '[DEBUG] Populating class realm plugin>org.apache.maven.plugins:maven-release-plugin:3.3.1\n';
  const body = log.slice(log.indexOf(marker) + marker.length).split(/\n(?!\[DEBUG\]\s+Included:)/)[0];
  const coordinates = [...body.matchAll(/Included: ([^\s]+)/g)].map(match => match[1]);
  assert.equal(coordinates.length, 36, 'Probe must use the verified 36-entry actual isolated Release realm');
  const jars = coordinates.filter(c => c.split(':')[2] === 'jar').map(c => {
    const [group, name, type, version, extra] = c.split(':');
    assert.equal(extra, undefined, 'Unexpected classified realm entry');
    assert.equal(type, 'jar');
    const file = path.join(repository, ...group.split('.'), name, version, `${name}-${version}.jar`);
    assert.ok(existsSync(file), `Actual realm JAR missing: ${file}`);
    return file;
  });
  // SLF4J is imported from Maven's parent realm, not supplied by a POM override.
  assert.match(log, /Imported: org\.slf4j\.\* < plexus\.core/);
  const mavenHome = log.match(/^Maven home: (.+)$/m)?.[1];
  assert.ok(mavenHome, 'Actual Maven home must be recorded in debug output');
  const logger = path.join(mavenHome, 'lib/slf4j-api-1.7.36.jar');
  assert.ok(existsSync(logger));
  const classpath = [...jars, logger].join(path.delimiter);
  const compile = spawnSync('javac', ['-cp', classpath, '-d', directory,
    path.join(root, 'scripts/tests/fixtures/ReleasePluginLocalProbe.java')], {
    encoding: 'utf8', timeout: 30000,
  });
  assert.equal(compile.status, 0, compile.stderr || compile.stdout);
  const home = path.join(directory, 'isolated-home');
  mkdirSync(home);
  const probe = spawnSync('java', ['-Xmx96m', `-Duser.home=${home}`,
    '-cp', `${directory}${path.delimiter}${classpath}`, 'ReleasePluginLocalProbe', directory, ...jars], {
    encoding: 'utf8', timeout: 30000,
    env: { ...process.env, HOME: home, XDG_CONFIG_HOME: home, GIT_CONFIG_NOSYSTEM: '1' },
  });
  assert.equal(probe.status, 0, probe.stderr || probe.stdout);
  assert.match(probe.stdout, /PROBE_COMPLETE/);
  console.log(probe.stdout.trim());
}
