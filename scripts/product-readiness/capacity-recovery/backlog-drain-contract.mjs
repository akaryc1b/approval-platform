import { resolve } from 'node:path';

import { java21Environment } from '../pc-h5-runtime/contract.mjs';
import { backendOrigin } from './contract.mjs';
import { upgradeRestorePlan } from './upgrade-restore-contract.mjs';

export const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu;
export const backendTimeoutMs = 15 * 60_000;
export const maximumRuntimeMs = 10 * 60_000;
export const stateTimeoutMs = 5 * 60_000;
export const startConcurrency = 12;
export const approvalConcurrency = 16;
export const requestTimeoutMs = 8_000;
export const maximumApprovalAttempts = 4;
export const approvalBackoffsMs = Object.freeze([50, 100, 200]);
export const dispatchBatchSize = 96;
export const dispatchRetryDelay = 'PT10S';
export const dispatchMaximumAttempts = '100';
export const claim =
  'OUTBOX_CONNECTOR_BACKLOG_DRAIN_LOCAL_CONFIGURED_VOLUME_PASSED';
export const nonClaims = Object.freeze([
  'PRODUCTION_OUTBOX_DRAIN_RATE_NOT_VERIFIED',
  'MULTI_NODE_OUTBOX_DRAIN_NOT_VERIFIED',
  'PRODUCTION_RTO_NOT_VERIFIED',
]);

export function rounded(value) {
  return Number(value.toFixed(3));
}

export function requiredText(value, label) {
  if (typeof value !== 'string' || value.trim().length === 0) {
    throw new Error(`${label} is required`);
  }
  return value.trim();
}

export function unwrapObject(value) {
  if (value?.data && typeof value.data === 'object') return value.data;
  return value;
}

export function snapshot(kind, value) {
  return {
    schemaVersion: 1,
    evidenceKind: kind,
    capturedAt: new Date().toISOString(),
    ...value,
  };
}

export function exactConfiguredRowCount(contract) {
  const value = Number(
    contract.largeTenant?.dataset?.cumulativeGeneratedInstances,
  );
  if (value !== dispatchBatchSize) {
    throw new Error(
      `backlog drain requires cumulative configured volume ${dispatchBatchSize}`,
    );
  }
  return value;
}

export function backendEnvironment(runDirectory, contract) {
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
    APPROVAL_DEMO_PAYMENT_SANDBOX_EVENT_ALLOWLIST_FILE:
      resolve(runDirectory, 'payment-sandbox-events.allowlist'),
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
    APPROVAL_GENERIC_DISPATCH_BATCH_SIZE: String(dispatchBatchSize),
    APPROVAL_GENERIC_DISPATCH_LEASE: 'PT30S',
    APPROVAL_GENERIC_RETRY_INITIAL_DELAY: dispatchRetryDelay,
    APPROVAL_GENERIC_RETRY_MAXIMUM_DELAY: dispatchRetryDelay,
    APPROVAL_GENERIC_RETRY_MAXIMUM_ATTEMPTS: dispatchMaximumAttempts,
    APPROVAL_GENERIC_RETRY_JITTER_RATIO: '0',
  };
}

export function backlogDrainPlan(contract) {
  const expectedRows = exactConfiguredRowCount(contract);
  const upgradeRestore = upgradeRestorePlan(contract);
  return {
    stage:
      'retain and drain the profile matrix original 96 completion events through the existing Generic REST Connector using an exact generated-event allowlist',
    expectedRows,
    claim,
    evidenceKind: 'CAPACITY_OUTBOX_BACKLOG_DRAIN_SUMMARY_V1',
    nonClaims: [...nonClaims, ...upgradeRestore.nonClaims],
    upgradeRestore,
  };
}
