import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import test from 'node:test';
import { browserNativeSnapshot } from '../ops/grafana-browser-native-snapshot.mjs';
import { BrowserStartupDiagnostics } from '../ops/grafana-browser-diagnostics.mjs';

const root = resolve(import.meta.dirname, '../..');
const marker = 'GRAFANA_BROWSER_DIAGNOSTICS=';
const secret = 'PRIVATE-provider-token';
function stat({ name = secret, state = 'S', minor = '11', major = '12', delay = '13' } = {}) {
  const fields = Array(50).fill('0'); fields[0] = state; fields[7] = minor; fields[9] = major; fields[39] = delay;
  return '123 (' + name + ') ' + fields.join(' ') + '\n';
}
function fixture({ ids = ['123', '124', '125'], overrides = {}, errorAt = -1, accounting = '1' } = {}) {
  const paths = [], values = { '/proc/123/stat': stat(), '/proc/123/io': 'read_bytes: 1234\nwrite_bytes: 5678\n',
    '/proc/sys/kernel/task_delayacct': accounting + '\n' };
  for (const [index, id] of ids.entries()) {
    values['/proc/123/task/' + id + '/stat'] = stat({ name: index ? 'DevToolsPipeHan' : secret, state: index ? 'S' : 'D' });
    values['/proc/123/task/' + id + '/wchan'] = index ? 'futex_wait_queue' : 'folio_wait_bit_common';
  }
  Object.assign(values, overrides);
  const control = { directoryReads: 0, closed: false };
  return { control, paths, options: {
    read(file, limit) { paths.push(file); assert.ok(limit <= 2048); return values[file] ?? null; },
    openDirectory(file, options) { assert.equal(file, '/proc/123/task'); assert.equal(options.bufferSize, 1); let next = 0;
      return { readSync() { control.directoryReads++; if (next === errorAt) throw new Error(secret);
        return next < ids.length ? { name: ids[next++] } : null; }, closeSync() { control.closed = true; } }; },
    readLink(file) { assert.equal(file, '/proc/123/exe'); return '/opt/google/chrome/chrome'; },
    statFilesystem(file, options) { assert.equal(file, '/owned-private/profile'); assert.equal(options.bigint, true);
      return { type: 0xef53n, bavail: 1024n, bsize: 4096n, ffree: 12345n }; },
  } };
}

test('native snapshot uses verified stat fields, combined truncated pipe names and primitive-only output', () => {
  const f = fixture(), value = browserNativeSnapshot(123, '/owned-private/profile', f.options);
  assert.equal(value.minorFaults, 11); assert.equal(value.majorFaults, 12); assert.equal(value.readBytes, 1234);
  assert.equal(value.writeBytes, 5678); assert.equal(value.delayacct, 'enabled'); assert.equal(value.executable, 'chrome');
  assert.deepEqual(value.profileFs, { kind: 'ext', scope: 'profile', profileState: 'present', availableBytes: 4194304, freeInodes: 12345 });
  assert.equal(value.threads.coverage, 'complete'); assert.equal(value.threads.scanned, 3); assert.equal(value.threads.statReads, 3);
  assert.equal(value.threads.pipeNames, 2); assert.equal(value.threads.ioDelayTicks, 39);
  assert.equal(value.threads.delayKnown, 3); assert.equal(value.threads.states.D, 1); assert.equal(value.threads.states.S, 2);
  assert.equal(value.threads.waits.ioOrPageWait, 1); assert.equal(value.threads.waits.futex, 2); assert.equal(f.control.closed, true);
  const text = JSON.stringify(value); assert.ok(!text.includes(secret)); assert.ok(!text.includes('/owned')); assert.ok(!text.includes('/proc'));
  assert.ok(!text.includes('DevTools')); assert.ok(!text.includes('folio_wait')); assert.ok(!text.includes('pid'));
});

