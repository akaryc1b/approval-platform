#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import {
  appendFileSync,
  existsSync,
  lstatSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  writeFileSync,
} from 'node:fs';
import { cpus, totalmem } from 'node:os';
import {
  dirname,
  extname,
  relative,
  resolve,
  sep,
} from 'node:path';
import { fileURLToPath } from 'node:url';

import { java21Environment } from './pc-h5-runtime/contract.mjs';
import { shouldRunInCi } from './pc-h5-runtime/ci-scope.mjs';
import {
  delay,
  startManagedNode,
  terminateManaged,
  waitForHttp,
  waitForMarker,
} from './pc-h5-runtime/processes.mjs';
import { waitForPortAvailable } from './purchase-payment-e2e/evidence.mjs';

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const contractPath = resolve(repositoryRoot, 'config/demo/quick-start.json');
const outputRoot = resolve(repositoryRoot, '.runtime/quick-start');
const ledgerPath = resolve(outputRoot, 'consecutive-clean-runs.json');
const artifactLog = resolve(repositoryRoot, 'root-install.log');
const commands = new Set(['plan', 'run', 'ci']);
const sha40 = /^[0-9a-f]{40}$/u;
const retainedExtensions = new Set(['.json', '.md', '.png', '.zip']);
const maximumEvidenceFileBytes = 64 * 1024 * 1024;
const maximumEvidenceTotalBytes = 96 * 1024 * 1024;
const envelopeBegin = 'APPROVAL_QUICK_START_EVIDENCE_ENVELOPE_BEGIN';
const envelopeEnd = 'APPROVAL_QUICK_START_EVIDENCE_ENVELOPE_END';

class UsageError extends Error {}

function usage() {
  return `Usage: node scripts/product-readiness/demo-quickstart-final.mjs <command> [options]\n\nCommands:\n  plan  Print the governed, non-destructive Quick Start plan.\n  run   Execute one measured Quick Start and remain attached until interrupted.\n  ci    Execute two independent measured runs only for a relevant CI change set.\n\nOptions:\n  --json  Machine-readable plan output.\n  --help  Show this help.\n\nThe Product Alpha runtime uses real local PostgreSQL, Redis, Spring Boot,\nFlowable, PC, H5 and Chromium. It does not claim WeChat runtime, production\ndeployment/payment, a compatibility matrix, accessibility, capacity, recovery,\nMySQL 8.4 support, or a Release.`;
}

function parseArguments(argv) {
  const values = argv.filter(value => value !== '--');
  const command = values.shift() || 'plan';
  if (!commands.has(command)) throw new UsageError(`Unknown command: ${command}`);
  const flags = new Set(values);
  for (const flag of flags) {
    if (flag !== '--json' && flag !== '--help') {
      throw new UsageError(`Unknown option: ${flag}`);
    }
  }
  if (command !== 'plan' && flags.has('--json')) {
    throw new UsageError('--json is only available for plan');
  }
  return {
    command,
    help: flags.has('--help'),
    json: flags.has('--json'),
  };
}

function readJson(path) {
  return JSON.parse(readFileSync(path, 'utf8'));
}

