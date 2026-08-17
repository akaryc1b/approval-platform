#!/usr/bin/env node

import { spawn, spawnSync } from 'node:child_process';
import { once } from 'node:events';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const composeFile = 'deploy/compose/docker-compose.yml';
const composeProject = 'approval-platform-demo';
const healthUrl = 'http://127.0.0.1:8080/actuator/health';
const infrastructureTimeoutMs = 120_000;
const backendTimeoutMs = 240_000;
const pollIntervalMs = 1_500;
const seedMarker = 'PURCHASE_PAYMENT_DEMO_SEED_APPLIED';
const commands = new Set(['start', 'plan', 'stop', 'reset']);

function parseArguments(argv) {
  const values = [...argv];
  let command = 'start';
  if (values[0] && !values[0].startsWith('--')) {
    command = values.shift();
  }
  if (!commands.has(command)) {
    throw new UsageError(`Unknown command: ${command}`);
  }

  const flags = new Set(values);
  const allowed = new Set(['--help']);
  if (command === 'plan') allowed.add('--json');
  if (command === 'reset') allowed.add('--confirm-local-data-loss');
  const unknown = [...flags].filter((flag) => !allowed.has(flag));
  if (unknown.length > 0) {
    throw new UsageError(`Unknown option(s) for ${command}: ${unknown.join(', ')}`);
  }

  return {
    command,
    help: flags.has('--help'),
    json: flags.has('--json'),
    confirmLocalDataLoss: flags.has('--confirm-local-data-loss'),
  };
}

function usage() {
  return `Usage: node scripts/product-readiness/demo-backend.mjs [command] [options]\n\nCommands:\n  start   Run preflight, start PostgreSQL/Redis, build, start the real backend,\n          wait for Actuator UP and the deterministic seed, then remain attached.\n  plan    Print the exact non-destructive startup plan without executing it.\n  stop    Stop local Compose infrastructure without deleting its volume.\n  reset   Delete the disposable local Compose volume. Requires\n          --confirm-local-data-loss.\n\nOptions:\n  --json                     Machine-readable output for plan.\n  --confirm-local-data-loss  Required only for reset.\n  --help                     Show this help.\n\nThis command does not prove the 10-minute Quick Start, a complete approval E2E,\npayment integration, cross-client consistency, capacity, or recovery.`;
}

function startupPlan() {
  const compose = `docker compose --project-name ${composeProject} -f ${composeFile}`;
  return {
    schemaVersion: 1,
    entrypoint: 'pnpm demo:backend:start',
    destructive: false,
    steps: [
      {
        id: 'preflight',
        command: 'node scripts/product-readiness/demo-preflight.mjs',
      },
      {
        id: 'infrastructure',
        command: `${compose} up -d postgres redis`,
      },
      {
        id: 'postgres-readiness',
        command: `${compose} exec -T postgres pg_isready -U approval -d approval`,
      },
      {
        id: 'redis-readiness',
        command: `${compose} exec -T redis redis-cli ping`,
      },
      {
        id: 'reactor-build',
        command: 'mvn -B -ntp -DskipTests install',
      },
      {
        id: 'backend',
        command: 'APPROVAL_DEMO_PURCHASE_PAYMENT_ENABLED=true mvn -B -ntp -f apps/server/pom.xml spring-boot:run -Dspring-boot.run.profiles=local',
      },
      {
        id: 'health',
        command: `GET ${healthUrl}`,
      },
      {
        id: 'seed',
        command: `wait for ${seedMarker}`,
      },
    ],
    successMarkers: [
      'DEMO_BACKEND_ONE_COMMAND_STARTED',
      'BACKEND_LOCAL_START_VERIFIED',
      seedMarker,
    ],
    nonClaims: [
      'QUICK_START_10_MINUTES_NOT_EXECUTED',
      'PURCHASE_APPROVAL_E2E_NOT_EXECUTED',
      'PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED',
      'CROSS_CLIENT_RUNTIME_NOT_EXECUTED',
      'PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED',
    ],
  };
}

function printPlan(jsonOutput) {
  const plan = startupPlan();
  if (jsonOutput) {
    console.log(JSON.stringify(plan, null, 2));
    return;
  }
  console.log('Approval Platform demo backend startup plan');
  console.log('Read-only plan: no process, container, database or volume is changed.\n');
  for (const [index, step] of plan.steps.entries()) {
    console.log(`${String(index + 1).padStart(2, '0')}. ${step.id}: ${step.command}`);
  }
  console.log('\nExpected runtime markers after a successful real execution:');
  for (const marker of plan.successMarkers) console.log(marker);
  console.log('\nExplicit non-claims:');
  for (const marker of plan.nonClaims) console.log(marker);
}

