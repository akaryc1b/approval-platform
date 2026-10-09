import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import * as fs from 'node:fs';
import { tmpdir } from 'node:os';
import * as path from 'node:path';
import test from 'node:test';
import { fileURLToPath, pathToFileURL } from 'node:url';
import vm from 'node:vm';
import { deflateSync } from 'node:zlib';

import * as publication from '../product-readiness/artifact-privacy/publication.mjs';

const sourceRoot = fileURLToPath(new URL('../product-readiness/', import.meta.url));
const canary = 'SYNTHETIC_ONLY_PRODUCER_PASSWORD_CANARY_42';
const safePrefix = 'SYNTHETIC_SAFE_PREVIOUS_LOG\n';
const identity = {
  commitSha: 'a'.repeat(40), treeSha: 'b'.repeat(40),
  checkedOutSha: 'a'.repeat(40), checkedOutTreeSha: 'b'.repeat(40),
  exactHeadSha: 'a'.repeat(40), exactHeadTreeSha: 'b'.repeat(40),
  sourceTreeMatchesExactHead: true,
};
const producers = [
  ['quick-start/evidence.mjs', 'collectEvidence', 'appendCiEvidenceEnvelope'],
  ['browser-accessibility/evidence.mjs', 'collectEvidence', 'appendCiEvidenceEnvelope'],
  ['pc-h5-runtime-smoke.mjs', 'collectEvidenceFiles', 'appendCiEvidenceEnvelope', 'private'],
  ['purchase-payment-e2e/evidence.mjs', 'collectCiEvidence', 'appendCiEvidenceEnvelope'],
  ['capacity-recovery/evidence.mjs', 'collectEvidence', 'appendCiEvidenceEnvelope'],
  ['capacity-recovery/profile-matrix-evidence.mjs', 'collectEvidence', 'appendProfileMatrixEnvelope'],
  ['capacity-recovery/backlog-drain-evidence.mjs', 'collectEvidence', 'appendEvidenceEnvelope'],
  ['capacity-recovery/upgrade-restore.mjs', 'collectEvidence', 'appendEvidenceEnvelope', 'private'],
];

function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1) {
      crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0);
    }
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function pngChunk(name, bytes) {
  const payload = Buffer.concat([Buffer.from(name), bytes]);
  const size = Buffer.alloc(4);
  size.writeUInt32BE(bytes.length);
  const checksum = Buffer.alloc(4);
  checksum.writeUInt32BE(crc32(payload));
  return Buffer.concat([size, payload, checksum]);
}

// A complete RGB PNG with valid framing, zlib stream, and per-chunk CRCs.
const pngHeader = Buffer.alloc(13);
pngHeader.writeUInt32BE(1, 0);
pngHeader.writeUInt32BE(1, 4);
pngHeader[8] = 8;
pngHeader[9] = 2;
const png = Buffer.concat([
  Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
  pngChunk('IHDR', pngHeader),
  pngChunk('IDAT', deflateSync(Buffer.from([0, 255, 255, 255]))),
  pngChunk('IEND', Buffer.alloc(0)),
]);

function traceZip() {
  const name = Buffer.from('synthetic.trace');
  const payload = Buffer.from(`${JSON.stringify({
    type: 'context-options', version: 8, origin: 'library',
    playwrightVersion: '1.60.0', contextId: 'synthetic-context',
    browserName: 'chromium', options: {}, wallTime: 1, monotonicTime: 1,
  })}\n`);
  // A single stored ZIP member avoids fixture libraries and external commands.
  const local = Buffer.alloc(30);
  local.writeUInt32LE(0x04034b50, 0);
  local.writeUInt16LE(20, 4);
  local.writeUInt32LE(crc32(payload), 14);
  local.writeUInt32LE(payload.length, 18);
  local.writeUInt32LE(payload.length, 22);
  local.writeUInt16LE(name.length, 26);
  const central = Buffer.alloc(46);
  central.writeUInt32LE(0x02014b50, 0);
  central.writeUInt16LE(20, 4);
  central.writeUInt16LE(20, 6);
  central.writeUInt32LE(crc32(payload), 16);
  central.writeUInt32LE(payload.length, 20);
  central.writeUInt32LE(payload.length, 24);
  central.writeUInt16LE(name.length, 28);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(1, 8);
  end.writeUInt16LE(1, 10);
  end.writeUInt32LE(central.length + name.length, 12);
  end.writeUInt32LE(local.length + name.length + payload.length, 16);
  return Buffer.concat([local, name, payload, central, name, end]);
}