function writeJson(path, value) {
  writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`, {
    encoding: 'utf8',
    mode: 0o600,
  });
}

function requireText(value, name) {
  if (typeof value !== 'string' || !value.trim()) {
    throw new Error(`${name} must be a non-empty string`);
  }
  return value.trim();
}

function requireStringList(value, name) {
  if (!Array.isArray(value) || value.length === 0) {
    throw new Error(`${name} must be a non-empty array`);
  }
  const normalized = value.map((item, index) =>
    requireText(item, `${name}[${index}]`));
  if (new Set(normalized).size !== normalized.length) {
    throw new Error(`${name} must not contain duplicates`);
  }
  return normalized;
}

function normalizePort(value, name) {
  if (!Number.isInteger(value) || value < 1024 || value > 65_535) {
    throw new Error(`${name} must be between 1024 and 65535`);
  }
  return value;
}

function localHttpUrl(value, expectedPort, expectedPath, name) {
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new Error(`${name} must be an absolute URL`);
  }
  if (url.protocol !== 'http:'
    || !['127.0.0.1', 'localhost', '::1'].includes(url.hostname)
    || Number(url.port || 80) !== expectedPort
    || url.pathname !== expectedPath
    || url.username
    || url.password
    || url.search
    || url.hash) {
    throw new Error(`${name} must be the governed loopback URL`);
  }
  return url.toString().replace(/\/$/u, '');
}

function loadContract() {
  const acceptance = readJson(contractPath);
  const scenarioPath = resolve(repositoryRoot, acceptance.scenarioManifest || '');
  const clientsPath = resolve(repositoryRoot, acceptance.crossClientManifest || '');
  const scenario = readJson(scenarioPath);
  const clients = readJson(clientsPath);
  if (acceptance.schemaVersion !== 1) {
    throw new Error('unsupported Quick Start schemaVersion');
  }
  if (acceptance.scenarioManifest
    !== 'config/demo/purchase-payment-golden-path.json') {
    throw new Error('Quick Start scenarioManifest is not canonical');
  }
  if (acceptance.crossClientManifest
    !== 'config/demo/cross-client-local-demo.json') {
    throw new Error('Quick Start crossClientManifest is not canonical');
  }
  if (clients.scenarioManifest !== acceptance.scenarioManifest
    || clients.tenantId !== scenario.tenant?.id
    || clients.businessKey !== scenario.request?.businessKey) {
    throw new Error('Quick Start manifests do not share one governed scenario');
  }
  const maximumReadySeconds = acceptance.maximumReadySeconds;
  if (!Number.isInteger(maximumReadySeconds)
    || maximumReadySeconds < 60
    || maximumReadySeconds > 600) {
    throw new Error('maximumReadySeconds must be between 60 and 600');
  }
  const backendHealthUrl = localHttpUrl(
    acceptance.backendHealthUrl,
    8080,
    '/actuator/health',
    'backendHealthUrl',
  );
  const resolvedClients = {};
  for (const name of ['pc', 'h5']) {
    const configured = acceptance.clients?.[name];
    const governed = clients.clients?.[name];
    const actorId = requireText(configured?.actorId, `${name}.actorId`);
    const port = normalizePort(configured?.port, `${name}.port`);
    const route = requireText(configured?.route, `${name}.route`);
    if (!governed?.allowedActors?.includes(actorId)) {
      throw new Error(`${name} actor is not governed by the cross-client manifest`);
    }
    if (port !== governed.defaultPort || route !== governed.route) {
      throw new Error(`${name} port or route diverges from the governed client`);
    }
    resolvedClients[name] = { actorId, port, route };
  }
  if (resolvedClients.pc.actorId !== resolvedClients.h5.actorId) {
    throw new Error('PC and H5 must expose the same seeded pending task');
  }
  const expectedClaims = [
    'QUICK_START_10_MINUTES_PASSED',
    'DEMO_BACKEND_READY_PASSED',
    'PC_DEMO_READY_PASSED',
    'H5_DEMO_READY_PASSED',
    'TWO_CONSECUTIVE_CLEAN_QUICK_START_RUNS_PASSED',
  ];
  const claims = requireStringList(
    acceptance.claimsAfterTwoConsecutiveCleanRuns,
    'claimsAfterTwoConsecutiveCleanRuns',
  );
  if (JSON.stringify(claims) !== JSON.stringify(expectedClaims)) {
    throw new Error('Quick Start claims do not match Product Alpha');
  }
  const expectedNonClaims = [
    'WECHAT_DEVTOOLS_RUNTIME_NOT_VERIFIED',
    'WECHAT_PHYSICAL_DEVICE_NOT_VERIFIED',
    'PRODUCTION_DEPLOYMENT_NOT_VERIFIED',
    'PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED',
    'PERFORMANCE_CAPACITY_NOT_VERIFIED',
    'BROWSER_COMPATIBILITY_NOT_VERIFIED',
    'ACCESSIBILITY_NOT_VERIFIED',
    'RPO_RTO_NOT_VERIFIED',
    'MYSQL_8_4_NOT_VERIFIED',
    'RELEASE_NOT_CREATED',
  ];
  const nonClaims = requireStringList(acceptance.nonClaims, 'nonClaims');
  if (JSON.stringify(nonClaims) !== JSON.stringify(expectedNonClaims)) {
    throw new Error('Quick Start non-claims do not match Product Alpha');
  }
  const pc = resolvedClients.pc;
  const h5 = resolvedClients.h5;
  return {
    acceptance,
    scenario,
    clients,
    tenantId: requireText(clients.tenantId, 'tenantId'),
    businessKey: requireText(clients.businessKey, 'businessKey'),
    actorId: pc.actorId,
    maximumReadySeconds,
    maximumReadyMs: maximumReadySeconds * 1000,
    backendHealthUrl,
    backendOrigin: 'http://127.0.0.1:8080',
    pcOrigin: `http://127.0.0.1:${pc.port}`,
    h5Origin: `http://127.0.0.1:${h5.port}`,
    pcUrl: `http://127.0.0.1:${pc.port}${pc.route}?demoOperator=${encodeURIComponent(pc.actorId)}`,
    h5Url: `http://127.0.0.1:${h5.port}/?demoOperator=${encodeURIComponent(h5.actorId)}#${h5.route}`,
    claims,
    nonClaims,
  };
}

