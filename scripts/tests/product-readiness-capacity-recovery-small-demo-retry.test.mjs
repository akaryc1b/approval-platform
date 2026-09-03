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

import { createProfileCommandRetryFetch } from
  '../product-readiness/capacity-recovery/retryable-command-fetch.mjs';

const identity = Object.freeze({
  commitSha: 'c'.repeat(40),
  treeSha: 'd'.repeat(40),
});

function createSmallDemoRun(outputRoot) {
  const runDirectory = resolve(outputRoot, 'capacity-test-small-demo');
  mkdirSync(runDirectory, { recursive: true, mode: 0o700 });
  writeFileSync(resolve(runDirectory, 'source-identity.json'), `${JSON.stringify({
    schemaVersion: 1,
    evidenceKind: 'CAPACITY_RECOVERY_SOURCE_IDENTITY_V1',
    runId: 'capacity-test-small-demo',
    capturedAt: new Date().toISOString(),
    ...identity,
  })}\n`, 'utf8');
  writeFileSync(resolve(runDirectory, 'profile-contract.json'), '{}\n', 'utf8');
  return runDirectory;
}

test('Small Demo retry evidence resolves its non-profile run directory', async () => {
  const outputRoot = mkdtempSync(resolve(tmpdir(), 'capacity-small-retry-'));
  const runDirectory = createSmallDemoRun(outputRoot);
  const responses = [
    new Response(JSON.stringify({
      code: 'APPROVAL_COMMAND_FAILED',
      retryable: true,
    }), { status: 500 }),
    new Response(JSON.stringify({ data: { accepted: true } }), { status: 200 }),
  ];
  try {
    const controller = createProfileCommandRetryFetch({
      outputRoot,
      identity,
      installedAtMs: Date.now() - 10,
      runDirectorySuffix: null,
      contractFileName: 'profile-contract.json',
      evidenceFileName: 'small-demo-command-retry-evidence.json',
      fetchImplementation: async () => responses.shift(),
      sleepImplementation: async () => {},
    });
    const response = await controller.fetch(
      'http://127.0.0.1:8080/api/approval/tasks/'
        + '11111111-1111-4111-8111-111111111111/approve',
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': 'small-demo-stable-key',
          'X-Request-Id': 'small-demo-request-1',
          'X-Trace-Id': 'small-demo-request-1',
        },
        body: '{}',
      },
    );
    assert.equal(response.status, 200);
    const evidence = JSON.parse(readFileSync(
      resolve(runDirectory, 'small-demo-command-retry-evidence.json'),
      'utf8',
    ));
    assert.equal(evidence.runId, 'capacity-test-small-demo');
    assert.equal(evidence.commitSha, identity.commitSha);
    assert.equal(evidence.treeSha, identity.treeSha);
    assert.equal(evidence.totals.transportAttempts, 2);
    assert.equal(evidence.totals.recoveredCommands, 1);
    assert.equal(evidence.attempts[0].outcome, 'RETRY_SCHEDULED');
    assert.equal(evidence.attempts[1].outcome, 'SUCCEEDED_AFTER_RETRY');
  } finally {
    rmSync(outputRoot, { recursive: true, force: true });
  }
});

test('retry evidence rejects path-like custom file names', () => {
  const outputRoot = mkdtempSync(resolve(tmpdir(), 'capacity-small-invalid-'));
  try {
    assert.throws(
      () => createProfileCommandRetryFetch({
        outputRoot,
        identity,
        contractFileName: '../profile-contract.json',
        fetchImplementation: async () => new Response('{}'),
      }),
      /must remain within the run directory/u,
    );
  } finally {
    rmSync(outputRoot, { recursive: true, force: true });
  }
});
