import { createHash } from 'node:crypto';
import { renameSync, rmSync, writeFileSync } from 'node:fs';

export const allowlistHeader = 'PURCHASE_PAYMENT_EXACT_EVENTS_V1';
const maximumEvents = 96;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u;
const literal = /^[0-9A-Za-z._:-]+$/u;
const fields = ['eventId', 'idempotencyKey', 'aggregateId', 'businessKey', 'purchaseOrderReference'];

function requireValue(condition, message) {
  if (!condition) throw new Error(`exact event allowlist: ${message}`);
}

export function createExactEventAllowlist(rows, instances, tenantId, eventType) {
  requireValue(Array.isArray(rows) && rows.length >= 1 && rows.length <= maximumEvents,
    'requires between 1 and 96 events');
  requireValue(Array.isArray(instances) && instances.length === rows.length,
    'instance and event counts differ');
  requireValue(typeof tenantId === 'string' && literal.test(tenantId) && tenantId.length <= 160,
    'invalid tenant');
  requireValue(eventType === 'purchase-payment.completed.v1', 'invalid completion type');
  const byInstance = new Map();
  for (const instance of instances) {
    requireValue(instance && uuid.test(instance.instanceId || '')
      && !byInstance.has(instance.instanceId), 'invalid or repeated instance identity');
    byInstance.set(instance.instanceId, instance);
  }
  const entries = [...rows].map(row => {
    requireValue(row && uuid.test(row.eventId || '') && uuid.test(row.aggregateId || '')
      && row.eventType === eventType
      && row.idempotencyKey === `${eventType}:${row.aggregateId}`
      && row.status === 'PENDING' && row.deliveredAt === null,
    'requires exact undelivered completion events');
    const instance = byInstance.get(row.aggregateId);
    requireValue(Boolean(instance), 'event does not belong to a supplied instance');
    const entry = {
      eventId: row.eventId,
      idempotencyKey: row.idempotencyKey,
      aggregateId: row.aggregateId,
      businessKey: instance.businessKey,
      purchaseOrderReference: instance.purchaseOrderReference,
    };
    for (const field of fields) {
      requireValue(typeof entry[field] === 'string' && entry[field].length <= 256
        && literal.test(entry[field]), `${field} must be an exact bounded literal`);
    }
    return entry;
  }).sort((left, right) => left.eventId.localeCompare(right.eventId));
  for (const field of fields) {
    requireValue(new Set(entries.map(entry => entry[field])).size === entries.length,
      `duplicate ${field}`);
  }
  const content = `${allowlistHeader}\t${tenantId}\n`
    + entries.map(entry => fields.map(field => entry[field]).join('\t')).join('\n') + '\n';
  return {
    schemaVersion: 1,
    evidenceKind: 'PURCHASE_PAYMENT_EXACT_EVENT_ALLOWLIST_V1',
    tenantId,
    eventType,
    entries,
    content,
    sha256: createHash('sha256').update(content, 'utf8').digest('hex'),
  };
}

/** Publish in the private run directory before writing the recovery control. */
export function publishExactEventAllowlist(path, rows, instances, tenantId, eventType) {
  const value = createExactEventAllowlist(rows, instances, tenantId, eventType);
  const temporary = `${path}.tmp`;
  let created = false;
  try {
    writeFileSync(temporary, value.content, { encoding: 'utf8', mode: 0o600, flag: 'wx' });
    created = true;
    renameSync(temporary, path);
  } finally {
    if (created) rmSync(temporary, { force: true });
  }
  return value;
}

export function verifyExactAcceptedPayments(sandbox, allowlist) {
  const accepted = sandbox?.acceptedPayments;
  if (!Array.isArray(accepted) || accepted.length !== allowlist.entries.length
      || sandbox.acceptedPaymentResults !== allowlist.entries.length
      || sandbox.allowlistSha256 !== allowlist.sha256) return false;
  const expected = new Map(allowlist.entries.map(entry => [entry.eventId, entry]));
  const seen = new Set();
  for (const entry of accepted) {
    if (!entry || seen.has(entry.eventId)) return false;
    const original = expected.get(entry.eventId);
    if (!original || fields.some(field => entry[field] !== original[field])) return false;
    seen.add(entry.eventId);
  }
  return true;
}
