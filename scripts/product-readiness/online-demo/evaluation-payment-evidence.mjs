import { createHash } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import { createExactEventAllowlist, verifyExactAcceptedPayments } from '../capacity-recovery/sandbox-event-allowlist.mjs';
import { evaluationSlotIds, runEvaluationDocker } from './evaluation-docker-slots.mjs';

const requireValue = (value, code) => { if (!value) throw new Error(code); };
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u;
const resourceId = value => typeof value === 'string' && /^[0-9a-f]{64}$/u.test(value);
export const evaluationBusinessSnapshotSql = `
BEGIN READ ONLY;
SET LOCAL statement_timeout = '2000ms';
SELECT json_build_object(
 'instances', (SELECT coalesce(json_agg(r ORDER BY r."instanceId"), '[]'::json) FROM (
   SELECT instance_id::text AS "instanceId", business_key AS "businessKey", status,
     purchase_order_reference AS "purchaseOrderReference", attachment_ids_json AS "attachmentIds"
   FROM ap_approval_instance WHERE tenant_id = 'demo-purchase-payment' LIMIT 6) r),
 'attachments', (SELECT coalesce(json_agg(r ORDER BY r."attachmentId"), '[]'::json) FROM (
   SELECT attachment_id::text AS "attachmentId", instance_id::text AS "instanceId", file_name AS "fileName",
     content_type AS "contentType", size_bytes AS "sizeBytes", sha256,
     encode(sha256(content), 'hex') AS "storedContentSha256"
   FROM ap_approval_attachment WHERE tenant_id = 'demo-purchase-payment' LIMIT 24) r),
 'outbox', (SELECT coalesce(json_agg(r ORDER BY r."eventId"), '[]'::json) FROM (
   SELECT id::text AS id, event_id::text AS "eventId", aggregate_id AS "aggregateId", event_type AS "eventType",
     idempotency_key AS "idempotencyKey", status, attempts, last_error AS "lastError", response_code AS "responseCode",
     provider_request_id AS "providerRequestId", delivered_at::text AS "deliveredAt"
   FROM ap_outbox WHERE tenant_id = 'demo-purchase-payment' LIMIT 6) r),
 'counts', json_build_object(
   'instances', (SELECT count(*) FROM ap_approval_instance WHERE tenant_id = 'demo-purchase-payment'),
   'attachments', (SELECT count(*) FROM ap_approval_attachment WHERE tenant_id = 'demo-purchase-payment'),
   'outbox', (SELECT count(*) FROM ap_outbox WHERE tenant_id = 'demo-purchase-payment'))
);
ROLLBACK;`;

/** Read-only platform evidence and exact existing recovery-file publication.
 * This is an operator-owned adapter, not a browser route or SQL execution API.
 * Every operation pins a READY generation and the exact labelled container IDs.
 */