test('bounded directory iteration never reads or samples beyond its explicit cap', () => {
  const f = fixture({ ids: Array.from({ length: 1000 }, (_, index) => String(index + 123)) });
  const value = browserNativeSnapshot(123, '/owned-private/profile', f.options);
  assert.equal(value.threads.coverage, 'partial'); assert.equal(value.threads.scanned, 64);
  assert.equal(f.control.directoryReads, 65); assert.equal(f.control.closed, true);
  assert.equal(f.paths.filter(path => path.includes('/task/')).length, 128);
});

test('partial enumeration, invalid entries and disappeared task files never become complete zero-marker evidence', () => {
  const partial = fixture({ errorAt: 1 });
  const first = browserNativeSnapshot(123, '/owned-private/profile', partial.options);
  assert.equal(first.threads.coverage, 'partial'); assert.equal(first.threads.scanned, 1);
  const invalid = fixture({ ids: ['../../SECRET', '123', '123', '2147483648', '124'] });
  const second = browserNativeSnapshot(123, '/owned-private/profile', invalid.options);
  assert.equal(second.threads.coverage, 'partial'); assert.equal(second.threads.scanned, 2);
  assert.ok(invalid.paths.every(path => !path.includes('..')));
  const vanished = fixture({ overrides: { '/proc/123/task/124/stat': null, '/proc/123/task/124/wchan': '0' } });
  const third = browserNativeSnapshot(123, '/owned-private/profile', vanished.options);
  assert.equal(third.threads.coverage, 'partial'); assert.equal(third.threads.states.unknown, 1);
  assert.equal(third.threads.waits.unknown, 1); assert.equal(third.threads.statReads, 2);
});

test('missing permissions and malformed kernel files stay unknown without exporting error text', () => {
  const unavailable = browserNativeSnapshot(123, '/owned-private/profile', {
    read() { throw new Error(secret); }, openDirectory() { throw new Error(secret); },
    readLink() { throw new Error(secret); }, statFilesystem() { throw new Error(secret); },
  });
  assert.equal(unavailable.threads.coverage, 'unavailable'); assert.equal(unavailable.threads.pipeNames, null);
  assert.equal(unavailable.threads.ioDelayTicks, null); assert.equal(unavailable.delayacct, 'unavailable');
  assert.equal(unavailable.executable, 'unavailable'); assert.equal(unavailable.profileFs.kind, 'unavailable');
  assert.ok(!JSON.stringify(unavailable).includes(secret));
  const f = fixture({ overrides: { '/proc/123/stat': stat({ minor: '-1', major: '9007199254740992' }),
    '/proc/123/io': 'read_bytes: 7\nread_bytes: 8\nwrite_bytes: PRIVATE\n',
    '/proc/123/task/123/stat': '123 (PRIVATE) bad-state', '/proc/123/task/124/wchan': 'x'.repeat(129) } });
  const value = browserNativeSnapshot(123, '/owned-private/profile', f.options);
  assert.equal(value.minorFaults, null); assert.equal(value.majorFaults, null); assert.equal(value.readBytes, null);
  assert.equal(value.writeBytes, null); assert.equal(value.threads.states.unknown, 1); assert.equal(value.threads.waits.unknown, 1);
});

test('disabled or unreadable delay accounting and unsafe sums cannot imply no I/O delay', () => {
  for (const accounting of ['0', 'PRIVATE', '']) {
    const f = fixture({ accounting }), value = browserNativeSnapshot(123, '/owned-private/profile', f.options);
    assert.equal(value.threads.ioDelayTicks, null); assert.equal(value.threads.delayKnown, 0);
  }
  const f = fixture({ overrides: { '/proc/123/task/123/stat': stat({ delay: String(Number.MAX_SAFE_INTEGER) }),
    '/proc/123/task/124/stat': stat({ delay: String(Number.MAX_SAFE_INTEGER) }) } });
  assert.equal(browserNativeSnapshot(123, '/owned-private/profile', f.options).threads.ioDelayTicks, null);
});

