import assert from 'node:assert/strict';
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { requirePinnedMaven } from '../ci/maven-toolchain.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const checker = path.join(root, 'scripts/ci/verify-site-dependency-plugin-baseline.py');
const source = readFileSync(path.join(root, 'pom.xml'), 'utf8');
const sourcePoms = spawnSync('git', ['ls-files', '-z', '--', 'pom.xml', '**/pom.xml'], {
  cwd: root, encoding: 'utf8',
}).stdout.split('\0').filter(Boolean);
const mavenEnabled = process.env.GITHUB_ACTIONS === 'true'
  || process.env.SITE_DEPENDENCY_PLUGIN_COMPATIBILITY === 'true';
const check = (directory = root, effective) => spawnSync('python3', [checker, `--root=${directory}`,
  ...(effective ? [`--effective-pom=${effective}`] : [])], { encoding: 'utf8' });
const sitePlugin = source.match(/<plugin>\s*<groupId>org\.apache\.maven\.plugins<\/groupId>\s*<artifactId>maven-site-plugin<\/artifactId>[\s\S]*?<\/plugin>/)?.[0];
const dependencyPlugin = source.match(/<plugin>\s*<groupId>org\.apache\.maven\.plugins<\/groupId>\s*<artifactId>maven-dependency-plugin<\/artifactId>[\s\S]*?<\/plugin>/)?.[0];
const jsoupDependency = /<dependency>\s*<groupId>org\.jsoup<\/groupId>\s*<artifactId>jsoup<\/artifactId>[\s\S]*?<\/dependency>/;

function temporary(t, prefix) {
  const directory = mkdtempSync(path.join(tmpdir(), prefix));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  return directory;
}
function sourceFixture(t, mutate, moduleMutation) {
  const directory = temporary(t, 'site-dependency-source-');
  assert.ok(sourcePoms.length >= 26, 'source POM inventory must be available');
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

test('Site/Dependency source remains explicitly managed, plugin-local and unbound across 26 projects', () => {
  const result = check();
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /verified across 26 projects; not scanner evidence/);
});

for (const [name, mutate] of [
  ['Site downgrade', pom => pom.replace('<maven.site.version>3.22.0', '<maven.site.version>3.12.1')],
  ['Dependency downgrade', pom => pom.replace('<maven.dependency.version>3.11.0', '<maven.dependency.version>3.7.0')],
  ['jsoup downgrade', pom => pom.replace('<maven.site.jsoup.version>1.23.2', '<maven.site.jsoup.version>1.17.2')],
  ['missing Site management', pom => pom.replace(sitePlugin, '')],
  ['missing Dependency management', pom => pom.replace(dependencyPlugin, '')],
  ['duplicate Dependency management', pom => pom.replace(dependencyPlugin, dependencyPlugin + dependencyPlugin)],
  ['duplicate Site management', pom => pom.replace(sitePlugin, sitePlugin + sitePlugin)],
  ['missing jsoup dependency', pom => pom.replace(jsoupDependency, '')],
  ['duplicate jsoup dependency', pom => pom.replace(jsoupDependency, entry => entry + entry)],
  ['test-scoped jsoup', pom => pom.replace(jsoupDependency, entry => entry.replace('</dependency>', '<scope>test</scope></dependency>'))],
  ['optional jsoup', pom => pom.replace(jsoupDependency, entry => entry.replace('</dependency>', '<optional>true</optional></dependency>'))],
  ['excluded jsoup dependencies', pom => pom.replace(jsoupDependency, entry => entry.replace('</dependency>', '<exclusions/></dependency>'))],
  ['classified jsoup', pom => pom.replace(jsoupDependency, entry => entry.replace('</dependency>', '<classifier>tests</classifier></dependency>'))],
  ['duplicate version', pom => pom.replace('<version>${maven.site.version}</version>', '<version>${maven.site.version}</version><version>3.12.1</version>')],
  ['duplicate property', pom => pom.replace('<maven.site.version>3.22.0</maven.site.version>', '<maven.site.version>3.22.0</maven.site.version>'.repeat(2))],
  ['new lifecycle binding', pom => pom.replace(sitePlugin, sitePlugin.replace('</plugin>', '<executions><execution><phase>verify</phase><goals><goal>site</goal></goals></execution></executions></plugin>'))],
  ['jsoup added to another plugin', pom => pom.replace(dependencyPlugin, dependencyPlugin.replace('</plugin>', '<dependencies>' + pom.match(jsoupDependency)[0] + '</dependencies></plugin>'))],
  ['profile property override', pom => pom.replace('</project>', '<profiles><profile><id>drift</id><properties><maven.site.version>3.12.1</maven.site.version></properties></profile></profiles></project>')],
  ['application-level jsoup management', pom => pom.replace('<dependencyManagement>\n        <dependencies>', '<dependencyManagement>\n        <dependencies>' + pom.match(jsoupDependency)[0])],
  ['malformed XML', pom => pom.replace('</project>', '</wrong-project>')],
]) test(`Site/Dependency source contract rejects ${name}`, t => {
  const result = sourceFixture(t, mutate);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /ValueError:|ParseError:/);
});

