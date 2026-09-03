import assert from 'node:assert/strict';
import {
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import test from 'node:test';

import {
  createProfileCommandRetryFetch,
  profileCommandRetryContract,
} from '../product-readiness/capacity-recovery/retryable-command-fetch.mjs';

function exactIdentity() {
  return {
    commitSha: 'a'.repeat(40),
    treeSha: 'b'.repeat(40),
  };
}

function profileRun(outputRoot, identity) {
  const runDirectory = resolve(outputRoot, 'test-profiles');
  mkdirSync(runDirectory, { recursive: true, mode: 0o700 });
  writeFileSync(
    resolve(runDirectory, 'source-identity.json'),
    `${JSON.stringify({
      schemaVersion: 1,
      evidenceKind: 'CAPACITY_PROFILE_MATRIX_SOURCE_IDENTITY_V1',
      runId: 'test-profiles',
      capturedAt: new Date().toISOString(),
      ...identity,
    })}\n`,
    'utf8',
  );
  writeFileSync(
    resolve(runDirectory, 'profile-matrix-contract.json'),
    '{}\n',
    'utf8',
  );
  return runDirectory;
}

function approvalRequest() {
  return {
    url: 'http://127.0.0.1:8080/api/approval/tasks/'
      + '11111111-1111-4111-8111-111111111111/approve',
    options: {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': 'stable-approval-key',
        'X-Request-Id': 'request-1',
        'X-Trace-Id': 'request-1',
      },
      body: '{}',
    },
  };
}

function approvalCommandError(retryable) {
  return {
    code: 'APPROVAL_COMMAND_FAILED',
    message: 'approval command failed',
    retryable,
    requestId: 'request-1',
    occurredAt: '2026-09-03T01:47:32.169786351Z',
  };
}

test('profile command retry contract is narrow deterministic and bounded', () => {
  assert.equal(profileCommandRetryContract.origin, 'http://127.0.0.1:8080');
  assert.equal(profileCommandRetryContract.method, 'POST');
  assert.equal(
    profileCommandRetryContract.path,
    '/api/approval/tasks/<uuid>/approve',
  );
  assert.equal(
    profileCommandRetryContract.retryableCode,
    'APPROVAL_COMMAND_FAILED',
  );
  assert.equal(profileCommandRetryContract.maximumAttempts, 4);
  assert.deepEqual(
    profileCommandRetryContract.backoffMilliseconds,
    [50, 100, 200],
  );
  assert.equal(profileCommandRetryContract.sameIdempotencyKeyRequired, true);
  assert.equal(profileCommandRetryContract.freshRequestAndTraceIdPerRetry, true);
  assert.equal(profileCommandRetryContract.networkFailuresRetried, false);
  assert.equal(profileCommandRetryContract.nonCommandWritesRetried, false);
});

