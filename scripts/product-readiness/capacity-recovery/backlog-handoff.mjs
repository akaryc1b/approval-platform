const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u;
const identityFields = ['id', 'eventId', 'eventType', 'aggregateId', 'idempotencyKey', 'requestId', 'traceId'];

export function validateBacklogHandoff(handoff, rows, expectedRows) {
  if (!handoff || typeof handoff.sourceRunId !== 'string' || !handoff.sourceRunId.endsWith('-profiles')
      || !Array.isArray(handoff.instances) || expectedRows !== 96
      || handoff.instances.length !== expectedRows || !Array.isArray(rows)
      || rows.length !== expectedRows) {
    throw new Error('drain requires the original 96-instance profile-matrix handoff');
  }
  const ids = new Set();
  for (const instance of handoff.instances) {
    if (!instance || !uuid.test(instance.instanceId || '') || ids.has(instance.instanceId)
        || instance.finalStatus !== 'COMPLETED') {
      throw new Error('profile handoff contains invalid, repeated or incomplete instances');
    }
    ids.add(instance.instanceId);
  }
  for (const key of ['id', 'eventId', 'aggregateId', 'idempotencyKey']) {
    if (new Set(rows.map(row => row?.[key])).size !== expectedRows) {
      throw new Error(`profile handoff contains repeated ${key}`);
    }
  }
  for (const row of rows) {
    if (!row || !ids.has(row.aggregateId) || !uuid.test(row.id || '')
        || !uuid.test(row.eventId || '') || row.status !== 'PENDING' || row.attempts !== 0
        || row.deliveredAt !== null || row.providerRequestId !== null
        || row.responseCode !== null || row.lastError !== null
        || row.eventType !== 'purchase-payment.completed.v1'
        || row.idempotencyKey !== `${row.eventType}:${row.aggregateId}`) {
      throw new Error('original matrix Outbox must be untouched and exactly PENDING');
    }
  }
  return structuredClone(rows);
}

export function requireSameBacklogIdentity(original, observed) {
  if (!Array.isArray(observed) || observed.length !== original.length) {
    throw new Error('original backlog row count changed');
  }
  const byId = new Map(original.map(row => [row.id, row]));
  const seen = new Set();
  for (const row of observed) {
    const baseline = row && byId.get(row.id);
    if (!baseline || seen.has(row.id)
        || identityFields.some(key => row[key] !== baseline[key])) {
      throw new Error('original profile-matrix backlog identity changed');
    }
    seen.add(row.id);
  }
}
