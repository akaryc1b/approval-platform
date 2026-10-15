import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { pathToFileURL } from 'node:url';
import test from 'node:test';
const sha = value => createHash('sha256').update(value).digest('hex');
const prior = readFileSync(new URL('../../docs/operations/assembly-plexus-evidence/candidate-root-pom.xml', import.meta.url));
const current = readFileSync(new URL('../../pom.xml', import.meta.url));
const expected = '26f93ea126fad31dad2646d84ca6945b68e68c277396bf37cf4f9cf815a11629';
async function fixture(t, source = current) {
  const root = mkdtempSync(join(tmpdir(), 'jar-source-view-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  for (const directory of ['scripts/security', 'docs/operations/assembly-plexus-evidence']) mkdirSync(join(root, directory), { recursive: true });
  const module = join(root, 'scripts/security/jar-plugin-source-continuation.mjs');
  copyFileSync(new URL('../security/jar-plugin-source-continuation.mjs', import.meta.url), module);
  const archive = join(root, 'docs/operations/assembly-plexus-evidence/candidate-root-pom.xml');
  writeFileSync(archive, prior); writeFileSync(join(root, 'pom.xml'), source);
  const { readPreservedAssemblySource: read } = await import(pathToFileURL(module));
  assert.deepEqual(read('pom.xml', expected), prior, 'pristine source projection succeeds before mutation');
  return { read, root, archive };
}
for (const [name, source] of [['accepted Assembly', prior], ['current JAR', current]]) test(`JAR source continuation preserves exact ${name} bytes`, async t => {
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
  assert.throws(() => f.read('pom.xml', '0'.repeat(64)), /historical source byte digest mismatch/);
  for (const path of ['', '/pom.xml', '../pom.xml', './pom.xml', 'a//pom.xml', 'a/../pom.xml', 'a\\pom.xml'])
    assert.throws(() => f.read(path, expected), /safe relative source path/);
});