function quickStartPlan(contract) {
  return {
    schemaVersion: 1,
    entrypoint: 'pnpm demo:quickstart',
    maximumReadySeconds: contract.maximumReadySeconds,
    scenarioManifest: contract.acceptance.scenarioManifest,
    crossClientManifest: contract.acceptance.crossClientManifest,
    tenantId: contract.tenantId,
    businessKey: contract.businessKey,
    actorId: contract.actorId,
    stages: [
      'run the existing read-only workstation preflight',
      'delete only the disposable approval-platform-demo runtime data',
      'start the existing PostgreSQL, Redis, Spring Boot, Flowable and deterministic seed path',
      'install retained PC and H5 workspaces through existing bootstrap commands',
      'start existing PC and H5 clients as one governed demo actor',
      'open the real PC and H5 pages with system Chromium',
      'retain one visible screenshot for each ready client',
      'measure from command start through browser-visible readiness',
      'remain attached for an explicit local demo session when run interactively',
      'clean managed processes, containers, ports and disposable data in finally',
    ],
    readyUrls: {
      backendHealth: contract.backendHealthUrl,
      pc: contract.pcUrl,
      h5: contract.h5Url,
    },
    evidenceRoot: '.runtime/quick-start/<run-id>/',
    claimsAfterTwoConsecutiveCleanRuns: contract.claims,
    nonClaims: contract.nonClaims,
  };
}

function executable(name) {
  if (process.platform === 'win32') {
    if (name === 'mvn' || name === 'pnpm') return `${name}.cmd`;
    if (name === 'git') return 'git.exe';
  }
  return name;
}

function runCaptured(command, args, environment = process.env, timeoutMs = 30_000) {
  return spawnSync(executable(command), args, {
    cwd: repositoryRoot,
    encoding: 'utf8',
    env: environment,
    shell: false,
    timeout: timeoutMs,
  });
}

function requireSuccess(label, result) {
  if (result.error) throw new Error(`${label} could not start: ${result.error.message}`);
  if (result.status !== 0) {
    const detail = [result.stdout, result.stderr]
      .filter(Boolean)
      .join('\n')
      .trim()
      .slice(-8_000);
    throw new Error(`${label} failed with exit code ${result.status}: ${detail}`);
  }
  return String(result.stdout || result.stderr).trim();
}

function runNodeChecked(label, args, environment, timeoutMs) {
  console.log(`\n==> ${label}`);
  const result = spawnSync(process.execPath, args, {
    cwd: repositoryRoot,
    env: environment,
    shell: false,
    stdio: 'inherit',
    timeout: timeoutMs,
  });
  requireSuccess(label, result);
}

function runPnpmChecked(label, args, environment, timeoutMs) {
  console.log(`\n==> ${label}`);
  const result = spawnSync(
    process.platform === 'win32' ? 'pnpm.cmd' : 'pnpm',
    args,
    {
      cwd: repositoryRoot,
      env: environment,
      shell: false,
      stdio: 'inherit',
      timeout: timeoutMs,
    },
  );
  requireSuccess(label, result);
}

function gitRevision(revision, label) {
  const value = requireSuccess(
    label,
    runCaptured('git', ['rev-parse', '--verify', revision]),
  );
  if (!sha40.test(value)) throw new Error(`${label} is not a 40-character SHA`);
  return value;
}

