import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { existsSync, mkdtempSync, readFileSync, readdirSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { prepareGrafanaFonts } from '../ops/grafana-browser-fonts.mjs';
import test from 'node:test';
import { BrowserPipe, chromiumArguments, panelReadExpression, assertNavigation } from '../ops/grafana-browser-driver.mjs';
import { renderEngineMetrics, runGrafanaBrowser, sourceIdentities } from '../ops/grafana-browser-runtime.mjs';
import { grafanaPin, verifyGrafanaArchive, validateGrafanaReceipt, verifyGrafanaBrowser } from '../ops/verify-grafana-browser.mjs';

const root = resolve(import.meta.dirname, '../..');
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const selected = { 'var-datasource': 'approval-fixture', 'var-environment': 'test', 'var-instance': 'node-a', from: 'now-5m', to: 'now' };
const context = t => { const dir = mkdtempSync(resolve(tmpdir(), 'grafana-browser-unit-')); t.after(() => rmSync(dir, { recursive: true, force: true })); return dir; };

test('controlled source distinguishes four actual zeros from four unavailable samples', () => {
  for (const [state, expected] of [['healthy', ['3', '5', '2', '1', '1']], ['unavailable', ['NaN', 'NaN', 'NaN', 'NaN', '0']], ['empty', ['0', '0', '0', '0', '1']]]) {
    const lines = renderEngineMetrics(state).split('\n').filter(line => line && !line.startsWith('#'));
    assert.equal(lines.length, 5); assert.deepEqual(lines.map(line => line.split(' ').at(-1)), expected);
    assert.ok(lines.every(line => line.includes('application="approval-platform"')));
    assert.doesNotMatch(lines.join('\n'), /tenant|request|process_id|password/u);
  }
  assert.throws(() => renderEngineMetrics('invalid'), /FIXTURE_STATE/u);
});
test('navigation requires the exact environment instance datasource and time range', () => {
  const url = 'http://127.0.0.1:12345/d/approval-engine-jobs/slug?' + new URLSearchParams(selected);
  assertNavigation(url, 'approval-engine-jobs', selected);
  for (const name of Object.keys(selected)) { const bad = new URL(url); bad.searchParams.set(name, 'other');
    assert.throws(() => assertNavigation(bad.href, 'approval-engine-jobs', selected)); }
  assert.throws(() => assertNavigation(url.replace('127.0.0.1', 'example.com'), 'approval-engine-jobs', selected));
  assert.throws(() => assertNavigation(url, 'approval-operations', selected));
});
test('browser owns an empty profile and anonymous protocol pipes rather than a shared debugger port', () => {
  const args = chromiumArguments('/tmp/owned-profile');
  assert.ok(args.includes('--remote-debugging-pipe')); assert.ok(args.includes('--user-data-dir=/tmp/owned-profile'));
  assert.ok(args.includes('--disable-background-networking')); assert.ok(args.includes('--disable-extensions'));
  assert.ok(args.some(arg => arg.includes('MAP * ~NOTFOUND')));
  assert.ok(!args.some(arg => arg.startsWith('--remote-debugging-port')));
});
test('source identities hash the actual shipped dashboards and actual engine rule', () => {
  const identities = sourceIdentities(root); assert.equal(identities.length, 3);
  for (const item of identities) assert.equal(item.sha256, sha(readFileSync(resolve(root, item.path))));
});
test('Grafana release has a fixed official URL and digest; corrupt or linked archives never execute', t => {
  assert.equal(grafanaPin.version, '13.2.2'); assert.equal(new URL(grafanaPin.url).hostname, 'dl.grafana.com');
  assert.equal(grafanaPin.sha256, '9662c838a09824fdb072e5f6fbdd45b62cf541b20f3d609ea5011e6e5f544c8f');
  const dir = context(t), file = resolve(dir, 'invalid.tgz'); writeFileSync(file, 'invalid');
  assert.throws(() => verifyGrafanaArchive(file), /DIGEST/u);
  assert.throws(() => verifyGrafanaArchive(dir), /ARCHIVE_FILE/u);
  const link = resolve(dir, 'link'); symlinkSync(file, link);
  assert.throws(() => verifyGrafanaArchive(link), /ARCHIVE_FILE/u);
});
function receipt() {
  // Protocol-only JPEG boundary bytes, deliberately not native screenshot/rendering evidence.
  const image = Buffer.alloc(6000); image.writeUInt16BE(0xffd8, 0); image.writeUInt16BE(0xffd9, image.length - 2);
  return { status: 'OPS_GRAFANA_BROWSER_VERIFIED', grafanaVersion: grafanaPin.version, browserVersion: 'Chrome/100.0',
    cjkFont: 'Noto Sans CJK SC', cjkGlyphCount: 5, distinctCjkGlyphs: 4,
    inputs: sourceIdentities(root), provisionedDashboards: 2, originalQueries: 25, engineQueries: 11,
    overviewPanelsRendered: 25, enginePanelsRendered: 8, navigationBothWays: true, viewerWriteDenied: true,
    anonymousReadDenied: true, cleanupPassed: true, realGrafana: true, realPrometheus: true, realChromium: true,
    businessDatabaseVerified: false, humanNotificationVerified: false, productionDeploymentVerified: false,
    metricSource: 'CONTROLLED_HTTP_FIXTURE', readings: [
      { state: 'healthy', values: ['3', '5', '2', '1'] },
      { state: 'unavailable', values: Array(4).fill('unknown') }, { state: 'empty', values: Array(4).fill('0') },
    ], screenshots: ['overview', 'engine-healthy', 'engine-unavailable', 'engine-empty'].map(name => ({ name,
      bytes: image.length, sha256: sha(image), base64: image.toString('base64') })) };
}
test('receipt rejects missing rendering, forged hashes, wrong values or inflated production claims', () => {
  validateGrafanaReceipt(receipt(), sourceIdentities(root));
  const mutations = [r => { r.cleanupPassed = false; }, r => { r.navigationBothWays = false; },
    r => { r.viewerWriteDenied = false; }, r => { r.businessDatabaseVerified = true; },
    r => { r.humanNotificationVerified = true; }, r => { r.realGrafana = false; },
    r => { r.cjkFont = 'Last Resort'; }, r => { r.cjkGlyphCount = 0; }, r => { r.distinctCjkGlyphs = 1; },
    r => { r.screenshots[0].sha256 = '0'.repeat(64); }, r => { r.screenshots.pop(); },
    r => { r.inputs[0].sha256 = '0'.repeat(64); }, r => { r.readings[1].values = Array(4).fill('0'); }];
  for (const mutate of mutations) { const r = receipt(); mutate(r); assert.throws(() => validateGrafanaReceipt(r, sourceIdentities(root))); }
});
test('download failure propagates once without extraction, fallback, or a success receipt', t => {
  const directory = context(t), prometheus = resolve(directory, 'prometheus'); writeFileSync(prometheus, 'controlled');
  const calls = [], messages = []; t.mock.method(console, 'log', (...args) => messages.push(args));
  const failure = new Error('EXPECTED_DOWNLOAD_FAILURE');
  assert.throws(() => verifyGrafanaBrowser({ directory, repositoryRoot: root, prometheus,
    runCommand: (...args) => { calls.push(args); throw failure; } }), e => e === failure);
  assert.equal(calls.length, 1); assert.equal(calls[0][0], 'curl');
  assert.ok(calls[0][1].includes('--max-filesize')); assert.ok(calls[0][1].includes('--proto-redir'));
  assert.ok(!calls[0][1].includes('--retry')); assert.deepEqual(messages, []);
});
test('missing native executable fails with owned listener and directory cleanup', { timeout: 12000 }, async t => {
  const directory = context(t); const before = { term: process.listenerCount('SIGTERM'), int: process.listenerCount('SIGINT') };
  await assert.rejects(runGrafanaBrowser({ directory, repositoryRoot: root,
    grafanaHome: resolve(directory, 'absent-home'), prometheus: resolve(directory, 'absent-prometheus') }), /GRAFANA_BROWSER_FAILED/u);
  assert.deepEqual(readdirSync(directory), []);
  assert.equal(process.listenerCount('SIGTERM'), before.term); assert.equal(process.listenerCount('SIGINT'), before.int);
});
test('real Chromium pipe reads visible fixture DOM, clicks a fragment, and closes pending commands', { timeout: 25000 }, async t => {
  const directory = context(t);
  const browser = new BrowserPipe(resolve(directory, 'chrome'), { PATH: process.env.PATH, HOME: directory, LANG: 'C.UTF-8' });
  try {
    assert.match(await browser.start(), /Chrome\//u);
    // This is an isolated about:blank component fixture, not Grafana or HTTP acceptance.
    // Mirrors v13.2.2 PanelChrome: section data-testid plus aria-labelledby title.
    await browser.evaluate(`document.body.innerHTML = '<section data-testid="data-testid Panel header 可执行任务" aria-labelledby="panel-title"><h2 id="panel-title">可执行任务</h2><div data-testid="data-testid panel content">3</div></section><a href="#next">next</a>'`);
    const panel = await browser.evaluate(panelReadExpression('可执行任务'));
    assert.equal(panel.text, '3'); assert.equal(panel.visible, true); assert.equal(panel.error, false);
    assert.ok((await browser.platformFonts('h2')).some(font => font.glyphCount > 0));
    assert.equal(await browser.evaluate(panelReadExpression('不存在')), null);
    // A legacy-looking decoy cannot supply values for the actual PanelChrome.
    await browser.evaluate(`document.body.insertAdjacentHTML('beforeend','<section aria-label="可执行任务 panel"><div data-testid="data-testid panel content">99</div></section>')`);
    assert.equal((await browser.evaluate(panelReadExpression('可执行任务'))).text, '3');
    await browser.evaluate(`document.querySelector('section[data-testid]').insertAdjacentHTML('beforeend','<button data-testid="data-testid Panel status error">Error</button>')`);
    assert.equal((await browser.evaluate(panelReadExpression('可执行任务'))).error, true);
    await browser.evaluate(`document.querySelector('button').remove(); document.querySelector('section[data-testid]').style.display='none'`);
    assert.equal((await browser.evaluate(panelReadExpression('可执行任务'))).visible, false);
    await browser.evaluate(`document.querySelector('section[data-testid]').style.display=''; document.body.append(document.querySelector('section[data-testid]').cloneNode(true))`);
    assert.equal(await browser.evaluate(panelReadExpression('可执行任务')), null, 'duplicate titles cannot silently select the first panel');
    await browser.evaluate(`document.querySelectorAll('section[data-testid]')[1].remove()`);
    await browser.evaluate("document.querySelector('a').click()");
    assert.ok((await browser.evaluate('location.href')).endsWith('#next'));
    const image = await browser.call('Page.captureScreenshot', { format: 'jpeg', quality: 50 });
    assert.equal(Buffer.from(image.data, 'base64').readUInt16BE(0), 0xffd8);
    await assert.rejects(browser.navigate('https://example.com/'), /LOOPBACK/u);
  } finally { await browser.stop(); }
  assert.equal(browser.pending.size, 0); await assert.rejects(browser.call('Browser.getVersion'), /CLOSED/u);
});
test('permanent provisioning reuses Prometheus and retains the prior native test ceiling', () => {
  const source = readFileSync(resolve(root, 'scripts/ops/verify-prometheus-rules.mjs'), 'utf8');
  assert.ok(source.includes("import { verifyGrafanaBrowser } from './verify-grafana-browser.mjs';"));
  assert.ok(source.includes('result.grafanaBrowser = verifyGrafanaBrowser('));
  assert.ok(source.includes('console.log(JSON.stringify(result.grafanaBrowser))'));
  const aggregate = readFileSync(resolve(root, 'scripts/tests/m4-sla-calendar-boundary.test.mjs'), 'utf8');
  assert.ok(aggregate.includes("import './ops-grafana-browser.test.mjs';"));
});

for (const mode of ['ci', 'local']) {
  test('font preparation reuses an isolated helper child in ' + mode + ' mode', async t => {
    const directory = context(t), file = resolve(directory, 'controlled-font-helper.mjs');
    writeFileSync(file, `export function ensureCjkFontRuntime() {
      if(process.env.GITHUB_ACTIONS!==${JSON.stringify(mode==='ci'?'true':'false')}) throw new Error('mode');
      if(Object.keys(process.env).some(k=>/TOKEN|SECRET|PASSWORD/.test(k))) throw new Error('unexpected credential');
      console.log('CJK_FONT_RUNTIME_READY=Noto Sans CJK SC');
    }`);
    assert.deepEqual(await prepareGrafanaFonts(mode, {moduleUrl:pathToFileURL(file).href}),
      {status:'OPS_GRAFANA_CJK_READY',family:'Noto Sans CJK SC'});
  });
}
test('font helper failure and timeout reject without leaving a child or signal handler', {timeout:10000}, async t => {
  const directory = context(t), file = resolve(directory, 'controlled-font-failure.mjs');
  const before=[process.listenerCount('SIGTERM'),process.listenerCount('SIGINT')];
  writeFileSync(file, "export function ensureCjkFontRuntime(){throw new Error('controlled failure')}");
  await assert.rejects(prepareGrafanaFonts('local',{moduleUrl:pathToFileURL(file).href}),/GRAFANA_FONT_PREPARATION_FAILED/u);
  writeFileSync(file, "export function ensureCjkFontRuntime(){setInterval(()=>{},1000)}");
  await assert.rejects(prepareGrafanaFonts('local',{moduleUrl:pathToFileURL(file).href,timeoutMs:300}),/GRAFANA_FONT_PREPARATION_TIMEOUT/u);
  await assert.rejects(prepareGrafanaFonts('unknown'),/GRAFANA_FONT_MODE/u);
  assert.deepEqual([process.listenerCount('SIGTERM'),process.listenerCount('SIGINT')],before);
});
