import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { EventEmitter } from 'node:events';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import test from 'node:test';
import { BrowserStartupDiagnostics, browserResourceSnapshot } from '../ops/grafana-browser-diagnostics.mjs';
import { BrowserPipe, chromiumArguments } from '../ops/grafana-browser-driver.mjs';
import { createBrowserIoSampler } from '../ops/grafana-browser-io-window.mjs';
import { createStartupIoObserver } from '../ops/grafana-browser-startup-io.mjs';
import { boundStartupRecord, createStartupEvidenceCapture, finalizedStartupEvidence, startupCompanion,
  startupHandshake, startupIoMarker } from '../ops/grafana-browser-startup-evidence.mjs';
import { parseGrafanaBrowserResult, grafanaPin } from '../ops/verify-grafana-browser.mjs';
import { sourceIdentities } from '../ops/grafana-browser-runtime.mjs';

const root = resolve(import.meta.dirname, '../..'), groupRoot = '/sys/fs/cgroup/PRIVATE-owned.scope';
const marker = 'GRAFANA_BROWSER_DIAGNOSTICS=', secret = 'PRIVATE-token-device-0xabcdef';
function stat(pid, { start = '9007199254740993', name = 'PRIVATE (leader) name', state = 'S' } = {}) {
  const fields = Array(50).fill('0'); fields[0] = state; fields[19] = start;
  return `${pid} (${name}) ${fields.join(' ')}\n`;
}
const io = (read = 100, write = 200) => `read_bytes: ${read}\nwrite_bytes: ${write}\ncancelled_write_bytes: 999\nPRIVATE: ${secret}\n`;
function fixture({ overrides = {}, owned, tick = 0, readError = null } = {}) {
  const handles = owned ?? [{ pid: 13001, exitCode: null, signalCode: null }, { pid: 13002, exitCode: null, signalCode: null }];
  const calls = [], counts = new Map(); let phase = 0, now = 0;
  const values = which => ({ '/proc/self/cgroup': '0::/PRIVATE-owned.scope\n',
    '/proc/13001/stat': stat(13001), '/proc/13002/stat': stat(13002),
    '/proc/13001/io': which ? io(150, 900) : io(), '/proc/13002/io': which ? io(250, 500) : io(),
    '/proc/meminfo': which ? 'Dirty: 2 kB\nWriteback: 5 kB\n' : 'Dirty: 10 kB\nWriteback: 1 kB\n',
    [groupRoot + '/memory.stat']: which ? 'file_dirty 2048\nfile_writeback 4096\n' : 'file_dirty 8192\nfile_writeback 1024\n',
    ...(overrides[which] ?? {}),
  });
  const read = (file, limit) => {
    calls.push({ file, limit, phase }); now += tick;
    const key = phase + ':' + file, count = (counts.get(key) ?? 0) + 1; counts.set(key, count);
    if (readError) throw Object.assign(new Error(secret), { code: readError });
    const value = values(phase)[file]; return typeof value === 'function' ? value(count) : value ?? null;
  };
  return { handles, calls, read, now: () => now,
    endpoint(time = 100) { phase = 1; now = time; }, setTime(time) { now = time; },
    create() { const observer = createStartupIoObserver(handles, { read, now: () => now });
      observer.origin(now + 1); observer.observeRoot('launch', groupRoot); return observer; },
    finish(observer, end = 'handshake', endpointRoot = groupRoot) { this.endpoint(); observer.observeRoot(end, endpointRoot); return observer.complete(end); },
  };
}

test('two owned leaders produce aggregate accounted bytes and decreasing dirty gauges remain endpoint gauges', () => {
  const f = fixture({ tick: 1 }), observer = f.create(), value = f.finish(observer);
  assert.deepEqual(value, { schema: 1, end: 'handshake', spanMs: 100, leadMs: 10, sampleMs: [9, 8],
    services: { expected: 2, paired: 2, coverage: 'complete', readBytes: 200, writeBytes: 1000 },
    host: { dirtyBytes: [10240, 2048], writebackBytes: [1024, 5120] },
    cgroup: { dirtyBytes: [8192, 2048], writebackBytes: [1024, 4096] } });
  assert.equal(f.calls.length, 17); assert.equal(f.calls.reduce((sum, call) => sum + call.limit + 1, 0), 40977);
  assert.deepEqual(f.calls.slice(1, 7).map(call => call.file), ['/proc/13001/stat', '/proc/13001/io', '/proc/13001/stat',
    '/proc/13002/stat', '/proc/13002/io', '/proc/13002/stat']);
  const text = JSON.stringify(value);
  assert.doesNotMatch(text, /PRIVATE|13001|13002|9007199254740993|0xabcdef|\/proc|\/sys|cancelled|outside|caus/u);
  assert.equal(observer.complete('failure'), value); observer.clear(); observer.clear(); assert.equal(f.calls.length, 17);
  assert.equal(JSON.stringify(observer), '{}');
});

