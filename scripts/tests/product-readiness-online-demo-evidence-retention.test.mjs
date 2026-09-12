import assert from 'node:assert/strict';
import { globSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../..', import.meta.url));
const workflow = readFileSync(resolve(root, '.github/workflows/approval-platform-validation.yml'), 'utf8');
const job = workflow.split('\n  online-images:\n')[1];
assert.ok(job, 'existing online image job is required');
const retain = job.split('      - name: Retain image build and runtime evidence\n')[1];
assert.ok(retain, 'existing evidence upload step is required');
const block = retain.match(/          path: \|\n((?: {12}[^\n]+\n)+)/u)?.[1];
assert.ok(block, 'explicit artifact paths are required');
const patterns = block.trim().split('\n').map(line => line.trim());
const expected = [
  'online-image-contracts.log',
  'online-image-runtime.log',
  '.runtime/online-demo-images/*/image-build.json',
  '.runtime/online-demo-image-runtime/*/*.json',
  '.runtime/online-demo-image-runtime/*/evaluation-browser-*.png',
];

test('existing image artifact retains redacted browser PNGs alongside logs and JSON without widening its scope', () => {
  assert.deepEqual(patterns, expected);
  assert.match(retain, /        if: always\(\)/u);
  assert.match(retain, /          include-hidden-files: true/u);
  assert.match(retain, /          if-no-files-found: error/u);
});

test('actual filesystem globs include success and failure screenshots but exclude browser profiles, keys and unrelated images', t => {
  // These are filename fixtures, not screenshots or a browser execution claim.
  const directory = mkdtempSync(resolve(tmpdir(), 'approval-evidence-glob-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const run = '.runtime/online-demo-image-runtime/run-fixture/';
  const included = ['online-image-contracts.log', 'online-image-runtime.log',
    '.runtime/online-demo-images/build-fixture/image-build.json',
    run + 'evaluation-browser-trace.json', run + 'evaluation-browser-A-purchase.png',
    run + 'evaluation-browser-B-failure.png'];
  const excluded = [run + 'unrelated.png', run + 'key.pem', run + 'cookies.txt',
    run + 'chrome/Default/Cookies', run + 'nested/evaluation-browser-A-private.png',
    'unrelated/evaluation-browser-A-purchase.png'];
  for (const path of [...included, ...excluded]) {
    const file = resolve(directory, path); mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, 'fixture', { mode: 0o600 });
  }
  const matched = [...new Set(patterns.flatMap(pattern => globSync(pattern, { cwd: directory })))].sort();
  assert.deepEqual(matched, [...included].sort());
});