function configuredExactHead() {
  const eventPath = process.env.GITHUB_EVENT_PATH;
  if (eventPath && existsSync(eventPath)) {
    const candidate = readJson(eventPath)?.pull_request?.head?.sha;
    if (candidate !== undefined && !sha40.test(candidate || '')) {
      throw new Error('pull_request.head.sha is invalid');
    }
    if (sha40.test(candidate || '')) return candidate;
  }
  const candidate = process.env.APPROVAL_DEMO_EXACT_HEAD_SHA || process.env.GITHUB_SHA;
  if (candidate && !sha40.test(candidate)) {
    throw new Error('configured exact Head SHA is invalid');
  }
  return candidate || null;
}

function ensureCommitAvailable(commitSha) {
  const present = runCaptured('git', ['cat-file', '-e', `${commitSha}^{commit}`]);
  if (!present.error && present.status === 0) return;
  requireSuccess(
    'Fetch exact Quick Start Head',
    runCaptured(
      'git',
      ['fetch', '--no-tags', '--depth=1', 'origin', commitSha],
      process.env,
      120_000,
    ),
  );
}

function sourceIdentity() {
  const checkedOutSha = gitRevision('HEAD', 'checked-out revision');
  const checkedOutTreeSha = gitRevision('HEAD^{tree}', 'checked-out tree');
  const commitSha = configuredExactHead() || checkedOutSha;
  ensureCommitAvailable(commitSha);
  const treeSha = gitRevision(`${commitSha}^{tree}`, 'exact Head tree');
  if (checkedOutTreeSha !== treeSha) {
    throw new Error('checked-out source tree does not match the exact Quick Start Head');
  }
  return {
    checkedOutSha,
    checkedOutTreeSha,
    commitSha,
    treeSha,
    sourceTreeMatchesExactHead: true,
  };
}

function firstLine(value) {
  return String(value).split(/\r?\n/u).find(line => line.trim())?.trim() || 'unavailable';
}

function commandVersion(label, command, args) {
  return firstLine(requireSuccess(label, runCaptured(command, args)));
}

function environmentSnapshot() {
  const processors = cpus();
  return {
    schemaVersion: 1,
    evidenceKind: 'QUICK_START_ENVIRONMENT_V1',
    operatingSystem: {
      platform: process.platform,
      architecture: process.arch,
      release: commandVersion('Operating system release', 'uname', ['-a']),
    },
    cpu: {
      logicalCount: processors.length,
      model: processors[0]?.model || 'unavailable',
    },
    memoryBytes: totalmem(),
    tools: {
      node: process.version,
      java: commandVersion('Java version', 'java', ['-version']),
      maven: commandVersion('Maven version', 'mvn', ['-version']),
      pnpm: commandVersion('pnpm version', 'pnpm', ['--version']),
      docker: commandVersion('Docker version', 'docker', ['--version']),
      compose: commandVersion('Docker Compose version', 'docker', ['compose', 'version']),
      git: commandVersion('Git version', 'git', ['--version']),
    },
    capturedAt: new Date().toISOString(),
  };
}

function chromeExecutable() {
  const candidates = [
    process.env.APPROVAL_DEMO_CHROME_PATH,
    '/usr/bin/google-chrome',
    '/usr/bin/google-chrome-stable',
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser',
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/Applications/Chromium.app/Contents/MacOS/Chromium',
    '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge',
    process.env.PROGRAMFILES
      ? resolve(process.env.PROGRAMFILES, 'Google/Chrome/Application/chrome.exe')
      : null,
    process.env['PROGRAMFILES(X86)']
      ? resolve(process.env['PROGRAMFILES(X86)'], 'Google/Chrome/Application/chrome.exe')
      : null,
    process.env.LOCALAPPDATA
      ? resolve(process.env.LOCALAPPDATA, 'Google/Chrome/Application/chrome.exe')
      : null,
  ].filter(Boolean);
  const found = candidates.find(candidate => existsSync(candidate));
  if (!found) {
    throw new Error(
      'no supported Chrome/Chromium executable is available; set APPROVAL_DEMO_CHROME_PATH',
    );
  }
  return found;
}

function remainingTimeout(startedAtMs, maximumReadyMs, minimumMs = 1_000) {
  const remaining = maximumReadyMs - (Date.now() - startedAtMs);
  if (remaining < minimumMs) {
    throw new Error('Quick Start exhausted its 600-second readiness budget');
  }
  return remaining;
}

function resetDisposableData(environment, timeoutMs = 120_000) {
  runNodeChecked(
    'Delete only the disposable local demo runtime data',
    [
      'scripts/product-readiness/demo-backend.mjs',
      'reset',
      '--confirm-local-data-loss',
    ],
    environment,
    timeoutMs,
  );
}

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