function functionSource(source, name) {
  const declaration = new RegExp(`^(?:export )?function ${name}\\(`, 'mu');
  const start = source.search(declaration);
  assert.notEqual(start, -1, `missing producer function ${name}`);
  const tail = source.slice(start);
  const next = tail.slice(1).search(/^\s*(?:export )?(?:async )?function /mu);
  return (next < 0 ? tail : tail.slice(0, next + 1)).replace(/^export /u, '');
}

async function loadProducer(spec, root, output) {
  const [file, collector, append, visibility] = spec;
  const source = fs.readFileSync(path.join(sourceRoot, file), 'utf8');
  if (visibility !== 'private') {
    // Preserve the original module graph and public API. repositoryRoot is
    // derived from import.meta.url, so the real append writes only inside root.
    const module = await import(pathToFileURL(path.join(root, 'scripts/product-readiness', file)));
    return { publish: status => module[append](status, output, identity), fixtureSource: module[append].toString() };
  }
  // Launchers have no public publication entry point and importing them runs
  // their CLI. Evaluate the actual collector/append bodies unchanged instead.
  const constants = [...source.matchAll(
    /^const (?:envelopeBegin|envelopeEnd|evidenceEnvelopeBegin|evidenceEnvelopeEnd|retainedExtensions|retainedEvidenceExtensions|maximum\w*(?:Bytes|Size)|requiredPassFiles) = [\s\S]*?;/gmu,
  )].map(match => match[0]).join('\n');
  const code = [constants, functionSource(source, collector),
    file === 'pc-h5-runtime-smoke.mjs' ? functionSource(source, 'evidenceRelativePath') : '',
    functionSource(source, append), `globalThis.publish = ${append};`].join('\n');
  const context = vm.createContext({
    ...fs, ...path, ...publication, Buffer, createHash,
    repositoryRoot: root, outputDirectory: output,
    process: { env: { GITHUB_ACTIONS: 'true', GITHUB_RUN_ID: 'synthetic-producer-run' } },
  });
  vm.runInContext(code, context, { timeout: 1_000 });
  return {
    publish: status => file === 'pc-h5-runtime-smoke.mjs'
      ? context.publish(status, identity) : context.publish(status, output, identity),
    fixtureSource: code,
  };
}

