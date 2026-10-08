import { opendirSync, readlinkSync, statfsSync } from 'node:fs';
import { dirname } from 'node:path';
import { readBoundedDiagnosticFile, safeDiagnosticRead } from './grafana-browser-diagnostic-files.mjs';

const MAX_THREADS = 64;
const integer = value => /^\d{1,16}$/u.test(String(value)) && Number.isSafeInteger(Number(value)) ? Number(value) : null;
const states = new Set(['R', 'S', 'D', 'Z', 'T', 't', 'W', 'X', 'x', 'K', 'P', 'I']);
const attempt = operation => { try { return operation(); } catch { return null; } };

// Linux 6.17 fs/proc/array.c and Documentation/filesystems/proc.rst:
// after the final ") ", field 3 is index 0; minflt/majflt are fields 10/12,
// delayacct_blkio_ticks is field 42. Whole-PID CPU/faults cover its thread group;
// field 42 remains per-task, so task files are needed for the bounded thread sum.
function taskStat(text) {
  if (text === null || !/^\d+ \(/u.test(text)) return null;
  const end = text.lastIndexOf(') '), begin = text.indexOf(' (') + 2;
  if (end < begin || end - begin > 64) return null;
  const fields = text.slice(end + 2).trim().split(/\s+/u);
  if (fields.length < 22 || !states.has(fields[0])) return null;
  return { state: fields[0], pipeName: text.slice(begin, end) === 'DevToolsPipeHan',
    minorFaults: integer(fields[7]), majorFaults: integer(fields[9]), ioDelayTicks: integer(fields[39]) };
}

// Both Chromium 154.0.8037.97 pipe thread names truncate to DevToolsPipeHan
// under Linux PR_SET_NAME. This identifies a combined name marker, never a role.
function tasks(pid, openDirectory) {
  let directory; const ids = []; const seen = new Set(); let coverage = 'complete';
  try {
    directory = openDirectory('/proc/' + pid + '/task', { bufferSize: 1 });
    for (let count = 0; count <= MAX_THREADS; count++) {
      const entry = directory.readSync();
      if (entry === null) return { ids, coverage };
      if (count === MAX_THREADS) return { ids, coverage: 'partial' };
      const name = entry?.name;
      if (typeof name !== 'string' || !/^\d{1,10}$/u.test(name) || Number(name) < 1
        || Number(name) > 2147483647 || seen.has(name)) { coverage = 'partial'; continue; }
      seen.add(name); ids.push(name);
    }
  } catch { coverage = ids.length ? 'partial' : 'unavailable'; }
  finally { if (directory) attempt(() => directory.closeSync()); }
  return { ids, coverage };
}

function waitClass(text) {
  if (text === null || text.trim() === '' || text.trim() === '0') return 'unknown';
  const name = text.trim();
  if (new Set(['io_schedule', 'folio_wait_bit_common', 'wait_on_page_bit_common',
    'folio_wait_writeback', 'wait_on_page_writeback', 'blk_mq_get_tag', 'rq_qos_wait']).has(name)) return 'ioOrPageWait';
  if (new Set(['futex_wait_queue', 'futex_wait_queue_me', 'futex_wait', 'futex_wait_multiple', 'do_futex']).has(name)) return 'futex';
  if (new Set(['do_epoll_wait', 'ep_poll', 'do_poll', 'do_select', 'poll_schedule_timeout']).has(name)) return 'poll';
  if (new Set(['pipe_read', 'pipe_write', 'pipe_wait_readable', 'pipe_wait_writable']).has(name)) return 'pipe';
  if (new Set(['unix_stream_read_generic', 'unix_stream_data_wait', 'skb_wait_for_more_packets', '__skb_wait_for_more_packets']).has(name)) return 'socket';
  return 'other';
}

function executableKind(pid, readLink) {
  const path = attempt(() => readLink('/proc/' + pid + '/exe'));
  if (typeof path !== 'string' || Buffer.byteLength(path) > 4096) return 'unavailable';
  if (path === '/opt/google/chrome/chrome') return 'chrome';
  if (path === '/usr/lib/chromium/chromium' || path === '/usr/lib/chromium-browser/chromium-browser') return 'chromium';
  if (new Set(['/bin/bash', '/usr/bin/bash', '/bin/dash', '/usr/bin/dash', '/bin/sh', '/usr/bin/sh']).has(path)) return 'shell';
  return 'other';
}

function filesystem(profile, statFilesystem) {
  if (typeof profile !== 'string' || !profile.startsWith('/') || profile.includes('\0') || Buffer.byteLength(profile) > 4096
    || profile.split('/').some(part => part === '.' || part === '..')) return null;
  let stat, scope = 'profile', profileState = 'present';
  try { stat = statFilesystem(profile, { bigint: true }); }
  catch (error) {
    profileState = error?.code === 'ENOENT' ? 'missing'
      : error?.code === 'EACCES' || error?.code === 'EPERM' ? 'denied' : 'unavailable';
    if (profileState === 'missing' && dirname(profile) !== '/') {
      scope = 'parent'; stat = attempt(() => statFilesystem(dirname(profile), { bigint: true }));
    }
  }
  if (!stat) return { kind: 'unavailable', scope: 'unavailable', profileState, availableBytes: null, freeInodes: null };
  const kinds = new Map([[0xef53n, 'ext'], [0x1021994n, 'tmpfs'], [0x794c7630n, 'overlay'],
    [0x58465342n, 'xfs'], [0x9123683en, 'btrfs'], [0x6969n, 'nfs']]);
  const number = value => typeof value === 'bigint' && value >= 0n && value <= BigInt(Number.MAX_SAFE_INTEGER) ? Number(value) : null;
  const available = typeof stat.bavail === 'bigint' && typeof stat.bsize === 'bigint' && stat.bavail >= 0n && stat.bsize > 0n
    ? number(stat.bavail * stat.bsize) : null;
  return { kind: kinds.get(stat.type) ?? 'other', scope, profileState, availableBytes: available, freeInodes: number(stat.ffree) };
}

/** Bounded native observation only; no process names, paths, IDs, or raw wait channels leave this function. */
export function browserNativeSnapshot(pid, profile, { read = readBoundedDiagnosticFile,
  openDirectory = opendirSync, readLink = readlinkSync, statFilesystem = statfsSync } = {}) {
  if (!Number.isSafeInteger(pid) || pid < 1 || pid > 2147483647) return null;
  const get = (file, limit) => safeDiagnosticRead(read, file, limit);
  const root = '/proc/' + pid, process = taskStat(get(root + '/stat', 2048));
  const io = get(root + '/io', 1024);
  const ioValue = pattern => { const matches = io === null ? [] : [...io.matchAll(pattern)];
    return matches.length === 1 ? integer(matches[0][1]) : null; };
  const accounting = get('/proc/sys/kernel/task_delayacct', 16)?.trim();
  const delayacct = accounting === '1' ? 'enabled' : accounting === '0' ? 'disabled' : 'unavailable';
  const enumeration = tasks(pid, openDirectory);
  const stateCounts = { R: 0, S: 0, D: 0, other: 0, unknown: 0 };
  const waits = { ioOrPageWait: 0, futex: 0, poll: 0, pipe: 0, socket: 0, other: 0, unknown: 0 };
  let statReads = 0, pipeNames = 0, delayKnown = 0, ioDelayTicks = 0;
  for (const tid of enumeration.ids) {
    const taskRoot = root + '/task/' + tid;
    const stat = taskStat(get(taskRoot + '/stat', 2048));
    if (stat) { statReads++; if (stat.pipeName) pipeNames++;
      stateCounts[Object.hasOwn(stateCounts, stat.state) ? stat.state : 'other']++;
      if (delayacct === 'enabled' && stat.ioDelayTicks !== null) { delayKnown++; ioDelayTicks += stat.ioDelayTicks; }
    } else stateCounts.unknown++;
    waits[waitClass(get(taskRoot + '/wchan', 128))]++;
  }
  const coverage = enumeration.coverage === 'complete' && statReads !== enumeration.ids.length ? 'partial' : enumeration.coverage;
  return { executable: executableKind(pid, readLink), profileFs: filesystem(profile, statFilesystem),
    minorFaults: process?.minorFaults ?? null, majorFaults: process?.majorFaults ?? null,
    readBytes: ioValue(/^read_bytes:\s+(\d+)$/gmu), writeBytes: ioValue(/^write_bytes:\s+(\d+)$/gmu),
    delayacct, threads: { coverage, scanned: enumeration.ids.length, statReads,
      states: stateCounts, waits, pipeNames: statReads ? pipeNames : null,
      delayKnown, ioDelayTicks: delayKnown > 0 && Number.isSafeInteger(ioDelayTicks) ? ioDelayTicks : null } };
}
