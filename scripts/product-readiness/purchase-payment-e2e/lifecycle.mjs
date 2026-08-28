import { cpSync, existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { java21Environment } from '../pc-h5-runtime/contract.mjs';
import {
  delay,
  runNodeChecked,
  terminateManaged,
} from '../pc-h5-runtime/processes.mjs';
import {
  pcH5OutputDirectory,
  pollIntervalMs,
  writeJson,
} from './contract.mjs';
import {
  validatePcH5Evidence,
  waitForPortAvailable,
} from './evidence.mjs';

export function resetDisposableData(environment) {
  runNodeChecked(
    'Delete disposable local runtime data',
    [
      'scripts/product-readiness/demo-backend.mjs',
      'reset',
      '--confirm-local-data-loss',
    ],
    environment,
  );
}

export function stagePcH5Evidence(
  runDirectory,
  contract,
  identity,
  reuseExisting,
) {
  if (!reuseExisting) {
    resetDisposableData(java21Environment());
    runNodeChecked(
      'Execute the existing PC/H5 runtime handoff',
      ['scripts/product-readiness/pc-h5-runtime-smoke.mjs', 'run'],
      java21Environment(),
    );
  }
  const evidencePath = resolve(
    pcH5OutputDirectory,
    'pc-h5-runtime-evidence.json',
  );
  if (!existsSync(evidencePath)) {
    throw new Error('preceding PC/H5 runtime evidence is unavailable');
  }
  const evidence = validatePcH5Evidence(
    JSON.parse(readFileSync(evidencePath, 'utf8')),
    contract,
    identity,
  );
  cpSync(
    pcH5OutputDirectory,
    resolve(runDirectory, 'pc-h5-runtime'),
    { recursive: true },
  );
  return evidence;
}

function terminateProcessGroup(child, signal) {
  if (!child?.pid || child.exitCode !== null) return;
  try {
    if (process.platform === 'win32') child.kill(signal);
    else process.kill(-child.pid, signal);
  } catch (error) {
    if (error.code !== 'ESRCH') throw error;
  }
}

async function stopManaged(processState) {
  terminateManaged(processState);
  let deadline = Date.now() + 10_000;
  while (processState.child.exitCode === null && Date.now() < deadline) {
    await delay(pollIntervalMs);
  }
  if (processState.child.exitCode !== null) return;

  terminateProcessGroup(processState.child, 'SIGKILL');
  deadline = Date.now() + 5_000;
  while (processState.child.exitCode === null && Date.now() < deadline) {
    await delay(pollIntervalMs);
  }
  if (processState.child.exitCode === null) {
    throw new Error(`${processState.label} did not terminate after SIGKILL`);
  }
}

export async function cleanupRuntime(managed, environment, runDirectory) {
  const actions = [];
  for (const processState of managed.reverse()) {
    await stopManaged(processState);
    actions.push(`stopped:${processState.label}`);
  }
  resetDisposableData(environment);
  actions.push('deleted:approval-platform-demo-volume');
  for (const port of [5777, 8080, 9000]) {
    await waitForPortAvailable(port);
    actions.push(`released-port:${port}`);
  }
  const evidence = {
    schemaVersion: 1,
    evidenceKind: 'PURCHASE_PAYMENT_E2E_CLEANUP_V1',
    actions,
    completedAt: new Date().toISOString(),
  };
  writeJson(resolve(runDirectory, 'cleanup-evidence.json'), evidence);
  return evidence;
}
