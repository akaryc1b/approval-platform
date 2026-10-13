import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { retainedBootUtilitiesSource } from './fixtures/boot-utilities-retained-source.mjs';
import { retainedJacocoAntRunSource } from './fixtures/jacoco-antrun-retained-source.mjs';

const root = retainedJacocoAntRunSource(fileURLToPath(new URL('../../', import.meta.url))).directory;
const adapter = path.join(root, 'scripts/ci/boot_utilities_contract.py');
const retained = retainedBootUtilitiesSource(root);
const raw = readFileSync(path.join(root, 'pom.xml'), 'utf8');
const addition = "                        <dependency>\n                            <groupId>org.codehaus.plexus</groupId>\n                            <artifactId>plexus-utils</artifactId>\n                            <version>3.6.1</version>\n                        </dependency>\n                        <dependency>\n                            <groupId>org.apache.commons</groupId>\n                            <artifactId>commons-lang3</artifactId>\n                            <version>3.18.0</version>\n                        </dependency>\n";
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const python = args => spawnSync('/usr/bin/python3', ['-I', '-S', '-B', ...args], {
  encoding: 'utf8', timeout: 20000, maxBuffer: 32 * 1024 * 1024,
  env: { PATH: '/usr/bin:/bin', LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8', TZ: 'UTC' },
});
function temporary(t, prefix) {
  const directory = mkdtempSync(path.join(tmpdir(), prefix));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  return directory;
}
function rejected(result) {
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /ValueError:|ParseError:/);
}

test('Boot source reverses exactly two private utility overrides and preserves all historical contract bytes', () => {
  const result = python([adapter, `--root=${root}`]);
  assert.equal(result.status, 0, result.stderr);
  const receipt = JSON.parse(result.stdout);
  assert.equal(receipt.status, 'PASSED_BOOT_UTILITIES_SOURCE');
  assert.equal(receipt.sourcePomCount, 28);
  assert.equal(receipt.transportCompatibilityClaimed, false);
  const projection = retained.projection;
  assert.deepEqual(Object.keys(projection.suppliedSourceHashes).filter(name =>
    projection.suppliedSourceHashes[name] !== projection.retainedSourceHashes[name]), ['pom.xml']);
  assert.equal(raw.replace(addition, ''), readFileSync(path.join(retained.directory, 'pom.xml'), 'utf8'));
  for (const [name, expected] of Object.entries(projection.retainedSourceHashes)) {
    assert.equal(hash(readFileSync(path.join(retained.directory, name))), expected, name);
  }
});

for (const [name, relative, mutate] of [
  ['missing private override', 'pom.xml', text => text.replace(addition, '')],
  ['old Plexus version', 'pom.xml', text => text.replace('<version>3.6.1</version>', '<version>3.5.1</version>')],
  ['old Lang version', 'pom.xml', text => text.replace('<version>3.18.0</version>', '<version>3.16.0</version>')],
  ['explicit source scope', 'pom.xml', text => text.replace(addition, addition.replace('</dependency>', '<scope>compile</scope></dependency>'))],
  ['duplicate utility addition', 'pom.xml', text => text.replace(addition, addition + addition)],
  ['retained HTTP change', 'pom.xml', text => text.replace('<version>5.6.3</version>', '<version>5.6.2</version>')],
  ['runtime dependency escape', 'pom.xml', text => text.replace(addition, '').replace('</project>', `<dependencies>${addition}</dependencies></project>`)],
  ['shared Boot BOM expansion', 'pom.xml', text => text.replace('<spring-boot.version>4.0.8', '<spring-boot.version>4.0.9')],
  ['retained Jackson change', 'pom.xml', text => text.replace('<jackson3-bom.version>3.1.7', '<jackson3-bom.version>3.1.8')],
  ['overlay byte change', 'integrations/ruoyi5-host-starter/overlay/ruoyi-extend/ruoyi-approval-host-starter/pom.xml', text => text + '\n'],
  ['historical contract rewrite', 'scripts/ci/verify-release-plugin-baseline.py', text => text + '\n'],
  ['historical HTTP contract rewrite', 'scripts/ci/boot_http_contract.py', text => text + '\n'],
]) test(`Boot source guard rejects ${name}`, t => {
  const directory = temporary(t, 'boot-utilities-invalid-source-');
  for (const file of Object.keys(retained.projection.suppliedSourceHashes)) {
    const destination = path.join(directory, file); mkdirSync(path.dirname(destination), { recursive: true });
    copyFileSync(path.join(root, file), destination);
  }
  const file = path.join(directory, relative); const before = readFileSync(file, 'utf8'); const after = mutate(before);
  assert.notEqual(after, before); writeFileSync(file, after);
  rejected(python([adapter, `--root=${directory}`]));
});

