import { spawnSync } from 'node:child_process';
import {
  mkdirSync,
  writeFileSync,
} from 'node:fs';
import { arch, cpus, platform, release, totalmem } from 'node:os';
import { resolve } from 'node:path';
import { performance } from 'node:perf_hooks';

import { java21Environment } from '../pc-h5-runtime/contract.mjs';
import {
  delay,
  runNodeChecked,
  startManagedNode,
  terminateManaged,
  waitForMarker,
} from '../pc-h5-runtime/processes.mjs';
import {
  eventType,
  executable,
  requireSuccess,
  runCaptured,
} from '../purchase-payment-e2e/contract.mjs';
import { waitForPortAvailable } from '../purchase-payment-e2e/evidence.mjs';
import {
  backendOrigin,
  composeFile,
  composeProject,
  outputRoot,
  repositoryRoot,
  runIdentifier,
  sourceIdentity,
  writeJson,
} from './contract.mjs';
import { appendProfileMatrixEnvelope } from './profile-matrix-evidence.mjs';
import {
  percentile,
  runBoundedPool,
  summarizeByOperation,
  summarizeSamples,
} from './statistics.mjs';

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu;
const backendTimeoutMs = 15 * 60_000;

function rounded(value) {
  return Number(value.toFixed(3));
}

function requiredText(value, label) {
  if (typeof value !== 'string' || value.trim().length === 0) {
    throw new Error(`${label} is required`);
  }
  return value.trim();
}

function parsedInstant(value, label) {
  const timestamp = Date.parse(requiredText(value, label));
  if (!Number.isFinite(timestamp)) throw new Error(`${label} must be an ISO instant`);
  return timestamp;
}

function unwrapObject(value) {
  if (value?.data && typeof value.data === 'object') return value.data;
  return value;
}

