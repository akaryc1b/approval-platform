import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const read = relativePath => readFileSync(path.join(root, relativePath), 'utf8');
const parse = relativePath => JSON.parse(read(relativePath));

test('generated Acceptance catalog and milestone indexes have no drift', () => {
  execFileSync(
    process.execPath,
    ['scripts/generate-acceptance-catalog.mjs', '--check'],
    { cwd: root, stdio: 'pipe' },
  );
});

test('Acceptance catalog classifies every immutable record exactly once', () => {
  const source = parse('config/acceptance-catalog.json');
  const lock = parse('config/acceptance-lock.json');
  const generated = parse('docs/acceptance/catalog.json');
  const sourceRecords = source.milestones.flatMap(milestone => milestone.records);
  const generatedRecords = generated.milestones.flatMap(milestone => milestone.records);

  assert.deepEqual(
    sourceRecords.map(record => record.path).sort(),
    Object.keys(lock.documents).sort(),
  );
  assert.equal(new Set(sourceRecords.map(record => record.id)).size, sourceRecords.length);
  assert.equal(new Set(sourceRecords.map(record => record.path)).size, sourceRecords.length);
  assert.equal(sourceRecords.some(record => Object.hasOwn(record, 'blob')), false);

  for (const record of generatedRecords) {
    assert.equal(record.blob, lock.documents[record.path]);
    assert.equal(existsSync(path.join(root, record.path)), true);
    assert.equal(record.path.startsWith('docs/current/'), false);
    assert.equal(record.path.startsWith('docs/releases/'), false);
  }
});

test('Acceptance milestone indexes are navigational and preserve historical paths', () => {
  const generated = parse('docs/acceptance/catalog.json');
  const rootIndex = read('docs/acceptance/README.md');
  assert.match(rootIndex, /不移动已锁定正文/u);
  assert.match(rootIndex, /相对链接/u);

  for (const milestone of generated.milestones) {
    const indexPath = `docs/acceptance/${milestone.id}/README.md`;
    assert.equal(existsSync(path.join(root, indexPath)), true);
    const content = read(indexPath);
    assert.match(content, /只负责分类和导航/u);
    for (const record of milestone.records) {
      assert.equal(content.includes(record.blob), true);
      assert.equal(content.includes(path.basename(record.path)), true);
    }
  }
});
