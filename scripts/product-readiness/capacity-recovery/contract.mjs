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
const requiredStatuses = [
  'EXECUTABLE_INITIAL',
  'EXECUTABLE_EXTENDED',
  'EXECUTABLE_EXTENDED',
];
const requiredClaims = [
  'SMALL_DEMO_CAPACITY_BASELINE_PASSED',
  'SMALL_DEMO_CONCURRENT_PURCHASE_FLOW_PASSED',
  'SMALL_DEMO_READ_PRESSURE_PASSED',
  'OUTBOX_CONNECTOR_RECOVERY_REUSED_AND_MEASURED',
  'CAPACITY_RECOVERY_INITIAL_SLICE_PUBLISHED',
];
const requiredExtendedClaims = [
  'STANDARD_DEPLOYMENT_LOCAL_REFERENCE_PASSED',
  'LARGE_TENANT_LOCAL_REFERENCE_PASSED',
  'MULTI_INSTANCE_APPROVAL_THROUGHPUT_MEASURED',
  'OUTBOX_BACKLOG_CREATION_VOLUME_MEASURED',
  'BEYOND_CONFIGURED_READ_POINT_OBSERVED',
  'CAPACITY_PROFILE_MATRIX_PUBLISHED',
];
const requiredNonClaims = [
  'PRODUCTION_CAPACITY_NOT_VERIFIED',
  'MAXIMUM_STABLE_ENVELOPE_NOT_VERIFIED',
  'PEAK_RESOURCE_ENVELOPE_NOT_VERIFIED',
  'MULTI_NODE_CAPACITY_NOT_VERIFIED',
  'OUTBOX_CONNECTOR_BACKLOG_DRAIN_VOLUME_NOT_VERIFIED',
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

function normalizeThresholds(profile) {
  const label = profile.id;
  const thresholds = {
    maximumErrorRate: requireFinite(
      profile.thresholds?.maximumErrorRate,
      `${label}.thresholds.maximumErrorRate`,
      0,
      1,
    ),
    maximumReadP95Ms: requireFinite(
      profile.thresholds?.maximumReadP95Ms,
      `${label}.thresholds.maximumReadP95Ms`,
      1,
      30000,
    ),
    maximumReadP99Ms: requireFinite(
      profile.thresholds?.maximumReadP99Ms,
      `${label}.thresholds.maximumReadP99Ms`,
      1,
      30000,
    ),
    minimumReadThroughputPerSecond: requireFinite(
      profile.thresholds?.minimumReadThroughputPerSecond,
      `${label}.thresholds.minimumReadThroughputPerSecond`,
      0.001,
      100000,
    ),
    minimumCompletedFlowsPerSecond: requireFinite(
      profile.thresholds?.minimumCompletedFlowsPerSecond,
      `${label}.thresholds.minimumCompletedFlowsPerSecond`,
      0.001,
      100000,
    ),
  };
  if (thresholds.maximumReadP99Ms < thresholds.maximumReadP95Ms) {
    throw new Error(`${label} maximumReadP99Ms must not be lower than maximumReadP95Ms`);
  }
  return thresholds;
}

function normalizeProfile(profile, index) {
  const label = requiredString(profile.id, `profiles[${index}].id`);
  const extended = index > 0;
  const workload = {
    generatedInstances: requireInteger(
      profile.workload?.generatedInstances,
      `${label}.workload.generatedInstances`,
      2,
      500,
    ),
    startConcurrency: requireInteger(
      profile.workload?.startConcurrency,
      `${label}.workload.startConcurrency`,
      1,
      64,
    ),
    approvalConcurrency: requireInteger(
      profile.workload?.approvalConcurrency,
      `${label}.workload.approvalConcurrency`,
      1,
      64,
    ),
    readRequests: requireInteger(
      profile.workload?.readRequests,
      `${label}.workload.readRequests`,
      10,
      10000,
    ),
    readConcurrency: requireInteger(
      profile.workload?.readConcurrency,
      `${label}.workload.readConcurrency`,
      1,
      128,
    ),
    requestTimeoutMs: requireInteger(
      profile.workload?.requestTimeoutMs,
      `${label}.workload.requestTimeoutMs`,
      1000,
      30000,
    ),
    stateTimeoutMs: requireInteger(
      profile.workload?.stateTimeoutMs,
      `${label}.workload.stateTimeoutMs`,
      10000,
      600000,
    ),
  };
  if (workload.startConcurrency > workload.generatedInstances
      || workload.approvalConcurrency > workload.generatedInstances) {
    throw new Error(`${label} concurrency must not exceed generatedInstances`);
  }
  if (extended) {
    workload.overloadReadRequests = requireInteger(
      profile.workload?.overloadReadRequests,
      `${label}.workload.overloadReadRequests`,
      10,
      10000,
    );
    workload.overloadReadConcurrency = requireInteger(
      profile.workload?.overloadReadConcurrency,
      `${label}.workload.overloadReadConcurrency`,
      workload.readConcurrency + 1,
      256,
    );
  }
  const dataset = profile.dataset || {};
  if (Number(dataset.generatedInstances) !== workload.generatedInstances) {
    throw new Error(`${label} dataset/workload generatedInstances must match`);
  }
  return {
    ...profile,
    id: label,
    displayName: requireString(profile.displayName, `${label}.displayName`),
    workload,
    thresholds: normalizeThresholds(profile),
  };
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
  if (manifest.schemaVersion !== 2) {
    throw new Error('capacity recovery schemaVersion must be 2');
  }
  if (manifest.scenarioSource
      !== 'config/demo/purchase-payment-golden-path.json'
      || manifest.quickStartSource !== 'config/demo/quick-start.json') {
    throw new Error('capacity recovery sources must reuse the governed product manifests');
  }
  if (manifest.databaseVendor !== 'PostgreSQL 16') {
    throw new Error('capacity target must remain PostgreSQL 16');
  }
  requireInteger(manifest.applicationInstances, 'applicationInstances', 1, 1);
  const maximumRuntimeSeconds = requireInteger(
    manifest.maximumRuntimeSeconds,
    'maximumRuntimeSeconds',
    1800,
    3600,
  );
  const extendedProfileRuntimeSeconds = requireInteger(
    manifest.extendedProfileRuntimeSeconds,
    'extendedProfileRuntimeSeconds',
    300,
    1200,
  );
  const profileIds = manifest.profiles?.map(profile => profile.id);
  const profileStatuses = manifest.profiles?.map(profile => profile.status);
  requireExactList(profileIds, requiredProfileIds, 'profile IDs');
  requireExactList(profileStatuses, requiredStatuses, 'profile statuses');
  const profiles = manifest.profiles.map(normalizeProfile);
  if (Number(profiles[2].dataset?.cumulativeGeneratedInstances)
      !== profiles[1].workload.generatedInstances
        + profiles[2].workload.generatedInstances) {
    throw new Error('large-tenant cumulativeGeneratedInstances is inconsistent');
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
  requireExactList(
    manifest.extendedClaims,
    requiredExtendedClaims,
    'extendedClaims',
  );
  requireExactList(manifest.nonClaims, requiredNonClaims, 'nonClaims');
  requireExactList(
    manifest.extendedNonClaims,
    requiredNonClaims,
    'extendedNonClaims',
  );
  return {
    ...manifest,
    maximumRuntimeSeconds,
    extendedProfileRuntimeSeconds,
    profiles,
    scenario,
    smallDemo: profiles[0],
    standardDeployment: profiles[1],
    largeTenant: profiles[2],
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
    '  run    Execute all three local reference profiles and recovery evidence',
    '  ci     Execute only when the existing product-readiness path scope selects it',
    '',
    'Options:',
    '  --json Print the plan as JSON',
    '  --help Show this help',
  ].join('\n');
}

export function plan(contract) {
  return {
    schemaVersion: 2,
    entrypoint: 'pnpm demo:runtime:capacity-recovery',
    databaseVendor: contract.databaseVendor,
    applicationInstances: contract.applicationInstances,
    maximumRuntimeSeconds: contract.maximumRuntimeSeconds,
    extendedProfileRuntimeSeconds: contract.extendedProfileRuntimeSeconds,
    profiles: contract.profiles,
    executableProfiles: contract.profiles.map(profile => profile.id),
    scenario: {
      tenantId: requireString(contract.scenario.tenant?.id, 'scenario tenant.id'),
      businessKeyPrefix:
        `${requireString(contract.scenario.request?.businessKey, 'scenario request.businessKey')}-CAP-`,
      processDefinition: 'purchase-payment',
    },
    stages: [
      'reuse demo-backend.mjs for PostgreSQL, Redis, Spring Boot, Flowable and deterministic Seed',
      'measure the Small Demo configured point and exact-Head purchase-payment recovery evidence',
      'run cumulative Standard Deployment and Large Tenant local-reference profiles',
      'measure configured and higher-concurrency read points, concurrent starts and full approvals',
      'measure completion Outbox backlog creation without claiming volume drain',
      'capture exact source, host, process and PostgreSQL observations',
      'write bounded machine-readable evidence under .runtime/capacity-recovery/<run-id>/',
      'clean managed processes, ports, containers and disposable data in finally',
    ],
    evidenceRoot: '.runtime/capacity-recovery/<run-id>/',
    claims: contract.claims,
    extendedClaims: contract.extendedClaims,
    nonClaims: contract.nonClaims,
  };
}

export function printPlan(contract, jsonOutput) {
  const value = plan(contract);
  console.log(jsonOutput
    ? JSON.stringify(value, null, 2)
    : `Approval Platform capacity/recovery plan\n${JSON.stringify(value, null, 2)}`);
}
