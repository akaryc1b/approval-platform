import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import test from 'node:test';
import { createBrowserIoSampler } from '../ops/grafana-browser-io-window.mjs';
import { BrowserStartupDiagnostics, browserResourceSnapshot } from '../ops/grafana-browser-diagnostics.mjs';
import { BrowserPipe, chromiumArguments } from '../ops/grafana-browser-driver.mjs';

const root = resolve(import.meta.dirname, '../..'), marker = 'GRAFANA_BROWSER_DIAGNOSTICS=';
const privateRoot = '/sys/fs/cgroup/PRIVATE-token.scope', secret = 'PRIVATE-device-password-0xdeadbeef';
const g = (id = '8:0', readBytes = 100, writeBytes = 20, readOps = 3, writeOps = 2) =>
  `${id} rbytes=${readBytes} wbytes=${writeBytes} rios=${readOps} wios=${writeOps}\n`;
function d(id = '8:0', { name = secret, readOps = 5, readSectors = 20, writeOps = 3, writeSectors = 10,
  inFlight = 1, ioMs = 7, weightedMs = 8, layout = 11 } = {}) {
  return `${id.replace(':', ' ')} ${name} ` + [readOps, 0, readSectors, 0, writeOps, 0, writeSectors, 0,
    inFlight, ioMs, weightedMs, ...Array(layout - 11).fill(0)].join(' ') + '\n';
}
function window({ firstGroup = g(), lastGroup = g('8:0', 356, 30, 6, 4), firstDisk = d(),
  lastDisk = d('8:0', { readOps: 7, readSectors: 24, writeOps: 4, writeSectors: 12, inFlight: 9, ioMs: 10, weightedMs: 21 }),
  firstRoot = privateRoot, lastRoot = privateRoot, firstTime = 100, lastTime = 1100, readError = false } = {}) {
  let time = firstTime, phase = 0; const calls = [];
  const sampler = createBrowserIoSampler({ now: () => time });
  const read = (file, limit) => {
    calls.push({ file, limit, phase });
    assert.equal(limit, 8192);
    if (readError) throw Object.assign(new Error(secret), { code: readError });
    if (file === '/proc/diskstats') return phase ? lastDisk : firstDisk;
    assert.equal(file, (phase ? lastRoot : firstRoot) + '/io.stat');
    return phase ? lastGroup : firstGroup;
  };
  assert.equal(sampler.sample('launch', firstRoot, read), null);
  phase = 1; time = lastTime;
  return { value: sampler.sample('failure', lastRoot, read), sampler, calls, read };
}

test('one anonymous exact-key window keeps unlike accounting domains separate and in-flight as a gauge', () => {
  const { value, calls } = window();
  assert.deepEqual(value, { coverage: 'complete', scope: 'visible-nested', devices: 1, paired: 1,
    selected: 'largest-read-delta', spanMs: 1000,
    cgroup: { readBytes: 256, writeBytes: 10, readOps: 3, writeOps: 2 },
    device: { readBytes: 2048, writeBytes: 1024, readOps: 2, writeOps: 1, ioMs: 3, weightedMs: 13, inFlight: 9 } });
  assert.equal(calls.length, 4);
  assert.deepEqual(calls.map(call => call.file), [privateRoot + '/io.stat', '/proc/diskstats', privateRoot + '/io.stat', '/proc/diskstats']);
  assert.doesNotMatch(JSON.stringify(value), /PRIVATE|8:0|0xdeadbeef|\/sys|\/proc|outside|share|utilization|cause/u);
});

