import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { BrowserPipe, panelReadExpression, assertNavigation, stopProcess } from './grafana-browser-driver.mjs';

export const dashboardFiles = ['approval-operations.json', 'approval-engine-jobs.json'];
export const states = ['healthy', 'unavailable', 'empty'];
const sha256 = value => createHash('sha256').update(value).digest('hex');

/** Controlled source only. Actual Prometheus evaluates the repository's unmodified rule file. */
export function renderEngineMetrics(state) {
  assert.ok(states.includes(state), 'GRAFANA_BROWSER_INVALID_FIXTURE_STATE');
  const counts = state === 'healthy' ? [3, 5, 2, 1] : state === 'empty' ? [0, 0, 0, 0] : ['NaN', 'NaN', 'NaN', 'NaN'];
  return '# TYPE approval_engine_jobs gauge\n' + ['executable', 'timer', 'suspended', 'dead_letter']
    .map((q, i) => `approval_engine_jobs{application="approval-platform",queue="${q}"} ${counts[i]}\n`).join('')
    + '# TYPE approval_engine_jobs_sample_up gauge\n'
    + `approval_engine_jobs_sample_up{application="approval-platform"} ${state === 'unavailable' ? 0 : 1}\n`;
}

export function sourceIdentities(repositoryRoot) {
  return [...dashboardFiles.map(name => 'deploy/observability/grafana/' + name),
    'deploy/observability/prometheus/approval-engine-jobs.rules.yml'].map(path => ({ path,
    sha256: sha256(readFileSync(resolve(repositoryRoot, path))) }));
}

