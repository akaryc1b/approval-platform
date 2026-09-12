import assert from 'node:assert/strict';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import test from 'node:test';
import { createEvaluationPaymentEvidence, evaluationStableBusinessDigest, requireEvaluationBusinessReset,
  evaluationBusinessSnapshotSql } from '../product-readiness/online-demo/evaluation-payment-evidence.mjs';
import { executeEvaluationBusinessRehearsal } from '../product-readiness/online-demo/evaluation-business-rehearsal.mjs';
import { createEvaluationSessions } from '../product-readiness/online-demo/evaluation-sessions.mjs';
import { createEvaluationHttpsServer } from '../product-readiness/online-demo/evaluation-http.mjs';

const root = resolve(import.meta.dirname, '../..');
const scenario = JSON.parse(readFileSync(resolve(root, 'config/demo/purchase-payment-golden-path.json')));
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
function state(generation = 'a'.repeat(32)) {
  const instanceId = randomUUID(); const attachmentId = randomUUID(); const eventId = randomUUID();
  return { generation, slotId: 'slot-a',
    instances: [{ instanceId, businessKey: 'EVAL-A-1', status: 'COMPLETED', purchaseOrderReference: 'PO-EVAL-A-1', attachmentIds: [attachmentId] }],
    attachments: [{ attachmentId, instanceId, fileName: 'evaluation.txt', contentType: 'text/plain', sizeBytes: 7,
      sha256: hash('fixture'), storedContentSha256: hash('fixture') }],
    outbox: [{ id: randomUUID(), eventId, eventType: 'purchase-payment.completed.v1', aggregateId: instanceId,
      idempotencyKey: `purchase-payment.completed.v1:${instanceId}`, status: 'PENDING', attempts: 1,
      lastError: 'HTTP 503: payment sandbox unavailable', responseCode: null, providerRequestId: null, deliveredAt: null }],
    counts: { instances: 1, attachments: 1, outbox: 1 },
    sandbox: { available: false, acceptedPayments: [], acceptedPaymentResults: 0, allowlistSha256: null, failure: null } };
}
function fresh() {
  const value = state('b'.repeat(32)); value.instances[0].businessKey = 'DEMO-PP-0001';
  value.outbox = []; value.counts.outbox = 0; return value;
}
test('reset requires old session-created instance, actual attachment and outbox and restores the seed', () => {
  const before = state(); const after = fresh();
  assert.deepEqual(requireEvaluationBusinessReset(before, after, before.instances[0].instanceId, before.attachments[0].attachmentId), {
    oldInstanceAbsent: true, oldAttachmentAbsent: true, outboxEmpty: true, paymentLedgerEmpty: true, seedRestored: true,
  });
});
for (const fault of ['same-generation', 'old-instance', 'old-attachment', 'outbox', 'payment', 'no-seed', 'no-prior-outbox']) {
  test(`reset cannot pass with ${fault}`, () => {
    const before = state(); const after = fresh();
    if (fault === 'same-generation') after.generation = before.generation;
    if (fault === 'old-instance') after.instances.push(before.instances[0]);
    if (fault === 'old-attachment') after.attachments.push(before.attachments[0]);
    if (fault === 'outbox') after.outbox = before.outbox;
    if (fault === 'payment') after.sandbox.acceptedPaymentResults = 1;
    if (fault === 'no-seed') after.instances = [];
    if (fault === 'no-prior-outbox') before.outbox = [];
    assert.throws(() => requireEvaluationBusinessReset(before, after, before.instances[0].instanceId, before.attachments[0].attachmentId));
  });
}
test('stable comparison includes real records/content and ignores polling counters only', () => {
  const a = state(); const b = structuredClone(a); b.sandbox.deliveryAttempts = 100;
  assert.equal(evaluationStableBusinessDigest(a), evaluationStableBusinessDigest(b));
  b.attachments[0].storedContentSha256 = 'f'.repeat(64);
  assert.notEqual(evaluationStableBusinessDigest(a), evaluationStableBusinessDigest(b));
});
function evidenceFixture() {
  const data = state(); const namespace = 'c'.repeat(32);
  const resources = { closed: false, engineId: 'engine-fixture', namespace, slots: [{ state: 'READY', slotId: 'slot-a',
    generation: data.generation, resetInFlight: false, uncertainMutation: false,
    names: { containers: { backend: 'backend', postgres: 'postgres', probe: 'probe' } },
    containers: { backend: { id: '1'.repeat(64) }, postgres: { id: '2'.repeat(64) }, probe: { id: '3'.repeat(64) } } }] };
  const calls = []; let fault;
  const run = async (args, options) => {
    calls.push({ args, options });
    if (fault) await fault(args);
    if (args[0] === 'info') return JSON.stringify({ ID: resources.engineId, OSType: 'linux' });
    if (args[0] === 'container') {
      const role = args[2] === '1'.repeat(64) ? 'backend' : 'postgres';
      return JSON.stringify([{ Id: args[2], Name: `/${role}`, State: { Running: true }, Config: { Labels: {
        'io.approval.evaluation.owner': namespace, 'io.approval.evaluation.slot': 'slot-a',
        'io.approval.evaluation.generation': resources.slots[0].generation, 'io.approval.evaluation.role': role,
      } } }]);
    }
    if (args.includes('psql')) {
      assert.equal(args.at(-1), evaluationBusinessSnapshotSql);
      return JSON.stringify({ instances: data.instances, attachments: data.attachments, outbox: data.outbox, counts: data.counts });
    }
    if (args.includes('cat')) return JSON.stringify(data.sandbox);
    if (args.includes('sh')) {
      const content = options.input.toString(); const entries = content.trim().split('\n').slice(1).map(line => {
        const [eventId, idempotencyKey, aggregateId, businessKey, purchaseOrderReference] = line.split('\t');
        return { eventId, idempotencyKey, aggregateId, businessKey, purchaseOrderReference };
      });
      data.sandbox = { available: true, acceptedPayments: entries, acceptedPaymentResults: entries.length,
        allowlistSha256: hash(content), failure: null };
      for (const row of data.outbox) Object.assign(row, { status: 'DELIVERED', responseCode: 200,
        providerRequestId: `local-payment-sandbox-${row.eventId}`, lastError: null, deliveredAt: '2026-09-08 00:00:00+00' });
      return '';
    }
    throw new Error('unexpected fixture command');
  };
  return { data, resources, calls, setFault: value => { fault = value; },
    evidence: createEvaluationPaymentEvidence({ adapter: { snapshot: () => structuredClone(resources) }, run }) };
}
test('exact allowlist is built from real-shaped rows and travels only on stdin before recovery', async () => {
  const f = evidenceFixture(); const result = await f.evidence.recover('slot-a', new AbortController().signal);
  assert.equal(result.status, 'EXACT_SIGNED_SANDBOX_PAYMENT_DELIVERED');
  const publish = f.calls.find(value => value.args.includes('sh'));
  assert.ok(publish.options.input.toString().startsWith('PURCHASE_PAYMENT_EXACT_EVENTS_V1\tdemo-purchase-payment\n'));
  assert.equal(publish.args.some(value => value.includes(f.data.outbox[0].eventId)), false);
  assert.match(publish.args.at(-1), /mv .*events.*printf/u);
  await assert.rejects(f.evidence.recover('slot-a', new AbortController().signal), /ALREADY_REQUESTED/u);
});
for (const fault of ['bad-content', 'truncated-counts', 'wrong-generation', 'foreign-resource', 'unavailable-slot']) {
  test(`operator evidence refuses ${fault}`, async () => {
    const f = evidenceFixture();
    if (fault === 'bad-content') f.data.attachments[0].storedContentSha256 = '0'.repeat(64);
    if (fault === 'truncated-counts') f.data.counts.instances = 900;
    if (fault === 'unavailable-slot') f.resources.slots[0].state = 'RESETTING';
    if (fault === 'wrong-generation') f.setFault(args => { if (args.includes('psql')) f.resources.slots[0].generation = 'f'.repeat(32); });
    if (fault === 'foreign-resource') f.resources.slots[0].names.containers.backend = 'not-backend';
    await assert.rejects(f.evidence.snapshot('slot-a', new AbortController().signal));
    assert.equal(f.calls.some(value => value.args.includes('sh')), false);
  });
}
test('uncertain recovery publication is not silently retried', async () => {
  const f = evidenceFixture(); f.setFault(args => { if (args.includes('sh')) throw new Error('daemon uncertainty'); });
  await assert.rejects(f.evidence.recover('slot-a', new AbortController().signal));
  f.setFault(null); await assert.rejects(f.evidence.recover('slot-a', new AbortController().signal), /ALREADY_REQUESTED/u);
});