test('top-level API retryable approval failure is recovered with retained attempts', async () => {
  const outputRoot = mkdtempSync(resolve(tmpdir(), 'approval-capacity-retry-'));
  const identity = exactIdentity();
  const runDirectory = profileRun(outputRoot, identity);
  const calls = [];
  const delays = [];
  const responses = [
    new Response(JSON.stringify(approvalCommandError(true)), { status: 500 }),
    new Response(JSON.stringify({ data: { accepted: true } }), { status: 200 }),
  ];
  try {
    const controller = createProfileCommandRetryFetch({
      outputRoot,
      identity,
      installedAtMs: Date.now() - 10,
      fetchImplementation: async (url, options) => {
        calls.push({ url: String(url), options });
        return responses.shift();
      },
      sleepImplementation: async milliseconds => {
        delays.push(milliseconds);
      },
    });
    const request = approvalRequest();
    const response = await controller.fetch(request.url, request.options);
    assert.equal(response.status, 200);
    assert.equal(calls.length, 2);
    assert.equal(
      calls[0].options.headers.get('Idempotency-Key'),
      'stable-approval-key',
    );
    assert.equal(
      calls[1].options.headers.get('Idempotency-Key'),
      'stable-approval-key',
    );
    assert.equal(calls[0].options.headers.get('X-Request-Id'), 'request-1');
    assert.equal(
      calls[1].options.headers.get('X-Request-Id'),
      'request-1-retry-2',
    );
    assert.equal(
      calls[1].options.headers.get('X-Trace-Id'),
      'request-1-retry-2',
    );
    assert.deepEqual(delays, [50]);

    const evidence = JSON.parse(readFileSync(
      resolve(runDirectory, 'profile-matrix-command-retry-evidence.json'),
      'utf8',
    ));
    assert.equal(evidence.commitSha, identity.commitSha);
    assert.equal(evidence.treeSha, identity.treeSha);
    assert.equal(evidence.totals.logicalCommandsObserved, 1);
    assert.equal(evidence.totals.transportAttempts, 2);
    assert.equal(evidence.totals.retryableResponses, 1);
    assert.equal(evidence.totals.commandsRetried, 1);
    assert.equal(evidence.totals.recoveredCommands, 1);
    assert.equal(evidence.totals.terminalFailures, 0);
    assert.equal(evidence.attempts[0].code, 'APPROVAL_COMMAND_FAILED');
    assert.equal(evidence.attempts[0].retryable, true);
    assert.equal(evidence.attempts[0].outcome, 'RETRY_SCHEDULED');
    assert.equal(evidence.attempts[1].outcome, 'SUCCEEDED_AFTER_RETRY');
  } finally {
    rmSync(outputRoot, { recursive: true, force: true });
  }
});

test('nested retryable error envelope remains accepted for compatibility', async () => {
  const outputRoot = mkdtempSync(resolve(tmpdir(), 'approval-capacity-nested-'));
  const identity = exactIdentity();
  const runDirectory = profileRun(outputRoot, identity);
  const responses = [
    new Response(JSON.stringify({
      error: {
        code: 'APPROVAL_COMMAND_FAILED',
        retryable: true,
      },
    }), { status: 500 }),
    new Response('{}', { status: 200 }),
  ];
  try {
    const controller = createProfileCommandRetryFetch({
      outputRoot,
      identity,
      installedAtMs: Date.now() - 10,
      fetchImplementation: async () => responses.shift(),
      sleepImplementation: async () => {},
    });
    const request = approvalRequest();
    const response = await controller.fetch(request.url, request.options);
    assert.equal(response.status, 200);
    const evidence = JSON.parse(readFileSync(
      resolve(runDirectory, 'profile-matrix-command-retry-evidence.json'),
      'utf8',
    ));
    assert.equal(evidence.totals.transportAttempts, 2);
    assert.equal(evidence.totals.recoveredCommands, 1);
  } finally {
    rmSync(outputRoot, { recursive: true, force: true });
  }
});

test('top-level non-retryable approval response is retained as terminal', async () => {
  const outputRoot = mkdtempSync(resolve(tmpdir(), 'approval-capacity-terminal-'));
  const identity = exactIdentity();
  const runDirectory = profileRun(outputRoot, identity);
  let calls = 0;
  try {
    const controller = createProfileCommandRetryFetch({
      outputRoot,
      identity,
      installedAtMs: Date.now() - 10,
      fetchImplementation: async () => {
        calls += 1;
        return new Response(
          JSON.stringify(approvalCommandError(false)),
          { status: 500 },
        );
      },
      sleepImplementation: async () => {
        throw new Error('non-retryable response must not sleep');
      },
    });
    const request = approvalRequest();
    const response = await controller.fetch(request.url, request.options);
    assert.equal(response.status, 500);
    assert.equal(calls, 1);
    const evidence = JSON.parse(readFileSync(
      resolve(runDirectory, 'profile-matrix-command-retry-evidence.json'),
      'utf8',
    ));
    assert.equal(evidence.totals.transportAttempts, 1);
    assert.equal(evidence.totals.retryableResponses, 0);
    assert.equal(evidence.totals.commandsRetried, 0);
    assert.equal(evidence.totals.recoveredCommands, 0);
    assert.equal(evidence.totals.terminalFailures, 1);
    assert.equal(
      evidence.attempts[0].outcome,
      'TERMINAL_NON_RETRYABLE_RESPONSE',
    );
  } finally {
    rmSync(outputRoot, { recursive: true, force: true });
  }
});
