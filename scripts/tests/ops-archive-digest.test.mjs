import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import * as fs from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import test from 'node:test';
import { archiveDigestBufferBytes, archiveSha256Sync } from '../ops/archive-digest.mjs';
import { verifyArchiveDigest } from '../ops/verify-prometheus-rules.mjs';
import { verifyGrafanaArchive } from '../ops/verify-grafana-browser.mjs';

const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const options = { maximumBytes: 512 * 1024 * 1024, rejectionMessage: 'ARCHIVE_REJECTED' };
function fixture(t, bytes = Buffer.from('bounded archive digest fixture')) {
  const directory = fs.mkdtempSync(resolve(tmpdir(), 'archive-digest-unit-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const file = resolve(directory, 'archive');
  fs.writeFileSync(file, bytes);
  return { directory, file, bytes };
}
function observedIo(overrides = {}) {
  const opened = [], closed = [], reads = [], flags = [];
  const io = { ...fs, ...overrides,
    openSync(...args) {
      flags.push(args[1]);
      const fd = (overrides.openSync || fs.openSync)(...args); opened.push(fd); return fd;
    },
    readSync(fd, buffer, offset, length, position) {
      reads.push({ buffer, offset, length, position });
      return (overrides.readSync || fs.readSync)(fd, buffer, offset, length, position);
    },
    closeSync(fd) { closed.push(fd); return (overrides.closeSync || fs.closeSync)(fd); },
  };
  return { io, opened, closed, reads, flags };
}
function assertClosed(observed) {
  assert.equal(observed.opened.length, 1);
  assert.deepEqual(observed.closed, observed.opened);
  assert.throws(() => fs.fstatSync(observed.opened[0]), { code: 'EBADF' });
}

test('archive hashing is synchronous, hashes a partial final chunk, and reuses one bounded buffer', t => {
  const bytes = Buffer.alloc(2 * archiveDigestBufferBytes + 19, 0xa5); bytes[bytes.length - 1] = 0x3c;
  const { file } = fixture(t, bytes), observed = observedIo();
  assert.equal(archiveSha256Sync(file, options, observed.io), sha(bytes));
  assert.deepEqual(observed.reads.map(read => read.length), [archiveDigestBufferBytes, archiveDigestBufferBytes, 19, 1]);
  assert.deepEqual(observed.reads.map(read => read.position), [0, archiveDigestBufferBytes, 2 * archiveDigestBufferBytes, bytes.length]);
  assert.equal(new Set(observed.reads.map(read => read.buffer)).size, 1);
  assert.ok(observed.reads.every(read => read.offset === 0 && read.buffer.length === archiveDigestBufferBytes));
  assert.equal(observed.flags[0], fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW | fs.constants.O_NONBLOCK);
  assertClosed(observed);
  assert.equal(verifyArchiveDigest(file, sha(bytes)), undefined);
  fs.writeFileSync(file, Buffer.concat([bytes.subarray(0, -1), Buffer.from([0x3d])]));
  assert.throws(() => verifyArchiveDigest(file, sha(bytes)), /PROMTOOL_ARCHIVE_DIGEST_MISMATCH/u);
});

test('positive short reads advance by actual bytes and never hash stale buffer tails', t => {
  const { file, bytes } = fixture(t), observed = observedIo({
    readSync: (fd, buffer, offset, length, position) => fs.readSync(fd, buffer, offset, Math.min(length, 7), position),
  });
  assert.equal(archiveSha256Sync(file, options, observed.io), sha(bytes));
  assert.deepEqual(observed.reads.map(read => read.position), [0, 7, 14, 21, 28, bytes.length]);
  assert.ok(observed.reads.every(read => read.buffer.length === bytes.length));
  assertClosed(observed);
});

test('exact size limit is accepted; empty, oversized, linked, missing and non-files fail before open', t => {
  const { directory, file, bytes } = fixture(t), observed = observedIo();
  assert.equal(archiveSha256Sync(file, { ...options, maximumBytes: bytes.length }, observed.io), sha(bytes));
  assertClosed(observed);
  const link = resolve(directory, 'link'); fs.symlinkSync(file, link);
  for (const [path, maximumBytes] of [[file, bytes.length - 1], [link, 1024], [directory, 1024],
    [resolve(directory, 'missing'), 1024]]) {
    const rejected = observedIo();
    assert.throws(() => archiveSha256Sync(path, { ...options, maximumBytes }, rejected.io));
    assert.deepEqual(rejected.opened, []); assert.deepEqual(rejected.reads, []);
  }
  fs.truncateSync(file, 0);
  assert.throws(() => archiveSha256Sync(file, options), /ARCHIVE_REJECTED/u);
});

test('both archive callers retain their own upper bounds, digest requirements and empty-file checks', t => {
  const { file } = fixture(t);
  for (const [verify, maximumBytes, rejected] of [
    [path => verifyArchiveDigest(path, '0'.repeat(64)), 120 * 1024 * 1024, /PROMTOOL_ARCHIVE_REJECTED/u],
    [verifyGrafanaArchive, 512 * 1024 * 1024, /GRAFANA_BROWSER_ARCHIVE_FILE/u],
  ]) {
    fs.truncateSync(file, maximumBytes + 1); assert.throws(() => verify(file), rejected);
    fs.truncateSync(file, 0); assert.throws(() => verify(file), rejected);
    fs.writeFileSync(file, 'wrong digest'); assert.throws(() => verify(file), /DIGEST/u);
  }
  assert.throws(() => verifyArchiveDigest(file, 'A'.repeat(64)), /PROMTOOL_DIGEST_REQUIRED/u);
});

for (const kind of ['empty', 'directory', 'symlink', 'oversized']) {
  test(`${kind} archive preserves each caller's existing rejection error type and code`, t => {
    const { directory, file } = fixture(t); let path = file;
    if (kind === 'empty') fs.truncateSync(file, 0);
    if (kind === 'directory') path = directory;
    if (kind === 'symlink') { path = resolve(directory, 'link'); fs.symlinkSync(file, path); }
    if (kind === 'oversized') fs.truncateSync(file, 512 * 1024 * 1024 + 1);
    assert.throws(() => verifyGrafanaArchive(path), error => {
      assert.equal(error.constructor, assert.AssertionError); assert.equal(error.code, 'ERR_ASSERTION');
      assert.equal(error.message, 'GRAFANA_BROWSER_ARCHIVE_FILE'); assert.equal(error.operator, '==');
      assert.equal(error.actual, false); assert.equal(error.expected, true); return true;
    });
    assert.throws(() => verifyArchiveDigest(path, '0'.repeat(64)), error => {
      assert.equal(error.constructor, Error); assert.equal(error.code, undefined);
      assert.equal(error.message, 'PROMTOOL_ARCHIVE_REJECTED'); return true;
    });
  });
}

test('missing archives preserve genuine filesystem errors in both callers', t => {
  const { directory } = fixture(t), path = resolve(directory, 'missing');
  for (const verify of [verifyGrafanaArchive, file => verifyArchiveDigest(file, '0'.repeat(64))]) {
    assert.throws(() => verify(path), error => {
      assert.equal(error.constructor, Error); assert.equal(error.code, 'ENOENT');
      assert.equal(error.syscall, 'lstat'); assert.equal(error.path, path); return true;
    });
  }
});

test('digest mismatches retain Grafana assertion errors and Prometheus plain errors', t => {
  const { file } = fixture(t);
  assert.throws(() => verifyGrafanaArchive(file), error => {
    assert.equal(error.constructor, assert.AssertionError); assert.equal(error.code, 'ERR_ASSERTION');
    assert.match(error.message, /GRAFANA_BROWSER_ARCHIVE_DIGEST/u); return true;
  });
  assert.throws(() => verifyArchiveDigest(file, '0'.repeat(64)), error => {
    assert.equal(error.constructor, Error); assert.equal(error.code, undefined);
    assert.equal(error.message, 'PROMTOOL_ARCHIVE_DIGEST_MISMATCH'); return true;
  });
});

test('a custom rejection error does not wrap genuine read failures', t => {
  const { file } = fixture(t), failure = Object.assign(new Error('controlled I/O failure'), { code: 'EIO' });
  const observed = observedIo({ readSync() { throw failure; } });
  assert.throws(() => archiveSha256Sync(file, { ...options,
    rejectionError() { assert.fail('filesystem errors must bypass the rejection factory'); },
  }, observed.io), error => error === failure);
  assertClosed(observed);
});

test('invalid limits and unsafe file sizes cannot allocate a buffer or start an unbounded loop', t => {
  const { file } = fixture(t);
  for (const maximumBytes of [0, -1, 1.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) {
    const observed = observedIo({ lstatSync() { assert.fail('invalid configuration must fail before I/O'); } });
    assert.throws(() => archiveSha256Sync(file, { ...options, maximumBytes }, observed.io), /ARCHIVE_REJECTED/u);
    assert.deepEqual(observed.opened, []);
  }
  for (const size of [-1, 1.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) {
    const observed = observedIo({ lstatSync: () => Object.assign(fs.lstatSync(file), { size }) });
    assert.throws(() => archiveSha256Sync(file, options, observed.io), /ARCHIVE_REJECTED/u);
    assert.deepEqual(observed.opened, []);
  }
});

for (const result of [0, -1, 1.5, NaN, Infinity, undefined, '1', 'too-many']) {
  test(`invalid/non-progress read ${String(result)} fails immediately and closes the descriptor`, t => {
    const { file } = fixture(t), observed = observedIo({
      readSync: (_fd, _buffer, _offset, length) => result === 'too-many' ? length + 1 : result,
    });
    assert.throws(() => archiveSha256Sync(file, options, observed.io), /ARCHIVE_REJECTED/u);
    assert.equal(observed.reads.length, 1); assertClosed(observed);
  });
}

for (const failAt of [1, 2, 4]) {
  test(`read failure at call ${failAt}, including EOF, propagates without retry and closes the descriptor`, t => {
    const { file } = fixture(t, Buffer.from('abc')); const failure = new Error('controlled read failure'); let calls = 0;
    const observed = observedIo({ readSync(fd, buffer, offset, length, position) {
      if (++calls === failAt) throw failure;
      return fs.readSync(fd, buffer, offset, Math.min(length, 1), position);
    } });
    assert.throws(() => archiveSha256Sync(file, options, observed.io), error => error === failure);
    assert.equal(calls, failAt); assertClosed(observed);
  });
}

test('real truncation during short reads fails closed at premature EOF', t => {
  const { file } = fixture(t, Buffer.from('abcdef')); let calls = 0;
  const observed = observedIo({ readSync(...args) {
    if (++calls === 2) fs.truncateSync(file, 2);
    return fs.readSync(args[0], args[1], args[2], Math.min(args[3], 2), args[4]);
  } });
  assert.throws(() => archiveSha256Sync(file, options, observed.io), /ARCHIVE_REJECTED/u);
  assert.equal(calls, 2); assertClosed(observed);
});

test('continuous growth is rejected after at most initial-size bytes plus one EOF probe', t => {
  const { file, bytes } = fixture(t, Buffer.from('abcdef'));
  const observed = observedIo({ readSync(fd, buffer, offset, length, position) {
    fs.appendFileSync(file, 'x');
    return fs.readSync(fd, buffer, offset, Math.min(length, 2), position);
  } });
  assert.throws(() => archiveSha256Sync(file, { ...options, maximumBytes: bytes.length }, observed.io), /ARCHIVE_REJECTED/u);
  assert.deepEqual(observed.reads.map(read => read.position), [0, 2, 4, 6]);
  assert.equal(observed.reads.at(-1).length, 1); assertClosed(observed);
});

for (const change of ['grow', 'truncate', 'rewrite']) {
  test(`final descriptor check detects ${change} after a successful EOF probe`, t => {
    const { file, bytes } = fixture(t);
    const observed = observedIo({ readSync(...args) {
      const result = fs.readSync(...args);
      if (result === 0) {
        if (change === 'grow') fs.appendFileSync(file, 'x');
        if (change === 'truncate') fs.truncateSync(file, bytes.length - 1);
        if (change === 'rewrite') { fs.writeFileSync(file, Buffer.alloc(bytes.length, 1)); fs.utimesSync(file, 1, 1); }
      }
      return result;
    } });
    assert.throws(() => archiveSha256Sync(file, options, observed.io), /ARCHIVE_REJECTED/u); assertClosed(observed);
  });
}

for (const change of ['identity', 'size', 'kind']) {
  test(`descriptor ${change} change between lstat and open is rejected before reading`, t => {
    const { file } = fixture(t);
    const observed = observedIo({ fstatSync(fd) {
      const stat = fs.fstatSync(fd);
      if (change === 'identity') stat.ino += 1;
      if (change === 'size') stat.size += 1;
      if (change === 'kind') stat.isFile = () => false;
      return stat;
    } });
    assert.throws(() => archiveSha256Sync(file, options, observed.io), /ARCHIVE_REJECTED/u);
    assert.deepEqual(observed.reads, []); assertClosed(observed);
  });
}

test('a final-component symlink swapped in before open is rejected by the kernel', { skip: process.platform !== 'linux' }, t => {
  const { file } = fixture(t);
  const observed = observedIo({ openSync(...args) {
    fs.renameSync(file, file + '.original'); fs.symlinkSync(file + '.original', file);
    return fs.openSync(...args);
  } });
  assert.throws(() => archiveSha256Sync(file, options, observed.io), { code: 'ELOOP' });
  assert.deepEqual(observed.opened, []); assert.deepEqual(observed.closed, []);
});

test('a path replaced with a symlink after reading is rejected and the original descriptor is closed', t => {
  const { file } = fixture(t); let calls = 0;
  const observed = observedIo({ lstatSync(path) {
    if (++calls === 2) { fs.renameSync(file, file + '.original'); fs.symlinkSync(file + '.original', file); }
    return fs.lstatSync(path);
  } });
  assert.throws(() => archiveSha256Sync(file, options, observed.io), /ARCHIVE_REJECTED/u); assertClosed(observed);
});

for (const operation of ['open', 'first-fstat', 'final-fstat', 'final-lstat', 'close']) {
  test(`${operation} failure propagates and every successfully opened descriptor is closed once`, t => {
    const { file } = fixture(t); const failure = new Error('controlled ' + operation + ' failure'); let stats = 0, lstats = 0;
    const observed = observedIo({
      openSync(...args) { if (operation === 'open') throw failure; return fs.openSync(...args); },
      fstatSync(fd) {
        stats += 1;
        if (operation === 'first-fstat' && stats === 1 || operation === 'final-fstat' && stats === 2) throw failure;
        return fs.fstatSync(fd);
      },
      lstatSync(path) { if (++lstats === 2 && operation === 'final-lstat') throw failure; return fs.lstatSync(path); },
      closeSync(fd) { fs.closeSync(fd); if (operation === 'close') throw failure; },
    });
    assert.throws(() => archiveSha256Sync(file, options, observed.io), error => error === failure);
    if (operation === 'open') { assert.deepEqual(observed.opened, []); assert.deepEqual(observed.closed, []); }
    else assertClosed(observed);
  });
}
