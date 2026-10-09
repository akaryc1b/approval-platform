import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { chmodSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, join } from 'node:path';
import test from 'node:test';

import { publicationPolicy } from '../product-readiness/artifact-privacy/publication.mjs';

const publicationUrl = new URL('../product-readiness/artifact-privacy/publication.mjs', import.meta.url).href;
const canary = 'SYNTHETIC_ONLY_SANITIZER_RESPONSE_SECRET_CANARY_42';
const invalidResponse = 'EVIDENCE_PUBLICATION_REJECTED: invalid sanitizer response';
const unsafeEvidence = 'EVIDENCE_PUBLICATION_REJECTED: unsupported or unsafe evidence';

function response(zip = false) {
  const bytes = Buffer.from('synthetic sanitized publication');
  const sanitization = zip ? {
    schemaVersion: 1, policy: publicationPolicy, traceSchemaVersion: 8,
    credentialPlaceholder: '[REDACTED-MOCK-AUTH]',
    changedSources: [{
      path: 'resources/src@synthetic.txt', originalSha256: 'a'.repeat(64), publishedSha256: 'b'.repeat(64),
      transformation: 'credential redaction; published source is not the original source bytes',
    }],
    changedAssertionDiagnostics: [{
      trace: 'synthetic.trace', recordIndex: 3, callId: 'synthetic-call-42', field: 'result.received.ariaSnapshot',
      transformation: 'credential redaction in received DOM diagnostic only',
      assertionPredicateExpectedCriterionAndOutcome: 'unchanged',
    }],
    resourceReferencesRebound: 1, screenshotsAndFonts: 'byte-identical',
    transportSizes: 'original observed sizes; resource hashes bind published bytes',
  } : { policy: publicationPolicy };
  return {
    policy: publicationPolicy,
    files: [{ size: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex'),
      base64: bytes.toString('base64'), sanitization }],
  };
}