function composeArguments(...args) {
  return [
    'compose',
    '--project-name',
    composeProject,
    '-f',
    composeFile,
    ...args,
  ];
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
  if (!processState) return;
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

function remainingMilliseconds(deadline, label) {
  const remaining = deadline - Date.now();
  if (remaining <= 0) throw new Error(`${label} exceeded the profile-matrix deadline`);
  return remaining;
}

function resetDisposableData(environment, timeoutMs) {
  runNodeChecked(
    'Delete only the disposable local demo volume for profile matrix',
    [
      'scripts/product-readiness/demo-backend.mjs',
      'reset',
      '--confirm-local-data-loss',
    ],
    environment,
    timeoutMs,
  );
}

function capturePostgres(label) {
  const sql = [
    'select json_build_object(',
    "  'serverVersion', current_setting('server_version'),",
    "  'databaseSizeBytes', pg_database_size(current_database()),",
    "  'connections', stats.numbackends,",
    "  'activeConnections', (select count(*) from pg_stat_activity where datname = current_database() and state = 'active'),",
    "  'xactCommit', stats.xact_commit,",
    "  'xactRollback', stats.xact_rollback,",
    "  'blocksRead', stats.blks_read,",
    "  'blocksHit', stats.blks_hit,",
    "  'temporaryBytes', stats.temp_bytes,",
    "  'deadlocks', stats.deadlocks,",
    "  'lockCount', (select count(*) from pg_locks where database = (select oid from pg_database where datname = current_database()))",
    ')::text',
    'from pg_stat_database stats',
    'where stats.datname = current_database();',
  ].join('\n');
  const output = requireSuccess(
    `Capture PostgreSQL ${label}`,
    runCaptured(
      executable('docker'),
      composeArguments(
        'exec',
        '-T',
        'postgres',
        'psql',
        '-U',
        'approval',
        '-d',
        'approval',
        '-At',
        '-c',
        sql,
      ),
    ),
  );
  const value = JSON.parse(output);
  if (!String(value.serverVersion || '').startsWith('16.')) {
    throw new Error(`profile matrix is not running on PostgreSQL 16: ${value.serverVersion}`);
  }
  return {
    schemaVersion: 1,
    evidenceKind: 'CAPACITY_PROFILE_POSTGRES_OBSERVATION_V1',
    label,
    capturedAt: new Date().toISOString(),
    ...value,
  };
}

function captureProcess(label, rootPid) {
  if (process.platform === 'win32') {
    return {
      schemaVersion: 1,
      evidenceKind: 'CAPACITY_PROFILE_PROCESS_OBSERVATION_V1',
      label,
      capturedAt: new Date().toISOString(),
      supported: false,
      reason: 'Windows process-tree observation is not implemented',
    };
  }
  const result = spawnSync('ps', ['-eo', 'pid=,ppid=,rss=,vsz=,%cpu=,comm='], {
    cwd: repositoryRoot,
    encoding: 'utf8',
    env: process.env,
    shell: false,
    timeout: 10_000,
  });
  if (result.error || result.status !== 0) {
    throw new Error(`process observation failed: ${result.error?.message || result.stderr}`);
  }
  const entries = result.stdout.split(/\r?\n/u)
    .map(line => line.trim())
    .filter(Boolean)
    .map((line) => {
      const match = line.match(/^(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+([0-9.]+)\s+(.+)$/u);
      if (!match) return null;
      return {
        pid: Number(match[1]),
        parentPid: Number(match[2]),
        rssKilobytes: Number(match[3]),
        virtualKilobytes: Number(match[4]),
        cpuPercent: Number(match[5]),
        command: match[6],
      };
    })
    .filter(Boolean);
  const selected = new Set([rootPid]);
  let changed = true;
  while (changed) {
    changed = false;
    for (const entry of entries) {
      if (selected.has(entry.parentPid) && !selected.has(entry.pid)) {
        selected.add(entry.pid);
        changed = true;
      }
    }
  }
  const tree = entries.filter(entry => selected.has(entry.pid));
  return {
    schemaVersion: 1,
    evidenceKind: 'CAPACITY_PROFILE_PROCESS_OBSERVATION_V1',
    label,
    capturedAt: new Date().toISOString(),
    supported: true,
    pointInTimeOnly: true,
    processCount: tree.length,
    rssBytes: tree.reduce((total, entry) => total + entry.rssKilobytes * 1024, 0),
    virtualBytes: tree.reduce(
      (total, entry) => total + entry.virtualKilobytes * 1024,
      0,
    ),
    cpuPercentSum: rounded(
      tree.reduce((total, entry) => total + entry.cpuPercent, 0),
    ),
    processes: tree,
  };
}

function hostSnapshot() {
  return {
    schemaVersion: 1,
    evidenceKind: 'CAPACITY_PROFILE_HOST_V1',
    capturedAt: new Date().toISOString(),
    operatingSystem: {
      platform: platform(),
      release: release(),
      architecture: arch(),
    },
    cpu: {
      logicalCount: cpus().length,
      models: [...new Set(cpus().map(cpu => cpu.model))],
    },
    memoryBytes: totalmem(),
    nodeVersion: process.version,
  };
}

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
      `${label} expected ${expectedCount} ${taskDefinitionKey} task(s): ${JSON.stringify(value.activeTasks)}`,
    );
  }
  for (const task of matches) {
    if (!uuid.test(task.taskId || '') || !uuid.test(task.instanceId || '')) {
      throw new Error(`${label} returned an invalid task identity`);
    }
  }
  return matches;
}

function queueDelay(previousCompletedAt, taskCreatedAt) {
  return Math.max(
    0,
    parsedInstant(taskCreatedAt, 'task.createdAt')
      - parsedInstant(previousCompletedAt, 'previous completedAt'),
  );
}

function durationSummary(values) {
  if (values.length === 0) return null;
  return {
    observations: values.length,
    minimum: rounded(Math.min(...values)),
    p50: rounded(percentile(values, 0.5)),
    p95: rounded(percentile(values, 0.95)),
    p99: rounded(percentile(values, 0.99)),
    maximum: rounded(Math.max(...values)),
  };
}

