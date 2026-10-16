import { createStartupIoObserver } from './grafana-browser-startup-io.mjs';

export const startupIoMarker = 'GRAFANA_BROWSER_STARTUP_IO=';
const unavailable = reason => ({ status: 'unavailable', reason });
const reject = () => { throw new Error('DIAGNOSTIC_SHAPE'); };
const nullable = check => value => value === null ? null : check(value);
const integer = value => Number.isSafeInteger(value) && value >= 0 ? value : reject();
const ms = value => typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 86400000 ? value : reject();
const bool = value => typeof value === 'boolean' ? value : reject();
const choice = (...values) => value => values.includes(value) ? value : reject();
const n = nullable(integer), time = nullable(ms);
const tuple = check => value => Array.isArray(value) && value.length === 2 ? value.map(check) : reject();
const shape = (required, optional = {}) => value => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return reject();
  const result = {};
  for (const [key, check] of Object.entries(required)) result[key] = check(value[key]);
  for (const [key, check] of Object.entries(optional)) if (Object.hasOwn(value, key)) result[key] = check(value[key]);
  return result;
};
const numbers = keys => shape(Object.fromEntries(keys.map(key => [key, n])));
const pressure = nullable(shape({}, { someUs: integer, fullUs: integer }));
const pressures = shape({ cpu: pressure, memory: pressure, io: pressure });
const resource = nullable(shape({
  owned: shape({ state: nullable(choice('R', 'S', 'D', 'Z', 'T', 't', 'W', 'X', 'x', 'K', 'P', 'I')),
    ...Object.fromEntries(['rssKiB', 'threads', 'userCpuTicks', 'systemCpuTicks', 'schedulerCpuNs', 'schedulerWaitNs', 'schedulerSlices'].map(key => [key, n])) }),
  host: nullable(pressures), cgroup: nullable(shape({ pressure: pressures,
    cpu: nullable(shape({}, { usage_usec: n, nr_throttled: n, throttled_usec: n })),
    memoryEvents: nullable(shape({}, { high: n, oom: n, oom_kill: n })),
    memoryCurrentBytes: n, memoryMaxBytes: value => value === 'max' ? value : n(value),
    pidsCurrent: n, pidsMax: value => value === 'max' ? value : n(value) })),
}));
const native = nullable(shape({ executable: choice('chrome', 'chromium', 'shell', 'other', 'unavailable'),
  profileFs: nullable(shape({ kind: choice('ext', 'tmpfs', 'overlay', 'xfs', 'btrfs', 'nfs', 'other', 'unavailable'),
    scope: choice('profile', 'parent', 'unavailable'), profileState: choice('present', 'missing', 'denied', 'unavailable'), availableBytes: n, freeInodes: n })),
  minorFaults: n, majorFaults: n, readBytes: n, writeBytes: n, delayacct: choice('enabled', 'disabled', 'unavailable'),
  threads: shape({ coverage: choice('complete', 'partial', 'unavailable'), scanned: integer, statReads: integer,
    states: numbers(['R', 'S', 'D', 'other', 'unknown']), waits: numbers(['ioOrPageWait', 'futex', 'poll', 'pipe', 'socket', 'other', 'unknown']),
    pipeNames: n, delayKnown: integer, ioDelayTicks: n },
  { ioWaits: numbers(['schedule', 'pageBit', 'writeback', 'queue']) }),
}));
const ioWindow = nullable(value => {
  if (value?.coverage === 'unavailable' && Object.keys(value).length === 1) return { coverage: 'unavailable' };
  return shape({ coverage: choice('complete', 'partial', 'unavailable', 'no-activity'),
    scope: choice('visible-root', 'visible-nested', 'unknown'), devices: n, paired: n,
    selected: nullable(choice('largest-read-delta')), spanMs: time,
    cgroup: nullable(numbers(['readBytes', 'writeBytes', 'readOps', 'writeOps'])),
    device: nullable(shape(Object.fromEntries(['readBytes', 'writeBytes', 'readOps', 'writeOps', 'ioMs', 'weightedMs', 'inFlight'].map(key => [key, n])),
      { readMs: n, writeMs: n })),
  })(value);
});
const original = shape({ schema: choice(1),
  milestonesMs: shape(Object.fromEntries(['launch', 'launchReturned', 'spawned', 'firstWriteQueued', 'firstWriteCallback',
    'firstStderr', 'firstProtocol', 'handshake', 'failure', 'exit', 'cleanupFinished'].map(key => [key, time]))),
  firstWriteCallbackFailed: nullable(bool), browserVersion: nullable(value => typeof value === 'string' && value.length <= 64
    && /^(?:HeadlessChrome|Chrome)\/\d{1,5}(?:\.\d{1,5}){3}$/u.test(value) ? value : reject()),
  failureKind: nullable(choice('timeout', 'exit', 'spawn', 'write', 'pipe-end', 'pipe-error', 'stderr-error', 'protocol', 'protocol-limit', 'other')),
  timeoutElapsedMs: time, timeoutDriftMs: time, eventLoop: nullable(shape({ activeMs: time, idleMs: time })),
  resourceSampleDurationMs: shape({ launch: time, failure: time }), native, nativeSampleMs: time,
  nativeMode: nullable(choice('failure', 'probe')), pipeMarkerValidation: choice('unknown', 'verified'),
  resources: shape({ launch: resource, failure: resource }),
  exit: shape({ observed: bool, code: nullable(value => integer(value) <= 255 ? value : reject()),
    signal: nullable(choice('SIGHUP', 'SIGINT', 'SIGQUIT', 'SIGILL', 'SIGTRAP', 'SIGABRT', 'SIGBUS', 'SIGFPE', 'SIGKILL',
      'SIGUSR1', 'SIGSEGV', 'SIGUSR2', 'SIGPIPE', 'SIGALRM', 'SIGTERM', 'SIGSYS')) }),
  cleanup: shape({ term: choice('not-attempted', 'attempted', 'sent', 'missing', 'error'),
    kill: choice('not-attempted', 'attempted', 'sent', 'missing', 'error'), exited: nullable(bool), failed: bool }),
}, { ioWindow, omitted: choice('native-unserializable', 'shared-resources', 'native-and-shared-resources'), ioOmitted: choice('size', 'serialization'),
  detailsOmitted: choice('size', 'serialization', 'capture') });
