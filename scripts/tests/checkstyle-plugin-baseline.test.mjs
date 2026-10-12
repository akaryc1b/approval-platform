import assert from 'node:assert/strict';
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = fileURLToPath(new URL('../../', import.meta.url));
const checker = path.join(root, 'scripts/ci/verify-checkstyle-plugin-baseline.py');
const contract = path.join(root, 'scripts/ci/checkstyle_plugin_contract.py');
const source = readFileSync(path.join(root, 'pom.xml'), 'utf8');
const poms = JSON.parse(readFileSync(path.join(root, 'docs/operations/compiler-plugin-evidence/source-witness.json'))).sourcePomFiles.map(row => row.path);
assert.equal(poms.length, 28);
const pattern = /<plugin>\s*<groupId>org\.apache\.maven\.plugins<\/groupId>\s*<artifactId>maven-checkstyle-plugin<\/artifactId>[\s\S]*?<\/plugin>/;
const plugin = source.match(pattern)[0], dependency = plugin.match(/<dependency>[\s\S]*?<\/dependency>/)[0];
const property = '<maven.checkstyle.commons-lang3.version>3.18.0</maven.checkstyle.commons-lang3.version>';
const invoke = args => spawnSync('/usr/bin/python3', ['-I', '-S', '-B', ...args], {
  encoding: 'utf8', timeout: 20000, maxBuffer: 32 * 1024 * 1024,
  env: { PATH: '/usr/bin:/bin', LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8', TZ: 'UTC' },
});
const check = directory => invoke([checker, `--root=${directory}`]);
function temporary(t, name) {
  const directory = mkdtempSync(path.join(tmpdir(), name));
  t.after(() => rmSync(directory, { recursive: true, force: true })); return directory;
}
function fixture(t, mutate = x => x, fileMutation = () => {}) {
  const directory = temporary(t, 'checkstyle-source-');
  for (const relative of [...poms, 'config/checkstyle/checkstyle.xml']) {
    mkdirSync(path.dirname(path.join(directory, relative)), { recursive: true });
    copyFileSync(path.join(root, relative), path.join(directory, relative));
  }
  writeFileSync(path.join(directory, 'pom.xml'), mutate(source)); fileMutation(directory); return directory;
}
test('Checkstyle source accepts exact root-only Lang substitution and retains all historical source gates', t => {
  const result = check(fixture(t)); assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /26 projects and both overlays/);
});
for (const [name, mutate] of [
  ['missing property', p => p.replace(property, '')],
  ['duplicate property', p => p.replace(property, property.repeat(2))],
  ['Lang downgrade', p => p.replace(property, property.replace('3.18.0', '3.17.0'))],
  ['missing override', p => p.replace(dependency, '')],
  ['duplicate override', p => p.replace(dependency, dependency.repeat(2))],
  ['literal dependency version', p => p.replace('${maven.checkstyle.commons-lang3.version}', '3.18.0')],
  ['foreign dependency group', p => p.replace(dependency, dependency.replace('org.apache.commons', 'foreign.commons'))],
  ['extra dependency scope', p => p.replace(dependency, dependency.replace('</dependency>', '<scope>compile</scope></dependency>'))],
  ['additional plugin', p => p.replace(plugin, plugin.repeat(2))],
  ['plugin version drift', p => p.replace('<maven.checkstyle.version>3.6.0', '<maven.checkstyle.version>3.5.0')],
  ['rules path drift', p => p.replace('/config/checkstyle/checkstyle.xml', '/config/checkstyle/other.xml')],
  ['fail-open configuration', p => p.replace('<failOnViolation>true', '<failOnViolation>false')],
  ['phase drift', p => p.replace(plugin, plugin.replace('<phase>validate</phase>', '<phase>verify</phase>'))],
  ['extra execution', p => p.replace(plugin, plugin.replace('</executions>', '<execution><id>extra</id><goals><goal>help</goal></goals></execution></executions>'))],
  ['property reference outside owner', p => p.replace('<name>Approval Platform</name>', '<name>${maven.checkstyle.commons-lang3.version}</name>')],
  ['extra behavior property', p => p.replace('</properties>', '<checkstyle.skip>true</checkstyle.skip></properties>')],
  ['runtime dependency', p => p.replace('</project>', `<dependencies>${dependency}</dependencies></project>`) ],
  ['unrelated retained compiler pin', p => p.replace('<maven.compiler.version>3.14.0', '<maven.compiler.version>3.13.0')],
  ['other byte changed', p => p + '\n'],
]) test(`Checkstyle source rejects ${name}`, t => {
  const changed = mutate(source); assert.notEqual(changed, source, 'fixture must mutate source');
  const result = check(fixture(t, () => changed)); assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Checkstyle|Compiler|Release|pin drift|duplicate property|duplicate element|retained plugin/);
});
for (const relative of [poms.find(p => p.startsWith('server-modules/') && p !== 'server-modules/pom.xml'),
  ...poms.filter(p => p.includes('/overlay/')), 'config/checkstyle/checkstyle.xml'])
  test(`Checkstyle source rejects byte drift in ${relative}`, t => {
    const result = check(fixture(t, x => x, directory => {
      const file = path.join(directory, relative); writeFileSync(file, readFileSync(file, 'utf8') + '\n');
    })); assert.notEqual(result.status, 0); assert.match(result.stderr, /Checkstyle/);
  });