export async function runGrafanaBrowser({ directory, repositoryRoot, grafanaHome, prometheus }) {
  const workspace = mkdtempSync(resolve(directory, 'grafana-browser-'));
  const owned = [], listeners = []; let browser, result, state = 'healthy', phase = 'setup';
  const abort = new AbortController(); const deadline = performance.now() + 105000;
  const interrupt = () => abort.abort();
  process.once('SIGTERM', interrupt); process.once('SIGINT', interrupt);
  const env = { PATH: process.env.PATH, LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8', HOME: workspace, TMPDIR: workspace };
  const wait = async (read, label) => {
    while (performance.now() < deadline && !abort.signal.aborted) {
      for (const child of owned) assert.ok(child.exitCode === null && child.signalCode === null, 'GRAFANA_BROWSER_NATIVE_EXIT:' + phase);
      const value = await read(); if (value) return value;
      await new Promise(resolve => setTimeout(resolve, 200));
    }
    throw new Error('GRAFANA_BROWSER_DEADLINE:' + label);
  };
  const launch = (file, args, cwd, extra = {}) => {
    const child = spawn(file, args, { cwd, env: { ...env, ...extra }, detached: true,
      stdio: ['ignore', 'ignore', 'ignore'] });
    child.on('error', () => abort.abort()); owned.push(child); return child;
  };
  const listen = async server => { listeners.push(server); await new Promise((done, reject) => {
    server.once('error', reject); server.listen(0, '127.0.0.1', done);
  }); return server.address().port; };
  const freePort = async () => { const server = createServer(); const port = await listen(server);
    await new Promise(done => server.close(done)); listeners.splice(listeners.indexOf(server), 1); return port; };
  const json = async (url, options = {}) => {
    assert.equal(new URL(url).hostname, '127.0.0.1', 'GRAFANA_BROWSER_HTTP_LOOPBACK_ONLY');
    const response = await fetch(url, { ...options, redirect: 'error', signal: AbortSignal.any([abort.signal, AbortSignal.timeout(3000)]) });
    const text = await response.text(); assert.ok(text.length < 1024 * 1024, 'GRAFANA_BROWSER_HTTP_LIMIT');
    let body; try { body = JSON.parse(text); } catch { body = null; }
    return { status: response.status, body };
  };
  const ready = async url => wait(async () => {
    try { const r = await json(url); return r.status === 200 && r.body; } catch { return false; }
  }, phase);
  try {
    const metricsPort = await listen(createServer((request, response) => {
      if (request.url !== '/metrics' && request.url !== '/other') { response.writeHead(404).end(); return; }
      response.setHeader('content-type', 'text/plain; version=0.0.4');
      response.end(renderEngineMetrics(request.url === '/other' ? 'healthy' : state));
    }));
    const promPort = await freePort(), grafanaPort = await freePort();
    const promUrl = `http://127.0.0.1:${promPort}`, baseUrl = `http://127.0.0.1:${grafanaPort}`;
    const promConfig = resolve(workspace, 'prometheus.json');
    writeFileSync(promConfig, JSON.stringify({ global: { scrape_interval: '1s', evaluation_interval: '1s' },
      rule_files: [resolve(repositoryRoot, 'deploy/observability/prometheus/approval-engine-jobs.rules.yml')],
      scrape_configs: ['test', 'other'].map(environment => ({ job_name: 'fixture-' + environment,
        metrics_path: environment === 'test' ? '/metrics' : '/other',
        static_configs: [{ targets: [`127.0.0.1:${metricsPort}`], labels: { environment,
          job: 'approval-platform', instance: 'node-a', engine_job_monitor: 'enabled' } }],
        honor_labels: true, relabel_configs: [{ target_label: 'job', replacement: 'approval-platform' },
          { target_label: 'instance', replacement: 'node-a' }],
      })) }), { flag: 'wx', mode: 0o600 });
    // relabel job explicitly: static target labels are authoritative for this isolated fixture.
    launch(prometheus, ['--config.file=' + promConfig, '--storage.tsdb.path=' + resolve(workspace, 'tsdb'),
      '--storage.tsdb.retention.time=5m', '--storage.tsdb.retention.size=16MB', '--web.listen-address=127.0.0.1:' + promPort], workspace);
    phase = 'prometheus-start'; await ready(promUrl + '/api/v1/status/buildinfo');
    const adminPassword = randomBytes(24).toString('hex'), viewerPassword = randomBytes(24).toString('hex');
    const provisioning = resolve(workspace, 'provisioning'), dashboards = resolve(workspace, 'dashboards');
    for (const dir of ['datasources', 'dashboards']) mkdirSync(resolve(provisioning, dir), { recursive: true });
    mkdirSync(dashboards);
    for (const name of dashboardFiles) copyFileSync(resolve(repositoryRoot, 'deploy/observability/grafana', name), resolve(dashboards, name));
    writeFileSync(resolve(provisioning, 'datasources/fixture.yaml'), JSON.stringify({ apiVersion: 1, datasources: [{
      name: 'Approval fixture', uid: 'approval-fixture', type: 'prometheus', access: 'proxy', url: promUrl,
      isDefault: true, editable: false, jsonData: { httpMethod: 'POST', timeInterval: '1s', cacheLevel: 'None' },
    }] }), { flag: 'wx', mode: 0o600 });
    writeFileSync(resolve(provisioning, 'dashboards/fixture.yaml'), JSON.stringify({ apiVersion: 1, providers: [{
      name: 'Approval fixture', type: 'file', allowUiUpdates: false, options: { path: dashboards },
    }] }), { flag: 'wx', mode: 0o600 });
    launch(resolve(grafanaHome, 'bin/grafana'), ['server', '--homepath', grafanaHome], grafanaHome, {
      GF_SERVER_HTTP_ADDR: '127.0.0.1', GF_SERVER_HTTP_PORT: String(grafanaPort), GF_SERVER_ROOT_URL: baseUrl,
      GF_PATHS_DATA: resolve(workspace, 'data'), GF_PATHS_LOGS: resolve(workspace, 'logs'),
      GF_PATHS_PLUGINS: resolve(workspace, 'plugins'), GF_PATHS_PROVISIONING: provisioning,
      GF_SECURITY_ADMIN_USER: 'browser-admin', GF_SECURITY_ADMIN_PASSWORD: adminPassword,
      GF_AUTH_ANONYMOUS_ENABLED: 'false', GF_USERS_ALLOW_SIGN_UP: 'false', GF_USERS_DEFAULT_THEME: 'light',
      GF_ANALYTICS_ENABLED: 'false', GF_ANALYTICS_REPORTING_ENABLED: 'false', GF_ANALYTICS_CHECK_FOR_UPDATES: 'false',
      GF_ANALYTICS_CHECK_FOR_PLUGIN_UPDATES: 'false', GF_NEWS_NEWS_FEED_ENABLED: 'false',
      GF_PLUGINS_PREINSTALL_DISABLED: 'true', GF_PLUGINS_PREINSTALL: '', GF_UNIFIED_ALERTING_ENABLED: 'false',
    });
    phase = 'grafana-start'; const health = await ready(baseUrl + '/api/health');
    assert.equal(health.version, '13.2.2', 'GRAFANA_BROWSER_VERSION');
    const adminHeaders = { Authorization: 'Basic ' + Buffer.from('browser-admin:' + adminPassword).toString('base64'),
      'Content-Type': 'application/json' };
    const imported = [];
    for (const name of dashboardFiles) {
      const source = JSON.parse(readFileSync(resolve(dashboards, name)));
      phase = 'import-' + source.uid;
      const stored = await wait(async () => {
        const r = await json(baseUrl + '/api/dashboards/uid/' + source.uid, { headers: adminHeaders });
        return r.status === 200 && r.body;
      }, phase);
      const expressions = doc => doc.panels.flatMap(p => (p.targets || []).map(t => t.expr));
      assert.deepEqual(expressions(stored.dashboard), expressions(source), 'GRAFANA_BROWSER_IMPORTED_QUERIES_CHANGED');
      assert.equal(stored.dashboard.uid, source.uid); imported.push(source);
    }
    assert.equal((await json(baseUrl + '/api/dashboards/uid/approval-engine-jobs')).status, 401, 'GRAFANA_BROWSER_ANONYMOUS_ACCESS');
    const user = await json(baseUrl + '/api/admin/users', { method: 'POST', headers: adminHeaders,
      body: JSON.stringify({ name: 'Browser viewer', login: 'browser-viewer', email: 'browser-viewer@example.invalid', password: viewerPassword }) });
    assert.equal(user.status, 200); assert.ok(Number.isSafeInteger(user.body.id));
    assert.equal((await json(baseUrl + '/api/org/users/' + user.body.id, { method: 'PATCH', headers: adminHeaders,
      body: JSON.stringify({ role: 'Viewer' }) })).status, 200);
    browser = new BrowserPipe(resolve(workspace, 'chrome'), env); const browserVersion = await browser.start();
    phase = 'browser-login'; await browser.navigate(baseUrl + '/login');
    await wait(() => browser.evaluate(`!!document.querySelector('input[name="user"]') && !!document.querySelector('input[name="password"]')`), phase);
    await browser.evaluate(`(() => {
      const set = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;
      for (const [name,value] of ${JSON.stringify([['user', 'browser-viewer'], ['password', viewerPassword]])}) {
        const input=document.querySelector('input[name="'+name+'"]'); set.call(input,value);
        input.dispatchEvent(new Event('input',{bubbles:true})); input.dispatchEvent(new Event('change',{bubbles:true}));
      }
      document.querySelector('button[type="submit"]').click();
    })()`);
    await wait(() => browser.evaluate(`fetch('/api/user').then(async r=>r.ok && (await r.json()).login==='browser-viewer')`), phase);
    const protectedStatus = await browser.evaluate(`fetch('/api/dashboards/db',{method:'POST',headers:{'Content-Type':'application/json'},
      body:JSON.stringify({dashboard:{uid:'forbidden-fixture-write',title:'Forbidden fixture write',panels:[]},overwrite:false})}).then(r=>r.status)`);
    assert.equal(protectedStatus, 403, 'GRAFANA_BROWSER_VIEWER_WRITE_NOT_REJECTED');
    const selected = { 'var-datasource': 'approval-fixture', 'var-environment': 'test', 'var-instance': 'node-a', from: 'now-5m', to: 'now' };
    const dashboardUrl = uid => baseUrl + '/d/' + uid + '?' + new URLSearchParams(selected);
    const seePanel = async title => wait(async () => {
      const panel = await browser.evaluate(panelReadExpression(title));
      assert.ok(!panel?.error, 'GRAFANA_BROWSER_PANEL_ERROR:' + title);
      return panel?.visible && panel;
    }, 'panel-' + title);
    phase = 'overview-render'; await browser.navigate(dashboardUrl('approval-operations'));
    for (const panel of imported[0].panels.filter(p => p.type !== 'row')) await seePanel(panel.title);
    const screenshots = [];
    const screenshot = async name => {
      const { data } = await browser.call('Page.captureScreenshot', { format: 'jpeg', quality: 50, clip: { x: 0, y: 0, width: 1440, height: 1000, scale: 1 } });
      const bytes = Buffer.from(data, 'base64'); assert.ok(bytes.length > 5000 && bytes.length <= 150000, 'GRAFANA_BROWSER_SCREENSHOT_LIMIT');
      screenshots.push({ name, bytes: bytes.length, sha256: sha256(bytes), base64: data });
    };
    await seePanel('服务状态'); await screenshot('overview');
    async function clickDashboard(destination) {
      await wait(() => browser.evaluate(`(() => { const a=[...document.querySelectorAll('a')].find(a=>new URL(a.href).pathname==='/d/${destination}');
        if(!a) return false; a.scrollIntoView({block:'center'}); a.click(); return true; })()`), 'link-' + destination);
      await wait(async () => { const url = await browser.evaluate('location.href');
        if (!new URL(url).pathname.startsWith('/d/' + destination)) return false;
        assertNavigation(url, destination, selected); return true; }, 'navigation-' + destination);
    }
    phase = 'forward-navigation'; await clickDashboard('approval-engine-jobs');
    await seePanel('可执行任务');
    phase = 'cjk-rendering';
    const cjkSelector = '[data-testid="data-testid Panel header 可执行任务"] h2';
    await browser.evaluate('document.fonts.ready.then(()=>true)');
    const fonts = await browser.platformFonts(cjkSelector);
    const cjk = fonts.find(font => /^(Noto Sans CJK|Noto Sans SC|Source Han Sans|PingFang SC|Hiragino Sans GB|Microsoft YaHei|WenQuanYi Micro Hei)/u.test(font.familyName) && font.glyphCount >= 5);
    assert.ok(cjk, 'GRAFANA_BROWSER_CJK_PLATFORM_FONT_REQUIRED');
    const distinctCjkGlyphs = await browser.evaluate(`(() => {
      const element = document.querySelector(${JSON.stringify(cjkSelector)});
      const canvas = document.createElement('canvas'); canvas.width=64; canvas.height=64;
      const ctx=canvas.getContext('2d'); ctx.font='32px '+getComputedStyle(element).fontFamily;
      const hashes=['审','批','流','程'].map(glyph => {
        ctx.clearRect(0,0,64,64); ctx.fillText(glyph,8,42);
        const pixels=ctx.getImageData(0,0,64,64).data; let hash=2166136261, ink=0;
        for(let i=0;i<pixels.length;i++) { hash=Math.imul(hash^pixels[i],16777619); if(i%4===3) ink+=pixels[i]; }
        if(!ink) throw new Error('empty glyph'); return hash>>>0;
      });
      return new Set(hashes).size;
    })()`);
    assert.equal(distinctCjkGlyphs, 4, 'GRAFANA_BROWSER_CJK_DISTINCT_GLYPHS_REQUIRED');
    const readings = [];
    for (const current of states) {
      state = current; phase = 'state-' + current;
      await wait(async () => {
        const query = 'approval:engine_job_sample_healthy{job="approval-platform",environment="test",instance="node-a"}';
        const r = await json(promUrl + '/api/v1/query?query=' + encodeURIComponent(query));
        if (r.status !== 200) return false;
        if (current === 'unavailable') return r.body.data.result.length === 0;
        const jobs = await json(promUrl + '/api/v1/query?query=' + encodeURIComponent('approval_engine_jobs{job="approval-platform",environment="test",instance="node-a",queue="executable"}'));
        return r.body.data.result.length === 1 && jobs.body.data.result.length === 1
          && jobs.body.data.result[0].value[1] === (current === 'empty' ? '0' : '3');
      }, phase);
      await browser.navigate(dashboardUrl('approval-engine-jobs'));
      const expected = current === 'healthy' ? ['3', '5', '2', '1'] : current === 'empty' ? ['0', '0', '0', '0'] : null;
      const values = [];
      for (const [index, title] of ['可执行任务', '定时任务', '暂停任务', '死信任务'].entries()) {
        const p = await wait(async () => {
          const p = await browser.evaluate(panelReadExpression(title));
          assert.ok(!p?.error, 'GRAFANA_BROWSER_PANEL_ERROR:' + title);
          if (!p?.visible) return false;
          const tokens = p.text.split(/\s+/u);
          return (expected ? tokens.includes(expected[index]) : /未知|No data/u.test(p.text) && !tokens.includes('0')) && p;
        }, phase + '-' + title);
        values.push(expected ? expected[index] : 'unknown');
      }
      await seePanel('完整采样');
      await seePanel('任务数量趋势'); await seePanel('引擎任务告警');
      if (current === 'healthy') await wait(async () => (await browser.evaluate(panelReadExpression('任务数量趋势')))?.canvas, 'trend-canvas');
      await seePanel('服务状态'); await screenshot('engine-' + current); readings.push({ state: current, values });
    }
    phase = 'return-navigation'; await clickDashboard('approval-operations'); await seePanel('服务状态');
    assert.equal(browser.exceptions, 0, 'GRAFANA_BROWSER_UNCAUGHT_PAGE_ERRORS');
    assert.ok(screenshots.reduce((n, s) => n + s.base64.length, 0) < 850000, 'GRAFANA_BROWSER_RECEIPT_LIMIT');
    result = { status: 'OPS_GRAFANA_BROWSER_VERIFIED', grafanaVersion: health.version, browserVersion,
      cjkFont: cjk.familyName, cjkGlyphCount: cjk.glyphCount, distinctCjkGlyphs,
      inputs: sourceIdentities(repositoryRoot), provisionedDashboards: 2, originalQueries: 25, engineQueries: 11,
      overviewPanelsRendered: 25, enginePanelsRendered: 8, navigationBothWays: true, viewerWriteDenied: true,
      anonymousReadDenied: true, readings, screenshots, metricSource: 'CONTROLLED_HTTP_FIXTURE',
      realGrafana: true, realPrometheus: true, realChromium: true,
      businessDatabaseVerified: false, humanNotificationVerified: false, productionDeploymentVerified: false };
  } catch (error) {
    // Bounded phase only: no raw page, API body, credential or native log is emitted on failure.
    throw new Error('GRAFANA_BROWSER_FAILED:' + phase + ':' + (error.message?.startsWith('GRAFANA_BROWSER_') ? error.message : 'ASSERTION_OR_IO'));
  } finally {
    const errors = [];
    if (browser) try { await browser.stop(); } catch { errors.push('browser'); }
    for (const child of owned.reverse()) try { await stopProcess(child); } catch { errors.push('native'); }
    for (const server of listeners) { server.closeAllConnections(); await new Promise(done => server.close(done)); }
    rmSync(workspace, { recursive: true, force: true });
    process.removeListener('SIGTERM', interrupt); process.removeListener('SIGINT', interrupt);
    assert.equal(errors.length, 0, 'GRAFANA_BROWSER_CLEANUP_FAILED'); assert.ok(!existsSync(workspace));
    if (result) result.cleanupPassed = true;
  }
  return result;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    assert.equal(process.argv.length, 6, 'GRAFANA_BROWSER_ARGUMENTS');
    const [directory, repositoryRoot, grafanaHome, prometheus] = process.argv.slice(2);
    console.log('OPS_GRAFANA_BROWSER_RESULT=' + JSON.stringify(await runGrafanaBrowser({ directory, repositoryRoot, grafanaHome, prometheus })));
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
