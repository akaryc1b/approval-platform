import { spawnSync } from 'node:child_process';
import {
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  statSync,
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
  recoveryOutputRoot,
  repositoryRoot,
  runIdentifier,
  sourceIdentity,
  writeJson,
} from './contract.mjs';
import {
  appendCiEvidenceEnvelope,
  sha256File,
} from './evidence.mjs';
import {
  percentile,
  runBoundedPool,
  summarizeByOperation,
  summarizeSamples,
} from './statistics.mjs';

const backendTimeoutMs = 15 * 60_000;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu;

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
  if (remaining <= 0) throw new Error(`${label} exceeded the governed runtime deadline`);
  return remaining;
}

function resetDisposableData(environment, timeoutMs) {
  runNodeChecked(
    'Delete only the disposable local demo volume',
    [
      'scripts/product-readiness/demo-backend.mjs',
      'reset',
      '--confirm-local-data-loss',
    ],
    environment,
    timeoutMs,
  );
}

function captureVersion(command, args, environment) {
  const result = spawnSync(command, args, {
    cwd: repositoryRoot,
    encoding: 'utf8',
    env: environment,
    shell: false,
    timeout: 10_000,
  });
  if (result.error || result.status !== 0) {
    throw new Error(
      `${command} version capture failed: ${result.error?.message || result.stderr || result.stdout}`,
    );
  }
  return `${result.stdout || ''}\n${result.stderr || ''}`
    .split(/\r?\n/u)
    .map(line => line.trim())
    .filter(Boolean)
    .slice(0, 4);
}

function command(name) {
  if (process.platform === 'win32' && ['mvn', 'pnpm'].includes(name)) {
    return `${name}.cmd`;
  }
  return process.platform === 'win32' ? `${name}.exe` : name;
}

function environmentSnapshot(environment) {
  return {
    schemaVersion: 1,
    evidenceKind: 'CAPACITY_RECOVERY_ENVIRONMENT_V1',
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
    tools: {
      node: [process.version],
      java: captureVersion(command('java'), ['-version'], environment),
      maven: captureVersion(command('mvn'), ['-version'], environment),
      pnpm: captureVersion(command('pnpm'), ['--version'], environment),
      docker: captureVersion(command('docker'), ['--version'], environment),
      compose: captureVersion(
        command('docker'),
        ['compose', 'version'],
        environment,
      ),
      git: captureVersion(command('git'), ['--version'], environment),
    },
  };
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

function capturePostgresSnapshot(label) {
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
    `Capture PostgreSQL ${label} observations`,
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
    throw new Error(`capacity target is not PostgreSQL 16: ${value.serverVersion}`);
  }
  return {
    schemaVersion: 1,
    evidenceKind: 'CAPACITY_POSTGRES_OBSERVATION_V1',
    label,
    capturedAt: new Date().toISOString(),
    ...value,
  };
}

