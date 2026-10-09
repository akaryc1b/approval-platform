import { performance } from 'node:perf_hooks';
import { diagnosticCgroupRoot, readBoundedDiagnosticFile, safeDiagnosticRead } from './grafana-browser-diagnostic-files.mjs';

const integer = text => /^\d{1,16}$/u.test(text) && Number.isSafeInteger(Number(text)) ? Number(text) : null;
const validPid = value => Number.isSafeInteger(value) && value > 0 && value <= 2147483647;
const states = new Set(['R', 'S', 'D', 'Z', 'T', 't', 'W', 'X', 'x', 'K', 'P', 'I']);
const clock = now => { try { const value = now();
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= Number.MAX_SAFE_INTEGER ? value : null;
} catch { return null; } };
const elapsed = (before, after) => before !== null && after !== null && after >= before && after - before <= 86400000
  ? Math.round((after - before) * 1000) / 1000 : null;

function identity(text, pid) {
  if (text === null || !text.endsWith('\n')) return null;
  const prefix = /^(\d{1,10}) \(/u.exec(text), end = text.lastIndexOf(') ');
  if (!prefix || Number(prefix[1]) !== pid || end < prefix[0].length || end - prefix[0].length > 64) return null;
  const fields = text.slice(end + 2).trim().split(/[ \t]+/u);
  // Current Linux stat has fields 3..52. Unknown/truncated layouts remain unknown.
  if (fields.length !== 50 || !states.has(fields[0]) || !fields.slice(1).every(field => /^-?\d{1,20}$/u.test(field))
    || !/^\d{1,20}$/u.test(fields[19])) return null;
  const start = BigInt(fields[19]);
  return start <= 18446744073709551615n ? start.toString() : null;
}

function counters(text, names, unit) {
  const result = Object.fromEntries(names.map(name => [name, null]));
  if (text === null || !text.endsWith('\n')) return result;
  const lines = text.split('\n');
  if (lines.length > 129 || lines.some(line => Buffer.byteLength(line) > 256)) return result;
  const seen = new Set();
  for (const line of lines) {
    const match = /^([A-Za-z_]+)(.*)$/u.exec(line);
    if (!match || !Object.hasOwn(result, match[1])) continue;
    const name = match[1], parsed = unit === 'kB' ? /^:[ \t]+(\d{1,16})[ \t]+kB$/u.exec(match[2])
      : unit === 'io' ? /^:[ \t]+(\d{1,16})$/u.exec(match[2]) : /^[ \t]+(\d{1,16})$/u.exec(match[2]);
    const number = parsed ? integer(parsed[1]) : null, bytes = number === null ? null : number * (unit === 'kB' ? 1024 : 1);
    result[name] = seen.has(name) || !Number.isSafeInteger(bytes) ? null : bytes;
    seen.add(name);
  }
  return result;
}

/** Two already-owned leaders only; private PID/start tokens and handles never leave this closure. */
export function createStartupIoObserver(owned, { read = readBoundedDiagnosticFile, now = () => performance.now() } = {}) {
  let handles = Array.isArray(owned) && owned.length === 2 ? [owned[0], owned[1]] : [], baseline, origin = null;
  let launchRoot = null, endpointRoot = null, endpointPhase = null, completed = false, value = null;
  const get = (file, limit) => safeDiagnosticRead(read, file, limit);
  const livePid = child => { try { const pid = child?.pid;
    return child && child.exitCode === null && child.signalCode === null && validPid(pid) ? pid : null;
  } catch { return null; } };
  const capture = (root, membership = false) => {
    const started = clock(now);
    if (membership) root = diagnosticCgroupRoot(get('/proc/self/cgroup', 4096));
    const pids = handles.map(livePid), duplicate = handles.length === 2
      && (handles[0] === handles[1] || pids[0] !== null && pids[0] === pids[1]);
    const services = handles.map((child, index) => {
      const pid = pids[index]; if (pid === null || duplicate) return null;
      const file = '/proc/' + pid;
      const before = identity(get(file + '/stat', 2048), pid);
      const io = counters(get(file + '/io', 1024), ['read_bytes', 'write_bytes'], 'io');
      const after = identity(get(file + '/stat', 2048), pid);
      return before !== null && before === after && livePid(child) === pid
        ? { pid, start: before, readBytes: io.read_bytes, writeBytes: io.write_bytes } : null;
    });
    const host = counters(get('/proc/meminfo', 4096), ['Dirty', 'Writeback'], 'kB');
    const group = counters(root === null ? null : get(root + '/memory.stat', 4096), ['file_dirty', 'file_writeback']);
    return { started, duration: elapsed(started, clock(now)), root, services,
      host: { dirty: host.Dirty, writeback: host.Writeback }, group: { dirty: group.file_dirty, writeback: group.file_writeback } };
  };
  baseline = capture(null, true);
  return {
    origin(time) { if (origin === null) origin = typeof time === 'number' && Number.isFinite(time) && time >= 0 ? time : null; },
    observeRoot(phase, root) {
      if (completed) return;
      if (phase === 'launch') launchRoot = root;
      else { endpointRoot = root; endpointPhase = phase; }
    },
    complete(end) {
      if (completed) return value;
      completed = true;
      try {
        const sameGroup = baseline.root !== null && baseline.root === launchRoot && baseline.root === endpointRoot && endpointPhase === end;
        const last = capture(sameGroup ? baseline.root : null);
        const spanMs = elapsed(baseline.started, last.started), validSpan = spanMs !== null && last.started > baseline.started;
        let paired = 0, readBytes = 0, writeBytes = 0;
        for (let index = 0; index < 2; index++) {
          const before = baseline.services[index], after = last.services[index];
          if (!before || !after || before.pid !== after.pid || before.start !== after.start
            || [before.readBytes, before.writeBytes, after.readBytes, after.writeBytes].some(number => number === null)
            || after.readBytes < before.readBytes || after.writeBytes < before.writeBytes) continue;
          paired++; readBytes += after.readBytes - before.readBytes; writeBytes += after.writeBytes - before.writeBytes;
        }
        const complete = validSpan && paired === 2 && Number.isSafeInteger(readBytes) && Number.isSafeInteger(writeBytes);
        const gauges = (first, last) => ({ dirtyBytes: [first?.dirty ?? null, last?.dirty ?? null],
          writebackBytes: [first?.writeback ?? null, last?.writeback ?? null] });
        value = { schema: 1, end: ['handshake', 'failure'].includes(end) ? end : 'unavailable',
          spanMs: validSpan ? spanMs : null, leadMs: elapsed(baseline.started, origin), sampleMs: [baseline.duration, last.duration],
          services: { expected: 2, paired, coverage: complete ? 'complete' : paired && validSpan ? 'partial' : 'unavailable',
            readBytes: complete ? readBytes : null, writeBytes: complete ? writeBytes : null },
          host: gauges(baseline.host, last.host), cgroup: gauges(sameGroup ? baseline.group : null, sameGroup ? last.group : null) };
      } catch { value = { status: 'unavailable', reason: 'capture' }; }
      finally { baseline = null; handles = []; launchRoot = null; endpointRoot = null; }
      return value;
    },
    clear() { baseline = null; handles = []; launchRoot = null; endpointRoot = null; completed = true; },
  };
}
