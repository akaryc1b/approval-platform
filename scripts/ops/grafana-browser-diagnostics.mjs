import { performance } from 'node:perf_hooks';
import { readBoundedDiagnosticFile, safeDiagnosticRead as safe } from './grafana-browser-diagnostic-files.mjs';
import { browserNativeSnapshot } from './grafana-browser-native-snapshot.mjs';
export { readBoundedDiagnosticFile } from './grafana-browser-diagnostic-files.mjs';

const signals = new Set(['SIGHUP', 'SIGINT', 'SIGQUIT', 'SIGILL', 'SIGTRAP', 'SIGABRT',
  'SIGBUS', 'SIGFPE', 'SIGKILL', 'SIGUSR1', 'SIGSEGV', 'SIGUSR2', 'SIGPIPE', 'SIGALRM', 'SIGTERM', 'SIGSYS']);
const states = new Set(['R', 'S', 'D', 'Z', 'T', 't', 'W', 'X', 'x', 'K', 'P', 'I']);
const integer = value => /^\d{1,16}$/u.test(String(value)) && Number.isSafeInteger(Number(value))
  ? Number(value) : null;
const finite = value => typeof value === 'number' && Number.isFinite(value) && value >= 0;
const milliseconds = value => finite(value) ? Math.min(86400000, Math.round(value * 1000) / 1000) : null;
function pressure(text) {
  if (text === null) return null;
  const result = {};
  for (const line of text.trim().split('\n')) {
    const match = /^(some|full) avg10=(\d+(?:\.\d+)?) avg60=(\d+(?:\.\d+)?) avg300=(\d+(?:\.\d+)?) total=(\d+)$/u.exec(line);
    if (!match || Object.hasOwn(result, match[1] + 'Us')) return null;
    const averages = match.slice(2, 5).map(Number), total = integer(match[5]);
    if (averages.some(value => !finite(value) || value > 100) || total === null) return null;
    result[match[1] + 'Us'] = total;
  }
  return Object.keys(result).length ? result : null;
}

function counters(text, patterns) {
  if (text === null) return null;
  const result = {};
  for (const [name, pattern] of Object.entries(patterns)) {
    const matches = [...text.matchAll(pattern)];
    if (matches.length === 1) result[name] = integer(matches[0][1]);
  }
  return Object.keys(result).length ? result : null;
}