test('selection is the largest positive paired group read delta, not a sum or a convenient available device', () => {
  const firstGroup = g('8:0') + g('8:1') + g('253:0'), lastGroup = g('253:0', 700) + g('8:1', 500) + g('8:0', 250);
  const firstDisk = d('8:1') + d('8:0'), lastDisk = d('8:0', { readSectors: 40 }) + d('8:1', { readSectors: 70 });
  const value = window({ firstGroup, lastGroup, firstDisk, lastDisk }).value;
  assert.equal(value.coverage, 'partial'); assert.equal(value.devices, 3); assert.equal(value.paired, 3);
  assert.equal(value.cgroup.readBytes, 600); assert.equal(value.device, null);
  assert.deepEqual(Object.keys(value.cgroup), ['readBytes', 'writeBytes', 'readOps', 'writeOps']);
  const tie = window({ firstGroup: g('8:1') + g('8:0'), lastGroup: g('8:0', 300) + g('8:1', 300),
    firstDisk, lastDisk }).value;
  assert.equal(tie.device.readBytes, 20 * 512); assert.equal(tie.cgroup.readBytes, 200);
});

test('known group tokens are order-independent; bounded unknown fields cannot become output keys', () => {
  const value = window({ lastGroup: '8:0 PRIVATE=' + secret + ' wios=4 rios=6 rbytes=356 cost.usage=77 wbytes=30\n' }).value;
  assert.equal(value.coverage, 'complete'); assert.equal(value.cgroup.readBytes, 256);
  assert.doesNotMatch(JSON.stringify(value), /PRIVATE|cost|usage|password/u);
});

test('duplicate known fields, missing counters and malformed/unsafe counters never fabricate a usable row', () => {
  for (const value of ['-1', '+1', '1.5', '1e3', 'NaN', 'Infinity', '9007199254740992', '00000000000000001', '', secret]) {
    const result = window({ lastGroup: g('8:0', value) }).value;
    assert.equal(result.coverage, 'partial', value); assert.equal(result.paired, 0, value); assert.equal(result.cgroup, null, value);
  }
  for (const lastGroup of [g().trim() + ' rbytes=100\n', g().trim() + ' rbytes=bad\n',
    g().trim() + ' rbytes=\n', '8:0 rbytes=100 wbytes=20 rios=3\n', '8:0\n', g().trim() + ' broken\n']) {
    const result = window({ lastGroup }).value;
    assert.equal(result.coverage, 'partial'); assert.equal(result.cgroup, null);
  }
  const max = Number.MAX_SAFE_INTEGER;
  const valid = window({ firstGroup: g('8:0', 0, 0, 0, 0), lastGroup: g('8:0', max, max, max, max) }).value;
  assert.equal(valid.cgroup.readBytes, max); assert.equal(valid.coverage, 'complete');
});

test('duplicate or canonical-equivalent device keys and malformed global keys invalidate the source', () => {
  for (const lastGroup of [g() + g(), g() + g('0008:0000'), g('8:-1'), g('8:4294967296'), g('99999999999:0'),
    'bad-private-key rbytes=356 wbytes=30 rios=6 wios=4\n']) {
    const result = window({ lastGroup }).value;
    assert.equal(result.coverage, 'unavailable'); assert.equal(result.devices, null); assert.equal(result.cgroup, null);
  }
  for (const lastDisk of [d() + d(), d() + d('0008:0000'), d('8:4294967296')]) {
    const result = window({ lastDisk }).value;
    assert.equal(result.coverage, 'partial'); assert.equal(result.cgroup.readBytes, 256); assert.equal(result.device, null);
  }
  const canonical = window({ firstGroup: g('0008:0000'), firstDisk: d('0008:0000') }).value;
  assert.equal(canonical.coverage, 'complete'); assert.equal(canonical.device.readBytes, 2048);
});

test('supported diskstats layouts retain fixed fields and reject all other or malformed layouts', () => {
  for (const layout of [11, 15, 17]) {
    assert.equal(window({ firstDisk: d('8:0', { layout }), lastDisk: d('8:0', { layout, readSectors: 24 }) }).value.device.readBytes, 2048);
  }
  for (const lastDisk of [d('8:0', { layout: 12 }), d('8:0', { layout: 16 }), d().replace(' 0 ', ' invalid '),
    d('8:0', { readOps: -1 }), d('8:0', { inFlight: '9007199254740992' }), '8 0 name\n']) {
    const result = window({ lastDisk }).value;
    assert.equal(result.coverage, 'partial'); assert.equal(result.device, null);
  }
});

