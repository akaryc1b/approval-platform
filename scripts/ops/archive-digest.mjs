import { createHash } from 'node:crypto';
import * as fs from 'node:fs';

export const archiveDigestBufferBytes = 1024 * 1024;

/** Synchronous SHA-256 with one bounded buffer. The optional I/O seam is for fault tests. */
export function archiveSha256Sync(file, { maximumBytes, rejectionMessage,
  rejectionError = message => new Error(message) }, io = fs) {
  const checked = condition => { if (!condition) throw rejectionError(rejectionMessage); };
  checked(Number.isSafeInteger(maximumBytes) && maximumBytes > 0);
  const acceptable = stat => stat.isFile() && !stat.isSymbolicLink()
    && Number.isSafeInteger(stat.size) && stat.size > 0 && stat.size <= maximumBytes;
  const sameFile = (left, right) => acceptable(right) && left.dev === right.dev && left.ino === right.ino
    && left.size === right.size && left.mtimeMs === right.mtimeMs && left.ctimeMs === right.ctimeMs;
  const before = io.lstatSync(file);
  checked(acceptable(before));
  // NOFOLLOW closes the final-component symlink race; NONBLOCK avoids waiting on a substituted FIFO.
  const fd = io.openSync(file, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW | fs.constants.O_NONBLOCK);
  try {
    const opened = io.fstatSync(fd);
    checked(sameFile(before, opened));
    const buffer = Buffer.allocUnsafe(Math.min(archiveDigestBufferBytes, opened.size));
    const hash = createHash('sha256');
    let position = 0;
    // Bound reads to the initial size, even if a producer continues appending. Short reads must advance.
    while (position < opened.size) {
      const length = Math.min(buffer.length, opened.size - position);
      const bytes = io.readSync(fd, buffer, 0, length, position);
      checked(Number.isSafeInteger(bytes) && bytes > 0 && bytes <= length);
      hash.update(buffer.subarray(0, bytes));
      position += bytes;
    }
    // One-byte EOF probe and metadata checks reject growth, truncation, and observed replacement/writes.
    checked(io.readSync(fd, buffer, 0, 1, position) === 0);
    checked(sameFile(opened, io.fstatSync(fd)));
    checked(sameFile(opened, io.lstatSync(file)));
    return hash.digest('hex');
  } finally {
    io.closeSync(fd);
  }
}