async function readHealth(contract) {
  const response = await fetch(contract.backendHealthUrl, {
    headers: { Accept: 'application/json' },
    signal: AbortSignal.timeout(2_000),
  });
  const body = await response.text();
  if (!response.ok) throw new Error(`Actuator health returned HTTP ${response.status}`);
  const parsed = JSON.parse(body);
  if (parsed.status !== 'UP') throw new Error(`Actuator status is ${parsed.status}`);
  return {
    statusCode: response.status,
    body: parsed,
    capturedAt: new Date().toISOString(),
  };
}

function validateBrowserEvidence(path, contract, identity) {
  if (!existsSync(path)) throw new Error('Quick Start browser evidence is unavailable');
  const evidence = readJson(path);
  if (evidence.evidenceKind !== 'QUICK_START_BROWSER_READY_V1'
    || evidence.status !== 'PASSED'
    || evidence.exactHeadSha !== identity.commitSha
    || evidence.tenantId !== contract.tenantId
    || evidence.businessKey !== contract.businessKey
    || evidence.actorId !== contract.actorId
    || evidence.pcUrl !== contract.pcUrl
    || evidence.h5Url !== contract.h5Url) {
    throw new Error('Quick Start browser evidence is inconsistent');
  }
  for (const screenshot of [
    'quick-start-pc-ready.png',
    'quick-start-h5-ready.png',
  ]) {
    if (!existsSync(resolve(dirname(path), screenshot))) {
      throw new Error(`Quick Start did not retain ${screenshot}`);
    }
  }
  return evidence;
}

async function waitForStopSignal(managed) {
  return new Promise((resolvePromise, reject) => {
    let settled = false;
    const finish = signal => {
      if (settled) return;
      settled = true;
      resolvePromise(signal);
    };
    process.once('SIGINT', () => finish('SIGINT'));
    process.once('SIGTERM', () => finish('SIGTERM'));
    for (const processState of managed) {
      processState.child.once('exit', (code, signal) => {
        if (settled) return;
        settled = true;
        reject(new Error(
          `${processState.label} exited unexpectedly: code=${code} signal=${signal}`,
        ));
      });
    }
  });
}

async function cleanupRuntime(managed, environment, runDirectory) {
  const actions = [];
  for (const processState of managed.reverse()) {
    await stopManaged(processState);
    actions.push(`stopped:${processState.label}`);
  }
  resetDisposableData(environment);
  actions.push('deleted:approval-platform-demo-volume');
  for (const port of [5432, 5777, 6379, 8080, 9000]) {
    await waitForPortAvailable(port);
    actions.push(`released-port:${port}`);
  }
  const evidence = {
    schemaVersion: 1,
    evidenceKind: 'QUICK_START_CLEANUP_V1',
    actions,
    completedAt: new Date().toISOString(),
  };
  writeJson(resolve(runDirectory, 'cleanup-evidence.json'), evidence);
  return evidence;
}

function emptyLedger(identity) {
  return {
    schemaVersion: 1,
    evidenceKind: 'QUICK_START_CONSECUTIVE_CLEAN_RUNS_V1',
    commitSha: identity.commitSha,
    treeSha: identity.treeSha,
    successfulRunIds: [],
  };
}

function readLedger(identity) {
  if (!existsSync(ledgerPath)) return emptyLedger(identity);
  const value = readJson(ledgerPath);
  if (value.commitSha !== identity.commitSha || value.treeSha !== identity.treeSha) {
    return emptyLedger(identity);
  }
  return value;
}

function resetLedger(identity, failureRunId) {
  mkdirSync(outputRoot, { recursive: true, mode: 0o700 });
  writeJson(ledgerPath, {
    ...emptyLedger(identity),
    failureRunId,
    resetAt: new Date().toISOString(),
  });
}

function nextSuccessfulLedger(identity, runId) {
  const current = readLedger(identity);
  const successfulRunIds = [...current.successfulRunIds, runId]
    .filter((value, index, values) => values.indexOf(value) === index)
    .slice(-2);
  return {
    ...emptyLedger(identity),
    successfulRunIds,
    updatedAt: new Date().toISOString(),
  };
}

