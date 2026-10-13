import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { runInNewContext } from 'node:vm';
import { inspectCjkSurface, assertCjkFontEvidence, collectCjkFontEvidence, settleCjkAcknowledgment } from '../../apps/web/overlay/playground/__tests__/e2e/product-readiness-cjk-fonts.ts';
import { captureBudget } from '../../apps/web/overlay/playground/__tests__/e2e/product-readiness-capture-budget.ts';
import { relevantChangeSet } from '../product-readiness/pc-h5-runtime/ci-scope.mjs';

const read = path => readFileSync(new URL('../../' + path, import.meta.url), 'utf8');
const families = [{ familyName: 'Noto Sans CJK SC', glyphCount: 4 }];

function fixture({ label = '待我处理 1', raster = 'glyphs', status = 'loaded', color = 'rgb(0, 0, 0)', opacity = '1', fontFamily = 'Noto Sans CJK SC, sans-serif', x = 10, y = 10 } = {}) {
  const style = { display: 'block', visibility: 'visible', opacity, color, fontSize: '16px', fontStyle: 'normal', fontWeight: '400', fontFamily,
    getPropertyValue: name => name === '--font-family' ? fontFamily : color };
  const bounds = { x, y, left: x, top: y, right: x + 40, bottom: y + 20, width: 40, height: 20 };
  const root = { tagName: 'DIV', parentElement: null, getBoundingClientRect: () => bounds };
  const target = { tagName: 'SPAN', parentElement: root, getBoundingClientRect: () => bounds };
  const textNode = { textContent: label, parentElement: target, isConnected: true };
  root.children = [target];
  let character;
  const canvasContext = { clearRect() {}, fillText(value) { character = value; }, getImageData() {
    const pixels = new Uint8ClampedArray(4 * 96 * 96);
    if (raster !== 'blank') {
      for (let i = 0; i < 30; i++) pixels[i * 4 + 3] = 255;
      pixels[(raster === 'tofu' ? 40 : character.charCodeAt(0) % 9000 + 100) * 4 + 3] = 255;
    }
    return { data: pixels };
  } };
  const document = { fonts: { status, ready: Promise.resolve() }, documentElement: root,
    querySelectorAll: () => [root], createElement: () => ({ getContext: () => canvasContext }),
    createTreeWalker() { let read = false; return { nextNode() { if (read) return null; read = true; return textNode; } }; },
    createRange: () => ({ selectNodeContents() {}, getBoundingClientRect: () => bounds }) };
  const globals = { document, NodeFilter: { SHOW_TEXT: 4 }, getComputedStyle: () => style, innerWidth: 390, innerHeight: 844 };
  const inspect = (surface = 'list') => runInNewContext(`(${inspectCjkSurface.toString()})(${JSON.stringify(surface)})`, globals);
  return { document, style, bounds, inspect, globals, root, target, textNode, setRaster(value) { raster = value; } };
}

function settlementFixture() {
  const f = fixture({ label: '审批已同意' });
  let now = 0, sequence = 0, outcome;
  const frames = new Map(), timers = new Map(), listeners = new Map();
  const animation = { pending: false, playState: 'finished', effect: { getComputedTiming: () => ({ endTime: 300 }) } };
  f.target.getAnimations = () => [];
  f.root.getAnimations = () => [animation];
  const globals = { ...f.globals, performance: { now: () => now },
    requestAnimationFrame: fn => { const id = ++sequence; frames.set(id, fn); return id; },
    cancelAnimationFrame: id => frames.delete(id),
    setTimeout: (fn, timeout) => { const id = ++sequence; timers.set(id, { fn, at: now + timeout }); return id; },
    clearTimeout: id => timers.delete(id),
    addEventListener: (name, fn) => listeners.set(name, fn), removeEventListener: name => listeners.delete(name),
  };
  return { ...f, animation, frames, timers, listeners,
    start(timeout = 200) {
      const result = runInNewContext(`(${settleCjkAcknowledgment.toString()})({timeout:${timeout}})`, globals);
      result.then(() => { outcome = 'passed'; }, error => { outcome = error.message; });
      return result;
    },
    async frame(y = f.bounds.y, advance = 16) {
      f.bounds.y = f.bounds.top = y; f.bounds.bottom = y + f.bounds.height;
      now += advance;
      const pending = [...frames.values()]; frames.clear(); pending.forEach(fn => fn());
      for (const [id, timer] of [...timers]) if (timer.at <= now) { timers.delete(id); timer.fn(); }
      await Promise.resolve();
      return outcome;
    },
  };
}

