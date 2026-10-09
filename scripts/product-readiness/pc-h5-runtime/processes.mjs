import { spawn, spawnSync } from 'node:child_process';
import { createWriteStream } from 'node:fs';

import { repositoryRoot } from './contract.mjs';
import {
  createSafeProcessOutput,
  maximumCheckedOutputBytes,
  safeProcessErrorCode,
} from './safe-output.mjs';

const pollIntervalMs = 1_000;
const defaultCheckedProcessTimeoutMs = 15 * 60_000;
const maximumCheckedProcessTimeoutMs = 60 * 60_000;

function pnpmExecutable() {
  return process.platform === 'win32' ? 'pnpm.cmd' : 'pnpm';
}

function processExited(child) {
  return child.exitCode !== null || child.signalCode !== null;
}

function checkedProcessTimeout(environment, timeoutMs) {
  const configured = timeoutMs
    ?? environment?.APPROVAL_DEMO_COMMAND_TIMEOUT_MS
    ?? defaultCheckedProcessTimeoutMs;
  const value = Number(configured);
  if (!Number.isFinite(value)
      || value <= 0
      || value > maximumCheckedProcessTimeoutMs) {
    throw new Error(
      'checked process timeout must be between 1 ms and 60 minutes',
    );
  }
  return Math.floor(value);
}

function requireSuccessfulProcess(label, result) {
  if (result.error) {
    throw new Error(`${label} failed: ${safeProcessErrorCode(result.error)}`);
  }
  if (result.status !== 0) {
    const signal = result.signal ? ` signal ${result.signal}` : '';
    throw new Error(`${label} failed with exit code ${result.status}${signal}`);
  }
}

function recordCheckedOutput(result) {
  for (const output of [result.stdout, result.stderr]) {
    const recorder = createSafeProcessOutput({ emit: value => process.stdout.write(value) });
    if (output) recorder.write(output);
    recorder.end();
  }
}

function spawnChecked(label, spawnProcess) {
  try {
    return spawnProcess();
  } catch (error) {
    // Spawn validation errors may include paths, arguments, or environment
    // values. Do not attach their raw message or cause to the safe failure.
    throw new Error(`${label} failed: ${safeProcessErrorCode(error)}`);
  }
}

export function runPnpmChecked(
  label,
  args,
  environment = process.env,
  timeoutMs = undefined,
) {
  console.log(`\n==> ${label}`);
  const result = spawnChecked(label, () => spawnSync(pnpmExecutable(), args, {
    cwd: repositoryRoot,
    env: environment,
    shell: false,
    stdio: ['ignore', 'pipe', 'pipe'],
    maxBuffer: maximumCheckedOutputBytes,
    timeout: checkedProcessTimeout(environment, timeoutMs),
  }));
  recordCheckedOutput(result);
  requireSuccessfulProcess(label, result);
}

export function runNodeChecked(
  label,
  args,
  environment = process.env,
  timeoutMs = undefined,
  workingDirectory = repositoryRoot,
) {
  console.log(`\n==> ${label}`);
  const result = spawnChecked(label, () => spawnSync(process.execPath, args, {
    cwd: workingDirectory,
    env: environment,
    shell: false,
    stdio: ['ignore', 'pipe', 'pipe'],
    maxBuffer: maximumCheckedOutputBytes,
    timeout: checkedProcessTimeout(environment, timeoutMs),
  }));
  recordCheckedOutput(result);
  requireSuccessfulProcess(label, result);
}

export function startManagedNode(
  label,
  args,
  logFile,
  environment,
  workingDirectory = repositoryRoot,
) {
  console.log(`\n==> ${label}`);
  const stream = createWriteStream(logFile, { flags: 'w', mode: 0o600 });
  const state = { buffer: '', spawnError: undefined };
  const child = spawn(process.execPath, args, {
    cwd: workingDirectory,
    detached: process.platform !== 'win32',
    env: environment,
    shell: false,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  child.once('error', error => {
    state.spawnError = new Error(`${label} failed: ${safeProcessErrorCode(error)}`);
  });
  stream.once('error', error => {
    state.spawnError = new Error(`${label} log failed: ${safeProcessErrorCode(error)}`);
  });
  const retain = text => {
    state.buffer = `${state.buffer}${text}`.slice(-256_000);
  };
  const emit = text => {
    process.stdout.write(text);
    if (!stream.destroyed && !stream.writableEnded) stream.write(text);
    // Dynamic control values arrive separately; never replace them with the
    // deliberately value-free logging projection.
    if (!text.endsWith('=[value withheld]\n')) retain(text);
  };
  const recorders = [child.stdout, child.stderr].map(output => {
    const recorder = createSafeProcessOutput({ emit, onControlLine: retain });
    output.on('data', chunk => recorder.write(chunk));
    return recorder;
  });
  child.once('close', () => {
    for (const recorder of recorders) recorder.end();
    if (!stream.destroyed && !stream.writableEnded) stream.end();
  });
  return { child, label, state };
}

export function terminateManaged(processState) {
  const child = processState?.child;
  if (!child?.pid || processExited(child)) return;
  try {
    if (process.platform === 'win32') child.kill('SIGTERM');
    else process.kill(-child.pid, 'SIGTERM');
  } catch (error) {
    if (error.code !== 'ESRCH') throw error;
  }
}

export function delay(milliseconds) {
  return new Promise(resolvePromise =>
    setTimeout(resolvePromise, milliseconds));
}

export async function waitForMarker(processState, marker, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (processState.state.spawnError) {
      throw processState.state.spawnError;
    }
    if (processState.state.buffer.includes(marker)) return;
    if (processExited(processState.child)) {
      throw new Error(`${processState.label} exited before ${marker}`);
    }
    await delay(pollIntervalMs);
  }
  throw new Error(`${processState.label} did not emit ${marker}`);
}

export async function waitForHttp(url, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url, {
        redirect: 'manual',
        signal: AbortSignal.timeout(2_000),
      });
      if (response.status >= 200 && response.status < 500) return;
    } catch {
      // Bounded polling intentionally ignores transient startup failures.
    }
    await delay(pollIntervalMs);
  }
  throw new Error(`HTTP endpoint did not become ready: ${url}`);
}