// Synthetic parser fixtures only. Actual paired Maven XMLs are retained separately.
const generate = String.raw`
import copy,json,runpy,sys
from pathlib import Path
import xml.etree.ElementTree as ET
current,prior=Path(sys.argv[1]),Path(sys.argv[2]);ns={'m':'http://maven.apache.org/POM/4.0.0'};p='{'+ns['m']+'}'
clean=runpy.run_path(str(current/'scripts/ci/clean_plugin_contract.py'))
result={}
for label,source in [('before',prior),('after',current)]:
    template=ET.parse(source/'pom.xml').getroot();props=template.find('m:properties',ns)
    pins={n.tag.removeprefix(p):n.text for n in props};pins['maven.multiModuleProjectDirectory']=str(source)
    document=ET.Element(p+'projects')
    for artifact in clean['REACTOR'].values():
        model=ET.SubElement(document,p+'project')
        for key,value in [('groupId',clean['PROJECT_GROUP']),('artifactId',artifact),('version','0.1.0-SNAPSHOT')]:ET.SubElement(model,p+key).text=value
        model.append(copy.deepcopy(props));model.append(copy.deepcopy(template.find('m:dependencyManagement',ns)))
        build=copy.deepcopy(template.find('m:build',ns));model.append(build)
        managed=next(x for x in build.findall('m:pluginManagement/m:plugins/m:plugin',ns) if x.findtext('m:artifactId',namespaces=ns)=='spring-boot-maven-plugin')
        if artifact in ('approval-server','approval-generic-spring-host-example'):
            for d in managed.findall('m:dependencies/m:dependency',ns):ET.SubElement(d,p+'scope').text='compile'
            build.find('m:plugins',ns).append(copy.deepcopy(managed))
    raw=ET.tostring(document,encoding='unicode')
    for key,value in pins.items():raw=raw.replace('$'+'{'+key+'}',value)
    result[label]=raw
print(json.dumps(result))
`;
const generated = python(['-c', generate, root, retained.directory]);
assert.equal(generated.status, 0, generated.stderr);
const models = JSON.parse(generated.stdout);
function compare(t, current = models.after, prior = models.before) {
  const directory = temporary(t, 'boot-utilities-model-guards-');
  const candidate = path.join(directory, 'candidate.xml'); const baseline = path.join(directory, 'baseline.xml');
  writeFileSync(candidate, current); writeFileSync(baseline, prior);
  return python([adapter, `--root=${root}`, `--effective-pom=${candidate}`,
    `--baseline-effective-pom=${baseline}`, `--baseline-root=${retained.directory}`]);
}
test('Boot model parser accepts only the paired26 synthetic owner shapes without claiming actual capture', t => {
  const result = compare(t); assert.equal(result.status, 0, result.stderr);
  const receipt = JSON.parse(result.stdout);
  assert.equal(receipt.modelCount, 26); assert.equal(receipt.activeBootModelCount, 2);
  assert.equal(receipt.allOtherModelFieldsUnchanged, true); assert.equal(receipt.canonicalCiEvidence, false);
});
for (const [name, mutate] of [
  ['wrong Plexus version', text => text.replace('>3.6.1<', '>3.5.1<')],
  ['wrong Lang version', text => text.replace('>3.18.0<', '>3.16.0<')],
  ['retained Client version', text => text.replace('>5.6.3<', '>5.6.2<')],
  ['extra inactive managed scope', text => text.replace(/(<ns0:artifactId>plexus-utils<\/ns0:artifactId>\s*<ns0:version>3.6.1<\/ns0:version>)/, '$1<ns0:scope>compile</ns0:scope>')],
  ['retained shared Boot version', text => text.replace('>4.0.8<', '>4.0.9<')],
  ['runtime BOM drift', text => text.replace(/(<ns0:artifactId>spring-boot-dependencies<\/ns0:artifactId>\s*<ns0:version>)4.0.8/, (_match, prefix) => `${prefix}4.0.9`)],
]) test(`Boot paired model guard rejects ${name}`, t => {
  const changed = mutate(models.after); assert.notEqual(changed, models.after);
  rejected(compare(t, changed));
});
