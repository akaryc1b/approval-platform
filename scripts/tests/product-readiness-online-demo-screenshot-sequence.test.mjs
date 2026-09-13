import assert from 'node:assert/strict';
import { existsSync, globSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve } from 'node:path';
import test from 'node:test';

const source = readFileSync(new URL('../product-readiness/online-demo/evaluation-browser.mjs', import.meta.url), 'utf8');
const start = source.indexOf('  async function screenshot(session, name) {');
const end = source.indexOf('\n  function dispose()', start);
assert.ok(start >= 0 && end > start);
const body = source.slice(start, end);
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6jKsAAAAASUVORK5CYII=', 'base64');
function fixture(t) {
  const directory = mkdtempSync(resolve(tmpdir(), 'evaluation-screenshot-test-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const evidence = { screenshots: [] }; const calls = []; let data = png; let saves = 0;
  // Execute the exact browser screenshot function with CDP fixtures and real filesystem writes.
  const capture = new Function('required', 'evaluate', 'cdp', 'Buffer', 'writeFileSync', 'resolve', 'directory', 'evidence', 'save',
    body + '\nreturn screenshot;')(
    (value, code) => { if (!value) throw new Error(code); },
    async (session, expression) => { calls.push('redact'); assert.match(expression, /#invitation/u); },
    { send: async (method, options) => { calls.push('capture'); assert.equal(method, 'Page.captureScreenshot');
      assert.equal(options.captureBeyondViewport, false); return { data: data.toString('base64') }; } },
    Buffer, writeFileSync, resolve, directory, evidence, () => { saves++; });
  return { directory, evidence, calls, capture: name => capture({ id: 'fixture' }, name), setData: value => { data = value; }, saves: () => saves };
}
test('first and replacement invitation captures are distinct and neither is overwritten', async t => {
  const f = fixture(t); await f.capture('A-invited'); f.setData(Buffer.concat([png, Buffer.from('second')])); await f.capture('A-invited');
  assert.equal(new Set(f.evidence.screenshots).size, 2); assert.equal(readdirSync(f.directory).length, 2);
  assert.deepEqual(readFileSync(resolve(f.directory, f.evidence.screenshots[0])), png);
  assert.equal(f.saves(), 2); assert.deepEqual(f.calls, ['redact', 'capture', 'redact', 'capture']);
});
test('all 17 canonical capture references retain separate private PNG files', async t => {
  const f = fixture(t); for (let n = 0; n < 17; n++) await f.capture(n % 2 ? 'B-step' : 'A-step');
  assert.equal(new Set(f.evidence.screenshots).size, 17);
  for (const file of f.evidence.screenshots) {
    assert.ok(existsSync(resolve(f.directory, file))); assert.equal(statSync(resolve(f.directory, file)).mode & 0o777, 0o600);
    assert.match(file, /^evaluation-browser-\d{3}-[AB]-step\.png$/u);
  }
});
test('a filesystem collision fails without replacing prior bytes or appending a false trace reference', async t => {
  const f = fixture(t); writeFileSync(resolve(f.directory, 'evaluation-browser-001-A-invited.png'), 'previous');
  await assert.rejects(f.capture('A-invited'), { code: 'EEXIST' }); assert.equal(f.evidence.screenshots.length, 0);
  assert.equal(readFileSync(resolve(f.directory, 'evaluation-browser-001-A-invited.png'), 'utf8'), 'previous');
});
test('malformed screenshot names cannot escape the evidence directory', async t => {
  const f = fixture(t); for (const name of ['../token', '/absolute', '', 'A?cookie', 'x'.repeat(121)]) await assert.rejects(f.capture(name), /BROWSER_SCREENSHOT_NAME/u);
  assert.equal(f.calls.length, 0); assert.deepEqual(readdirSync(f.directory), []);
});
test('oversized image data cannot append a reference to a nonexistent artifact', async t => {
  const f = fixture(t); f.setData(Buffer.alloc(8 * 1024 * 1024 + 1)); await assert.rejects(f.capture('A-step'), /BROWSER_SCREENSHOT_LIMIT/u);
  assert.equal(f.evidence.screenshots.length, 0); assert.equal(f.saves(), 0); assert.deepEqual(readdirSync(f.directory), []);
});
test('capture count remains bounded, including repeated failure callbacks', async t => {
  const f = fixture(t); f.evidence.screenshots = Array.from({ length: 64 }, (_, n) => `${n}.png`);
  await assert.rejects(f.capture('A-failure'), /BROWSER_SCREENSHOT_LIMIT/u);
  assert.equal(f.evidence.screenshots.length, 64); assert.equal(f.saves(), 0);
});
test('unchanged upload globs retain both runs and their failures but not browser profiles or TLS material', t => {
  const workflow = readFileSync(new URL('../../.github/workflows/approval-platform-validation.yml', import.meta.url), 'utf8');
  const images = workflow.slice(workflow.indexOf('  online-images:'));
  const block = images.match(/          path: \|\n((?: {12}[^\n]+\n)+)/u)[1];
  const patterns = block.trim().split('\n').map(line => line.trim());
  const directory = mkdtempSync(resolve(tmpdir(), 'evaluation-repeat-retention-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const included = []; const excluded = [];
  for (const name of ['run-fixture', 'run-fixture-browser-repeat-2']) {
    const root = `.runtime/online-demo-image-runtime/${name}/`;
    included.push(root + 'evaluation-browser-rehearsal.json', root + 'evaluation-browser-trace.json',
      root + 'evaluation-browser-001-A-invited.png', root + 'evaluation-browser-017-A-invited.png',
      root + 'evaluation-browser-018-B-failure.png');
    excluded.push(root + 'key.pem', root + 'cookies.txt', root + 'chrome/Default/Cookies', root + 'other.png');
  }
  included.push('.runtime/online-demo-image-runtime/run-fixture/evaluation-browser-repeat.json');
  for (const name of [...included, ...excluded]) {
    const file = resolve(directory, name); mkdirSync(dirname(file), { recursive: true }); writeFileSync(file, 'fixture');
  }
  assert.deepEqual([...new Set(patterns.flatMap(pattern => globSync(pattern, { cwd: directory })))].sort(), included.sort());
  assert.match(images, /if: always\(\)/u); assert.match(images, /include-hidden-files: true/u);
  assert.equal((images.match(/node scripts\/product-readiness\/online-demo-images-runtime\.mjs ci/gu) || []).length, 1);
});
