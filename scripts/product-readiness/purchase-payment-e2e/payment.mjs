import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { chromeExecutable, java21Environment } from '../pc-h5-runtime/contract.mjs';
import {
  runPnpmChecked,
  startManagedNode,
  waitForHttp,
  waitForMarker,
} from '../pc-h5-runtime/processes.mjs';
import {
  backendOrigin,
  backendTimeoutMs,
  browserTimeoutMs,
  clientTimeoutMs,
  eventType,
  h5Origin,
  repositoryRoot,
  uuid,
  writeJson,
} from './contract.mjs';
import {
  queryOutbox,
  readSandboxStatus,
  waitForPortAvailable,
  waitForState,
} from './evidence.mjs';

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
    APPROVAL_GENERIC_DISPATCH_BATCH_SIZE: '10',
    APPROVAL_GENERIC_DISPATCH_LEASE: 'PT5S',
    APPROVAL_GENERIC_RETRY_INITIAL_DELAY: 'PT10S',
    APPROVAL_GENERIC_RETRY_MAXIMUM_DELAY: 'PT10S',
    APPROVAL_GENERIC_RETRY_MAXIMUM_ATTEMPTS: '5',
    APPROVAL_GENERIC_RETRY_JITTER_RATIO: '0',
  };
}

export function validCompletionOutboxIdentity(
  row,
  instanceId,
  completedEventType,
  paymentRequest,
) {
  return row.aggregateId === instanceId
    && row.eventType === completedEventType
    && row.requestId === paymentRequest.requestId
    && row.traceId === paymentRequest.traceId
    && row.idempotencyKey === `${completedEventType}:${instanceId}`
    && uuid.test(row.id || '')
    && uuid.test(row.eventId || '');
}