test('new, vanished, reset, unmatched or replaced rows remain unknown, with useful other paired rows retained', () => {
  for (const overrides of [{ firstGroup: '' }, { lastGroup: '' }, { lastGroup: g('8:1', 356) },
    { lastGroup: g('8:0', 99) }, { lastGroup: g('8:0', 356, 19) }]) {
    const value = window(overrides).value;
    assert.equal(value.coverage, 'partial'); assert.equal(value.paired, 0); assert.equal(value.cgroup, null);
  }
  for (const overrides of [{ firstDisk: '' }, { lastDisk: '' }, { lastDisk: d('8:1') },
    { lastDisk: d('8:0', { name: 'PRIVATE-replacement' }) }, { lastDisk: d('8:0', { readSectors: 19 }) },
    { lastDisk: d('8:0', { weightedMs: 7 }) }]) {
    const value = window(overrides).value;
    assert.equal(value.coverage, 'partial'); assert.equal(value.device, null); assert.equal(value.cgroup.readBytes, 256);
  }
  const value = window({ firstGroup: g() + g('8:1'), lastGroup: g('8:0', 99) + g('8:1', 500),
    firstDisk: d('8:1'), lastDisk: d('8:1', { readSectors: 40 }) }).value;
  assert.equal(value.coverage, 'partial'); assert.equal(value.paired, 1); assert.equal(value.cgroup.readBytes, 400);
});

test('safe byte multiplication, counter deltas and final gauges do not clamp overflow or infer resets', () => {
  const maximumSectors = Math.floor(Number.MAX_SAFE_INTEGER / 512);
  const base = d('8:0', { readSectors: 0, writeSectors: 0 });
  const value = window({ firstDisk: base, lastDisk: d('8:0', { readSectors: maximumSectors,
    writeSectors: maximumSectors, inFlight: Number.MAX_SAFE_INTEGER }) }).value;
  assert.equal(value.device.readBytes, maximumSectors * 512); assert.equal(value.device.inFlight, Number.MAX_SAFE_INTEGER);
  for (const field of ['readSectors', 'writeSectors']) {
    assert.equal(window({ firstDisk: base, lastDisk: d('8:0', { [field]: maximumSectors + 1 }) }).value.device, null);
  }
  assert.equal(window({ firstDisk: d('8:0', { inFlight: 20 }), lastDisk: d('8:0', { inFlight: 0 }) }).value.device.inFlight, 0);
});

test('zero change is distinguished from write-only traffic and unavailable evidence', () => {
  for (const overrides of [{ lastGroup: g() }, { firstGroup: '', lastGroup: '' }]) {
    const value = window(overrides).value;
    assert.equal(value.coverage, 'no-activity'); assert.equal(value.selected, null); assert.equal(value.cgroup, null);
  }
  const value = window({ lastGroup: g('8:0', 100, 1000) }).value;
  assert.equal(value.coverage, 'complete'); assert.equal(value.selected, null); assert.equal(value.device, null);
  assert.equal(window({ lastGroup: null }).value.coverage, 'unavailable');
});

test('visible root/nested labels do not claim a global accounting role and membership must stay identical', () => {
  const value = window({ firstRoot: '/sys/fs/cgroup', lastRoot: '/sys/fs/cgroup' }).value;
  assert.equal(value.scope, 'visible-root'); assert.equal(value.coverage, 'complete');
  assert.equal(window({ lastRoot: '/sys/fs/cgroup/OTHER-private' }).value.coverage, 'unavailable');
  assert.equal(window({ lastRoot: '/sys/fs/cgroup/OTHER-private' }).value.scope, 'unknown');
  for (const firstRoot of [null, '/tmp', '/sys/fs/cgroup/../secret', '/sys/fs/cgroup/x/./y',
    '/sys/fs/cgroup//x', '/sys/fs/cgroup/x\0secret', '/sys/fs/cgroup/' + 'x'.repeat(1025)]) {
    const result = window({ firstRoot }); assert.equal(result.value.coverage, 'unavailable');
    assert.equal(result.calls.length, 2); assert.doesNotMatch(JSON.stringify(result.value), /OTHER|private|secret|tmp/u);
  }
});

