import { performance } from 'node:perf_hooks';

import { delay } from '../pc-h5-runtime/processes.mjs';
import {
  approvalBackoffsMs,
  backendOrigin,
  maximumApprovalAttempts,
  requestTimeoutMs,
  requiredText,
  rounded,
  snapshot,
  unwrapObject,
} from './upgrade-restore-contract.mjs';

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu;
const approvalPath = /^\/api\/approval\/tasks\/[0-9a-f-]{36}\/approve$/iu;

function stage(contract, key) {
  const matches = contract.scenario.expectedWorkflow.filter(value =>
    value.taskDefinitionKey === key);
  if (matches.length !== 1) {
    throw new Error(`governed workflow must contain one ${key} stage`);
  }
  return matches[0];
}

function parsePayload(text) {
  if (!text.trim()) return undefined;
  try {
    return JSON.parse(text);
  } catch (error) {
    throw new Error('upgrade/restore API returned invalid JSON', { cause: error });
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

export function createRecorder(contract, token) {
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
      const requestId = `upgrade-restore-${token}-${sequence}`;
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
        attempts.push(snapshot('CAPACITY_UPGRADE_RESTORE_HTTP_ATTEMPT_V1', {
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
        }));
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
      attempts.push(snapshot('CAPACITY_UPGRADE_RESTORE_HTTP_ATTEMPT_V1', {
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
      }));
      if (succeeded) return unwrapObject(payload);
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

function exactTasks(payload, key, count, label) {
  const tasks = payload?.activeTasks;
  if (!Array.isArray(tasks)) {
    throw new Error(`${label} does not expose activeTasks`);
  }
  const matches = tasks.filter(task => task.taskDefinitionKey === key);
  if (tasks.length !== count || matches.length !== count) {
    throw new Error(`${label} expected ${count} ${key} task(s)`);
  }
  for (const task of matches) {
    if (!uuid.test(task.taskId || '')
        || !uuid.test(task.instanceId || '')
        || !requiredText(task.assigneeId, `${label}.assigneeId`)) {
      throw new Error(`${label} returned an invalid task identity`);
    }
  }
  return matches;
}

async function approve(recorder, task, actorId, token, label) {
  const result = await recorder.request(
    `approve-${task.taskDefinitionKey}`,
    `/api/approval/tasks/${task.taskId}/approve`,
    {
      actorId,
      method: 'POST',
      idempotencyKey: `upgrade-restore-${token}-${task.taskId}`,
      json: { comment: `Upgrade/restore rehearsal ${label}` },
    },
  );
  if (result?.completedTaskId !== task.taskId
      || result?.instanceId !== task.instanceId) {
    throw new Error(`${label} returned an inconsistent task action result`);
  }
  return result;
}

export async function createInFlightPurchase(
  recorder,
  contract,
  prefixes,
  attachmentIds,
  token,
) {
  const businessKey = `${prefixes.businessKey}${token}`;
  const purchaseOrderReference = `${prefixes.purchaseOrderReference}${token}`;
  let result = await recorder.request(
    'start-in-flight-purchase',
    '/api/approval/instances/purchase-payment',
    {
      actorId: contract.scenario.assigneeRules.initiatorUserId.value,
      method: 'POST',
      idempotencyKey: `upgrade-restore-start-${token}`,
      json: {
        ...contract.scenario.request,
        businessKey,
        purchaseOrderReference,
        attachmentIds,
        assigneeRules: contract.scenario.assigneeRules,
      },
    },
  );
  if (!uuid.test(result?.instanceId || '') || result.status !== 'RUNNING') {
    throw new Error('upgrade/restore start returned an invalid instance');
  }
  const instanceId = result.instanceId;
  let tasks = exactTasks(result, 'managerApproval', 1, 'purchase start');
  result = await approve(
    recorder,
    tasks[0],
    stage(contract, 'managerApproval').actorIds[0],
    token,
    'managerApproval',
  );
  tasks = exactTasks(result, 'financeReview', 1, 'manager approval');
  result = await approve(
    recorder,
    tasks[0],
    stage(contract, 'financeReview').actorIds[0],
    token,
    'financeReview',
  );
  tasks = exactTasks(result, 'financeCountersign', 2, 'finance review');
  return {
    instanceId,
    businessKey,
    purchaseOrderReference,
    tasks,
  };
}

function normalizeTask(task) {
  return {
    taskId: requiredText(task.taskId, 'task.taskId'),
    instanceId: requiredText(task.instanceId, 'task.instanceId'),
    taskDefinitionKey: requiredText(
      task.taskDefinitionKey,
      'task.taskDefinitionKey',
    ),
    assigneeId: requiredText(task.assigneeId, 'task.assigneeId'),
    createdAt: requiredText(task.createdAt, 'task.createdAt'),
  };
}

export async function readConsistency(recorder, contract, instanceId) {
  const actorId = contract.scenario.assigneeRules.initiatorUserId.value;
  const details = await recorder.request(
    'read-in-flight-instance',
    `/api/approval/instances/${instanceId}`,
    { actorId },
  );
  const timeline = await recorder.request(
    'read-in-flight-timeline',
    `/api/approval/instances/${instanceId}/timeline`,
    { actorId },
  );
  const instance = details?.instance;
  if (!instance || instance.instanceId !== instanceId) {
    throw new Error('instance consistency read returned an invalid identity');
  }
  const activeTasks = (details.activeTasks || [])
    .map(normalizeTask)
    .sort((left, right) => left.taskId.localeCompare(right.taskId));
  const events = timeline?.items;
  if (!Array.isArray(events) || events.length < 3) {
    throw new Error('timeline consistency read is incomplete');
  }
  return {
    instance: {
      instanceId,
      tenantId: instance.tenantId,
      businessKey: instance.businessKey,
      definitionKey: instance.definitionKey,
      status: instance.status,
      startedAt: instance.startedAt,
      completedAt: instance.completedAt || null,
    },
    activeTasks,
    timeline: events.map(event => ({
      eventId: requiredText(event.eventId, 'timeline.eventId'),
      eventType: requiredText(event.eventType, 'timeline.eventType'),
      taskId: event.taskId || null,
      taskDefinitionKey: event.taskDefinitionKey || null,
      actorId: event.actorId || null,
      occurredAt: requiredText(event.occurredAt, 'timeline.occurredAt'),
    })),
  };
}

export function requireExactRestoredConsistency(before, after) {
  const comparable = value => JSON.stringify({
    instance: value.instance,
    activeTasks: value.activeTasks,
    timeline: value.timeline,
  });
  if (comparable(before) !== comparable(after)) {
    throw new Error('restored in-flight business summary differs from pre-backup state');
  }
}

export async function continueRestoredPurchase(
  recorder,
  contract,
  restored,
  token,
) {
  const governedActors = [...stage(contract, 'financeCountersign').actorIds];
  if (governedActors.length !== 2
      || restored.activeTasks.length !== governedActors.length) {
    throw new Error('restored countersign assignments are inconsistent');
  }
  let remaining = [...restored.activeTasks];
  let result;
  for (const actor of governedActors) {
    const task = remaining.find(candidate => candidate.assigneeId === actor);
    if (!task) throw new Error(`restored countersign lost actor ${actor}`);
    result = await approve(
      recorder,
      task,
      actor,
      token,
      `financeCountersign/${actor}`,
    );
    remaining = (result.activeTasks || []).filter(candidate =>
      candidate.taskDefinitionKey === 'financeCountersign');
  }
  const payment = exactTasks(
    result,
    'paymentConfirmation',
    1,
    'restored countersign completion',
  )[0];
  result = await approve(
    recorder,
    payment,
    stage(contract, 'paymentConfirmation').actorIds[0],
    token,
    'paymentConfirmation',
  );
  if (result.instanceStatus !== 'COMPLETED'
      || !Array.isArray(result.activeTasks)
      || result.activeTasks.length !== 0) {
    throw new Error('restored purchase did not complete');
  }
  return {
    instanceId: result.instanceId,
    completedTaskId: result.completedTaskId,
    instanceStatus: result.instanceStatus,
    completedAt: result.completedAt,
  };
}