function executable(name) {
  return process.platform === 'win32' && name === 'mvn' ? 'mvn.cmd' : name;
}

function runChecked(label, command, args) {
  console.log(`\n==> ${label}`);
  const result = spawnSync(executable(command), args, {
    cwd: root,
    env: process.env,
    stdio: 'inherit',
    shell: false,
  });
  if (result.error) throw new Error(`${label} could not start: ${result.error.message}`);
  if (result.status !== 0) throw new Error(`${label} failed with exit code ${result.status}`);
}

function runCaptured(command, args) {
  return spawnSync(executable(command), args, {
    cwd: root,
    env: process.env,
    encoding: 'utf8',
    shell: false,
    timeout: 10_000,
  });
}

function composeArguments(...args) {
  return ['compose', '--project-name', composeProject, '-f', composeFile, ...args];
}

function bounded(value) {
  return String(value ?? '')
    .replace(/\s+/gu, ' ')
    .trim()
    .slice(0, 400) || 'no diagnostic output';
}

function delay(milliseconds) {
  return new Promise((resolvePromise) => setTimeout(resolvePromise, milliseconds));
}

async function waitForCommand(label, command, args, predicate, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  let lastDetail = 'not executed';
  while (Date.now() < deadline) {
    const result = runCaptured(command, args);
    lastDetail = result.error?.message ?? `${result.stdout ?? ''}\n${result.stderr ?? ''}`;
    if (!result.error && result.status === 0 && predicate(lastDetail)) {
      console.log(`${label}: ready`);
      return;
    }
    await delay(pollIntervalMs);
  }
  throw new Error(`${label} did not become ready: ${bounded(lastDetail)}`);
}

function readLocalDatabaseEnvironment() {
  const path = resolve(root, '.env.example');
  if (!existsSync(path)) throw new Error('Missing .env.example');
  const parsed = new Map();
  for (const rawLine of readFileSync(path, 'utf8').split(/\r?\n/u)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    const match = line.match(/^([A-Z][A-Z0-9_]*)=(.*)$/u);
    if (match) parsed.set(match[1], match[2]);
  }

  const required = ['APPROVAL_DB_URL', 'APPROVAL_DB_USERNAME', 'APPROVAL_DB_PASSWORD'];
  for (const name of required) {
    if (!parsed.has(name) || parsed.get(name).length === 0) {
      throw new Error(`Missing local database variable ${name} in .env.example`);
    }
  }

  return Object.fromEntries(required.map((name) => [name, parsed.get(name)]));
}

function attachOutput(child, state) {
  const forward = (stream, target) => {
    stream.on('data', (chunk) => {
      const text = chunk.toString('utf8');
      target.write(text);
      state.recentOutput = `${state.recentOutput}${text}`.slice(-65_536);
      const match = state.recentOutput.match(/PURCHASE_PAYMENT_DEMO_SEED_APPLIED[^\r\n]*/u);
      if (match) state.seedLine = match[0];
    });
  };
  forward(child.stdout, process.stdout);
  forward(child.stderr, process.stderr);
}

async function readHealth() {
  try {
    const response = await fetch(healthUrl, {
      headers: { Accept: 'application/json' },
      signal: AbortSignal.timeout(2_000),
    });
    const body = await response.text();
    if (!response.ok) return { ready: false, detail: `HTTP ${response.status}: ${body}` };
    const payload = JSON.parse(body);
    return {
      ready: payload?.status === 'UP',
      detail: body,
    };
  } catch (error) {
    return { ready: false, detail: error.message };
  }
}

async function waitForBackend(child, state, isStopping) {
  const deadline = Date.now() + backendTimeoutMs;
  let lastHealth = 'not requested';
  while (Date.now() < deadline) {
    if (isStopping()) return false;
    if (state.spawnError) throw state.spawnError;
    if (child.exitCode !== null) {
      throw new Error(`Backend exited before readiness with code ${child.exitCode}`);
    }
    const health = await readHealth();
    lastHealth = health.detail;
    if (health.ready && state.seedLine) return true;
    await delay(1_000);
  }
  throw new Error(
    `Backend did not reach health UP and deterministic seed completion: ${bounded(lastHealth)}`,
  );
}

async function waitForExit(child) {
  if (child.exitCode !== null || child.signalCode !== null) {
    return [child.exitCode, child.signalCode];
  }
  return once(child, 'exit');
}

function terminateChild(child, signal) {
  if (!child.pid || child.exitCode !== null) return;
  try {
    if (process.platform === 'win32') child.kill(signal);
    else process.kill(-child.pid, signal);
  } catch (error) {
    if (error.code !== 'ESRCH') throw error;
  }
}