test('acknowledgment waits for its finite entrance and natural geometry before binding a witness', async () => {
  const f = settlementFixture(), result = f.start();
  f.animation.playState = 'running';
  assert.equal(await f.frame(-5.154985427856445), undefined);
  assert.equal(await f.frame(2.3293838500976562), undefined);
  assert.equal(await f.frame(20), undefined);
  f.animation.playState = 'finished';
  assert.equal(await f.frame(20), undefined);
  assert.equal(await f.frame(20), undefined);
  assert.equal(await f.frame(20), 'passed');
  await result;
  assert.equal(f.frames.size + f.timers.size + f.listeners.size, 0);
});
test('acknowledgment settlement rejects disappearance, changed text and navigation', async () => {
  for (const change of [f => { f.textNode.isConnected = false; }, f => { f.textNode.textContent = '审批已拒绝'; }, f => f.listeners.get('pagehide')()]) {
    const f = settlementFixture(), result = f.start();
    await f.frame(); change(f); await f.frame();
    await assert.rejects(result, /CJK_SURFACE_CHANGED/);
    assert.equal(f.frames.size + f.timers.size + f.listeners.size, 0);
  }
});
test('never-settling, paused, infinite, unknown, clipped and invisible acknowledgments expire within the budget', async () => {
  for (const mode of ['running', 'paused', 'infinite', 'unknown', 'geometry', 'clipped', 'invisible']) {
    const f = settlementFixture(), result = f.start(100);
    if (mode === 'running' || mode === 'paused') f.animation.playState = mode;
    if (mode === 'infinite' || mode === 'unknown') {
      f.animation.playState = 'running';
      f.animation.effect.getComputedTiming = () => ({ endTime: mode === 'infinite' ? Infinity : undefined });
    }
    if (mode === 'invisible') f.style.opacity = '0';
    for (let i = 0; i < 7; i++) await f.frame(mode === 'geometry' ? 10 + i : mode === 'clipped' ? -5 : 10);
    await assert.rejects(result, /CJK_SURFACE_SETTLEMENT_EXPIRED/);
    assert.equal(f.frames.size + f.timers.size + f.listeners.size, 0);
  }
});
test('each new entrance movement invalidates earlier stable frames and settlement never edits animation state', async () => {
  const f = settlementFixture(), result = f.start();
  await f.frame(10); await f.frame(10);
  f.animation.pending = true; await f.frame(10);
  assert.equal(f.animation.pending, true);
  f.animation.pending = false;
  assert.equal(await f.frame(20), undefined);
  assert.equal(await f.frame(20), undefined);
  assert.equal(await f.frame(20), 'passed');
  await result;
});

test('actual visible list and acknowledgment labels use their own computed font and nonempty distinct glyphs', () => {
  for (const [surface, label] of [['list', '待我处理 1'], ['acknowledged', '审批已同意']]) {
    const evidence = fixture({ label }).inspect(surface);
    assertCjkFontEvidence(evidence, [{ ...families[0], glyphCount: 5 }], true);
    assert.equal(evidence.label, label.replace(/[\s\d]/gu, ''));
    assert.equal(evidence.computedFontFamily, 'Noto Sans CJK SC, sans-serif');
    assert.equal(evidence.selector, 'div:nth-child(1) > span:nth-child(1)');
    assert.ok(evidence.minimumInkPixels > 20); assert.ok(evidence.uniqueGlyphHashes >= 6);
  }
});
test('font-ready DOM text cannot substitute for nonempty distinct Chinese pixels', () => {
  assert.throws(() => assertCjkFontEvidence(fixture({ raster: 'blank' }).inspect(), families), /NONEMPTY/);
  assert.throws(() => assertCjkFontEvidence(fixture({ raster: 'tofu' }).inspect(), families), /DISTINCT/);
});
test('unresolved fonts, invisible text and the wrong surface label fail before platform-font inspection', () => {
  for (const options of [{ status: 'loading' }, { opacity: '0' }, { color: 'transparent' }, { color: 'rgba(0, 0, 0, 0)' }, { label: '审批已同意' }]) {
    assert.throws(() => fixture(options).inspect(), /CJK_FONT_UNRESOLVED|CJK_SURFACE_LABEL_REQUIRED|CJK_SURFACE_TEXT_INVISIBLE/);
  }
});
test('a positive text rectangle outside any viewport edge cannot provide visible glyph evidence', () => {
  for (const options of [{ x: -50 }, { x: 390 }, { y: -30 }, { y: 844 }]) {
    assert.throws(() => fixture(options).inspect(), /SURFACE_LABEL_REQUIRED/);
  }
  assertCjkFontEvidence(fixture({ x: -10 }).inspect(), families);
});
test('CSS family declarations and canvas glyphs cannot substitute for actual resolved CJK coverage', () => {
  const evidence = fixture().inspect();
  for (const fonts of [[], [{ familyName: 'Arial', glyphCount: 4 }], [{ familyName: 'Last Resort', glyphCount: 4 }], [{ ...families[0], glyphCount: 0 }], [{ ...families[0], glyphCount: 3 }]]) {
    assert.throws(() => assertCjkFontEvidence(evidence, fonts), /PLATFORM_FONT_REQUIRED/);
  }
  // H5 can use a working platform CJK fallback even without an explicit CSS family.
  const fallback = fixture({ fontFamily: 'sans-serif' }).inspect();
  assertCjkFontEvidence(fallback, families);
  assert.throws(() => assertCjkFontEvidence(fallback, families, true), /EXPLICIT_FONT_STACK/);
});

