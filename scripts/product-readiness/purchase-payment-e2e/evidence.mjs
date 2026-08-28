import { createHash } from 'node:crypto';
import {
  appendFileSync,
  existsSync,
  lstatSync,
  mkdirSync,
  readFileSync,
  readdirSync,
} from 'node:fs';
import { createServer } from 'node:net';
import { relative, resolve, sep } from 'node:path';

import { delay } from '../pc-h5-runtime/processes.mjs';
import {
  composeFile,
  composeProject,
  executable,
  ledgerPath,
  outputRoot,
  pollIntervalMs,
  readJson,
  requireSuccess,
  requireText,
  runCaptured,
  stateTimeoutMs,
  uuid,
  writeJson,
} from './contract.mjs';

const ciEnvelopeBegin = 'APPROVAL_PURCHASE_PAYMENT_E2E_ENVELOPE_BEGIN';
const ciEnvelopeEnd = 'APPROVAL_PURCHASE_PAYMENT_E2E_ENVELOPE_END';
const retainedEvidenceExtensions = new Set(['.json', '.md', '.png', '.zip']);
const maximumEvidenceFileBytes = 64 * 1024 * 1024;
const maximumEvidenceTotalBytes = 96 * 1024 * 1024;

function collectCiEvidence(directory, files = []) {
  for (const name of readdirSync(directory).sort()) {
    const target = resolve(directory, name);
    const metadata = lstatSync(target);
    if (metadata.isSymbolicLink()) {
      throw new Error(`E2E evidence must not contain a symbolic link: ${target}`);
    }
    if (metadata.isDirectory()) {
      if (name !== 'pc-h5-runtime') collectCiEvidence(target, files);
      continue;
    }
    if (!metadata.isFile()) continue;
    const extension = name.slice(name.lastIndexOf('.')).toLowerCase();
    if (retainedEvidenceExtensions.has(extension)) files.push(target);
  }
  return files;
}

export function appendCiEvidenceEnvelope(status, runDirectory, identity) {
  if (process.env.GITHUB_ACTIONS !== 'true') return;
  const artifactLog = resolve(repositoryRoot, 'root-install.log');
  if (!existsSync(artifactLog)) {
    throw new Error('root-install.log is unavailable for E2E evidence retention');
  }
  let totalBytes = 0;
  const files = collectCiEvidence(runDirectory).map((target) => {
    const content = readFileSync(target);
    if (content.length > maximumEvidenceFileBytes) {
      throw new Error(`E2E evidence file is too large: ${target}`);
    }
    totalBytes += content.length;
    if (totalBytes > maximumEvidenceTotalBytes) {
      throw new Error('E2E retained evidence exceeds the bounded total size');
    }
    const path = relative(runDirectory, target).split(sep).join('/');
    if (!path || path.startsWith('../') || path.includes('/../')) {
      throw new Error(`E2E evidence escaped its run directory: ${target}`);
    }
    return {
      path,
      size: content.length,
      sha256: createHash('sha256').update(content).digest('hex'),
      base64: content.toString('base64'),
    };
  });
  if (status === 'PASSED') {
    for (const required of [
      'h5-payment-runtime-evidence.json',
      'h5-payment-before.png',
      'h5-payment-after.png',
      'outbox-pending-evidence.json',
      'outbox-delivered-evidence.json',
      'cleanup-evidence.json',
      'runtime-summary.json',
    ]) {
      if (!files.some(file => file.path === required)) {
        throw new Error(`passed E2E did not retain ${required}`);
      }
    }
    if (!files.some(file => file.path.endsWith('/trace.zip'))) {
      throw new Error('passed E2E did not retain its Playwright trace.zip');
    }
  }
  const envelope = {
    schemaVersion: 1,
    evidenceKind: 'PURCHASE_PAYMENT_H5_SURROGATE_CI_ENVELOPE_V1',
    status,
    commitSha: identity.commitSha,
    treeSha: identity.treeSha,
    githubRunId: process.env.GITHUB_RUN_ID || null,
    capturedAt: new Date().toISOString(),
    totalBytes,
    files,
  };
  appendFileSync(
    artifactLog,
    `\n${ciEnvelopeBegin}\n${JSON.stringify(envelope)}\n${ciEnvelopeEnd}\n`,
    'utf8',
  );
}

export function composeArguments(...args) {
  return [
    'compose',
    '--project-name',
    composeProject,
    '-f',
    composeFile,
    ...args,
  ];
}


export function validatePcH5Evidence(value, contract, identity) {
  if (value?.claim !== 'PC_H5_APPROVAL_HANDOFF_PASSED'
    || value?.commitSha !== identity.commitSha
    || value?.finalState?.status !== 'RUNNING'
    || value?.finalState?.currentTaskDefinitionKey
      !== contract.policy.taskDefinitionKey
    || value?.paymentHandoff?.taskDefinitionKey
      !== contract.policy.taskDefinitionKey
    || value?.paymentHandoff?.actorId !== contract.policy.actorId
    || value?.paymentHandoff?.client !== contract.policy.targetClient) {
    throw new Error('PC/H5 handoff evidence does not match H5 surrogate acceptance');
  }
  const taskIds = [
    ...(value.steps || []).map(step => step.taskId),
    value.paymentHandoff.taskId,
  ];
  if (taskIds.length !== 5
    || taskIds.some(taskId => typeof taskId !== 'string' || !taskId)
    || new Set(taskIds).size !== taskIds.length) {
    throw new Error('PC/H5 evidence must retain five independent task IDs');
  }
  if (!uuid.test(value.instanceId || '')) {
    throw new Error('PC/H5 evidence instanceId is invalid');
  }
  return value;
}