function createRecorder(profile, contract, runToken) {
  const samples = [];
  let sequence = 0;

  async function request(operation, path, options = {}) {
    sequence += 1;
    const requestId = `profile-${runToken}-${sequence}`;
    const startedEpochMs = Date.now();
    const started = performance.now();
    let status = 0;
    let payload;
    let failure;
    try {
      const headers = {
        Accept: 'application/json',
        'Cache-Control': 'no-store',
        'X-Operator-Id': requiredText(options.actorId, `${operation}.actorId`),
        'X-Request-Id': requestId,
        'X-Tenant-Id': contract.scenario.tenant.id,
        'X-Trace-Id': requestId,
      };
      let body;
      if (options.json !== undefined) {
        headers['Content-Type'] = 'application/json';
        body = JSON.stringify(options.json);
      } else if (options.formData !== undefined) {
        body = options.formData;
      }
      if (options.idempotencyKey) {
        headers['Idempotency-Key'] = options.idempotencyKey;
      }
      const response = await fetch(`${backendOrigin}${path}`, {
        method: options.method || 'GET',
        headers,
        body,
        signal: AbortSignal.timeout(profile.workload.requestTimeoutMs),
      });
      status = response.status;
      const text = await response.text();
      if (!response.ok) {
        failure = new Error(`${operation} returned HTTP ${status}: ${text.slice(0, 1000)}`);
      } else if (text.trim()) {
        try {
          payload = JSON.parse(text);
        } catch (error) {
          failure = new Error(`${operation} returned invalid JSON`, { cause: error });
        }
      }
    } catch (error) {
      failure = error;
    }
    const completedEpochMs = Date.now();
    samples.push({
      sequence,
      operation,
      method: options.method || 'GET',
      path,
      status,
      ok: !failure,
      startedEpochMs,
      completedEpochMs,
      latencyMs: rounded(performance.now() - started),
    });
    if (failure) throw failure;
    return payload;
  }

  return { request, samples };
}

async function uploadAttachment(recorder, contract, profile, businessKey, index, runToken) {
  const configuredBytes = Number(profile.dataset.attachmentBytesPerRequest);
  const prefix = `capacity ${profile.id} ${businessKey}\n`;
  const content = prefix.padEnd(Math.max(prefix.length, configuredBytes), '.');
  const formData = new FormData();
  formData.append(
    'file',
    new Blob([content], { type: 'text/plain' }),
    `${profile.id}-${index + 1}.txt`,
  );
  const payload = unwrapObject(await recorder.request(
    'attachment-upload',
    '/api/approval/attachments',
    {
      actorId: contract.scenario.assigneeRules.initiatorUserId.value,
      method: 'POST',
      idempotencyKey: `${profile.id}-upload-${runToken}-${index + 1}`,
      formData,
    },
  ));
  if (!uuid.test(payload?.attachmentId || '')) {
    throw new Error(`${profile.id} attachment upload did not return a UUID`);
  }
  return payload.attachmentId;
}

async function startPurchase(recorder, contract, profile, index, runToken) {
  const businessKey = `${contract.scenario.request.businessKey}-${profile.id.toUpperCase()}-${runToken}-${String(index + 1).padStart(3, '0')}`;
  const attachmentId = await uploadAttachment(
    recorder,
    contract,
    profile,
    businessKey,
    index,
    runToken,
  );
  const payload = unwrapObject(await recorder.request(
    'purchase-start',
    '/api/approval/instances/purchase-payment',
    {
      actorId: contract.scenario.assigneeRules.initiatorUserId.value,
      method: 'POST',
      idempotencyKey: `${profile.id}-start-${runToken}-${index + 1}`,
      json: {
        ...contract.scenario.request,
        businessKey,
        purchaseOrderReference: `PO-${profile.id}-${runToken}-${index + 1}`,
        attachmentIds: [attachmentId],
        assigneeRules: contract.scenario.assigneeRules,
      },
    },
  ));
  if (!uuid.test(payload?.instanceId || '') || payload.status !== 'RUNNING') {
    throw new Error(`${profile.id} start returned an invalid instance`);
  }
  const tasks = exactActiveTasks(
    payload,
    'managerApproval',
    1,
    `${businessKey} start`,
  );
  return {
    businessKey,
    attachmentId,
    instanceId: payload.instanceId,
    startedAt: payload.startedAt,
    tasks,
    queueDelaysMs: [queueDelay(payload.startedAt, tasks[0].createdAt)],
  };
}

