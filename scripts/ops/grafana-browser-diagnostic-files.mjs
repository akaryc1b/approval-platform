import { closeSync, constants, openSync, readSync } from 'node:fs';

/** Read at most limit + 1 bytes; oversized or unavailable kernel files remain unknown. */
export function readBoundedDiagnosticFile(file, limit) {
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > 8192) return null;
  let fd;
  try {
    fd = openSync(file, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
    const bytes = Buffer.alloc(limit + 1); let used = 0;
    while (used < bytes.length) {
      const count = readSync(fd, bytes, used, bytes.length - used, null);
      if (count === 0) break;
      used += count;
    }
    return used <= limit ? bytes.subarray(0, used).toString('utf8') : null;
  } catch { return null; } finally { if (fd !== undefined) try { closeSync(fd); } catch {} }
}

export function safeDiagnosticRead(read, file, limit) {
  try { const text = read(file, limit);
    return typeof text === 'string' && Buffer.byteLength(text) <= limit ? text : null;
  } catch { return null; }
}

/** Private conventional cgroup-v2 location; callers must never report this path. */
export function diagnosticCgroupRoot(membership) {
  const entries = membership?.trim().split('\n') ?? [];
  const path = entries.length === 1 && entries[0].startsWith('0::/') ? entries[0].slice(3) : null;
  const segments = path?.split('/') ?? [];
  return path !== null && path.length <= 1024 && /^\/[A-Za-z0-9_./:-]*$/u.test(path)
    && !segments.some(part => part === '.' || part === '..')
    ? '/sys/fs/cgroup' + (path === '/' ? '' : path) : null;
}