function safeSqlLiteral(value, name) {
  const text = requireText(value, name);
  if (!/^[0-9A-Za-z._:-]+$/u.test(text)) {
    throw new Error(`${name} contains unsupported SQL evidence characters`);
  }
  return `'${text}'`;
}

export function queryOutbox(instanceId, completedEventType) {
  if (!uuid.test(instanceId)) throw new Error('instanceId is not a UUID');
  const sql = [
    'select',
    "  id::text, event_id::text, event_type, aggregate_id, status, attempts::text,",
    "  coalesce(response_code::text, ''), coalesce(provider_request_id, ''),",
    '  request_id, trace_id, idempotency_key',
    'from ap_outbox',
    `where aggregate_id = ${safeSqlLiteral(instanceId, 'instanceId')}`,
    `  and event_type = ${safeSqlLiteral(completedEventType, 'eventType')}`,
    'order by created_at, id;',
  ].join('\n');
  const result = runCaptured(executable('docker'), composeArguments(
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
  ));
  const output = requireSuccess('Read-only completion Outbox query', result);
  if (!output) return [];
  return output.split(/\r?\n/u).filter(Boolean).map((line) => {
    const values = line.split('|');
    if (values.length !== 11) {
      throw new Error(`unexpected Outbox query row: ${line}`);
    }
    return {
      id: values[0],
      eventId: values[1],
      eventType: values[2],
      aggregateId: values[3],
      status: values[4],
      attempts: Number.parseInt(values[5], 10),
      responseCode: values[6] ? Number.parseInt(values[6], 10) : null,
      providerRequestId: values[7] || null,
      requestId: values[8],
      traceId: values[9],
      idempotencyKey: values[10],
    };
  });
}

export function readSandboxStatus(path) {
  if (!existsSync(path)) throw new Error('payment sandbox status file is unavailable');
  return readJson(path);
}

export async function waitForState(label, read, accepted, timeoutMs = stateTimeoutMs) {
  const deadline = Date.now() + timeoutMs;
  let lastValue;
  let lastError;
  while (Date.now() < deadline) {
    try {
      lastValue = read();
      lastError = undefined;
    } catch (error) {
      lastError = error;
      await delay(pollIntervalMs);
      continue;
    }
    if (accepted(lastValue)) return lastValue;
    await delay(pollIntervalMs);
  }
  const detail = lastError instanceof Error
    ? lastError.message
    : JSON.stringify(lastValue);
  throw new Error(`${label} did not reach the required state: ${detail}`);
}


function portAvailable(port) {
  return new Promise((resolvePromise, reject) => {
    const server = createServer();
    server.once('error', reject);
    server.listen(port, '127.0.0.1', () => {
      server.close(error => error ? reject(error) : resolvePromise(true));
    });
  });
}

export async function waitForPortAvailable(port) {
  const deadline = Date.now() + 10_000;
  let lastError;
  while (Date.now() < deadline) {
    try {
      await portAvailable(port);
      return;
    } catch (error) {
      lastError = error;
    }
    await delay(pollIntervalMs);
  }
  const detail = lastError instanceof Error ? lastError.message : String(lastError);
  throw new Error(`port ${port} was not released: ${detail}`);
}


function emptyLedger(identity) {
  return {
    schemaVersion: 1,
    evidenceKind: 'PURCHASE_PAYMENT_CONSECUTIVE_CLEAN_RUNS_V1',
    commitSha: identity.commitSha,
    treeSha: identity.treeSha,
    successfulRunIds: [],
  };
}

function readLedger(identity) {
  if (!existsSync(ledgerPath)) return emptyLedger(identity);
  const value = readJson(ledgerPath);
  if (value.commitSha !== identity.commitSha || value.treeSha !== identity.treeSha) {
    return emptyLedger(identity);
  }
  return value;
}

export function resetLedger(identity, failureRunId) {
  mkdirSync(outputRoot, { recursive: true, mode: 0o700 });
  writeJson(ledgerPath, {
    ...emptyLedger(identity),
    failureRunId,
    resetAt: new Date().toISOString(),
  });
}

export function nextSuccessfulLedger(identity, runId) {
  const ledger = readLedger(identity);
  const runIds = [...ledger.successfulRunIds, runId]
    .filter((value, index, values) => values.indexOf(value) === index)
    .slice(-2);
  return {
    ...emptyLedger(identity),
    successfulRunIds: runIds,
    updatedAt: new Date().toISOString(),
  };
}