async function start() {
  runChecked('Read-only workstation preflight', process.execPath, [
    'scripts/product-readiness/demo-preflight.mjs',
  ]);
  runChecked('Start isolated local PostgreSQL and Redis', 'docker', composeArguments(
    'up', '-d', 'postgres', 'redis',
  ));
  await waitForCommand(
    'PostgreSQL',
    'docker',
    composeArguments('exec', '-T', 'postgres', 'pg_isready', '-U', 'approval', '-d', 'approval'),
    (output) => /accepting connections/iu.test(output),
    infrastructureTimeoutMs,
  );
  await waitForCommand(
    'Redis',
    'docker',
    composeArguments('exec', '-T', 'redis', 'redis-cli', 'ping'),
    (output) => /PONG/iu.test(output),
    infrastructureTimeoutMs,
  );
  runChecked('Build Maven reactor for local startup', 'mvn', [
    '-B', '-ntp', '-DskipTests', 'install',
  ]);

  const localDatabaseEnvironment = readLocalDatabaseEnvironment();
  console.log('\n==> Start real backend with explicit local seed');
  const child = spawn(executable('mvn'), [
    '-B',
    '-ntp',
    '-f',
    'apps/server/pom.xml',
    'spring-boot:run',
    '-Dspring-boot.run.profiles=local',
  ], {
    cwd: root,
    env: {
      ...process.env,
      ...localDatabaseEnvironment,
      APPROVAL_DEMO_PURCHASE_PAYMENT_ENABLED: 'true',
    },
    detached: process.platform !== 'win32',
    shell: false,
    stdio: ['inherit', 'pipe', 'pipe'],
  });
  const state = { recentOutput: '', seedLine: null, spawnError: null };
  child.once('error', (error) => {
    state.spawnError = error;
  });
  attachOutput(child, state);

  let stopping = false;
  const requestStop = (signal) => {
    if (stopping) return;
    stopping = true;
    console.log(`\nStopping the attached local backend after ${signal}.`);
    terminateChild(child, 'SIGTERM');
    const forceTimer = setTimeout(() => terminateChild(child, 'SIGKILL'), 10_000);
    forceTimer.unref();
  };
  process.once('SIGINT', () => requestStop('SIGINT'));
  process.once('SIGTERM', () => requestStop('SIGTERM'));

  let ready;
  try {
    ready = await waitForBackend(child, state, () => stopping);
  } catch (error) {
    terminateChild(child, 'SIGTERM');
    const forceTimer = setTimeout(() => terminateChild(child, 'SIGKILL'), 10_000);
    forceTimer.unref();
    throw error;
  }
  if (!ready) {
    await waitForExit(child);
    console.log('DEMO_BACKEND_PROCESS_STOPPED');
    return;
  }

  console.log('\nApproval Platform local demo backend is ready.');
  console.log('DEMO_BACKEND_ONE_COMMAND_STARTED');
  console.log('BACKEND_LOCAL_START_VERIFIED');
  console.log(state.seedLine);
  console.log('QUICK_START_10_MINUTES_NOT_EXECUTED');
  console.log('PURCHASE_APPROVAL_E2E_NOT_EXECUTED');
  console.log(`Health: ${healthUrl}`);
  console.log('Press Ctrl-C to stop the backend. PostgreSQL and Redis remain available.');

  const [code, signal] = await waitForExit(child);
  if (!stopping && code !== 0) {
    throw new Error(`Backend exited with code ${code ?? '<none>'} signal ${signal ?? '<none>'}`);
  }
  console.log('DEMO_BACKEND_PROCESS_STOPPED');
}

function stop() {
  runChecked('Stop isolated local infrastructure without deleting data', 'docker', composeArguments('down'));
  console.log('DEMO_BACKEND_LOCAL_INFRASTRUCTURE_STOPPED');
}

function reset(confirmLocalDataLoss) {
  if (!confirmLocalDataLoss) {
    throw new UsageError(
      'reset requires --confirm-local-data-loss; no Docker command was executed',
    );
  }
  runChecked('Delete disposable local infrastructure and PostgreSQL volume', 'docker', composeArguments(
    'down', '-v', '--remove-orphans',
  ));
  console.log('DEMO_BACKEND_LOCAL_DATA_RESET');
}

class UsageError extends Error {}

async function main() {
  const options = parseArguments(process.argv.slice(2));
  if (options.help) {
    console.log(usage());
    return;
  }
  if (options.command === 'plan') {
    printPlan(options.json);
    return;
  }
  if (options.command === 'stop') {
    stop();
    return;
  }
  if (options.command === 'reset') {
    reset(options.confirmLocalDataLoss);
    return;
  }
  await start();
}

main().catch((error) => {
  console.error(`DEMO_BACKEND_COMMAND_FAILED: ${error.message}`);
  if (error instanceof UsageError) console.error(usage());
  process.exitCode = error instanceof UsageError ? 2 : 1;
});
