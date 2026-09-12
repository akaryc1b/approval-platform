import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { runInNewContext } from 'node:vm';
import test from 'node:test';

const path = new URL('../product-readiness/online-demo-images-runtime.mjs', import.meta.url);
const source = readFileSync(path, 'utf8');
// Execute the real launcher body. Font installation, Docker, clock and browser are
// controlled interfaces: these tests prove ordering, not real fonts or business E2E.
async function run({ selected = true, command = 'ci', fontFailure = false, browserFailure = false } = {}) {
  const calls = []; const errors = []; let time = 0;
  const process = { argv: ['node', 'launcher.mjs', command], exitCode: 0 };
  const context = {
    process, resolve, fileURLToPath, URL,
    console: { log() {}, error: message => errors.push(message) },
    performance: { now: () => time }, readFileSync: () => '{}',
    selectImageRuntimeScope: () => ({ selected, reason: 'TEST_SCOPE' }),
    ensureCjkFontRuntime() {
      calls.push('font'); time += 1000;
      if (fontFailure) throw new Error('No Simplified Chinese font is available');
      return { platform: 'linux', prepared: true, family: 'Noto Sans CJK SC' };
    },
    executeImageRuntime: async () => { calls.push('images'); return { receipt: { status: 'SMOKE' }, directory: '/fixture' }; },
    executeEvaluationSlotRehearsal: async () => { calls.push('slots'); return { status: 'SLOTS', privateReadIdentity: { status: 'SIGNED_PENDING_READ_PREFLIGHT_PASSED' } }; },
    executeEvaluationBusinessRehearsal: async options => {
      calls.push(options.browser ? 'browser' : 'api');
      if (options.browser && browserFailure) throw new Error('BROWSER_FAILURE');
      assert.ok(options.maximumMs <= 360000);
      return { status: options.browser ? 'TWO_BROWSER_PC_H5_BUSINESS_RESET_PASSED' : 'TWO_SESSION_REAL_BUSINESS_API_RESET_PASSED' };
    },
    exportEvaluationApplications: async () => { calls.push('export'); return { roots: {}, dispose() { calls.push('dispose'); } }; },
  };
  const body = source.replace(/^#![^\n]*\n/u, '').replace(/^import .*;\n/gmu, '')
    .replaceAll('import.meta.url', JSON.stringify(path.href));
  await runInNewContext('(async () => {\n' + body + '\n})()', context);
  return { calls, errors, process };
}

test('selected execution prepares existing system CJK fonts before images or browser resources', async () => {
  const r = await run();
  assert.deepEqual(r.calls, ['font', 'images', 'slots', 'api', 'export', 'browser', 'dispose']);
  assert.equal(r.process.exitCode, 0);
  assert.match(source, /import \{ ensureCjkFontRuntime \} from '\.\/quick-start\/cjk-fonts\.mjs'/u);
});
test('unselected CI remains free of font installation, Docker and browser side effects', async () => {
  const r = await run({ selected: false }); assert.deepEqual(r.calls, []); assert.equal(r.process.exitCode, 0);
});
test('font preparation failure cannot become a successful or partially started rehearsal', async () => {
  const r = await run({ fontFailure: true }); assert.deepEqual(r.calls, ['font']);
  assert.equal(r.process.exitCode, 1); assert.ok(r.errors[0].includes('No Simplified Chinese font'));
});
test('explicit local runs use the same font preparation, without a CI-only browser bypass', async () => {
  const r = await run({ selected: false, command: 'run' });
  assert.deepEqual(r.calls, ['font', 'images', 'slots', 'api', 'export', 'browser', 'dispose']);
});
test('a later browser failure still closes the exported applications and remains failed', async () => {
  const r = await run({ browserFailure: true });
  assert.equal(r.calls.at(-1), 'dispose'); assert.equal(r.process.exitCode, 1);
  assert.ok(r.errors[0].includes('BROWSER_FAILURE'));
});
test('the launcher reuses the bounded font helper without embedding binaries, external font URLs or a larger job budget', () => {
  assert.doesNotMatch(source, /https?:\/\/|apt-get|sudo|--no-sandbox|ignore-certificate-errors'/u);
  assert.equal(source.match(/ensureCjkFontRuntime\(\)/gu)?.length, 1);
  assert.ok(source.indexOf('const started = performance.now()') < source.indexOf('ensureCjkFontRuntime();'));
  assert.match(source, /42 \* 60_000/u);
});
