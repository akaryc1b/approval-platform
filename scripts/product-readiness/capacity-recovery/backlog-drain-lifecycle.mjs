import { resolve } from 'node:path';

import {
  delay,
  runNodeChecked,
  terminateManaged,
} from '../pc-h5-runtime/processes.mjs';
import { waitForPortAvailable } from '../purchase-payment-e2e/evidence.mjs';
import {
  composeFile,
  composeProject,
  writeJson,
} from './contract.mjs';
import { snapshot } from './backlog-drain-contract.mjs';

function processExited(child) {
  return child.exitCode !== null || child.signalCode !== null;
}

function terminateProcessGroup(child, signal) {
  if (!child?.pid || processExited(child)) return;
  try {
    if (process.platform === 'win32') child.kill(signal);
    else process.kill(-child.pid, signal);
  } catch (error) {
    if (error.code !== 'ESRCH') throw error;
  }
}

async function stopManaged(processState) {
  if (!processState) return;
  terminateManaged(processState);
  let deadline = Date.now() + 10_000;
  while (!processExited(processState.child) && Date.now() < deadline) {
    await delay(250);
  }
  if (processExited(processState.child)) return;
  terminateProcessGroup(processState.child, 'SIGKILL');
  deadline = Date.now() + 5_000;
  while (!processExited(processState.child) && Date.now() < deadline) {
    await delay(250);
  }
  if (!processExited(processState.child)) {
    throw new Error(`${processState.label} did not terminate after SIGKILL`);
  }
}

export function remainingMilliseconds(deadline, label) {
  const remaining = deadline - Date.now();
  if (remaining <= 0) {
    throw new Error(`${label} exceeded the backlog-drain deadline`);
  }
  return remaining;
}

export function composeArguments(...args) {
  return [
    'compose',
    '--project-name',
    composeProject,
    '-f',
    composeFile,
    ...args,
  ];
}

export function resetDisposableData(environment, timeoutMs) {
  runNodeChecked(
    'Delete only the disposable local demo volume for backlog drain',
    [
      'scripts/product-readiness/demo-backend.mjs',
      'reset',
      '--confirm-local-data-loss',
    ],
    environment,
    timeoutMs,
  );
}

export async function cleanup(backend, environment, runDirectory, mutated) {
  const actions = [];
  if (backend) {
    await stopManaged(backend);
    actions.push('stopped:backlog-drain-demo-backend');
  }
  if (mutated) {
    resetDisposableData(environment, 15 * 60_000);
    actions.push('deleted:approval-platform-demo-volume');
    for (const port of [5432, 6379, 8080]) {
      await waitForPortAvailable(port);
      actions.push(`released-port:${port}`);
    }
  } else {
    actions.push('skipped-reset:failure-before-runtime-mutation');
  }
  const evidence = snapshot('CAPACITY_BACKLOG_DRAIN_CLEANUP_V1', {
    actions,
    status: 'PASSED',
  });
  writeJson(resolve(runDirectory, 'backlog-drain-cleanup.json'), evidence);
  return evidence;
}
