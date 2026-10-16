#!/usr/bin/env node

import { spawn, spawnSync } from 'node:child_process';
import { once } from 'node:events';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  recordReusableBuild,
  reusableBuild,
} from './capacity-recovery/demo-backend-build-reuse.mjs';
import {
  backendApplicationArguments,
  backendEnvironment,
  backendReady,
  observeBackendOutput,
  startupContract,
  startupMode,
} from './demo-backend-startup.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const rootPomPath = resolve(root, 'pom.xml');
const composeFile = 'deploy/compose/docker-compose.yml';
const composeProject = 'approval-platform-demo';
const demoMavenProfile = 'product-readiness-demo';
const healthUrl = 'http://localhost:8080/actuator/health';
const infrastructureTimeoutMs = 120_000;
const backendTimeoutMs = 240_000;
const pollIntervalMs = 1_500;
const commands = new Set(['start', 'start-restored', 'plan', 'plan-restored', 'stop', 'reset']);
const capacityBuildReuseVariable = 'APPROVAL_DEMO_CAPACITY_REUSE_BUILD';

class UsageError extends Error {}

function parseArguments(argv) {
  const values = [...argv];
  let command = 'start';
  if (values[0] && !values[0].startsWith('--')) command = values.shift();
  if (!commands.has(command)) throw new UsageError(`Unknown command: ${command}`);

  const flags = new Set(values);
  const allowed = new Set(['--help']);
  if (command === 'plan' || command === 'plan-restored') allowed.add('--json');
  if (command === 'reset') allowed.add('--confirm-local-data-loss');
  const unknown = [...flags].filter(flag => !allowed.has(flag));
  if (unknown.length > 0) {
    throw new UsageError(`Unknown option(s) for ${command}: ${unknown.join(', ')}`);
  }
  return {
    command,
    confirmLocalDataLoss: flags.has('--confirm-local-data-loss'),
    help: flags.has('--help'),
    json: flags.has('--json'),
  };
}

function usage() {
  return `Usage: node scripts/product-readiness/demo-backend.mjs [command] [options]\n\nCommands:\n  start   Run preflight, start PostgreSQL/Redis, build, start the real backend,\n          wait for Actuator UP and the deterministic seed, then remain attached.\n  start-restored  Start preserved local data with demo dependencies but no seed;\n                  wait for health UP and the restore-only startup marker.\n  plan    Print the exact non-destructive seeded startup plan.\n  plan-restored  Print the non-destructive restore-only startup plan.\n  stop    Stop local Compose infrastructure without deleting its volume.\n  reset   Delete the disposable local Compose volume. Requires\n          --confirm-local-data-loss.\n\nOptions:\n  --json                     Machine-readable output for either plan command.\n  --confirm-local-data-loss  Required only for reset.\n  --help                     Show this help.\n\nThis command does not prove the 10-minute Quick Start, a complete approval E2E,\npayment integration, cross-client consistency, capacity, or recovery.`;
}

function rootRevision() {
  if (!existsSync(rootPomPath)) throw new Error('Missing root pom.xml');
  const source = readFileSync(rootPomPath, 'utf8');
  const match = source.match(/<revision>([^<]+)<\/revision>/u);
  const revision = match?.[1]?.trim();
  if (!revision || revision.includes('${')) {
    throw new Error('root pom.xml must declare a concrete revision');
  }
  if (!/^[0-9A-Za-z][0-9A-Za-z._-]*$/u.test(revision)) {
    throw new Error('root pom.xml revision contains unsupported characters');
  }
  return revision;
}

function capacityBuildReuseRequested() {
  const value = process.env[capacityBuildReuseVariable];
  if (value === undefined || value === 'false') return false;
  if (value === 'true') return true;
  throw new Error(
    `${capacityBuildReuseVariable} must be true, false or unset`,
  );
}

