import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { closeSync, lstatSync, mkdtempSync, openSync, readFileSync, rmSync, writeSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { performance } from 'node:perf_hooks';
import { fileURLToPath } from 'node:url';
import { archiveDigestBufferBytes, archiveSha256Sync } from '../ops/archive-digest.mjs';

const MiB = 1024 * 1024;
const script = fileURLToPath(import.meta.url);

function measure(mode, file, expected, maximumBytes) {
  assert.ok(['whole-buffer', 'bounded-buffer'].includes(mode));
  assert.equal(typeof global.gc, 'function', 'worker requires --expose-gc');
  global.gc();
  const before = process.memoryUsage(), beforeMaxRssKiB = process.resourceUsage().maxRSS;
  const started = performance.now();
  let digest, wholeBuffer;
  if (mode === 'whole-buffer') {
    // Previous archive-verification strategy, with the same file and digest checks.
    const stat = lstatSync(file);
    assert.ok(stat.isFile() && !stat.isSymbolicLink() && stat.size > 0 && stat.size <= maximumBytes);
    wholeBuffer = readFileSync(file);
    digest = createHash('sha256').update(wholeBuffer).digest('hex');
  } else {
    digest = archiveSha256Sync(file, { maximumBytes, rejectionMessage: 'BENCHMARK_ARCHIVE_REJECTED' });
  }
  assert.equal(digest, expected);
  const hashMs = performance.now() - started;
  const after = process.memoryUsage(), maxRssKiB = process.resourceUsage().maxRSS;
  // Keep the baseline allocation live through the sample; maxRSS also records its high-water mark.
  if (wholeBuffer) assert.equal(wholeBuffer.length, maximumBytes);
  return { mode, bytes: maximumBytes, digest, hashMs, beforeMaxRssKiB, maxRssKiB,
    maxRssIncreaseKiB: maxRssKiB - beforeMaxRssKiB, rssIncreaseBytes: after.rss - before.rss,
    arrayBuffersIncreaseBytes: after.arrayBuffers - before.arrayBuffers,
    externalIncreaseBytes: after.external - before.external };
}

function makeFixture(file, bytes) {
  // Actual writes, not truncate-created sparse files, and no archive-sized allocation in the parent.
  const buffer = Buffer.alloc(MiB);
  for (let index = 0; index < buffer.length; index += 1) buffer[index] = index % 251;
  const hash = createHash('sha256'), fd = openSync(file, 'wx', 0o600);
  try {
    let position = 0;
    while (position < bytes) {
      const length = Math.min(buffer.length, bytes - position);
      let offset = 0;
      while (offset < length) {
        const written = writeSync(fd, buffer, offset, length - offset, position + offset);
        assert.ok(Number.isSafeInteger(written) && written > 0 && written <= length - offset);
        offset += written;
      }
      hash.update(buffer.subarray(0, length)); position += length;
    }
    return hash.digest('hex');
  } finally { closeSync(fd); }
}

function runBenchmark(args) {
  let sizesMiB = [120, 512], trials = 3;
  assert.equal(args.length % 2, 0, 'usage: [--sizes-mib 120,512] [--trials 3]');
  for (let index = 0; index < args.length; index += 2) {
    if (args[index] === '--sizes-mib') sizesMiB = args[index + 1].split(',').map(Number);
    else if (args[index] === '--trials') trials = Number(args[index + 1]);
    else throw new Error('unknown argument: ' + args[index]);
  }
  assert.ok(sizesMiB.length > 0 && sizesMiB.every(size => Number.isInteger(size) && size > 0 && size <= 512));
  assert.ok(Number.isInteger(trials) && trials > 0 && trials <= 10);
  const directory = mkdtempSync(resolve(tmpdir(), 'archive-digest-benchmark-')), samples = [];
  try {
    for (const size of sizesMiB) {
      const bytes = size * MiB, file = resolve(directory, 'archive.bin'), expected = makeFixture(file, bytes);
      for (let trial = 0; trial < trials; trial += 1) {
        // Fresh sequential process per sample; alternate order to reduce cache/order bias.
        const modes = trial % 2 === 0 ? ['whole-buffer', 'bounded-buffer'] : ['bounded-buffer', 'whole-buffer'];
        for (const mode of modes) {
          const child = spawnSync(process.execPath, ['--expose-gc', script, '--worker', mode, file, expected, String(bytes)],
            { encoding: 'utf8', timeout: 120000, maxBuffer: MiB,
              env: { PATH: process.env.PATH, LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8' } });
          assert.ok(!child.error && child.status === 0, child.error?.message || child.stderr);
          const sample = JSON.parse(child.stdout);
          assert.equal(sample.digest, expected); assert.equal(sample.bytes, bytes); assert.equal(sample.mode, mode);
          samples.push({ trial: trial + 1, ...sample });
        }
      }
      rmSync(file);
    }
    const median = values => { const sorted = [...values].sort((a, b) => a - b); const mid = Math.floor(sorted.length / 2);
      return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2; };
    const summary = sizesMiB.map(size => ({ sizeMiB: size, modes: ['whole-buffer', 'bounded-buffer'].map(mode => {
      const selected = samples.filter(sample => sample.mode === mode && sample.bytes === size * MiB);
      return { mode, medianHashMs: median(selected.map(sample => sample.hashMs)),
        medianMaxRssKiB: median(selected.map(sample => sample.maxRssKiB)),
        medianMaxRssIncreaseKiB: median(selected.map(sample => sample.maxRssIncreaseKiB)),
        medianArrayBuffersIncreaseBytes: median(selected.map(sample => sample.arrayBuffersIncreaseBytes)) };
    }) }));
    return { benchmark: 'archive-digest-memory', node: process.version, platform: process.platform, arch: process.arch,
      bufferBytes: archiveDigestBufferBytes, trials, sizesMiB, summary, samples,
      scope: 'Synthetic nonsparse files; isolated hash memory only. No browser, extraction, download or CI acceptance measurement.' };
  } finally { rmSync(directory, { recursive: true, force: true }); }
}

if (process.argv[2] === '--worker') {
  assert.equal(process.argv.length, 7);
  console.log(JSON.stringify(measure(process.argv[3], process.argv[4], process.argv[5], Number(process.argv[6]))));
} else {
  console.log(JSON.stringify(runBenchmark(process.argv.slice(2)), null, 2));
}
