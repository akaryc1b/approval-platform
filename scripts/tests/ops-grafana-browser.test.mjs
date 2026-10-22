import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { existsSync, mkdtempSync, readFileSync, readdirSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { prepareGrafanaFonts, provisionGrafanaCiFonts } from '../ops/grafana-browser-fonts.mjs';
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
  assert.deepEqual(args, ['--headless=new', '--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage',
    '--no-first-run', '--no-default-browser-check', '--disable-background-networking',
    '--disable-component-update', '--disable-sync', '--disable-extensions', '--disable-breakpad',
    '--password-store=basic', '--use-mock-keychain', '--remote-debugging-pipe',
    '--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1, EXCLUDE localhost', '--lang=en-US',
    '--user-data-dir=/tmp/owned-profile', '--no-startup-window']);
  assert.equal(args.filter(arg => arg === '--no-startup-window').length, 1);
  assert.ok(args.every(arg => arg.startsWith('--')), 'the launch must not open an implicit startup URL');
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
test('browser startup failures are attributed to startup rather than the last dashboard import', () => {
  const source = readFileSync(resolve(root, 'scripts/ops/grafana-browser-runtime.mjs'), 'utf8');
  // Source-boundary coverage only; native browser acceptance remains required separately.
  assert.equal(source.split("phase = 'browser-start';").length, 2);
  assert.match(source, /phase = 'browser-start';\s+startupCapture = createStartupEvidenceCapture\(owned\);\s+browser = new BrowserPipe\(resolve\(workspace, 'chrome'\), env, \{ diagnostics: startupCapture\.diagnostics \}\); const browserVersion = await browser\.start\(\);\s+phase = 'browser-login';/u);
  assert.match(source, /'GRAFANA_BROWSER_FAILED:' \+ phase/u);
});

test('login diagnostics distinguish four evaluation phases without changing execution bounds', () => {
  const source = readFileSync(resolve(root, 'scripts/ops/grafana-browser-runtime.mjs'), 'utf8');
  const driver = readFileSync(resolve(root, 'scripts/ops/grafana-browser-driver.mjs'), 'utf8');
  const login = source.slice(source.indexOf("phase = 'browser-login';"), source.indexOf('    const selected ='));
  // Source-boundary coverage only: no Grafana, Chromium, login or POST executes here.
  assert.deepEqual([...login.matchAll(/phase = '([^']+)';/gu)].map(match => match[1]), [
    'browser-login', 'browser-login-form', 'browser-login-submit', 'browser-login-session', 'browser-viewer-write',
  ]);
  assert.match(login, /phase = 'browser-login'; await browser\.navigate\(baseUrl \+ '\/login'\);/u);
  assert.match(login, /phase = 'browser-login-form';\s+await wait\(\(\) => browser\.evaluate\(`!!document\.querySelector/u);
  assert.match(login, /phase = 'browser-login-submit';\s+await browser\.evaluate\(`/u);
  assert.match(login, /phase = 'browser-login-session';\s+await wait\(\(\) => browser\.evaluate\(`fetch\('\/api\/user'\)/u);
  assert.match(login, /phase = 'browser-viewer-write';\s+const protectedStatus = await browser\.evaluate\(`fetch\('\/api\/dashboards\/db'/u);
  assert.equal([...login.matchAll(/browser\.evaluate\(/gu)].length, 4);
  assert.equal([...login.matchAll(/await wait\(/gu)].length, 2);
  assert.equal(login.split("document.querySelector('button[type=\"submit\"]').click();").length, 2);
  assert.equal(login.split("method:'POST'").length, 2);
  assert.match(login, /assert\.equal\(protectedStatus, 403, 'GRAFANA_BROWSER_VIEWER_WRITE_NOT_REJECTED'\)/u);
  assert.doesNotMatch(login, /\b(?:catch|try|retry)\b/u);
  assert.match(source, /const deadline = performance\.now\(\) \+ 105000;/u);
  assert.match(source, /while \(performance\.now\(\) < deadline && !abort\.signal\.aborted\)/u);
  assert.match(source, /const value = await read\(\); if \(value\) return value;/u);
  assert.match(source, /setTimeout\(resolve, 200\)/u);
  assert.match(driver, /this\.startupDiagnostics\.timeout\(startedAt, 10000\);\s+this\.fail\('GRAFANA_BROWSER_TIMEOUT:' \+ method\);\s+\}, 10000\);/u);
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

test('CI prerequisite runs the existing cold installer once; both later readiness and warm setup only probe', async t => {
  const directory = context(t), callsFile = resolve(directory, 'calls.jsonl'), installed = resolve(directory, 'installed');
  const fakeCommand = `#!${process.execPath}
    import {appendFileSync, existsSync, writeFileSync} from 'node:fs';
    import {basename} from 'node:path';
    const command=basename(process.argv[1]), args=process.argv.slice(2);
    appendFileSync(${JSON.stringify(callsFile)}, JSON.stringify({command,args,env:Object.keys(process.env).sort()})+'\\n');
    if(command==='fc-cache') writeFileSync(${JSON.stringify(installed)}, 'installed');
    if(command==='fc-list' && existsSync(${JSON.stringify(installed)})) console.log('Noto Sans CJK SC');
  `;
  for (const name of ['fc-list', 'sudo', 'fc-cache']) writeFileSync(resolve(directory, name), fakeCommand, {mode:0o700});
  const oldPath = process.env.PATH, oldSecret = process.env.GRAFANA_FIXTURE_SECRET;
  process.env.PATH = directory; process.env.GRAFANA_FIXTURE_SECRET = 'not-for-the-child';
  try {
    const expected = {status:'OPS_GRAFANA_CJK_READY',family:'Noto Sans CJK SC'};
    assert.deepEqual(await provisionGrafanaCiFonts(), expected);
    const calls = () => readFileSync(callsFile, 'utf8').trim().split('\n').map(line => JSON.parse(line));
    assert.deepEqual(calls().map(({command,args}) => [command,args]), [
      ['fc-list',[':lang=zh','family']], ['sudo',['-n','apt-get','update','-qq']],
      ['sudo',['-n','apt-get','install','-y','--no-install-recommends','fontconfig','fonts-noto-cjk']],
      ['fc-cache',['-f']], ['fc-list',[':lang=zh','family']],
    ]);
    assert.deepEqual(await prepareGrafanaFonts('ci'), expected);
    assert.deepEqual(await provisionGrafanaCiFonts(), expected);
    assert.deepEqual(calls().slice(5).map(call => call.command), ['fc-list','fc-list']);
    assert.ok(calls().every(call => call.env.every(name => ['PATH','LANG','LC_ALL','GITHUB_ACTIONS','DEBIAN_FRONTEND'].includes(name))));
  } finally {
    process.env.PATH = oldPath;
    if (oldSecret === undefined) delete process.env.GRAFANA_FIXTURE_SECRET;
    else process.env.GRAFANA_FIXTURE_SECRET = oldSecret;
  }
});

test('cold setup has an explicit 570s ceiling without extending the 60s runtime ceiling', async t => {
  const directory = context(t), file = resolve(directory, 'stalled-font-helper.mjs');
  writeFileSync(file, 'export function ensureCjkFontRuntime(){setInterval(()=>{},1000)}');
  t.mock.timers.enable({apis:['setTimeout']});
  let settled = false;
  const pending = provisionGrafanaCiFonts({moduleUrl:pathToFileURL(file).href});
  const checked = assert.rejects(pending, /GRAFANA_FONT_PREPARATION_TIMEOUT/u).then(() => { settled = true; });
  t.mock.timers.tick(60000); await Promise.resolve(); assert.equal(settled, false);
  t.mock.timers.tick(509999); await Promise.resolve(); assert.equal(settled, false);
  t.mock.timers.tick(1); await checked;
  await assert.rejects(provisionGrafanaCiFonts({timeoutMs:570001}), /GRAFANA_FONT_PROVISION_TIMEOUT/u);
  await assert.rejects(prepareGrafanaFonts('ci',{timeoutMs:60001}), /GRAFANA_FONT_TIMEOUT/u);
});

test('cold setup rejects installer failure and invalid font identity without leaking a signal handler', async t => {
  const directory = context(t), file = resolve(directory, 'failed-font-helper.mjs');
  const before = [process.listenerCount('SIGTERM'),process.listenerCount('SIGINT')];
  for (const body of ["throw new Error('controlled install failure')", "console.log('CJK_FONT_RUNTIME_READY=Arial')",
    "console.log('CJK_FONT_RUNTIME_READY=Noto Sans CJK SC\\nCJK_FONT_RUNTIME_READY=Noto Sans CJK SC')"]) {
    writeFileSync(file, 'export function ensureCjkFontRuntime(){' + body + '}');
    await assert.rejects(provisionGrafanaCiFonts({moduleUrl:pathToFileURL(file).href}));
    assert.deepEqual([process.listenerCount('SIGTERM'),process.listenerCount('SIGINT')], before);
  }
});

test('the actual M4 entry provisions once before all runtime bodies and fails closed without changing their budgets', t => {
  const directory = context(t), file = resolve(directory, 'aggregate.test.mjs');
  const entry = readFileSync(resolve(root, 'scripts/tests/m4-sla-calendar-boundary.test.mjs'), 'utf8');
  const url = source => 'data:text/javascript,' + encodeURIComponent(source);
  for (const mode of ['ci', 'local', 'failed']) {
    let count = 0;
    const bodies = resolve(directory, 'bodies-' + mode);
    const helper = url(`export async function provisionGrafanaCiFonts(){
      if(globalThis.prepared) throw Error('setup repeated');
      await new Promise(resolve=>setTimeout(resolve,50));
      ${mode === 'failed' ? "throw Error('controlled prerequisite failure');" : 'globalThis.prepared=true;'}
    }`);
    const source = entry.replace("'../ops/grafana-browser-fonts.mjs'", JSON.stringify(helper))
      .replace(/import '\.\/[^']+\.test\.mjs';/gu, () => {
        const body = `import assert from 'node:assert/strict';import test from 'node:test';import {appendFileSync} from 'node:fs';
          test('runtime ${++count}',{timeout:20},()=>{
            assert.equal(globalThis.prepared,${mode === 'local' ? 'undefined' : 'true'});
            appendFileSync(${JSON.stringify(bodies)},'x');
          });`;
        return `import ${JSON.stringify(url(body))};`;
      });
    assert.equal(count, 16, 'retain every original aggregate import');
    writeFileSync(file, source);
    const result = spawnSync(process.execPath, ['--test', file], {encoding:'utf8',timeout:10000,
      env:{PATH:process.env.PATH,GITHUB_ACTIONS:mode === 'local' ? 'false' : 'true'}});
    assert.equal(result.status, mode === 'failed' ? 1 : 0, result.stdout + result.stderr);
    assert.equal(existsSync(bodies) ? readFileSync(bodies).length : 0, mode === 'failed' ? 0 : 16);
  }
});

test('font failures retain the actual bounded installer phase and status without native output', async t => {
  const directory = context(t), file = resolve(directory, 'observed-font-helper.mjs');
  const source = readFileSync(resolve(root, 'scripts/product-readiness/quick-start/cjk-fonts.mjs'), 'utf8');
  for (const [phase, failAt, result] of [
    ['probe-before',1,{status:100}], ['package-index',2,{status:100}],
    ['package-install',3,{status:null,signal:'SIGTERM',error:{code:'ETIMEDOUT',message:'PRIVATE native timeout'}}],
    ['font-cache',4,{status:null,signal:'PRIVATE signal',error:{code:'PRIVATE error',message:'PRIVATE detail'}}],
    ['probe-after',5,{status:100}],
  ]) {
    const fake = `let calls=0;function spawnSync(command,args,options){
      calls++;
      if(options.timeout!==[10000,180000,240000,120000,10000][calls-1]) throw Error('changed phase budget');
      process.stderr.write('PRIVATE native output /private/path credential=value\\n');
      return calls===${failAt} ? {...${JSON.stringify(result)},stdout:'PRIVATE stdout',stderr:'PRIVATE stderr'} : {status:0,stdout:''};
    }`;
    writeFileSync(file, source.replace("import { spawnSync } from 'node:child_process';", fake));
    const pending = failAt === 1 ? prepareGrafanaFonts('local',{moduleUrl:pathToFileURL(file).href})
      : provisionGrafanaCiFonts({moduleUrl:pathToFileURL(file).href});
    await assert.rejects(pending, error => {
      assert.ok(error.message.startsWith('GRAFANA_FONT_PREPARATION_FAILED:phase=' + phase + ',state=complete,'));
      assert.match(error.message, /,childExit=1,childSignal=none$/u);
      assert.doesNotMatch(error.message, /PRIVATE|private|credential|value/u);
      if (phase === 'package-install') assert.match(error.message, /exitCode=none,signal=SIGTERM,error=ETIMEDOUT/u);
      else if (phase === 'font-cache') assert.match(error.message, /exitCode=none,signal=other,error=other/u);
      else assert.match(error.message, /exitCode=100,signal=none,error=none/u);
      assert.ok(error.message.length < 300); return true;
    });
  }
});

test('font diagnostic records are strict bounded optional data, never a success or error-text channel', async t => {
  const directory = context(t), file = resolve(directory, 'untrusted-font-diagnostics.mjs');
  const valid = {phase:'package-install',state:'start',elapsedMs:0,exitCode:null,signal:null,error:null};
  const invalid = [null,[],{}, {...valid,private:'PRIVATE'}, {...valid,phase:'PRIVATE'}, {...valid,state:'PRIVATE'},
    {...valid,elapsedMs:570001}, {...valid,elapsedMs:-1}, {...valid,elapsedMs:'PRIVATE'},
    {...valid,exitCode:256}, {...valid,exitCode:'PRIVATE'}, {...valid,signal:'PRIVATE'}, {...valid,error:'PRIVATE'}];
  for (const withValid of [false,true]) {
    writeFileSync(file, `export function ensureCjkFontRuntime({observe}){
      ${withValid ? 'observe(' + JSON.stringify(valid) + ');' : ''}
      for(const value of ${JSON.stringify(invalid)}) observe(value);
      console.log('CJK_FONT_RUNTIME_PHASE='+ 'PRIVATE'.repeat(50));
      console.log('PRIVATE unrelated stdout');console.error('PRIVATE stderr');throw Error('PRIVATE failure');
    }`);
    await assert.rejects(provisionGrafanaCiFonts({moduleUrl:pathToFileURL(file).href}), error => {
      assert.ok(error.message.includes('phase=' + (withValid ? 'package-install,state=start' : 'unknown,state=unknown')));
      assert.doesNotMatch(error.message, /PRIVATE|unrelated|failure/u); return true;
    });
  }
});

test('font timeout and native child signal report the last phase without changing cleanup or bounds', async t => {
  const directory = context(t), file = resolve(directory, 'unfinished-font-phase.mjs');
  const before = [process.listenerCount('SIGTERM'),process.listenerCount('SIGINT')];
  for (const signal of [false,true]) {
    writeFileSync(file, `export function ensureCjkFontRuntime({observe}){
      observe({phase:'package-index',state:'start',elapsedMs:0,exitCode:null,signal:null,error:null});
      ${signal ? "process.kill(process.pid,'SIGTERM');" : 'setInterval(()=>{},1000);'}
    }`);
    await assert.rejects(prepareGrafanaFonts('ci',{moduleUrl:pathToFileURL(file).href,timeoutMs:300}), error => {
      assert.ok(error.message.startsWith('GRAFANA_FONT_PREPARATION_' + (signal ? 'FAILED' : 'TIMEOUT') + ':phase=package-index,state=start,'));
      assert.ok(error.message.endsWith('childExit=none,childSignal=' + (signal ? 'SIGTERM' : 'none')));
      return true;
    });
    assert.deepEqual([process.listenerCount('SIGTERM'),process.listenerCount('SIGINT')], before);
  }
});
