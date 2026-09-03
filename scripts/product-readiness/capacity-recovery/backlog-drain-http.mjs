import { performance } from 'node:perf_hooks';

import { delay } from '../pc-h5-runtime/processes.mjs';
import { backendOrigin } from './contract.mjs';
import {
  approvalBackoffsMs,
  approvalConcurrency,
  maximumApprovalAttempts,
  requestTimeoutMs,
  requiredText,
  rounded,
  startConcurrency,
  unwrapObject,
  uuid,
} from './backlog-drain-contract.mjs';
import { runBoundedPool } from './statistics.mjs';

const approvalPath = /^\/api\/approval\/tasks\/[0-9a-f-]{36}\/approve$/iu;

function governedStage(contract, taskDefinitionKey) {
  const matches = contract.scenario.expectedWorkflow.filter(stage =>
    stage.taskDefinitionKey === taskDefinitionKey);
  if (matches.length !== 1) {
    throw new Error(`governed workflow must contain one ${taskDefinitionKey}`);
  }
  return matches[0];
}

function exactActiveTasks(payload, taskDefinitionKey, expectedCount, label) {
  const value = unwrapObject(payload);
  if (!Array.isArray(value?.activeTasks)) {
    throw new Error(`${label} does not expose activeTasks`);
  }
  const matches = value.activeTasks.filter(task =>
    task.taskDefinitionKey === taskDefinitionKey);
  if (matches.length !== expectedCount || value.activeTasks.length !== expectedCount) {
    throw new Error(
      `${label} expected ${expectedCount} ${taskDefinitionKey} task(s): `
        + JSON.stringify(value.activeTasks),
    );
  }
  for (const task of matches) {
    if (!uuid.test(task.taskId || '') || !uuid.test(task.instanceId || '')) {
      throw new Error(`${label} returned an invalid task identity`);
    }
  }
  return matches;
}

function parsePayload(text) {
  if (!text.trim()) return undefined;
  try {
    return JSON.parse(text);
  } catch (error) {
    throw new Error('backlog-drain API returned invalid JSON', { cause: error });
  }
}

function retryClassification(payload) {
  const candidate = payload?.error
    && typeof payload.error === 'object'
    && !Array.isArray(payload.error)
    ? payload.error
    : payload;
  return {
    code: typeof candidate?.code === 'string' ? candidate.code : null,
    retryable: candidate?.retryable === true,
  };
}

function createRecorder(contract, token) {
  const attempts = [];
  let sequence = 0;

  async function request(operation, path, options = {}) {
    const method = options.method || 'GET';
    const body = options.json === undefined
      ? undefined
      : JSON.stringify(options.json);
    const headers = {
      Accept: 'application/json',
      'Cache-Control': 'no-store',
      'X-Operator-Id': requiredText(options.actorId, `${operation}.actorId`),
      'X-Tenant-Id': contract.scenario.tenant.id,
    };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (options.idempotencyKey) {
      headers['Idempotency-Key'] = options.idempotencyKey;
    }
    const governedApproval = method === 'POST' && approvalPath.test(path);
    const totalAttempts = governedApproval ? maximumApprovalAttempts : 1;

    for (let attempt = 1; attempt <= totalAttempts; attempt += 1) {
      sequence += 1;
      const requestId = `backlog-${token}-${sequence}`;
      const startedAt = new Date().toISOString();
      const started = performance.now();
      let response;
      let text = '';
      let payload;
      try {
        response = await fetch(`${backendOrigin}${path}`, {
          method,
          headers: {
            ...headers,
            'X-Request-Id': requestId,
            'X-Trace-Id': requestId,
          },
          body,
          signal: AbortSignal.timeout(requestTimeoutMs),
        });
        text = await response.text();
        payload = parsePayload(text);
      } catch (error) {
        attempts.push({
          sequence,
          operation,
          method,
          path,
          attempt,
          status: 0,
          retryable: false,
          outcome: 'TERMINAL_NETWORK_FAILURE_NOT_RETRIED',
          startedAt,
          completedAt: new Date().toISOString(),
          latencyMs: rounded(performance.now() - started),
        });
        throw error;
      }

      const classification = retryClassification(payload);
      const retryable = governedApproval
        && response.status === 500
        && classification.code === 'APPROVAL_COMMAND_FAILED'
        && classification.retryable;
      const succeeded = response.ok;
      let outcome = succeeded ? 'SUCCEEDED' : 'TERMINAL_RESPONSE';
      if (succeeded && attempt > 1) outcome = 'SUCCEEDED_AFTER_RETRY';
      else if (retryable && attempt < totalAttempts) outcome = 'RETRY_SCHEDULED';
      else if (retryable) outcome = 'TERMINAL_RETRYABLE_FAILURE';
      attempts.push({
        sequence,
        operation,
        method,
        path,
        attempt,
        status: response.status,
        code: classification.code,
        retryable,
        outcome,
        startedAt,
        completedAt: new Date().toISOString(),
        latencyMs: rounded(performance.now() - started),
      });
      if (succeeded) return payload;
      if (!retryable || attempt === totalAttempts) {
        throw new Error(
          `${operation} returned HTTP ${response.status}: ${text.slice(0, 1000)}`,
        );
      }
      await delay(approvalBackoffsMs[attempt - 1]);
    }
    throw new Error(`${operation} exhausted bounded approval retry handling`);
  }

  return { request, attempts };
}

