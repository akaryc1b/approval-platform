import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import test from 'node:test';
import { BrowserPipe, chromiumArguments, stopProcess } from '../ops/grafana-browser-driver.mjs';
import { BrowserStartupDiagnostics, browserResourceSnapshot, readBoundedDiagnosticFile } from '../ops/grafana-browser-diagnostics.mjs';

const root = resolve(import.meta.dirname, '../..');
const marker = 'GRAFANA_BROWSER_DIAGNOSTICS=';
const secret = 'SECRET-provider-password-private-path';
const originalKill = process.kill;
const stat = (cpu = '12') => '123 (' + secret + ') ' + ['R', ...Array.from({ length: 30 }, (_, i) => i === 10 || i === 11 ? cpu : '0')].join(' ');
const pressure = 'some avg10=0.12 avg60=0.34 avg300=0.56 total=789\nfull avg10=0.01 avg60=0.02 avg300=0.03 total=456\n';
function reader(overrides = {}) {
  const values = {
    '/proc/123/status': 'Name:\t' + secret + '\nState:\tS (sleeping)\nVmRSS:\t4321 kB\nThreads:\t7\n',
    '/proc/123/stat': stat(), '/proc/123/schedstat': '1234 5678 9\n',
    '/proc/123/cgroup': '0::/owned-private.slice/browser.scope\n',
    '/proc/pressure/cpu': pressure, '/proc/pressure/memory': pressure, '/proc/pressure/io': pressure,
    '/sys/fs/cgroup/owned-private.slice/browser.scope/cpu.stat': 'usage_usec 23\nnr_throttled 4\nthrottled_usec 5\nprivate ' + secret,
    '/sys/fs/cgroup/owned-private.slice/browser.scope/memory.events': 'high 1\noom 0\noom_kill 0\n',
    '/sys/fs/cgroup/owned-private.slice/browser.scope/memory.current': '54321\n',
    '/sys/fs/cgroup/owned-private.slice/browser.scope/memory.max': 'max\n',
    '/sys/fs/cgroup/owned-private.slice/browser.scope/pids.current': '12\n',
    '/sys/fs/cgroup/owned-private.slice/browser.scope/pids.max': '100\n',
    '/sys/fs/cgroup/owned-private.slice/browser.scope/cpu.pressure': pressure,
    '/sys/fs/cgroup/owned-private.slice/browser.scope/memory.pressure': pressure,
    '/sys/fs/cgroup/owned-private.slice/browser.scope/io.pressure': pressure, ...overrides,
  };
  return (file, limit) => { assert.ok(limit > 0 && limit <= 8192); return values[file] ?? null; };
}
function pipe(t, options = {}) {
  const child = new EventEmitter(); child.stderr = new EventEmitter(); child.exitCode = null; child.signalCode = null;
  const input = new EventEmitter(), output = new EventEmitter(); output.setEncoding = () => {};
  const writes = []; input.write = (text, callback) => { writes.push({ value: JSON.parse(text.slice(0, -1)), callback }); return true; };
  child.stdio = [null, null, child.stderr, input, output];
  const reports = []; let time = 100, loop = { active: 0, idle: 0 };
  const browser = new BrowserPipe('/owned-fixture/profile', { PATH: process.env.PATH }, {
    diagnostics: { now: () => time, utilization: () => loop, snapshot: () => null, report: line => reports.push(line), ...options },
    launch(file, args, settings) { assert.deepEqual(args, chromiumArguments('/owned-fixture/profile'));
      assert.deepEqual(settings.stdio, ['ignore', 'ignore', 'pipe', 'pipe', 'pipe']); return child; },
  });
  t.after(() => browser.fail('GRAFANA_BROWSER_FIXTURE_CLEANUP'));
  return { browser, child, input, output, writes, reports, setTime: value => { time = value; },
    setLoop: value => { loop = value; }, reply: value => output.emit('data', JSON.stringify(value) + '\0') };
}