async function readPressure(
  recorder,
  contract,
  instances,
  requestCount,
  concurrency,
  prefix,
) {
  const manager = governedStage(contract, 'managerApproval').actorIds[0];
  const work = Array.from({ length: requestCount }, (_, index) => ({
    instance: instances[index % instances.length],
    kind: index % 2 === 0 ? 'list' : 'detail',
  }));
  const started = Date.now();
  const startIndex = recorder.samples.length;
  await runBoundedPool(
    work,
    Math.min(concurrency, work.length),
    async ({ instance, kind }) => {
      const task = instance.tasks[0];
      if (kind === 'list') {
        const query = new URLSearchParams({
          keyword: instance.businessKey,
          limit: '20',
          offset: '0',
        });
        const page = unwrapObject(await recorder.request(
          `${prefix}-pending-list`,
          `/api/approval/tasks/pending?${query}`,
          { actorId: manager },
        ));
        if (!Array.isArray(page?.items)
            || !page.items.some(item =>
              item.taskId === task.taskId
                && item.businessKey === instance.businessKey)) {
          throw new Error(`${prefix} pending list lost ${instance.businessKey}`);
        }
        return;
      }
      const details = unwrapObject(await recorder.request(
        `${prefix}-pending-detail`,
        `/api/approval/tasks/pending/${task.taskId}`,
        { actorId: manager },
      ));
      if (details?.taskId !== task.taskId
          || details?.businessKey !== instance.businessKey) {
        throw new Error(`${prefix} pending detail lost ${instance.businessKey}`);
      }
    },
  );
  const elapsedMs = Math.max(1, Date.now() - started);
  return summarizeSamples(recorder.samples.slice(startIndex), elapsedMs);
}

