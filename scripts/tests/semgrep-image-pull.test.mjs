import assert from 'node:assert/strict';
import test from 'node:test';
import { pullSemgrepImage } from '../security/semgrep-image-pull.mjs';

const image = 'semgrep/semgrep:1.172.0';
const layer = '0123456789ab';
const digest = 'a'.repeat(64);
const progress = `${layer}: Pulling fs layer`;

// Every test injects both dependencies. No Docker, scanner, network or clock wait.
function invoke(result, { thrown, times = [100, 900125.4] } = {}) {
  const env = { TEST_ONLY: 'not forwarded into diagnostics' }, calls = [];
  let value, error, clockCalls = 0;
  try {
    value = pullSemgrepImage(image, { env,
      now: () => times[clockCalls++],
      spawn: (...args) => { calls.push(args); if (thrown) throw thrown; return result; },
    });
  } catch (caught) { error = caught; }
  assert.equal(calls.length, 1);
  assert.equal(clockCalls, 2);
  assert.deepEqual(calls[0], ['docker', ['pull', image], {
    env, encoding: 'utf8', timeout: 900000, maxBuffer: 256 * 1024 * 1024,
  }]);
  return { value, error };
}

function failure(result, options) {
  const { value, error } = invoke(result, options);
  assert.equal(value, undefined);
  assert.ok(error instanceof Error);
  assert.deepEqual(Object.keys(error), ['diagnostics']);
  assert.equal(Object.hasOwn(error, 'cause'), false);
  assert.equal(error.message, `Semgrep image pull failed: ${JSON.stringify(error.diagnostics)}`);
  for (const summary of [error.diagnostics.stdout, error.diagnostics.stderr]) {
    assert.equal(summary.retainedBytes, Buffer.byteLength(JSON.stringify(summary.entries)));
    assert.ok(Buffer.byteLength(JSON.stringify(summary)) <= 4096);
  }
  return error.diagnostics;
}

test('single successful pull returns the original result with unchanged process options', () => {
  const result = { status: 0, signal: null, stdout: `${image}\n`, stderr: '', pid: 123 };
  const outcome = invoke(result);
  assert.equal(outcome.value, result);
  assert.equal(outcome.error, undefined);
});

test('timeout keeps separate safe progress, status, signal and actual elapsed time', () => {
  const stdout = `${progress}\n${layer}: Download complete\n`;
  const stderr = 'Error response from daemon: Get "https://private.example/token": i/o timeout\n';
  const diagnostics = failure({ status: null, signal: 'SIGTERM', stdout, stderr,
    error: Object.assign(new Error('private spawn message'), { code: 'ETIMEDOUT' }) });
  assert.equal(diagnostics.failure, 'TIMEOUT');
  assert.equal(diagnostics.code, 'ETIMEDOUT');
  assert.equal(diagnostics.status, null);
  assert.equal(diagnostics.signal, 'SIGTERM');
  assert.equal(diagnostics.elapsedMs, 900025);
  assert.deepEqual(diagnostics.stdout.entries, [progress, `${layer}: Download complete`]);
  assert.deepEqual(diagnostics.stderr.entries, ['TIMEOUT']);
  assert.equal(diagnostics.stdout.inputBytes, Buffer.byteLength(stdout));
  assert.equal(diagnostics.stderr.inputBytes, Buffer.byteLength(stderr));
  assert.equal(diagnostics.stdout.inputLines, 2);
  assert.doesNotMatch(JSON.stringify(diagnostics), /private|https|token|spawn message/);
});

for (const [label, result, category] of [
  ['nonzero exit', { status: 1 }, 'EXIT_STATUS'],
  ['null status', { status: null }, 'STATUS_UNAVAILABLE'],
  ['missing status', {}, 'STATUS_UNAVAILABLE'],
  ['missing result', undefined, 'STATUS_UNAVAILABLE'],
  ['signal with zero status', { status: 0, signal: 'SIGKILL' }, 'SIGNAL'],
  ['spawn error with zero status', { status: 0, error: { code: 'ENOENT' } }, 'SPAWN_ERROR'],
  ['buffer failure with zero status', { status: 0, error: { code: 'ENOBUFS' } }, 'SPAWN_ERROR'],
]) test(`${label} fails closed even with success-like progress`, () => {
  const diagnostics = failure(result && { ...result, stdout: `Status: Downloaded newer image for ${image}\n` });
  assert.equal(diagnostics.failure, category);
  assert.equal(diagnostics.status, result?.status ?? null);
});

