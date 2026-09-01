import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { repositoryRoot } from '../pc-h5-runtime/contract.mjs';
import {
  runIdentifier,
  sha40,
  sourceIdentity,
  writeJson,
} from '../purchase-payment-e2e/contract.mjs';

export { repositoryRoot, runIdentifier, sha40, sourceIdentity, writeJson };

export const quickStartPath = resolve(
  repositoryRoot,
  'config/demo/quick-start.json',
);
export const scenarioPath = resolve(
  repositoryRoot,
  'config/demo/purchase-payment-golden-path.json',
);
export const crossClientPath = resolve(
  repositoryRoot,
  'config/demo/cross-client-local-demo.json',
);
export const outputRoot = resolve(repositoryRoot, '.runtime/quick-start');
export const ledgerPath = resolve(outputRoot, 'consecutive-clean-runs.json');
export const browserEvidenceFile = 'quick-start-browser-evidence.json';
export const pollIntervalMs = 500;
const commands = new Set(['plan', 'start', 'ci']);

export class UsageError extends Error {}

export function usage() {
  return `Usage: node scripts/product-readiness/demo-quickstart.mjs <command> [options]\n\nCommands:\n  plan   Print the bounded one-command Quick Start plan.\n  start  Start the real local demo, prove the seeded request is visible in PC/H5,\n         then remain attached until Ctrl-C.\n  ci     Run two independent clean timed Quick Starts only for a relevant CI change set.\n\nOptions:\n  --json  Machine-readable plan output.\n  --help  Show this help.\n\nThe command uses the existing demo backend and client launchers. It is local-only\nand does not claim WeChat runtime, production deployment, capacity or recovery.`;
}

