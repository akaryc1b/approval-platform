#!/usr/bin/env node

import { createHash } from 'node:crypto';
import {
  mkdirSync,
  readFileSync,
  renameSync,
  writeFileSync,
} from 'node:fs';
import {
  dirname,
  extname,
  isAbsolute,
  relative,
  resolve,
  sep,
} from 'node:path';
import { fileURLToPath } from 'node:url';

import { summarizeTaskHistory } from './runtime-task-history.mjs';

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const scenarioPath = resolve(
  repositoryRoot,
  'config/demo/purchase-payment-golden-path.json',
);
const crossClientPath = resolve(
  repositoryRoot,
  'config/demo/cross-client-local-demo.json',
);
const apiBasePath = '/api/approval';
const healthPath = '/actuator/health';
const commands = new Set(['plan', 'observe']);
const defaultOutput = 'build/product-readiness/cross-client-runtime-evidence.json';
const defaultTimeoutSeconds = 600;
const defaultPollIntervalMs = 1_000;
const requestTimeoutMs = 5_000;

class UsageError extends Error {}

function usage() {
  return `Usage: node scripts/product-readiness/demo-runtime-evidence.mjs <command> [options]\n\nCommands:\n  plan      Print the read-only interactive evidence plan.\n  observe   Observe a human-driven PC/H5/WeChat handoff against the real local backend.\n\nOptions:\n  --backend-origin <origin>       Loopback or RFC1918 HTTP backend origin.\n  --output <path>                 JSON path below build/product-readiness/.\n  --timeout-seconds <seconds>     Per-stage timeout, 30-1800 (default 600).\n  --poll-interval-ms <ms>         Poll interval, 250-5000 (default 1000).\n  --confirm-interactive-run       Required for observe. The script itself performs no approval.\n  --json                          Machine-readable plan output.\n  --help                          Show this help.\n\nThe observe command reads backend state while a person completes actions in the\nlisted development clients. It does not automate a browser, prove a physical\nWeChat device, or verify production payment integration.`;
}

function optionValue(values, index, name) {
  const current = values[index];
  const inlinePrefix = `${name}=`;
  if (current.startsWith(inlinePrefix)) {
    return { consumed: 1, value: current.slice(inlinePrefix.length) };
  }
  if (current !== name) return undefined;
  const value = values[index + 1];
  if (!value || value.startsWith('--')) {
    throw new UsageError(`${name} requires a value`);
  }
  return { consumed: 2, value };
}

function parseInteger(value, name, minimum, maximum) {
  if (!/^\d+$/u.test(value)) {
    throw new UsageError(`${name} must be an integer`);
  }
  const parsed = Number.parseInt(value, 10);
  if (parsed < minimum || parsed > maximum) {
    throw new UsageError(`${name} must be between ${minimum} and ${maximum}`);
  }
  return parsed;
}

function parseArguments(argv) {
  const values = argv.filter(value => value !== '--');
  const command = values.shift() || 'plan';
  if (!commands.has(command)) throw new UsageError(`Unknown command: ${command}`);

  const options = {
    backendOrigin: undefined,
    command,
    confirmInteractiveRun: false,
    help: false,
    json: false,
    output: defaultOutput,
    pollIntervalMs: defaultPollIntervalMs,
    timeoutSeconds: defaultTimeoutSeconds,
  };

  for (let index = 0; index < values.length;) {
    const value = values[index];
    if (value === '--help') {
      options.help = true;
      index += 1;
      continue;
    }
    if (value === '--json') {
      options.json = true;
      index += 1;
      continue;
    }
    if (value === '--confirm-interactive-run') {
      options.confirmInteractiveRun = true;
      index += 1;
      continue;
    }
    const backendOrigin = optionValue(values, index, '--backend-origin');
    if (backendOrigin) {
      options.backendOrigin = backendOrigin.value;
      index += backendOrigin.consumed;
      continue;
    }
    const output = optionValue(values, index, '--output');
    if (output) {
      options.output = output.value;
      index += output.consumed;
      continue;
    }
    const timeout = optionValue(values, index, '--timeout-seconds');
    if (timeout) {
      options.timeoutSeconds = parseInteger(
        timeout.value,
        '--timeout-seconds',
        30,
        1_800,
      );
      index += timeout.consumed;
      continue;
    }
    const pollInterval = optionValue(values, index, '--poll-interval-ms');
    if (pollInterval) {
      options.pollIntervalMs = parseInteger(
        pollInterval.value,
        '--poll-interval-ms',
        250,
        5_000,
      );
      index += pollInterval.consumed;
      continue;
    }
    throw new UsageError(`Unknown option: ${value}`);
  }

  if (command === 'plan' && (
    options.backendOrigin
    || options.confirmInteractiveRun
    || options.output !== defaultOutput
    || options.pollIntervalMs !== defaultPollIntervalMs
    || options.timeoutSeconds !== defaultTimeoutSeconds
  )) {
    throw new UsageError('plan accepts only --json and --help');
  }
  if (command === 'observe' && options.json) {
    throw new UsageError('--json is only available for plan');
  }
  if (command === 'observe' && !options.confirmInteractiveRun) {
    throw new UsageError('observe requires --confirm-interactive-run');
  }
  return options;
}