test('thrown spawn errors retain only finite code labels and unavailable streams', () => {
  const thrown = Object.assign(new Error('credential=private-value'), {
    code: 'EACCES', path: '/private/file', stdout: 'private captured output',
  });
  const diagnostics = failure(undefined, { thrown, times: [5, 7] });
  assert.equal(diagnostics.code, 'EACCES');
  assert.equal(diagnostics.elapsedMs, 2);
  assert.equal(diagnostics.stdout.available, false);
  assert.equal(diagnostics.stdout.inputBytes, null);
  assert.equal(diagnostics.stdout.inputLines, null);
  assert.equal(diagnostics.stderr.available, false);
  assert.doesNotMatch(JSON.stringify(diagnostics), /private|credential/);
});

test('untrusted structured error fields cannot become retained strings or unbounded numbers', () => {
  const diagnostics = failure({ status: Number.MAX_VALUE, signal: 'private-signal',
    error: { code: 'private-code', message: 'private-message' }, stdout: { private: true }, stderr: null },
  { times: [0, Infinity] });
  assert.equal(diagnostics.code, 'UNKNOWN');
  assert.equal(diagnostics.signal, 'UNKNOWN');
  assert.equal(diagnostics.status, null);
  assert.equal(diagnostics.elapsedMs, null);
  assert.equal(diagnostics.stdout.available, false);
  assert.doesNotMatch(JSON.stringify(diagnostics), /private/);
  assert.equal(failure({ status: 2 }, { times: [2, 1] }).elapsedMs, null);
});

test('fixed Docker states, public image references and digests are allowlisted', () => {
  const states = ['Pulling fs layer', 'Waiting', 'Verifying Checksum', 'Download complete', 'Pull complete', 'Already exists'];
  const lines = states.map(state => `${layer}: ${state}`).concat([
    `${layer}: Downloading [=>${' '.repeat(48)}]  1.25MB/42.5MB`,
    `${layer}: Extracting [${'='.repeat(50)}]  42.5MB/42.5MB`,
    `${layer}: Downloading`, `${layer}: Extracting`, '1.172.0: Pulling from semgrep/semgrep',
    `Digest: sha256:${digest}`, image, `docker.io/${image}`,
    `semgrep/semgrep@sha256:${digest}`, `docker.io/semgrep/semgrep@sha256:${digest}`,
    `Status: Downloaded newer image for ${image}`, `Status: Image is up to date for docker.io/${image}`,
  ]);
  const summary = failure({ status: 1, stdout: `${lines.join('\n')}\n` }).stdout;
  const expected = [...lines]; expected[6] = `${layer}: Downloading`; expected[7] = `${layer}: Extracting`;
  assert.deepEqual(summary.entries, expected);
  assert.equal(summary.inputLines, lines.length);
  assert.equal(summary.omittedLines, 0);
});

test('known acquisition errors emit constant categories without URL, path or credential suffixes', () => {
  const lines = [
    'Error response from daemon: toomanyrequests: https://private.example/rate?token=secret',
    'Error response from daemon: unauthorized: Bearer private-authorization',
    'Error response from daemon: manifest for private.example/image not found: manifest unknown: secret',
    'failed to register layer: write /private/file: no space left on device',
    'error pulling image configuration: Get "https://private.example": TLS handshake timeout',
    'failed to copy: dial tcp private.example:443: connection refused',
    'Error response from daemon: Get "https://private.example": x509: certificate signed by unknown authority',
    'Cannot connect to the Docker daemon at unix:///private/docker.sock. Is the docker daemon running?',
  ];
  const summary = failure({ status: 1, stderr: `${lines.join('\n')}\n` }).stderr;
  assert.deepEqual(summary.entries, ['RATE_LIMIT', 'ACCESS_DENIED', 'MANIFEST_UNAVAILABLE', 'NO_SPACE',
    'TIMEOUT', 'CONNECTION_ERROR', 'TLS_ERROR', 'DAEMON_UNAVAILABLE']);
  assert.equal(summary.omittedLines, 0);
  assert.doesNotMatch(JSON.stringify(summary), /private|secret|Bearer|https|unix:/);
});

