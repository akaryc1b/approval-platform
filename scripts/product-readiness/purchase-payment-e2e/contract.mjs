import { spawnSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { repositoryRoot } from '../pc-h5-runtime/contract.mjs';

export { repositoryRoot };

export const acceptancePath = resolve(
  repositoryRoot,
  'config/demo/purchase-payment-alpha-acceptance.json',
);
export const scenarioPath = resolve(
  repositoryRoot,
  'config/demo/purchase-payment-golden-path.json',
);
export const outboxSourcePath = resolve(
  repositoryRoot,
  'server-modules/approval-persistence-jdbc/src/main/java/io/github/akaryc1b/approval/persistence/jdbc/JdbcApprovalBusinessEventOutbox.java',
);
export const pcH5OutputDirectory = resolve(
  repositoryRoot,
  'build/product-readiness/pc-h5-runtime',
);
export const outputRoot = resolve(
  repositoryRoot,
  '.runtime/purchase-payment-e2e',
);
export const ledgerPath = resolve(outputRoot, 'consecutive-clean-runs.json');
export const composeFile = 'deploy/compose/docker-compose.yml';
export const composeProject = 'approval-platform-demo';
export const backendOrigin = 'http://127.0.0.1:8080';
export const h5Origin = 'http://127.0.0.1:9000';
export const pollIntervalMs = 250;
export const stateTimeoutMs = 90_000;
export const backendTimeoutMs = 15 * 60_000;
export const clientTimeoutMs = 5 * 60_000;
export const browserTimeoutMs = 5 * 60_000;
const commands = new Set(['plan', 'run', 'ci']);
export const sha40 = /^[0-9a-f]{40}$/u;
export const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu;

export class UsageError extends Error {}

export function usage() {
  return `Usage: node scripts/product-readiness/purchase-payment-e2e.mjs <command> [options]\n\nCommands:\n  plan  Print the one-command H5-surrogate purchase-to-payment plan.\n  run   Execute a clean local golden-path run.\n  ci    Continue from the preceding path-gated PC/H5 CI smoke.\n\nOptions:\n  --json  Machine-readable plan output.\n  --help  Show this help.\n\nThe authoritative payment target remains WeChat. Product Alpha acceptance uses\nthe real H5 mobile UI as an explicit surrogate and never emits a WeChat runtime\npass claim.`;
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

export function requireText(value, name) {
  if (typeof value !== 'string' || !value.trim()) {
    throw new Error(`${name} must be a non-empty string`);
  }
  return value.trim();
}

export function loadContract() {
  const scenario = readJson(scenarioPath);
  const acceptance = readJson(acceptancePath);
  if (acceptance.schemaVersion !== 1) {
    throw new Error('unsupported purchase-payment acceptance schemaVersion');
  }
  if (acceptance.scenarioManifest
    !== 'config/demo/purchase-payment-golden-path.json') {
    throw new Error('acceptance scenarioManifest is not canonical');
  }
  const policy = acceptance.paymentConfirmationAcceptance;
  const matches = scenario.expectedWorkflow.filter(stage =>
    stage.taskDefinitionKey === policy?.taskDefinitionKey);
  if (matches.length !== 1) {
    throw new Error('authoritative paymentConfirmation stage is missing');
  }
  const stage = matches[0];
  if (stage.actorIds?.length !== 1
    || stage.actorIds[0] !== policy.actorId
    || stage.client !== policy.targetClient
    || policy.acceptanceClient !== 'h5'
    || policy.acceptanceMode !== 'H5_MOBILE_SURROGATE') {
    throw new Error('H5 surrogate acceptance does not match the governed scenario');
  }
  const expectedClaims = [
    'PURCHASE_PAYMENT_LOCAL_ALPHA_E2E_PASSED',
    'H5_PAYMENT_CONFIRMATION_PASSED',
    'PURCHASE_APPROVAL_E2E_PASSED',
    'PURCHASE_TO_PAYMENT_SANDBOX_E2E_PASSED',
    'OUTBOX_RETRY_AND_IDEMPOTENCY_PASSED',
    'TWO_CONSECUTIVE_CLEAN_RUNS_PASSED',
  ];
  if (JSON.stringify(acceptance.claimsAfterTwoConsecutiveCleanRuns)
    !== JSON.stringify(expectedClaims)) {
    throw new Error('acceptance gated claims do not match Product Alpha');
  }
  const expectedNonClaims = [
    'WECHAT_DEVTOOLS_RUNTIME_NOT_VERIFIED',
    'WECHAT_PHYSICAL_DEVICE_NOT_VERIFIED',
    'PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED',
    'PERFORMANCE_NOT_VERIFIED',
    'BROWSER_COMPATIBILITY_NOT_VERIFIED',
    'ACCESSIBILITY_NOT_VERIFIED',
    'RPO_RTO_NOT_VERIFIED',
    'MYSQL_8_4_NOT_VERIFIED',
  ];
  if (JSON.stringify(acceptance.nonClaims) !== JSON.stringify(expectedNonClaims)) {
    throw new Error('acceptance non-claims do not match Product Alpha');
  }
  return { acceptance, policy, scenario };
}

export function e2ePlan(contract) {
  return {
    schemaVersion: 1,
    entrypoint: 'pnpm demo:runtime:purchase-payment:e2e',
    scenarioManifest: contract.acceptance.scenarioManifest,
    targetClient: contract.policy.targetClient,
    acceptanceClient: contract.policy.acceptanceClient,
    acceptanceMode: contract.policy.acceptanceMode,
    stages: [
      'delete only the disposable approval-platform-demo volume',
      'reuse pc-h5-runtime-smoke.mjs for managerApproval and finance approvals',
      'retain the same instance at the independent paymentConfirmation task',
      'build the WeChat Mini Program without claiming WeChat runtime execution',
      'restart the same backend data with GenericRestBusinessCallbackConnector and OutboxDispatcher',
      'use the real H5 mobile UI as demo-employee to approve paymentConfirmation',
      'wait for COMPLETED and exactly one transactional completion Outbox row',
      'observe sandbox HTTP 503 and recoverable Outbox PENDING',
      'restore the sandbox and wait for bounded retry to DELIVERED',
      'prove exactly one accepted payment side effect',
      'clean processes, ports, containers and disposable data in finally',
    ],
    evidenceRoot: '.runtime/purchase-payment-e2e/<run-id>/',
    claimsAfterTwoConsecutiveCleanRuns:
      contract.acceptance.claimsAfterTwoConsecutiveCleanRuns,
    nonClaims: contract.acceptance.nonClaims,
  };
}

export function printPlan(contract, jsonOutput) {
  const plan = e2ePlan(contract);
  if (jsonOutput) {
    console.log(JSON.stringify(plan, null, 2));
    return;
  }
  console.log('Approval Platform purchase-to-payment Product Alpha plan');
  console.log(JSON.stringify(plan, null, 2));
}

export function executable(name) {
  return process.platform === 'win32' ? `${name}.exe` : name;
}

export function runCaptured(command, args, environment = process.env) {
  return spawnSync(command, args, {
    cwd: repositoryRoot,
    encoding: 'utf8',
    env: environment,
    shell: false,
  });
}

export function requireSuccess(label, result) {
  if (result.error) throw new Error(`${label} could not start: ${result.error.message}`);
  if (result.status !== 0) {
    const detail = [result.stdout, result.stderr]
      .filter(Boolean)
      .join('\n')
      .trim()
      .slice(-8_000);
    throw new Error(`${label} failed with exit code ${result.status}: ${detail}`);
  }
  return result.stdout.trim();
}

function gitRevision(revision, label) {
  const value = requireSuccess(
    label,
    runCaptured(executable('git'), ['rev-parse', '--verify', revision]),
  );
  if (!sha40.test(value)) throw new Error(`${label} is not a 40-character SHA`);
  return value;
}

function configuredExactHead() {
  const eventPath = process.env.GITHUB_EVENT_PATH;
  if (eventPath && existsSync(eventPath)) {
    const event = readJson(eventPath);
    const candidate = event?.pull_request?.head?.sha;
    if (candidate !== undefined && !sha40.test(candidate || '')) {
      throw new Error('pull_request.head.sha is invalid');
    }
    if (sha40.test(candidate || '')) return candidate;
  }
  const candidate = process.env.APPROVAL_DEMO_EXACT_HEAD_SHA
    || process.env.GITHUB_SHA;
  if (candidate && !sha40.test(candidate)) {
    throw new Error('configured exact Head SHA is invalid');
  }
  return candidate || null;
}

export function sourceIdentity() {
  const checkedOutSha = gitRevision('HEAD', 'checked-out revision');
  const checkedOutTreeSha = gitRevision('HEAD^{tree}', 'checked-out tree');
  const commitSha = configuredExactHead() || checkedOutSha;
  const treeSha = gitRevision(`${commitSha}^{tree}`, 'exact Head tree');
  if (checkedOutTreeSha !== treeSha) {
    throw new Error('checked-out source tree does not match the exact Head');
  }
  return {
    checkedOutSha,
    checkedOutTreeSha,
    commitSha,
    treeSha,
    sourceTreeMatchesExactHead: true,
  };
}

export function eventType() {
  const source = readFileSync(outboxSourcePath, 'utf8');
  const match = source.match(/COMPLETED_EVENT_TYPE\s*=\s*"([^"]+)"/u);
  if (!match) throw new Error('authoritative completion event type is unavailable');
  return match[1];
}

export function runIdentifier() {
  return `${new Date().toISOString().replace(/[:.]/gu, '-')}-${randomUUID().slice(0, 8)}`;
}

export function writeJson(path, value) {
  writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`, {
    encoding: 'utf8',
    mode: 0o600,
  });
}