async function startPurchase(
  recorder,
  contract,
  prefixes,
  attachmentIds,
  index,
  token,
) {
  const suffix = `${token}-${String(index + 1).padStart(3, '0')}`;
  const businessKey = `${prefixes.businessKey}${suffix}`;
  const purchaseOrderReference = `${prefixes.purchaseOrderReference}${suffix}`;
  const payload = unwrapObject(await recorder.request(
    'backlog-purchase-start',
    '/api/approval/instances/purchase-payment',
    {
      actorId: contract.scenario.assigneeRules.initiatorUserId.value,
      method: 'POST',
      idempotencyKey: `backlog-start-${suffix}`,
      json: {
        ...contract.scenario.request,
        businessKey,
        purchaseOrderReference,
        attachmentIds,
        assigneeRules: contract.scenario.assigneeRules,
      },
    },
  ));
  if (!uuid.test(payload?.instanceId || '') || payload.status !== 'RUNNING') {
    throw new Error(`${businessKey} start returned an invalid instance`);
  }
  return {
    businessKey,
    purchaseOrderReference,
    instanceId: payload.instanceId,
    tasks: exactActiveTasks(
      payload,
      'managerApproval',
      1,
      `${businessKey} start`,
    ),
  };
}

async function approveTask(recorder, task, actorId, token, label) {
  const payload = unwrapObject(await recorder.request(
    `backlog-approve-${task.taskDefinitionKey}`,
    `/api/approval/tasks/${task.taskId}/approve`,
    {
      actorId,
      method: 'POST',
      idempotencyKey: `backlog-approve-${token}-${task.taskId}`,
      json: { comment: `Capacity backlog drain ${label}` },
    },
  ));
  if (payload?.completedTaskId !== task.taskId
      || payload?.instanceId !== task.instanceId) {
    throw new Error(`${label} returned an inconsistent task action result`);
  }
  return payload;
}

async function advanceSingle(
  recorder,
  contract,
  instances,
  currentKey,
  nextKey,
  expectedNextCount,
  token,
) {
  const actor = governedStage(contract, currentKey).actorIds[0];
  return runBoundedPool(
    instances,
    Math.min(approvalConcurrency, instances.length),
    async (instance) => {
      const task = instance.tasks[0];
      if (task.taskDefinitionKey !== currentKey) {
        throw new Error(`${instance.businessKey} is not at ${currentKey}`);
      }
      const result = await approveTask(
        recorder,
        task,
        actor,
        token,
        `${instance.businessKey}/${currentKey}`,
      );
      return {
        ...instance,
        tasks: exactActiveTasks(
          result,
          nextKey,
          expectedNextCount,
          `${instance.businessKey}/${currentKey}`,
        ),
      };
    },
  );
}

