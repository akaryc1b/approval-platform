import { performance } from 'node:perf_hooks';
import { readBoundedDiagnosticFile, safeDiagnosticRead } from './grafana-browser-diagnostic-files.mjs';

const MAX_BYTES = 8192, MAX_ROWS = 64, MAX_ROW_BYTES = 1024, MAX_TOKENS = 32;
const integer = value => /^\d{1,16}$/u.test(value) && Number.isSafeInteger(Number(value)) ? Number(value) : null;
const fields = { rbytes: 'readBytes', wbytes: 'writeBytes', rios: 'readOps', wios: 'writeOps' };
const groupFields = Object.values(fields);
const deviceFields = ['readOps', 'readSectors', 'writeOps', 'writeSectors', 'ioMs', 'weightedMs'];
const clock = now => { try { const value = now();
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= Number.MAX_SAFE_INTEGER ? value : null;
} catch { return null; } };

function key(major, minor) {
  if (!/^\d{1,10}$/u.test(major) || !/^\d{1,10}$/u.test(minor)
    || Number(major) > 4294967295 || Number(minor) > 4294967295) return null;
  return Number(major) + ':' + Number(minor);
}

function rows(text) {
  // Kernel tables end each record with LF. A cut-off final row is not a baseline.
  if (text === null || text !== '' && !text.endsWith('\n') || !/^[\t\n\x20-\x7e]*$/u.test(text)) return null;
  const result = [];
  for (const line of text.split('\n')) {
    if (Buffer.byteLength(line) > MAX_ROW_BYTES) return null;
    if (line.trim() === '') continue;
    const tokens = line.trim().split(/[ \t]+/u);
    if (result.length === MAX_ROWS || tokens.length > MAX_TOKENS) return null;
    result.push(tokens);
  }
  return result;
}

function groupTable(text) {
  const input = rows(text);
  if (input === null) return null;
  const result = new Map();
  for (const tokens of input) {
    const pair = /^(\d{1,10}):(\d{1,10})$/u.exec(tokens[0]);
    const id = pair ? key(pair[1], pair[2]) : null;
    if (id === null || result.has(id)) return null;
    const value = {}, seen = new Set(); let valid = true;
    for (const token of tokens.slice(1)) {
      const separator = token.indexOf('=');
      if (separator < 1) { valid = false; continue; }
      const name = token.slice(0, separator);
      if (!Object.hasOwn(fields, name)) continue;
      const number = integer(token.slice(separator + 1));
      if (seen.has(name) || number === null) valid = false;
      seen.add(name); value[fields[name]] = number;
    }
    result.set(id, valid && seen.size === groupFields.length ? value : null);
  }
  return result;
}

function deviceTable(text) {
  const input = rows(text);
  if (input === null) return null;
  const result = new Map();
  for (const tokens of input) {
    const id = key(tokens[0], tokens[1]);
    if (id === null || result.has(id)) return null;
    // Linux diskstats: 3 prefix fields plus 11, 15 (discard), or 17 (flush) stats.
    // Retain the name privately only to reject an observed replacement at the same key.
    const numbers = tokens.slice(3).map(integer);
    result.set(id, [14, 18, 20].includes(tokens.length) && numbers.every(value => value !== null)
      ? { name: tokens[2], readOps: numbers[0], readSectors: numbers[2], writeOps: numbers[4],
        writeSectors: numbers[6], inFlight: numbers[8], ioMs: numbers[9], weightedMs: numbers[10] } : null);
  }
  return result;
}

function delta(before, after, names) {
  if (!before || !after) return null;
  const result = {};
  for (const name of names) {
    if (after[name] < before[name]) return null;
    const value = after[name] - before[name];
    if (!Number.isSafeInteger(value) || value < 0) return null;
    result[name] = value;
  }
  return result;
}

function deviceDelta(before, after) {
  if (!before || !after || before.name !== after.name) return null;
  const value = delta(before, after, deviceFields);
  if (!value) return null;
  const readBytes = value.readSectors * 512, writeBytes = value.writeSectors * 512;
  if (!Number.isSafeInteger(readBytes) || !Number.isSafeInteger(writeBytes)) return null;
  return { readBytes, writeBytes, readOps: value.readOps, writeOps: value.writeOps,
    ioMs: value.ioMs, weightedMs: value.weightedMs, inFlight: after.inFlight };
}

function summary(before, after) {
  const same = before?.root !== null && before?.root === after.root;
  const elapsed = before?.started !== null && after.started !== null ? after.started - before?.started : NaN;
  const spanMs = Number.isFinite(elapsed) && elapsed > 0 && elapsed <= 86400000 ? Math.round(elapsed * 1000) / 1000 : null;
  const result = { coverage: 'unavailable', scope: same ? after.root === '/sys/fs/cgroup' ? 'visible-root' : 'visible-nested' : 'unknown',
    devices: null, paired: null, selected: null, spanMs, cgroup: null, device: null };
  if (!same || spanMs === null || !before.group || !after.group) return result;
  const ids = new Set([...before.group.keys(), ...after.group.keys()]);
  if (ids.size > MAX_ROWS) return result;
  result.devices = ids.size; result.paired = 0;
  let selected = null, anyChange = false;
  for (const id of [...ids].sort()) {
    const value = delta(before.group.get(id), after.group.get(id), groupFields);
    if (!value) continue;
    result.paired++;
    if (groupFields.some(name => value[name] > 0)) anyChange = true;
    if (value.readBytes > (result.cgroup?.readBytes ?? 0)) {
      selected = id; result.cgroup = value; result.selected = 'largest-read-delta';
    }
  }
  if (selected !== null) result.device = deviceDelta(before.device?.get(selected), after.device?.get(selected));
  const complete = result.paired === result.devices && before.device !== null && after.device !== null
    && [...before.device.values(), ...after.device.values()].every(value => value !== null)
    && (selected === null || result.device !== null);
  result.coverage = !complete ? 'partial' : anyChange ? 'complete' : 'no-activity';
  return result;
}

/** Private baseline/maps; only anonymous numeric deltas and fixed enums can leave this closure. */
export function createBrowserIoSampler({ now = () => performance.now() } = {}) {
  let baseline = null, launched = false, completed = false;
  const capture = (root, read) => {
    const started = clock(now);
    // Reuse the resource sampler's validated conventional cgroup-v2 mount only.
    if (typeof root !== 'string' || root.length > 1038 || !/^\/sys\/fs\/cgroup(?:\/[A-Za-z0-9_.:-]+)*$/u.test(root)
      || root.split('/').some(part => part === '.' || part === '..')) return { root: null, started, group: null, device: null };
    return { root, started, group: groupTable(safeDiagnosticRead(read, root + '/io.stat', MAX_BYTES)),
      device: deviceTable(safeDiagnosticRead(read, '/proc/diskstats', MAX_BYTES)) };
  };
  return {
    sample(phase, root, read = readBoundedDiagnosticFile) {
      if (completed || !['launch', 'failure'].includes(phase)) return null;
      if (phase === 'launch') {
        if (!launched) { launched = true; baseline = capture(root, read); }
        return null;
      }
      completed = true;
      try { return summary(baseline, capture(root, read)); }
      finally { baseline = null; }
    },
    clear() { baseline = null; completed = true; },
  };
}