function requireText(value, name) {
  if (typeof value !== 'string' || !value.trim()) {
    throw new Error(`${name} must be a non-empty string`);
  }
  return value.trim();
}

function requireStringList(value, name) {
  if (!Array.isArray(value) || value.length === 0) {
    throw new Error(`${name} must be a non-empty array`);
  }
  const normalized = value.map((item, index) =>
    requireText(item, `${name}[${index}]`));
  if (new Set(normalized).size !== normalized.length) {
    throw new Error(`${name} must not contain duplicates`);
  }
  return normalized;
}

function loadContract() {
  const scenarioSource = readFileSync(scenarioPath, 'utf8');
  const crossClientSource = readFileSync(crossClientPath, 'utf8');
  const scenario = JSON.parse(scenarioSource);
  const manifest = JSON.parse(crossClientSource);

  if (scenario.schemaVersion !== 1 || manifest.schemaVersion !== 1) {
    throw new Error('unsupported demo contract schemaVersion');
  }
  if (manifest.scenarioManifest !== 'config/demo/purchase-payment-golden-path.json') {
    throw new Error('cross-client scenarioManifest is not canonical');
  }
  if (manifest.tenantId !== scenario.tenant?.id) {
    throw new Error('cross-client tenant does not match the golden path');
  }
  if (manifest.businessKey !== scenario.request?.businessKey) {
    throw new Error('cross-client businessKey does not match the golden path');
  }
  if (manifest.connectorKey !== scenario.directory?.connectorKey) {
    throw new Error('cross-client connectorKey does not match the golden path');
  }

  const knownActors = new Set(
    scenario.directory?.users?.map(user => requireText(user.id, 'user.id')),
  );
  const expected = scenario.expectedWorkflow.flatMap(step =>
    step.actorIds.map(actorId => ({
      actorId,
      taskDefinitionKey: step.taskDefinitionKey,
    })));
  if (!Array.isArray(manifest.expectedHandoff)
    || manifest.expectedHandoff.length !== expected.length) {
    throw new Error('cross-client handoff cardinality does not match expectedWorkflow');
  }

  const handoffs = manifest.expectedHandoff.map((handoff, index) => {
    const expectedStep = expected[index];
    const client = requireText(handoff.client, `handoff[${index}].client`);
    const actorId = requireText(handoff.actorId, `handoff[${index}].actorId`);
    const taskDefinitionKey = requireText(
      handoff.taskDefinitionKey,
      `handoff[${index}].taskDefinitionKey`,
    );
    if (actorId !== expectedStep.actorId
      || taskDefinitionKey !== expectedStep.taskDefinitionKey) {
      throw new Error(`cross-client handoff[${index}] does not match expectedWorkflow`);
    }
    if (!knownActors.has(actorId)) {
      throw new Error(`cross-client handoff[${index}] references unknown actor`);
    }
    const clientContract = manifest.clients?.[client];
    if (!clientContract) {
      throw new Error(`cross-client handoff[${index}] references unknown client`);
    }
    const allowedActors = requireStringList(
      clientContract.allowedActors,
      `${client}.allowedActors`,
    );
    if (!allowedActors.includes(actorId)) {
      throw new Error(`${actorId} is not allowed for ${client}`);
    }
    return {
      actorId,
      client,
      clientCommand: clientCommand(client, actorId),
      clientLocation: clientLocation(client, clientContract, actorId),
      sequence: index + 1,
      taskDefinitionKey,
    };
  });

  return {
    contractHashes: {
      crossClientSha256: sha256(crossClientSource),
      scenarioSha256: sha256(scenarioSource),
    },
    handoffs,
    manifest,
    scenario,
  };
}

