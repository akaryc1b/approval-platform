import { createHash } from 'node:crypto';
import {
  existsSync,
  lstatSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  realpathSync,
  renameSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { basename, relative, resolve, sep } from 'node:path';
import { performance } from 'node:perf_hooks';

const approvalPath = /^\/api\/approval\/tasks\/[0-9a-f-]{36}\/approve$/iu;
const expectedOrigin = 'http://127.0.0.1:8080';
const evidenceFileName = 'profile-matrix-command-retry-evidence.json';
const retryableCode = 'APPROVAL_COMMAND_FAILED';
const maximumAttempts = 4;
const backoffMilliseconds = Object.freeze([50, 100, 200]);
const maximumBodyBytes = 64 * 1024;

export const profileCommandRetryContract = Object.freeze({
  schemaVersion: 1,
  evidenceKind: 'CAPACITY_PROFILE_COMMAND_RETRY_CONTRACT_V1',
  origin: expectedOrigin,
  method: 'POST',
  path: '/api/approval/tasks/<uuid>/approve',
  retryableCode,
  maximumAttempts,
  backoffMilliseconds,
  sameIdempotencyKeyRequired: true,
  freshRequestAndTraceIdPerRetry: true,
  networkFailuresRetried: false,
  nonCommandWritesRetried: false,
});

function rounded(value) {
  return Number(value.toFixed(3));
}

function requiredText(value, label) {
  if (typeof value !== 'string' || value.trim().length === 0) {
    throw new Error(`${label} is required`);
  }
  return value.trim();
}

function exactIdentity(value) {
  const commitSha = requiredText(value?.commitSha, 'identity.commitSha');
  const treeSha = requiredText(value?.treeSha, 'identity.treeSha');
  if (!/^[0-9a-f]{40}$/u.test(commitSha)
      || !/^[0-9a-f]{40}$/u.test(treeSha)) {
    throw new Error('retry evidence requires exact commit and tree identities');
  }
  return { commitSha, treeSha };
}

function safeRelative(root, target) {
  const value = relative(root, target).split(sep).join('/');
  if (!value || value.startsWith('../') || value.includes('/../')) {
    throw new Error(`profile retry evidence escaped its output root: ${target}`);
  }
  return value;
}

function readJson(path) {
  return JSON.parse(readFileSync(path, 'utf8'));
}

function activeProfileDirectory(outputRoot, identity, installedAtMs) {
  if (!existsSync(outputRoot)) {
    throw new Error('capacity profile output root is unavailable for retry evidence');
  }
  const canonicalRoot = realpathSync(outputRoot);
  const candidates = [];
  for (const entry of readdirSync(canonicalRoot, { withFileTypes: true })) {
    if (!entry.isDirectory() || !entry.name.endsWith('-profiles')) continue;
    const candidate = resolve(canonicalRoot, entry.name);
    const metadata = lstatSync(candidate);
    if (metadata.isSymbolicLink()) {
      throw new Error(`profile retry evidence rejects a symbolic-link run: ${candidate}`);
    }
    const canonicalCandidate = realpathSync(candidate);
    safeRelative(canonicalRoot, canonicalCandidate);
    const sourcePath = resolve(canonicalCandidate, 'source-identity.json');
    const contractPath = resolve(canonicalCandidate, 'profile-matrix-contract.json');
    if (!existsSync(sourcePath) || !existsSync(contractPath)) continue;
    const source = readJson(sourcePath);
    const capturedAt = Date.parse(source.capturedAt || '');
    if (!Number.isFinite(capturedAt) || capturedAt < installedAtMs - 1_000) continue;
    if (source.commitSha !== identity.commitSha
        || source.treeSha !== identity.treeSha) continue;
    candidates.push({
      directory: canonicalCandidate,
      capturedAt,
      modifiedAt: statSync(canonicalCandidate).mtimeMs,
    });
  }
  candidates.sort((left, right) =>
    right.capturedAt - left.capturedAt || right.modifiedAt - left.modifiedAt);
  if (candidates.length !== 1) {
    throw new Error(
      `expected one active exact-Head profile run for retry evidence, found ${candidates.length}`,
    );
  }
  return candidates[0].directory;
}

function normalizeRequest(input, init = {}) {
  if (input instanceof Request) {
    throw new Error('profile retry wrapper requires an explicit URL and reusable body');
  }
  const url = new URL(String(input));
  const method = String(init.method || 'GET').toUpperCase();
  const headers = new Headers(init.headers || {});
  return { url, method, headers };
}

function scopedApprovalCommand(request) {
  return request.url.origin === expectedOrigin
    && request.method === 'POST'
    && approvalPath.test(request.url.pathname);
}

function retryableResponsePayload(text) {
  try {
    const value = JSON.parse(text);
    const nested = value?.error;
    const candidate = nested && typeof nested === 'object' && !Array.isArray(nested)
      ? nested
      : value;
    return {
      code: typeof candidate?.code === 'string' ? candidate.code : null,
      retryable: candidate?.retryable === true,
    };
  } catch {
    return { code: null, retryable: false };
  }
}

function signalAwareDelay(milliseconds, signal, sleepImplementation) {
  if (!signal) return sleepImplementation(milliseconds);
  if (signal.aborted) {
    return Promise.reject(signal.reason || new Error('retry delay aborted'));
  }
  return new Promise((resolvePromise, rejectPromise) => {
    let settled = false;
    const finish = (callback, value) => {
      if (settled) return;
      settled = true;
      signal.removeEventListener('abort', abort);
      callback(value);
    };
    const abort = () => finish(
      rejectPromise,
      signal.reason || new Error('retry delay aborted'),
    );
    signal.addEventListener('abort', abort, { once: true });
    Promise.resolve(sleepImplementation(milliseconds)).then(
      () => finish(resolvePromise),
      error => finish(rejectPromise, error),
    );
  });
}

function commandDigest(idempotencyKey) {
  return createHash('sha256').update(idempotencyKey).digest('hex');
}

function writeJsonAtomically(path, value, writeSequence) {
  const temporary = `${path}.tmp-${process.pid}-${writeSequence}`;
  writeFileSync(
    temporary,
    `${JSON.stringify(value, null, 2)}\n`,
    { encoding: 'utf8', mode: 0o600 },
  );
  renameSync(temporary, path);
}

export function createProfileCommandRetryFetch({
  fetchImplementation,
  outputRoot,
  identity: rawIdentity,
  installedAtMs = Date.now(),
  sleepImplementation = milliseconds =>
    new Promise(resolvePromise => setTimeout(resolvePromise, milliseconds)),
}) {
  if (typeof fetchImplementation !== 'function') {
    throw new Error('fetchImplementation must be a function');
  }
  const identity = exactIdentity(rawIdentity);
  const canonicalOutputRoot = resolve(requiredText(outputRoot, 'outputRoot'));
  const events = [];
  const commands = new Map();
  let eventSequence = 0;
  let writeSequence = 0;
  let runDirectory;

  function evidenceDirectory() {
    runDirectory ||= activeProfileDirectory(
      canonicalOutputRoot,
      identity,
      installedAtMs,
    );
    return runDirectory;
  }

  function evidenceValue() {
    const commandValues = [...commands.values()];
    return {
      schemaVersion: 1,
      evidenceKind: 'CAPACITY_PROFILE_COMMAND_RETRY_EVIDENCE_V1',
      runId: basename(evidenceDirectory()),
      ...identity,
      contract: profileCommandRetryContract,
      totals: {
        logicalCommandsObserved: commandValues.length,
        transportAttempts: events.length,
        retryableResponses: commandValues.reduce(
          (total, command) => total + command.retryableResponses,
          0,
        ),
        commandsRetried: commandValues.filter(command => command.attempts > 1).length,
        recoveredCommands: commandValues.filter(command => command.recovered).length,
        terminalFailures: commandValues.filter(command => command.terminalFailure).length,
        maximumObservedAttempts: commandValues.reduce(
          (maximum, command) => Math.max(maximum, command.attempts),
          0,
        ),
      },
      attempts: events,
      interpretation:
        'PROFILE_REQUEST_SUMMARY_COUNTS_TERMINAL_LOGICAL_OUTCOMES_WHILE_THIS_FILE_RETAINS_EVERY_TRANSPORT_ATTEMPT',
      nonClaims: [
        'NETWORK_FAILURES_NOT_RETRIED',
        'NON_APPROVAL_COMMAND_WRITES_NOT_RETRIED',
        'RETRY_RECOVERY_DOES_NOT_PROVE_MAXIMUM_STABLE_ENVELOPE',
      ],
      updatedAt: new Date().toISOString(),
    };
  }

  function persist() {
    const directory = evidenceDirectory();
    mkdirSync(directory, { recursive: true, mode: 0o700 });
    writeSequence += 1;
    writeJsonAtomically(
      resolve(directory, evidenceFileName),
      evidenceValue(),
      writeSequence,
    );
  }

  async function retryingFetch(input, init = {}) {
    const request = normalizeRequest(input, init);
    if (!scopedApprovalCommand(request)) {
      return fetchImplementation(input, init);
    }
    const idempotencyKey = requiredText(
      request.headers.get('Idempotency-Key'),
      'approval Idempotency-Key',
    );
    const body = init.body;
    if (typeof body !== 'string'
        || Buffer.byteLength(body, 'utf8') > maximumBodyBytes) {
      throw new Error('profile approval retry requires one bounded reusable JSON body');
    }
    const baseRequestId = requiredText(
      request.headers.get('X-Request-Id'),
      'approval X-Request-Id',
    );
    const commandId = commandDigest(idempotencyKey);
    const command = commands.get(commandId) || {
      commandId,
      path: request.url.pathname,
      attempts: 0,
      retryableResponses: 0,
      recovered: false,
      terminalFailure: false,
    };
    commands.set(commandId, command);

    for (let attempt = 1; attempt <= maximumAttempts; attempt += 1) {
      const headers = new Headers(request.headers);
      const requestId = attempt === 1
        ? baseRequestId
        : `${baseRequestId}-retry-${attempt}`;
      headers.set('X-Request-Id', requestId);
      headers.set('X-Trace-Id', requestId);
      const startedAt = new Date().toISOString();
      const started = performance.now();
      let response;
      try {
        response = await fetchImplementation(request.url, {
          ...init,
          method: request.method,
          headers,
          body,
        });
      } catch (error) {
        command.attempts = attempt;
        command.terminalFailure = true;
        eventSequence += 1;
        events.push({
          sequence: eventSequence,
          commandId,
          attempt,
          requestId,
          status: 0,
          code: null,
          retryable: false,
          outcome: 'TERMINAL_NETWORK_FAILURE_NOT_RETRIED',
          startedAt,
          completedAt: new Date().toISOString(),
          latencyMs: rounded(performance.now() - started),
        });
        persist();
        throw error;
      }

      const text = await response.clone().text();
      const classification = retryableResponsePayload(text);
      const mayRetry = response.status === 500
        && classification.code === retryableCode
        && classification.retryable;
      const succeeded = response.ok;
      command.attempts = attempt;
      if (mayRetry) command.retryableResponses += 1;
      command.recovered = succeeded && attempt > 1;
      command.terminalFailure = !succeeded
        && (!mayRetry || attempt === maximumAttempts);
      let outcome = 'SUCCEEDED';
      if (succeeded && attempt > 1) outcome = 'SUCCEEDED_AFTER_RETRY';
      else if (mayRetry && attempt < maximumAttempts) {
        outcome = 'RETRY_SCHEDULED';
      } else if (mayRetry) outcome = 'TERMINAL_RETRYABLE_FAILURE';
      else if (!succeeded) outcome = 'TERMINAL_NON_RETRYABLE_RESPONSE';
      eventSequence += 1;
      events.push({
        sequence: eventSequence,
        commandId,
        attempt,
        requestId,
        status: response.status,
        code: classification.code,
        retryable: mayRetry,
        outcome,
        startedAt,
        completedAt: new Date().toISOString(),
        latencyMs: rounded(performance.now() - started),
      });
      persist();

      if (succeeded || !mayRetry || attempt === maximumAttempts) {
        if (succeeded && attempt > 1) {
          console.log(
            `CAPACITY_PROFILE_COMMAND_RETRY_RECOVERED command=${commandId} attempts=${attempt}`,
          );
        }
        return response;
      }
      const backoffMs = backoffMilliseconds[attempt - 1];
      console.log(
        `CAPACITY_PROFILE_COMMAND_RETRY_SCHEDULED command=${commandId} attempt=${attempt} backoffMs=${backoffMs}`,
      );
      await signalAwareDelay(
        backoffMs,
        init.signal,
        sleepImplementation,
      );
    }
    throw new Error('bounded profile command retry loop exhausted unexpectedly');
  }

  return {
    fetch: retryingFetch,
    snapshot: evidenceValue,
  };
}

export function installProfileCommandRetryEvidence(options) {
  const originalFetch = globalThis.fetch;
  const controller = createProfileCommandRetryFetch({
    ...options,
    fetchImplementation: originalFetch,
  });
  globalThis.fetch = controller.fetch;
  let restored = false;
  return {
    snapshot: controller.snapshot,
    restore() {
      if (restored) return;
      restored = true;
      if (globalThis.fetch !== controller.fetch) {
        throw new Error('profile command retry fetch was replaced before restoration');
      }
      globalThis.fetch = originalFetch;
    },
  };
}