test('resource diagnostics export only numeric allowlists and state, never process identity or raw content', () => {
  const value = browserResourceSnapshot(123, { read: reader() });
  assert.deepEqual(value.owned, { state: 'S', rssKiB: 4321, threads: 7, userCpuTicks: 12, systemCpuTicks: 12,
    schedulerCpuNs: 1234, schedulerWaitNs: 5678, schedulerSlices: 9 });
  assert.equal(value.cgroup.cpu.throttled_usec, 5); assert.equal(value.cgroup.memoryMaxBytes, 'max');
  assert.equal(value.host.io.someUs, 789); assert.equal(value.cgroup.pressure.cpu.fullUs, 456);
  assert.doesNotMatch(JSON.stringify(value), /SECRET|private|browser\.scope|\/proc|\/sys|password/u);
});

test('missing and malformed proc data are unavailable without raw errors or invented zero measurements', () => {
  const absent = browserResourceSnapshot(123, { read() { throw new Error(secret); } });
  assert.ok(Object.values(absent.owned).every(value => value === null));
  assert.deepEqual(absent.host, { cpu: null, memory: null, io: null }); assert.equal(absent.cgroup, null);
  const malformed = browserResourceSnapshot(123, { read: () => secret });
  assert.ok(Object.values(malformed.owned).every(value => value === null)); assert.equal(malformed.cgroup, null);
  const bad = browserResourceSnapshot(123, { read: reader({ '/proc/123/status': 'State: Q SECRET\nVmRSS: -1 kB\nThreads: 1 kB\n',
    '/proc/123/stat': '123 (PRIVATE) Q 1 2', '/proc/123/schedstat': '1 2 ' + secret,
    '/proc/pressure/cpu': 'some avg10=NaN avg60=0 avg300=0 total=5\n' }) });
  assert.ok(Object.values(bad.owned).every(value => value === null)); assert.equal(bad.host.cpu, null);
  assert.equal(browserResourceSnapshot(secret), null);
});

for (const membership of ['0::/../../etc\n', '0::/ok\n0::/other\n', '1:memory:/legacy\n', '0::/bad\\name\n']) {
  test('cgroup paths reject traversal, ambiguity and unsupported membership: ' + JSON.stringify(membership), () => {
    const requests = [], read = reader({ '/proc/123/cgroup': membership });
    const value = browserResourceSnapshot(123, { read(file, limit) { requests.push(file); return read(file, limit); } });
    assert.equal(value.cgroup, null); assert.ok(!requests.some(file => file.startsWith('/sys/')));
  });
}

test('bounded reader rejects oversized data and symlinks, including oversized injected reads', t => {
  const directory = mkdtempSync(resolve(tmpdir(), 'browser-diagnostics-')); t.after(() => rmSync(directory, { recursive: true, force: true }));
  const file = resolve(directory, 'data'); writeFileSync(file, '12345678');
  assert.equal(readBoundedDiagnosticFile(file, 8), '12345678'); assert.equal(readBoundedDiagnosticFile(file, 7), null);
  symlinkSync(file, resolve(directory, 'link')); assert.equal(readBoundedDiagnosticFile(resolve(directory, 'link'), 8), null);
  assert.equal(readBoundedDiagnosticFile(file, 8193), null); assert.equal(readBoundedDiagnosticFile(file, -1), null);
  const value = browserResourceSnapshot(123, { read: () => 'x'.repeat(9000) });
  assert.ok(Object.values(value.owned).every(item => item === null)); assert.equal(value.cgroup, null);
});