function sha256(value) {
  return createHash('sha256').update(value, 'utf8').digest('hex');
}

function clientCommand(client, actorId) {
  return `pnpm demo:client:${client} -- --actor ${actorId} --skip-install`;
}

function clientLocation(client, contract, actorId) {
  const actor = encodeURIComponent(actorId);
  if (client === 'pc') {
    return `http://127.0.0.1:${contract.defaultPort}${contract.route}`
      + `?demoOperator=${actor}`;
  }
  if (client === 'h5') {
    return `http://127.0.0.1:${contract.defaultPort}/#${contract.route}`
      + `?demoOperator=${actor}`;
  }
  return `${contract.route}?demoOperator=${actor}`;
}

function launchPlan(contract) {
  return {
    schemaVersion: 1,
    evidenceKind: 'CROSS_CLIENT_INTERACTIVE_RUNTIME_OBSERVER_V1',
    backendCommand: 'pnpm demo:backend:start',
    tenantId: contract.manifest.tenantId,
    businessKey: contract.manifest.businessKey,
    handoffs: contract.handoffs,
    finalRead: {
      actorId: 'demo-admin',
      client: 'pc',
      clientCommand: 'pnpm demo:client:pc -- --actor demo-admin --port 5778 --skip-install',
      clientLocation: 'http://127.0.0.1:5778/approval/workbench?demoOperator=demo-admin',
    },
    output: defaultOutput,
    claimsAvailableAfterSuccessfulObservation: [
      'CROSS_CLIENT_SHARED_INSTANCE_OBSERVED',
      'CROSS_CLIENT_ROLE_HANDOFFS_OBSERVED',
      'PURCHASE_APPROVAL_RUNTIME_COMPLETED',
    ],
    nonClaims: [
      'AUTOMATED_BROWSER_E2E_NOT_EXECUTED',
      'CLIENT_SCREEN_RECORDING_NOT_INCLUDED',
      'WECHAT_PHYSICAL_DEVICE_NOT_VERIFIED',
      'BROWSER_COMPATIBILITY_NOT_VERIFIED',
      'ACCESSIBILITY_NOT_VERIFIED',
      'QUICK_START_10_MINUTES_NOT_EXECUTED',
      'PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED',
    ],
  };
}

function printPlan(contract, jsonOutput) {
  const plan = launchPlan(contract);
  if (jsonOutput) {
    console.log(JSON.stringify(plan, null, 2));
    return;
  }
  console.log('Approval Platform interactive cross-client evidence plan');
  console.log(JSON.stringify(plan, null, 2));
}

function isPrivateIpv4(hostname) {
  const octets = hostname.split('.').map(value => Number.parseInt(value, 10));
  if (octets.length !== 4 || octets.some(value => !Number.isInteger(value))) {
    return false;
  }
  if (octets.some(value => value < 0 || value > 255)) return false;
  return octets[0] === 10
    || octets[0] === 127
    || (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31)
    || (octets[0] === 192 && octets[1] === 168);
}

function normalizeBackendOrigin(value) {
  let origin;
  try {
    origin = new URL(value);
  } catch {
    throw new UsageError('--backend-origin must be an absolute URL');
  }
  const hostname = origin.hostname.toLowerCase();
  const localHost = hostname === 'localhost'
    || hostname === '::1'
    || isPrivateIpv4(hostname);
  if (origin.protocol !== 'http:' || !localHost || origin.username || origin.password) {
    throw new UsageError('--backend-origin must be a loopback or RFC1918 HTTP origin');
  }
  if (origin.pathname !== '/' || origin.search || origin.hash) {
    throw new UsageError('--backend-origin must not contain a path, query or hash');
  }
  return origin.origin;
}