function browserFixture(options = {}) {
  const f = fixture(options), calls = [], evaluations = []; let detached = false;
  const session = { async send(method, params) {
    calls.push({ method, params });
    if (method === 'DOM.getDocument') return { root: { nodeId: 1 } };
    if (method === 'DOM.querySelector') return { nodeId: options.nodeId ?? 9 };
    if (method === 'CSS.getPlatformFontsForNode') {
      if (options.fontFailure) throw new Error('controlled CDP failure');
      if (options.replace) f.style.fontWeight = '700';
      return { fonts: options.fonts ?? families };
    }
    return {};
  }, async detach() { detached = true; if (options.stallDetach) await new Promise(() => {}); } };
  const page = { context: () => ({ newCDPSession: async target => { assert.equal(target, page); return session; } }),
    async evaluate(fn, arg) {
      evaluations.push(fn.name);
      if (fn === inspectCjkSurface) return f.inspect(arg);
      await f.document.fonts.ready;
    } };
  return { ...f, calls, evaluations, page, detached: () => detached };
}
test('only the acknowledged capture settles its current toast before font inspection and keeps later equality strict', async () => {
  const f = browserFixture({ label: '审批已同意', fonts: [{ ...families[0], glyphCount: 5 }] });
  const result = await collectCjkFontEvidence(f.page, 'acknowledged', captureBudget(1000));
  assert.ok(f.evaluations.indexOf('settleCjkAcknowledgment') < f.evaluations.indexOf('inspectCjkSurface'));
  f.bounds.y += 1;
  await assert.rejects(result.assertCurrent(), /SURFACE_CHANGED/);
  const list = browserFixture(); await collectCjkFontEvidence(list.page, 'list', captureBudget(1000));
  assert.equal(list.evaluations.includes('settleCjkAcknowledgment'), false);
});
test('Chromium evidence resolves the exact inspected control and rechecks its witness around capture', async () => {
  const f = browserFixture(), result = await collectCjkFontEvidence(f.page, 'list', captureBudget(1000));
  assert.equal(result.evidence.cjkGlyphsRendered, true);
  assert.deepEqual(result.evidence.platformFonts, families);
  assert.equal(f.calls.find(call => call.method === 'DOM.querySelector').params.selector, result.evidence.selector);
  assert.equal(f.calls.find(call => call.method === 'CSS.getPlatformFontsForNode').params.nodeId, 9);
  assert.equal(f.detached(), true);
  await result.assertCurrent();
  f.bounds.x += 1;
  await assert.rejects(result.assertCurrent(), /SURFACE_CHANGED/);
  f.bounds.x -= 1;
  f.style.fontWeight = '700';
  await assert.rejects(result.assertCurrent(), /SURFACE_CHANGED/);
});
test('frozen witnesses reject real text, font and glyph changes after initial readiness', async () => {
  for (const change of [f => { f.textNode.textContent = '审批已拒绝'; }, f => { f.style.fontFamily = 'Arial'; }, f => f.setRaster('tofu')]) {
    const f = browserFixture(), result = await collectCjkFontEvidence(f.page, 'list', captureBudget(1000));
    change(f);
    await assert.rejects(result.assertCurrent(), /SURFACE_CHANGED|SURFACE_LABEL_REQUIRED/);
  }
});
test('stalled CDP cleanup consumes the same deadline and never hides the original font failure', async () => {
  const clean = browserFixture({ stallDetach: true });
  await assert.rejects(collectCjkFontEvidence(clean.page, 'list', captureBudget(150)), /deadline/);
  assert.equal(clean.detached(), true);
  const failed = browserFixture({ stallDetach: true, fontFailure: true });
  await assert.rejects(collectCjkFontEvidence(failed.page, 'list', captureBudget(150)), /controlled CDP failure/);
  assert.equal(failed.detached(), true);
});
test('missing nodes, unresolved platform fonts, CDP failure or changed controls never produce success evidence', async () => {
  for (const options of [{ nodeId: 0 }, { fonts: [] }, { fontFailure: true }, { replace: true }]) {
    const f = browserFixture(options);
    await assert.rejects(collectCjkFontEvidence(f.page, 'list', captureBudget(1000)), /NODE_REQUIRED|FONT_REQUIRED|CDP failure|SURFACE_CHANGED/);
    assert.equal(f.detached(), true);
  }
});
test('font collection uses the existing capture deadline and never starts after expiry', async () => {
  let now = 0; const budget = captureBudget(10, () => now); now = 10;
  const f = browserFixture();
  await assert.rejects(collectCjkFontEvidence(f.page, 'list', budget), /expired/);
  assert.deepEqual(f.calls, []);
});