function writeFixtures(output, fixtureSource) {
  // Populate the declared required-file contract; this suite tests publication,
  // while the producer boundary suites separately test acceptance requirements.
  for (const match of fixtureSource.matchAll(/['"]([\w/-]+\.(?:json|md|png))['"]/gu)) {
    const target = path.join(output, match[1]);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, target.endsWith('.png') ? png
      : target.endsWith('.json') ? '{}' : 'Synthetic safe evidence\n');
  }
  for (const name of ['first', 'second', 'third']) {
    const directory = path.join(output, 'playwright', name);
    fs.mkdirSync(directory, { recursive: true });
    fs.writeFileSync(path.join(directory, 'trace.zip'), traceZip());
  }
  fs.mkdirSync(path.join(output, 'nested'), { recursive: true });
  fs.writeFileSync(path.join(output, 'nested', 'extra-evidence.json'), JSON.stringify({
    password: canary, businessKey: 'synthetic-business-37', actorId: 'synthetic-actor-37', status: 'FAILED',
  }));
  fs.writeFileSync(path.join(output, 'nested', 'diagnostic.md'), `password: ${canary}\n`);
  fs.writeFileSync(path.join(output, 'ignored.log'), canary);
}

for (const spec of producers) {
  test(`real ${spec[0]} publication is sanitized, integral, and atomic`, { concurrency: false }, async () => {
    const root = fs.mkdtempSync(path.join(tmpdir(), 'synthetic-producer-publication-'));
    const previousEnvironment = { GITHUB_ACTIONS: process.env.GITHUB_ACTIONS, GITHUB_RUN_ID: process.env.GITHUB_RUN_ID };
    try {
      fs.cpSync(sourceRoot, path.join(root, 'scripts/product-readiness'), { recursive: true });
      const output = path.join(root, 'evidence');
      const logPath = path.join(root, 'root-install.log');
      fs.mkdirSync(output);
      const { publish, fixtureSource } = await loadProducer(spec, root, output);
      // Only the synchronous append checks use the CI environment. Never leave
      // it changed across an await when imported by the shared hygiene suite.
      process.env.GITHUB_ACTIONS = 'true';
      process.env.GITHUB_RUN_ID = 'synthetic-producer-run';
      writeFixtures(output, fixtureSource);
      for (const status of ['PASSED', 'FAILED']) {
        fs.writeFileSync(logPath, safePrefix);
        const previousInode = fs.statSync(logPath).ino;
        publish(status);
        assert.notEqual(fs.statSync(logPath).ino, previousInode, 'producer must replace the fully staged log atomically');
        const log = fs.readFileSync(logPath, 'utf8');
        assert.equal(log.includes(canary), false, 'raw synthetic secret reached the envelope');
        assert.ok(log.startsWith(safePrefix));
        const lines = log.slice(safePrefix.length).trim().split('\n');
        assert.equal(lines.length, 3, 'append must publish exactly one framed envelope');
        assert.match(lines[0], /_ENVELOPE_BEGIN$/u);
        assert.equal(lines[2], lines[0].replace(/BEGIN$/u, 'END'));
        const envelope = JSON.parse(lines[1]);
        assert.equal(envelope.status, status);
        assert.equal(envelope.commitSha, identity.commitSha);
        assert.equal(envelope.treeSha, identity.treeSha);
        assert.equal(envelope.publicationPolicy, publication.publicationPolicy);
        assert.equal(envelope.totalBytes, envelope.files.reduce((sum, file) => sum + file.size, 0));
        assert.equal(envelope.files.some(file => file.path === 'ignored.log'), false);
        for (const file of envelope.files) {
          const bytes = Buffer.from(file.base64, 'base64');
          assert.equal(bytes.toString('base64'), file.base64);
          assert.equal(bytes.length, file.size);
          assert.equal(createHash('sha256').update(bytes).digest('hex'), file.sha256);
          assert.equal(file.sanitization.policy, publication.publicationPolicy);
          assert.equal(bytes.includes(Buffer.from(canary)), false, 'decoded artifact retained a synthetic secret');
          if (file.path.endsWith('.png')) assert.deepEqual(bytes, png);
        }
        const extra = envelope.files.find(file => file.path === 'nested/extra-evidence.json');
        assert.ok(extra, 'collector must traverse nested evidence');
        assert.deepEqual(JSON.parse(Buffer.from(extra.base64, 'base64')), {
          password: '[REDACTED-MOCK-AUTH]', businessKey: 'synthetic-business-37',
          actorId: 'synthetic-actor-37', status: 'FAILED',
        });
        const markdown = envelope.files.find(file => file.path === 'nested/diagnostic.md');
        assert.ok(markdown, 'collector must retain sanitized Markdown diagnostics');
        assert.match(Buffer.from(markdown.base64, 'base64').toString(), /\[REDACTED-MOCK-AUTH\]/u);
      }
      // A malformed final member must not append even the earlier safe files or
      // disturb the previous complete envelope. Exercise both status branches.
      const priorLog = fs.readFileSync(logPath);
      fs.writeFileSync(path.join(output, 'zzz-malformed.json'), `{"password":"${canary}`);
      for (const status of ['PASSED', 'FAILED']) {
        assert.throws(() => publish(status), error => {
          assert.equal(String(error).includes(canary), false);
          assert.equal(publication.publicFailureDetail(error), 'EVIDENCE_PUBLICATION_REJECTED');
          return true;
        });
        assert.deepEqual(fs.readFileSync(logPath), priorLog);
      }
    } finally {
      for (const [key, value] of Object.entries(previousEnvironment)) {
        if (value === undefined) delete process.env[key];
        else process.env[key] = value;
      }
      fs.rmSync(root, { recursive: true, force: true });
    }
  });
}