function resolveOutputPath(value) {
  if (isAbsolute(value) || extname(value).toLowerCase() !== '.json') {
    throw new UsageError('--output must be a relative JSON path');
  }
  const allowedRoot = resolve(repositoryRoot, 'build/product-readiness');
  const output = resolve(repositoryRoot, value);
  const relativePath = relative(allowedRoot, output);
  if (relativePath === '..'
    || relativePath.startsWith(`..${sep}`)
    || isAbsolute(relativePath)) {
    throw new UsageError('--output must stay below build/product-readiness/');
  }
  return output;
}

function requestHeaders(contract, actorId, requestId) {
  return {
    Accept: 'application/json',
    'X-Operator-Id': actorId,
    'X-Request-Id': requestId,
    'X-Tenant-Id': contract.manifest.tenantId,
    'X-Trace-Id': requestId,
  };
}

function bounded(value) {
  return String(value ?? '')
    .replace(/\s+/gu, ' ')
    .trim()
    .slice(0, 500) || 'no response body';
}

async function fetchJson(url, init = {}) {
  const response = await fetch(url, {
    ...init,
    signal: AbortSignal.timeout(requestTimeoutMs),
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`HTTP ${response.status} from ${url}: ${bounded(text)}`);
  }
  try {
    return JSON.parse(text);
  } catch (error) {
    throw new Error(`invalid JSON from ${url}: ${error.message}`);
  }
}

async function verifyHealth(backendOrigin) {
  const payload = await fetchJson(`${backendOrigin}${healthPath}`, {
    headers: { Accept: 'application/json' },
  });
  if (payload?.status !== 'UP') {
    throw new Error('local backend health status is not UP');
  }
  return payload.status;
}

async function pendingMatches(contract, backendOrigin, handoff) {
  const query = new URLSearchParams({
    keyword: contract.manifest.businessKey,
    limit: '20',
    offset: '0',
  });
  const requestId = `demo-runtime-observe-${handoff.sequence}-pending-v1`;
  const payload = await fetchJson(
    `${backendOrigin}${apiBasePath}/tasks/pending?${query}`,
    { headers: requestHeaders(contract, handoff.actorId, requestId) },
  );
  if (!Array.isArray(payload?.items)) {
    throw new Error('pending-task response does not contain items');
  }
  return payload.items.filter(item =>
    item?.businessKey === contract.manifest.businessKey
      && item?.taskDefinitionKey === handoff.taskDefinitionKey);
}

async function readTimeline(contract, backendOrigin, instanceId, suffix) {
  const requestId = `demo-runtime-observe-timeline-${suffix}-v1`;
  const payload = await fetchJson(
    `${backendOrigin}${apiBasePath}/instances/${encodeURIComponent(instanceId)}/timeline`,
    { headers: requestHeaders(contract, 'demo-admin', requestId) },
  );
  if (payload?.instanceId !== instanceId || !Array.isArray(payload.items)) {
    throw new Error('timeline response does not match the observed instance');
  }
  return payload;
}

async function readInstance(contract, backendOrigin, instanceId, suffix) {
  const requestId = `demo-runtime-observe-instance-${suffix}-v1`;
  const payload = await fetchJson(
    `${backendOrigin}${apiBasePath}/instances/${encodeURIComponent(instanceId)}`,
    { headers: requestHeaders(contract, 'demo-admin', requestId) },
  );
  if (payload?.instance?.instanceId !== instanceId) {
    throw new Error('instance response does not match the observed instance');
  }
  if (payload.instance.businessKey !== contract.manifest.businessKey) {
    throw new Error('instance response does not match the governed businessKey');
  }
  return payload;
}

function delay(milliseconds) {
  return new Promise(resolvePromise => setTimeout(resolvePromise, milliseconds));
}