test('missing, denied, throwing and truncated inputs are bounded and never export exception text', () => {
  for (const readError of ['ENOENT', 'EACCES', 'EPERM']) {
    const { value, calls } = window({ readError });
    assert.equal(value.coverage, 'unavailable'); assert.equal(calls.length, 4);
    assert.doesNotMatch(JSON.stringify(value), /PRIVATE|password|EPERM|ENOENT|EACCES/u);
  }
  for (const lastGroup of [null, undefined, {}, 12, g().trim(), g() + '\0', g().replace('\n', '\r\n')]) {
    const value = window({ lastGroup: lastGroup === undefined ? {} : lastGroup }).value;
    assert.equal(value.coverage, 'unavailable'); assert.equal(value.cgroup, null);
  }
  assert.equal(window({ lastDisk: d().trim() }).value.device, null);
});

test('byte, row, row-width, token and union bounds never produce prefix success', () => {
  const padded = (text, bytes) => text + '\n'.repeat(bytes - Buffer.byteLength(text));
  assert.equal(window({ lastGroup: padded(g('8:0', 356), 8192) }).value.coverage, 'complete');
  assert.equal(window({ lastGroup: padded(g(), 8193) }).value.coverage, 'unavailable');
  assert.equal(window({ lastDisk: padded(d(), 8193) }).value.device, null);
  const groupRows = n => Array.from({ length: n }, (_, i) => g('8:' + i)).join('');
  const diskRows = n => Array.from({ length: n }, (_, i) => d('8:' + i, { name: 'x' })).join('');
  assert.equal(window({ firstGroup: groupRows(64), lastGroup: groupRows(64), firstDisk: diskRows(64), lastDisk: diskRows(64) }).value.devices, 64);
  assert.equal(window({ lastGroup: groupRows(65) }).value.coverage, 'unavailable');
  assert.equal(window({ lastDisk: diskRows(65) }).value.device, null);
  const line = g('8:0', 356).trim();
  assert.equal(window({ lastGroup: line + ' ' + 'x'.repeat(1023 - line.length) + '\n' }).value.coverage, 'partial');
  assert.equal(window({ lastGroup: line + ' ' + 'x'.repeat(1024 - line.length) + '\n' }).value.coverage, 'unavailable');
  assert.equal(window({ lastGroup: line + ' x=1'.repeat(27) + '\n' }).value.coverage, 'complete');
  assert.equal(window({ lastGroup: line + ' x=1'.repeat(28) + '\n' }).value.coverage, 'unavailable');
  const disjoint = Array.from({ length: 33 }, (_, i) => g('9:' + i)).join('');
  assert.equal(window({ firstGroup: groupRows(33), lastGroup: disjoint }).value.coverage, 'unavailable');
});

test('non-monotonic, unavailable, invalid or excessive sample spans cannot be reported as a valid interval', () => {
  for (const lastTime of [null, NaN, Infinity, -1, 99, 100, Number.MAX_SAFE_INTEGER + 1, 86400200]) {
    const value = window({ lastTime }).value;
    assert.equal(value.coverage, 'unavailable'); assert.equal(value.spanMs, null); assert.equal(value.cgroup, null);
  }
  assert.equal(window({ lastTime: 1100.123456 }).value.spanMs, 1000.123);
  const sampler = createBrowserIoSampler({ now() { throw new Error(secret); } });
  sampler.sample('launch', privateRoot, () => null);
  assert.equal(sampler.sample('failure', privateRoot, () => null).spanMs, null);
});