const handshake = shape({ atMs: time, sampleMs: time, nativeSampling: choice('not-requested'), resources: resource, ioWindow },
  { detailsOmitted: choice('size', 'serialization', 'capture') });
const gauges = shape({ dirtyBytes: tuple(n), writebackBytes: tuple(n) });
const companion = shape({ schema: choice(1), end: choice('handshake', 'failure', 'unavailable'), spanMs: time, leadMs: time,
  sampleMs: tuple(time), services: shape({ expected: choice(2), paired: choice(0, 1, 2),
    coverage: choice('complete', 'partial', 'unavailable'), readBytes: n, writeBytes: n }), host: gauges, cgroup: gauges });

// Copy only retained keys: an optional detail's getter/cycle must not erase old evidence.
function withoutStartupDetails(value, bundle = true) {
  const record = input => input !== null && typeof input === 'object'
    && [Object.prototype, null].includes(Object.getPrototypeOf(input)) && !Object.hasOwn(input, 'toJSON');
  if (!record(value)) return value;
  const omit = (source, keys) => Object.fromEntries(Object.keys(source).filter(key => !keys.includes(key)).map(key => [key, source[key]]));
  let result = value;
  const replace = (key, replacement) => { if (result === value) result = omit(value, ['detailsOmitted']); result[key] = replacement; };
  const device = record(value.ioWindow) ? value.ioWindow.device : null, threads = record(value.native) ? value.native.threads : null;
  if (record(device) && ['readMs', 'writeMs'].some(key => Object.hasOwn(device, key)))
    replace('ioWindow', { ...value.ioWindow, device: omit(device, ['readMs', 'writeMs']) });
  if (record(threads) && Object.hasOwn(threads, 'ioWaits'))
    replace('native', { ...value.native, threads: omit(threads, ['ioWaits']) });
  // The final bundle has two independently bounded records with optional details.
  for (const key of bundle ? ['diagnostic', 'handshake'] : []) if (Object.hasOwn(value, key)) {
    const stripped = withoutStartupDetails(value[key], false);
    if (stripped !== value[key]) replace(key, stripped);
  }
  return result === value && Object.hasOwn(value, 'detailsOmitted') ? omit(value, ['detailsOmitted']) : result;
}