test('CI code retains all existing stages and adds required real business API/reset rehearsal without another image build', () => {
  const code = readFileSync(resolve(root, 'scripts/product-readiness/online-demo-images-runtime.mjs'), 'utf8');
  assert.ok(code.indexOf('const business = await executeEvaluationBusinessRehearsal(') > code.indexOf('const slots = await executeEvaluationSlotRehearsal('));
  assert.equal(code.match(/await executeImageRuntime\(root\)/gu).length, 1);
  assert.match(code, /business.status !== 'TWO_SESSION_REAL_BUSINESS_API_RESET_PASSED'/u);
  assert.match(evaluationBusinessSnapshotSql, /BEGIN READ ONLY/u);
  assert.doesNotMatch(evaluationBusinessSnapshotSql, /\b(?:DELETE|UPDATE|INSERT|TRUNCATE)\b/u);
});

// End-to-end rehearsal CONTROL FLOW test: HTTPS/cookies/session controller are real.
// Database, Docker and payment state below are deliberately in-memory doubles.
// This test must never be cited as a real application, payment or browser acceptance result.
function fakeRuntimeFactory(capture, failAt) {
  return async options => {
    const states = new Map(); const resources = new Map(); let controller;
    function reset(slotId) {
      const value = fresh(); value.slotId = slotId; value.generation = randomBytes(16).toString('hex');
      value.instances = [{ instanceId: randomUUID(), businessKey: 'DEMO-PP-0001', status: 'RUNNING', attachmentIds: [] }];
      value.attachments = []; value.counts = { instances: 1, attachments: 0, outbox: 0 };
      value.tasks = []; value.contents = new Map(); states.set(slotId, value);
      resources.set(slotId, { slotId, generation: value.generation });
    }
    const snapshot = id => {
      const { tasks, contents, ...value } = states.get(id);
      value.counts = { instances: value.instances.length, attachments: value.attachments.length, outbox: value.outbox.length };
      return structuredClone(value);
    };
    const respond = (data, status = 200, contentType = 'application/json') => ({ status, headers: { 'content-type': contentType },
      body: Buffer.isBuffer(data) ? data : Buffer.from(JSON.stringify(data)) });
    const next = (value, instance, index) => {
      instance.step = index;
      if (index === scenario.expectedWorkflow.length) {
        instance.status = 'COMPLETED'; const eventId = randomUUID();
        value.outbox.push({ eventId, aggregateId: instance.instanceId, status: 'PENDING', attempts: 1,
          lastError: 'HTTP 503: payment sandbox unavailable' }); return;
      }
      const step = scenario.expectedWorkflow[index];
      for (const actorId of step.actorIds) value.tasks.push({ taskId: randomUUID(), instanceId: instance.instanceId,
        taskDefinitionKey: step.taskDefinitionKey, actorId });
    };
    controller = createEvaluationSessions({ scenario, slotIds: ['slot-a', 'slot-b'], clock: options.clock,
      sessionTtlMs: options.sessionTtlMs,
      resetSlot: async ({ slotId, resetNonce }) => { reset(slotId); return { slotId, resetNonce, generation: resources.get(slotId).generation, clean: true }; },
      dispatchBusiness: async ({ slotId, generation, actorId, request }) => {
        assert.equal(generation, resources.get(slotId).generation, 'transport must use the actual adapter generation');
        const value = states.get(slotId); const path = request.target.split('?')[0];
        if (path === '/api/approval/attachments' && request.method === 'POST') {
          const form = await new Response(request.body, { headers: { 'Content-Type': request.contentType } }).formData();
          const file = form.get('file'); const bytes = Buffer.from(await file.arrayBuffer());
          const attachment = { attachmentId: randomUUID(), instanceId: null, sha256: hash(bytes), storedContentSha256: hash(bytes) };
          value.attachments.push(attachment); value.contents.set(attachment.attachmentId, bytes); return respond(attachment);
        }
        if (path === '/api/approval/forms/purchase-payment/versions/1/submissions') {
          const form = JSON.parse(request.body); const body = { businessKey: form.businessKey, ...form.values, attachmentIds: form.values.attachments };  const instance = { ...body, instanceId: randomUUID(), status: 'RUNNING' };
          value.instances.push(instance); for (const a of value.attachments) if (body.attachmentIds.includes(a.attachmentId)) a.instanceId = instance.instanceId;
          next(value, instance, 0); return respond({ instanceId: instance.instanceId });
        }
        if (path === '/api/approval/tasks/pending') return respond({ items: value.tasks.filter(task => task.actorId === actorId) });
        if (path.endsWith('/approve')) {
          const id = path.split('/')[4]; const task = value.tasks.find(task => task.taskId === id);
          if (!task || task.actorId !== actorId) return respond({}, 404);
          const instance = value.instances.find(i => i.instanceId === task.instanceId);
          value.tasks = value.tasks.filter(t => t.taskId !== id);
          if (!value.tasks.some(t => t.instanceId === instance.instanceId)) next(value, instance, instance.step + 1);
          return respond({ instanceId: instance.instanceId });
        }
        if (path.includes('/attachments/')) {
          const id = path.split('/')[4]; const body = value.contents.get(id);
          return body ? respond(body, 200, 'text/plain') : respond({}, 404);
        }
        if (path.includes('/instances/')) {
          const instance = value.instances.find(i => i.instanceId === path.split('/')[4]);
          if (!instance) return respond({}, 404);
          return respond(path.endsWith('/timeline') ? { instanceId: instance.instanceId, items: [{ action: 'APPROVED' }] } : { instance });
        }
        return respond({}, 404);
      } });
    await controller.reset('slot-a'); await controller.reset('slot-b');
    const server = createEvaluationHttpsServer({ ...options, controller });
    const runtime = { controller, server,
      snapshot: () => ({ resources: { slots: [...resources.values()] } }),
      payment: { snapshot: async id => snapshot(id), recover: async id => {
        if (failAt === 'payment') throw new Error('payment failure');
        const value = states.get(id); for (const row of value.outbox) row.status = 'DELIVERED';
        value.sandbox.available = true; value.sandbox.acceptedPaymentResults = value.outbox.length;
        value.sandbox.acceptedPayments = value.outbox.map(row => ({ eventId: row.eventId }));
        return { status: 'EXACT_SIGNED_SANDBOX_PAYMENT_DELIVERED' };
      } },
      dispose: async () => {
        controller.disable(); server.closeAllConnections();
        if (server.listening) await new Promise(done => server.close(done));
        capture.disposed = true; return { status: failAt === 'cleanup' ? 'FAILED' : 'PASSED' };
      } };
    return runtime;
  };
}
for (const fault of [null, 'payment', 'cleanup']) test(`real HTTPS rehearsal control flow with substituted application: ${fault || 'success'}`, async t => {
  const directory = mkdtempSync(resolve(tmpdir(), 'evaluation-business-rehearsal-test-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const capture = {}; const source = { commitSha: 'a'.repeat(40), treeSha: 'b'.repeat(40) };
  const smoke = { status: 'LOCAL_IMAGE_STARTUP_SMOKE_PASSED', cleanup: { status: 'PASSED' }, source,
    build: { status: 'LOCAL_IMAGES_BUILT_NOT_RUNTIME_ACCEPTED', source, images: [{ component: 'backend' }] } };
  const pending = executeEvaluationBusinessRehearsal({ smoke, directory, scenario, maximumMs: 20000 },
    { createRuntime: fakeRuntimeFactory(capture, fault) });
  if (fault) await assert.rejects(pending); else assert.equal((await pending).status, 'TWO_SESSION_REAL_BUSINESS_API_RESET_PASSED');
  const receipt = JSON.parse(readFileSync(resolve(directory, 'evaluation-business-rehearsal.json')));
  assert.equal(capture.disposed, true); assert.equal(receipt.status === 'FAILED', Boolean(fault));
  assert.ok(receipt.nonClaims.includes('PC_H5_BROWSER_E2E_NOT_EXECUTED'));
});