export function parseArguments(argv) {
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

export function readJson(path) {
  return JSON.parse(readFileSync(path, 'utf8'));
}

function requireText(value, name) {
  if (typeof value !== 'string' || !value.trim()) {
    throw new Error(`${name} must be a non-empty string`);
  }
  return value.trim();
}

function requirePort(value, name) {
  if (!Number.isInteger(value) || value < 1024 || value > 65_535) {
    throw new Error(`${name} must be an integer between 1024 and 65535`);
  }
  return value;
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

export function loadContract() {
  for (const path of [quickStartPath, scenarioPath, crossClientPath]) {
    if (!existsSync(path)) throw new Error(`Quick Start contract is missing ${path}`);
  }
  const quickStart = readJson(quickStartPath);
  const scenario = readJson(scenarioPath);
  const crossClient = readJson(crossClientPath);
  if (quickStart.schemaVersion !== 1) {
    throw new Error('unsupported Quick Start schemaVersion');
  }
  if (quickStart.scenarioManifest
      !== 'config/demo/purchase-payment-golden-path.json'
      || quickStart.crossClientManifest
      !== 'config/demo/cross-client-local-demo.json') {
    throw new Error('Quick Start manifest references are not canonical');
  }
  if (crossClient.scenarioManifest !== quickStart.scenarioManifest) {
    throw new Error('Quick Start and cross-client manifests disagree');
  }
  if (crossClient.tenantId !== scenario.tenant?.id
      || crossClient.businessKey !== scenario.request?.businessKey) {
    throw new Error('Quick Start identity does not match the governed scenario');
  }
  if (!Number.isInteger(quickStart.maximumReadySeconds)
      || quickStart.maximumReadySeconds < 60
      || quickStart.maximumReadySeconds > 600) {
    throw new Error('maximumReadySeconds must be between 60 and 600');
  }
  const health = new URL(requireText(
    quickStart.backendHealthUrl,
    'backendHealthUrl',
  ));
  if (health.protocol !== 'http:'
      || !['127.0.0.1', 'localhost'].includes(health.hostname)
      || health.pathname !== '/actuator/health'
      || health.username
      || health.password
      || health.search
      || health.hash) {
    throw new Error('backendHealthUrl must be the local Actuator health endpoint');
  }

  const clients = {};
  for (const clientName of ['pc', 'h5']) {
    const configured = quickStart.clients?.[clientName];
    const governed = crossClient.clients?.[clientName];
    if (!configured || !governed) {
      throw new Error(`Quick Start client is missing ${clientName}`);
    }
    const actorId = requireText(configured.actorId, `${clientName}.actorId`);
    if (!governed.allowedActors?.includes(actorId)) {
      throw new Error(`${clientName} actor is not governed by cross-client manifest`);
    }
    const route = requireText(configured.route, `${clientName}.route`);
    if (route !== governed.route) {
      throw new Error(`${clientName} route does not match cross-client manifest`);
    }
    const port = requirePort(configured.port, `${clientName}.port`);
    if (port !== governed.defaultPort) {
      throw new Error(`${clientName} port does not match cross-client manifest`);
    }
    clients[clientName] = { actorId, port, route };
  }
  if (clients.pc.actorId !== clients.h5.actorId) {
    throw new Error('Quick Start PC and H5 must show the same governed pending task');
  }

  const expectedClaims = [
    'QUICK_START_10_MINUTES_PASSED',
    'DEMO_BACKEND_READY_PASSED',
    'PC_DEMO_READY_PASSED',
    'H5_DEMO_READY_PASSED',
    'TWO_CONSECUTIVE_CLEAN_QUICK_START_RUNS_PASSED',
  ];
  const claims = requireStringList(
    quickStart.claimsAfterTwoConsecutiveCleanRuns,
    'claimsAfterTwoConsecutiveCleanRuns',
  );
  if (JSON.stringify(claims) !== JSON.stringify(expectedClaims)) {
    throw new Error('Quick Start gated claims are invalid');
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
  const nonClaims = requireStringList(quickStart.nonClaims, 'nonClaims');
  if (JSON.stringify(nonClaims) !== JSON.stringify(expectedNonClaims)) {
    throw new Error('Quick Start non-claims are invalid');
  }
  return {
    clients,
    crossClient,
    healthUrl: health.toString(),
    maximumReadySeconds: quickStart.maximumReadySeconds,
    claims,
    nonClaims,
    scenario,
  };
}

export function clientUrl(clientName, client) {
  const actor = encodeURIComponent(client.actorId);
  if (clientName === 'pc') {
    return `http://127.0.0.1:${client.port}${client.route}?demoOperator=${actor}`;
  }
  return `http://127.0.0.1:${client.port}/?demoOperator=${actor}#${client.route}`;
}

export function plan(contract) {
  return {
    schemaVersion: 1,
    entrypoint: 'pnpm demo:quickstart',
    maximumReadySeconds: contract.maximumReadySeconds,
    tenantId: contract.scenario.tenant.id,
    businessKey: contract.scenario.request.businessKey,
    pcUrl: clientUrl('pc', contract.clients.pc),
    h5Url: clientUrl('h5', contract.clients.h5),
    stages: [
      'delete only the disposable approval-platform-demo volume',
      'reuse demo-backend.mjs for preflight, PostgreSQL, Redis, Maven, Spring Boot, Flowable, health and deterministic Seed',
      'install the generated Vben and UniApp workspaces while the backend starts',
      'reuse demo-client.mjs for the governed PC and H5 roles',
      'wait with bounded polling for backend, PC and H5 readiness',
      'use a real Chromium browser to prove the governed seeded request is visible in both clients',
      'retain source, environment, timing, health, screenshots, trace and cleanup evidence',
      'remain attached until interrupted for the interactive command',
      'clean managed processes, containers, network, ports and disposable data in finally',
    ],
    evidenceRoot: '.runtime/quick-start/<run-id>/',
    claimsAfterTwoConsecutiveCleanRuns: contract.claims,
    nonClaims: contract.nonClaims,
  };
}

export function printPlan(contract, jsonOutput) {
  const value = plan(contract);
  if (jsonOutput) {
    console.log(JSON.stringify(value, null, 2));
    return;
  }
  console.log('Approval Platform 10-minute Quick Start plan');
  console.log(JSON.stringify(value, null, 2));
}
