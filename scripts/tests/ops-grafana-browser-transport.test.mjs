import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import test from 'node:test';
import { BrowserPipe } from '../ops/grafana-browser-driver.mjs';
import { prepareGrafanaFonts } from '../ops/grafana-browser-fonts.mjs';

function fixture(t) {
  const child = new EventEmitter(); child.stderr = new EventEmitter();
  const input = new EventEmitter(), output = new EventEmitter(); output.setEncoding = () => {};
  const writes = []; input.write = data => { writes.push(JSON.parse(data.slice(0, -1))); return true; };
  child.stdio = [null, null, child.stderr, input, output];
  const env = { PATH: process.env.PATH, HOME: '/owned-fixture' };
  const browser = new BrowserPipe('/owned-fixture/profile', env, { launch(file, args, options) {
    assert.equal(options.env, env); assert.equal(options.detached, true);
    assert.deepEqual(options.stdio, ['ignore', 'ignore', 'pipe', 'pipe', 'pipe']);
    assert.ok(args.includes('--remote-debugging-pipe'));
    assert.ok(args.includes('--user-data-dir=/owned-fixture/profile'));
    assert.ok(args.every(arg => !arg.startsWith('--remote-debugging-port')));
    return child;
  } });
  child.emit('spawn');
  t.after(() => browser.fail('GRAFANA_BROWSER_FIXTURE_CLEANUP'));
  return { browser, child, input, output, writes,
    reply: value => output.emit('data', JSON.stringify(value) + '\0') };
}

test('fragmented and combined pipe responses preserve request identity and session', async t => {
  const f = fixture(t); const a = f.browser.call('Browser.getVersion');
  f.browser.session = 'fixture-session'; const b = f.browser.call('Page.enable');
  assert.equal(f.writes[0].sessionId, undefined); assert.equal(f.writes[1].sessionId, 'fixture-session');
  f.output.emit('data', '{"id":1,"res');
  f.output.emit('data', 'ult":{"product":"Chrome/fixture"}}\0{"id":2,"result":{}}\0');
  assert.deepEqual(await a, { product: 'Chrome/fixture' }); assert.deepEqual(await b, {});
  assert.equal(f.browser.responses, 2); assert.equal(f.browser.pending.size, 0);
});

test('events and unrelated responses cannot complete a pending command', async t => {
  const f = fixture(t); const command = f.browser.call('Page.enable');
  f.reply({ method: 'Runtime.exceptionThrown' }); f.reply({ id: 99, result: {} });
  assert.equal(f.browser.exceptions, 1); assert.equal(f.browser.pending.size, 1);
  f.reply({ id: 1, result: {} }); await command;
});

for (const value of ['not JSON', 'null', '[]']) {
  test('invalid protocol closes every pending call without an uncaught parse error: ' + value, async t => {
    const f = fixture(t); const a = f.browser.call('Browser.getVersion'), b = f.browser.call('Page.enable');
    const checked = Promise.all([assert.rejects(a, /GRAFANA_BROWSER_PROTOCOL_INVALID/),
      assert.rejects(b, /GRAFANA_BROWSER_PROTOCOL_INVALID/)]);
    f.output.emit('data', value + '\0'); await checked;
    assert.equal(f.browser.pending.size, 0); assert.equal(f.browser.closed, true);
    assert.equal(f.browser.buffer, '');
  });
}

for (const [event, expected] of [['end', 'PIPE_ENDED'], ['error', 'PIPE_CLOSED']]) {
  test('read pipe ' + event + ' fails immediately rather than waiting for the command timeout', async t => {
    const f = fixture(t); const pending = f.browser.call('Browser.getVersion');
    const checked = assert.rejects(pending, new RegExp('GRAFANA_BROWSER_' + expected));
    f.output.emit(event, new Error('sensitive transport detail')); await checked;
    assert.equal(f.browser.closed, true); assert.equal(f.browser.pending.size, 0);
  });
}

for (const [stream, event, code] of [['input', 'error', 'PIPE_CLOSED'], ['child', 'exit', 'EXITED'],
  ['child', 'error', 'START_FAILED']]) {
  test('transport ' + stream + '/' + event + ' never exports raw error text', async t => {
    const f = fixture(t); const pending = f.browser.call('Browser.getVersion');
    const checked = assert.rejects(pending, error => {
      assert.ok(error.message.startsWith('GRAFANA_BROWSER_' + code));
      assert.ok(!error.message.includes('SECRET')); return true;
    });
    f[stream].emit(event, new Error('SECRET provider or profile value')); await checked;
    await assert.rejects(f.browser.call('Page.enable'), /GRAFANA_BROWSER_CLOSED/);
  });
}

