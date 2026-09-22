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
  constructor(profile, environment, { launch = spawn } = {}) {
    this.child = launch(chromiumExecutable(), chromiumArguments(profile), {
      env: environment, detached: true, stdio: ['ignore', 'ignore', 'pipe', 'pipe', 'pipe'],
    });
    this.next = 0; this.pending = new Map(); this.buffer = ''; this.exceptions = 0;
    this.closed = false; this.failure = null;
    this.started = false; this.stderrBytes = 0; this.protocolBytes = 0; this.responses = 0;
    this.nativeCategories = new Set(); this.nativeTail = '';
    this.child.once('spawn', () => { this.started = true; });
    // Drain stderr, but retain/export only bounded counters and fixed categories. Never raw logs.
    this.child.stderr.on('data', part => {
      if (this.closed) return;
      this.stderrBytes = Math.min(1024 * 1024, this.stderrBytes + part.length);
      const text = this.nativeTail + part.toString('utf8');
      for (const [category, pattern] of [
        ['dbus', /dbus|D-Bus/i], ['fontconfig', /fontconfig|font cache/i],
        ['devtools', /devtools|remote.debugging/i], ['crashpad', /crashpad/i],
        ['resource', /out of memory|cannot allocate|resource temporarily unavailable|pthread_create|too many open files/i],
        ['permission', /permission denied|operation not permitted/i],
        ['policy', /disallowed|disabled by.*policy|policy.*disabled/i],
        ['library', /error while loading shared libraries|symbol lookup error/i],
        ['sandbox', /sandbox/i],
      ]) if (pattern.test(text)) this.nativeCategories.add(category);
      this.nativeTail = text.slice(-128);
    });
    this.child.stderr.on('error', () => this.fail('GRAFANA_BROWSER_STDERR_CLOSED'));
    this.child.stdio[4].setEncoding('utf8');
    this.child.stdio[4].on('data', part => {
      if (this.closed) return;
      this.protocolBytes = Math.min(64 * 1024 * 1024, this.protocolBytes + Buffer.byteLength(part));
      this.buffer += part;
      if (this.buffer.length > 8 * 1024 * 1024) return this.fail('GRAFANA_BROWSER_PROTOCOL_LIMIT');
      for (let offset; (offset = this.buffer.indexOf('\0')) >= 0;) {
        const text = this.buffer.slice(0, offset); this.buffer = this.buffer.slice(offset + 1);
        if (!text) continue;
        let value;
        try { value = JSON.parse(text); } catch { return this.fail('GRAFANA_BROWSER_PROTOCOL_INVALID'); }
        if (!value || typeof value !== 'object' || Array.isArray(value)) {
          return this.fail('GRAFANA_BROWSER_PROTOCOL_INVALID');
        }
        if (value.method === 'Runtime.exceptionThrown') this.exceptions++;
        if (value.id && this.pending.has(value.id)) {
          this.responses++;
          const task = this.pending.get(value.id); this.pending.delete(value.id); clearTimeout(task.timer);
          if (value.error) task.reject(new Error('GRAFANA_BROWSER_COMMAND:' + task.method));
          else task.resolve(value.result);
        }
      }
    });
    this.child.stdio[4].on('end', () => this.fail('GRAFANA_BROWSER_PIPE_ENDED'));
    this.child.stdio[4].on('error', () => this.fail('GRAFANA_BROWSER_PIPE_CLOSED'));
    this.child.on('error', () => this.fail('GRAFANA_BROWSER_START_FAILED'));
    this.child.on('exit', () => this.fail('GRAFANA_BROWSER_EXITED'));
    this.child.stdio[3].on('error', () => this.fail('GRAFANA_BROWSER_PIPE_CLOSED'));
  }
  diagnosticSummary() {
    return `spawn=${Number(this.started)},stderr=${this.stderrBytes},protocol=${this.protocolBytes},responses=${this.responses}`
      + ',categories=' + ([...this.nativeCategories].sort().join('+') || 'none');
  }
  fail(code) {
    if (this.closed) return;
    this.closed = true;
    this.failure = new Error(code + ':' + this.diagnosticSummary());
    this.buffer = ''; this.nativeTail = '';
    for (const task of this.pending.values()) { clearTimeout(task.timer); task.reject(this.failure); }
    this.pending.clear();
  }
  call(method, params = {}, sessionId = this.session) {
    if (this.closed) return Promise.reject(new Error('GRAFANA_BROWSER_CLOSED'));
    if (!/^[A-Za-z]+\.[A-Za-z]+$/u.test(method)) return Promise.reject(new Error('GRAFANA_BROWSER_METHOD_REJECTED'));
    if (this.pending.size >= 128) return Promise.reject(new Error('GRAFANA_BROWSER_PENDING_LIMIT'));
    const id = ++this.next;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => this.fail('GRAFANA_BROWSER_TIMEOUT:' + method), 10000);
      this.pending.set(id, { resolve, reject, timer, method });
      try {
        this.child.stdio[3].write(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }) + '\0');
      } catch { this.fail('GRAFANA_BROWSER_WRITE_FAILED'); }
    });
  }
  async start() {
    const version = await this.call('Browser.getVersion');
    const target = await this.call('Target.createTarget', { url: 'about:blank' });
    this.session = (await this.call('Target.attachToTarget', { targetId: target.targetId, flatten: true })).sessionId;
    await this.call('Page.enable'); await this.call('Runtime.enable');
    await this.call('DOM.enable'); await this.call('CSS.enable');
    await this.call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 2000, deviceScaleFactor: 1, mobile: false });
    return version.product;
  }
  async evaluate(expression) {
    const result = await this.call('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    assert.ok(!result.exceptionDetails, 'GRAFANA_BROWSER_EVALUATION_FAILED');
    return result.result.value;
  }
  async platformFonts(selector) {
    const { root } = await this.call('DOM.getDocument');
    const { nodeId } = await this.call('DOM.querySelector', { nodeId: root.nodeId, selector });
    assert.ok(nodeId > 0, 'GRAFANA_BROWSER_FONT_NODE_REQUIRED');
    return (await this.call('CSS.getPlatformFontsForNode', { nodeId })).fonts;
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

// v13.2.2 PanelChrome renders Panels.Panel.title(title) as data-testid.
// Its accessible name is aria-labelledby, not the legacy containerByTitle aria-label.
export function panelReadExpression(title) {
  return `(() => {
    const panels = document.querySelectorAll('[data-testid=' + JSON.stringify(${JSON.stringify('data-testid Panel header ' + title)}) + ']');
    if (panels.length !== 1) return null;
    const panel = panels[0];
    panel.scrollIntoView({block:'center'});
    const content = panel.querySelector('[data-testid="data-testid panel content"]');
    if (!content) return null;
    const rect = content.getBoundingClientRect();
    const text = content.innerText.trim();
    // A missing health sample must not inherit Grafana's default healthy-green color.
    // Read the rendered text node; a CSS class or DOM string alone is not color evidence.
    const statusPanel = ['服务状态','完整采样','流程采样','Outbox 采样'].includes(${JSON.stringify(title)});
    const unknownColors = [];
    if (statusPanel && text.split(/\\s+/u).includes('未知')) {
      const walker = document.createTreeWalker(content, NodeFilter.SHOW_TEXT);
      for (let node; (node = walker.nextNode());) {
        if (node.textContent.trim() !== '未知') continue;
        const element = node.parentElement, bounds = element.getBoundingClientRect();
        if (bounds.width > 0 && bounds.height > 0) unknownColors.push(getComputedStyle(element).color);
      }
    }
    const unknownColorInvalid = statusPanel && text.split(/\\s+/u).includes('未知')
      && (unknownColors.length === 0 || unknownColors.some(color => color !== 'rgb(107, 114, 128)'));
    return {text, visible:rect.width>0 && rect.height>0, unknownColors,
      canvas:!!content.querySelector('canvas'),
      error:!!panel.querySelector('[data-testid="data-testid Panel status error"]') || unknownColorInvalid};
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
