import assert from 'node:assert/strict';
import fs from 'node:fs';
import { syncBuiltinESMExports } from 'node:module';
import test from 'node:test';
import { memoizePluginOwnerParser } from '../security/plugin-owner-parser-cache.mjs';
import { parseCheckstylePluginOwners, readCheckstylePluginSourceWitness }
  from '../security/checkstyle-plugin-graph-transition.mjs';

test('pure parser cache keys exact strings and isolates first results and nested cache hits', () => {
  let calls = 0;
  const parse = memoizePluginOwnerParser(raw => { calls++; return JSON.parse(raw); });
  const raw = '{"owners":{"one":["a"]}}';
  const first = parse(raw); first.owners.one.push('first mutation');
  const hit = parse(raw); assert.deepEqual(hit, { owners: { one: ['a'] } });
  hit.owners.one.push('hit mutation');
  assert.deepEqual(parse(raw), { owners: { one: ['a'] } }); assert.equal(calls, 1);
  assert.deepEqual(parse(raw + ' '), { owners: { one: ['a'] } }); assert.equal(calls, 2);
});

test('pure parser cache retains neither parse failures nor non-string inputs', () => {
  let calls = 0;
  const parse = memoizePluginOwnerParser(raw => {
    calls++; assert.equal(typeof raw, 'string'); return JSON.parse(raw);
  });
  for (const raw of ['invalid', undefined, null, {}, Buffer.from('1'), new String('1')]) {
    const before = calls; assert.throws(() => parse(raw)); assert.throws(() => parse(raw));
    assert.equal(calls - before, 2);
  }
});

test('pure parser cache evicts at eight entries and bounds exact UTF-8 key bytes', () => {
  let calls = 0;
  const parse = memoizePluginOwnerParser(raw => { calls++; return JSON.parse(raw); });
  for (let n = 0; n < 8; n++) assert.equal(parse(String(n)), n);
  assert.equal(parse('0'), 0); assert.equal(calls, 8);
  assert.equal(parse('8'), 8); assert.equal(parse('0'), 0); assert.equal(calls, 10);
  const boundary = JSON.stringify('x'.repeat(2 * 1024 * 1024 - 2));
  assert.equal(Buffer.byteLength(boundary), 2 * 1024 * 1024);
  parse(boundary); parse(boundary); assert.equal(calls, 11);
  const oversized = JSON.stringify('é'.repeat(1024 * 1024));
  assert.ok(oversized.length < 2 * 1024 * 1024);
  assert.ok(Buffer.byteLength(oversized) > 2 * 1024 * 1024);
  parse(oversized); parse(oversized); assert.equal(calls, 13);
  assert.equal(parse('3'), 3); parse(boundary); assert.equal(calls, 13, 'oversized inputs must not evict bounded entries');
});

test('real owner parser rejects changed bytes and repeated-owner drift after a cache hit', () => {
  const raw = 'The following plugins have been resolved:\n   demo:plugin:jar:1\n'
    + '      demo:plugin:jar:1\n      demo:dependency:jar:1\n';
  const expected = parseCheckstylePluginOwners(raw);
  const hit = parseCheckstylePluginOwners(raw); hit.owners['demo:plugin:jar:1'].push('forged');
  assert.deepEqual(parseCheckstylePluginOwners(raw), expected);
  assert.throws(() => parseCheckstylePluginOwners(raw + 'forged line\n'), /unrecognized line/);
  assert.throws(() => parseCheckstylePluginOwners(raw + raw.replace('dependency:jar:1', 'dependency:jar:2')),
    /repeated owner drift/);
});

test('default source witness keeps its final same-call fresh read after manifest validation', () => {
  const originalRead = fs.readFileSync; let reads = 0;
  fs.readFileSync = function (path, ...args) {
    const raw = originalRead.call(this, path, ...args);
    if (String(path).endsWith('/docs/operations/checkstyle-plugin-evidence/source-witness.json') && ++reads === 2)
      return typeof raw === 'string' ? raw + '\n' : Buffer.concat([raw, Buffer.from('\n')]);
    return raw;
  };
  syncBuiltinESMExports();
  try {
    assert.throws(() => readCheckstylePluginSourceWitness(), /Checkstyle byte digest mismatch/);
    assert.equal(reads, 2, 'the default call must reread the witness after validating its manifest');
  } finally { fs.readFileSync = originalRead; syncBuiltinESMExports(); }
  assert.equal(readCheckstylePluginSourceWitness().releaseBlocked, true);
});