test('wait channels use fixed coarse classes and never export raw symbolic names', () => {
  const names = ['io_schedule', 'futex_wait', 'ep_poll', 'pipe_read', 'unix_stream_data_wait', secret, '0'];
  const ids = names.map((_, index) => String(index + 123)), overrides = {};
  for (const [index, name] of names.entries()) overrides['/proc/123/task/' + ids[index] + '/wchan'] = name;
  const f = fixture({ ids, overrides }), value = browserNativeSnapshot(123, '/owned-private/profile', f.options);
  assert.deepEqual(value.threads.waits, { ioOrPageWait: 1, futex: 1, poll: 1, pipe: 1, socket: 1, other: 1, unknown: 1 });
  assert.ok(!JSON.stringify(value).includes(secret));
});

test('executable and filesystem classification reject private strings, unknown values and unsafe numbers', () => {
  const f = fixture();
  for (const [path, expected] of [['/usr/lib/chromium/chromium', 'chromium'], ['/usr/bin/bash', 'shell'],
    ['/private/chrome', 'other'], ['x'.repeat(4097), 'unavailable']]) {
    const value = browserNativeSnapshot(123, '/owned-private/profile', { ...f.options, readLink: () => path,
      statFilesystem: () => ({ type: 999n, bavail: -1n, bsize: 4096n, ffree: 9007199254740992n }) });
    assert.equal(value.executable, expected); assert.deepEqual(value.profileFs, { kind: 'other', scope: 'profile',
      profileState: 'present', availableBytes: null, freeInodes: null });
    assert.ok(!JSON.stringify(value).includes('/private'));
  }
  assert.equal(browserNativeSnapshot(-1, '/owned-private/profile', f.options), null);
});

test('a not-yet-created profile reports only its parent filesystem and an explicit missing state', () => {
  const f = fixture(), reads = [];
  const value = browserNativeSnapshot(123, '/owned-private/profile', { ...f.options, statFilesystem(file) {
    reads.push(file); if (reads.length === 1) throw Object.assign(new Error(secret), { code: 'ENOENT' });
    return { type: 0x1021994n, bavail: 1n, bsize: 4096n, ffree: 2n };
  } });
  assert.deepEqual(reads, ['/owned-private/profile', '/owned-private']);
  assert.deepEqual(value.profileFs, { kind: 'tmpfs', scope: 'parent', profileState: 'missing', availableBytes: 4096, freeInodes: 2 });
  const rejected = browserNativeSnapshot(123, '/owned-private/../../etc', { ...f.options, statFilesystem() { assert.fail('traversal'); } });
  assert.equal(rejected.profileFs, null);
});

test('native sampling is failure-only unless the existing successful probe explicitly observes its own browser', () => {
  let calls = 0, now = 0; const f = fixture();
  const d = new BrowserStartupDiagnostics({ profile: '/owned-private/profile', now: () => now,
    snapshot: () => null, nativeSnapshot(pid, profile) { calls++; now += 4; return browserNativeSnapshot(pid, profile, f.options); }, report() {} });
  d.launched(123); d.version('Chrome/154.0.8037.97'); assert.equal(calls, 0);
  d.observeNative('probe'); assert.equal(calls, 1); assert.equal(d.value.nativeSampleMs, 4);
  assert.equal(d.value.pipeMarkerValidation, 'verified');
  d.observeNative('probe'); assert.equal(calls, 1);
  d.failure('GRAFANA_BROWSER_TIMEOUT:Browser.getVersion'); assert.equal(calls, 2); assert.equal(d.value.nativeMode, 'failure');
  d.failure('GRAFANA_BROWSER_PIPE_ENDED'); assert.equal(calls, 2);
});