/** Fixed numeric/enum fields only. No executable, command line, path or process ID is exported. */
export function browserResourceSnapshot(pid, { read = readBoundedDiagnosticFile } = {}) {
  if (!Number.isSafeInteger(pid) || pid < 1 || pid > 2147483647) return null;
  const processRoot = '/proc/' + pid;
  const status = safe(read, processRoot + '/status', 8192);
  const stat = safe(read, processRoot + '/stat', 4096);
  const scheduler = safe(read, processRoot + '/schedstat', 256);
  const fields = stat && /^\d+ \(/u.test(stat) && stat.includes(') ')
    ? stat.slice(stat.lastIndexOf(') ') + 2).trim().split(/\s+/u) : [];
  const state = status?.match(/^State:\s+([A-Za-z])(?:\s|$)/mu)?.[1];
  const numericStatus = pattern => {
    const match = status?.match(pattern);
    return match ? integer(match[1]) : null;
  };
  const schedule = scheduler?.trim().match(/^(\d+) (\d+) (\d+)$/u);
  const validStat = fields.length >= 22 && states.has(fields[0]);
  const owned = { state: states.has(state) ? state : null, rssKiB: numericStatus(/^VmRSS:\s+(\d+) kB$/mu),
    threads: numericStatus(/^Threads:\s+(\d+)$/mu), userCpuTicks: validStat ? integer(fields[11]) : null,
    systemCpuTicks: validStat ? integer(fields[12]) : null,
    schedulerCpuNs: schedule ? integer(schedule[1]) : null,
    schedulerWaitNs: schedule ? integer(schedule[2]) : null,
    schedulerSlices: schedule ? integer(schedule[3]) : null };
  const host = Object.fromEntries(['cpu', 'memory', 'io'].map(name =>
    [name, pressure(safe(read, '/proc/pressure/' + name, 2048))]));
  // The kernel-provided unified-cgroup location is used only for fixed diagnostic files.
  // Reject traversal, ambiguity and arbitrary paths; never export the location itself.
  const membership = safe(read, processRoot + '/cgroup', 4096);
  const entries = membership?.trim().split('\n') ?? [];
  const path = entries.length === 1 && entries[0].startsWith('0::/') ? entries[0].slice(3) : null;
  const segments = path?.split('/') ?? [];
  const validPath = path !== null && path.length <= 1024 && /^\/[A-Za-z0-9_./:-]*$/u.test(path)
    && !segments.some(part => part === '.' || part === '..');
  let cgroup = null;
  if (validPath) {
    const root = '/sys/fs/cgroup' + (path === '/' ? '' : path);
    const scalar = file => { const text = safe(read, root + '/' + file, 64)?.trim();
      return text === 'max' ? 'max' : integer(text); };
    cgroup = {
      pressure: Object.fromEntries(['cpu', 'memory', 'io'].map(name =>
        [name, pressure(safe(read, root + '/' + name + '.pressure', 2048))])),
      cpu: counters(safe(read, root + '/cpu.stat', 2048), {
        usage_usec: /^usage_usec (\d+)$/gmu, nr_throttled: /^nr_throttled (\d+)$/gmu,
        throttled_usec: /^throttled_usec (\d+)$/gmu,
      }),
      memoryEvents: counters(safe(read, root + '/memory.events', 1024), {
        high: /^high (\d+)$/gmu, oom: /^oom (\d+)$/gmu, oom_kill: /^oom_kill (\d+)$/gmu,
      }),
      memoryCurrentBytes: scalar('memory.current'), memoryMaxBytes: scalar('memory.max'),
      pidsCurrent: scalar('pids.current'), pidsMax: scalar('pids.max'),
    };
  }
  return { owned, host, cgroup };
}

/** One bounded report per owned browser; diagnostics cannot change the browser's result. */
export class BrowserStartupDiagnostics {
  constructor({ now = () => performance.now(), utilization = () => performance.eventLoopUtilization(),
    snapshot = browserResourceSnapshot, nativeSnapshot = browserNativeSnapshot, profile = null,
    report = text => console.error(text) } = {}) {
    this.now = now; this.utilization = utilization; this.snapshot = snapshot; this.report = report;
    this.nativeSnapshot = nativeSnapshot; this.profile = profile;
    this.origin = this.readClock(); this.baselineLoop = this.readLoop(); this.pid = null; this.emitted = false;
    this.value = { schema: 1, milestonesMs: { launch: 0, launchReturned: null, spawned: null, firstWriteQueued: null,
      firstWriteCallback: null, firstStderr: null, firstProtocol: null, handshake: null, failure: null,
      exit: null, cleanupFinished: null }, firstWriteCallbackFailed: null, browserVersion: null,
    failureKind: null, timeoutElapsedMs: null, timeoutDriftMs: null, eventLoop: null,
    resourceSampleDurationMs: { launch: null, failure: null },
    native: null, nativeSampleMs: null, nativeMode: null, pipeMarkerValidation: 'unknown',
    resources: { launch: null, failure: null }, exit: { observed: false, code: null, signal: null },
    cleanup: { term: 'not-attempted', kill: 'not-attempted', exited: null, failed: false } };
  }
  readClock() { try { const value = this.now(); return finite(value) ? value : null; } catch { return null; } }
  readLoop() { try { const value = this.utilization();
    return value && finite(value.active) && finite(value.idle) ? { active: value.active, idle: value.idle } : null;
  } catch { return null; } }
  elapsed() { const current = this.readClock();
    return current === null || this.origin === null ? null : milliseconds(current - this.origin); }
  mark(name) { if (Object.hasOwn(this.value.milestonesMs, name) && this.value.milestonesMs[name] === null)
    this.value.milestonesMs[name] = this.elapsed(); }
  launched(pid) { this.pid = pid; this.mark('launchReturned'); this.value.resources.launch = this.sample('launch'); }
  sample(phase) {
    const before = this.readClock();
    try { return this.snapshot(this.pid); } catch { return null; }
    finally { const after = this.readClock();
      this.value.resourceSampleDurationMs[phase] = before === null || after === null ? null : milliseconds(after - before); }
  }
  observeNative(mode) {
    if (!['failure', 'probe'].includes(mode) || this.value.nativeMode === 'failure'
      || this.value.nativeMode === 'probe' && mode === 'probe') return this.value.native;
    this.value.nativeMode = mode; const before = this.readClock();
    try { this.value.native = this.nativeSnapshot(this.pid, this.profile); }
    catch { this.value.native = null; }
    finally { const after = this.readClock();
      this.value.nativeSampleMs = before === null || after === null ? null : milliseconds(after - before); }
    const threads = this.value.native?.threads;
    if (mode === 'probe' && this.value.browserVersion !== null && threads?.coverage === 'complete' && threads.statReads === threads.scanned
      && threads.pipeNames >= 2) this.value.pipeMarkerValidation = 'verified';
    return this.value.native;
  }
  writeCallback(failed) { if (this.value.milestonesMs.firstWriteCallback !== null) return;
    this.mark('firstWriteCallback'); this.value.firstWriteCallbackFailed = failed === true; }
  version(product) { if (typeof product === 'string' && product.length <= 64
    && /^(?:HeadlessChrome|Chrome)\/\d{1,5}(?:\.\d{1,5}){3}$/u.test(product)) this.value.browserVersion = product;
    this.mark('handshake'); }
  timeout(startedAt, budget) {
    const now = this.readClock();
    if (now !== null && finite(startedAt) && budget === 10000) {
      this.value.timeoutElapsedMs = milliseconds(now - startedAt);
      this.value.timeoutDriftMs = milliseconds(Math.max(0, now - startedAt - budget));
    }
  }
  failure(code) {
    if (code === 'GRAFANA_BROWSER_STOPPED' || this.value.failureKind !== null) return;
    this.mark('failure');
    const allowed = new Map([['GRAFANA_BROWSER_TIMEOUT', 'timeout'], ['GRAFANA_BROWSER_EXITED', 'exit'],
      ['GRAFANA_BROWSER_START_FAILED', 'spawn'], ['GRAFANA_BROWSER_WRITE_FAILED', 'write'],
      ['GRAFANA_BROWSER_PIPE_ENDED', 'pipe-end'], ['GRAFANA_BROWSER_PIPE_CLOSED', 'pipe-error'],
      ['GRAFANA_BROWSER_STDERR_CLOSED', 'stderr-error'], ['GRAFANA_BROWSER_PROTOCOL_INVALID', 'protocol'],
      ['GRAFANA_BROWSER_PROTOCOL_LIMIT', 'protocol-limit']]);
    this.value.failureKind = allowed.get(typeof code === 'string' ? code.split(':')[0] : '') ?? 'other';
    const loop = this.readLoop();
    if (loop && this.baselineLoop) this.value.eventLoop = {
      activeMs: milliseconds(loop.active - this.baselineLoop.active), idleMs: milliseconds(loop.idle - this.baselineLoop.idle) };
    this.value.resources.failure = this.sample('failure');
    this.observeNative('failure');
  }
  exited(code, signal) { this.mark('exit'); this.value.exit = { observed: true,
    code: Number.isInteger(code) && code >= 0 && code <= 255 ? code : null,
    signal: signals.has(signal) ? signal : null }; }
  cleanup(event) {
    const key = event?.signal === 'SIGTERM' ? 'term' : event?.signal === 'SIGKILL' ? 'kill' : null;
    if (key && ['attempted', 'sent', 'missing', 'error'].includes(event.result)) this.value.cleanup[key] = event.result;
  }
  finish(child, failed) {
    this.mark('cleanupFinished'); this.value.cleanup.failed = failed === true;
    this.value.cleanup.exited = child?.exitCode !== null && child?.exitCode !== undefined
      || child?.signalCode !== null && child?.signalCode !== undefined;
    if (!this.value.exit.observed && this.value.cleanup.exited) this.exited(child.exitCode, child.signalCode);
    if (this.emitted) return;
    this.emitted = true;
    try { let value = this.value, text;
      try { text = JSON.stringify(value); }
      catch { value = { ...value, native: null, omitted: 'native-unserializable' }; text = JSON.stringify(value); }
      if (Buffer.byteLength(text) > 3000) {
        const onlyOwned = snapshot => snapshot ? { owned: snapshot.owned, host: null, cgroup: null } : null;
        value = { ...value, resources: { launch: onlyOwned(value.resources.launch), failure: onlyOwned(value.resources.failure) },
          omitted: 'shared-resources' }; text = JSON.stringify(value);
      }
      if (Buffer.byteLength(text) > 3000) {
        value = { ...value, native: null, omitted: 'native-and-shared-resources' }; text = JSON.stringify(value);
      }
      if (Buffer.byteLength(text) <= 3000) this.report('GRAFANA_BROWSER_DIAGNOSTICS=' + text);
    } catch {} // A reporting failure must never replace the original browser/cleanup failure.
  }
}