function collectEvidence(directory, files = []) {
  for (const name of readdirSync(directory).sort()) {
    const target = resolve(directory, name);
    const metadata = lstatSync(target);
    if (metadata.isSymbolicLink()) {
      throw new Error(`Quick Start evidence must not contain symlinks: ${target}`);
    }
    if (metadata.isDirectory()) {
      collectEvidence(target, files);
      continue;
    }
    if (metadata.isFile() && retainedExtensions.has(extname(name).toLowerCase())) {
      files.push(target);
    }
  }
  return files;
}

function appendCiEvidenceEnvelope(status, runDirectory, identity) {
  if (process.env.GITHUB_ACTIONS !== 'true') return;
  if (!existsSync(artifactLog)) {
    throw new Error('root-install.log is unavailable for Quick Start evidence retention');
  }
  let totalBytes = 0;
  const files = collectEvidence(runDirectory).map((target) => {
    const content = readFileSync(target);
    if (content.length > maximumEvidenceFileBytes) {
      throw new Error(`Quick Start evidence file is too large: ${target}`);
    }
    totalBytes += content.length;
    if (totalBytes > maximumEvidenceTotalBytes) {
      throw new Error('Quick Start retained evidence exceeds the bounded total size');
    }
    const path = relative(runDirectory, target).split(sep).join('/');
    if (!path || path.startsWith('../') || path.includes('/../')) {
      throw new Error(`Quick Start evidence escaped its run directory: ${target}`);
    }
    return {
      path,
      size: content.length,
      sha256: createHash('sha256').update(content).digest('hex'),
      base64: content.toString('base64'),
    };
  });
  if (status === 'PASSED') {
    for (const required of [
      'source-identity.json',
      'environment.json',
      'backend-health.json',
      'quick-start-browser-evidence.json',
      'quick-start-pc-ready.png',
      'quick-start-h5-ready.png',
      'startup-evidence.json',
      'cleanup-evidence.json',
      'runtime-summary.json',
    ]) {
      if (!files.some(file => file.path === required)) {
        throw new Error(`passed Quick Start did not retain ${required}`);
      }
    }
    if (!files.some(file => file.path.endsWith('/trace.zip'))) {
      throw new Error('passed Quick Start did not retain Playwright trace.zip');
    }
  }
  const envelope = {
    schemaVersion: 1,
    evidenceKind: 'QUICK_START_CI_ARTIFACT_ENVELOPE_V1',
    status,
    commitSha: identity.commitSha,
    treeSha: identity.treeSha,
    githubRunId: process.env.GITHUB_RUN_ID || null,
    capturedAt: new Date().toISOString(),
    totalBytes,
    files,
  };
  appendFileSync(
    artifactLog,
    `\n${envelopeBegin}\n${JSON.stringify(envelope)}\n${envelopeEnd}\n`,
    'utf8',
  );
}

