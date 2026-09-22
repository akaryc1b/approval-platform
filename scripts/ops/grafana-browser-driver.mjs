import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { existsSync, realpathSync } from 'node:fs';

/** Isolated Chromium over anonymous pipes; no debugger port, package download or shared profile. */
export function chromiumExecutable() {
  const file = ['/usr/bin/google-chrome', '/usr/bin/google-chrome-stable',
    '/usr/bin/chromium', '/usr/bin/chromium-browser'].find(existsSync);
  assert.ok(file, 'GRAFANA_BROWSER_CHROMIUM_REQUIRED');
  return realpathSync(file);
}

export function chromiumArguments(profile) {
  return ['--headless=new', '--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage',
    '--no-first-run', '--no-default-browser-check', '--disable-background-networking',
    '--disable-component-update', '--disable-sync', '--disable-extensions', '--disable-breakpad',
    '--password-store=basic', '--use-mock-keychain', '--remote-debugging-pipe',
    '--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1, EXCLUDE localhost', '--lang=en-US',
    '--user-data-dir=' + profile, 'about:blank'];
}

export class BrowserPipe {
  constructor(profile, environment) {
    this.child = spawn(chromiumExecutable(), chromiumArguments(profile), {
      env: environment, detached: true, stdio: ['ignore', 'ignore', 'ignore', 'pipe', 'pipe'],
    });
    this.next = 0; this.pending = new Map(); this.buffer = ''; this.exceptions = 0;
    this.closed = false;
    this.child.stdio[4].setEncoding('utf8');
    this.child.stdio[4].on('data', part => {
      this.buffer += part;
      if (this.buffer.length > 8 * 1024 * 1024) return this.fail('GRAFANA_BROWSER_PROTOCOL_LIMIT');
      for (let offset; (offset = this.buffer.indexOf('\0')) >= 0;) {
        const text = this.buffer.slice(0, offset); this.buffer = this.buffer.slice(offset + 1);
        if (!text) continue;
        let value;
        try { value = JSON.parse(text); } catch { this.fail('GRAFANA_BROWSER_PROTOCOL_INVALID'); continue; }
        if (value.method === 'Runtime.exceptionThrown') this.exceptions++;
        if (value.id && this.pending.has(value.id)) {
          const task = this.pending.get(value.id); this.pending.delete(value.id); clearTimeout(task.timer);
          if (value.error) task.reject(new Error('GRAFANA_BROWSER_COMMAND:' + task.method));
          else task.resolve(value.result);
        }
      }
    });
    this.child.on('error', () => this.fail('GRAFANA_BROWSER_START_FAILED'));
    this.child.on('exit', () => this.fail('GRAFANA_BROWSER_EXITED'));
    this.child.stdio[3].on('error', () => this.fail('GRAFANA_BROWSER_PIPE_CLOSED'));
  }
  fail(code) {
    this.closed = true;
    for (const task of this.pending.values()) { clearTimeout(task.timer); task.reject(new Error(code)); }
    this.pending.clear();
  }
  call(method, params = {}, sessionId = this.session) {
    if (this.closed) return Promise.reject(new Error('GRAFANA_BROWSER_CLOSED'));
    const id = ++this.next;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error('GRAFANA_BROWSER_TIMEOUT:' + method)); }, 10000);
      this.pending.set(id, { resolve, reject, timer, method });
      this.child.stdio[3].write(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }) + '\0');
    });
  }
  async start() {
    const version = await this.call('Browser.getVersion');
    const target = await this.call('Target.createTarget', { url: 'about:blank' });
    this.session = (await this.call('Target.attachToTarget', { targetId: target.targetId, flatten: true })).sessionId;
    await this.call('Page.enable'); await this.call('Runtime.enable');
    await this.call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 2000, deviceScaleFactor: 1, mobile: false });
    return version.product;
  }
  async evaluate(expression) {
    const result = await this.call('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    assert.ok(!result.exceptionDetails, 'GRAFANA_BROWSER_EVALUATION_FAILED');
    return result.result.value;
  }
  async navigate(url) {
    const parsed = new URL(url);
    assert.equal(parsed.hostname, '127.0.0.1', 'GRAFANA_BROWSER_LOOPBACK_ONLY');
    assert.equal(parsed.protocol, 'http:'); assert.equal(parsed.username, ''); assert.equal(parsed.password, '');
    const result = await this.call('Page.navigate', { url });
    assert.ok(!result.errorText, 'GRAFANA_BROWSER_NAVIGATION_FAILED');
  }
  async stop() {
    this.fail('GRAFANA_BROWSER_STOPPED');
    await stopProcess(this.child);
  }
}

export async function stopProcess(child) {
  if (!child?.pid) return;
  const exited = () => child.exitCode !== null || child.signalCode !== null;
  const kill = signal => { try { process.kill(-child.pid, signal); } catch (e) { if (e.code !== 'ESRCH') throw e; } };
  // Kill the owned process group even when the leader has already exited.
  kill('SIGTERM');
  if (!exited()) await Promise.race([new Promise(resolve => child.once('exit', resolve)), new Promise(resolve => setTimeout(resolve, 1500))]);
  kill('SIGKILL');
  if (!exited()) await Promise.race([new Promise(resolve => child.once('exit', resolve)), new Promise(resolve => setTimeout(resolve, 1500))]);
  assert.ok(exited(), 'GRAFANA_BROWSER_CHILD_CLEANUP_FAILED');
}

// Official v13.2.2 e2e selector: Panels.Panel.containerByTitle -> '<title> panel'.
export function panelReadExpression(title) {
  return `(() => {
    const panel = document.querySelector('[aria-label=' + JSON.stringify(${JSON.stringify(title + ' panel')}) + ']');
    if (!panel) return null;
    panel.scrollIntoView({block:'center'});
    const content = panel.querySelector('[data-testid="data-testid panel content"]');
    if (!content) return null;
    const rect = content.getBoundingClientRect();
    return {text:content.innerText.trim(), visible:rect.width>0 && rect.height>0,
      canvas:!!content.querySelector('canvas'), error:!!panel.querySelector('[data-testid="data-testid Panel status error"]')};
  })()`;
}

export function assertNavigation(url, destination, expected) {
  const actual = new URL(url);
  assert.equal(actual.hostname, '127.0.0.1');
  assert.ok(actual.pathname === '/d/' + destination || actual.pathname.startsWith('/d/' + destination + '/'));
  for (const name of ['var-datasource', 'var-environment', 'var-instance', 'from', 'to']) {
    assert.equal(actual.searchParams.get(name), expected[name], 'GRAFANA_BROWSER_NAVIGATION_CONTEXT:' + name);
  }
}