export async function runPaymentStage(
  runDirectory,
  contract,
  identity,
  pcH5Evidence,
  managed,
) {
  for (const port of [5777, 8080, 9000]) {
    await waitForPortAvailable(port);
  }

  const completedEventType = eventType();
  const environment = backendEnvironment(runDirectory, contract);
  const sandboxStatusPath = environment.APPROVAL_DEMO_PAYMENT_SANDBOX_STATUS_FILE;
  const recoveryControlPath = environment.APPROVAL_DEMO_PAYMENT_SANDBOX_CONTROL_FILE;

  const backend = startManagedNode(
    'Restart the same backend data with local payment sandbox and Outbox dispatcher',
    ['scripts/product-readiness/demo-backend.mjs', 'start'],
    resolve(runDirectory, 'backend-payment.log'),
    environment,
  );
  managed.push(backend);
  await waitForMarker(backend, 'BACKEND_LOCAL_START_VERIFIED', backendTimeoutMs);
  await waitForMarker(backend, 'PURCHASE_PAYMENT_DEMO_SEED_APPLIED', backendTimeoutMs);

  const initialSandbox = await waitForState(
    'Initial unavailable payment sandbox',
    () => readSandboxStatus(sandboxStatusPath),
    value => value.available === false
      && value.deliveryAttempts === 0
      && value.acceptedPaymentResults === 0,
  );
  writeJson(resolve(runDirectory, 'payment-sandbox-initial.json'), initialSandbox);

  const beforePayment = queryOutbox(pcH5Evidence.instanceId, completedEventType);
  if (beforePayment.length !== 0) {
    throw new Error('completion Outbox event exists before paymentConfirmation');
  }
  writeJson(resolve(runDirectory, 'outbox-before-payment.json'), beforePayment);

  runPnpmChecked(
    'Build WeChat Mini Program (build-only; runtime remains a non-claim)',
    ['mobile:build:weixin'],
    process.env,
  );

  const h5 = startManagedNode(
    'Start H5 payment-confirmation surrogate as governed demo-employee',
    [
      'scripts/product-readiness/demo-client.mjs',
      'h5',
      '--actor',
      contract.policy.actorId,
      '--port',
      '9000',
      '--skip-install',
    ],
    resolve(runDirectory, 'h5-payment.log'),
    process.env,
  );
  managed.push(h5);
  await waitForHttp(`${h5Origin}/`, clientTimeoutMs);

  runPnpmChecked(
    'Complete paymentConfirmation through the visible H5 mobile UI',
    [
      '--dir',
      '.upstream/vben',
      '--filter',
      '@vben/playground',
      'exec',
      'playwright',
      'test',
      '--config=product-readiness.playwright.config.ts',
      '__tests__/e2e/product-readiness-h5-payment-runtime.spec.ts',
    ],
    {
      ...process.env,
      APPROVAL_DEMO_BACKEND_ORIGIN: backendOrigin,
      APPROVAL_DEMO_CHROME_PATH: chromeExecutable(),
      APPROVAL_DEMO_EVIDENCE_DIR: runDirectory,
      APPROVAL_DEMO_EXACT_HEAD_SHA: identity.commitSha,
      APPROVAL_DEMO_EXPECTED_PAYMENT_TASK_ID:
        pcH5Evidence.paymentHandoff.taskId,
      APPROVAL_DEMO_H5_URL: `${h5Origin}/#/pages/task/list`,
      APPROVAL_DEMO_INSTANCE_ID: pcH5Evidence.instanceId,
      APPROVAL_DEMO_PLAYWRIGHT_TIMEOUT_MS: String(browserTimeoutMs),
      APPROVAL_DEMO_REPOSITORY_ROOT: repositoryRoot,
    },
  );

  const h5EvidencePath = resolve(
    runDirectory,
    'h5-payment-runtime-evidence.json',
  );
  if (!existsSync(h5EvidencePath)) {
    throw new Error('H5 payment runtime evidence was not created');
  }
  const h5Evidence = JSON.parse(readFileSync(h5EvidencePath, 'utf8'));
  if (h5Evidence.status !== 'PASSED'
    || h5Evidence.stageMarker !== 'H5_PAYMENT_CONFIRMATION_STAGE_PASSED'
    || h5Evidence.commitSha !== identity.commitSha
    || h5Evidence.instanceId !== pcH5Evidence.instanceId
    || h5Evidence.taskId !== pcH5Evidence.paymentHandoff.taskId
    || h5Evidence.targetClient !== contract.policy.targetClient
    || h5Evidence.acceptanceClient !== contract.policy.acceptanceClient
    || h5Evidence.finalState?.status !== 'COMPLETED') {
    throw new Error('H5 payment runtime evidence is inconsistent');
  }

  const pending = await waitForState(
    '503 recovery evidence',
    () => ({
      outbox: queryOutbox(pcH5Evidence.instanceId, completedEventType),
      sandbox: readSandboxStatus(sandboxStatusPath),
    }),
    value => value.outbox.length === 1
      && validCompletionOutboxIdentity(
        value.outbox[0],
        pcH5Evidence.instanceId,
        completedEventType,
        h5Evidence.request,
      )
      && value.outbox[0].status === 'PENDING'
      && value.outbox[0].attempts >= 1
      && value.sandbox.lastHttpStatus === 503
      && value.sandbox.deliveryAttempts >= 1
      && value.sandbox.acceptedPaymentResults === 0,
  );
  writeJson(resolve(runDirectory, 'outbox-pending-evidence.json'), pending);

  writeFileSync(recoveryControlPath, 'recover\n', {
    encoding: 'utf8',
    mode: 0o600,
  });

  const delivered = await waitForState(
    'Outbox delivered after sandbox recovery',
    () => ({
      outbox: queryOutbox(pcH5Evidence.instanceId, completedEventType),
      sandbox: readSandboxStatus(sandboxStatusPath),
    }),
    value => value.outbox.length === 1
      && validCompletionOutboxIdentity(
        value.outbox[0],
        pcH5Evidence.instanceId,
        completedEventType,
        h5Evidence.request,
      )
      && value.outbox[0].status === 'DELIVERED'
      && value.outbox[0].responseCode === 200
      && typeof value.outbox[0].providerRequestId === 'string'
      && value.outbox[0].providerRequestId.length > 0
      && value.sandbox.available === true
      && value.sandbox.acceptedPaymentResults === 1
      && value.sandbox.acceptedEventId === value.outbox[0].eventId
      && value.sandbox.acceptedIdempotencyKey
        === value.outbox[0].idempotencyKey,
  );
  writeJson(resolve(runDirectory, 'outbox-delivered-evidence.json'), delivered);

  let stableObservations = 0;
  await waitForState(
    'Exactly-one payment side effect stability window',
    () => ({
      outbox: queryOutbox(pcH5Evidence.instanceId, completedEventType),
      sandbox: readSandboxStatus(sandboxStatusPath),
    }),
    (value) => {
      if (value.outbox.length > 1 || value.sandbox.acceptedPaymentResults > 1) {
        throw new Error('duplicate completion event or payment side effect detected');
      }
      const stable = value.outbox.length === 1
        && validCompletionOutboxIdentity(
          value.outbox[0],
          pcH5Evidence.instanceId,
          completedEventType,
          h5Evidence.request,
        )
        && value.outbox[0].status === 'DELIVERED'
        && value.sandbox.acceptedPaymentResults === 1;
      stableObservations = stable ? stableObservations + 1 : 0;
      return stableObservations >= 8;
    },
  );

  return {
    completedEventType,
    h5Evidence,
    pending,
    delivered,
  };
}
