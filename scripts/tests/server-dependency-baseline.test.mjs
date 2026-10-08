import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { requirePinnedMaven } from '../ci/maven-toolchain.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const checker = path.join(root, 'scripts/ci/verify-server-dependency-baseline.py');
const source = readFileSync(path.join(root, 'pom.xml'), 'utf8');
const host = readFileSync(path.join(root, 'integrations/host-sdk/pom.xml'), 'utf8');
const run = directory => spawnSync('python3', [checker, `--root=${directory}`], { encoding: 'utf8' });
const dependency = (group, name) => new RegExp(`            <dependency>\\s*<groupId>${group.replaceAll('.', '\\.')}<\\/groupId>\\s*<artifactId>${name}<\\/artifactId>[\\s\\S]*?<\\/dependency>\\n`);

// This is a source contract, not a reconstructed Maven graph or a vulnerability disposition.
test('candidate uses explicit BOM precedence and aligned embedded Tomcat with preserved toolchain pins', () => {
  const result = run(root);
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /not resolved graph or scanner evidence/);
});

for (const [name, mutate] of [
  ['property-only Jackson override', pom => pom.replace(dependency('com.fasterxml.jackson', 'jackson-bom'), '')],
  ['Jackson import after Boot', pom => {
    const entry = pom.match(dependency('tools.jackson', 'jackson-bom'))[0];
    return pom.replace(entry, '').replace('</dependencies>', entry + '        </dependencies>');
  }],
  ['OTel import after Boot', pom => {
    const entry = pom.match(dependency('io.opentelemetry', 'opentelemetry-bom'))[0];
    return pom.replace(entry, '').replace('</dependencies>', entry + '        </dependencies>');
  }],
  ['missing Tomcat websocket', pom => pom.replace(dependency('org.apache.tomcat.embed', 'tomcat-embed-websocket'), '')],
  ['mixed Tomcat family', pom => pom.replace('<version>${tomcat-embed.version}</version>', '<version>11.0.24</version>')],
  ['duplicate Tomcat core', pom => pom.replace(dependency('org.apache.tomcat.embed', 'tomcat-embed-core'), entry => entry + entry)],
  ['independent Boot plugin', pom => pom.replace('<artifactId>spring-boot-maven-plugin</artifactId>\n                    <version>${spring-boot.version}</version>', '<artifactId>spring-boot-maven-plugin</artifactId>\n                    <version>4.0.2</version>')],
  ['pgjdbc downgrade', pom => pom.replace('<version>42.7.13</version>', '<version>42.7.9</version>')],
  ['Java change', pom => pom.replace('<java.version>21</java.version>', '<java.version>17</java.version>')],
  ['Flowable change', pom => pom.replace('<flowable.version>8.0.0</flowable.version>', '<flowable.version>7.2.0</flowable.version>')],
]) test(`source contract rejects ${name}`, t => {
  const directory = mkdtempSync(path.join(tmpdir(), 'server-dependency-contract-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  mkdirSync(path.join(directory, 'integrations/host-sdk'), { recursive: true });
  writeFileSync(path.join(directory, 'integrations/host-sdk/pom.xml'), host);
  const changed = mutate(source);
  assert.notEqual(changed, source, 'mutation must actually change the source');
  writeFileSync(path.join(directory, 'pom.xml'), changed);
  const result = run(directory);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /ValueError:/);
});

// Deliberately minimal synthetic XML tests the checker only. It is never saved as Maven evidence.
const effectiveVersions = [
  ['org.springframework.boot:spring-boot', '4.0.8'],
  ['org.springframework.boot:spring-boot-starter-web', '4.0.8'],
  ['org.springframework:spring-core', '7.0.9'],
  ['org.springframework:spring-context', '7.0.9'],
  ['org.springframework:spring-webmvc', '7.0.9'],
  ['com.fasterxml.jackson.core:jackson-annotations', '2.21'],
  ['com.fasterxml.jackson.core:jackson-core', '2.21.7'],
  ['com.fasterxml.jackson.core:jackson-databind', '2.21.7'],
  ['com.fasterxml.jackson.datatype:jackson-datatype-jsr310', '2.21.7'],
  ['tools.jackson.core:jackson-core', '3.1.7'],
  ['tools.jackson.core:jackson-databind', '3.1.7'],
  ['org.flowable:flowable-engine', '8.0.0'],
  ['org.postgresql:postgresql', '42.7.13'],
  ['io.opentelemetry:opentelemetry-api', '1.62.0'],
  ['io.opentelemetry:opentelemetry-sdk', '1.62.0'],
  ['io.opentelemetry:opentelemetry-exporter-otlp', '1.62.0'],
  ...['core', 'el', 'websocket'].map(name => [`org.apache.tomcat.embed:tomcat-embed-${name}`, '11.0.26']),
];
function effectiveFixture(versions = effectiveVersions, pluginVersion = '4.0.8') {
  return `<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
    <dependencyManagement><dependencies>${versions.map(([id, version]) => {
      const [group, name] = id.split(':');
      return `<dependency><groupId>${group}</groupId><artifactId>${name}</artifactId><version>${version}</version></dependency>`;
    }).join('')}</dependencies></dependencyManagement><build><pluginManagement><plugins><plugin>
    <groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId><version>${pluginVersion}</version>
    </plugin></plugins></pluginManagement></build></project>`;
}
function checkEffective(t, xml) {
  const directory = mkdtempSync(path.join(tmpdir(), 'synthetic-effective-pom-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const file = path.join(directory, 'synthetic.xml');
  writeFileSync(file, xml);
  return spawnSync('python3', [checker, `--root=${root}`, `--effective-pom=${file}`], { encoding: 'utf8' });
}
test('effective-POM checker accepts a complete synthetic baseline', t => {
  const result = checkEffective(t, effectiveFixture());
  assert.equal(result.status, 0, result.stderr);
});
for (const [name, id, version] of [
  ['shared annotations mismatch', 'com.fasterxml.jackson.core:jackson-annotations', '2.20'],
  ['Jackson 2 downgrade', 'com.fasterxml.jackson.core:jackson-core', '2.21.5'],
  ['Jackson 3 downgrade', 'tools.jackson.core:jackson-databind', '3.1.5'],
  ['Tomcat websocket mismatch', 'org.apache.tomcat.embed:tomcat-embed-websocket', '11.0.24'],
  ['OTel losing precedence', 'io.opentelemetry:opentelemetry-sdk', '1.55.0'],
  ['unresolved interpolation', 'org.springframework:spring-core', '${spring-framework.version}'],
]) test(`effective-POM checker rejects ${name}`, t => {
  const changed = effectiveVersions.map(([key, current]) => [key, key === id ? version : current]);
  const result = checkEffective(t, effectiveFixture(changed));
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /effective dependency drift/);
});
test('effective-POM checker rejects missing and duplicate managed artifacts and a stale plugin', t => {
  for (const xml of [effectiveFixture(effectiveVersions.slice(1)),
    effectiveFixture([...effectiveVersions, effectiveVersions[0]]), effectiveFixture(effectiveVersions, '4.0.2')]) {
    const result = checkEffective(t, xml);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /ValueError:/);
  }
});
test('effective-POM checker distinguishes artifact classifiers without accepting a stale Jackson module', t => {
  const extra = '<dependency><groupId>org.springframework</groupId><artifactId>spring-core</artifactId><version>7.0.9</version><classifier>tests</classifier></dependency>';
  assert.equal(checkEffective(t, effectiveFixture().replace('</dependencies>', extra + '</dependencies>')).status, 0);
  const result = checkEffective(t, effectiveFixture([...effectiveVersions, ['com.fasterxml.jackson.dataformat:jackson-dataformat-xml', '2.20.2']]));
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /effective Jackson family mismatch/);
});

// CI inspects real Maven model output; synthetic fixtures above are never used here.
test('CI verifies actual Maven effective dependency management', {
  skip: process.env.GITHUB_ACTIONS !== 'true', timeout: 300000,
}, t => {
  requirePinnedMaven();
  const directory = mkdtempSync(path.join(tmpdir(), 'server-effective-pom-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const file = path.join(directory, 'effective-pom.xml');
  const maven = spawnSync('mvn', ['-B', '-ntp', '-N',
    'org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom', `-Doutput=${file}`], {
    cwd: root, encoding: 'utf8', timeout: 240000, maxBuffer: 8 * 1024 * 1024,
  });
  assert.equal(maven.status, 0, maven.stderr || maven.stdout);
  const result = spawnSync('python3', [checker, `--root=${root}`, `--effective-pom=${file}`], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  console.log(result.stdout.trim());
});