function invokeSanitizer({ value = response(), stdout, mode = 'success', zip = false } = {}) {
  const root = mkdtempSync(join(tmpdir(), 'synthetic-sanitizer-response-'));
  try {
    const payload = join(root, 'response.txt');
    writeFileSync(payload, stdout ?? JSON.stringify(value));
    const executable = join(root, 'python3');
    // The shim is visible only to a child Node process. The real publication
    // wrapper still owns spawnSync, response parsing, validation and diagnostics.
    writeFileSync(executable, `#!${process.execPath}\n` + [
      "const fs = require('node:fs');",
      "fs.writeSync(1, fs.readFileSync(process.env.SYNTHETIC_RESPONSE_PATH));",
      `fs.writeSync(2, ${JSON.stringify(canary)});`,
      "if (process.env.SYNTHETIC_RESPONSE_MODE === 'nonzero') process.exit(9);",
      "if (process.env.SYNTHETIC_RESPONSE_MODE === 'signal') process.kill(process.pid, 'SIGTERM');",
    ].join('\n'));
    chmodSync(executable, 0o700);
    const script = [
      `import { preparePublicationFiles, publicFailureDetail } from ${JSON.stringify(publicationUrl)};`,
      'try {',
      `  const files = preparePublicationFiles([${JSON.stringify(join(root, zip ? 'synthetic.zip' : 'synthetic.json'))}]);`,
      "  process.stdout.write(JSON.stringify({ rejected: false, files }));",
      '} catch (error) {',
      '  process.stdout.write(JSON.stringify({ rejected: true, message: error.message, detail: publicFailureDetail(error) }));',
      '}',
    ].join('\n');
    const result = spawnSync(process.execPath, ['--input-type=module', '-e', script], {
      encoding: 'utf8', shell: false, timeout: 10_000, maxBuffer: 1024 * 1024,
      env: { ...process.env, PATH: `${root}${delimiter}${process.env.PATH || ''}`,
        SYNTHETIC_RESPONSE_PATH: payload, SYNTHETIC_RESPONSE_MODE: mode },
    });
    assert.equal(result.error, undefined, 'publication child process did not finish');
    assert.equal(result.status, 0, 'publication child process failed');
    assert.equal(`${result.stdout}${result.stderr}`.includes(canary), false,
      'untrusted sanitizer output reached a diagnostic or returned artifact');
    assert.equal(result.stderr, '', 'captured sanitizer stderr must not escape');
    return JSON.parse(result.stdout);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

function reject(options, message = invalidResponse) {
  assert.deepEqual(invokeSanitizer(options), {
    rejected: true, message, detail: 'EVIDENCE_PUBLICATION_REJECTED',
  });
}

for (const zip of [false, true]) {
  test(`publication accepts a valid response with ${zip ? 'trace' : 'ordinary'} provenance`, () => {
    const value = response(zip);
    assert.deepEqual(invokeSanitizer({ value, zip }), { rejected: false, files: value.files });
  });
}

for (const mode of ['nonzero', 'signal']) {
  test(`publication rejects sanitizer ${mode} termination without echoing its output`, () => {
    reject({ mode, stdout: canary }, unsafeEvidence);
  });
}

test('publication rejects malformed sanitizer JSON without parser excerpts', () => {
  reject({ stdout: `{"password":"${canary}` });
});

const invalidResponses = [
  ['null response', () => null],
  ['missing policy', value => { delete value.policy; }],
  ['wrong policy', value => { value.policy = 'synthetic-unsupported-policy'; }],
  ['unauthorized response field', value => { value.password = canary; }],
  ['missing files', value => { delete value.files; }],
  ['non-array files', value => { value.files = {}; }],
  ['too few files', value => { value.files = []; }],
  ['too many files', value => { value.files.push({ ...value.files[0] }); }],
  ['null file', value => { value.files[0] = null; }],
  ['unauthorized file field', value => { value.files[0].password = canary; }],
  ['non-string base64', value => { value.files[0].base64 = 42; }],
  ['noncanonical base64', value => { value.files[0].base64 += '\n'; }],
  ['invalid base64', value => { value.files[0].base64 = '!synthetic-invalid!'; }],
  ['missing hash', value => { delete value.files[0].sha256; }],
  ['malformed hash', value => { value.files[0].sha256 = 'G'.repeat(64); }],
  ['mismatched hash', value => { value.files[0].sha256 = '0'.repeat(64); }],
  ['missing size', value => { delete value.files[0].size; }],
  ['negative size', value => { value.files[0].size = -1; }],
  ['fractional size', value => { value.files[0].size = 1.5; }],
  ['unsafe integer size', value => { value.files[0].size = Number.MAX_SAFE_INTEGER + 1; }],
  ['oversized file', value => { value.files[0].size = 64 * 1024 * 1024 + 1; }],
  ['mismatched size', value => { value.files[0].size += 1; }],
  ['absent provenance', value => { delete value.files[0].sanitization; }],
  ['null provenance', value => { value.files[0].sanitization = null; }],
  ['array provenance', value => { value.files[0].sanitization = []; }],
  ['wrong provenance policy', value => { value.files[0].sanitization.policy = 'synthetic-unsupported-policy'; }],
  ['unauthorized provenance field', value => { value.files[0].sanitization.password = canary; }],
];
for (const [label, mutate] of invalidResponses) {
  test(`publication rejects ${label} with one stable diagnostic`, () => {
    const value = response();
    reject({ value: mutate(value) === null ? null : value });
  });
}

const invalidTraceProvenance = [
  ['ordinary-file provenance', provenance => { for (const key of Object.keys(provenance)) if (key !== 'policy') delete provenance[key]; }],
  ['missing required field', provenance => { delete provenance.changedSources; }],
  ['unexpected top-level field', provenance => { provenance.password = canary; }],
  ['unsupported trace schema', provenance => { provenance.traceSchemaVersion = 99; }],
  ['changed placeholder', provenance => { provenance.credentialPlaceholder = canary; }],
  ['nonidentical screenshot claim', provenance => { provenance.screenshotsAndFonts = 'rewritten'; }],
  ['negative resource count', provenance => { provenance.resourceReferencesRebound = -1; }],
  ['source metadata injection', provenance => { provenance.changedSources[0].password = canary; }],
  ['source traversal', provenance => { provenance.changedSources[0].path = 'resources/src@synthetic/../../secret.txt'; }],
  ['malformed source hash', provenance => { provenance.changedSources[0].originalSha256 = canary; }],
  ['source transformation injection', provenance => { provenance.changedSources[0].transformation = canary; }],
  ['assertion metadata injection', provenance => { provenance.changedAssertionDiagnostics[0].password = canary; }],
  ['assertion path traversal', provenance => { provenance.changedAssertionDiagnostics[0].trace = '../synthetic.trace'; }],
  ['negative assertion index', provenance => { provenance.changedAssertionDiagnostics[0].recordIndex = -1; }],
  ['assertion criterion mutation', provenance => { provenance.changedAssertionDiagnostics[0].assertionPredicateExpectedCriterionAndOutcome = 'changed'; }],
  ['unexpected assertion field', provenance => { provenance.changedAssertionDiagnostics[0].field = 'expectedText'; }],
];
for (const [label, mutate] of invalidTraceProvenance) {
  test(`publication rejects trace provenance ${label}`, () => {
    const value = response(true);
    mutate(value.files[0].sanitization);
    reject({ value, zip: true });
  });
}