test('missing, duplicate, exited, changed or unsafe leaders never become partial aggregate traffic', () => {
  for (const owned of [[], [{}], [null, { pid: 13002, exitCode: null, signalCode: null }],
    [{ pid: 13001, exitCode: 0, signalCode: null }, { pid: 13002, exitCode: null, signalCode: null }],
    [{ pid: 13001, exitCode: null, signalCode: 'SIGTERM' }, { pid: 13002, exitCode: null, signalCode: null }],
    [{ pid: 13001, exitCode: null, signalCode: null }, { pid: 13001, exitCode: null, signalCode: null }],
    [{ pid: '../PRIVATE', exitCode: null, signalCode: null }, { pid: 2147483648, exitCode: null, signalCode: null }]]) {
    const f = fixture({ owned }), value = f.finish(f.create());
    assert.equal(value.services.readBytes, null); assert.equal(value.services.writeBytes, null);
    assert.ok(value.services.paired < 2); assert.notEqual(value.services.coverage, 'complete');
    assert.ok(f.calls.every(call => !call.file.includes('..') && !call.file.includes('2147483648')));
  }
  const f = fixture(), observer = f.create(); f.handles[0].exitCode = 0;
  assert.equal(f.finish(observer).services.paired, 1);
  const changed = fixture(), second = changed.create(); changed.handles[0].pid = 13003;
  assert.equal(changed.finish(second).services.paired, 1);
});

test('stat/io/stat brackets use exact private field22 tokens, PID and supported layout', () => {
  for (const value of [stat(13003), stat(13001, { state: 'Q' }), stat(13001, { start: '-1' }),
    stat(13001, { start: '18446744073709551616' }), stat(13001).trim(), stat(13001).replace(' 0\n', '\n'),
    stat(13001, { name: 'x'.repeat(65) }), 'x'.repeat(2049), null]) {
    const f = fixture({ overrides: { 1: { '/proc/13001/stat': value } } });
    assert.equal(f.finish(f.create()).services.paired, 1);
  }
  for (const endpoint of ['9007199254740994', '00000000000000000009']) {
    const f = fixture({ overrides: { 1: { '/proc/13001/stat': stat(13001, { start: endpoint }) } } });
    assert.equal(f.finish(f.create()).services.paired, 1);
  }
  for (const phase of [0, 1]) {
    const f = fixture({ overrides: { [phase]: { '/proc/13001/stat': count => stat(13001, { start: count === 1 ? '9' : '10' }) } } });
    assert.equal(f.finish(f.create()).services.paired, 1);
  }
  const canonical = fixture({ overrides: { 0: { '/proc/13001/stat': stat(13001, { start: '00000000000000000009' }) },
    1: { '/proc/13001/stat': stat(13001, { start: '9' }) } } });
  assert.equal(canonical.finish(canonical.create()).services.paired, 2);
});

