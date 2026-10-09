import {
  closeSync, constants, fstatSync, fsyncSync, lstatSync,
  openSync, readSync, renameSync, unlinkSync, writeSync,
} from 'node:fs';
import { randomUUID } from 'node:crypto';

// All envelope producers share this lock. The workflow's initial root install
// redirect has finished before these producers run. A crash may leave a lock or
// staging file, which blocks a later publication instead of risking lost bytes.
export function appendPublicationEnvelope(path, envelope) {
  const lock = `${path}.publication.lock`;
  const staged = `${path}.publication-${randomUUID()}.tmp`;
  let lockFd;
  let inputFd;
  let outputFd;
  let stagedExists = false;
  try {
    lockFd = openSync(lock, 'wx', 0o600);
    const before = lstatSync(path, { bigint: true });
    if (!before.isFile() || before.isSymbolicLink()) throw new Error();
    inputFd = openSync(path, constants.O_RDONLY | constants.O_NOFOLLOW);
    const opened = fstatSync(inputFd, { bigint: true });
    const identity = stat => [stat.dev, stat.ino, stat.size, stat.mtimeNs, stat.ctimeNs].join(':');
    if (identity(before) !== identity(opened)) throw new Error();
    outputFd = openSync(staged, 'wx', 0o600);
    stagedExists = true;
    const writeAll = bytes => {
      let offset = 0;
      while (offset < bytes.length) {
        const written = writeSync(outputFd, bytes, offset, bytes.length - offset);
        if (!Number.isSafeInteger(written) || written <= 0) throw new Error();
        offset += written;
      }
    };
    const buffer = Buffer.alloc(1024 * 1024);
    if (opened.size > BigInt(Number.MAX_SAFE_INTEGER)) throw new Error();
    let remaining = Number(opened.size);
    while (remaining > 0) {
      const count = readSync(inputFd, buffer, 0, Math.min(buffer.length, remaining), null);
      if (!Number.isSafeInteger(count) || count <= 0) throw new Error();
      writeAll(buffer.subarray(0, count));
      remaining -= count;
    }
    writeAll(Buffer.from(envelope));
    fsyncSync(outputFd);
    if (identity(opened) !== identity(fstatSync(inputFd, { bigint: true }))
        || identity(opened) !== identity(lstatSync(path, { bigint: true }))) throw new Error();
    closeSync(outputFd);
    outputFd = undefined;
    closeSync(inputFd);
    inputFd = undefined;
    renameSync(staged, path);
    stagedExists = false;
  } catch {
    throw new Error('EVIDENCE_PUBLICATION_REJECTED: publication transaction failed');
  } finally {
    // Best-effort cleanup must never replace the constant public diagnostic
    // with a filesystem exception that includes an input-controlled path.
    for (const fd of [inputFd, outputFd, lockFd]) {
      if (fd !== undefined) { try { closeSync(fd); } catch { /* retain safe error */ } }
    }
    if (stagedExists) { try { unlinkSync(staged); } catch { /* private staged file */ } }
    if (lockFd !== undefined) { try { unlinkSync(lock); } catch { /* fail closed next time */ } }
  }
}
