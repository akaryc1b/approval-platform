import assert from 'node:assert/strict';
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = fileURLToPath(new URL('../../', import.meta.url));
const checker = path.join(root, 'scripts/ci/verify-compiler-plugin-baseline.py');
const source = readFileSync(path.join(root, 'pom.xml'), 'utf8');
const list = spawnSync('git', ['ls-files', '--', 'pom.xml', '**/pom.xml'], { cwd: root, encoding: 'utf8' });
assert.equal(list.status, 0); const poms = list.stdout.trim().split('\n'); assert.equal(poms.length, 28);
const pattern = /<plugin>\s*<groupId>org\.apache\.maven\.plugins<\/groupId>\s*<artifactId>maven-compiler-plugin<\/artifactId>[\s\S]*?<\/plugin>/;
const plugin = source.match(pattern)[0], dependency = plugin.match(/<dependency>[\s\S]*?<\/dependency>/)[0];
const check = directory => spawnSync('python3', ['-B', checker, `--root=${directory}`], { encoding: 'utf8' });
test('Compiler addition preserves all source Release/Clean gates and independent overlays', () => {
  const result = check(root); assert.equal(result.status, 0, result.stderr);
});
for (const [name, mutate] of [
  ['missing override', p => p.replace(dependency, '')],
  ['IO downgrade', p => p.replace('<maven.compiler.commons-io.version>2.20.0', '<maven.compiler.commons-io.version>2.11.0')],
  ['Compiler upgrade', p => p.replace('<maven.compiler.version>3.14.0', '<maven.compiler.version>3.15.0')],
  ['aliased owner pin', p => p.replace('${maven.compiler.commons-io.version}', '${maven.clean.commons-io.version}')],
  ['literal owner pin', p => p.replace('${maven.compiler.commons-io.version}', '2.20.0')],
  ['duplicate property', p => p.replace('</properties>', '<maven.compiler.commons-io.version>2.20.0</maven.compiler.commons-io.version></properties>')],
  ['duplicate owner', p => p.replace(plugin, plugin + plugin)],
  ['duplicate override', p => p.replace(plugin, plugin.replace(dependency, dependency + dependency))],
  ['global IO', p => p.replace('</project>', `<dependencies>${dependency}</dependencies></project>`)],
  ['extra realm dependency', p => p.replace(plugin, plugin.replace('</dependencies>', '<dependency><groupId>other</groupId><artifactId>extra</artifactId><version>1</version></dependency></dependencies>'))],
  ['scope change', p => p.replace(plugin, plugin.replace('</dependency>', '<scope>runtime</scope></dependency>'))],
  ['exclusion', p => p.replace(plugin, plugin.replace('</dependency>', '<exclusions/></dependency>'))],
  ['forked compiler', p => p.replace(plugin, plugin.replace('</configuration>', '<fork>true</fork></configuration>'))],
  ['release change', p => p.replace(plugin, plugin.replace('${java.version}', '17'))],
  ['parameter metadata disabled', p => p.replace(plugin, plugin.replace('<parameters>true', '<parameters>false'))],
  ['warning suppression', p => p.replace(plugin, plugin.replace('<showWarnings>true', '<showWarnings>false'))],
  ['profile override', p => p.replace('<profiles>', `<profiles><profile><id>compiler-override</id><build><plugins>${plugin}</plugins></build></profile>`)],
]) test(`Compiler source rejects ${name}`, t => {
  const directory = mkdtempSync(path.join(tmpdir(), 'compiler-source-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  for (const relative of poms) {
    const destination = path.join(directory, relative); mkdirSync(path.dirname(destination), { recursive: true });
    copyFileSync(path.join(root, relative), destination);
  }
  const changed = mutate(source); assert.notEqual(changed, source);
  writeFileSync(path.join(directory, 'pom.xml'), changed);
  const result = check(directory); assert.notEqual(result.status, 0); assert.match(result.stderr, /ValueError:/);
});

test('Compiler live runner defaults to a nonexecuting bounded plan', () => {
  const result = spawnSync('python3', ['-B', path.join(root, 'scripts/tests/fixtures/run_compiler_live_compatibility.py')], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr); const plan = JSON.parse(result.stdout);
  assert.equal(plan.execution, 'NOT_RUN'); assert.equal(plan.offlineLocally, true);
  assert.deepEqual(plan.goals, ['org.apache.maven.plugins:maven-compiler-plugin:compile', 'org.apache.maven.plugins:maven-compiler-plugin:testCompile']);
});