for (const [name, addition] of [
  ['module plugin version override', '<build><plugins><plugin><artifactId>maven-site-plugin</artifactId><version>3.12.1</version></plugin></plugins></build>'],
  ['module Dependency binding', '<build><plugins><plugin><artifactId>maven-dependency-plugin</artifactId><executions><execution><phase>verify</phase><goals><goal>tree</goal></goals></execution></executions></plugin></plugins></build>'],
  ['module property override', '<properties><maven.site.jsoup.version>1.17.2</maven.site.jsoup.version></properties>'],
  ['profile plugin override', '<profiles><profile><id>drift</id><build><plugins><plugin><artifactId>maven-site-plugin</artifactId><version>3.12.1</version></plugin></plugins></build></profile></profiles>'],
]) test(`Site/Dependency source contract rejects ${name}`, t => {
  const result = sourceFixture(t, pom => pom, pom => pom.replace('</project>', addition + '</project>'));
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /ValueError:/);
});

const effectivePlugins = `<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-site-plugin</artifactId><version>3.22.0</version><dependencies><dependency><groupId>org.jsoup</groupId><artifactId>jsoup</artifactId><version>1.23.2</version></dependency></dependencies></plugin><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-dependency-plugin</artifactId><version>3.11.0</version></plugin>`;
// Minimal synthetic models validate the checker only; they are never Maven evidence.
function effectiveFixture() {
  const projects = [];
  function visit(relative) {
    const pom = readFileSync(path.join(root, relative), 'utf8');
    const withoutParent = pom.replace(/<parent>[\s\S]*?<\/parent>/, '');
    const id = withoutParent.match(/<artifactId>([^<]+)<\/artifactId>/)[1];
    projects.push(`<project><groupId>io.github.akaryc1b.approval</groupId><artifactId>${id}</artifactId><build><pluginManagement><plugins>${effectivePlugins}</plugins></pluginManagement></build></project>`);
    for (const module of pom.matchAll(/<module>([^<]+)<\/module>/g)) visit(path.join(path.dirname(relative), module[1], 'pom.xml'));
  }
  visit('pom.xml');
  assert.equal(projects.length, 26);
  return `<projects xmlns="http://maven.apache.org/POM/4.0.0">${projects.join('')}</projects>`;
}
function checkEffective(t, xml) {
  const file = path.join(temporary(t, 'site-dependency-effective-'), 'synthetic.xml');
  writeFileSync(file, xml);
  return check(root, file);
}
test('effective checker accepts only the full candidate model set', t => {
  const result = checkEffective(t, effectiveFixture());
  assert.equal(result.status, 0, result.stderr);
});
for (const [name, mutate] of [
  ['Site downgrade', xml => xml.replace('<version>3.22.0</version>', '<version>3.12.1</version>')],
  ['Dependency downgrade', xml => xml.replace('<version>3.11.0</version>', '<version>3.7.0</version>')],
  ['jsoup downgrade', xml => xml.replace('<version>1.23.2</version>', '<version>1.17.2</version>')],
  ['duplicate plugin', xml => xml.replace(effectivePlugins, effectivePlugins.repeat(2))],
  ['missing project', xml => xml.replace(/<project>[\s\S]*?<\/project>/, '')],
  ['duplicate project', xml => xml.replace('</projects>', xml.match(/<project>[\s\S]*?<\/project>/)[0] + '</projects>')],
  ['wrong project group', xml => xml.replace('<groupId>io.github.akaryc1b.approval</groupId>', '<groupId>unrelated</groupId>')],
  ['wrong project identity', xml => xml.replace('<artifactId>approval-platform</artifactId>', '<artifactId>unrelated</artifactId>')],
  ['active plugin override', xml => xml.replace('</build>', `<plugins>${effectivePlugins.replace('1.23.2', '1.17.2')}</plugins></build>`)],
  ['new managed execution', xml => xml.replace('</plugin>', '<executions><execution><phase>verify</phase></execution></executions></plugin>')],
]) test(`effective Site/Dependency checker rejects ${name}`, t => {
  const result = checkEffective(t, mutate(effectiveFixture()));
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /ValueError:/);
});