async function waitUntil(description, options, action) {
  const deadline = Date.now() + options.timeoutSeconds * 1_000;
  let lastDetail = 'not observed';
  while (Date.now() < deadline) {
    const result = await action();
    if (result.done) return result.value;
    lastDetail = result.detail || lastDetail;
    await delay(options.pollIntervalMs);
  }
  throw new Error(`${description} timed out: ${bounded(lastDetail)}`);
}

async function waitForTask(contract, backendOrigin, handoff, options) {
  return waitUntil(
    `${handoff.client}/${handoff.actorId}/${handoff.taskDefinitionKey}`,
    options,
    async () => {
      const matches = await pendingMatches(contract, backendOrigin, handoff);
      if (matches.length === 1) return { done: true, value: matches[0] };
      if (matches.length > 1) {
        throw new Error(`multiple governed pending tasks found for ${handoff.actorId}`);
      }
      return { done: false, detail: 'expected pending task is not visible yet' };
    },
  );
}

async function waitForTaskGone(contract, backendOrigin, handoff, taskId, options) {
  await waitUntil(
    `completion of ${handoff.client}/${handoff.actorId}/${handoff.taskDefinitionKey}`,
    options,
    async () => {
      const matches = await pendingMatches(contract, backendOrigin, handoff);
      const retained = matches.some(item => item?.taskId === taskId);
      return retained
        ? { done: false, detail: `task ${taskId} remains pending` }
        : { done: true, value: true };
    },
  );
}

function newAuditEvents(before, after, actorId) {
  const existing = new Set(before.items.map(item => item.eventId));
  return after.items.filter(item =>
    !existing.has(item.eventId)
      && item.operatorId === actorId
      && item.action === 'TASK_APPROVED');
}

function writeEvidence(path, evidence) {
  mkdirSync(dirname(path), { recursive: true });
  const temporary = `${path}.tmp`;
  writeFileSync(temporary, `${JSON.stringify(evidence, null, 2)}\n`, {
    encoding: 'utf8',
    mode: 0o600,
  });
  renameSync(temporary, path);
}

function printHandoffInstruction(handoff) {
  console.log('\n============================================================');
  console.log(`Stage ${handoff.sequence}: ${handoff.taskDefinitionKey}`);
  console.log(`Client: ${handoff.client}`);
  console.log(`Actor: ${handoff.actorId}`);
  console.log(`Start: ${handoff.clientCommand}`);
  console.log(`Open: ${handoff.clientLocation}`);
  console.log('Complete the approval in that client. The observer will continue after');
  console.log('the current task disappears and a matching audit event is recorded.');
  console.log('============================================================\n');
}