test('incomplete or zero marker observations remain unknown, including a throwing sampler', () => {
  for (const setup of [fixture({ errorAt: 1 }), fixture({ ids: ['123'] })]) {
    const d = new BrowserStartupDiagnostics({ snapshot: () => null,
      nativeSnapshot: () => browserNativeSnapshot(123, '/owned-private/profile', setup.options), report() {} });
    d.launched(123); d.observeNative('probe'); assert.equal(d.value.pipeMarkerValidation, 'unknown');
  }
  let now = 0; const reports = [];
  const d = new BrowserStartupDiagnostics({ snapshot: () => null, now: () => now,
    nativeSnapshot() { now += 3; throw new Error(secret); }, report: value => reports.push(value) });
  d.launched(123); d.failure('GRAFANA_BROWSER_PIPE_ENDED'); d.finish({ exitCode: 0, signalCode: null }, false);
  assert.equal(d.value.native, null); assert.equal(d.value.nativeSampleMs, 3); assert.equal(reports.length, 1);
  assert.equal(d.value.failureKind, 'pipe-end'); assert.ok(!reports[0].includes(secret));
});

test('the real CI1815 receipt retains a fully populated native discriminator inside the unchanged stderr tail', () => {
  const prior = JSON.parse(readFileSync(resolve(root, 'scripts/tests/fixtures/grafana-native-timeout-receipt.json')));
  const f = fixture({ ids: Array.from({ length: 64 }, (_, index) => String(index + 123)) });
  const native = browserNativeSnapshot(123, '/owned-private/profile', f.options), reports = [];
  const d = new BrowserStartupDiagnostics({ report: value => reports.push(value), snapshot: () => null, nativeSnapshot: () => null });
  Object.assign(d.value, prior, { native, nativeMode: 'failure', nativeSampleMs: 8, pipeMarkerValidation: 'unknown' });
  d.finish({ exitCode: null, signalCode: 'SIGKILL' }, false);
  assert.equal(reports.length, 1); assert.ok(Buffer.byteLength(reports[0]) <= 3028);
  const result = JSON.parse(reports[0].slice(marker.length)); assert.deepEqual(result.native, native);
  assert.equal(result.omitted, undefined); assert.deepEqual(result.resources, prior.resources);
  const original = 'GRAFANA_BROWSER_FAILED:browser-start:GRAFANA_BROWSER_TIMEOUT:Browser.getVersion:spawn=1,stderr=382,protocol=0,responses=0,categories=dbus';
  const stderr = reports[0] + '\n' + original + '\n'; assert.equal(stderr.slice(-4000), stderr);
  assert.equal(JSON.parse(stderr.split('\n')[0].slice(marker.length)).native.threads.scanned, 64);
});

test('maximal safe native counters retain the real timeout discriminator and all original shared counters', () => {
  const prior = JSON.parse(readFileSync(resolve(root, 'scripts/tests/fixtures/grafana-native-timeout-receipt.json')));
  const max = String(Number.MAX_SAFE_INTEGER), ids = Array.from({ length: 64 }, (_, index) => String(index + 123));
  const overrides = { '/proc/123/stat': stat({ minor: max, major: max }),
    '/proc/123/io': 'read_bytes: ' + max + '\nwrite_bytes: ' + max + '\n' };
  for (const [index, id] of ids.entries()) overrides['/proc/123/task/' + id + '/stat'] = stat({
    name: 'DevToolsPipeHan', delay: index === 0 ? max : '0' });
  const f = fixture({ ids, overrides }), native = browserNativeSnapshot(123, '/owned-private/profile', {
    ...f.options, statFilesystem: () => ({ type: 0xef53n, bsize: 1n, bavail: BigInt(max), ffree: BigInt(max) }),
  });
  const reports = [], d = new BrowserStartupDiagnostics({ snapshot: () => null, nativeSnapshot: () => null, report: value => reports.push(value) });
  Object.assign(d.value, prior, { native, nativeSampleMs: 86399999.999, nativeMode: 'failure' });
  d.finish({ exitCode: null, signalCode: 'SIGKILL' }, false);
  assert.equal(reports.length, 1); const value = JSON.parse(reports[0].slice(marker.length));
  assert.equal(value.omitted, undefined); assert.deepEqual(value.resources, prior.resources); assert.deepEqual(value.native, native);
  assert.ok(Buffer.byteLength(reports[0]) <= 3028);
  const failure = 'GRAFANA_BROWSER_FAILED:browser-start:GRAFANA_BROWSER_TIMEOUT:Browser.getVersion:'
    + 'spawn=1,stderr=1048576,protocol=67108864,responses=128,categories=crashpad+dbus+devtools+fontconfig+library+path-length+permission+policy+resource+sandbox+socket';
  const stderr = reports[0] + '\n' + failure + '\n'; assert.equal(stderr.slice(-4000), stderr);
});