test('selected PC/H5 runtime prepares the existing CJK prerequisite before managed processes and retains the browser deadline', () => {
  const source = read('scripts/product-readiness/pc-h5-runtime-smoke.mjs');
  assert.match(source, /import \{ ensureCjkFontRuntime \} from '\.\/quick-start\/cjk-fonts\.mjs'/);
  assert.ok(source.indexOf('    ensureCjkFontRuntime();') < source.indexOf('const backend = startManagedNode('));
  assert.match(source, /browserTimeoutMs = 5 \* 60_000/);
  assert.match(source, /if \(options.command === 'ci' && !shouldRunInCi\(\)\) return;/);
  const spec = read('apps/web/overlay/playground/__tests__/e2e/product-readiness-pc-h5-runtime.spec.ts');
  assert.match(spec, /phase === 'IMMEDIATE_ACKNOWLEDGED_RESULT' \? 'acknowledged' : 'list'/);
  assert.match(spec, /font: font.evidence/);
  assert.match(spec, /await font.assertCurrent\(\)/);
  assert.doesNotMatch(read('apps/web/overlay/playground/__tests__/e2e/product-readiness-browser-accessibility.spec.ts'), /product-readiness-cjk-fonts/);
  for (const path of ['apps/web/overlay/playground/__tests__/e2e/product-readiness-cjk-fonts.ts', 'scripts/tests/product-readiness-cjk-fonts.test.mjs']) assert.equal(relevantChangeSet([path]), true);
});
test('reused font preparation installs only the bounded existing CI prerequisite and rejects unavailable local coverage', () => {
  const source = read('scripts/product-readiness/quick-start/cjk-fonts.mjs').replace("import { spawnSync } from 'node:child_process';", '').replace('export function ensureCjkFontRuntime', 'function ensureCjkFontRuntime');
  const execute = (ci, installed) => {
    const calls = [];
    const result = () => runInNewContext(source + '\nensureCjkFontRuntime()', {
      process: { platform: 'linux', env: { GITHUB_ACTIONS: String(ci) } }, console: { log() {} },
      spawnSync(command, args, options) {
        calls.push({ command, args, options });
        return { status: 0, stdout: command === 'fc-list' && (installed || calls.length > 1) ? 'Noto Sans CJK SC' : '' };
      },
    });
    return { calls, result };
  };
  const existing = execute(true, true); assert.equal(existing.result().family, 'Noto Sans CJK SC'); assert.equal(existing.calls.length, 1);
  const ci = execute(true, false); assert.equal(ci.result().family, 'Noto Sans CJK SC');
  assert.deepEqual(ci.calls.map(call => call.command), ['fc-list', 'sudo', 'sudo', 'fc-cache', 'fc-list']);
  assert.ok(ci.calls.every(call => call.options.timeout > 0 && call.options.shell === false));
  assert.ok(ci.calls[2].args.includes('fonts-noto-cjk'));
  const local = execute(false, false); assert.throws(local.result, /No Simplified Chinese font/); assert.equal(local.calls.length, 1);
});