function captureProcessSnapshot(label, rootPid) {
  if (process.platform === 'win32') {
    return {
      schemaVersion: 1,
      evidenceKind: 'CAPACITY_PROCESS_OBSERVATION_V1',
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
  const processes = result.stdout.split(/\r?\n/u).map(line => line.trim())
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
    for (const entry of processes) {
      if (selected.has(entry.parentPid) && !selected.has(entry.pid)) {
        selected.add(entry.pid);
        changed = true;
      }
    }
  }
  const tree = processes.filter(entry => selected.has(entry.pid));
  return {
    schemaVersion: 1,
    evidenceKind: 'CAPACITY_PROCESS_OBSERVATION_V1',
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

function workflowStage(contract, taskDefinitionKey) {
  const matches = contract.scenario.expectedWorkflow.filter(stage =>
    stage.taskDefinitionKey === taskDefinitionKey);
  if (matches.length !== 1) {
    throw new Error(`governed workflow must contain one ${taskDefinitionKey} stage`);
  }
  return matches[0];
}

function activeTasks(payload, taskDefinitionKey, expectedCount, label) {
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

function createRequestRecorder(runId, contract) {
  const samples = [];
  let sequence = 0;
  const timeoutMs = contract.smallDemo.workload.requestTimeoutMs;

  async function request(operation, path, options = {}) {
    sequence += 1;
    const requestId = `capacity-${runId.slice(-12)}-${sequence}`;
    const startedEpochMs = Date.now();
    const started = performance.now();
    let status = 0;
    let parsed;
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
        signal: AbortSignal.timeout(timeoutMs),
      });
      status = response.status;
      const text = await response.text();
      if (!response.ok) {
        failure = new Error(`${operation} returned HTTP ${status}: ${text.slice(0, 1000)}`);
      } else if (text.trim()) {
        try {
          parsed = JSON.parse(text);
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
    return parsed;
  }

  return { request, samples };
}

async function uploadAttachment(recorder, contract, businessKey, index, runToken) {
  const configuredBytes = contract.smallDemo.dataset.attachmentBytesPerRequest;
  const prefix = `capacity evidence ${businessKey}\n`;
  const content = prefix.padEnd(Math.max(prefix.length, configuredBytes), '.');
  const formData = new FormData();
  formData.append(
    'file',
    new Blob([content], { type: 'text/plain' }),
    `capacity-${index + 1}.txt`,
  );
  const payload = unwrapObject(await recorder.request(
    'attachment-upload',
    '/api/approval/attachments',
    {
      actorId: contract.scenario.assigneeRules.initiatorUserId.value,
      method: 'POST',
      idempotencyKey: `capacity-upload-${runToken}-${index + 1}`,
      formData,
    },
  ));
  if (!uuid.test(payload?.attachmentId || '')) {
    throw new Error('attachment upload did not return a UUID');
  }
  return payload;
}

async function createPurchase(recorder, contract, index, runToken) {
  const baseBusinessKey = contract.scenario.request.businessKey;
  const businessKey = `${baseBusinessKey}-CAP-${runToken}-${String(index + 1).padStart(2, '0')}`;
  const attachment = await uploadAttachment(
    recorder,
    contract,
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
      idempotencyKey: `capacity-start-${runToken}-${index + 1}`,
      json: {
        ...contract.scenario.request,
        businessKey,
        purchaseOrderReference: `PO-CAP-${runToken}-${index + 1}`,
        attachmentIds: [attachment.attachmentId],
        assigneeRules: contract.scenario.assigneeRules,
      },
    },
  ));
  if (!uuid.test(payload?.instanceId || '') || payload.status !== 'RUNNING') {
    throw new Error(`purchase start returned an invalid instance: ${JSON.stringify(payload)}`);
  }
  const managerTasks = activeTasks(
    payload,
    'managerApproval',
    1,
    `${businessKey} start`,
  );
  return {
    businessKey,
    attachmentId: attachment.attachmentId,
    instanceId: payload.instanceId,
    startedAt: payload.startedAt,
    tasks: managerTasks,
    queueDelaysMs: [queueDelay(payload.startedAt, managerTasks[0].createdAt)],
  };
}

async function runReadPressure(recorder, contract, instances) {
  const manager = workflowStage(contract, 'managerApproval').actorIds[0];
  const requests = Array.from(
    { length: contract.smallDemo.workload.readRequests },
    (_, index) => ({
      index,
      instance: instances[index % instances.length],
      kind: index % 2 === 0 ? 'list' : 'detail',
    }),
  );
  await runBoundedPool(
    requests,
    Math.min(contract.smallDemo.workload.readConcurrency, requests.length),
    async ({ instance, kind }) => {
      const task = instance.tasks[0];
      if (kind === 'list') {
        const query = new URLSearchParams({
          keyword: instance.businessKey,
          limit: '20',
          offset: '0',
        });
        const page = unwrapObject(await recorder.request(
          'pending-list',
          `/api/approval/tasks/pending?${query}`,
          { actorId: manager },
        ));
        if (!Array.isArray(page?.items)
            || !page.items.some(item =>
              item.taskId === task.taskId
                && item.businessKey === instance.businessKey)) {
          throw new Error(`pending list lost ${instance.businessKey}/${task.taskId}`);
        }
        return;
      }
      const details = unwrapObject(await recorder.request(
        'pending-detail',
        `/api/approval/tasks/pending/${task.taskId}`,
        { actorId: manager },
      ));
      if (details?.taskId !== task.taskId
          || details?.businessKey !== instance.businessKey) {
        throw new Error(`pending detail lost ${instance.businessKey}/${task.taskId}`);
      }
    },
  );
}

async function approveTask(recorder, task, actorId, runToken, label) {
  const payload = unwrapObject(await recorder.request(
    `approve-${task.taskDefinitionKey}`,
    `/api/approval/tasks/${task.taskId}/approve`,
    {
      actorId,
      method: 'POST',
      idempotencyKey: `capacity-approve-${runToken}-${task.taskId}`,
      json: { comment: `Capacity baseline ${label}` },
    },
  ));
  if (payload?.completedTaskId !== task.taskId
      || payload?.instanceId !== task.instanceId) {
    throw new Error(`${label} returned an inconsistent task action result`);
  }
  return payload;
}

async function advanceSingleStage(
  recorder,
  contract,
  instances,
  currentKey,
  nextKey,
  expectedNextCount,
  runToken,
) {
  const actor = workflowStage(contract, currentKey).actorIds[0];
  return runBoundedPool(
    instances,
    Math.min(contract.smallDemo.workload.approvalConcurrency, instances.length),
    async (instance) => {
      const task = instance.tasks[0];
      if (task.taskDefinitionKey !== currentKey) {
        throw new Error(`${instance.businessKey} is not at ${currentKey}`);
      }
      const result = await approveTask(
        recorder,
        task,
        actor,
        runToken,
        `${instance.businessKey}/${currentKey}`,
      );
      const nextTasks = activeTasks(
        result,
        nextKey,
        expectedNextCount,
        `${instance.businessKey}/${currentKey}`,
      );
      return {
        ...instance,
        tasks: nextTasks,
        queueDelaysMs: [
          ...instance.queueDelaysMs,
          ...nextTasks.map(next => queueDelay(result.completedAt, next.createdAt)),
        ],
      };
    },
  );
}

async function advanceCountersign(recorder, contract, instances, runToken) {
  const governedActors = [...workflowStage(contract, 'financeCountersign').actorIds];
  if (governedActors.length !== 2) {
    throw new Error('financeCountersign must have exactly two governed actors');
  }
  return runBoundedPool(
    instances,
    Math.min(contract.smallDemo.workload.approvalConcurrency, instances.length),
    async (instance) => {
      const initial = [...instance.tasks].sort((left, right) =>
        left.assigneeId.localeCompare(right.assigneeId));
      if (initial.length !== governedActors.length
          || !governedActors.every(actor =>
            initial.some(task => task.assigneeId === actor))) {
        throw new Error(`${instance.businessKey} countersign assignments are inconsistent`);
      }
      let remaining = initial;
      let result;
      for (const actor of governedActors) {
        const task = remaining.find(candidate => candidate.assigneeId === actor);
        if (!task) throw new Error(`${instance.businessKey} lost countersign actor ${actor}`);
        result = await approveTask(
          recorder,
          task,
          actor,
          runToken,
          `${instance.businessKey}/financeCountersign/${actor}`,
        );
        remaining = (result.activeTasks || []).filter(candidate =>
          candidate.taskDefinitionKey === 'financeCountersign');
      }
      const paymentTasks = activeTasks(
        result,
        'paymentConfirmation',
        1,
        `${instance.businessKey}/financeCountersign`,
      );
      return {
        ...instance,
        tasks: paymentTasks,
        countersignTaskCount: initial.length,
        queueDelaysMs: [
          ...instance.queueDelaysMs,
          queueDelay(result.completedAt, paymentTasks[0].createdAt),
        ],
      };
    },
  );
}

async function completePayments(recorder, contract, instances, runToken) {
  const actor = workflowStage(contract, 'paymentConfirmation').actorIds[0];
  return runBoundedPool(
    instances,
    Math.min(contract.smallDemo.workload.approvalConcurrency, instances.length),
    async (instance) => {
      const task = instance.tasks[0];
      const result = await approveTask(
        recorder,
        task,
        actor,
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
        completedAt: result.completedAt,
        finalStatus: result.instanceStatus,
      };
    },
  );
}

async function verifyCompleted(recorder, contract, instances) {
  await runBoundedPool(
    instances,
    Math.min(contract.smallDemo.workload.readConcurrency, instances.length),
    async (instance) => {
      const details = unwrapObject(await recorder.request(
        'completed-instance',
        `/api/approval/instances/${instance.instanceId}`,
        { actorId: contract.scenario.assigneeRules.initiatorUserId.value },
      ));
      if (details?.instance?.status !== 'COMPLETED'
          || details.instance.businessKey !== instance.businessKey) {
        throw new Error(`${instance.businessKey} completed instance read is inconsistent`);
      }
      const timeline = unwrapObject(await recorder.request(
        'completed-timeline',
        `/api/approval/instances/${instance.instanceId}/timeline`,
        { actorId: contract.scenario.assigneeRules.initiatorUserId.value },
      ));
      if (!Array.isArray(timeline?.items)
          || timeline.items.length < 6
          || new Set(timeline.items.map(item => item.eventId)).size
            !== timeline.items.length) {
        throw new Error(`${instance.businessKey} timeline is incomplete`);
      }
    },
  );
}

function validateProfileThresholds(profile, thresholds) {
  const failures = [];
  if (profile.requestSummary.errorRate > thresholds.maximumErrorRate) {
    failures.push(
      `errorRate ${profile.requestSummary.errorRate} > ${thresholds.maximumErrorRate}`,
    );
  }
  if (profile.readSummary.latencyMs.p95 > thresholds.maximumReadP95Ms) {
    failures.push(
      `read p95 ${profile.readSummary.latencyMs.p95}ms > ${thresholds.maximumReadP95Ms}ms`,
    );
  }
  if (profile.readSummary.latencyMs.p99 > thresholds.maximumReadP99Ms) {
    failures.push(
      `read p99 ${profile.readSummary.latencyMs.p99}ms > ${thresholds.maximumReadP99Ms}ms`,
    );
  }
  if (profile.readSummary.throughputPerSecond
      < thresholds.minimumReadThroughputPerSecond) {
    failures.push(
      `read throughput ${profile.readSummary.throughputPerSecond}/s < ${thresholds.minimumReadThroughputPerSecond}/s`,
    );
  }
  if (profile.completedFlowsPerSecond
      < thresholds.minimumCompletedFlowsPerSecond) {
    failures.push(
      `completed flows ${profile.completedFlowsPerSecond}/s < ${thresholds.minimumCompletedFlowsPerSecond}/s`,
    );
  }
  if (failures.length > 0) {
    throw new Error(`Small Demo thresholds failed: ${failures.join('; ')}`);
  }
}

async function cleanupSmallDemo(
  backend,
  environment,
  runDirectory,
  runtimeMutationStarted,
) {
  const actions = [];
  if (backend) {
    await stopManaged(backend);
    actions.push('stopped:existing-demo-backend-lifecycle');
  }
  if (runtimeMutationStarted) {
    resetDisposableData(environment, 15 * 60_000);
    actions.push('deleted:approval-platform-demo-volume');
    for (const port of [5432, 6379, 8080]) {
      await waitForPortAvailable(port);
      actions.push(`released-port:${port}`);
    }
  } else {
    actions.push('skipped-reset:failure-before-runtime-mutation');
  }
  const evidence = {
    schemaVersion: 1,
    evidenceKind: 'CAPACITY_SMALL_DEMO_CLEANUP_V1',
    actions,
    completedAt: new Date().toISOString(),
  };
  writeJson(resolve(runDirectory, 'small-demo-cleanup.json'), evidence);
  return evidence;
}

async function executeSmallDemo(contract, runId, runDirectory, deadline) {
  const environment = java21Environment();
  const recorder = createRequestRecorder(runId, contract);
  let backend;
  let result;
  let executionError;
  let cleanupError;
  let cleanup;
  let runtimeMutationStarted = false;
  try {
    resetDisposableData(
      environment,
      remainingMilliseconds(deadline, 'initial disposable reset'),
    );
    runtimeMutationStarted = true;
    backend = startManagedNode(
      'Start the existing demo backend lifecycle for Small Demo measurement',
      ['scripts/product-readiness/demo-backend.mjs', 'start'],
      resolve(runDirectory, 'backend.log'),
      environment,
    );
    await waitForMarker(
      backend,
      'BACKEND_LOCAL_START_VERIFIED',
      Math.min(
        backendTimeoutMs,
        remainingMilliseconds(deadline, 'backend readiness'),
      ),
    );
    await waitForMarker(
      backend,
      'PURCHASE_PAYMENT_DEMO_SEED_APPLIED',
      Math.min(
        backendTimeoutMs,
        remainingMilliseconds(deadline, 'deterministic Seed readiness'),
      ),
    );

    const postgresBefore = capturePostgresSnapshot('before-small-demo-workload');
    const processBefore = captureProcessSnapshot('before-small-demo-workload', backend.child.pid);
    writeJson(resolve(runDirectory, 'postgres-before.json'), postgresBefore);
    writeJson(resolve(runDirectory, 'process-before.json'), processBefore);

    const runToken = runId.replace(/[^0-9A-Za-z]/gu, '').slice(-12);
    const indices = Array.from(
      { length: contract.smallDemo.workload.generatedInstances },
      (_, index) => index,
    );
    let instances = await runBoundedPool(
      indices,
      Math.min(contract.smallDemo.workload.startConcurrency, indices.length),
      index => createPurchase(recorder, contract, index, runToken),
    );
    await runReadPressure(recorder, contract, instances);
    instances = await advanceSingleStage(
      recorder,
      contract,
      instances,
      'managerApproval',
      'financeReview',
      1,
      runToken,
    );
    instances = await advanceSingleStage(
      recorder,
      contract,
      instances,
      'financeReview',
      'financeCountersign',
      2,
      runToken,
    );
    instances = await advanceCountersign(
      recorder,
      contract,
      instances,
      runToken,
    );
    instances = await completePayments(
      recorder,
      contract,
      instances,
      runToken,
    );
    await verifyCompleted(recorder, contract, instances);

    const postgresAfter = capturePostgresSnapshot('after-small-demo-workload');
    const processAfter = captureProcessSnapshot('after-small-demo-workload', backend.child.pid);
    writeJson(resolve(runDirectory, 'postgres-after.json'), postgresAfter);
    writeJson(resolve(runDirectory, 'process-after.json'), processAfter);
    writeJson(resolve(runDirectory, 'request-samples.json'), {
      schemaVersion: 1,
      evidenceKind: 'CAPACITY_HTTP_REQUEST_SAMPLES_V1',
      samples: recorder.samples,
    });

    const readSamples = recorder.samples.filter(sample =>
      sample.operation === 'pending-list'
        || sample.operation === 'pending-detail');
    const firstRead = Math.min(...readSamples.map(sample => sample.startedEpochMs));
    const lastRead = Math.max(...readSamples.map(sample => sample.completedEpochMs));
    const firstStart = Math.min(...recorder.samples
      .filter(sample => sample.operation === 'purchase-start')
      .map(sample => sample.startedEpochMs));
    const lastCompletion = Math.max(...recorder.samples
      .filter(sample => sample.operation === 'approve-paymentConfirmation')
      .map(sample => sample.completedEpochMs));
    const workflowElapsedMs = Math.max(1, lastCompletion - firstStart);
    const queueDelays = instances.flatMap(instance => instance.queueDelaysMs);
    result = {
      schemaVersion: 1,
      evidenceKind: 'CAPACITY_SMALL_DEMO_PROFILE_V1',
      profileId: contract.smallDemo.id,
      displayName: contract.smallDemo.displayName,
      status: 'PASSED_AT_CONFIGURED_POINT_ONLY',
      databaseVendor: contract.databaseVendor,
      applicationInstances: contract.applicationInstances,
      dataset: contract.smallDemo.dataset,
      workload: contract.smallDemo.workload,
      thresholds: contract.smallDemo.thresholds,
      generatedInstances: instances.length,
      completedInstances: instances.filter(instance =>
        instance.finalStatus === 'COMPLETED').length,
      totalApprovalTasksCompleted: instances.length * 5,
      parallelCountersignTasksPerInstance: 2,
      measuredParallelInstanceWorkers: Math.min(
        contract.smallDemo.workload.approvalConcurrency,
        instances.length,
      ),
      requestSummary: summarizeSamples(
        recorder.samples,
        Math.max(
          1,
          Math.max(...recorder.samples.map(sample => sample.completedEpochMs))
            - Math.min(...recorder.samples.map(sample => sample.startedEpochMs)),
        ),
      ),
      operations: summarizeByOperation(recorder.samples),
      readSummary: summarizeSamples(readSamples, Math.max(1, lastRead - firstRead)),
      workflowElapsedMs,
      completedFlowsPerSecond: rounded(instances.length / (workflowElapsedMs / 1000)),
      queueDelayMs: durationSummary(queueDelays),
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
      verifiedEnvelope: {
        generatedInstances: instances.length,
        startConcurrency: contract.smallDemo.workload.startConcurrency,
        approvalConcurrency: contract.smallDemo.workload.approvalConcurrency,
        readConcurrency: contract.smallDemo.workload.readConcurrency,
        readRequests: contract.smallDemo.workload.readRequests,
        beyondConfiguredPoint: 'NOT_TESTED',
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
    writeJson(resolve(runDirectory, 'small-demo-profile.json'), result);
    validateProfileThresholds(result, contract.smallDemo.thresholds);
  } catch (error) {
    executionError = error;
    if (recorder.samples.length > 0) {
      writeJson(resolve(runDirectory, 'request-samples.json'), {
        schemaVersion: 1,
        evidenceKind: 'CAPACITY_HTTP_REQUEST_SAMPLES_V1',
        samples: recorder.samples,
      });
    }
  } finally {
    try {
      cleanup = await cleanupSmallDemo(
        backend,
        environment,
        runDirectory,
        runtimeMutationStarted,
      );
    } catch (error) {
      cleanupError = error;
    }
  }
  if (executionError || cleanupError) {
    throw new Error(
      `Small Demo execution failed: ${JSON.stringify({
        execution: executionError instanceof Error
          ? executionError.message
          : executionError ? String(executionError) : null,
        cleanup: cleanupError instanceof Error
          ? cleanupError.message
          : cleanupError ? String(cleanupError) : null,
      })}`,
      { cause: executionError || cleanupError },
    );
  }
  return { profile: result, cleanup };
}

function recoveryCandidate(identity) {
  if (!existsSync(recoveryOutputRoot)) return null;
  const candidates = readdirSync(recoveryOutputRoot, { withFileTypes: true })
    .filter(entry => entry.isDirectory())
    .map(entry => resolve(recoveryOutputRoot, entry.name))
    .filter(directory => existsSync(resolve(directory, 'runtime-summary.json')))
    .sort((left, right) =>
      statSync(resolve(right, 'runtime-summary.json')).mtimeMs
        - statSync(resolve(left, 'runtime-summary.json')).mtimeMs);
  for (const directory of candidates) {
    const summary = JSON.parse(readFileSync(
      resolve(directory, 'runtime-summary.json'),
      'utf8',
    ));
    if (summary.commitSha === identity.commitSha
        && summary.treeSha === identity.treeSha
        && summary.evidenceKind === 'PURCHASE_PAYMENT_LOCAL_ALPHA_E2E_V1'
        && summary.claimsDeclared === true
        && Array.isArray(summary.successfulRunIds)
        && summary.successfulRunIds.length >= 2) {
      return { directory, summary };
    }
  }
  return null;
}

function validateRecoveryCandidate(candidate, contract) {
  const pendingPath = resolve(
    candidate.directory,
    'outbox-pending-evidence.json',
  );
  const deliveredPath = resolve(
    candidate.directory,
    'outbox-delivered-evidence.json',
  );
  const cleanupPath = resolve(candidate.directory, 'cleanup-evidence.json');
  for (const path of [pendingPath, deliveredPath, cleanupPath]) {
    if (!existsSync(path)) throw new Error(`recovery source is missing ${path}`);
  }
  const pending = JSON.parse(readFileSync(pendingPath, 'utf8'));
  const delivered = JSON.parse(readFileSync(deliveredPath, 'utf8'));
  const cleanup = JSON.parse(readFileSync(cleanupPath, 'utf8'));
  if (candidate.summary.finalStatus !== 'COMPLETED'
      || candidate.summary.outboxStatus !== 'DELIVERED'
      || candidate.summary.acceptedPaymentSideEffects !== 1
      || pending?.outbox?.length !== 1
      || pending.outbox[0].status !== 'PENDING'
      || pending.outbox[0].attempts < 1
      || pending?.sandbox?.lastHttpStatus !== 503
      || delivered?.outbox?.length !== 1
      || delivered.outbox[0].status !== 'DELIVERED'
      || delivered.outbox[0].responseCode !== 200
      || delivered?.sandbox?.acceptedPaymentResults !== 1
      || !cleanup?.actions?.includes('deleted:approval-platform-demo-volume')) {
    throw new Error('purchase-payment recovery evidence is inconsistent');
  }
  const pendingMetadata = statSync(pendingPath);
  const deliveredMetadata = statSync(deliveredPath);
  const observedSeconds = rounded(Math.max(
    0,
    (deliveredMetadata.mtimeMs - pendingMetadata.mtimeMs) / 1000,
  ));
  if (observedSeconds > contract.recovery.maximumObservedDrainSeconds) {
    throw new Error(
      `observed recovery ${observedSeconds}s exceeded ${contract.recovery.maximumObservedDrainSeconds}s`,
    );
  }
  return {
    schemaVersion: 1,
    evidenceKind: 'CAPACITY_OUTBOX_RECOVERY_REUSE_V1',
    sourceRunId: candidate.summary.runId,
    sourceEvidenceKind: candidate.summary.evidenceKind,
    sourceSuccessfulRunIds: candidate.summary.successfulRunIds,
    finalStatus: candidate.summary.finalStatus,
    pending: {
      outboxStatus: pending.outbox[0].status,
      attempts: pending.outbox[0].attempts,
      sandboxHttpStatus: pending.sandbox.lastHttpStatus,
      evidenceWrittenAt: pendingMetadata.mtime.toISOString(),
      sha256: sha256File(pendingPath),
    },
    delivered: {
      outboxStatus: delivered.outbox[0].status,
      attempts: delivered.outbox[0].attempts,
      responseCode: delivered.outbox[0].responseCode,
      acceptedPaymentResults: delivered.sandbox.acceptedPaymentResults,
      evidenceWrittenAt: deliveredMetadata.mtime.toISOString(),
      sha256: sha256File(deliveredPath),
    },
    pendingToDeliveredEvidenceSeconds: observedSeconds,
    maximumObservedDrainSeconds: contract.recovery.maximumObservedDrainSeconds,
    measurementBoundary:
      'FILESYSTEM_EVIDENCE_INTERVAL_NOT_PRODUCTION_RTO',
    rpoRtoClaim: 'RPO_RTO_NOT_VERIFIED',
    capturedAt: new Date().toISOString(),
  };
}

function ensureAcceptedRecoveryEvidence(
  contract,
  identity,
  reuseRecoveryEvidence,
  deadline,
) {
  let candidate = recoveryCandidate(identity);
  if (!candidate && reuseRecoveryEvidence) {
    throw new Error(
      'preceding exact-Head purchase-payment E2E evidence is unavailable in CI',
    );
  }
  let attempts = 0;
  while (!candidate && attempts < 2) {
    attempts += 1;
    runNodeChecked(
      `Execute accepted purchase-payment recovery run ${attempts}/2`,
      ['scripts/product-readiness/purchase-payment-e2e.mjs', 'run'],
      {
        ...java21Environment(),
        APPROVAL_DEMO_COMMAND_TIMEOUT_MS: String(
          Math.min(60 * 60_000, remainingMilliseconds(deadline, 'recovery E2E')),
        ),
      },
      Math.min(60 * 60_000, remainingMilliseconds(deadline, 'recovery E2E')),
    );
    candidate = recoveryCandidate(identity);
  }
  if (!candidate) {
    throw new Error('two clean exact-Head recovery runs did not publish accepted evidence');
  }
  return validateRecoveryCandidate(candidate, contract);
}

export async function execute(contract, { reuseRecoveryEvidence }) {
  const identity = sourceIdentity();
  const runId = runIdentifier();
  const runDirectory = resolve(outputRoot, runId);
  mkdirSync(runDirectory, { recursive: true, mode: 0o700 });
  const startedAt = new Date();
  const deadline = startedAt.getTime() + contract.maximumRuntimeSeconds * 1000;
  writeJson(resolve(runDirectory, 'source-identity.json'), {
    schemaVersion: 1,
    evidenceKind: 'CAPACITY_RECOVERY_SOURCE_IDENTITY_V1',
    runId,
    capturedAt: startedAt.toISOString(),
    ...identity,
  });
  writeJson(resolve(runDirectory, 'profile-contract.json'), {
    schemaVersion: 1,
    databaseVendor: contract.databaseVendor,
    applicationInstances: contract.applicationInstances,
    maximumRuntimeSeconds: contract.maximumRuntimeSeconds,
    profiles: contract.profiles,
    recovery: contract.recovery,
    claims: contract.claims,
    nonClaims: contract.nonClaims,
  });
  writeJson(
    resolve(runDirectory, 'environment.json'),
    environmentSnapshot(java21Environment()),
  );

  try {
    const smallDemo = await executeSmallDemo(
      contract,
      runId,
      runDirectory,
      deadline,
    );
    const recovery = ensureAcceptedRecoveryEvidence(
      contract,
      identity,
      reuseRecoveryEvidence,
      deadline,
    );
    writeJson(resolve(runDirectory, 'recovery-summary.json'), recovery);
    const completedAt = new Date();
    const summary = {
      schemaVersion: 1,
      evidenceKind: 'CAPACITY_RECOVERY_INITIAL_SLICE_V1',
      status: 'PASSED',
      runId,
      commitSha: identity.commitSha,
      treeSha: identity.treeSha,
      databaseVendor: contract.databaseVendor,
      applicationInstances: contract.applicationInstances,
      executedProfile: contract.smallDemo.id,
      profile: smallDemo.profile,
      recovery,
      cleanup: smallDemo.cleanup,
      claims: contract.claims,
      nonClaims: contract.nonClaims,
      startedAt: startedAt.toISOString(),
      completedAt: completedAt.toISOString(),
      elapsedSeconds: rounded(
        (completedAt.getTime() - startedAt.getTime()) / 1000,
      ),
    };
    writeJson(resolve(runDirectory, 'runtime-summary.json'), summary);
    appendCiEvidenceEnvelope('PASSED', runDirectory, identity);
    console.log(`CAPACITY_RECOVERY_RUN_ID=${runId}`);
    console.log(`CAPACITY_RECOVERY_EVIDENCE=${runDirectory}`);
    for (const claim of contract.claims) console.log(claim);
    for (const nonClaim of contract.nonClaims) console.log(nonClaim);
  } catch (error) {
    writeJson(resolve(runDirectory, 'runtime-failure.json'), {
      schemaVersion: 1,
      evidenceKind: 'CAPACITY_RECOVERY_FAILURE_V1',
      runId,
      failedAt: new Date().toISOString(),
      detail: error instanceof Error ? error.message : String(error),
    });
    appendCiEvidenceEnvelope('FAILED', runDirectory, identity);
    throw error;
  }
}
