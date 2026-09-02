import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import {
  repositoryRoot,
  runIdentifier,
  sourceIdentity,
  writeJson,
} from '../purchase-payment-e2e/contract.mjs';

export { repositoryRoot, runIdentifier, sourceIdentity, writeJson };

export const manifestPath = resolve(
  repositoryRoot,
  'config/demo/capacity-recovery.json',
);
export const scenarioPath = resolve(
  repositoryRoot,
  'config/demo/purchase-payment-golden-path.json',
);
export const outputRoot = resolve(
  repositoryRoot,
  '.runtime/capacity-recovery',
);
export const recoveryOutputRoot = resolve(
  repositoryRoot,
  '.runtime/purchase-payment-e2e',
);
export const backendOrigin = 'http://127.0.0.1:8080';
export const composeFile = 'deploy/compose/docker-compose.yml';
export const composeProject = 'approval-platform-demo';

const allowedCommands = new Set(['plan', 'run', 'ci']);
const requiredProfileIds = [
  'small-demo',
  'standard-deployment',
  'large-tenant',
];
const requiredClaims = [
  'SMALL_DEMO_CAPACITY_BASELINE_PASSED',
  'SMALL_DEMO_CONCURRENT_PURCHASE_FLOW_PASSED',
  'SMALL_DEMO_READ_PRESSURE_PASSED',
  'OUTBOX_CONNECTOR_RECOVERY_REUSED_AND_MEASURED',
  'CAPACITY_RECOVERY_INITIAL_SLICE_PUBLISHED',
];
const requiredNonClaims = [
  'STANDARD_DEPLOYMENT_CAPACITY_NOT_VERIFIED',
  'LARGE_TENANT_CAPACITY_NOT_VERIFIED',
  'PRODUCTION_CAPACITY_NOT_VERIFIED',
  'PEAK_RESOURCE_ENVELOPE_NOT_VERIFIED',
  'MULTI_NODE_CAPACITY_NOT_VERIFIED',
  'UPGRADE_REHEARSAL_NOT_VERIFIED',
  'BACKUP_RESTORE_NOT_VERIFIED',
  'RPO_RTO_NOT_VERIFIED',
  'MYSQL_8_4_NOT_VERIFIED',
  'PRODUCTION_DEPLOYMENT_NOT_VERIFIED',
  'RELEASE_NOT_CREATED',
];

export class UsageError extends Error {}

function requireString(value, label) {
  if (typeof value !== 'string' || value.trim().length === 0) {
    throw new Error(`${label} is required`);
  }
  return value.trim();
}

function requireInteger(value, label, minimum, maximum) {
  if (!Number.isInteger(value) || value < minimum || value > maximum) {
    throw new Error(`${label} must be between ${minimum} and ${maximum}`);
  }
  return value;
}

function requireFinite(value, label, minimum, maximum) {
  if (!Number.isFinite(value) || value < minimum || value > maximum) {
    throw new Error(`${label} must be between ${minimum} and ${maximum}`);
  }
  return value;
}

function requireExactList(value, expected, label) {
  if (JSON.stringify(value) !== JSON.stringify(expected)) {
    throw new Error(`${label} must be ${expected.join(', ')}`);
  }
  return value;
}

export function readJson(path) {
  return JSON.parse(readFileSync(path, 'utf8'));
}

