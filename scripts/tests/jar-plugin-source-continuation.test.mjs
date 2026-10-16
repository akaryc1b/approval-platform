import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { pathToFileURL } from 'node:url';
import test from 'node:test';
const sha = value => createHash('sha256').update(value).digest('hex');
const prior = readFileSync(new URL('../../docs/operations/assembly-plexus-evidence/candidate-root-pom.xml', import.meta.url));
const jar = readFileSync(new URL('../../docs/operations/jar-plugin-evidence/candidate-root-pom.xml', import.meta.url));
const current = readFileSync(new URL('../../pom.xml', import.meta.url));
const expected = '26f93ea126fad31dad2646d84ca6945b68e68c277396bf37cf4f9cf815a11629';
const jarExpected = '92694a65be5d42493864470184469b465df8107bfd98388b8b0db15292c54c7f';
async function fixture(t, source = current) {
  const root = mkdtempSync(join(tmpdir(), 'jar-source-view-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  for (const directory of ['scripts/security', 'docs/operations/assembly-plexus-evidence', 'docs/operations/jar-plugin-evidence'])
    mkdirSync(join(root, directory), { recursive: true });
  const module = join(root, 'scripts/security/jar-plugin-source-continuation.mjs');
  copyFileSync(new URL('../security/jar-plugin-source-continuation.mjs', import.meta.url), module);
  const archive = join(root, 'docs/operations/assembly-plexus-evidence/candidate-root-pom.xml');
  const jarArchive = join(root, 'docs/operations/jar-plugin-evidence/candidate-root-pom.xml');
  writeFileSync(archive, prior); writeFileSync(join(root, 'pom.xml'), source);
  writeFileSync(jarArchive, jar);
  const { readPreservedAssemblySource: read, readPreservedJarSource: readJar } = await import(pathToFileURL(module));
  assert.deepEqual(read('pom.xml', expected), prior, 'pristine source projection succeeds before mutation');
  return { read, readJar, root, archive, jarArchive };
}
for (const [name, source] of [['accepted Assembly', prior], ['accepted JAR', jar], ['current Lang', current]]) test(`JAR source continuation preserves exact ${name} bytes`, async t => {
  const f = await fixture(t, source); assert.equal(sha(f.read('pom.xml', expected)), expected);
  writeFileSync(join(f.root, 'pom.xml'), Buffer.concat([source, Buffer.from('\n')]));
  assert.throws(() => f.read('pom.xml', expected), /JAR continuation historical source byte digest mismatch/);
  writeFileSync(join(f.root, 'pom.xml'), source); assert.deepEqual(f.read('pom.xml', expected), prior);
});
test('JAR source continuation checks the actual retained root on every read', async t => {
  const f = await fixture(t); writeFileSync(f.archive, Buffer.concat([prior, Buffer.from('\n')]));
  assert.throws(() => f.read('pom.xml', expected), /JAR continuation retained source byte digest mismatch/);
  writeFileSync(f.archive, prior); assert.deepEqual(f.read('pom.xml', expected), prior);
});
test('JAR source continuation rejects foreign expected bytes and unsafe paths', async t => {
  const f = await fixture(t);
  for (const read of [f.read, f.readJar]) {
    assert.throws(() => read('pom.xml', '0'.repeat(64)), /historical source byte digest mismatch/);
    for (const path of ['', '/pom.xml', '../pom.xml', './pom.xml', 'a//pom.xml', 'a/../pom.xml', 'a\\pom.xml'])
      assert.throws(() => read(path, expected), /safe relative source path/);
  }
});

test('Lang continuation independently binds the JAR intermediate archive on every read', async t => {
  const f = await fixture(t);
  assert.deepEqual(f.readJar('pom.xml', jarExpected), jar);
  for (const bytes of [prior, Buffer.concat([jar, Buffer.from('\n')])]) {
    writeFileSync(f.jarArchive, bytes);
    assert.throws(() => f.readJar('pom.xml', jarExpected), /retained Lang predecessor byte digest mismatch/);
    assert.throws(() => f.read('pom.xml', expected), /retained Lang predecessor byte digest mismatch/);
    writeFileSync(f.jarArchive, jar);
    assert.deepEqual(f.readJar('pom.xml', jarExpected), jar);
    assert.deepEqual(f.read('pom.xml', expected), prior);
  }
  writeFileSync(join(f.root, 'pom.xml'), jar);
  assert.deepEqual(f.readJar('pom.xml', jarExpected), jar);
  writeFileSync(join(f.root, 'pom.xml'), prior);
  assert.throws(() => f.readJar('pom.xml', jarExpected), /historical source byte digest mismatch/);
});

for (const [name, mutate] of [
  ['wrong Lang version', s => s.replace('<maven.enforcer.commons-lang3.version>3.18.0', '<maven.enforcer.commons-lang3.version>3.19.0')],
  ['missing owner override', s => s.replace('        <maven.assembly.commons-lang3.version>3.18.0</maven.assembly.commons-lang3.version>\n', '')],
  ['duplicate owner override', s => s.replace('<maven.enforcer.commons-lang3.version>3.18.0</maven.enforcer.commons-lang3.version>', '<maven.enforcer.commons-lang3.version>3.18.0</maven.enforcer.commons-lang3.version>\n        <maven.enforcer.commons-lang3.version>3.18.0</maven.enforcer.commons-lang3.version>')],
  ['unrelated owner change', s => s.replace('<maven.jar.version>3.5.1', '<maven.jar.version>3.5.0')],
]) test(`Lang continuation rejects ${name} even with matching rewritten archive`, async t => {
  const f = await fixture(t), changed = mutate(current.toString());
  assert.notEqual(changed, current.toString());
  writeFileSync(join(f.root, 'pom.xml'), changed); writeFileSync(f.jarArchive, changed);
  assert.throws(() => f.readJar('pom.xml', jarExpected), /historical source byte digest mismatch/);
  assert.throws(() => f.read('pom.xml', expected), /historical source byte digest mismatch/);
});

test('Lang continuation preserves historical JAR witness diagnostic and Gitleaks source readers', async () => {
  const { readJarPluginSourceWitness } = await import('../security/jar-plugin-graph-transition.mjs');
  const { readJarPluginDiagnostic, verifyJarPluginDiagnostic } = await import('./fixtures/jar-plugin-osv-diagnostic-verifier.mjs');
  const { gitleaksJarExecutionDigestFields } = await import('../security/m6-pr-e-e3-review-gitleaks-capture-hash.mjs');
  const witness = readJarPluginSourceWitness();
  assert.equal(witness.rootPom.rawSha256, jarExpected);
  assert.equal(witness.sourcePomFiles.find(row => row.path === 'pom.xml').candidateSha256, jarExpected);
  assert.deepEqual(verifyJarPluginDiagnostic(readJarPluginDiagnostic()),
    { targets: 467, findings: 10, zeroFindingTargets: 458, removed: 1, added: 0, changed: 0, scannerCliExecuted: false, releaseBlocked: true });
  const fields = gitleaksJarExecutionDigestFields();
  assert.equal(fields.length, 121);
  assert.deepEqual([...new Set(fields.map(field => field.path))].sort(),
    ['docs/operations/jar-plugin-evidence/compatibility-local-seal.json', 'docs/operations/jar-plugin-evidence/model-runtime-inputs.json']);
});