test('PID accessors cannot change a validated path, duplicate identities or expand the fixed two-handle copy', () => {
  let accesses = 0;
  const changing = { get pid() { return ++accesses % 2 ? 13001 : '../../tmp/PRIVATE'; }, exitCode: null, signalCode: null };
  const stable = { pid: 13002, exitCode: null, signalCode: null };
  const f = fixture({ owned: [changing, stable] }), value = f.finish(f.create());
  assert.equal(value.services.paired, 1);
  assert.ok(f.calls.every(call => !call.file.includes('..'))); assert.ok(f.calls.length <= 17);
  const throws = { get pid() { throw new Error(secret); }, exitCode: null, signalCode: null };
  const denied = fixture({ owned: [throws, stable] }); assert.equal(denied.finish(denied.create()).services.paired, 1);
  let alternate = 0;
  const duplicate = { get pid() { return ++alternate % 2 ? 13001 : 13002; }, exitCode: null, signalCode: null };
  const repeated = fixture({ owned: [duplicate, duplicate] });
  assert.equal(repeated.finish(repeated.create()).services.paired, 0);
  assert.ok(repeated.calls.every(call => !/\/proc\/\d+\//u.test(call.file)));
  const owned = [{ pid: 13001, exitCode: null, signalCode: null }, stable];
  owned.slice = () => { assert.fail('overridable copy method must not be called'); };
  const bounded = fixture({ owned }); assert.equal(bounded.finish(bounded.create()).services.paired, 2);
  assert.equal(bounded.calls.length, 17);
});

test('I/O duplicate/malformed/missing/reset counters and unsafe aggregate sums are unknown', () => {
  for (const text of [io().trim(), io() + 'read_bytes: 1\n', io() + 'read_bytes: BAD\nread_bytes: 9\n',
    'read_bytes 1\nwrite_bytes: 2\n', 'read_bytes: 1\n', io(-1), io('9007199254740992'), io('1e3'), io('NaN'),
    io('00000000000000001'), io('99'), 'x'.repeat(1025)]) {
    const f = fixture({ overrides: { 1: { '/proc/13001/io': text } } });
    const value = f.finish(f.create()); assert.equal(value.services.paired, 1, text);
    assert.equal(value.services.readBytes, null); assert.equal(value.services.writeBytes, null);
  }
  const max = Number.MAX_SAFE_INTEGER, f = fixture({ overrides: {
    0: { '/proc/13001/io': io(0, 0), '/proc/13002/io': io(0, 0) },
    1: { '/proc/13001/io': io(max, max), '/proc/13002/io': io(max, max) },
  } });
  const value = f.finish(f.create()); assert.equal(value.services.paired, 2);
  assert.equal(value.services.coverage, 'partial'); assert.equal(value.services.readBytes, null);
});

test('bounded gauge key parsing preserves null endpoints, rejects unsafe KiB conversion and never subtracts gauges', () => {
  for (const text of ['Dirty: 1 kB\nDirty: BAD\nDirty: 2 kB\nWriteback: 4 kB\n',
    'Dirty: 9007199254740991 kB\nWriteback: 4 kB\n', 'Dirty 3 kB\nWriteback: 4 kB\n',
    'Dirty: -1 kB\nWriteback: 4 kB\n', 'Dirty: 1 KB\nWriteback: 4 kB\n']) {
    const f = fixture({ overrides: { 1: { '/proc/meminfo': text } } }), value = f.finish(f.create());
    assert.equal(value.host.dirtyBytes[1], null); assert.equal(value.host.writebackBytes[1], 4096);
    assert.equal(value.services.coverage, 'complete');
  }
  for (const text of [null, 'Dirty: 1 kB', 'x'.repeat(4097), 'x\n'.repeat(129), 'x'.repeat(257) + '\n']) {
    const f = fixture({ overrides: { 1: { '/proc/meminfo': text, [groupRoot + '/memory.stat']: text } } }), value = f.finish(f.create());
    assert.deepEqual(value.host.dirtyBytes, [10240, null]); assert.deepEqual(value.cgroup.writebackBytes, [1024, null]);
  }
  const f = fixture({ overrides: { 1: { [groupRoot + '/memory.stat']: 'file_dirty 0\nfile_writeback 0\n' } } });
  assert.deepEqual(f.finish(f.create()).cgroup.dirtyBytes, [8192, 0]);
});

test('cgroup identity must match runtime baseline and both already-read browser roots without alternate reads', () => {
  for (const membership of [null, '0::/../PRIVATE\n', '1:memory:/PRIVATE\n', '0::/a\n0::/b\n', 'x'.repeat(4097)]) {
    const f = fixture({ overrides: { 0: { '/proc/self/cgroup': membership } } }), value = f.finish(f.create());
    assert.deepEqual(value.cgroup, { dirtyBytes: [null, null], writebackBytes: [null, null] });
    assert.equal(f.calls.filter(call => call.file.endsWith('/memory.stat')).length, 0);
  }
  for (const phase of ['launch', 'handshake']) {
    const f = fixture(), observer = f.create();
    if (phase === 'launch') observer.observeRoot('launch', '/sys/fs/cgroup/OTHER');
    const value = f.finish(observer, 'handshake', phase === 'handshake' ? '/sys/fs/cgroup/OTHER' : groupRoot);
    assert.deepEqual(value.cgroup.dirtyBytes, [null, null]);
    assert.equal(f.calls.filter(call => call.file.endsWith('/memory.stat')).length, 1);
    assert.ok(!f.calls.some(call => call.file.includes('OTHER')));
  }
});

test('denied reads and unavailable/reversed clocks stay anonymous, nullable and non-fatal', () => {
  for (const readError of ['EACCES', 'EPERM', 'ENOENT']) {
    const f = fixture({ readError }), value = f.finish(f.create());
    assert.equal(value.services.coverage, 'unavailable'); assert.equal(value.services.paired, 0);
    assert.deepEqual(value.host.dirtyBytes, [null, null]); assert.doesNotMatch(JSON.stringify(value), /PRIVATE|EPERM|ENOENT|EACCES/u);
  }
  for (const time of [0, -1, Infinity, NaN, null, 86400001]) {
    const f = fixture(), observer = f.create(); f.endpoint(time); observer.observeRoot('failure', groupRoot);
    const value = observer.complete('failure'); assert.equal(value.spanMs, null); assert.equal(value.services.readBytes, null);
  }
});

test('handshake device sampling is one-shot and non-consuming; later failure still compares the launch baseline', () => {
  let phase = 0, time = 0, reads = 0;
  const sampler = createBrowserIoSampler({ now: () => time });
  const read = file => { reads++; return file.endsWith('/io.stat')
    ? `8:0 rbytes=${100 + phase * 100} wbytes=0 rios=${phase} wios=0\n`
    : `8 0 PRIVATE-device ${phase} 0 ${phase} 0 0 0 0 0 0 ${phase} ${phase}\n`; };
  sampler.sample('launch', groupRoot, read); phase = 1; time = 100;
  assert.equal(sampler.sample('handshake', groupRoot, read).cgroup.readBytes, 100);
  assert.equal(sampler.sample('handshake', groupRoot, read), null); assert.equal(reads, 4);
  phase = 2; time = 200; assert.equal(sampler.sample('failure', groupRoot, read).cgroup.readBytes, 200);
  assert.equal(reads, 6); assert.equal(sampler.sample('failure', groupRoot, read), null);
});

function browserFixture(t, { failure = false, throws = false } = {}) {
  const f = fixture({ tick: 1 }), capture = createStartupEvidenceCapture(f.handles, { read: f.read, now: f.now });
  const child = new EventEmitter(); child.pid = 14001; child.stderr = new EventEmitter(); child.exitCode = 0; child.signalCode = null;
  const input = new EventEmitter(), output = new EventEmitter(); output.setEncoding = () => {};
  child.stdio = [null, null, child.stderr, input, output];
  let launches = 0, timers = 0, snapshots = 0, natives = 0;
  const reports = [], events = [], writes = [];
  const originalTimeout = globalThis.setTimeout;
  t.mock.method(globalThis, 'setTimeout', (callback, delay, ...args) => { timers++; events.push('timer:' + delay); return originalTimeout(callback, delay, ...args); });
  const read = (file, limit) => {
    if (file === '/proc/14001/cgroup') return '0::/PRIVATE-owned.scope\n';
    if (file.endsWith('/io.stat')) return '8:0 rbytes=100 wbytes=0 rios=1 wios=0\n';
    if (file === '/proc/diskstats') return '8 0 PRIVATE 1 0 1 0 0 0 0 0 0 0 0\n';
    return null;
  };
  input.write = (text, callback) => { events.push('write'); writes.push(JSON.parse(text.slice(0, -1))); callback?.(); return true; };
  const browser = new BrowserPipe('/PRIVATE-profile', { PATH: process.env.PATH }, {
    launch(file, args, options) { launches++; events.push('launch'); assert.equal(f.calls.length, 9);
      assert.deepEqual(args, chromiumArguments('/PRIVATE-profile'));
      assert.deepEqual(options.stdio, ['ignore', 'ignore', 'pipe', 'pipe', 'pipe']); return child; },
    diagnostics: { ...capture.diagnostics, now: f.now, nativeSnapshot() { natives++; return null; },
      snapshot(pid, options) { snapshots++; events.push('resource:' + snapshots);
        if (snapshots === 1) { assert.equal(timers, 0); assert.equal(f.calls.length, 9); }
        if (snapshots === 2 && !failure) { assert.equal(browser.pending.size, 0); assert.equal(browser.startupDiagnostics.value.milestonesMs.handshake, 91); }
        if (throws && snapshots === 2) throw new Error(secret);
        return browserResourceSnapshot(pid, { ...options, read }); }, report: text => reports.push(text) },
  });
  f.endpoint(100);
  return { f, capture, browser, child, output, reports, events, writes,
    counts: () => ({ launches, timers, snapshots, natives }) };
}

test('baseline completes before one launch; no new service reads occur before original command timer/write', async t => {
  const f = browserFixture(t), pending = f.browser.start();
  assert.deepEqual(f.events.slice(0, 4), ['launch', 'resource:1', 'timer:10000', 'write']);
  assert.equal(f.f.calls.length, 9);
  f.output.emit('data', JSON.stringify({ id: 1, result: { product: 'Chrome/154.0.8037.97' } }) + '\0');
  await Promise.resolve();
  assert.equal(f.f.calls.length, 17); assert.equal(f.counts().natives, 0); assert.equal(f.counts().snapshots, 2);
  f.browser.startupDiagnostics.version('Chrome/154.0.8037.97'); assert.equal(f.f.calls.length, 17);
  const checked = assert.rejects(pending, /PIPE_ENDED/); f.output.emit('end'); await checked;
  assert.equal(f.f.calls.length, 17); assert.equal(f.counts().natives, 1); assert.equal(f.counts().snapshots, 3);
  t.mock.method(process, 'kill', () => true); await f.browser.stop(); await f.browser.stop();
  assert.equal(f.reports.length, 2); const result = {}; f.capture.attach(result);
  assert.equal(result.startupEvidence.io.end, 'handshake'); assert.equal(result.startupEvidence.handshake.nativeSampling, 'not-requested');
  assert.equal(result.startupEvidence.diagnostic.failureKind, 'pipe-end'); assert.equal(f.counts().launches, 1);
});

test('unchanged 10s timeout and discarded late bytes close only the failure endpoint before cleanup', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const f = browserFixture(t, { failure: true }), pending = f.browser.start(), checked = assert.rejects(pending, /TIMEOUT:Browser.getVersion/);
  f.f.setTime(10099); t.mock.timers.tick(9999); assert.equal(f.browser.closed, false); assert.equal(f.f.calls.length, 9);
  f.f.setTime(10100); t.mock.timers.tick(1); await checked;
  const original = f.browser.failure; assert.equal(f.f.calls.length, 17); assert.equal(f.counts().natives, 1);
  f.f.setTime(10900); f.output.emit('data', JSON.stringify({ id: 1, result: { product: 'Chrome/154.0.8037.97' } }) + '\0');
  assert.equal(f.browser.responses, 0); assert.equal(f.browser.startupDiagnostics.value.milestonesMs.handshake, null);
  t.mock.method(process, 'kill', () => true); await f.browser.stop(); assert.equal(f.browser.failure, original);
  const result = {}; f.capture.attach(result); assert.equal(result.startupEvidence.io.end, 'failure');
  assert.equal(result.startupEvidence.diagnostic.timeoutElapsedMs, 10000);
  assert.equal(result.startupEvidence.handshake.status, 'unavailable');
});

test('successful resource endpoint performs exactly 18 bounded reads and no native enumeration', () => {
  let phase = 0, now = 0, nativeCalls = 0; const calls = [];
  const d = new BrowserStartupDiagnostics({ now: () => now, report() {}, onFinalized() {},
    nativeSnapshot() { nativeCalls++; throw new Error(secret); },
    snapshot(pid, options) { return browserResourceSnapshot(pid, { ...options, read(file, limit) {
      calls.push({ file, limit, phase }); now++;
      return file.endsWith('/cgroup') ? '0::/PRIVATE-owned.scope\n' : null;
    } }); } });
  d.launched(14001); phase = 1; now = 100; d.version('Chrome/154.0.8037.97');
  const successCalls = calls.filter(call => call.phase === 1);
  assert.equal(successCalls.length, 18); assert.equal(successCalls.reduce((sum, call) => sum + call.limit + 1, 0), 48658);
  assert.equal(d.handshakeSample.sampleMs, 18); assert.equal(d.handshakeSample.atMs, 100);
  assert.equal(nativeCalls, 0); d.version('Chrome/154.0.8037.97'); assert.equal(calls.length, 36);
  assert.ok(calls.every(call => !/\/task\/|wchan|\/exe|\/proc\/\d+\/io$/u.test(call.file)));
});

test('sampler, reporter and finalized-callback failures are isolated and never relabel the original failure', () => {
  let callbacks = 0, clear = 0; const d = new BrowserStartupDiagnostics({ now: () => 1,
    snapshot() { throw new Error(secret); }, nativeSnapshot() { throw new Error(secret); },
    startupIo: { origin() { throw new Error(secret); }, complete() { throw new Error(secret); }, clear() { clear++; throw new Error(secret); } },
    report() { throw new Error(secret); }, onFinalized(value) { callbacks++; assert.equal(value.diagnostic.failureKind, 'timeout'); throw new Error(secret); } });
  d.launched(14001); d.failure('GRAFANA_BROWSER_TIMEOUT:Browser.getVersion'); d.finish({ exitCode: 0, signalCode: null }, false);
  assert.equal(callbacks, 1); assert.equal(clear, 1); assert.deepEqual(d.startupIoValue, { status: 'unavailable', reason: 'capture' });
  assert.equal(d.value.failureKind, 'timeout'); assert.equal(d.value.cleanup.failed, false);
  const capture = createStartupEvidenceCapture([]), result = {}; capture.attach(result);
  assert.deepEqual(result.startupEvidence, { status: 'unavailable', reason: 'capture' });
  const deniedHandles = new Proxy([], { get() { throw new Error(secret); } });
  const unavailableCapture = createStartupEvidenceCapture(deniedHandles);
  let finalized;
  const success = new BrowserStartupDiagnostics({ ...unavailableCapture.diagnostics, now: () => 1,
    snapshot() { throw new Error(secret); }, report() { throw new Error(secret); }, onFinalized(value) { finalized = value; } });
  success.launched(14001); success.version('Chrome/154.0.8037.97'); success.finish({ exitCode: 0, signalCode: null }, false);
  assert.deepEqual(finalized.handshake, { status: 'unavailable', reason: 'capture' });
  assert.deepEqual(finalized.io, { status: 'unavailable', reason: 'capture' });
  assert.equal(finalized.diagnostic.failureKind, null);
});

test('constructor failure explicitly clears the prelaunch capture without starting a process or replacing the error', () => {
  const f = fixture(), capture = createStartupEvidenceCapture(f.handles, { now: f.now, read: f.read });
  let browser; const failure = new Error('controlled constructor failure');
  assert.throws(() => {
    try { browser = new BrowserPipe('/PRIVATE-profile', { PATH: process.env.PATH }, {
      diagnostics: capture.diagnostics, launch() { throw failure; },
    }); } finally { capture.clear(); }
  }, error => error === failure);
  assert.equal(browser, undefined); assert.equal(f.calls.length, 9);
  f.endpoint(); assert.equal(capture.diagnostics.startupIo.complete('failure'), null);
  capture.clear(); assert.equal(f.calls.length, 9);
  capture.diagnostics.startupIo.clear = () => { throw new Error(secret); };
  assert.doesNotThrow(() => capture.clear());
  const result = {}; capture.attach(result); assert.deepEqual(result.startupEvidence, { status: 'unavailable', reason: 'capture' });
  const runtime = readFileSync(resolve(root, 'scripts/ops/grafana-browser-runtime.mjs'), 'utf8');
  assert.match(runtime, /finally \{\s+const errors = \[\];\s+startupCapture\?\.clear\(\);[^\n]*\n\s+if \(browser\) try \{ await browser\.stop\(\);/u);
});

const saved = file => JSON.parse(readFileSync(resolve(root, 'scripts/tests/fixtures', file)));
function maximalCompanion() {
  const f = fixture(), value = f.finish(f.create()), max = Number.MAX_SAFE_INTEGER;
  value.spanMs = 86399999.999; value.leadMs = 86399999.999; value.sampleMs = [86399999.999, 86399999.999];
  value.services.readBytes = max; value.services.writeBytes = max;
  for (const group of ['host', 'cgroup']) for (const key of ['dirtyBytes', 'writebackBytes']) value[group][key] = [max, max];
  return value;
}
test('saved #1832/#1818 records and maximal companion survive the real 4000-character wrapper unchanged', () => {
  const failure = 'GRAFANA_BROWSER_FAILED:browser-start:GRAFANA_BROWSER_TIMEOUT:Browser.getVersion:'
    + 'spawn=1,stderr=1048576,protocol=67108864,responses=128,categories=crashpad+dbus+devtools+fontconfig+library+path-length+permission+policy+resource+sandbox+socket';
  for (const file of ['grafana-startup-timeout-1832.json', 'grafana-io-timeout-receipt.json']) {
    const prior = saved(file), reports = [], companion = maximalCompanion();
    const d = new BrowserStartupDiagnostics({ now: () => 1, startupIo: { clear() {} }, report: line => reports.push(line) });
    Object.assign(d.value, structuredClone(prior)); d.startupIoValue = companion;
    d.finish({ exitCode: prior.exit.code, signalCode: prior.exit.signal }, false);
    assert.equal(reports.length, 2); assert.equal(reports[0], marker + JSON.stringify(prior));
    assert.deepEqual(JSON.parse(reports[1].slice(startupIoMarker.length)), companion);
    const stderr = reports.join('\n') + '\n' + failure + '\n'; assert.equal(stderr.slice(-4000), stderr);
    assert.ok(Buffer.byteLength(stderr) <= 3811);
  }
  const command = readFileSync(resolve(root, 'scripts/ops/verify-prometheus-rules.mjs'), 'utf8');
  assert.match(command, /String\(result\.stderr \|\| ''\)\.slice\(-4000\)/u);
  const maximum = marker + 'x'.repeat(3000) + '\n' + startupIoMarker + 'x'.repeat(512) + '\n' + failure + '\n';
  assert.ok(Buffer.byteLength(maximum) <= 3811); assert.equal(('earlier log\n'.repeat(1000) + maximum).slice(-4000).endsWith(maximum), true);
});

test('full success PSI and launch-based device context are retained separately from unchanged later failure fields', () => {
  const prior = saved('grafana-startup-timeout-1832.json'); let calls = 0, now = 0, finalized;
  const d = new BrowserStartupDiagnostics({ now: () => now, report() {}, onFinalized: value => { finalized = value; },
    nativeSnapshot: () => structuredClone(prior.native),
    ioSampler: { sample: phase => phase === 'launch' ? null : structuredClone(prior.ioWindow), clear() {} },
    snapshot(pid, { observeIo }) { calls++; observeIo(groupRoot, () => null); now += 2;
      return structuredClone(calls === 1 ? prior.resources.launch : prior.resources.failure); } });
  d.launched(14001); now = 100; d.version('Chrome/154.0.8037.97');
  assert.deepEqual(d.handshakeSample.resources, prior.resources.failure); assert.deepEqual(d.handshakeSample.ioWindow, prior.ioWindow);
  assert.equal(d.value.resources.failure, null); assert.equal(d.value.native, null);
  assert.ok(Buffer.byteLength(JSON.stringify(d.handshakeSample)) <= 2048);
  now = 200; d.failure('GRAFANA_BROWSER_PIPE_ENDED'); d.finish({ exitCode: 0, signalCode: null }, false);
  assert.deepEqual(finalized.handshake.resources, prior.resources.failure);
  assert.deepEqual(finalized.diagnostic.resources.failure, prior.resources.failure);
  assert.deepEqual(finalized.diagnostic.native, prior.native); assert.deepEqual(finalized.diagnostic.ioWindow, prior.ioWindow);
  assert.equal(calls, 3); assert.ok(Buffer.byteLength(JSON.stringify(finalized)) <= 6000);
});

test('report bounds are exact and fixed unavailable records replace only optional failed additions', () => {
  for (const limit of [512, 2048, 3000, 6000]) {
    const value = { padding: '' }; value.padding = 'x'.repeat(limit - JSON.stringify(value).length);
    assert.equal(Buffer.byteLength(JSON.stringify(boundStartupRecord(value, limit))), limit);
    value.padding += 'x'; assert.deepEqual(boundStartupRecord(value, limit), { status: 'unavailable', reason: 'size' });
  }
  const cycle = {}; cycle.self = cycle;
  assert.deepEqual(startupCompanion(cycle), { status: 'unavailable', reason: 'serialization' });
  assert.deepEqual(startupHandshake({ padding: 'x'.repeat(2048) }), { status: 'unavailable', reason: 'size' });
  const prior = saved('grafana-startup-timeout-1832.json');
  const value = finalizedStartupEvidence(prior, cycle, { padding: 'x'.repeat(512) });
  assert.deepEqual(value.diagnostic, prior); assert.equal(value.handshake.reason, 'serialization'); assert.equal(value.io.reason, 'size');
  assert.ok(Buffer.byteLength(JSON.stringify(value)) <= 6000);
});

test('typed retention drops unknown private keys, rejects bad known fields, and detaches callback mutation', () => {
  const prior = saved('grafana-startup-timeout-1832.json'); prior.PRIVATE = secret; prior.resources.failure.owned.address = '0xabcdef';
  const companion = maximalCompanion(); companion.services.privatePid = 13001;
  const value = finalizedStartupEvidence(prior, null, companion);
  assert.doesNotMatch(JSON.stringify(value), /PRIVATE|13001|0xabcdef|address/u);
  const bad = structuredClone(prior); bad.failureKind = secret;
  assert.deepEqual(finalizedStartupEvidence(bad, null, companion).diagnostic, { status: 'unavailable', reason: 'capture' });
  const reports = []; let captured;
  const d = new BrowserStartupDiagnostics({ report: text => reports.push(text), onFinalized(record) {
    captured = record; record.diagnostic.failureKind = 'other'; record.diagnostic.resources.failure.host.io.someUs = 1;
  } });
  const original = saved('grafana-startup-timeout-1832.json'); Object.assign(d.value, structuredClone(original));
  d.finish({ exitCode: original.exit.code, signalCode: original.exit.signal }, false);
  assert.ok(captured); assert.equal(reports[0], marker + JSON.stringify(original));
  assert.deepEqual(d.value.resources, original.resources); assert.equal(d.value.failureKind, 'timeout');
});

function successfulReceipt(startupEvidence) {
  const image = Buffer.alloc(150000); image.writeUInt16BE(0xffd8, 0); image.writeUInt16BE(0xffd9, image.length - 2);
  return { status: 'OPS_GRAFANA_BROWSER_VERIFIED', grafanaVersion: grafanaPin.version, browserVersion: 'Chrome/154.0.8037.97',
    cjkFont: 'Noto Sans CJK SC', cjkGlyphCount: 5, distinctCjkGlyphs: 4, inputs: sourceIdentities(root),
    provisionedDashboards: 2, originalQueries: 25, engineQueries: 11, overviewPanelsRendered: 25, enginePanelsRendered: 8,
    navigationBothWays: true, viewerWriteDenied: true, anonymousReadDenied: true, cleanupPassed: true,
    realGrafana: true, realPrometheus: true, realChromium: true, businessDatabaseVerified: false,
    humanNotificationVerified: false, productionDeploymentVerified: false, metricSource: 'CONTROLLED_HTTP_FIXTURE',
    readings: [{ state: 'healthy', values: ['3', '5', '2', '1'] }, { state: 'unavailable', values: Array(4).fill('unknown') },
      { state: 'empty', values: Array(4).fill('0') }], startupEvidence,
    screenshots: ['overview', 'engine-healthy', 'engine-unavailable', 'engine-empty'].map(name => ({ name, bytes: image.length,
      sha256: createHash('sha256').update(image).digest('hex'), base64: image.toString('base64') })) };
}

test('real typed capture reaches the production result parser with maximal screenshots and no stderr forwarding', () => {
  const f = fixture(), capture = createStartupEvidenceCapture(f.handles, { now: f.now, read: f.read }), reports = [];
  const d = new BrowserStartupDiagnostics({ ...capture.diagnostics, now: f.now, nativeSnapshot() { assert.fail('no success native sampler'); },
    report: line => reports.push(line), snapshot: (pid, { observeIo }) => { observeIo(groupRoot, () => null); return null; } });
  d.launched(14001); f.endpoint(); d.version('Chrome/154.0.8037.97');
  d.cleanup({ signal: 'SIGTERM', result: 'sent' }); d.cleanup({ signal: 'SIGKILL', result: 'sent' });
  d.finish({ exitCode: 0, signalCode: null }, false);
  const result = successfulReceipt(); capture.attach(result);
  assert.equal(result.startupEvidence.io.end, 'handshake'); assert.equal(result.startupEvidence.diagnostic.failureKind, null);
  assert.equal(result.startupEvidence.handshake.nativeSampling, 'not-requested');
  const output = 'OPS_GRAFANA_BROWSER_RESULT=' + JSON.stringify(result) + '\n' + reports.join('\n') + '\n' + secret;
  assert.ok(Buffer.byteLength(output) < 1024 * 1024); assert.ok(result.screenshots.reduce((sum, s) => sum + s.base64.length, 0) < 850000);
  const retained = parseGrafanaBrowserResult(output, sourceIdentities(root));
  assert.deepEqual(retained.startupEvidence, result.startupEvidence); assert.ok(!JSON.stringify(retained).includes(secret));
  assert.throws(() => parseGrafanaBrowserResult(output + '\nOPS_GRAFANA_BROWSER_RESULT={}', sourceIdentities(root)), /ONE_RECEIPT/);
  for (const key of ['realChromium', 'cleanupPassed', 'viewerWriteDenied']) {
    assert.throws(() => parseGrafanaBrowserResult('OPS_GRAFANA_BROWSER_RESULT=' + JSON.stringify({ ...result, [key]: false }), sourceIdentities(root)));
  }
  const runtime = readFileSync(resolve(root, 'scripts/ops/grafana-browser-runtime.mjs'), 'utf8');
  assert.match(runtime, /startupCapture = createStartupEvidenceCapture\(owned\);\s+browser = new BrowserPipe/u);
  assert.match(runtime, /await browser\.stop\(\)[\s\S]*result\.cleanupPassed = true; startupCapture\.attach\(result\)/u);
  const provisioner = readFileSync(resolve(root, 'scripts/ops/verify-grafana-browser.mjs'), 'utf8');
  assert.match(provisioner, /const receipt = parseGrafanaBrowserResult\(output, inputs\)/u);
  assert.match(provisioner, /return \{ \.\.\.receipt, fontPreparation/u);
});