async function approveTask(recorder, task, actorId, profileId, runToken, label) {
  const payload = unwrapObject(await recorder.request(
    `approve-${task.taskDefinitionKey}`,
    `/api/approval/tasks/${task.taskId}/approve`,
    {
      actorId,
      method: 'POST',
      idempotencyKey: `${profileId}-approve-${runToken}-${task.taskId}`,
      json: { comment: `Capacity profile ${label}` },
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
  profile,
  instances,
  currentKey,
  nextKey,
  expectedNextCount,
  runToken,
) {
  const actor = governedStage(contract, currentKey).actorIds[0];
  return runBoundedPool(
    instances,
    Math.min(profile.workload.approvalConcurrency, instances.length),
    async (instance) => {
      const task = instance.tasks[0];
      if (task.taskDefinitionKey !== currentKey) {
        throw new Error(`${instance.businessKey} is not at ${currentKey}`);
      }
      const result = await approveTask(
        recorder,
        task,
        actor,
        profile.id,
        runToken,
        `${instance.businessKey}/${currentKey}`,
      );
      const tasks = exactActiveTasks(
        result,
        nextKey,
        expectedNextCount,
        `${instance.businessKey}/${currentKey}`,
      );
      return {
        ...instance,
        tasks,
        queueDelaysMs: [
          ...instance.queueDelaysMs,
          ...tasks.map(next => queueDelay(result.completedAt, next.createdAt)),
        ],
      };
    },
  );
}

async function advanceCountersign(recorder, contract, profile, instances, runToken) {
  const actors = [...governedStage(contract, 'financeCountersign').actorIds];
  if (actors.length !== 2) {
    throw new Error('financeCountersign must retain two governed actors');
  }
  return runBoundedPool(
    instances,
    Math.min(profile.workload.approvalConcurrency, instances.length),
    async (instance) => {
      const initial = [...instance.tasks];
      if (initial.length !== 2
          || !actors.every(actor =>
            initial.some(task => task.assigneeId === actor))) {
        throw new Error(`${instance.businessKey} countersign assignments are inconsistent`);
      }
      let remaining = initial;
      let result;
      for (const actor of actors) {
        const task = remaining.find(candidate => candidate.assigneeId === actor);
        if (!task) throw new Error(`${instance.businessKey} lost actor ${actor}`);
        result = await approveTask(
          recorder,
          task,
          actor,
          profile.id,
          runToken,
          `${instance.businessKey}/financeCountersign/${actor}`,
        );
        remaining = (result.activeTasks || []).filter(candidate =>
          candidate.taskDefinitionKey === 'financeCountersign');
      }
      const tasks = exactActiveTasks(
        result,
        'paymentConfirmation',
        1,
        `${instance.businessKey}/financeCountersign`,
      );
      return {
        ...instance,
        tasks,
        countersignTaskCount: initial.length,
        queueDelaysMs: [
          ...instance.queueDelaysMs,
          queueDelay(result.completedAt, tasks[0].createdAt),
        ],
      };
    },
  );
}

async function completePayments(recorder, contract, profile, instances, runToken) {
  const actor = governedStage(contract, 'paymentConfirmation').actorIds[0];
  return runBoundedPool(
    instances,
    Math.min(profile.workload.approvalConcurrency, instances.length),
    async (instance) => {
      const task = instance.tasks[0];
      const result = await approveTask(
        recorder,
        task,
        actor,
        profile.id,
        runToken,
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

async function verifyCompleted(recorder, contract, profile, instances) {
  await runBoundedPool(
    instances,
    Math.min(profile.workload.readConcurrency, instances.length),
    async (instance, index) => {
      const details = unwrapObject(await recorder.request(
        'completed-instance',
        `/api/approval/instances/${instance.instanceId}`,
        { actorId: contract.scenario.assigneeRules.initiatorUserId.value },
      ));
      if (details?.instance?.status !== 'COMPLETED'
          || details.instance.businessKey !== instance.businessKey) {
        throw new Error(`${instance.businessKey} completed read is inconsistent`);
      }
      if (index % Math.max(1, Math.floor(instances.length / 12)) !== 0) return;
      const timeline = unwrapObject(await recorder.request(
        'sampled-completed-timeline',
        `/api/approval/instances/${instance.instanceId}/timeline`,
        { actorId: contract.scenario.assigneeRules.initiatorUserId.value },
      ));
      if (!Array.isArray(timeline?.items)
          || timeline.items.length < 6
          || new Set(timeline.items.map(item => item.eventId)).size
            !== timeline.items.length) {
        throw new Error(`${instance.businessKey} sampled timeline is incomplete`);
      }
    },
  );
}

function safeSql(value, label) {
  const text = requiredText(value, label);
  if (!/^[0-9A-Za-z._:-]+$/u.test(text)) {
    throw new Error(`${label} contains unsupported SQL evidence characters`);
  }
  return `'${text}'`;
}

function queryOutboxBacklog(instanceIds) {
  if (instanceIds.length === 0 || instanceIds.some(value => !uuid.test(value))) {
    throw new Error('Outbox backlog query requires UUID instance IDs');
  }
  const sql = [
    'select status, count(*)::text',
    'from ap_outbox',
    `where event_type = ${safeSql(eventType(), 'eventType')}`,
    `  and aggregate_id in (${instanceIds.map(value => safeSql(value, 'instanceId')).join(', ')})`,
    'group by status',
    'order by status;',
  ].join('\n');
  const output = requireSuccess(
    'Measure completion Outbox backlog creation',
    runCaptured(
      executable('docker'),
      composeArguments(
        'exec',
        '-T',
        'postgres',
        'psql',
        '-U',
        'approval',
        '-d',
        'approval',
        '-At',
        '-F',
        '|',
        '-c',
        sql,
      ),
    ),
  );
  const byStatus = {};
  if (output) {
    for (const line of output.split(/\r?\n/u).filter(Boolean)) {
      const [status, count] = line.split('|');
      byStatus[status] = Number(count);
    }
  }
  return {
    schemaVersion: 1,
    evidenceKind: 'CAPACITY_OUTBOX_BACKLOG_CREATION_V1',
    eventType: eventType(),
    instanceCount: instanceIds.length,
    byStatus,
    totalRows: Object.values(byStatus).reduce((total, value) => total + value, 0),
    dispatcherEnabled: false,
    drainClaim: 'OUTBOX_CONNECTOR_BACKLOG_DRAIN_VOLUME_NOT_VERIFIED',
    capturedAt: new Date().toISOString(),
  };
}

function validateThresholds(summary, profile) {
  const failures = [];
  if (summary.requestSummary.errorRate > profile.thresholds.maximumErrorRate) {
    failures.push(`errorRate ${summary.requestSummary.errorRate}`);
  }
  if (summary.configuredRead.latencyMs.p95
      > profile.thresholds.maximumReadP95Ms) {
    failures.push(`read p95 ${summary.configuredRead.latencyMs.p95}ms`);
  }
  if (summary.configuredRead.latencyMs.p99
      > profile.thresholds.maximumReadP99Ms) {
    failures.push(`read p99 ${summary.configuredRead.latencyMs.p99}ms`);
  }
  if (summary.configuredRead.throughputPerSecond
      < profile.thresholds.minimumReadThroughputPerSecond) {
    failures.push(`read throughput ${summary.configuredRead.throughputPerSecond}/s`);
  }
  if (summary.completedFlowsPerSecond
      < profile.thresholds.minimumCompletedFlowsPerSecond) {
    failures.push(`flow throughput ${summary.completedFlowsPerSecond}/s`);
  }
  if (summary.outboxBacklog.totalRows !== summary.completedInstances
      || summary.outboxBacklog.byStatus.PENDING !== summary.completedInstances
      || Object.keys(summary.outboxBacklog.byStatus).length !== 1) {
    failures.push('completion Outbox backlog did not remain exactly PENDING');
  }
  if (failures.length > 0) {
    throw new Error(`${profile.id} thresholds failed: ${failures.join('; ')}`);
  }
}

async function runProfile(contract, profile, backend, runId, cumulativeBefore) {
  const token = `${profile.id.replaceAll('-', '')}${runId.replace(/[^0-9A-Za-z]/gu, '').slice(-8)}`;
  const recorder = createRecorder(profile, contract, token);
  const postgresBefore = capturePostgres(`${profile.id}-before`);
  const processBefore = captureProcess(`${profile.id}-before`, backend.child.pid);
  const indices = Array.from(
    { length: profile.workload.generatedInstances },
    (_, index) => index,
  );
  const firstStartEpochMs = Date.now();
  let instances = await runBoundedPool(
    indices,
    Math.min(profile.workload.startConcurrency, indices.length),
    index => startPurchase(recorder, contract, profile, index, token),
  );
  const configuredRead = await readPressure(
    recorder,
    contract,
    instances,
    profile.workload.readRequests,
    profile.workload.readConcurrency,
    'configured',
  );
  const overloadRead = await readPressure(
    recorder,
    contract,
    instances,
    profile.workload.overloadReadRequests,
    profile.workload.overloadReadConcurrency,
    'overload',
  );
  instances = await advanceSingle(
    recorder,
    contract,
    profile,
    instances,
    'managerApproval',
    'financeReview',
    1,
    token,
  );
  instances = await advanceSingle(
    recorder,
    contract,
    profile,
    instances,
    'financeReview',
    'financeCountersign',
    2,
    token,
  );
  instances = await advanceCountersign(
    recorder,
    contract,
    profile,
    instances,
    token,
  );
  instances = await completePayments(
    recorder,
    contract,
    profile,
    instances,
    token,
  );
  const lastCompletionEpochMs = Date.now();
  await verifyCompleted(recorder, contract, profile, instances);
  const outboxBacklog = queryOutboxBacklog(
    instances.map(instance => instance.instanceId),
  );
  const postgresAfter = capturePostgres(`${profile.id}-after`);
  const processAfter = captureProcess(`${profile.id}-after`, backend.child.pid);
  const firstSample = Math.min(...recorder.samples.map(sample => sample.startedEpochMs));
  const lastSample = Math.max(...recorder.samples.map(sample => sample.completedEpochMs));
  const workflowElapsedMs = Math.max(1, lastCompletionEpochMs - firstStartEpochMs);
  const summary = {
    schemaVersion: 1,
    evidenceKind: 'CAPACITY_LOCAL_REFERENCE_PROFILE_V1',
    profileId: profile.id,
    displayName: profile.displayName,
    status: 'PASSED_AT_CONFIGURED_POINT_ONLY',
    databaseVendor: contract.databaseVendor,
    applicationInstances: contract.applicationInstances,
    dataset: profile.dataset,
    workload: profile.workload,
    thresholds: profile.thresholds,
    generatedInstances: instances.length,
    cumulativeGeneratedInstances:
      cumulativeBefore + instances.length,
    completedInstances: instances.filter(instance =>
      instance.finalStatus === 'COMPLETED').length,
    totalApprovalTasksCompleted: instances.length * 5,
    parallelCountersignTasksPerInstance: 2,
    configuredRead,
    overloadRead: {
      ...overloadRead,
      configuredConcurrency: profile.workload.overloadReadConcurrency,
      interpretation:
        'HIGHER_THAN_CONFIGURED_READ_POINT_OBSERVED_NOT_MAXIMUM_ENVELOPE',
    },
    requestSummary: summarizeSamples(
      recorder.samples,
      Math.max(1, lastSample - firstSample),
    ),
    operations: summarizeByOperation(recorder.samples),
    workflowElapsedMs,
    completedFlowsPerSecond: rounded(
      instances.length / (workflowElapsedMs / 1000),
    ),
    queueDelayMs: durationSummary(
      instances.flatMap(instance => instance.queueDelaysMs),
    ),
    outboxBacklog,
    database: {
      before: postgresBefore,
      after: postgresAfter,
      storageGrowthBytes:
        Number(postgresAfter.databaseSizeBytes)
          - Number(postgresBefore.databaseSizeBytes),
      connectionDelta:
        Number(postgresAfter.connections) - Number(postgresBefore.connections),
      deadlockDelta:
        Number(postgresAfter.deadlocks) - Number(postgresBefore.deadlocks),
    },
    process: {
      before: processBefore,
      after: processAfter,
      peakResourceEnvelope: 'NOT_MEASURED_POINT_IN_TIME_ONLY',
    },
    highestTestedStablePoint: {
      generatedInstances: instances.length,
      startConcurrency: profile.workload.startConcurrency,
      approvalConcurrency: profile.workload.approvalConcurrency,
      configuredReadConcurrency: profile.workload.readConcurrency,
      higherReadConcurrency: profile.workload.overloadReadConcurrency,
      maximumStableEnvelope: 'NOT_SEARCHED',
    },
    instances: instances.map(instance => ({
      businessKey: instance.businessKey,
      attachmentId: instance.attachmentId,
      instanceId: instance.instanceId,
      finalStatus: instance.finalStatus,
      countersignTaskCount: instance.countersignTaskCount,
    })),
    completedAt: new Date().toISOString(),
  };
  validateThresholds(summary, profile);
  return { summary, samples: recorder.samples };
}

async function cleanup(backend, environment, runDirectory, mutated) {
  const actions = [];
  if (backend) {
    await stopManaged(backend);
    actions.push('stopped:existing-demo-backend-lifecycle');
  }
  if (mutated) {
    resetDisposableData(environment, 15 * 60_000);
    actions.push('deleted:approval-platform-demo-volume');
    for (const port of [5432, 6379, 8080]) {
      await waitForPortAvailable(port);
      actions.push(`released-port:${port}`);
    }
  } else {
    actions.push('skipped-reset:failure-before-runtime-mutation');
  }
  const value = {
    schemaVersion: 1,
    evidenceKind: 'CAPACITY_PROFILE_MATRIX_CLEANUP_V1',
    actions,
    completedAt: new Date().toISOString(),
  };
  writeJson(resolve(runDirectory, 'profile-matrix-cleanup.json'), value);
  return value;
}

export async function executeProfileMatrix(contract) {
  const identity = sourceIdentity();
  const runId = `${runIdentifier()}-profiles`;
  const runDirectory = resolve(outputRoot, runId);
  mkdirSync(runDirectory, { recursive: true, mode: 0o700 });
  const startedAt = new Date();
  const deadline = startedAt.getTime()
    + contract.extendedProfileRuntimeSeconds * 1000;
  writeJson(resolve(runDirectory, 'source-identity.json'), {
    schemaVersion: 1,
    evidenceKind: 'CAPACITY_PROFILE_MATRIX_SOURCE_IDENTITY_V1',
    runId,
    capturedAt: startedAt.toISOString(),
    ...identity,
  });
  writeJson(resolve(runDirectory, 'profile-matrix-contract.json'), {
    schemaVersion: 1,
    databaseVendor: contract.databaseVendor,
    applicationInstances: contract.applicationInstances,
    profiles: [contract.standardDeployment, contract.largeTenant],
    extendedClaims: contract.extendedClaims,
    extendedNonClaims: contract.extendedNonClaims,
  });
  writeJson(resolve(runDirectory, 'profile-matrix-host.json'), hostSnapshot());

  const environment = java21Environment();
  let backend;
  let mutated = false;
  let executionError;
  let cleanupError;
  let cleanupEvidence;
  let standard;
  let large;
  try {
    resetDisposableData(
      environment,
      remainingMilliseconds(deadline, 'profile-matrix initial reset'),
    );
    mutated = true;
    backend = startManagedNode(
      'Start the existing demo backend for Standard/Large local profiles',
      ['scripts/product-readiness/demo-backend.mjs', 'start'],
      resolve(runDirectory, 'profile-matrix-backend.log'),
      environment,
    );
    await waitForMarker(
      backend,
      'BACKEND_LOCAL_START_VERIFIED',
      Math.min(
        backendTimeoutMs,
        remainingMilliseconds(deadline, 'profile-matrix backend readiness'),
      ),
    );
    await waitForMarker(
      backend,
      'PURCHASE_PAYMENT_DEMO_SEED_APPLIED',
      Math.min(
        backendTimeoutMs,
        remainingMilliseconds(deadline, 'profile-matrix Seed readiness'),
      ),
    );

    standard = await runProfile(
      contract,
      contract.standardDeployment,
      backend,
      runId,
      0,
    );
    writeJson(
      resolve(runDirectory, 'standard-deployment-request-samples.json'),
      {
        schemaVersion: 1,
        evidenceKind: 'CAPACITY_PROFILE_HTTP_SAMPLES_V1',
        profileId: contract.standardDeployment.id,
        samples: standard.samples,
      },
    );
    writeJson(
      resolve(runDirectory, 'standard-deployment-profile.json'),
      standard.summary,
    );
    remainingMilliseconds(deadline, 'after standard deployment profile');

    large = await runProfile(
      contract,
      contract.largeTenant,
      backend,
      runId,
      standard.summary.cumulativeGeneratedInstances,
    );
    writeJson(
      resolve(runDirectory, 'large-tenant-request-samples.json'),
      {
        schemaVersion: 1,
        evidenceKind: 'CAPACITY_PROFILE_HTTP_SAMPLES_V1',
        profileId: contract.largeTenant.id,
        samples: large.samples,
      },
    );
    writeJson(
      resolve(runDirectory, 'large-tenant-profile.json'),
      large.summary,
    );
  } catch (error) {
    executionError = error;
  } finally {
    try {
      cleanupEvidence = await cleanup(
        backend,
        environment,
        runDirectory,
        mutated,
      );
    } catch (error) {
      cleanupError = error;
    }
  }

  if (executionError || cleanupError) {
    const failure = {
      schemaVersion: 1,
      evidenceKind: 'CAPACITY_PROFILE_MATRIX_FAILURE_V1',
      runId,
      failedAt: new Date().toISOString(),
      execution: executionError instanceof Error
        ? executionError.message
        : executionError ? String(executionError) : null,
      cleanup: cleanupError instanceof Error
        ? cleanupError.message
        : cleanupError ? String(cleanupError) : null,
    };
    writeJson(resolve(runDirectory, 'profile-matrix-failure.json'), failure);
    appendProfileMatrixEnvelope('FAILED', runDirectory, identity);
    throw new Error(`capacity profile matrix failed: ${JSON.stringify(failure)}`, {
      cause: executionError || cleanupError,
    });
  }

  const completedAt = new Date();
  const summary = {
    schemaVersion: 1,
    evidenceKind: 'CAPACITY_PROFILE_MATRIX_V1',
    status: 'PASSED',
    runId,
    commitSha: identity.commitSha,
    treeSha: identity.treeSha,
    databaseVendor: contract.databaseVendor,
    applicationInstances: contract.applicationInstances,
    host: hostSnapshot(),
    standardDeployment: standard.summary,
    largeTenant: large.summary,
    highestTestedStablePoint: {
      profileId: large.summary.profileId,
      cumulativeGeneratedInstances:
        large.summary.cumulativeGeneratedInstances,
      startConcurrency:
        large.summary.highestTestedStablePoint.startConcurrency,
      approvalConcurrency:
        large.summary.highestTestedStablePoint.approvalConcurrency,
      higherReadConcurrency:
        large.summary.highestTestedStablePoint.higherReadConcurrency,
      maximumStableEnvelope: 'NOT_SEARCHED',
    },
    outboxBacklogCreation: {
      standardRows: standard.summary.outboxBacklog.totalRows,
      largeRows: large.summary.outboxBacklog.totalRows,
      cumulativeRows:
        standard.summary.outboxBacklog.totalRows
          + large.summary.outboxBacklog.totalRows,
      drainVolumeClaim:
        'OUTBOX_CONNECTOR_BACKLOG_DRAIN_VOLUME_NOT_VERIFIED',
    },
    cleanup: cleanupEvidence,
    claims: contract.extendedClaims,
    nonClaims: contract.extendedNonClaims,
    startedAt: startedAt.toISOString(),
    completedAt: completedAt.toISOString(),
    elapsedSeconds: rounded(
      (completedAt.getTime() - startedAt.getTime()) / 1000,
    ),
  };
  writeJson(resolve(runDirectory, 'profile-matrix-summary.json'), summary);
  appendProfileMatrixEnvelope('PASSED', runDirectory, identity);
  console.log(`CAPACITY_PROFILE_MATRIX_RUN_ID=${runId}`);
  console.log(`CAPACITY_PROFILE_MATRIX_EVIDENCE=${runDirectory}`);
  for (const claim of contract.extendedClaims) console.log(claim);
  for (const nonClaim of contract.extendedNonClaims) console.log(nonClaim);
  return summary;
}