async function advanceCountersign(recorder, contract, instances, token) {
  const actors = [...governedStage(contract, 'financeCountersign').actorIds];
  if (actors.length !== 2) {
    throw new Error('financeCountersign must retain two governed actors');
  }
  return runBoundedPool(
    instances,
    Math.min(approvalConcurrency, instances.length),
    async (instance) => {
      let remaining = [...instance.tasks];
      if (remaining.length !== 2
          || !actors.every(actor =>
            remaining.some(task => task.assigneeId === actor))) {
        throw new Error(
          `${instance.businessKey} countersign assignments are inconsistent`,
        );
      }
      let result;
      for (const actor of actors) {
        const task = remaining.find(candidate => candidate.assigneeId === actor);
        if (!task) throw new Error(`${instance.businessKey} lost actor ${actor}`);
        result = await approveTask(
          recorder,
          task,
          actor,
          token,
          `${instance.businessKey}/financeCountersign/${actor}`,
        );
        remaining = (result.activeTasks || []).filter(candidate =>
          candidate.taskDefinitionKey === 'financeCountersign');
      }
      return {
        ...instance,
        tasks: exactActiveTasks(
          result,
          'paymentConfirmation',
          1,
          `${instance.businessKey}/financeCountersign`,
        ),
      };
    },
  );
}

async function completePayments(recorder, contract, instances, token) {
  const actor = governedStage(contract, 'paymentConfirmation').actorIds[0];
  return runBoundedPool(
    instances,
    Math.min(approvalConcurrency, instances.length),
    async (instance) => {
      const task = instance.tasks[0];
      const result = await approveTask(
        recorder,
        task,
        actor,
        token,
        `${instance.businessKey}/paymentConfirmation`,
      );
      if (result.instanceStatus !== 'COMPLETED'
          || !Array.isArray(result.activeTasks)
          || result.activeTasks.length !== 0) {
        throw new Error(`${instance.businessKey} did not complete`);
      }
      return {
        ...instance,
        tasks: [],
        finalStatus: result.instanceStatus,
        completedAt: result.completedAt,
      };
    },
  );
}

async function verifyCompleted(recorder, contract, instances) {
  await runBoundedPool(
    instances,
    Math.min(startConcurrency, instances.length),
    async (instance) => {
      const details = unwrapObject(await recorder.request(
        'backlog-completed-instance',
        `/api/approval/instances/${instance.instanceId}`,
        { actorId: contract.scenario.assigneeRules.initiatorUserId.value },
      ));
      if (details?.instance?.status !== 'COMPLETED'
          || details.instance.businessKey !== instance.businessKey) {
        throw new Error(`${instance.businessKey} completed read is inconsistent`);
      }
    },
  );
}

export async function executeBacklogWorkflow(
  contract,
  prefixes,
  attachmentIds,
  expectedRows,
  token,
) {
  const recorder = createRecorder(contract, token);
  let instances = await runBoundedPool(
    Array.from({ length: expectedRows }, (_, index) => index),
    startConcurrency,
    index => startPurchase(
      recorder,
      contract,
      prefixes,
      attachmentIds,
      index,
      token,
    ),
  );
  instances = await advanceSingle(
    recorder,
    contract,
    instances,
    'managerApproval',
    'financeReview',
    1,
    token,
  );
  instances = await advanceSingle(
    recorder,
    contract,
    instances,
    'financeReview',
    'financeCountersign',
    2,
    token,
  );
  instances = await advanceCountersign(
    recorder,
    contract,
    instances,
    token,
  );
  instances = await completePayments(
    recorder,
    contract,
    instances,
    token,
  );
  await verifyCompleted(recorder, contract, instances);
  return { instances, attempts: recorder.attempts };
}