test('one launch and first failure only; clear discards private state without reads', () => {
  let reads = 0, time = 1;
  const sampler = createBrowserIoSampler({ now: () => time }), read = () => { reads++; return ''; };
  sampler.sample('launch', privateRoot, read); sampler.sample('launch', privateRoot, read);
  sampler.sample('probe', privateRoot, read); time++;
  sampler.sample('failure', privateRoot, read); sampler.sample('failure', privateRoot, read);
  sampler.clear(); sampler.sample('launch', privateRoot, read); assert.equal(reads, 4);
  assert.equal(JSON.stringify(sampler), '{}');
  const success = createBrowserIoSampler(); success.sample('launch', privateRoot, read); success.clear();
  assert.equal(success.sample('failure', privateRoot, read), null); assert.equal(reads, 6);
});

test('the existing resource read privately supplies the membership and includes new I/O overhead', () => {
  let time = 0, phase = 0; const reads = [], reports = [];
  const read = (file, limit) => {
    reads.push({ file, phase, limit });
    if (file === '/proc/123/cgroup') return '0::/PRIVATE-token.scope\n';
    if (file === privateRoot + '/io.stat') { time += 2; return phase ? g('8:0', 356) : g(); }
    if (file === '/proc/diskstats') { time += 3; return phase ? d('8:0', { readSectors: 30 }) : d(); }
    return null;
  };
  const diagnostics = new BrowserStartupDiagnostics({ now: () => time, nativeSnapshot: () => null,
    snapshot: (pid, options) => browserResourceSnapshot(pid, { ...options, read }), report: line => reports.push(line) });
  diagnostics.launched(123); phase = 1; time = 1000;
  diagnostics.failure('GRAFANA_BROWSER_TIMEOUT:Browser.getVersion'); diagnostics.failure('GRAFANA_BROWSER_PIPE_ENDED');
  assert.deepEqual(diagnostics.value.resourceSampleDurationMs, { launch: 5, failure: 5 });
  assert.equal(diagnostics.value.ioWindow.spanMs, 1000); assert.equal(diagnostics.value.ioWindow.cgroup.readBytes, 256);
  assert.equal(reads.filter(row => row.file === '/proc/123/cgroup').length, 2);
  assert.equal(reads.filter(row => row.file.endsWith('/io.stat') || row.file === '/proc/diskstats').length, 4);
  diagnostics.finish({ exitCode: 0, signalCode: null }, false); diagnostics.finish({}, true);
  assert.equal(reports.length, 1); assert.doesNotMatch(reports[0], /PRIVATE|0xdeadbeef|\/sys|\/proc/u);
});

function receipt(ioWindow, adjust = () => {}) {
  const prior = JSON.parse(readFileSync(resolve(root, 'scripts/tests/fixtures/grafana-native-timeout-receipt.json')));
  const reports = [], diagnostics = new BrowserStartupDiagnostics({ now: () => 1, snapshot: () => null,
    nativeSnapshot: () => null, report: text => reports.push(text) });
  Object.assign(diagnostics.value, prior, { ioWindow }); adjust(diagnostics.value);
  diagnostics.finish({ exitCode: null, signalCode: 'SIGKILL' }, false);
  assert.equal(reports.length, 1); assert.ok(Buffer.byteLength(reports[0].slice(marker.length)) <= 3000);
  return { prior, text: reports[0], value: JSON.parse(reports[0].slice(marker.length)) };
}

test('new oversized/unserializable I/O is omitted before any original native or shared PSI fields', () => {
  const cycle = {}; cycle.self = cycle;
  for (const [ioWindow, expected] of [{ huge: 'x'.repeat(4000) }, cycle].map((value, i) => [value, i ? 'serialization' : 'size'])) {
    const { prior, value } = receipt(ioWindow);
    assert.equal(value.ioWindow, undefined); assert.equal(value.ioOmitted, expected);
    assert.deepEqual(value.resources, prior.resources); assert.equal(value.omitted, undefined);
    assert.equal(value.failureKind, prior.failureKind);
  }
  const { value } = receipt({ huge: 'x'.repeat(4000) }, record => {
    record.native = { threads: { waits: { ioOrPageWait: 1 }, pipeNames: 2 } };
  });
  assert.equal(value.native.threads.waits.ioOrPageWait, 1); assert.equal(value.native.threads.pipeNames, 2);
});