function startupPlan(command) {
  const mode = startupMode(command);
  const contract = startupContract(mode);
  const compose = `docker compose --project-name ${composeProject} -f ${composeFile}`;
  const revision = rootRevision();
  const profile = `-P${demoMavenProfile}`;
  return {
    schemaVersion: 1,
    entrypoint: mode === 'seed'
      ? 'pnpm demo:backend:start'
      : 'node scripts/product-readiness/demo-backend.mjs start-restored',
    startupMode: mode,
    seedEnabled: contract.seedEnabled === 'true',
    destructive: false,
    revision,
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
        command: `mvn -B -ntp ${profile} -Drevision=${revision} -DskipTests `
          + '-Dapproval.persistence.tests.skip=true install',
      },
      {
        id: 'backend',
        command: 'APPROVAL_DEMO_PURCHASE_PAYMENT_ENABLED=true '
          + `APPROVAL_DEMO_PURCHASE_PAYMENT_SEED_ENABLED=${contract.seedEnabled} `
          + `mvn -B -ntp ${profile} -Drevision=${revision} -pl :approval-server `
          + 'spring-boot:run -Dspring-boot.run.profiles=local '
          + `-Dspring-boot.run.arguments="${backendApplicationArguments(mode)}"`,
      },
      {
        id: 'health',
        command: `GET ${healthUrl}`,
      },
      {
        id: mode === 'seed' ? 'seed' : 'restore-only-startup',
        command: `wait for ${contract.applicationMarker}`,
      },
    ],
    successMarkers: [
      ...contract.successMarkers,
      contract.applicationMarker,
    ],
    nonClaims: [
      'QUICK_START_10_MINUTES_NOT_EXECUTED',
      'PURCHASE_APPROVAL_E2E_NOT_EXECUTED',
      'PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED',
      'CROSS_CLIENT_RUNTIME_NOT_EXECUTED',
      'PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED',
      ...(mode === 'restore-only' ? [
        'DETERMINISTIC_DEMO_SEED_NOT_EXECUTED',
        'RESTORED_DATA_CONSISTENCY_NOT_VERIFIED',
      ] : []),
    ],
  };
}

function printPlan(command, jsonOutput) {
  const value = startupPlan(command);
  if (jsonOutput) {
    console.log(JSON.stringify(value, null, 2));
    return;
  }
  console.log('Approval Platform demo backend startup plan');
  console.log('Read-only plan: no process, container, database or volume is changed.\n');
  for (const [index, step] of value.steps.entries()) {
    console.log(`${String(index + 1).padStart(2, '0')}. ${step.id}: ${step.command}`);
  }
  console.log('\nExpected runtime markers after a successful real execution:');
  for (const marker of value.successMarkers) console.log(marker);
  console.log('\nExplicit non-claims:');
  for (const marker of value.nonClaims) console.log(marker);
}

function mavenExecutable() {
  return process.platform === 'win32' ? 'mvn.cmd' : 'mvn';
}

function requireSuccessfulProcess(label, result) {
  if (result.error) throw new Error(`${label} could not start: ${result.error.message}`);
  if (result.status !== 0) throw new Error(`${label} failed with exit code ${result.status}`);
}

function runNodeChecked(label, args) {
  console.log(`\n==> ${label}`);
  const result = spawnSync(process.execPath, args, {
    cwd: root,
    env: process.env,
    shell: false,
    stdio: 'inherit',
  });
  requireSuccessfulProcess(label, result);
}

function runDockerChecked(label, args) {
  console.log(`\n==> ${label}`);
  const result = spawnSync('docker', args, {
    cwd: root,
    env: process.env,
    shell: false,
    stdio: 'inherit',
  });
  requireSuccessfulProcess(label, result);
}

function runMavenChecked(label, args) {
  console.log(`\n==> ${label}`);
  const result = spawnSync(mavenExecutable(), args, {
    cwd: root,
    env: process.env,
    shell: false,
    stdio: 'inherit',
  });
  requireSuccessfulProcess(label, result);
}