export function startupDetailFallback(value, limit) {
  let text;
  try { text = JSON.stringify(value); } catch {}
  if (typeof text === 'string' && Buffer.byteLength(text) <= limit) return { value, omitted: null };
  try { const stripped = withoutStartupDetails(value);
    return { value: stripped, omitted: stripped === value ? null : typeof text === 'string' ? 'size' : 'serialization' };
  } catch { return { value, omitted: null }; }
}
export function markStartupDetailsOmitted(value, reason, limit) {
  if (reason !== null) try {
    const annotated = { ...value, detailsOmitted: reason };
    if (Buffer.byteLength(JSON.stringify(annotated)) <= limit) return annotated;
  } catch {}
  return value;
}

/** A reporting bound, not a sampler/deadline bound. Always returns detached JSON data. */
export function boundStartupRecord(value, limit) {
  const fallback = startupDetailFallback(value, limit);
  try { const text = JSON.stringify(markStartupDetailsOmitted(fallback.value, fallback.omitted, limit));
    return typeof text !== 'string' ? unavailable('serialization')
      : Buffer.byteLength(text) > limit ? unavailable('size') : JSON.parse(text);
  } catch { return unavailable('serialization'); }
}
function normalized(value, check, limit, missing) {
  if (value === null || value === undefined) return unavailable(missing);
  const bounded = boundStartupRecord(value, limit);
  if (bounded?.status === 'unavailable' && ['capture', 'size', 'serialization', 'not-observed', 'not-requested'].includes(bounded.reason)) return unavailable(bounded.reason);
  try { return boundStartupRecord(check(bounded), limit); } catch {
    try { const stripped = withoutStartupDetails(bounded);
      if (stripped !== bounded) return boundStartupRecord(markStartupDetailsOmitted(check(stripped), 'capture', limit), limit);
    } catch {}
    return unavailable('capture');
  }
}
export const startupCompanion = value => normalized(value, companion, 512, 'capture');
export const startupHandshake = value => normalized(value, handshake, 2048, 'not-observed');
export function finalizedStartupEvidence(diagnostic, endpoint, io) {
  let result = { schema: 1, diagnostic: normalized(diagnostic, original, 3000, 'capture'),
    handshake: startupHandshake(endpoint), io: startupCompanion(io) };
  if (Buffer.byteLength(JSON.stringify(result)) > 6000) {
    const fallback = startupDetailFallback(result, 6000);
    result = markStartupDetailsOmitted(fallback.value, fallback.omitted, 6000);
  }
  if (Buffer.byteLength(JSON.stringify(result)) > 6000) {
    result.handshake = unavailable('size'); result.io = unavailable('size');
  }
  return boundStartupRecord(result, 6000);
}

/** Integrated rehearsal only; run before constructing its one browser. */
export function createStartupEvidenceCapture(owned, options) {
  let finalized = null, startupIo;
  try { startupIo = createStartupIoObserver(owned, options); }
  catch { startupIo = { complete: () => unavailable('capture'), clear() {} }; }
  return {
    diagnostics: { startupIo, onFinalized(value) { finalized = boundStartupRecord(value, 6000); } },
    attach(result) { result.startupEvidence = finalized ?? unavailable('capture'); },
    clear() { try { startupIo.clear(); } catch {} },
  };
}
