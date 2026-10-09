import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import * as fs from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import vm from 'node:vm';

import {
  appendPublicationEnvelope,
  publicFailureDetail,
} from '../product-readiness/artifact-privacy/publication.mjs';

const source = fs.readFileSync(new URL('../product-readiness/artifact-privacy/transaction.mjs', import.meta.url), 'utf8');
const prefix = 'SYNTHETIC_SAFE_PREVIOUS_LOG\n';
const envelope = '\nSYNTHETIC_ENVELOPE_BEGIN\n{"status":"PASSED"}\nSYNTHETIC_ENVELOPE_END\n';
const canary = 'SYNTHETIC_ONLY_FILESYSTEM_ERROR_PASSWORD_CANARY_42';
const rejected = 'EVIDENCE_PUBLICATION_REJECTED: publication transaction failed';

function fixture(t) {
  const root = fs.mkdtempSync(join(tmpdir(), 'synthetic-publication-transaction-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const log = join(root, 'root-install.log');
  fs.writeFileSync(log, prefix);
  return { root, log };
}

function transactionWith(overrides) {
  // Keep the production implementation intact, replacing only its imported
  // dependencies so real filesystem faults can occur at precise boundaries.
  const executable = source.replace(/^import[\s\S]*?;\r?\n/gmu, '')
    .replace(/^export /gmu, '');
  const context = vm.createContext({ ...fs, randomUUID, Buffer, ...overrides });
  vm.runInContext(`${executable}\nglobalThis.append = appendPublicationEnvelope;`, context, { timeout: 1_000 });
  return context.append;
}

function assertRejected(append, log) {
  assert.throws(() => append(log, envelope), error => {
    assert.equal(String(error).includes(canary), false, 'filesystem diagnostic exposed a synthetic secret');
    assert.equal(error.message, rejected);
    assert.equal(publicFailureDetail(error), 'EVIDENCE_PUBLICATION_REJECTED');
    return true;
  });
}

function assertPreserved({ root, log }, expected = prefix) {
  assert.equal(fs.readFileSync(log, 'utf8'), expected);
  assert.deepEqual(fs.readdirSync(root), ['root-install.log'], 'transaction must clean up its own staging file and lock');
}

test('public transaction atomically replaces the log and preserves consecutive complete envelopes', t => {
  const state = fixture(t);
  const previousInode = fs.statSync(state.log).ino;
  appendPublicationEnvelope(state.log, envelope);
  assertPreserved(state, prefix + envelope);
  assert.notEqual(fs.statSync(state.log).ino, previousInode, 'publication must replace the staged inode');
  appendPublicationEnvelope(state.log, envelope);
  assertPreserved(state, prefix + envelope + envelope);
});

test('public transaction preserves an original log spanning multiple copy buffers', t => {
  const state = fixture(t);
  const original = Buffer.alloc(2 * 1024 * 1024 + 73, 's');
  original.write(prefix);
  fs.writeFileSync(state.log, original);
  appendPublicationEnvelope(state.log, envelope);
  const published = fs.readFileSync(state.log);
  assert.equal(published.length, original.length + Buffer.byteLength(envelope));
  assert.equal(published.subarray(0, original.length).equals(original), true);
  assert.equal(published.subarray(original.length).toString(), envelope);
  assert.deepEqual(fs.readdirSync(state.root), ['root-install.log']);
});

test('short filesystem writes are retried until the complete envelope is staged', t => {
  const state = fixture(t);
  let writes = 0;
  const append = transactionWith({
    writeSync(fd, bytes, offset, length) {
      writes += 1;
      return fs.writeSync(fd, bytes, offset, Math.min(3, length));
    },
  });
  append(state.log, envelope);
  assert.ok(writes > 2, 'fixture must exercise partial-write retries');
  assertPreserved(state, prefix + envelope);
});

for (const failDuring of ['prefix', 'envelope']) {
  test(`partial ${failDuring} write followed by ENOSPC leaves the published log unchanged`, t => {
    const state = fixture(t);
    let writes = 0;
    const partialAt = failDuring === 'prefix' ? 1 : 2;
    const append = transactionWith({
      writeSync(fd, bytes, offset, length) {
        writes += 1;
        if (writes > partialAt) throw Object.assign(new Error(canary), { code: 'ENOSPC' });
        return fs.writeSync(fd, bytes, offset, writes === partialAt ? Math.min(5, length) : length);
      },
    });
    assertRejected(append, state.log);
    assert.equal(writes, partialAt + 1, 'fault must follow a successful partial write');
    assertPreserved(state);
  });
}

for (const result of [0, -1, 0.5, NaN]) {
  test(`invalid write result ${String(result)} fails closed without a partial append`, t => {
    const state = fixture(t);
    assertRejected(transactionWith({ writeSync: () => result }), state.log);
    assertPreserved(state);
  });
}

for (const operation of ['readSync', 'fsyncSync', 'renameSync']) {
  test(`${operation} failure preserves the previous log and hides filesystem details`, t => {
    const state = fixture(t);
    let faultReached = false;
    const append = transactionWith({
      [operation]() {
        faultReached = true;
        throw new Error(canary);
      },
    });
    assertRejected(append, state.log);
    assert.equal(faultReached, true);
    assertPreserved(state);
  });
}

test('staging creation failure releases only this transaction lock', t => {
  const state = fixture(t);
  let faultReached = false;
  const append = transactionWith({
    openSync(path, ...args) {
      if (path.endsWith('.tmp')) {
        faultReached = true;
        throw new Error(canary);
      }
      return fs.openSync(path, ...args);
    },
  });
  assertRejected(append, state.log);
  assert.equal(faultReached, true);
  assertPreserved(state);
});

test('an existing publication lock is preserved and prevents lost updates', t => {
  const state = fixture(t);
  const lock = `${state.log}.publication.lock`;
  fs.writeFileSync(lock, 'SYNTHETIC_OTHER_PUBLISHER');
  assertRejected(appendPublicationEnvelope, state.log);
  assert.equal(fs.readFileSync(state.log, 'utf8'), prefix);
  assert.equal(fs.readFileSync(lock, 'utf8'), 'SYNTHETIC_OTHER_PUBLISHER');
  assert.deepEqual(fs.readdirSync(state.root).sort(), ['root-install.log', 'root-install.log.publication.lock']);
});

test('a symlink log is rejected without touching its destination', t => {
  const state = fixture(t);
  const target = join(state.root, 'synthetic-target.log');
  fs.renameSync(state.log, target);
  fs.symlinkSync(target, state.log);
  assertRejected(appendPublicationEnvelope, state.log);
  assert.equal(fs.lstatSync(state.log).isSymbolicLink(), true);
  assert.equal(fs.readFileSync(target, 'utf8'), prefix);
  assert.deepEqual(fs.readdirSync(state.root).sort(), ['root-install.log', 'synthetic-target.log']);
});

test('a missing original log is rejected without creating an empty publication', t => {
  const state = fixture(t);
  fs.unlinkSync(state.log);
  assertRejected(appendPublicationEnvelope, state.log);
  assert.deepEqual(fs.readdirSync(state.root), []);
});

for (const mutation of ['append', 'replace']) {
  test(`concurrent ${mutation} of the original log is detected before replacement`, t => {
    const state = fixture(t);
    const otherPublication = 'SYNTHETIC_CONCURRENT_PUBLISHER\n';
    const append = transactionWith({
      fsyncSync(fd) {
        fs.fsyncSync(fd);
        if (mutation === 'append') fs.appendFileSync(state.log, otherPublication);
        else {
          const otherLog = join(state.root, 'other-staged.log');
          fs.writeFileSync(otherLog, otherPublication);
          fs.renameSync(otherLog, state.log);
        }
      },
    });
    assertRejected(append, state.log);
    assertPreserved(state, mutation === 'append' ? prefix + otherPublication : otherPublication);
  });
}

test('cleanup errors cannot replace the constant transaction rejection or publish staged bytes', t => {
  const state = fixture(t);
  let cleanupFailures = 0;
  const append = transactionWith({
    renameSync() { throw new Error(canary); },
    unlinkSync() { cleanupFailures += 1; throw new Error(canary); },
  });
  assertRejected(append, state.log);
  assert.equal(fs.readFileSync(state.log, 'utf8'), prefix);
  assert.ok(cleanupFailures > 0, 'fixture must exercise failed cleanup');
  assert.equal(fs.existsSync(`${state.log}.publication.lock`), true, 'failed lock cleanup must block future publication');
  assertRejected(appendPublicationEnvelope, state.log);
  assert.equal(fs.readFileSync(state.log, 'utf8'), prefix);
});
