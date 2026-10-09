import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { chmodSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, join } from 'node:path';
import test from 'node:test';

import { publicationPolicy, publicationRejectionCategories } from '../product-readiness/artifact-privacy/publication.mjs';

const publicationUrl = new URL('../product-readiness/artifact-privacy/publication.mjs', import.meta.url).href;
const canary = 'SYNTHETIC_ONLY_SANITIZER_RESPONSE_SECRET_CANARY_42';
const invalidResponse = 'EVIDENCE_PUBLICATION_REJECTED: invalid sanitizer response';
const unsafeEvidence = 'EVIDENCE_PUBLICATION_REJECTED: unsupported or unsafe evidence';
const rejectionLine = category => `EVIDENCE_REJECTION_V1 category=${category}\n`;

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

function invokeSanitizer({ value = response(), stdout, stderr = '', mode = 'success', status = 1,
  zip = false, category } = {}) {
  const root = mkdtempSync(join(tmpdir(), 'synthetic-sanitizer-response-'));
  try {
    const payload = join(root, 'response.txt');
    writeFileSync(payload, stdout ?? JSON.stringify(value));
    const errors = join(root, 'stderr.txt');
    writeFileSync(errors, stderr);
    const executable = join(root, 'python3');
    // The shim is visible only to a child Node process. The real publication
    // wrapper still owns spawnSync, response parsing, validation and diagnostics.
    writeFileSync(executable, `#!${process.execPath}\n` + [
      "const fs = require('node:fs');",
      "fs.writeSync(1, fs.readFileSync(process.env.SYNTHETIC_RESPONSE_PATH));",
      "fs.writeSync(2, fs.readFileSync(process.env.SYNTHETIC_ERROR_PATH));",
      "if (process.env.SYNTHETIC_RESPONSE_MODE === 'nonzero') process.exit(Number(process.env.SYNTHETIC_RESPONSE_STATUS));",
      "if (process.env.SYNTHETIC_RESPONSE_MODE === 'signal') process.kill(process.pid, 'SIGTERM');",
    ].join('\n'));
    chmodSync(executable, 0o700);
    if (mode === 'missing') rmSync(executable);
    const script = [
      "import childProcess from 'node:child_process';",
      "import { syncBuiltinESMExports } from 'node:module';",
      ...(mode === 'throw' ? [
        `childProcess.spawnSync = () => { throw new Error(${JSON.stringify(canary)}); };`,
        'syncBuiltinESMExports();',
      ] : []),
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
      env: { ...process.env, PATH: mode === 'missing' ? root : `${root}${delimiter}${process.env.PATH || ''}`,
        SYNTHETIC_RESPONSE_PATH: payload, SYNTHETIC_ERROR_PATH: errors,
        SYNTHETIC_RESPONSE_MODE: mode, SYNTHETIC_RESPONSE_STATUS: String(status) },
    });
    assert.equal(result.error, undefined, 'publication child process did not finish');
    assert.equal(result.status, 0, 'publication child process failed');
    assert.equal(`${result.stdout}${result.stderr}`.includes(canary), false,
      'untrusted sanitizer output reached a diagnostic or returned artifact');
    assert.equal(result.stderr, category ? rejectionLine(category) : '',
      'only the selected fixed category may reach public stderr');
    return JSON.parse(result.stdout);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

function reject(options, message = invalidResponse, category = 'SANITIZER_RESPONSE_INVALID') {
  assert.deepEqual(invokeSanitizer({ ...options, category }), {
    rejected: true, message, detail: 'EVIDENCE_PUBLICATION_REJECTED',
  });
}

for (const zip of [false, true]) {
  test(`publication accepts a valid response with ${zip ? 'trace' : 'ordinary'} provenance`, () => {
    const value = response(zip);
    assert.deepEqual(invokeSanitizer({ value, zip }), { rejected: false, files: value.files });
  });
}

for (const [mode, category] of [['nonzero', 'SANITIZER_PROTOCOL_INVALID'], ['signal', 'SANITIZER_TERMINATED'],
  ['missing', 'SANITIZER_PROCESS_FAILED'], ['throw', 'SANITIZER_PROCESS_FAILED']]) {
  test(`publication rejects sanitizer ${mode} termination without echoing its output`, () => {
    reject({ mode, stdout: canary, stderr: canary }, unsafeEvidence, category);
  });
}

for (const category of publicationRejectionCategories.filter(value => ![
  'SANITIZER_PROCESS_FAILED', 'SANITIZER_TERMINATED', 'SANITIZER_PROTOCOL_INVALID', 'SANITIZER_RESPONSE_INVALID',
].includes(value))) {
  test(`publication independently emits the trusted ${category} rejection before the exception`, () => {
    reject({ mode: 'nonzero', stdout: '', stderr: rejectionLine(category) }, unsafeEvidence, category);
  });
}

const malformedProtocols = [
  ['missing line', ''],
  ['freeform secret', canary],
  ['input path', `/synthetic/private/${canary}.zip`],
  ['parser exception', `SyntaxError: Unexpected token ${canary}`],
  ['unknown category', rejectionLine('UNKNOWN_CATEGORY')],
  ['wrapper-owned category', rejectionLine('SANITIZER_PROCESS_FAILED')],
  ['wrong version', 'EVIDENCE_REJECTION_V2 category=ARCHIVE_INVALID\n'],
  ['lowercase category', 'EVIDENCE_REJECTION_V1 category=archive_invalid\n'],
  ['missing LF', 'EVIDENCE_REJECTION_V1 category=ARCHIVE_INVALID'],
  ['CRLF', 'EVIDENCE_REJECTION_V1 category=ARCHIVE_INVALID\r\n'],
  ['extra LF', rejectionLine('ARCHIVE_INVALID') + '\n'],
  ['multiple lines', rejectionLine('ARCHIVE_INVALID') + rejectionLine('CONTENT_INVALID')],
  ['leading whitespace', ' ' + rejectionLine('ARCHIVE_INVALID')],
  ['extra field', `EVIDENCE_REJECTION_V1 category=ARCHIVE_INVALID detail=${canary}\n`],
  ['ANSI decoration', '\u001b[31m' + rejectionLine('ARCHIVE_INVALID')],
  ['NUL suffix', rejectionLine('ARCHIVE_INVALID') + '\u0000'],
];
for (const [label, stderr] of malformedProtocols) {
  test(`publication closes malformed sanitizer protocol: ${label}`, () => {
    reject({ mode: 'nonzero', stdout: '', stderr }, unsafeEvidence, 'SANITIZER_PROTOCOL_INVALID');
  });
}

test('publication rejects a trusted category with an unexpected exit status or nonempty stdout', () => {
  for (const options of [{ status: 9, stdout: '' }, { stdout: canary }, { stdout: ' ' }]) {
    reject({ mode: 'nonzero', stderr: rejectionLine('ARCHIVE_INVALID'), ...options },
      unsafeEvidence, 'SANITIZER_PROTOCOL_INVALID');
  }
});

test('publication rejects any stderr on a successful sanitizer status', () => {
  for (const stderr of [canary, rejectionLine('ARCHIVE_INVALID')]) {
    reject({ stderr }, invalidResponse, 'SANITIZER_PROTOCOL_INVALID');
  }
});

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