test('LF and CRLF are valid delimiters; embedded carriage returns and ANSI are omitted', () => {
  const stdout = `${progress}\r\n${layer}: Waiting\n${progress}\rprivate\n\x1b[31m${progress}\n${progress}\r\r\n`;
  const summary = failure({ status: 1, stdout }).stdout;
  assert.deepEqual(summary.entries, [progress, `${layer}: Waiting`]);
  assert.equal(summary.inputLines, 5);
  assert.equal(summary.inputBytes, Buffer.byteLength(stdout));
  assert.equal(summary.omittedLines, 3);
  assert.equal(summary.controlLines, 3);
});

test('unknown and adversarial complete lines are omitted without prefix clipping or sanitizing', () => {
  const lines = [
    `${progress} Authorization: Bearer private-value`, `${progress}\t`,
    ` ${progress}`, `${layer.toUpperCase()}: Waiting`, `0123456789abc: Waiting`,
    `Digest: sha256:${digest}suffix`, `Digest: sha256:${digest.slice(1)}`,
    `Status: Downloaded newer image for ${image}?token=secret`,
    `Status: Downloaded newer image for private.example/${image}`, 'semgrep/semgrep:latest',
    `${layer}: Downloading [=> ] 1MB/2MB private-suffix`,
    'Authorization: Bearer private-token', '{"auth":"private-base64"}',
    '/private/file', 'https://private.example/?access_token=secret', 'private unicode 🐱',
    `${progress}\0`, `${progress}\x7f`, `${progress}\x85`, `${progress}\u202e`,
    'Error response from daemon: unauthorized: \u200bprivate', `${image}\u2028`,
  ];
  const summary = failure({ status: 1, stdout: `${lines.join('\n')}\n` }).stdout;
  assert.deepEqual(summary.entries, []);
  assert.equal(summary.inputLines, lines.length);
  assert.equal(summary.inputBytes, Buffer.byteLength(`${lines.join('\n')}\n`));
  assert.equal(summary.omittedLines, lines.length);
  assert.equal(summary.controlLines, 7);
  assert.doesNotMatch(JSON.stringify(summary), /private|secret|Authorization|auth|suffix|🐱/);
});

test('overlong and partial records are omitted while later complete progress survives', () => {
  const oversized = `${progress}${' '.repeat(1024 * 1024)}private-tail`;
  const stdout = `${oversized}\n${'🐱'.repeat(300)}\n${progress}\n${layer}: Pull complete`;
  const summary = failure({ status: null, error: { code: 'ENOBUFS' }, stdout }).stdout;
  assert.deepEqual(summary.entries, [progress]);
  assert.equal(summary.inputBytes, Buffer.byteLength(stdout));
  assert.equal(summary.inputLines, 4);
  assert.equal(summary.oversizedLines, 2);
  assert.equal(summary.incompleteLines, 1);
  assert.equal(summary.omittedLines, 3);
  assert.equal(summary.truncatedLines, 0);
});

test('safe entry byte and line caps count omissions after full-line classification', () => {
  for (const line of [progress, `docker.io/semgrep/semgrep@sha256:${digest}`]) {
    const lines = Array(200).fill(line).concat([`${progress}private-tail`, `${progress}\x1b`, 'private unknown']);
    const stdout = `${lines.join('\n')}\n`, summary = failure({ status: 1, stdout, stderr: stdout }).stdout;
    assert.ok(summary.entries.length <= 64);
    assert.ok(summary.retainedBytes <= 3584);
    assert.equal(summary.inputLines, 203);
    assert.equal(summary.truncatedLines, 200 - summary.entries.length);
    assert.equal(summary.omittedLines, 203 - summary.entries.length);
    assert.equal(summary.controlLines, 1);
    assert.ok(summary.entries.every(entry => entry === line));
    assert.doesNotMatch(JSON.stringify(summary), /private/);
  }
});

test('empty captured streams remain distinct from unavailable output', () => {
  const diagnostics = failure({ status: 1, stdout: '', stderr: null });
  assert.equal(diagnostics.stdout.available, true);
  assert.equal(diagnostics.stdout.inputBytes, 0);
  assert.equal(diagnostics.stdout.inputLines, 0);
  assert.equal(diagnostics.stderr.available, false);
  assert.equal(diagnostics.stderr.inputBytes, null);
});
