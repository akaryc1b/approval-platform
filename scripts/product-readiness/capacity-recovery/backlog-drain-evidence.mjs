import { createHash } from 'node:crypto';
import {
  appendFileSync,
  existsSync,
  lstatSync,
  readFileSync,
  readdirSync,
} from 'node:fs';
import { relative, resolve, sep } from 'node:path';

import {
  eventType,
  executable,
  requireSuccess,
  runCaptured,
} from '../purchase-payment-e2e/contract.mjs';
import { repositoryRoot } from './contract.mjs';
import {
  dispatchBatchSize,
  requiredText,
  uuid,
} from './backlog-drain-contract.mjs';
import { composeArguments } from './backlog-drain-lifecycle.mjs';

const envelopeBegin = 'CAPACITY_BACKLOG_DRAIN_CI_ARTIFACT_ENVELOPE_BEGIN';
const envelopeEnd = 'CAPACITY_BACKLOG_DRAIN_CI_ARTIFACT_ENVELOPE_END';
const retainedExtensions = new Set(['.json', '.md']);
const maximumFileBytes = 24 * 1024 * 1024;
const maximumTotalBytes = 64 * 1024 * 1024;

function safeSql(value, label) {
  const text = requiredText(value, label);
  if (!/^[0-9A-Za-z._:-]+$/u.test(text)) {
    throw new Error(`${label} contains unsupported SQL evidence characters`);
  }
  return `'${text}'`;
}