export function createEvaluationPaymentEvidence({ adapter, run = runEvaluationDocker } = {}) {
  requireValue(adapter && typeof adapter.snapshot === 'function' && typeof run === 'function', 'EVIDENCE_ADAPTER_REQUIRED');
  const recoveries = new Map();
  function select(slotId) {
    requireValue(evaluationSlotIds.includes(slotId), 'UNKNOWN_EVIDENCE_SLOT');
    const root = adapter.snapshot(); const slot = root.slots.find(value => value.slotId === slotId);
    requireValue(!root.closed && slot?.state === 'READY' && !slot.resetInFlight && !slot.uncertainMutation,
      'EVIDENCE_SLOT_NOT_READY');
    requireValue(['backend', 'postgres', 'probe'].every(role => resourceId(slot.containers[role]?.id)), 'EVIDENCE_IDS_REQUIRED');
    return { root, slot };
  }
  async function scoped(slotId, signal, action) {
    requireValue(signal instanceof AbortSignal && !signal.aborted, 'EVIDENCE_SIGNAL_REQUIRED');
    const { root, slot } = select(slotId);
    const check = () => {
      const current = select(slotId);
      requireValue(!signal.aborted && current.root.namespace === root.namespace && current.root.engineId === root.engineId
        && current.slot.generation === slot.generation
        && ['backend', 'postgres', 'probe'].every(role => current.slot.containers[role].id === slot.containers[role].id),
      'EVIDENCE_GENERATION_CHANGED');
    };
    async function command(args, input) {
      check(); const result = await run(args, { signal, timeoutMs: 5000, input }); check(); return result;
    }
    const engine = JSON.parse(await command(['info', '--format', '{{json .}}']));
    requireValue(engine.ID === root.engineId && engine.OSType === 'linux', 'EVIDENCE_ENGINE_CHANGED');
    for (const role of ['backend', 'postgres']) {
      const values = JSON.parse(await command(['container', 'inspect', slot.containers[role].id]));
      const value = values[0];
      requireValue(values.length === 1 && value.Id === slot.containers[role].id && value.State?.Running === true
        && value.Name === `/${slot.names.containers[role]}` && value.Config?.Labels?.['io.approval.evaluation.owner'] === root.namespace
        && value.Config.Labels['io.approval.evaluation.slot'] === slotId
        && value.Config.Labels['io.approval.evaluation.generation'] === slot.generation
        && value.Config.Labels['io.approval.evaluation.role'] === role, 'EVIDENCE_RESOURCE_OWNERSHIP');
    }
    return action({ root, slot, command, check });
  }
  async function readScope({ slot, command }) {
    const raw = await command(['exec', slot.containers.postgres.id, 'psql', '-U', 'approval', '-d', 'approval',
      '-qAt', '-v', 'ON_ERROR_STOP=1', '-c', evaluationBusinessSnapshotSql]);
    requireValue(Buffer.byteLength(raw) <= 131072, 'BUSINESS_SNAPSHOT_LIMIT');
    const value = JSON.parse(raw);
    requireValue(value && ['instances', 'attachments', 'outbox'].every(key => Array.isArray(value[key])
      && value.counts?.[key] === value[key].length), 'BUSINESS_SNAPSHOT_INCOMPLETE');
    requireValue(value.instances.length <= 5 && value.attachments.length <= 18 && value.outbox.length <= 4, 'BUSINESS_QUOTA_EXCEEDED');
    for (const row of value.attachments) requireValue(uuid.test(row.attachmentId)
      && /^[0-9a-f]{64}$/u.test(row.sha256 || '') && row.sha256 === row.storedContentSha256, 'ATTACHMENT_CONTENT_CHANGED');
    const rawSandbox = await command(['exec', slot.containers.backend.id, 'cat', '/tmp/evaluation-payment-status.json']);
    requireValue(Buffer.byteLength(rawSandbox) <= 65536, 'PAYMENT_STATUS_LIMIT');
    const sandbox = JSON.parse(rawSandbox);
    requireValue(sandbox && typeof sandbox.available === 'boolean' && Array.isArray(sandbox.acceptedPayments)
      && sandbox.acceptedPayments.length <= 4 && sandbox.acceptedPaymentResults === sandbox.acceptedPayments.length
      && sandbox.failure === null, 'PAYMENT_STATUS_INVALID');
    return { schemaVersion: 1, kind: 'EVALUATION_BUSINESS_STATE', slotId: slot.slotId,
      generation: slot.generation, ...value, sandbox };
  }
  const snapshot = (slotId, signal) => scoped(slotId, signal, readScope);
  async function recover(slotId, signal) {
    return scoped(slotId, signal, async scope => {
      const { slot, command, check } = scope;
      requireValue(!recoveries.has(slot.generation), 'PAYMENT_RECOVERY_ALREADY_REQUESTED');
      const before = await readScope(scope);
      requireValue(before.outbox.length >= 1 && before.outbox.length <= 4 && !before.sandbox.available
        && before.sandbox.acceptedPaymentResults === 0 && before.outbox.every(row => row.status === 'PENDING'
          && row.attempts >= 1 && row.lastError?.startsWith('HTTP 503: payment sandbox unavailable')
          && row.responseCode === null && row.providerRequestId === null && row.deliveredAt === null), 'UNAVAILABLE_REAL_OUTBOX_REQUIRED');
      const relevant = before.instances.filter(instance => before.outbox.some(row => row.aggregateId === instance.instanceId));
      const allowlist = createExactEventAllowlist(before.outbox, relevant, 'demo-purchase-payment', 'purchase-payment.completed.v1');
      const started = performance.now();
      recoveries.set(slot.generation, 'REQUESTED'); // An uncertain publication is not automatically repeated.
      // Fixed command and fixed private tmpfs paths. Event bytes travel on stdin, not interpolation.
      await command(['exec', '-i', slot.containers.backend.id, 'sh', '-c',
        'set -eu; umask 077; test ! -e /tmp/evaluation-payment-control; test ! -e /tmp/evaluation-payment-events; test ! -e /tmp/evaluation-payment-events.tmp; cat > /tmp/evaluation-payment-events.tmp; mv /tmp/evaluation-payment-events.tmp /tmp/evaluation-payment-events; printf "recover\\n" > /tmp/evaluation-payment-control'],
      Buffer.from(allowlist.content));
      const ids = new Set(before.outbox.map(row => row.eventId));
      let after;
      for (;;) {
        check(); requireValue(performance.now() - started < 25000, 'PAYMENT_DRAIN_TIMEOUT');
        after = await readScope(scope);
        requireValue(after.outbox.length === ids.size && after.outbox.every(row => ids.has(row.eventId)), 'PAYMENT_EVENT_SET_CHANGED');
        if (after.outbox.every(row => row.status === 'DELIVERED' && row.responseCode === 200
            && row.providerRequestId === `local-payment-sandbox-${row.eventId}` && row.deliveredAt && row.lastError === null)
            && verifyExactAcceptedPayments(after.sandbox, allowlist)) break;
        await new Promise(resolve => setTimeout(resolve, 250));
      }
      for (let index = 0; index < 3; index += 1) {
        await new Promise(resolve => setTimeout(resolve, 300)); check();
        const stable = await readScope(scope);
        requireValue(verifyExactAcceptedPayments(stable.sandbox, allowlist)
          && JSON.stringify(stable.outbox) === JSON.stringify(after.outbox), 'PAYMENT_STABILITY_LOST');
      }
      recoveries.set(slot.generation, 'DELIVERED');
      return { status: 'EXACT_SIGNED_SANDBOX_PAYMENT_DELIVERED', before, after,
        eventIds: [...ids].sort(), allowlistSha256: allowlist.sha256,
        elapsedMs: Math.round(performance.now() - started), stableObservations: 3 };
    });
  }
  return Object.freeze({ snapshot, recover });
}