test('milestones distinguish kernel write callback from first protocol bytes and timeout drift', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const f = pipe(t); f.setTime(102); f.child.emit('spawn');
  f.setTime(103); const pending = f.browser.call('Browser.getVersion'); const checked = assert.rejects(pending, /TIMEOUT:Browser.getVersion/);
  f.setTime(107); f.writes[0].callback();
  f.setTime(108); f.child.stderr.emit('data', Buffer.from('dbus ' + secret));
  f.setTime(10153); f.setLoop({ active: 250, idle: 9800 }); t.mock.timers.tick(10000); await checked;
  const original = f.browser.failure.message;
  assert.equal(f.browser.startupDiagnostics.value.timeoutElapsedMs, 10050);
  assert.equal(f.browser.startupDiagnostics.value.timeoutDriftMs, 50);
  assert.deepEqual(f.browser.startupDiagnostics.value.eventLoop, { activeMs: 250, idleMs: 9800 });
  f.setTime(10170); f.reply({ id: 1, result: { product: 'Chrome/154.0.8037.57' } });
  assert.equal(f.browser.responses, 0); assert.equal(f.browser.closed, true);
  await f.browser.stop(); await f.browser.stop(); assert.equal(f.reports.length, 1);
  assert.equal(f.browser.failure.message, original);
  const report = JSON.parse(f.reports[0].slice(marker.length));
  assert.equal(report.milestonesMs.spawned, 2); assert.equal(report.milestonesMs.firstWriteQueued, 3);
  assert.equal(report.milestonesMs.firstWriteCallback, 7); assert.equal(report.milestonesMs.firstProtocol, 10070);
  assert.equal(report.failureKind, 'timeout'); assert.equal(report.browserVersion, null);
  assert.doesNotMatch(f.reports[0], new RegExp(secret));
});

test('late write callback and exit remain diagnostic-only after terminal failure', async t => {
  const f = pipe(t); const pending = f.browser.call('Browser.getVersion'); const checked = assert.rejects(pending, /PIPE_ENDED/);
  f.output.emit('end'); await checked;
  f.writes[0].callback(new Error(secret)); f.child.exitCode = null; f.child.signalCode = 'SIGKILL';
  f.child.emit('exit', null, 'SIGKILL'); await f.browser.stop();
  const report = JSON.parse(f.reports[0].slice(marker.length));
  assert.equal(report.firstWriteCallbackFailed, true); assert.deepEqual(report.exit, { observed: true, code: null, signal: 'SIGKILL' });
  assert.equal(report.cleanup.exited, true); assert.equal(f.browser.responses, 0); assert.equal(f.browser.pending.size, 0);
});

test('version, exit and signal allowlists cannot export arbitrary native or private strings', () => {
  const reports = [], d = new BrowserStartupDiagnostics({ snapshot: () => null, report: value => reports.push(value) });
  for (const product of [secret, 'Chrome/154.0.8037.57 ' + secret, 'Chrome/1.2.3', null]) d.version(product);
  assert.equal(d.value.browserVersion, null); d.version('Chrome/154.0.8037.57');
  assert.equal(d.value.browserVersion, 'Chrome/154.0.8037.57'); d.exited(secret, secret);
  assert.deepEqual(d.value.exit, { observed: true, code: null, signal: null });
  d.cleanup({ signal: secret, result: secret }); d.failure(secret); d.finish({ exitCode: null, signalCode: null }, false);
  assert.equal(d.value.failureKind, 'other'); assert.doesNotMatch(reports[0], new RegExp(secret));
});

test('unavailable clocks, counters, samples and throwing reporters never replace browser failures', async t => {
  const f = pipe(t, { now() { throw new Error(secret); }, utilization() { throw new Error(secret); },
    snapshot() { throw new Error(secret); }, report() { throw new Error(secret); } });
  const pending = f.browser.call('Browser.getVersion'); const checked = assert.rejects(pending, /PIPE_ENDED/);
  f.output.emit('end'); await checked; const error = f.browser.failure;
  await f.browser.stop(); assert.equal(f.browser.failure, error); assert.doesNotMatch(error.message, new RegExp(secret));
});

test('owned cleanup retains TERM/KILL semantics and observes outcomes even if observer throws', async t => {
  const signals = [], child = new EventEmitter(); child.pid = 123; child.exitCode = 0; child.signalCode = null;
  let missing = false;
  t.mock.method(process, 'kill', (pid, signal) => { assert.equal(pid, -123);
    if (missing) { const error = new Error(secret); error.code = 'ESRCH'; throw error; }
    signals.push(signal); });
  await stopProcess(child, { observe() { throw new Error(secret); } });
  assert.deepEqual(signals, ['SIGTERM', 'SIGKILL']);
  const events = []; missing = true;
  await stopProcess(child, { observe: event => events.push(event) });
  assert.deepEqual(events, [{ signal: 'SIGTERM', result: 'attempted' }, { signal: 'SIGTERM', result: 'missing' },
    { signal: 'SIGKILL', result: 'attempted' }, { signal: 'SIGKILL', result: 'missing' }]);
});