export function queryOutboxRows(instanceIds) {
  if (instanceIds.length !== dispatchBatchSize
      || instanceIds.some(value => !uuid.test(value))) {
    throw new Error(
      `backlog query requires ${dispatchBatchSize} UUID instance IDs`,
    );
  }
  const sql = [
    'select',
    "  id::text, event_id::text, event_type, aggregate_id, status, attempts::text,",
    "  coalesce(last_error, ''), coalesce(response_code::text, ''),",
    "  coalesce(provider_request_id, ''), request_id, coalesce(trace_id, ''),",
    "  idempotency_key, available_at::text, coalesce(delivered_at::text, '')",
    'from ap_outbox',
    `where event_type = ${safeSql(eventType(), 'eventType')}`,
    `  and aggregate_id in (${instanceIds.map(value =>
      safeSql(value, 'instanceId')).join(', ')})`,
    'order by aggregate_id, id;',
  ].join('\n');
  const output = requireSuccess(
    'Read capacity backlog Outbox rows',
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
  if (!output) return [];
  return output.split(/\r?\n/u).filter(Boolean).map((line) => {
    const values = line.split('|');
    if (values.length !== 14) {
      throw new Error(`unexpected backlog Outbox row: ${line}`);
    }
    return {
      id: values[0],
      eventId: values[1],
      eventType: values[2],
      aggregateId: values[3],
      status: values[4],
      attempts: Number.parseInt(values[5], 10),
      lastError: values[6] || null,
      responseCode: values[7] ? Number.parseInt(values[7], 10) : null,
      providerRequestId: values[8] || null,
      requestId: values[9],
      traceId: values[10] || null,
      idempotencyKey: values[11],
      availableAt: values[12],
      deliveredAt: values[13] || null,
    };
  });
}

function requireUnique(rows, key, label) {
  const values = rows.map(row => row[key]);
  if (values.some(value => !value)
      || new Set(values).size !== values.length) {
    throw new Error(`${label} values are not unique and complete`);
  }
}

function validateCommonRows(rows, expectedRows) {
  if (rows.length !== expectedRows) {
    throw new Error(`expected ${expectedRows} Outbox rows, found ${rows.length}`);
  }
  requireUnique(rows, 'id', 'Outbox id');
  requireUnique(rows, 'eventId', 'Outbox eventId');
  requireUnique(rows, 'aggregateId', 'Outbox aggregateId');
  requireUnique(rows, 'idempotencyKey', 'Outbox idempotencyKey');
  for (const row of rows) {
    if (!uuid.test(row.id)
        || !uuid.test(row.eventId)
        || !uuid.test(row.aggregateId)
        || row.eventType !== eventType()
        || row.idempotencyKey !== `${eventType()}:${row.aggregateId}`) {
      throw new Error(
        `invalid capacity backlog Outbox identity: ${JSON.stringify(row)}`,
      );
    }
  }
}

export function validateUnavailable(value, expectedRows) {
  validateCommonRows(value.rows, expectedRows);
  for (const row of value.rows) {
    if (row.status !== 'PENDING'
        || row.attempts < 1
        || row.responseCode !== null
        || row.providerRequestId !== null
        || row.deliveredAt !== null
        || !row.lastError?.startsWith(
          'HTTP 503: payment sandbox unavailable',
        )) {
      return false;
    }
  }
  return value.sandbox?.available === false
    && value.sandbox.deliveryAttempts >= expectedRows
    && value.sandbox.acceptedPaymentResults === 0
    && value.sandbox.lastHttpStatus === 503
    && value.sandbox.failure === null;
}

export function validateDelivered(value, expectedRows) {
  validateCommonRows(value.rows, expectedRows);
  for (const row of value.rows) {
    if (row.status !== 'DELIVERED'
        || row.attempts < 1
        || row.responseCode !== 200
        || row.providerRequestId !== `local-payment-sandbox-${row.eventId}`
        || row.deliveredAt === null
        || row.lastError !== null) {
      return false;
    }
  }
  requireUnique(value.rows, 'providerRequestId', 'providerRequestId');
  return value.sandbox?.available === true
    && value.sandbox.acceptedPaymentResults === expectedRows
    && value.sandbox.lastHttpStatus === 200
    && value.sandbox.failure === null;
}

export function seededAttachmentIds(contract) {
  const fixturePath = resolve(
    repositoryRoot,
    'config/demo/purchase-payment-demo-seed.json',
  );
  const fixture = JSON.parse(readFileSync(fixturePath, 'utf8'));
  const logicalIds = contract.scenario.request.attachmentIds;
  if (!Array.isArray(logicalIds)
      || !Array.isArray(fixture.attachments)
      || logicalIds.length !== fixture.attachments.length) {
    throw new Error('backlog drain seed attachment contract is inconsistent');
  }
  return logicalIds.map((logicalId, index) => {
    const attachment = fixture.attachments[index];
    if (attachment?.logicalId !== logicalId
        || !uuid.test(attachment?.attachmentId || '')) {
      throw new Error(`backlog drain seed attachment ${logicalId} is invalid`);
    }
    return attachment.attachmentId;
  });
}

function collectEvidence(directory, files = []) {
  for (const name of readdirSync(directory).sort()) {
    const target = resolve(directory, name);
    const metadata = lstatSync(target);
    if (metadata.isSymbolicLink()) {
      throw new Error(`backlog-drain evidence rejects symbolic link: ${target}`);
    }
    if (metadata.isDirectory()) {
      collectEvidence(target, files);
      continue;
    }
    if (!metadata.isFile()) continue;
    const extension = name.slice(name.lastIndexOf('.')).toLowerCase();
    if (retainedExtensions.has(extension)) files.push(target);
  }
  return files;
}

export function appendEvidenceEnvelope(status, runDirectory, identity) {
  if (process.env.GITHUB_ACTIONS !== 'true') return;
  const artifactLog = resolve(repositoryRoot, 'root-install.log');
  if (!existsSync(artifactLog)) {
    throw new Error('root-install.log is unavailable for backlog-drain evidence');
  }
  let totalBytes = 0;
  const files = collectEvidence(runDirectory).map((target) => {
    const content = readFileSync(target);
    if (content.length > maximumFileBytes) {
      throw new Error(`backlog-drain evidence file is too large: ${target}`);
    }
    totalBytes += content.length;
    if (totalBytes > maximumTotalBytes) {
      throw new Error('backlog-drain evidence exceeds bounded total size');
    }
    const path = relative(runDirectory, target).split(sep).join('/');
    if (!path || path.startsWith('../') || path.includes('/../')) {
      throw new Error(`backlog-drain evidence escaped its run directory: ${target}`);
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
      'source-identity.json',
      'backlog-drain-contract.json',
      'backlog-drain-instances.json',
      'backlog-drain-command-attempts.json',
      'outbox-backlog-unavailable.json',
      'outbox-backlog-delivered.json',
      'backlog-drain-cleanup.json',
      'outbox-backlog-drain-summary.json',
    ]) {
      if (!files.some(file => file.path === required)) {
        throw new Error(`passed backlog drain did not retain ${required}`);
      }
    }
  }
  appendFileSync(
    artifactLog,
    `\n${envelopeBegin}\n${JSON.stringify({
      schemaVersion: 1,
      evidenceKind: 'CAPACITY_BACKLOG_DRAIN_CI_ARTIFACT_ENVELOPE_V1',
      status,
      commitSha: identity.commitSha,
      treeSha: identity.treeSha,
      githubRunId: process.env.GITHUB_RUN_ID || null,
      capturedAt: new Date().toISOString(),
      totalBytes,
      files,
    })}\n${envelopeEnd}\n`,
    'utf8',
  );
}