/** Exclude counters and polling clocks but retain actual business record identities/content. */
export function evaluationStableBusinessDigest(value) {
  const stable = { instances: value.instances, attachments: value.attachments, outbox: value.outbox,
    acceptedPayments: [...value.sandbox.acceptedPayments].sort((a, b) => a.eventId.localeCompare(b.eventId)),
    available: value.sandbox.available, allowlistSha256: value.sandbox.allowlistSha256 };
  return createHash('sha256').update(JSON.stringify(stable)).digest('hex');
}
export function requireEvaluationBusinessReset(before, after, sessionInstanceId, sessionAttachmentId) {
  requireValue(uuid.test(sessionInstanceId || '') && uuid.test(sessionAttachmentId || ''), 'SESSION_BUSINESS_IDS_REQUIRED');
  requireValue(before.generation !== after.generation && before.instances.some(row => row.instanceId === sessionInstanceId)
    && before.attachments.some(row => row.attachmentId === sessionAttachmentId)
    && before.outbox.some(row => row.aggregateId === sessionInstanceId), 'PRIOR_REAL_BUSINESS_EVIDENCE_REQUIRED');
  requireValue(!after.instances.some(row => row.instanceId === sessionInstanceId)
    && !after.attachments.some(row => row.attachmentId === sessionAttachmentId)
    && after.outbox.length === 0 && after.sandbox.acceptedPaymentResults === 0
    && after.sandbox.acceptedPayments.length === 0 && after.sandbox.available === false,
  'PRIOR_BUSINESS_DATA_REMAINED');
  // Fixed seed attachment IDs may be recreated. Check old session-created IDs, not those fixture IDs.
  requireValue(after.instances.length === 1 && after.instances[0].businessKey === 'DEMO-PP-0001', 'CANONICAL_SEED_NOT_RESTORED');
  return { oldInstanceAbsent: true, oldAttachmentAbsent: true, outboxEmpty: true, paymentLedgerEmpty: true, seedRestored: true };
}