test('cleanup errors remain errors and still emit one sanitized report', async t => {
  const f = pipe(t); f.child.pid = 123;
  const failure = Object.assign(new Error(secret), { code: 'EPERM' });
  t.mock.method(process, 'kill', () => { throw failure; });
  await assert.rejects(f.browser.stop(), error => error === failure); assert.equal(f.reports.length, 1);
  const report = JSON.parse(f.reports[0].slice(marker.length));
  assert.equal(report.cleanup.failed, true); assert.equal(report.cleanup.term, 'error');
  assert.doesNotMatch(f.reports[0], new RegExp(secret));
});

test('wrapped integrated failure retains complete cleanup diagnostics inside the actual 4000-byte stderr tail', async t => {
  const runtime = readFileSync(resolve(root, 'scripts/ops/grafana-browser-runtime.mjs'), 'utf8');
  const command = readFileSync(resolve(root, 'scripts/ops/verify-prometheus-rules.mjs'), 'utf8');
  assert.match(runtime, /throw new Error\('GRAFANA_BROWSER_FAILED:' \+ phase/u);
  assert.match(runtime, /finally \{[\s\S]*?await browser\.stop\(\)/u);
  assert.match(command, /String\(result\.stderr \|\| ''\)\.slice\(-4000\)/u);
  const f = pipe(t, { snapshot: pid => browserResourceSnapshot(123, { read: reader() }) });
  const pending = f.browser.call('Browser.getVersion'); const checked = assert.rejects(pending, /PIPE_ENDED/);
  f.output.emit('end'); await checked;
  // The actual runtime copies this message before its finally block stops the browser.
  const wrapped = new Error('GRAFANA_BROWSER_FAILED:browser-start:' + f.browser.failure.message);
  await f.browser.stop(); const tail = (f.reports.join('\n') + '\n' + wrapped.message + '\n').slice(-4000);
  assert.ok(tail.startsWith(marker)); assert.ok(tail.includes(wrapped.message));
  const report = JSON.parse(tail.split('\n')[0].slice(marker.length));
  assert.equal(report.failureKind, 'pipe-end'); assert.equal(report.cleanup.failed, false);
});

test('maximal valid numeric diagnostics and original failure fit the existing command stderr budget', () => {
  const max = String(Number.MAX_SAFE_INTEGER), reports = [];
  const fullPressure = 'some avg10=100 avg60=100 avg300=100 total=' + max
    + '\nfull avg10=100 avg60=100 avg300=100 total=' + max + '\n';
  const read = file => file.endsWith('/status') ? 'State: R (running)\nVmRSS: ' + max + ' kB\nThreads: ' + max + '\n'
    : file.endsWith('/stat') ? stat(max) : file.endsWith('/schedstat') ? [max, max, max].join(' ')
      : file.endsWith('/cgroup') ? '0::/owned\n' : file.includes('/pressure/') || file.endsWith('.pressure') ? fullPressure
        : file.endsWith('/cpu.stat') ? ['usage_usec', 'nr_throttled', 'throttled_usec'].map(key => key + ' ' + max).join('\n')
          : file.endsWith('/memory.events') ? ['high', 'oom', 'oom_kill'].map(key => key + ' ' + max).join('\n') : max;
  let now = 0, loop = { active: 0, idle: 0 };
  const d = new BrowserStartupDiagnostics({ now: () => now, utilization: () => loop,
    snapshot: pid => browserResourceSnapshot(pid, { read }), report: line => reports.push(line) });
  now = 86399999.999; loop = { active: now, idle: now };
  d.launched(123); for (const name of Object.keys(d.value.milestonesMs)) d.mark(name);
  d.writeCallback(true); d.version('HeadlessChrome/99999.99999.99999.99999'); d.timeout(0, 10000);
  d.failure('GRAFANA_BROWSER_TIMEOUT:Browser.getVersion'); d.exited(255, 'SIGTERM');
  d.value.resourceSampleDurationMs = { launch: 86399999.999, failure: 86399999.999 };
  d.cleanup({ signal: 'SIGTERM', result: 'attempted' }); d.cleanup({ signal: 'SIGKILL', result: 'missing' });
  d.finish({ exitCode: 255, signalCode: null }, true);
  assert.equal(reports.length, 1); assert.ok(Buffer.byteLength(reports[0]) <= 3028);
  const failure = 'GRAFANA_BROWSER_FAILED:browser-start:GRAFANA_BROWSER_TIMEOUT:Browser.getVersion:'
    + 'spawn=1,stderr=1048576,protocol=67108864,responses=128,categories=crashpad+dbus+devtools+fontconfig+library+path-length+permission+policy+resource+sandbox+socket';
  const stderr = reports[0] + '\n' + failure + '\n'; assert.ok(Buffer.byteLength(stderr) < 4000);
  assert.equal(stderr.slice(-4000), stderr); assert.equal(JSON.parse(reports[0].slice(marker.length)).exit.code, 255);
});

test('cleanup keeps the existing 1500ms TERM grace before KILL and still requires native exit', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const signals = [], child = new EventEmitter(); child.pid = 123; child.exitCode = null; child.signalCode = null;
  t.mock.method(process, 'kill', (pid, signal) => { assert.equal(pid, -123); signals.push(signal);
    if (signal === 'SIGKILL') { child.signalCode = signal; child.emit('exit', null, signal); } });
  const stopped = stopProcess(child); assert.deepEqual(signals, ['SIGTERM']);
  t.mock.timers.tick(1499); await Promise.resolve(); assert.deepEqual(signals, ['SIGTERM']);
  t.mock.timers.tick(1); await stopped; assert.deepEqual(signals, ['SIGTERM', 'SIGKILL']);
});

test('diagnostic snapshots are taken only after native listeners attach, and launch remains singular', async t => {
  const child = new EventEmitter(); child.stderr = new EventEmitter(); child.exitCode = 0; child.signalCode = null;
  const input = new EventEmitter(), output = new EventEmitter(); output.setEncoding = () => {}; input.write = () => true;
  child.stdio = [null, null, child.stderr, input, output];
  let launches = 0, samples = 0;
  const env = { PATH: 'controlled' };
  const browser = new BrowserPipe('/owned-fixture/profile', env, {
    launch(file, args, options) { launches++; assert.equal(options.env, env); assert.deepEqual(args, chromiumArguments('/owned-fixture/profile')); return child; },
    diagnostics: { report() {}, snapshot() { samples++; assert.equal(child.listenerCount('exit'), 1);
      assert.equal(child.stderr.listenerCount('data'), 1); assert.equal(output.listenerCount('data'), 1); return null; } },
  });
  t.after(() => browser.fail('GRAFANA_BROWSER_FIXTURE_CLEANUP'));
  const pending = browser.call('Browser.getVersion'), checked = assert.rejects(pending, /PIPE_ENDED/);
  output.emit('end'); await checked; await browser.stop(); assert.equal(launches, 1); assert.equal(samples, 2);
});

test('cleanup test doubles restore the real kill function before later native tests', () => {
  assert.equal(process.kill, originalKill);
});

test('resource sample overhead is measured separately, including a throwing diagnostic read', () => {
  let now = 0, calls = 0;
  const d = new BrowserStartupDiagnostics({ now: () => now, snapshot() { calls++; now += calls === 1 ? 2 : 3;
    if (calls === 2) throw new Error(secret); return null; }, report() {} });
  d.launched(123); d.failure('GRAFANA_BROWSER_PIPE_ENDED');
  assert.deepEqual(d.value.resourceSampleDurationMs, { launch: 2, failure: 3 });
  assert.equal(d.value.milestonesMs.failure, 2); assert.equal(d.value.resources.failure, null);
});