function runDockerCaptured(args) {
  return spawnSync('docker', args, {
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
  return new Promise(resolvePromise => setTimeout(resolvePromise, milliseconds));
}

async function waitForDockerCommand(label, args, predicate, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  let lastDetail = 'not executed';
  while (Date.now() < deadline) {
    const result = runDockerCaptured(args);
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
  return Object.fromEntries(required.map(name => [name, parsed.get(name)]));
}

function attachOutput(child, state, mode) {
  const forward = (stream, target) => {
    stream.on('data', chunk => {
      const text = chunk.toString('utf8');
      try {
        observeBackendOutput(mode, state, text);
      } catch (error) {
        state.spawnError = error;
        terminateChild(child, 'SIGTERM');
        const forceTimer = setTimeout(() => terminateChild(child, 'SIGKILL'), 10_000);
        forceTimer.unref();
        return;
      }
      target.write(text);
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
    return {
      ready: JSON.parse(body)?.status === 'UP',
      detail: body,
    };
  } catch {
    return { ready: false, detail: 'health endpoint unavailable' };
  }
}

async function waitForBackend(child, state, isStopping, mode) {
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
    if (backendReady(mode, health.ready, state)) return true;
    await delay(1_000);
  }
  throw new Error(
    `Backend did not reach health UP and ${startupContract(mode).applicationMarker}: ${bounded(lastHealth)}`,
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

async function start(command) {
  const mode = startupMode(command);
  const contract = startupContract(mode);
  runNodeChecked('Read-only workstation preflight', [
    'scripts/product-readiness/demo-preflight.mjs',
  ]);
  runDockerChecked(
    'Start isolated local PostgreSQL and Redis',
    composeArguments('up', '-d', 'postgres', 'redis'),
  );
  await waitForDockerCommand(
    'PostgreSQL',
    composeArguments('exec', '-T', 'postgres', 'pg_isready', '-U', 'approval', '-d', 'approval'),
    output => /accepting connections/iu.test(output),
    infrastructureTimeoutMs,
  );
  await waitForDockerCommand(
    'Redis',
    composeArguments('exec', '-T', 'redis', 'redis-cli', 'ping'),
    output => /PONG/iu.test(output),
    infrastructureTimeoutMs,
  );

  const revision = rootRevision();
  const reuseRequested = capacityBuildReuseRequested();
  const existingBuild = reuseRequested
    ? reusableBuild(root, revision)
    : null;
  if (existingBuild) {
    console.log('\n==> Reuse pinned clean-tree Maven reactor build');
    console.log(
      `DEMO_BACKEND_PINNED_BUILD_REUSED commit=${existingBuild.commitSha} `
        + `tree=${existingBuild.treeSha}`,
    );
  } else {
    runMavenChecked('Build Maven reactor for local startup', [
      '-B',
      '-ntp',
      `-P${demoMavenProfile}`,
      `-Drevision=${revision}`,
      '-DskipTests',
      '-Dapproval.persistence.tests.skip=true',
      'install',
    ]);
    if (reuseRequested) {
      const recordedBuild = recordReusableBuild(root, revision);
      if (!recordedBuild) {
        console.log('DEMO_BACKEND_PINNED_BUILD_REUSE_SKIPPED_DIRTY_TREE');
      } else {
        console.log(
          `DEMO_BACKEND_PINNED_BUILD_RECORDED commit=${recordedBuild.commitSha} `
            + `tree=${recordedBuild.treeSha}`,
        );
      }
    }
  }

  const localDatabaseEnvironment = readLocalDatabaseEnvironment();
  console.log(mode === 'seed'
    ? '\n==> Start real backend with explicit local seed'
    : '\n==> Start local backend in restore-only mode without running the seed');
  const child = spawn(mavenExecutable(), [
    '-B',
    '-ntp',
    `-P${demoMavenProfile}`,
    `-Drevision=${revision}`,
    '-pl',
    ':approval-server',
    'spring-boot:run',
    '-Dspring-boot.run.profiles=local',
    `-Dspring-boot.run.arguments=${backendApplicationArguments(mode)}`,
  ], {
    cwd: root,
    env: backendEnvironment(mode, process.env, localDatabaseEnvironment),
    detached: process.platform !== 'win32',
    shell: false,
    stdio: ['inherit', 'pipe', 'pipe'],
  });
  const state = { recentOutput: '', seedLine: null, restoreLine: null, spawnError: null };
  child.once('error', error => {
    state.spawnError = error;
  });
  attachOutput(child, state, mode);

  let stopping = false;
  const requestStop = signal => {
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
    ready = await waitForBackend(child, state, () => stopping, mode);
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
  for (const marker of contract.successMarkers) console.log(marker);
  if (mode === 'seed') console.log(state.seedLine);
  else {
    console.log(state.restoreLine);
    console.log('DETERMINISTIC_DEMO_SEED_NOT_EXECUTED');
    console.log('RESTORED_DATA_CONSISTENCY_NOT_VERIFIED');
  }
  console.log('QUICK_START_10_MINUTES_NOT_EXECUTED');
  console.log('PURCHASE_APPROVAL_E2E_NOT_EXECUTED');
  console.log(`Health: ${healthUrl}`);
  console.log('Press Ctrl-C to stop the backend. PostgreSQL and Redis remain available.');

  const [code, signal] = await waitForExit(child);
  if (state.spawnError) throw state.spawnError;
  if (!stopping && code !== 0) {
    throw new Error(`Backend exited with code ${code ?? '<none>'} signal ${signal ?? '<none>'}`);
  }
  console.log('DEMO_BACKEND_PROCESS_STOPPED');
}

function stop() {
  runDockerChecked(
    'Stop isolated local infrastructure without deleting data',
    composeArguments('down'),
  );
  console.log('DEMO_BACKEND_LOCAL_INFRASTRUCTURE_STOPPED');
}

function reset(confirmLocalDataLoss) {
  if (!confirmLocalDataLoss) {
    throw new UsageError(
      'reset requires --confirm-local-data-loss; no Docker command was executed',
    );
  }
  runDockerChecked(
    'Delete disposable local infrastructure and PostgreSQL volume',
    composeArguments('down', '-v', '--remove-orphans'),
  );
  console.log('DEMO_BACKEND_LOCAL_DATA_RESET');
}

async function main() {
  const options = parseArguments(process.argv.slice(2));
  if (options.help) {
    console.log(usage());
    return;
  }
  if (options.command === 'plan' || options.command === 'plan-restored') {
    printPlan(options.command, options.json);
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
  await start(options.command);
}

main().catch(error => {
  console.error(`DEMO_BACKEND_COMMAND_FAILED: ${error.message}`);
  if (error instanceof UsageError) console.error(usage());
  process.exitCode = error instanceof UsageError ? 2 : 1;
});