test('oversized extended diagnostics preserve lifecycle, native discriminator and cleanup with explicit omission', () => {
  const reports = [], f = fixture(), d = new BrowserStartupDiagnostics({ report: value => reports.push(value), snapshot: () => null, nativeSnapshot: () => null });
  const prior = JSON.parse(readFileSync(resolve(root, 'scripts/tests/fixtures/grafana-native-timeout-receipt.json')));
  Object.assign(d.value, prior, { native: browserNativeSnapshot(123, '/owned-private/profile', f.options), nativeMode: 'failure' });
  // Inflated fixed numeric counter text simulates schema growth; native and lifecycle must survive.
  for (const phase of ['launch', 'failure']) for (const scope of ['host', 'cgroup']) {
    d.value.resources[phase][scope] = Object.fromEntries(Array.from({ length: 100 }, (_, index) => ['counter' + index, Number.MAX_SAFE_INTEGER]));
  }
  d.finish({ exitCode: null, signalCode: 'SIGKILL' }, false); assert.equal(reports.length, 1);
  const value = JSON.parse(reports[0].slice(marker.length)); assert.equal(value.omitted, 'shared-resources');
  assert.equal(value.failureKind, 'timeout'); assert.equal(value.exit.signal, 'SIGKILL'); assert.equal(value.cleanup.failed, false);
  assert.equal(value.native.threads.pipeNames, 2); assert.equal(value.resources.failure.host, null);
});

test('a non-serializable native extension cannot erase the original diagnostic receipt', () => {
  const reports = [], cyclic = {}; cyclic.self = cyclic;
  const d = new BrowserStartupDiagnostics({ snapshot: () => null, nativeSnapshot: () => cyclic, report: value => reports.push(value) });
  d.launched(123); d.failure('GRAFANA_BROWSER_TIMEOUT:Browser.getVersion'); d.finish({ exitCode: 0, signalCode: null }, false);
  assert.equal(reports.length, 1); const value = JSON.parse(reports[0].slice(marker.length));
  assert.equal(value.native, null); assert.equal(value.omitted, 'native-unserializable');
  assert.equal(value.failureKind, 'timeout'); assert.equal(value.cleanup.exited, true);
});

test('literal parsing and the existing successful probe preserve the one-launch validation boundary', () => {
  const source = readFileSync(resolve(root, 'scripts/ops/grafana-browser-native-snapshot.mjs'), 'utf8');
  assert.ok(!source.includes('new RegExp')); assert.ok(!source.includes('readdirSync'));
  assert.ok(!source.includes('spawn(')); assert.ok(!source.includes('process.kill'));
  const probe = readFileSync(resolve(root, 'scripts/tests/ops-grafana-browser-transport.test.mjs'), 'utf8');
  assert.equal(probe.split('new BrowserPipe(resolve(home').length - 1, 1);
  assert.equal(probe.split('await browser.start()').length - 1, 1);
  assert.ok(probe.includes("browser.startupDiagnostics.observeNative('probe')"));
});