// These are Maven's existing site lifecycle bindings, never a new verify/install binding.
const defaultSite = effectivePlugins.match(/<plugin>[\s\S]*?<\/plugin>/)[0].replace('</plugin>',
  '<executions><execution><id>default-site</id><phase>site</phase><goals><goal>site</goal></goals></execution>'
  + '<execution><id>default-deploy</id><phase>site-deploy</phase><goals><goal>deploy</goal></goals></execution></executions></plugin>');
test('effective checker permits Maven default site bindings but rejects new or duplicate active bindings', t => {
  const xml = effectiveFixture().replace('</build>', `<plugins>${defaultSite}</plugins></build>`);
  const result = checkEffective(t, xml);
  assert.equal(result.status, 0, result.stderr);
  for (const changed of [xml.replace('<phase>site</phase>', '<phase>verify</phase>'),
    xml.replace(defaultSite, defaultSite.repeat(2))]) {
    const result = checkEffective(t, changed);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /ValueError:/);
  }
});

function maven(args, cwd = root, timeout = 240000) {
  const repository = process.env.M6_PR_E_E2_MAVEN_REPOSITORY;
  const result = spawnSync('mvn', ['-B', '-ntp', '-Dstyle.color=never',
    ...(repository ? [`-Dmaven.repo.local=${repository}`] : []), ...args], {
    cwd, encoding: 'utf8', timeout, maxBuffer: 16 * 1024 * 1024,
  });
  assert.equal(result.status, 0, `${result.error ?? ''}\n${result.stdout}\n${result.stderr}`);
  return result.stdout.replace(/\u001b\[[0-9;]*m/g, '');
}

test('CI checks actual inherited Site/Dependency models across the entire 26-project reactor', {
  skip: !mavenEnabled, timeout: 300000,
}, t => {
  requirePinnedMaven();
  const file = path.join(temporary(t, 'site-dependency-real-models-'), 'effective-pom.xml');
  maven(['org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom', `-Doutput=${file}`]);
  const result = check(root, file);
  assert.equal(result.status, 0, result.stderr);
  console.log(result.stdout.trim());
});

function realmCoordinates(log, artifact, version) {
  const marker = `Populating class realm plugin>org.apache.maven.plugins:${artifact}:${version}`;
  const at = log.indexOf(marker);
  assert.ok(at >= 0, `Actual Maven class realm is absent: ${artifact}:${version}`);
  const body = log.slice(at + marker.length).split(/\n(?!\[DEBUG\]\s+Included:)/)[0];
  // The first newline belongs to the first Included line, so retain consecutive
  // Maven debug inclusion lines only, never another plugin's dependencies.
  const entries = [...body.matchAll(/Included: ([^\s]+)/g)].map(match => match[1]);
  assert.ok(entries.length > 2, `Actual class realm includes no dependency list: ${body}`);
  return entries;
}

test('realm parser isolates the actual Site dependency list from adjacent plugin realms', () => {
  const log = '[DEBUG] Populating class realm plugin>org.apache.maven.plugins:maven-site-plugin:3.22.0\n'
    + '[DEBUG]   Included: org.apache.maven.plugins:maven-site-plugin:jar:3.22.0\n'
    + '[DEBUG]   Included: org.jsoup:jsoup:jar:1.23.2\n'
    + '[DEBUG]   Included: org.apache.maven.doxia:doxia-core:jar:2.0.0\n'
    + '[DEBUG] Populating class realm plugin>other:plugin:1\n'
    + '[DEBUG]   Included: org.jsoup:jsoup:jar:1.17.2\n';
  assert.deepEqual(realmCoordinates(log, 'maven-site-plugin', '3.22.0'), [
    'org.apache.maven.plugins:maven-site-plugin:jar:3.22.0',
    'org.jsoup:jsoup:jar:1.23.2', 'org.apache.maven.doxia:doxia-core:jar:2.0.0',
  ]);
  assert.throws(() => realmCoordinates(log, 'maven-site-plugin', '3.12.1'), /Actual Maven class realm is absent/);
  assert.throws(() => realmCoordinates(log.split('\n')[0], 'maven-site-plugin', '3.22.0'), /no dependency list/);
});

// This disposable fixture invokes only local rendering/read-only dependency goals.
// It contains no distributionManagement, deploy, site:run, Docker or HTTP server.
test('CI exercises Site help/rendering and Dependency tree/resolve-plugins with the actual patched realm', {
  skip: !mavenEnabled, timeout: 600000,
}, t => {
  requirePinnedMaven();
  const directory = temporary(t, 'site-dependency-compatibility-');
  const escaped = path.relative(directory, path.join(root, 'pom.xml')).replaceAll('&', '&amp;').replaceAll('<', '&lt;');
  writeFileSync(path.join(directory, 'pom.xml'), `<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion><parent><groupId>io.github.akaryc1b.approval</groupId><artifactId>approval-platform</artifactId><version>\${revision}</version><relativePath>${escaped}</relativePath></parent>
  <artifactId>site-dependency-compatibility-fixture</artifactId><packaging>pom</packaging><name>Bounded Site fixture</name><properties><revision>0.1.0-SNAPSHOT</revision></properties>
  <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-site-plugin</artifactId></plugin><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-dependency-plugin</artifactId></plugin></plugins></build>
  <reporting><excludeDefaults>true</excludeDefaults></reporting></project>`);
  for (const folder of ['markdown', 'xdoc']) mkdirSync(path.join(directory, 'src/site', folder), { recursive: true });
  writeFileSync(path.join(directory, 'src/site/markdown/index.md'), '# Bounded Markdown marker\n\nA **strong marker** and [nested page](nested.html).\n\n<div data-marker="raw-html"><strong>Raw HTML marker &amp; entity</strong></div>\n');
  writeFileSync(path.join(directory, 'src/site/xdoc/nested.xml'), '<document><properties><title>Nested XML fixture</title></properties><body><section name="Bounded nested XML"><p>' + '<span>'.repeat(32) + 'Nested XML marker' + '</span>'.repeat(32) + '</p></section></body></document>');
  const help = maven(['-N', '-X', 'org.apache.maven.plugins:maven-site-plugin:3.22.0:help', '-Ddetail=true', '-Dgoal=site']);
  assert.match(help, /site:site|site:3\.22\.0:help/);
  const coordinates = realmCoordinates(help, 'maven-site-plugin', '3.22.0');
  assert.deepEqual(coordinates.filter(item => item.startsWith('org.jsoup:jsoup:')), ['org.jsoup:jsoup:jar:1.23.2']);
  const modelFile = path.join(directory, 'effective-pom.xml');
  maven(['-N', 'org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom', `-Doutput=${modelFile}`], directory);
  const model = readFileSync(modelFile, 'utf8');
  const inheritedSite = [...model.matchAll(/<plugin>\s*(?:<groupId>org\.apache\.maven\.plugins<\/groupId>\s*)?<artifactId>maven-site-plugin<\/artifactId>[\s\S]*?<\/plugin>/g)];
  assert.equal(inheritedSite.length, 2, 'Fixture must inherit both managed and active Site declarations from candidate source');
  for (const [plugin] of inheritedSite) {
    assert.match(plugin, /<version>3\.22\.0<\/version>/);
    assert.match(plugin, /<artifactId>jsoup<\/artifactId>\s*<version>1\.23\.2<\/version>/);
  }
  const render = maven(['-N', '-X', 'org.apache.maven.plugins:maven-site-plugin:3.22.0:site', '-DgenerateReports=false'], directory);
  assert.deepEqual(realmCoordinates(render, 'maven-site-plugin', '3.22.0').filter(item => item.startsWith('org.jsoup:jsoup:')), ['org.jsoup:jsoup:jar:1.23.2']);
  const index = readFileSync(path.join(directory, 'target/site/index.html'), 'utf8');
  const nested = readFileSync(path.join(directory, 'target/site/nested.html'), 'utf8');
  assert.match(index, /Bounded Markdown marker/);
  assert.match(index, /<strong>strong marker<\/strong>/);
  assert.match(index, /<div><strong>Raw HTML marker &amp; entity<\/strong><\/div>/);
  assert.match(nested, /Nested XML marker/);
  const tree = path.join(directory, 'tree.json');
  const plugins = path.join(directory, 'plugins.txt');
  maven(['-N', 'org.apache.maven.plugins:maven-dependency-plugin:3.11.0:tree', '-DoutputType=json', `-DoutputFile=${tree}`], directory);
  assert.equal(JSON.parse(readFileSync(tree, 'utf8')).artifactId, 'site-dependency-compatibility-fixture');
  maven(['-N', 'org.apache.maven.plugins:maven-dependency-plugin:3.11.0:resolve-plugins', `-DoutputFile=${plugins}`], directory);
  const report = readFileSync(plugins, 'utf8');
  assert.match(report, /org\.apache\.maven\.plugins:maven-site-plugin:jar:3\.22\.0/);
  assert.match(report, /org\.apache\.maven\.plugins:maven-dependency-plugin:jar:3\.11\.0/);
  assert.match(report, /org\.jsoup:jsoup:jar:1\.23\.2/);
  const repository = process.env.M6_PR_E_E2_MAVEN_REPOSITORY ?? path.join(homedir(), '.m2/repository');
  const jars = coordinates.map(coordinate => {
    const parts = coordinate.split(':');
    assert.equal(parts.length, 4, `Unexpected plugin JAR coordinate: ${coordinate}`);
    const [group, name, type, version] = parts;
    assert.equal(type, 'jar');
    const file = path.join(repository, ...group.split('.'), name, version, `${name}-${version}.jar`);
    assert.ok(existsSync(file), `Resolved plugin JAR is unavailable: ${file}`);
    return file;
  });
  const classpath = jars.join(path.delimiter);
  const compile = spawnSync('javac', ['-cp', classpath, '-d', directory, path.join(root, 'scripts/tests/fixtures/SitePluginJsoupProbe.java')], { encoding: 'utf8', timeout: 30000 });
  assert.equal(compile.status, 0, compile.stderr || compile.stdout);
  const jsoup = jars.find(file => path.basename(file) === 'jsoup-1.23.2.jar');
  const probe = spawnSync('java', ['-Xmx96m', '-cp', `${directory}${path.delimiter}${classpath}`, 'SitePluginJsoupProbe', jsoup], { encoding: 'utf8', timeout: 30000 });
  assert.equal(probe.status, 0, probe.stderr || probe.stdout);
  assert.match(probe.stdout, /PROBE_COMPLETE/);
  console.log(probe.stdout.trim());
  console.log('MAVEN_COMPATIBILITY_COMPLETE: Site 3.22.0 help/site; Dependency 3.11.0 tree/resolve-plugins; root and fixture Site realms contain only jsoup 1.23.2.');
});