// Synthetic parser fixtures only. They demonstrate order handling and rejection;
// they cannot serve as a retained Maven model capture or compatibility receipt.
const generate = String.raw`
import copy, json, runpy, sys
from pathlib import Path
import xml.etree.ElementTree as E
c = runpy.run_path(sys.argv[1]); root = Path(sys.argv[2]); ns = c['NS']; prefix = c['P']
release = runpy.run_path(str(root/'scripts/ci/verify-release-plugin-baseline.py'))
projects = release['reactor'](root)
result = {}
for label, file, base in [('before', root/'docs/operations/compiler-plugin-evidence/candidate-root-pom.xml', '/fixture/baseline'), ('after', root/'pom.xml', '/fixture/candidate')]:
    template = E.parse(file).getroot(); props = template.find('m:properties', ns)
    pins = {p.tag.removeprefix(prefix): p.text for p in props}; pins['maven.multiModuleProjectDirectory'] = base
    document = E.Element(prefix+'projects')
    for _, source in reversed(projects):
        p = E.SubElement(document, prefix+'project')
        for key, value in [('groupId', 'io.github.akaryc1b.approval'), ('artifactId', release['value'](source, 'artifactId')), ('version', '1.0.0-SNAPSHOT')]:
            E.SubElement(p, prefix+key).text = value
        p.append(copy.deepcopy(props)); build = copy.deepcopy(template.find('m:build', ns)); p.append(build)
        checkstyle = next(x for x in build.findall('m:plugins/m:plugin', ns) if c['targeted'](x))
        checkstyle.remove(checkstyle.find('m:groupId', ns))
        execution = checkstyle.find('m:executions/m:execution', ns)
        execution.append(copy.deepcopy(checkstyle.find('m:configuration', ns)))
        dependency = checkstyle.find('m:dependencies/m:dependency', ns)
        if dependency is not None: E.SubElement(dependency, prefix+'scope').text = 'compile'
    raw = E.tostring(document, encoding='unicode')
    for key, value in pins.items(): raw = raw.replace('$'+'{'+key+'}', value)
    result[label] = raw
print(json.dumps(result))
`;
const generated = invoke(['-c', generate, contract, root]);
assert.equal(generated.status, 0, generated.stderr);
const models = JSON.parse(generated.stdout);
function compareModels(t, after = models.after, before = models.before) {
  const directory = temporary(t, 'checkstyle-models-');
  const candidate = path.join(directory, 'candidate.xml'), baseline = path.join(directory, 'baseline.xml');
  writeFileSync(candidate, after); writeFileSync(baseline, before);
  return invoke(['-c', 'import runpy,sys; from pathlib import Path; c=runpy.run_path(sys.argv[1]); print(c["verify_effective"](Path("/fixture/candidate"),Path(sys.argv[2]),Path(sys.argv[3]),Path("/fixture/baseline")))', contract, candidate, baseline]);
}
test('paired models accept 26 identities in emitted order different from source traversal', t => {
  const result = compareModels(t); assert.equal(result.status, 0, result.stderr); assert.equal(result.stdout.trim(), '26');
});
for (const [name, alter] of [
  ['missing model', x => x.replace(/<ns0:project>[\s\S]*?<\/ns0:project>/, '')],
  ['duplicate model', x => x.replace('</ns0:projects>', x.match(/<ns0:project>[\s\S]*?<\/ns0:project>/)[0] + '</ns0:projects>')],
  ['wrong model identity', x => x.replace('approval-generic-spring-host-example', 'foreign')],
  ['Lang downgrade', x => x.replace('>3.18.0<', '>3.17.0<')],
  ['wrong effective scope', x => x.replace('<ns0:scope>compile</ns0:scope>', '<ns0:scope>runtime</ns0:scope>')],
  ['missing effective scope', x => x.replace('<ns0:scope>compile</ns0:scope>', '')],
  ['new behavior property', x => x.replace('</ns0:properties>', '<ns0:checkstyle.skip>true</ns0:checkstyle.skip></ns0:properties>')],
  ['retained execution phase', x => x.replace('<ns0:phase>validate</ns0:phase>', '<ns0:phase>verify</ns0:phase>')],
  ['retained rules path', x => x.replace('/config/checkstyle/checkstyle.xml', '/config/checkstyle/forged.xml')],
  ['retained failOnViolation', x => x.replace('<ns0:failOnViolation>true', '<ns0:failOnViolation>false')],
  ['retained compiler version', x => x.replace('<ns0:maven.compiler.version>3.14.0', '<ns0:maven.compiler.version>3.13.0')],
  ['duplicate Lang property', x => x.replace('</ns0:properties>', '<ns0:maven.checkstyle.commons-lang3.version>3.18.0</ns0:maven.checkstyle.commons-lang3.version></ns0:properties>')],
  ['missing dependency', x => x.replace(/<ns0:dependency>\s*<ns0:groupId>org.apache.commons<\/ns0:groupId>\s*<ns0:artifactId>commons-lang3<\/ns0:artifactId>[\s\S]*?<\/ns0:dependency>/, '')],
]) test(`paired Checkstyle models reject ${name}`, t => {
  const changed = alter(models.after); assert.notEqual(changed, models.after, 'fixture must mutate models');
  const result = compareModels(t, changed); assert.notEqual(result.status, 0); assert.match(result.stderr, /Checkstyle/);
});
test('paired models reject reordered candidate without reordering actual baseline', t => {
  const rows = models.after.match(/<ns0:project>[\s\S]*?<\/ns0:project>/g);
  const changed = models.after.replace(rows.join(''), [...rows].reverse().join(''));
  assert.notEqual(changed, models.after); assert.notEqual(compareModels(t, changed).status, 0);
});
test('effective-model verification fails closed when no paired actual baseline is supplied', () => {
  const result = invoke([checker, `--effective-pom=${path.join(root, 'pom.xml')}`]);
  assert.notEqual(result.status, 0); assert.match(result.stderr, /comparison requires candidate, baseline and baseline root/);
});