test('the saved CI1818 timeout plus maximal new numbers preserves all prior fields inside the existing tail', () => {
  const prior = JSON.parse(readFileSync(resolve(root, 'scripts/tests/fixtures/grafana-io-timeout-receipt.json')));
  assert.equal(Buffer.byteLength(JSON.stringify(prior)), 2488);
  const ioWindow = window().value, max = Number.MAX_SAFE_INTEGER;
  ioWindow.devices = 64; ioWindow.paired = 64; ioWindow.spanMs = 86399999.999;
  for (const group of ['cgroup', 'device']) for (const name of Object.keys(ioWindow[group])) ioWindow[group][name] = max;
  const reports = [], diagnostics = new BrowserStartupDiagnostics({ now: () => 1, report: line => reports.push(line) });
  Object.assign(diagnostics.value, structuredClone(prior), { ioWindow });
  diagnostics.finish({ exitCode: null, signalCode: 'SIGKILL' }, false);
  const value = JSON.parse(reports[0].slice(marker.length)), { ioWindow: observed, ...retained } = value;
  assert.deepEqual(retained, prior); assert.deepEqual(observed, ioWindow);
  assert.ok(Buffer.byteLength(reports[0].slice(marker.length)) <= 3000); assert.equal(reports.length, 1);
  const failure = 'GRAFANA_BROWSER_FAILED:browser-start:GRAFANA_BROWSER_TIMEOUT:Browser.getVersion:'
    + 'spawn=1,stderr=1048576,protocol=67108864,responses=128,categories=crashpad+dbus+devtools+fontconfig+library+path-length+permission+policy+resource+sandbox+socket';
  const stderr = reports[0] + '\n' + failure + '\n'; assert.equal(stderr.slice(-4000), stderr);
  const aggregate = readFileSync(resolve(root, 'scripts/tests/m4-sla-calendar-boundary.test.mjs'), 'utf8');
  assert.ok(aggregate.includes("import './ops-grafana-io-window.test.mjs';"));
});

test('maximal existing native and original resource fields survive optional I/O size fallback', () => {
  const prior = JSON.parse(readFileSync(resolve(root, 'scripts/tests/fixtures/grafana-io-timeout-receipt.json')));
  const maximize = value => {
    if (!value || typeof value !== 'object') return;
    for (const [name, child] of Object.entries(value)) {
      if (typeof child === 'number') value[name] = Number.MAX_SAFE_INTEGER;
      else maximize(child);
    }
  };
  maximize(prior.native); prior.nativeSampleMs = 86399999.999;
  const reports = [], diagnostics = new BrowserStartupDiagnostics({ now: () => 1, report: line => reports.push(line) });
  Object.assign(diagnostics.value, structuredClone(prior), { ioWindow: window().value });
  diagnostics.finish({ exitCode: null, signalCode: 'SIGKILL' }, false);
  const value = JSON.parse(reports[0].slice(marker.length));
  assert.equal(value.ioWindow, undefined); assert.equal(value.omitted, undefined);
  assert.deepEqual(value.resources, prior.resources); assert.deepEqual(value.native, prior.native);
  assert.ok(Buffer.byteLength(reports[0].slice(marker.length)) <= 3000);
});

