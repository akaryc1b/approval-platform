import { spawnSync } from 'node:child_process';
import { performance } from 'node:perf_hooks';

const MAX_LINE_BYTES = 1024;
const MAX_RETAINED_BYTES = 3584; // Reserve 512 bytes for counters within each 4 KiB summary.
const MAX_RETAINED_LINES = 64;
const ERROR_CODES = new Set(['ETIMEDOUT', 'ENOBUFS', 'ENOENT', 'EACCES', 'EPERM', 'EAGAIN', 'ENOMEM', 'EMFILE', 'ENFILE', 'EINVAL', 'EIO', 'E2BIG']);
const SIGNALS = new Set(['SIGTERM', 'SIGKILL', 'SIGINT', 'SIGHUP', 'SIGQUIT', 'SIGABRT', 'SIGSEGV', 'SIGBUS', 'SIGILL', 'SIGPIPE', 'SIGALRM', 'SIGXCPU', 'SIGXFSZ']);
const IMAGE = '(?:docker\\.io/)?semgrep/semgrep:1\\.172\\.0';
const IMAGE_LINE = new RegExp(`^${IMAGE}$`);
const IMAGE_STATUS = new RegExp(`^Status: (Downloaded newer image for|Image is up to date for) (${IMAGE})$`);
const AMOUNT = '[0-9]{1,12}(?:\\.[0-9]{1,3})?(?:B|kB|MB|GB|TB)';
const LAYER_PROGRESS = new RegExp(`^([0-9a-f]{12}): (Downloading|Extracting)(?: +\\[[=> ]{1,50}\\] +${AMOUNT}(?:/${AMOUNT})?)?$`);
const ERROR_CATEGORIES = [
  ['RATE_LIMIT', /^Error response from daemon: (?:toomanyrequests:|Too Many Requests\b).*$/],
  ['ACCESS_DENIED', /^Error response from daemon: (?:unauthorized:|denied:|pull access denied for\b).*$/],
  ['MANIFEST_UNAVAILABLE', /^Error response from daemon: (?:manifest for .+ not found: manifest unknown:|manifest unknown:|no matching manifest for\b).*$/],
  ['NO_SPACE', /^(?:Error response from daemon: |failed to register layer: ).*\bno space left on device$/],
  ['TIMEOUT', /^(?:Error response from daemon: |error pulling image configuration: |failed to copy: ).*(?:i\/o timeout|TLS handshake timeout|context deadline exceeded|Client\.Timeout exceeded while awaiting headers)\)?$/],
  ['CONNECTION_ERROR', /^(?:Error response from daemon: |error pulling image configuration: |failed to copy: ).*(?:connection refused|connection reset by peer|no such host|network is unreachable)$/],
  ['TLS_ERROR', /^Error response from daemon: .*x509: certificate (?:signed by unknown authority|has expired or is not yet valid).*$/],
  ['DAEMON_UNAVAILABLE', /^Cannot connect to the Docker daemon at .+\. Is the docker daemon running\?$/],
];

// Only complete, bounded, control-free lines reach this narrow Docker grammar.
// Error categories emit constants, never their URL, host, path or message suffix.
function classifyLine(line) {
  const layer = line.match(/^([0-9a-f]{12}): (Pulling fs layer|Waiting|Verifying Checksum|Download complete|Pull complete|Already exists)$/)
    || line.match(LAYER_PROGRESS);
  if (layer) return `${layer[1]}: ${layer[2]}`;
  if (line === '1.172.0: Pulling from semgrep/semgrep') return line;
  if (IMAGE_LINE.test(line) || IMAGE_STATUS.test(line)
      || /^Digest: sha256:[0-9a-f]{64}$/.test(line)
      || /^(?:docker\.io\/)?semgrep\/semgrep@sha256:[0-9a-f]{64}$/.test(line)) return line;
  for (const [label, pattern] of ERROR_CATEGORIES) if (pattern.test(line)) return label;
  return null;
}

function summarize(output) {
  const available = typeof output === 'string';
  const summary = { available, inputBytes: available ? Buffer.byteLength(output) : null,
    inputLines: available ? 0 : null, omittedLines: 0,
    oversizedLines: 0, controlLines: 0, incompleteLines: 0, truncatedLines: 0,
    retainedBytes: 2, entries: [] };
  if (!available) return summary;
  for (let start = 0; start < output.length;) {
    const newline = output.indexOf('\n', start), complete = newline !== -1;
    const end = complete ? newline : output.length;
    const contentEnd = complete && output.charCodeAt(end - 1) === 13 ? end - 1 : end;
    // Count oversized records without allocating their potentially huge slices.
    const line = contentEnd - start <= MAX_LINE_BYTES ? output.slice(start, contentEnd) : null;
    const oversized = line === null || Buffer.byteLength(line) > MAX_LINE_BYTES;
    summary.inputLines++;
    if (oversized) summary.oversizedLines++;
    if (!complete) summary.incompleteLines++;
    let entry = null;
    if (complete && !oversized) {
      if (/[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]/u.test(line)) summary.controlLines++;
      else entry = classifyLine(line);
    }
    // Classify the entire input line first. Never clip raw output into a line
    // that could hide an untrusted suffix behind an otherwise valid prefix.
    const retainedBytes = entry === null ? 0 : Buffer.byteLength(JSON.stringify(entry)) + (summary.entries.length ? 1 : 0);
    if (entry !== null && (summary.entries.length >= MAX_RETAINED_LINES
        || summary.retainedBytes + retainedBytes > MAX_RETAINED_BYTES)) {
      summary.truncatedLines++;
      entry = null;
    }
    if (entry === null) {
      summary.omittedLines++;
    } else {
      summary.entries.push(entry);
      summary.retainedBytes += retainedBytes;
    }
    start = complete ? end + 1 : end;
  }
  return summary;
}

// Keep the existing single attempt, timeout, buffer and termination behavior.
// A spawnSync timeout is not a hard wall guarantee; elapsedMs is observed time.
export function pullSemgrepImage(image, { env, spawn = spawnSync, now = () => performance.now() } = {}) {
  const started = now();
  let result;
  try {
    result = spawn('docker', ['pull', image], { env, encoding: 'utf8', timeout: 900000, maxBuffer: 256 * 1024 * 1024 });
  } catch (error) {
    result = { error };
  }
  const elapsed = now() - started;
  if (!result?.error && result?.status === 0 && result?.signal == null) return result;
  const code = result?.error ? (ERROR_CODES.has(result.error.code) ? result.error.code : 'UNKNOWN') : null;
  const signal = result?.signal == null ? null : (SIGNALS.has(result.signal) ? result.signal : 'UNKNOWN');
  const diagnostics = {
    failure: code === 'ETIMEDOUT' ? 'TIMEOUT' : result?.error ? 'SPAWN_ERROR'
      : signal !== null ? 'SIGNAL' : result?.status == null ? 'STATUS_UNAVAILABLE' : 'EXIT_STATUS',
    code,
    status: Number.isInteger(result?.status) && result.status >= -2147483648 && result.status <= 2147483647 ? result.status : null,
    signal,
    elapsedMs: Number.isFinite(elapsed) && elapsed >= 0 && elapsed <= Number.MAX_SAFE_INTEGER ? Math.round(elapsed) : null,
    stdout: summarize(result?.stdout),
    stderr: summarize(result?.stderr),
  };
  const error = new Error(`Semgrep image pull failed: ${JSON.stringify(diagnostics)}`);
  error.diagnostics = diagnostics;
  throw error;
}
