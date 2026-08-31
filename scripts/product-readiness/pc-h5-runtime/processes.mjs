import { spawn, spawnSync } from 'node:child_process';
import { createWriteStream } from 'node:fs';

import { repositoryRoot } from './contract.mjs';

const pollIntervalMs = 1_000;

function pnpmExecutable() {
  return process.platform === 'win32' ? 'pnpm.cmd' : 'pnpm';
}

function processExited(child) {
  return child.exitCode !== null || child.signalCode !== null;
}

export function runPnpmChecked(label, args, environment = process.env) {
  console.log(`\n==> ${label}`);
  const result = spawnSync(pnpmExecutable(), args, {
    cwd: repositoryRoot,
    env: environment,
    shell: false,
    stdio: 'inherit',
  });
  if (result.error) {
    throw new Error(`${label} could not start: ${result.error.message}`);
  }
  if (result.status !== 0) {
    throw new Error(`${label} failed with exit code ${result.status}`);
  }
}

export function runNodeChecked(label, args, environment = process.env) {
  console.log(`\n==> ${label}`);
  const result = spawnSync(process.execPath, args, {
    cwd: repositoryRoot,
    env: environment,
    shell: false,
    stdio: 'inherit',
  });
  if (result.error) {
    throw new Error(`${label} could not start: ${result.error.message}`);
  }
  if (result.status !== 0) {
    throw new Error(`${label} failed with exit code ${result.status}`);
  }
}

export function startManagedNode(label, args, logFile, environment) {
  console.log(`\n==> ${label}`);
  const stream = createWriteStream(logFile, { flags: 'w', mode: 0o600 });
  const state = { buffer: '', spawnError: undefined };
  const child = spawn(process.execPath, args, {
    cwd: repositoryRoot,
    detached: process.platform !== 'win32',
    env: environment,
    shell: false,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  child.once('error', error => {
    state.spawnError = error;
  });
  stream.once('error', error => {
    state.spawnError = error;
  });
  const record = chunk => {
    const text = chunk.toString('utf8');
    process.stdout.write(text);
    if (!stream.destroyed && !stream.writableEnded) stream.write(text);
    state.buffer = `${state.buffer}${text}`.slice(-256_000);
  };
  child.stdout.on('data', record);
  child.stderr.on('data', record);
  child.once('close', () => {
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