export function loadContract() {
  for (const path of [manifestPath, scenarioPath]) {
    if (!existsSync(path)) throw new Error(`capacity contract is missing ${path}`);
  }
  const manifest = readJson(manifestPath);
  const scenario = readJson(scenarioPath);
  if (manifest.schemaVersion !== 1) {
    throw new Error('capacity recovery schemaVersion must be 1');
  }
  if (manifest.scenarioSource
      !== 'config/demo/purchase-payment-golden-path.json'
      || manifest.quickStartSource !== 'config/demo/quick-start.json') {
    throw new Error('capacity recovery sources must reuse the governed product manifests');
  }
  if (manifest.databaseVendor !== 'PostgreSQL 16') {
    throw new Error('initial capacity target must remain PostgreSQL 16');
  }
  requireInteger(manifest.applicationInstances, 'applicationInstances', 1, 1);
  const maximumRuntimeSeconds = requireInteger(
    manifest.maximumRuntimeSeconds,
    'maximumRuntimeSeconds',
    300,
    3600,
  );
  const profileIds = manifest.profiles?.map(profile => profile.id);
  requireExactList(profileIds, requiredProfileIds, 'profile IDs');
  const smallDemo = manifest.profiles[0];
  if (smallDemo.status !== 'EXECUTABLE') {
    throw new Error('Small Demo must be the executable initial profile');
  }
  for (const profile of manifest.profiles.slice(1)) {
    if (profile.status !== 'PLANNED') {
      throw new Error(`${profile.id} must remain PLANNED in the initial slice`);
    }
  }
  const workload = smallDemo.workload;
  const normalizedWorkload = {
    generatedInstances: requireInteger(
      workload?.generatedInstances,
      'small-demo.workload.generatedInstances',
      2,
      50,
    ),
    startConcurrency: requireInteger(
      workload?.startConcurrency,
      'small-demo.workload.startConcurrency',
      1,
      10,
    ),
    approvalConcurrency: requireInteger(
      workload?.approvalConcurrency,
      'small-demo.workload.approvalConcurrency',
      1,
      20,
    ),
    readRequests: requireInteger(
      workload?.readRequests,
      'small-demo.workload.readRequests',
      10,
      1000,
    ),
    readConcurrency: requireInteger(
      workload?.readConcurrency,
      'small-demo.workload.readConcurrency',
      1,
      50,
    ),
    requestTimeoutMs: requireInteger(
      workload?.requestTimeoutMs,
      'small-demo.workload.requestTimeoutMs',
      1000,
      30000,
    ),
    stateTimeoutMs: requireInteger(
      workload?.stateTimeoutMs,
      'small-demo.workload.stateTimeoutMs',
      10000,
      300000,
    ),
  };
  const thresholds = {
    maximumErrorRate: requireFinite(
      smallDemo.thresholds?.maximumErrorRate,
      'small-demo.thresholds.maximumErrorRate',
      0,
      1,
    ),
    maximumReadP95Ms: requireFinite(
      smallDemo.thresholds?.maximumReadP95Ms,
      'small-demo.thresholds.maximumReadP95Ms',
      1,
      30000,
    ),
    maximumReadP99Ms: requireFinite(
      smallDemo.thresholds?.maximumReadP99Ms,
      'small-demo.thresholds.maximumReadP99Ms',
      1,
      30000,
    ),
    minimumReadThroughputPerSecond: requireFinite(
      smallDemo.thresholds?.minimumReadThroughputPerSecond,
      'small-demo.thresholds.minimumReadThroughputPerSecond',
      0.001,
      100000,
    ),
    minimumCompletedFlowsPerSecond: requireFinite(
      smallDemo.thresholds?.minimumCompletedFlowsPerSecond,
      'small-demo.thresholds.minimumCompletedFlowsPerSecond',
      0.001,
      100000,
    ),
  };
  if (thresholds.maximumReadP99Ms < thresholds.maximumReadP95Ms) {
    throw new Error('maximumReadP99Ms must not be lower than maximumReadP95Ms');
  }
  if (manifest.recovery?.entrypoint
      !== 'pnpm demo:runtime:purchase-payment:e2e'
      || manifest.recovery?.requiredEvidenceKind
      !== 'PURCHASE_PAYMENT_LOCAL_ALPHA_E2E_V1') {
    throw new Error('capacity recovery must reuse the accepted purchase-payment E2E');
  }
  const maximumObservedDrainSeconds = requireInteger(
    manifest.recovery.maximumObservedDrainSeconds,
    'recovery.maximumObservedDrainSeconds',
    1,
    600,
  );
  requireExactList(manifest.claims, requiredClaims, 'claims');
  requireExactList(manifest.nonClaims, requiredNonClaims, 'nonClaims');
  return {
    ...manifest,
    maximumRuntimeSeconds,
    scenario,
    smallDemo: {
      ...smallDemo,
      workload: normalizedWorkload,
      thresholds,
    },
    recovery: {
      ...manifest.recovery,
      maximumObservedDrainSeconds,
    },
  };
}

export function parseArguments(args) {
  const values = args.filter(value => value !== '--');
  const command = values.find(value => !value.startsWith('-')) || 'run';
  const options = { command, help: false, json: false };
  for (const value of values) {
    if (value === command) continue;
    if (value === '--help' || value === '-h') options.help = true;
    else if (value === '--json') options.json = true;
    else throw new UsageError(`unknown option: ${value}`);
  }
  if (!allowedCommands.has(command)) {
    throw new UsageError(`unknown command: ${command}`);
  }
  if (command !== 'plan' && options.json) {
    throw new UsageError('--json is only available for plan');
  }
  return options;
}

export function usage() {
  return [
    'Usage: node scripts/product-readiness/capacity-recovery.mjs <command>',
    '',
    'Commands:',
    '  plan   Print the governed capacity/recovery plan without starting a runtime',
    '  run    Execute the Small Demo capacity slice and accepted recovery reuse',
    '  ci     Execute only when the existing product-readiness path scope selects it',
    '',
    'Options:',
    '  --json Print the plan as JSON',
    '  --help Show this help',
  ].join('\n');
}

export function plan(contract) {
  return {
    schemaVersion: 1,
    entrypoint: 'pnpm demo:runtime:capacity-recovery',
    databaseVendor: contract.databaseVendor,
    applicationInstances: contract.applicationInstances,
    maximumRuntimeSeconds: contract.maximumRuntimeSeconds,
    profiles: contract.profiles,
    executableProfile: contract.smallDemo.id,
    scenario: {
      tenantId: requireString(contract.scenario.tenant?.id, 'scenario tenant.id'),
      businessKeyPrefix:
        `${requireString(contract.scenario.request?.businessKey, 'scenario request.businessKey')}-CAP-`,
      processDefinition: 'purchase-payment',
    },
    stages: [
      'reuse demo-backend.mjs for PostgreSQL, Redis, Spring Boot, Flowable and deterministic Seed',
      'create bounded attachments and purchase-payment instances through public HTTP APIs',
      'measure concurrent starts, pending task list/detail reads and the real approval chain',
      'exercise parallel finance countersign tasks with bounded concurrency',
      'capture exact source, host, process and PostgreSQL observations before and after the workload',
      'reuse the accepted purchase-payment E2E for real 503, Outbox PENDING, recovery and DELIVERED evidence',
      'write machine-readable evidence under .runtime/capacity-recovery/<run-id>/',
      'clean managed processes, ports, containers and disposable data in finally',
    ],
    evidenceRoot: '.runtime/capacity-recovery/<run-id>/',
    claims: contract.claims,
    nonClaims: contract.nonClaims,
  };
}

export function printPlan(contract, jsonOutput) {
  const value = plan(contract);
  console.log(jsonOutput
    ? JSON.stringify(value, null, 2)
    : `Approval Platform capacity/recovery plan\n${JSON.stringify(value, null, 2)}`);
}
