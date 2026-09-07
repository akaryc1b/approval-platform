import { randomBytes } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { performance } from 'node:perf_hooks';
import { createEvaluationDockerSlots, evaluationSlotIds, runEvaluationDocker } from './evaluation-docker-slots.mjs';
import { createEvaluationSessions } from './evaluation-sessions.mjs';

const requireCondition = (value, message) => { if (!value) throw new Error(message); };
const resourceId = value => typeof value === 'string' && /^[0-9a-f]{64}$/u.test(value);
const writeJson = (directory, file, value) => writeFileSync(resolve(directory, file),
  `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
const tcpProbe = `import { connect } from 'node:net';
const [host, rawPort] = process.argv.slice(1);
const socket = connect({ host, port: Number(rawPort) });
let completed = false;
function finish(value) { if (completed) return; completed = true; socket.destroy(); console.log(value); }
socket.setTimeout(1000);
socket.once('connect', () => finish('CONNECTED'));
socket.once('error', () => finish('BLOCKED'));
socket.once('timeout', () => finish('BLOCKED'));`;

/** Real CI integration: reuse already-built images; never rebuild or publish. */
export async function executeEvaluationSlotRehearsal({ smoke, directory, scenario, maximumMs = 360_000 }, {
  run = runEvaluationDocker,
} = {}) {
  requireCondition(smoke?.status === 'LOCAL_IMAGE_STARTUP_SMOKE_PASSED' && smoke.cleanup?.status === 'PASSED'
    && smoke.build?.status === 'LOCAL_IMAGES_BUILT_NOT_RUNTIME_ACCEPTED'
    && JSON.stringify(smoke.source) === JSON.stringify(smoke.build.source), 'PASSED_EXACT_IMAGE_SMOKE_REQUIRED');
  requireCondition(Number.isSafeInteger(maximumMs) && maximumMs >= 1000 && maximumMs <= 360_000, 'INVALID_REHEARSAL_BUDGET');
  const started = performance.now();
  const stop = new AbortController();
  const timer = setTimeout(() => stop.abort(), maximumMs);
  let time = 1000; // Expiry clock is controlled; Docker operations use real elapsed time.
  let adapter; let controller; let failure;
  const receipt = { schemaVersion: 1, kind: 'EVALUATION_PRIVATE_SLOT_REHEARSAL', status: 'RUNNING',
    source: smoke.source, checks: [], phase: 'INITIALIZE_SLOTS', cleanup: null,
    scope: 'TWO_REAL_BACKEND_DATABASE_CACHE_STACKS_WITH_SYNTHETIC_RESET_MARKERS',
    nonClaims: ['BUSINESS_API_IDENTITY_NOT_CONNECTED', 'APPROVAL_ATTACHMENT_OUTBOX_RESET_NOT_VERIFIED',
      'SIGNED_PAYMENT_SANDBOX_NOT_STARTED', 'BROWSER_BUSINESS_E2E_NOT_EXECUTED',
      'HOST_GATEWAY_EGRESS_NOT_BLOCKED_BY_INTERNAL_NETWORK', 'PUBLIC_URL_NOT_PUBLISHED'] };
  const save = () => writeJson(directory, 'evaluation-slot-rehearsal.json', receipt);
  const checkTime = () => requireCondition(!stop.signal.aborted && performance.now() - started < maximumMs, 'REHEARSAL_DEADLINE');
  async function command(args) {
    checkTime();
    const value = await run(args, { signal: stop.signal, timeoutMs: 5000 }); checkTime(); return value;
  }
  const slot = id => adapter.snapshot().slots.find(value => value.slotId === id);
  const container = (id, role) => { const value = slot(id)?.containers[role]?.id; requireCondition(resourceId(value), 'EXACT_RESOURCE_ID_REQUIRED'); return value; };
  const sql = (id, query) => command(['exec', container(id, 'postgres'), 'psql', '-U', 'approval', '-d', 'approval', '-qAt', '-v', 'ON_ERROR_STOP=1', '-c', query]);
  const redis = (id, ...args) => command(['exec', container(id, 'redis'), 'redis-cli', '--raw', ...args]);
  async function cleanMarkers(id) {
    requireCondition(await sql(id, "select to_regclass('public.evaluation_reset_probe') is null;") === 't', 'DATABASE_MARKER_REMAINED');
    requireCondition(await redis(id, 'EXISTS', 'evaluation:reset-probe') === '0', 'CACHE_MARKER_REMAINED');
    requireCondition(await command(['exec', container(id, 'backend'), 'sh', '-c',
      'test ! -e /tmp/evaluation-reset-probe.txt && printf CLEAN']) === 'CLEAN', 'TMPFS_MARKER_REMAINED');
  }
  async function mark(id, value) {
    requireCondition(/^[0-9a-f]{32}$/u.test(value), 'INVALID_SYNTHETIC_MARKER');
    await sql(id, `create table evaluation_reset_probe (value text not null); insert into evaluation_reset_probe values ('${value}');`);
    requireCondition(await redis(id, 'SET', 'evaluation:reset-probe', value) === 'OK', 'CACHE_MARKER_WRITE_FAILED');
    await command(['exec', container(id, 'backend'), 'sh', '-c', 'printf %s "$1" > /tmp/evaluation-reset-probe.txt', 'probe', value]);
  }
  async function verifyMarker(id, value) {
    requireCondition(await sql(id, 'select value from evaluation_reset_probe;') === value, 'DATABASE_MARKER_CHANGED');
    requireCondition(await redis(id, 'GET', 'evaluation:reset-probe') === value, 'CACHE_MARKER_CHANGED');
    requireCondition(await command(['exec', container(id, 'backend'), 'cat', '/tmp/evaluation-reset-probe.txt']) === value, 'TMPFS_MARKER_CHANGED');
  }
  async function networkChecks() {
    const addresses = {};
    for (const id of evaluationSlotIds) {
      addresses[id] = {};
      for (const role of ['postgres', 'redis']) {
        const values = JSON.parse(await command(['container', 'inspect', container(id, role)]));
        requireCondition(values.length === 1 && values[0].Id === container(id, role), 'NETWORK_PROBE_IDENTITY');
        const address = values[0].NetworkSettings.Networks[slot(id).names.network]?.IPAddress;
        requireCondition(typeof address === 'string' && /^(?:[0-9]{1,3}\.){3}[0-9]{1,3}$/u.test(address), 'OWNED_IPV4_REQUIRED');
        addresses[id][role] = address;
      }
    }
    for (const from of evaluationSlotIds) for (const to of evaluationSlotIds) for (const [role, port] of [['postgres', '5432'], ['redis', '6379']]) {
      const result = await command(['exec', container(from, 'probe'), 'node', '--input-type=module', '-e', tcpProbe, addresses[to][role], port]);
      requireCondition(result === (from === to ? 'CONNECTED' : 'BLOCKED'), 'CROSS_SLOT_NETWORK_BOUNDARY_FAILED');
      receipt.checks.push({ check: 'OWNED_DATABASE_CACHE_TCP', from, to, role, result });
    }
  }
  save();
  try {
    adapter = createEvaluationDockerSlots({ source: smoke.source, backendImage: smoke.build.images[0],
      infrastructure: smoke.infrastructure, archiveSha256: smoke.build.archiveSha256,
      record: value => writeJson(directory, 'evaluation-slot-resources.json', value), run });
    controller = createEvaluationSessions({ scenario, slotIds: evaluationSlotIds, resetTimeoutMs: 60_000,
      sessionTtlMs: 1000, clock: () => time,
      resetSlot: request => adapter.resetSlot({ ...request, signal: AbortSignal.any([request.signal, stop.signal]) }) });
    for (const id of evaluationSlotIds) { checkTime(); await controller.reset(id); await cleanMarkers(id); }
    const enter = () => controller.redeem(controller.issueInvitation().invitation);
    const a = enter(); const b = enter();
    requireCondition(a.token !== b.token && a.session.businessAccess === 'NOT_CONNECTED', 'CONTROL_SESSION_BOUNDARY');
    const markerA = randomBytes(16).toString('hex'); const markerB = randomBytes(16).toString('hex');
    receipt.phase = 'WRITE_AND_VERIFY_MARKERS'; save();
    await mark('slot-a', markerA); await mark('slot-b', markerB);
    await verifyMarker('slot-a', markerA); await verifyMarker('slot-b', markerB);
    receipt.phase = 'CROSS_SLOT_NETWORK_PROBES'; save(); await networkChecks();
    receipt.beforeReset = adapter.snapshot(); save();
    receipt.phase = 'SESSION_END_RESET'; save();
    const ended = controller.end(a.token, a.session.csrfToken);
    let revoked = false; try { controller.status(a.token); } catch (error) { revoked = error.code === 'SESSION_REQUIRED'; }
    await ended; requireCondition(revoked, 'OLD_CREDENTIAL_STILL_VALID');
    await cleanMarkers('slot-a'); await verifyMarker('slot-b', markerB);
    requireCondition(JSON.stringify(slot('slot-b')) === JSON.stringify(receipt.beforeReset.slots[1]), 'OTHER_SLOT_RESOURCES_CHANGED');
    requireCondition(controller.status(b.token).businessAccess === 'NOT_CONNECTED', 'OTHER_CONTROL_SESSION_CHANGED');
    receipt.checks.push({ check: 'SESSION_END_REAL_RESET', oldCredentialRevoked: true,
      databaseMarkerRemoved: true, cacheMarkerRemoved: true, tmpfsMarkerRemoved: true, otherSlotPreserved: true });
    time = 1500; const replacement = enter(); await mark('slot-a', markerA);
    const replacementState = slot('slot-a');
    receipt.phase = 'EXPIRY_RESET'; save();
    time = 2000; const swept = await controller.sweep();
    requireCondition(swept.attempted === 1 && swept.failed === 0, 'EXPIRY_RESET_FAILED');
    await cleanMarkers('slot-b'); await verifyMarker('slot-a', markerA);
    requireCondition(JSON.stringify(slot('slot-a')) === JSON.stringify(replacementState), 'EXPIRY_TOUCHED_OTHER_SLOT');
    requireCondition(controller.status(replacement.token).businessAccess === 'NOT_CONNECTED', 'REPLACEMENT_SESSION_LOST');
    receipt.checks.push({ check: 'CONTROLLED_CLOCK_EXPIRY_REAL_RESET', resetSlots: 1, otherSlotPreserved: true });
    receipt.afterReset = adapter.snapshot(); checkTime(); receipt.checksPassed = true;
  } catch {
    failure = true; receipt.failure = 'EVALUATION_SLOT_REHEARSAL_FAILED';
  } finally {
    clearTimeout(timer); controller?.disable();
    receipt.cleanup = adapter ? await adapter.dispose() : { status: 'NOT_CREATED' };
    receipt.controller = controller?.snapshot() || null;
    receipt.elapsedMs = Math.round(performance.now() - started);
    receipt.status = !failure && receipt.checksPassed && receipt.cleanup.status === 'PASSED'
      ? 'PRIVATE_TWO_SLOT_RESET_REHEARSAL_PASSED' : 'FAILED';
    save();
  }
  if (receipt.status !== 'PRIVATE_TWO_SLOT_RESET_REHEARSAL_PASSED') throw new Error('EVALUATION_SLOT_REHEARSAL_FAILED');
  return receipt;
}
