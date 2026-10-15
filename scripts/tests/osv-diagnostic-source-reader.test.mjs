import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import test from 'node:test';

const scannerPath = 'scripts/security/m6-pr-e-e4-scan.mjs';
const helperPath = 'scripts/security/semgrep-image-pull.mjs';
const historicalHash = '04d601d05b22a50b8d6bf2888e480ffd1ed9be88ab55bd99b3c094d5e70b0873';
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const current = readFileSync(new URL('../../' + scannerPath, import.meta.url));
const helper = readFileSync(new URL('../../' + helperPath, import.meta.url));
const importLine = "import { pullSemgrepImage } from './semgrep-image-pull.mjs';\n";
const call = 'pullSemgrepImage(image,{env});';
const historical = Buffer.from(current.toString().replace(importLine, '')
  .replace(call, "run('docker',['pull','--quiet',image],{env,timeout:900000});"));
assert.equal(historical.length, 16606);
assert.equal(sha(historical), historicalHash, 'fixture reconstructs exact historical bytes');

async function fixture(t, source = current, includeHelper = true) {
  const root = mkdtempSync(join(tmpdir(), 'osv-diagnostic-source-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  mkdirSync(join(root, 'scripts/security'), { recursive: true });
  mkdirSync(join(root, 'scripts/tests/fixtures'), { recursive: true });
  const readerPath = join(root, 'scripts/tests/fixtures/osv-diagnostic-source-reader.mjs');
  copyFileSync(new URL('./fixtures/osv-diagnostic-source-reader.mjs', import.meta.url), readerPath);
  writeFileSync(join(root, scannerPath), source);
  if (includeHelper) writeFileSync(join(root, helperPath), helper);
  const { readOsvDiagnosticSource: read } = await import(pathToFileURL(readerPath));
  return { root, read, scanner: join(root, scannerPath), helper: join(root, helperPath) };
}

test('historical scanner is returned exactly without requiring a later pull helper', async t => {
  const f = await fixture(t, historical, false);
  assert.deepEqual(f.read(scannerPath, historicalHash), historical);
});

test('reviewed current scanner and helper project only to the exact retained scanner', async t => {
  const f = await fixture(t);
  assert.deepEqual(f.read(scannerPath, historicalHash), historical);
  assert.deepEqual(readFileSync(f.scanner), current);
  assert.deepEqual(readFileSync(f.helper), helper);
});

for (const [label, mutate] of [
  ['unrelated edit', source => source + '\n'],
  ['missing import', source => source.replace(importLine, '')],
  ['duplicate import', source => source.replace(importLine, importLine + importLine)],
  ['missing call', source => source.replace(call, '')],
  ['duplicate call', source => source.replace(call, call + call)],
  ['changed acquisition call', source => source.replace(call, 'pullSemgrepImage(image,{env,timeout:1});')],
]) test(`historical scanner projection rejects ${label}`, async t => {
  const f = await fixture(t, Buffer.from(mutate(current.toString())));
  assert.throws(() => f.read(scannerPath, historicalHash), /current scanner hash mismatch/);
});

for (const [label, source] of [['old', historical], ['current', current]]) {
  test(`${label} scanner still requires the exact historical expectation`, async t => {
    const f = await fixture(t, source);
    for (const wrong of [undefined, '0'.repeat(64), sha(current)])
      assert.throws(() => f.read(scannerPath, wrong), /expected historical scanner hash mismatch/);
  });
  test(`${label} scanner is read again after a successful projection`, async t => {
    const f = await fixture(t, source);
    assert.deepEqual(f.read(scannerPath, historicalHash), historical);
    writeFileSync(f.scanner, Buffer.concat([source, Buffer.from('\n')]));
    assert.throws(() => f.read(scannerPath, historicalHash), /current scanner hash mismatch/);
    writeFileSync(f.scanner, source);
    assert.deepEqual(f.read(scannerPath, historicalHash), historical);
  });
}

test('current scanner requires its unchanged pull helper on every read', async t => {
  const f = await fixture(t);
  assert.deepEqual(f.read(scannerPath, historicalHash), historical);
  writeFileSync(f.helper, Buffer.concat([helper, Buffer.from('\n')]));
  assert.throws(() => f.read(scannerPath, historicalHash), /pull helper hash mismatch/);
  rmSync(f.helper);
  assert.throws(() => f.read(scannerPath, historicalHash), { code: 'ENOENT' });
  writeFileSync(f.helper, helper);
  assert.deepEqual(f.read(scannerPath, historicalHash), historical);
});

test('other paths use fresh direct bytes and cannot borrow the scanner projection', async t => {
  const f = await fixture(t);
  const other = 'scripts/security/other.mjs', file = join(f.root, other);
  writeFileSync(file, current);
  assert.deepEqual(f.read(other, historicalHash), current);
  assert.throws(() => assert.equal(sha(f.read(other, historicalHash)), historicalHash));
  assert.deepEqual(f.read('./' + scannerPath, historicalHash), current);
  assert.throws(() => assert.equal(sha(f.read('./' + scannerPath, historicalHash)), historicalHash));
  writeFileSync(file, historical);
  assert.equal(sha(f.read(other, historicalHash)), historicalHash);
  writeFileSync(file, Buffer.concat([historical, Buffer.from('\n')]));
  assert.throws(() => assert.equal(sha(f.read(other, historicalHash)), historicalHash));
});