async function execute(keepAlive) {
  const contract = loadContract();
  const identity = sourceIdentity();
  const startedAt = new Date();
  const startedAtMs = startedAt.getTime();
  const runId = `${startedAt.toISOString().replace(/[:.]/gu, '-')}-${randomUUID().slice(0, 8)}`;
  const runDirectory = resolve(outputRoot, runId);
  mkdirSync(runDirectory, { recursive: true, mode: 0o700 });
  writeJson(resolve(runDirectory, 'source-identity.json'), {
    schemaVersion: 1,
    evidenceKind: 'QUICK_START_SOURCE_IDENTITY_V1',
    runId,
    capturedAt: startedAt.toISOString(),
    ...identity,
  });
  writeJson(resolve(runDirectory, 'acceptance-contract.json'), contract.acceptance);
  writeJson(resolve(runDirectory, 'environment.json'), environmentSnapshot());

  const environment = java21Environment();
  const managed = [];
  let executionError;
  let cleanupError;
  let cleanupEvidence;
  let startupEvidence;
  try {
    resetDisposableData(environment);
    const backend = startManagedNode(
      'Start the existing local backend and deterministic seed',
      ['scripts/product-readiness/demo-backend.mjs', 'start'],
      resolve(runDirectory, 'backend.log'),
      environment,
    );
    managed.push(backend);

    runPnpmChecked(
      'Install the retained PC workspace',
      ['web:install'],
      process.env,
      remainingTimeout(startedAtMs, contract.maximumReadyMs),
    );
    runPnpmChecked(
      'Install the retained H5 workspace',
      ['mobile:install'],
      process.env,
      remainingTimeout(startedAtMs, contract.maximumReadyMs),
    );

    await waitForMarker(
      backend,
      'BACKEND_LOCAL_START_VERIFIED',
      remainingTimeout(startedAtMs, contract.maximumReadyMs),
    );
    await waitForMarker(
      backend,
      'PURCHASE_PAYMENT_DEMO_SEED_APPLIED',
      remainingTimeout(startedAtMs, contract.maximumReadyMs),
    );
    const health = await readHealth(contract);
    writeJson(resolve(runDirectory, 'backend-health.json'), health);

    const pc = startManagedNode(
      'Start the existing PC workbench as the governed Quick Start actor',
      [
        'scripts/product-readiness/demo-client.mjs',
        'pc',
        '--actor',
        contract.actorId,
        '--port',
        '5777',
        '--skip-install',
      ],
      resolve(runDirectory, 'pc.log'),
      process.env,
    );
    managed.push(pc);
    const h5 = startManagedNode(
      'Start the existing H5 approval center as the governed Quick Start actor',
      [
        'scripts/product-readiness/demo-client.mjs',
        'h5',
        '--actor',
        contract.actorId,
        '--port',
        '9000',
        '--skip-install',
      ],
      resolve(runDirectory, 'h5.log'),
      process.env,
    );
    managed.push(h5);
    await Promise.all([
      waitForHttp(
        `${contract.pcOrigin}/`,
        remainingTimeout(startedAtMs, contract.maximumReadyMs),
      ),
      waitForHttp(
        `${contract.h5Origin}/`,
        remainingTimeout(startedAtMs, contract.maximumReadyMs),
      ),
    ]);

    runPnpmChecked(
      'Verify visible PC and H5 readiness with system Chromium',
      [
        '--dir',
        '.upstream/vben',
        '--filter',
        '@vben/playground',
        'exec',
        'playwright',
        'test',
        '--config=product-readiness.playwright.config.ts',
        '__tests__/e2e/product-readiness-quick-start-ready-v2.spec.ts',
      ],
      {
        ...process.env,
        APPROVAL_DEMO_BACKEND_ORIGIN: contract.backendOrigin,
        APPROVAL_DEMO_CHROME_PATH: chromeExecutable(),
        APPROVAL_DEMO_EVIDENCE_DIR: runDirectory,
        APPROVAL_DEMO_EXACT_HEAD_SHA: identity.commitSha,
        APPROVAL_DEMO_H5_URL: contract.h5Url,
        APPROVAL_DEMO_PC_URL: contract.pcUrl,
        APPROVAL_DEMO_PLAYWRIGHT_TIMEOUT_MS: String(
          remainingTimeout(startedAtMs, contract.maximumReadyMs),
        ),
        APPROVAL_DEMO_REPOSITORY_ROOT: repositoryRoot,
        APPROVAL_QUICK_START_ACTOR_ID: contract.actorId,
        APPROVAL_QUICK_START_BUSINESS_KEY: contract.businessKey,
        APPROVAL_QUICK_START_TENANT_ID: contract.tenantId,
      },
      remainingTimeout(startedAtMs, contract.maximumReadyMs),
    );
    const browserEvidence = validateBrowserEvidence(
      resolve(runDirectory, 'quick-start-browser-evidence.json'),
      contract,
      identity,
    );

    const readyAt = new Date();
    const elapsedSeconds = (readyAt.getTime() - startedAtMs) / 1000;
    if (elapsedSeconds > contract.maximumReadySeconds) {
      throw new Error(`Quick Start exceeded ${contract.maximumReadySeconds} seconds`);
    }
    startupEvidence = {
      schemaVersion: 1,
      evidenceKind: 'QUICK_START_STARTUP_V1',
      runId,
      commitSha: identity.commitSha,
      treeSha: identity.treeSha,
      tenantId: contract.tenantId,
      businessKey: contract.businessKey,
      actorId: contract.actorId,
      startedAt: startedAt.toISOString(),
      readyAt: readyAt.toISOString(),
      elapsedSeconds,
      maximumReadySeconds: contract.maximumReadySeconds,
      health,
      browserEvidence,
      urls: {
        backendHealth: contract.backendHealthUrl,
        pc: contract.pcUrl,
        h5: contract.h5Url,
      },
    };
    writeJson(resolve(runDirectory, 'startup-evidence.json'), startupEvidence);

    console.log('\nApproval Platform Product Alpha Quick Start is ready.');
    console.log(`QUICK_START_RUN_ID=${runId}`);
    console.log(`QUICK_START_READY_SECONDS=${elapsedSeconds.toFixed(3)}`);
    console.log(`QUICK_START_TENANT=${contract.tenantId}`);
    console.log(`QUICK_START_BUSINESS_KEY=${contract.businessKey}`);
    console.log(`QUICK_START_ACTOR=${contract.actorId}`);
    console.log(`QUICK_START_PC_URL=${contract.pcUrl}`);
    console.log(`QUICK_START_H5_URL=${contract.h5Url}`);
    if (keepAlive) {
      console.log('Press Ctrl-C to stop and clean the disposable local demo.');
      await waitForStopSignal(managed);
    }
  } catch (error) {
    executionError = error;
  } finally {
    try {
      cleanupEvidence = await cleanupRuntime(
        managed,
        environment,
        runDirectory,
      );
    } catch (error) {
      cleanupError = error;
    }
  }

  if (executionError || cleanupError) {
    resetLedger(identity, runId);
    writeJson(resolve(runDirectory, 'runtime-failure.json'), {
      schemaVersion: 1,
      evidenceKind: 'QUICK_START_FAILURE_V1',
      runId,
      execution: executionError instanceof Error
        ? executionError.message
        : executionError ? String(executionError) : null,
      cleanup: cleanupError instanceof Error
        ? cleanupError.message
        : cleanupError ? String(cleanupError) : null,
      failedAt: new Date().toISOString(),
    });
    appendCiEvidenceEnvelope('FAILED', runDirectory, identity);
    throw new Error(
      `Quick Start failed: ${JSON.stringify({
        execution: executionError instanceof Error ? executionError.message : null,
        cleanup: cleanupError instanceof Error ? cleanupError.message : null,
      })}`,
      { cause: executionError || cleanupError },
    );
  }

  const ledger = nextSuccessfulLedger(identity, runId);
  const claimsDeclared = ledger.successfulRunIds.length >= 2;
  const summary = {
    schemaVersion: 1,
    evidenceKind: 'QUICK_START_RUNTIME_SUMMARY_V1',
    status: 'PASSED',
    runId,
    commitSha: identity.commitSha,
    treeSha: identity.treeSha,
    tenantId: contract.tenantId,
    businessKey: contract.businessKey,
    actorId: contract.actorId,
    elapsedSeconds: startupEvidence.elapsedSeconds,
    maximumReadySeconds: contract.maximumReadySeconds,
    urls: startupEvidence.urls,
    cleanup: cleanupEvidence,
    successfulRunIds: ledger.successfulRunIds,
    claimsDeclared,
    claims: claimsDeclared ? contract.claims : [],
    nonClaims: contract.nonClaims,
    completedAt: new Date().toISOString(),
  };
  try {
    writeJson(resolve(runDirectory, 'runtime-summary.json'), summary);
    writeJson(ledgerPath, ledger);
    appendCiEvidenceEnvelope('PASSED', runDirectory, identity);
  } catch (error) {
    resetLedger(identity, runId);
    throw error;
  }

  console.log(`QUICK_START_EVIDENCE=${runDirectory}`);
  if (!claimsDeclared) {
    console.log('QUICK_START_FIRST_CLEAN_RUN_RECORDED');
    console.log('TWO_CONSECUTIVE_CLEAN_QUICK_START_RUNS_REQUIRED');
  } else {
    for (const claim of contract.claims) console.log(claim);
  }
  for (const nonClaim of contract.nonClaims) console.log(nonClaim);
}

async function main() {
  const options = parseArguments(process.argv.slice(2));
  if (options.help) {
    console.log(usage());
    return;
  }
  const contract = loadContract();
  if (options.command === 'plan') {
    console.log(JSON.stringify(quickStartPlan(contract), null, 2));
    return;
  }
  if (options.command === 'ci') {
    if (!shouldRunInCi()) return;
    await execute(false);
    console.log('QUICK_START_SECOND_CLEAN_RUN_STARTING');
    await execute(false);
    return;
  }
  await execute(true);
}

main().catch((error) => {
  const detail = error instanceof Error ? error.message : String(error);
  console.error(`DEMO_QUICK_START_FAILED: ${detail}`);
  if (error instanceof UsageError) console.error(usage());
  process.exitCode = error instanceof UsageError ? 2 : 1;
});