test('a command timeout is terminal for the session and late replies cannot resurrect it', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const f = fixture(t); const a = f.browser.call('Browser.getVersion'), b = f.browser.call('Page.enable');
  const checked = Promise.all([assert.rejects(a, /TIMEOUT:Browser.getVersion/),
    assert.rejects(b, /TIMEOUT:Browser.getVersion/)]);
  t.mock.timers.tick(10000); await checked;
  assert.equal(f.browser.pending.size, 0); f.reply({ id: 1, result: { product: 'too late' } });
  assert.equal(f.browser.responses, 0); await assert.rejects(f.browser.call('Page.enable'), /CLOSED/);
});

test('native diagnostics retain bounded counts and fixed categories, never private strings', async t => {
  const f = fixture(t);
  f.child.stderr.emit('data', Buffer.from('SECRET password=abc db'));
  f.child.stderr.emit('data', Buffer.from('us: connection failed; fontconfig; remote debugging disabled by policy\n'));
  f.child.stderr.emit('data', Buffer.alloc(2 * 1024 * 1024, 120));
  const pending = f.browser.call('Browser.getVersion');
  const checked = assert.rejects(pending, error => {
    assert.match(error.message, /spawn=1,stderr=1048576,protocol=0,responses=0/);
    assert.match(error.message, /categories=dbus\+devtools\+fontconfig\+policy/);
    assert.doesNotMatch(error.message, /SECRET|password|abc|owned-fixture/); return true;
  });
  f.output.emit('end'); await checked;
  assert.equal(f.browser.nativeTail, '');
});

test('serialization and synchronous write failures close the session and clear all timers', async t => {
  const f = fixture(t); f.input.write = () => { throw new Error('SECRET path'); };
  await assert.rejects(f.browser.call('Browser.getVersion'), /GRAFANA_BROWSER_WRITE_FAILED/);
  assert.equal(f.browser.pending.size, 0); assert.equal(f.browser.closed, true);
});

test('failed protocol command does not expose the native response error', async t => {
  const f = fixture(t); const pending = f.browser.call('Page.enable');
  const checked = assert.rejects(pending, error => error.message === 'GRAFANA_BROWSER_COMMAND:Page.enable');
  f.reply({ id: 1, error: { message: 'SECRET browser error' } }); await checked;
  assert.equal(f.browser.closed, false);
});

test('invalid method labels and excessive pending commands are rejected before another write', async t => {
  const f = fixture(t);
  await assert.rejects(f.browser.call('SECRET:/private'), /METHOD_REJECTED/);
  const pending = Array.from({ length: 128 }, () => f.browser.call('Page.enable'));
  const checked = Promise.all(pending.map(p => assert.rejects(p, /FIXTURE_STOP/)));
  await assert.rejects(f.browser.call('Page.enable'), /PENDING_LIMIT/);
  assert.equal(f.writes.length, 128); f.browser.fail('GRAFANA_BROWSER_FIXTURE_STOP'); await checked;
});

test('real isolated Chromium completes the same handshake and reports rendered CJK fonts', { timeout: 80000 }, async () => {
  // This component can run before the native provisioner: own its font prerequisite.
  // 60s font preparation + 20s component budget; individual browser commands remain 10s.
  await prepareGrafanaFonts(process.env.GITHUB_ACTIONS === 'true' ? 'ci' : 'local');
  const home = mkdtempSync(resolve(tmpdir(), 'grafana-transport-native-'));
  const browser = new BrowserPipe(resolve(home, 'chrome'), {
    PATH: process.env.PATH, LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8', HOME: home, TMPDIR: home,
  });
  try {
    assert.match(await browser.start(), /^(HeadlessChrome|Chrome)\//);
    await browser.evaluate(`document.body.innerHTML='<h2 id="cjk" style="font-family: Noto Sans CJK SC,sans-serif">可执行任务</h2>'`);
    await browser.evaluate('document.fonts.ready.then(()=>true)');
    const fonts = await browser.platformFonts('#cjk');
    assert.ok(fonts.some(font => /^(Noto Sans CJK|Noto Sans SC|Source Han Sans|WenQuanYi)/u.test(font.familyName) && font.glyphCount >= 5), 'actual platform glyph use is required');
    assert.ok(browser.responses >= 10); assert.equal(browser.closed, false);
  } finally {
    await browser.stop(); rmSync(home, { recursive: true, force: true });
  }
});
