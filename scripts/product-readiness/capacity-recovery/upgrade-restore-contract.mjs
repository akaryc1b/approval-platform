import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { java21Environment } from '../pc-h5-runtime/contract.mjs';
import { backendOrigin, repositoryRoot } from './contract.mjs';

export const maximumRuntimeMs = 15 * 60_000;
export const backendTimeoutMs = 10 * 60_000;
export const stateTimeoutMs = 5 * 60_000;
export const requestTimeoutMs = 8_000;
export const maximumApprovalAttempts = 4;
export const approvalBackoffsMs = Object.freeze([50, 100, 200]);
export const claim =
  'LOCAL_IN_FLIGHT_POSTGRES_UPGRADE_RESTORE_REHEARSAL_PASSED';
export const nonClaims = Object.freeze([
  'ZERO_DOWNTIME_UPGRADE_NOT_VERIFIED',
  'ROLLBACK_REHEARSAL_NOT_VERIFIED',
  'PRODUCTION_RPO_NOT_VERIFIED',
  'PRODUCTION_RTO_NOT_VERIFIED',
  'MULTI_NODE_RECOVERY_NOT_VERIFIED',
  'PRODUCTION_BACKUP_RETENTION_NOT_VERIFIED',
]);

export function requiredText(value, label) {
  if (typeof value !== 'string' || value.trim().length === 0) {
    throw new Error(`${label} is required`);
  }
  return value.trim();
}

export function rounded(value) {
  return Number(value.toFixed(3));
}

export function snapshot(evidenceKind, value) {
  return {
    schemaVersion: 1,
    evidenceKind,
    capturedAt: new Date().toISOString(),
    ...value,
  };
}

export function unwrapObject(value) {
  if (value?.data && typeof value.data === 'object') return value.data;
  return value;
}

function githubEvent() {
  const eventPath = process.env.GITHUB_EVENT_PATH;
  if (!eventPath || !existsSync(eventPath)) return undefined;
  return JSON.parse(readFileSync(eventPath, 'utf8'));
}

export function exactUpgradeRefs(runGit) {
  const event = githubEvent();
  const pullRequest = event?.pull_request;
  if (pullRequest) {
    return {
      source: 'GITHUB_PULL_REQUEST_EVENT',
      baseSha: requiredText(pullRequest.base?.sha, 'pull request base SHA'),
      candidateSha: requiredText(
        pullRequest.head?.sha,
        'pull request candidate SHA',
      ),
    };
  }

  runGit(['fetch', '--no-tags', '--depth=1', 'origin', 'main']);
  return {
    source: 'LOCAL_ORIGIN_MAIN_MERGE_BASE',
    baseSha: requiredText(
      runGit(['merge-base', 'HEAD', 'origin/main']),
      'local merge-base SHA',
    ),
    candidateSha: requiredText(runGit(['rev-parse', 'HEAD']), 'local Head SHA'),
  };
}

export function rehearsalPrefixes(contract) {
  return {
    businessKey: `${contract.scenario.request.businessKey}-UPGRADE-RESTORE-`,
    purchaseOrderReference:
      `${contract.scenario.request.purchaseOrderReference}-UPGRADE-RESTORE-`,
  };
}

export function candidateEnvironment(runDirectory, contract, prefixes) {
  const callback = `${backendOrigin}/payment-sandbox/v1/events`;
  return {
    ...java21Environment(),
    APPROVAL_DEMO_PAYMENT_SANDBOX_ENABLED: 'true',
    APPROVAL_DEMO_PAYMENT_SANDBOX_ENDPOINT: callback,
    APPROVAL_DEMO_PAYMENT_SANDBOX_CONTROL_FILE:
      resolve(runDirectory, 'payment-sandbox-recover.control'),
    APPROVAL_DEMO_PAYMENT_SANDBOX_STATUS_FILE:
      resolve(runDirectory, 'payment-sandbox-status.json'),
    APPROVAL_DEMO_PAYMENT_SANDBOX_CLOCK_SKEW: 'PT5M',
    APPROVAL_DEMO_PAYMENT_SANDBOX_BUSINESS_KEY_PREFIX: prefixes.businessKey,
    APPROVAL_DEMO_PAYMENT_SANDBOX_PURCHASE_ORDER_REFERENCE_PREFIX:
      prefixes.purchaseOrderReference,
    APPROVAL_GENERIC_CONNECTOR_ENABLED: 'true',
    APPROVAL_GENERIC_CONNECTOR_KEY: contract.scenario.directory.connectorKey,
    APPROVAL_GENERIC_HOST_BASE_URI: 'http://127.0.0.1:19090',
    APPROVAL_GENERIC_CALLBACK_URI: callback,
    APPROVAL_GENERIC_KEY_ID: 'purchase-payment-local-alpha-v1',
    APPROVAL_GENERIC_SECRET:
      'purchase-payment-local-alpha-secret-material-2026',
    APPROVAL_GENERIC_TIMEOUT: 'PT2S',
    APPROVAL_GENERIC_DISPATCH_ENABLED: 'true',
    APPROVAL_GENERIC_DISPATCH_FIXED_DELAY: 'PT0.5S',
    APPROVAL_GENERIC_DISPATCH_BATCH_SIZE: '16',
    APPROVAL_GENERIC_DISPATCH_LEASE: 'PT30S',
    APPROVAL_GENERIC_RETRY_INITIAL_DELAY: 'PT2S',
    APPROVAL_GENERIC_RETRY_MAXIMUM_DELAY: 'PT2S',
    APPROVAL_GENERIC_RETRY_MAXIMUM_ATTEMPTS: '20',
    APPROVAL_GENERIC_RETRY_JITTER_RATIO: '0',
  };
}

export function baseEnvironment() {
  return java21Environment();
}

export function upgradeRestorePlan() {
  return {
    stage:
      'create an in-flight purchase on the exact main baseline, quiesce, pg_dump, rebuild PostgreSQL 16, pg_restore into the candidate Head, verify consistency and continue to payment',
    claim,
    evidenceKind: 'CAPACITY_UPGRADE_RESTORE_SUMMARY_V1',
    rpoBoundary:
      'LOCAL_QUIESCED_ZERO_COMMITTED_BUSINESS_RECORD_LOSS_ONLY',
    rtoBoundary:
      'LOCAL_SINGLE_NODE_OUTAGE_TO_FIRST_SUCCESSFUL_BUSINESS_READ_ONLY',
    nonClaims,
  };
}

export { backendOrigin, repositoryRoot };