async function observe(contract, options) {
  const backendOrigin = normalizeBackendOrigin(
    options.backendOrigin || contract.manifest.defaultBackendOrigin,
  );
  const outputPath = resolveOutputPath(options.output);
  const startedAt = new Date().toISOString();
  const evidence = {
    schemaVersion: 1,
    evidenceKind: 'CROSS_CLIENT_INTERACTIVE_RUNTIME_OBSERVER_V1',
    status: 'IN_PROGRESS',
    startedAt,
    completedAt: null,
    backendOrigin,
    contractHashes: contract.contractHashes,
    tenantId: contract.manifest.tenantId,
    businessKey: contract.manifest.businessKey,
    instanceId: null,
    healthStatus: null,
    handoffs: [],
    final: null,
    claims: [],
    nonClaims: launchPlan(contract).nonClaims,
  };
  writeEvidence(outputPath, evidence);

  try {
    evidence.healthStatus = await verifyHealth(backendOrigin);
    writeEvidence(outputPath, evidence);

    let instanceId;
    for (const handoff of contract.handoffs) {
      const task = await waitForTask(contract, backendOrigin, handoff, options);
      if (!task?.taskId || !task?.instanceId) {
        throw new Error('pending task is missing taskId or instanceId');
      }
      if (instanceId && task.instanceId !== instanceId) {
        throw new Error('cross-client handoff changed the governed instanceId');
      }
      instanceId ||= task.instanceId;
      evidence.instanceId = instanceId;

      const beforeTimeline = await readTimeline(
        contract,
        backendOrigin,
        instanceId,
        `before-${handoff.sequence}`,
      );
      const stageEvidence = {
        ...handoff,
        taskId: task.taskId,
        instanceId,
        observedAt: new Date().toISOString(),
        completedObservedAt: null,
        auditEventIds: [],
      };
      evidence.handoffs.push(stageEvidence);
      writeEvidence(outputPath, evidence);
      printHandoffInstruction(handoff);

      await waitForTaskGone(
        contract,
        backendOrigin,
        handoff,
        task.taskId,
        options,
      );
      const afterTimeline = await readTimeline(
        contract,
        backendOrigin,
        instanceId,
        `after-${handoff.sequence}`,
      );
      const auditEvents = newAuditEvents(
        beforeTimeline,
        afterTimeline,
        handoff.actorId,
      );
      if (auditEvents.length !== 1) {
        throw new Error(
          `expected one new TASK_APPROVED audit event for ${handoff.actorId}; `
          + `observed ${auditEvents.length}`,
        );
      }
      stageEvidence.completedObservedAt = new Date().toISOString();
      stageEvidence.auditEventIds = auditEvents.map(item => item.eventId);
      writeEvidence(outputPath, evidence);
    }

    const finalInstance = await readInstance(
      contract,
      backendOrigin,
      evidence.instanceId,
      'final',
    );
    if (finalInstance.instance.status !== 'COMPLETED') {
      throw new Error(`final instance status is ${finalInstance.instance.status}`);
    }
    const finalTaskSummary = summarizeTaskHistory(finalInstance.tasks);
    if (finalTaskSummary.activeTaskCount !== 0) {
      throw new Error(
        `completed instance still exposes ${finalTaskSummary.activeTaskCount} active tasks`,
      );
    }
    const finalTimeline = await readTimeline(
      contract,
      backendOrigin,
      evidence.instanceId,
      'final',
    );
    const auditEventIds = evidence.handoffs.flatMap(stage => stage.auditEventIds);
    if (new Set(auditEventIds).size !== contract.handoffs.length) {
      throw new Error('cross-client approval audit event IDs are not unique');
    }

    evidence.status = 'PASSED';
    evidence.completedAt = new Date().toISOString();
    evidence.final = {
      status: finalInstance.instance.status,
      activeTaskCount: finalTaskSummary.activeTaskCount,
      activeTaskIds: finalTaskSummary.activeTaskIds,
      taskHistoryCount: finalTaskSummary.historyTaskCount,
      taskStatusCounts: finalTaskSummary.statusCounts,
      auditEventIds,
      timelineEventCount: finalTimeline.items.length,
      observedAt: evidence.completedAt,
    };
    evidence.claims = [
      'CROSS_CLIENT_SHARED_INSTANCE_OBSERVED',
      'CROSS_CLIENT_ROLE_HANDOFFS_OBSERVED',
      'PURCHASE_APPROVAL_RUNTIME_COMPLETED',
    ];
    writeEvidence(outputPath, evidence);

    console.log('\nCross-client interactive runtime observation passed.');
    console.log(`Evidence: ${relative(repositoryRoot, outputPath)}`);
    for (const claim of evidence.claims) console.log(claim);
    for (const nonClaim of evidence.nonClaims) console.log(nonClaim);
  } catch (error) {
    evidence.status = 'FAILED';
    evidence.completedAt = new Date().toISOString();
    evidence.failure = bounded(error.message);
    writeEvidence(outputPath, evidence);
    throw error;
  }
}

async function main() {
  const options = parseArguments(process.argv.slice(2));
  if (options.help) {
    console.log(usage());
    return;
  }
  const contract = loadContract();
  if (options.command === 'plan') {
    printPlan(contract, options.json);
    return;
  }
  await observe(contract, options);
}

main().catch(error => {
  console.error(`CROSS_CLIENT_RUNTIME_EVIDENCE_FAILED: ${error.message}`);
  if (error instanceof UsageError) console.error(usage());
  process.exitCode = error instanceof UsageError ? 2 : 1;
});