test('an omission marker cannot displace an original receipt that fits exactly', () => {
  const { value, text } = receipt({ huge: 'x'.repeat(4000) }, record => {
    // finish changes lifecycle; freeze its mutable fields before measuring to make exactly 3000 bytes.
    record.milestonesMs.cleanupFinished = 1; record.exit = { observed: true, code: null, signal: 'SIGKILL' };
    record.cleanup.exited = true; record.cleanup.failed = false;
    const { ioWindow, ...original } = record;
    original.padding = ''; record.padding = 'x'.repeat(3000 - Buffer.byteLength(JSON.stringify(original)));
  });
  assert.equal(Buffer.byteLength(text.slice(marker.length)), 3000);
  assert.equal(value.ioWindow, undefined); assert.equal(value.ioOmitted, undefined);
  assert.equal(value.omitted, undefined); assert.ok(value.resources.failure.host.io);
});

test('the original overflow and non-serializable native fallbacks remain available only after new I/O omission', () => {
  const oversized = receipt(window().value, value => {
    for (const phase of ['launch', 'failure']) value.resources[phase].host = { huge: 'x'.repeat(4000) };
  }).value;
  assert.equal(oversized.ioWindow, undefined); assert.equal(oversized.omitted, 'shared-resources');
  assert.equal(oversized.resources.failure.host, null);
  const cycle = {}; cycle.self = cycle;
  const unserializable = receipt(window().value, value => { value.native = cycle; }).value;
  assert.equal(unserializable.native, null); assert.equal(unserializable.omitted, 'native-unserializable');
  assert.equal(unserializable.ioWindow, undefined);
});

test('optional sampler exceptions cannot replace process failure, launch flags, deadlines or cleanup', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const child = new EventEmitter(), input = new EventEmitter(), output = new EventEmitter();
  child.pid = 123; child.stderr = new EventEmitter(); child.exitCode = null; child.signalCode = null;
  child.stdio = [null, null, child.stderr, input, output]; output.setEncoding = () => {};
  const calls = [], reports = []; let launches = 0, writes = 0, time = 0;
  input.write = (text, callback) => { writes++; callback(); return true; };
  const browser = new BrowserPipe('/PRIVATE-profile', { PATH: process.env.PATH }, {
    launch(file, args, options) { launches++; assert.deepEqual(args, chromiumArguments('/PRIVATE-profile'));
      assert.deepEqual(options, { env: { PATH: process.env.PATH }, detached: true, stdio: ['ignore', 'ignore', 'pipe', 'pipe', 'pipe'] }); return child; },
    diagnostics: { now: () => time, snapshot: (pid, { observeIo }) => { observeIo(privateRoot, () => null); return null; },
      nativeSnapshot: () => null, report: text => reports.push(text),
      ioSampler: { sample(phase) { calls.push(phase); throw new Error(secret); }, clear() { throw new Error(secret); } } },
  });
  const pending = browser.call('Browser.getVersion'), rejection = assert.rejects(pending, /GRAFANA_BROWSER_TIMEOUT:Browser.getVersion/);
  time = 9999; t.mock.timers.tick(9999); assert.equal(browser.closed, false); assert.equal(writes, 1);
  time = 10000; t.mock.timers.tick(1); await rejection;
  assert.equal(launches, 1); assert.deepEqual(calls, ['launch', 'failure']);
  assert.equal(browser.startupDiagnostics.value.timeoutElapsedMs, 10000);
  assert.equal(browser.startupDiagnostics.value.timeoutDriftMs, 0);
  assert.deepEqual(browser.startupDiagnostics.value.ioWindow, { coverage: 'unavailable' });
  browser.startupDiagnostics.finish({ exitCode: 0, signalCode: null }, false);
  assert.equal(reports.length, 1); assert.doesNotMatch(reports[0], /PRIVATE|password|0xdeadbeef/u);
});

test('new module uses fixed paths/static patterns without polling, launch, device traversal or unbounded reads', () => {
  const source = readFileSync(resolve(root, 'scripts/ops/grafana-browser-io-window.mjs'), 'utf8');
  assert.doesNotMatch(source, /new RegExp|setTimeout|setInterval|spawn\(|readFileSync|readdir|readlink|process\.kill/u);
  assert.equal(source.match(/safeDiagnosticRead\(read,/gu).length, 2);
});
