import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import { evaluationBusinessRoute, normalizeEvaluationBusinessRequest } from './evaluation-business-request.mjs';

export class EvaluationError extends Error {
  constructor(code, status = 400) { super(code); this.code = code; this.status = status; }
}
const reject = (code, status) => { throw new EvaluationError(code, status); };
const hash = value => createHash('sha256').update(value).digest('hex');
const token = () => randomBytes(32).toString('base64url');
const identifier = value => typeof value === 'string' && /^[a-z][a-z0-9-]{0,63}$/u.test(value);
export const validEvaluationToken = value => typeof value === 'string'
  && /^[A-Za-z0-9_-]{43}$/u.test(value)
  && Buffer.from(value, 'base64url').toString('base64url') === value;
const equalToken = (left, right) => validEvaluationToken(left) && validEvaluationToken(right)
  && timingSafeEqual(Buffer.from(left), Buffer.from(right));
function bounded(value, minimum, maximum) {
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) reject('INVALID_CONFIGURATION');
  return value;
}

/** Use the canonical scenario's business actors, never its seed administrator. */
export function evaluationActors(scenario) {
  if (scenario?.schemaVersion !== 1 || !identifier(scenario.tenant?.id)
      || !Array.isArray(scenario.directory?.users) || !Array.isArray(scenario.expectedWorkflow)) {
    reject('INVALID_SCENARIO');
  }
  const users = new Map();
  for (const user of scenario.directory.users) {
    if (!user || !identifier(user.id) || users.has(user.id) || !Array.isArray(user.roleCodes)
        || !user.roleCodes.every(role => typeof role === 'string')
        || typeof user.displayName !== 'string' || user.displayName.length > 100) reject('INVALID_SCENARIO');
    users.set(user.id, user);
  }
  const initialActor = scenario.assigneeRules?.initiatorUserId?.value;
  const actors = new Set([initialActor]);
  for (const step of scenario.expectedWorkflow) {
    if (!step || !Array.isArray(step.actorIds) || !step.actorIds.length) reject('INVALID_SCENARIO');
    for (const id of step.actorIds) actors.add(id);
  }
  if (actors.size < 2 || actors.size > 8) reject('INVALID_SCENARIO');
  return { tenantId: scenario.tenant.id, initialActor, actors: [...actors].map(id => {
    const user = users.get(id);
    if (!user || user.roleCodes.some(role => /ADMIN|\*/iu.test(role))) reject('PRIVILEGED_OR_UNKNOWN_ACTOR');
    return Object.freeze({ id, displayName: user.displayName });
  }) };
}

/**
 * Single-process admission controller, not a business backend or tenant store.
 * A trusted reset adapter must wipe/provision each independently isolated slot.
 * Its nonce-bound acknowledgement is a contract, not independent database proof.
 * Every new controller starts quarantined, including after a process restart.
 */
export function createEvaluationSessions({ scenario, slotIds, resetSlot,
  sessionTtlMs = 30 * 60_000, invitationTtlMs = 5 * 60_000,
  resetTimeoutMs = 30_000, clock = () => performance.now(),
  readPending: transportRead, readTimeoutMs = 5000, dispatchBusiness: transportBusiness } = {}) {
  const identity = evaluationActors(scenario);
  if (!Array.isArray(slotIds) || slotIds.length < 1 || slotIds.length > 2
      || slotIds.some(id => !identifier(id)) || new Set(slotIds).size !== slotIds.length
      || typeof resetSlot !== 'function' || typeof clock !== 'function') reject('INVALID_CONFIGURATION');
  bounded(sessionTtlMs, 1000, 30 * 60_000);
  bounded(invitationTtlMs, 1000, 15 * 60_000);
  bounded(resetTimeoutMs, 10, 60_000);
  bounded(readTimeoutMs, 10, 5000);
  if (transportRead !== undefined && typeof transportRead !== 'function') reject('INVALID_CONFIGURATION');
  if (transportBusiness !== undefined && typeof transportBusiness !== 'function') reject('INVALID_CONFIGURATION');
  const slots = new Map(slotIds.map(id => [id, { id, state: 'QUARANTINED',
    generation: null, session: null, inFlight: null, needsReset: false, revision: 0, abort: null, reads: new Set(), writes: new Set(), writeUncertain: false }]));
  const invitations = new Map();
  const sessions = new Map();
  let disabled = false;
  let lastClock = -Infinity;
  let rateWindow = -Infinity;
  let attempts = 0;
  const metrics = { admitted: 0, expired: 0, resets: 0, resetFailures: 0 };

  function invalidate(slot, automatic = false) {
    slot.session?.access.abort();
    if (slot.session) sessions.delete(slot.session.key);
    slot.session = null;
    slot.state = 'QUARANTINED';
    slot.needsReset = automatic;
    slot.revision += 1;
  }
  function disable() {
    disabled = true;
    invitations.clear();
    for (const slot of slots.values()) { slot.abort?.abort(); invalidate(slot); }
  }
  function now() {
    if (disabled) reject('EVALUATION_DISABLED', 503);
    let value;
    try { value = clock(); } catch { disable(); reject('CLOCK_INVALID', 503); }
    if (!Number.isFinite(value) || value < lastClock || value < 0) {
      disable(); reject('CLOCK_INVALID', 503);
    }
    lastClock = value;
    return value;
  }
  function expire(time) {
    for (const [key, deadline] of invitations) if (deadline <= time) invitations.delete(key);
    for (const slot of slots.values()) {
      if (slot.session && slot.session.deadline <= time) {
        invalidate(slot, true); metrics.expired += 1;
      }
    }
  }
  function sessionFor(value) {
    const time = now(); expire(time);
    if (!validEvaluationToken(value)) reject('SESSION_REQUIRED', 401);
    const slot = sessions.get(hash(value));
    if (!slot || slot.state !== 'ACTIVE' || !slot.session) reject('SESSION_REQUIRED', 401);
    return { slot, time };
  }
  function csrfFor(slot, csrf) {
    if (!equalToken(csrf, slot.session.csrf)) reject('CSRF_REJECTED', 403);
  }
  function view(slot, time) {
    return { actorId: slot.session.actorId,
      actors: identity.actors.map(actor => ({ ...actor })), csrfToken: slot.session.csrf,
      expiresInSeconds: Math.ceil((slot.session.deadline - time) / 1000),
      businessAccess: transportBusiness ? 'PURCHASE_PAYMENT_WORKFLOW' : transportRead ? 'SIGNED_PENDING_READ' : 'NOT_CONNECTED', scope: 'DISPOSABLE_EVALUATION_CONTROL_PLANE' };
  }
  /**
   * Trusted gateway lookup.  The browser never receives the slot generation or
   * any backend address; callers must present the opaque cookie on every
   * business request.  Reset, expiry, logout and actor rotation all remove the
   * old token from `sessions`, so an already captured browser request cannot be
   * rebound to a replacement stack or actor.
   */
  function businessBinding(value) {
    const { slot } = sessionFor(value);
    if (!identifier(slot.id) || !/^[0-9a-f]{32}$/u.test(slot.generation || '') || /^0+$/u.test(slot.generation)) reject('SLOT_BINDING_INVALID', 503);
    return Object.freeze({ slotId: slot.id, generation: slot.generation,
      actorId: slot.session.actorId, tenantId: identity.tenantId,
      sessionRevision: slot.revision, expiresAt: slot.session.deadline });
  }
  function rotate(slot, time, actorId, deadline = time + sessionTtlMs) {
    slot.revision += 1;
    const value = token();
    const csrf = token();
    slot.session?.access.abort();
    if (slot.session) sessions.delete(slot.session.key);
    slot.session = { key: hash(value), csrf, actorId, deadline, access: new AbortController() };
    slot.state = 'ACTIVE'; sessions.set(slot.session.key, slot);
    return { token: value, session: view(slot, time) };
  }
  function issueInvitation() {
    const time = now(); expire(time);
    if (invitations.size >= 16) reject('INVITATION_LIMIT', 429);
    const value = token(); invitations.set(hash(value), time + invitationTtlMs);
    return { invitation: value, expiresInSeconds: Math.ceil(invitationTtlMs / 1000) };
  }
  function revokeInvitation(value) {
    now();
    if (!validEvaluationToken(value)) reject('INVITATION_REJECTED', 401);
    return invitations.delete(hash(value));
  }
  function redeem(value) {
    const time = now(); expire(time);
    if (time - rateWindow >= 60_000) { rateWindow = time; attempts = 0; }
    if (++attempts > 30) reject('INVITATION_RATE_LIMIT', 429);
    if (!validEvaluationToken(value) || !invitations.has(hash(value))) reject('INVITATION_REJECTED', 401);
    const slot = [...slots.values()].find(candidate => candidate.state === 'READY' && !candidate.inFlight);
    if (!slot) reject('NO_EVALUATION_SLOT', 429);
    // No await between consumption and allocation: concurrent redemption has one winner.
    invitations.delete(hash(value)); metrics.admitted += 1;
    return rotate(slot, time, identity.initialActor);
  }
  function status(value) { const { slot, time } = sessionFor(value); return view(slot, time); }
  function changeActor(value, csrf, actorId) {
    const { slot, time } = sessionFor(value); csrfFor(slot, csrf);
    if (!identity.actors.some(actor => actor.id === actorId)) reject('ACTOR_REJECTED', 403);
    if (slot.writeUncertain) reject('BUSINESS_RESET_REQUIRED', 409);
    if (slot.writes.size) reject('WRITE_IN_PROGRESS', 409);
    return rotate(slot, time, actorId, slot.session.deadline);
  }
  /** Private routing is derived from the session, never from browser tenant/slot fields.
   * This operation is read-only. Cancellation does not claim rollback of future writes.
   */
  async function readPending(value, csrf, externalSignal) {
    if (!transportRead) reject('BUSINESS_NOT_CONNECTED', 503);
    if (externalSignal !== undefined && !(externalSignal instanceof AbortSignal)) reject('INVALID_READ_SIGNAL');
    const { slot, time } = sessionFor(value); csrfFor(slot, csrf);
    if (slot.reads.size >= 2) reject('SESSION_READ_LIMIT', 429);
    const session = slot.session;
    const generation = slot.generation;
    const timeout = new AbortController();
    const combined = AbortSignal.any([session.access.signal, timeout.signal,
      ...(externalSignal ? [externalSignal] : [])]);
    const assertCurrent = () => {
      const current = sessionFor(value);
      if (combined.aborted || current.slot !== slot || slot.session !== session
          || slot.generation !== generation) reject('SESSION_READ_REVOKED', 401);
    };
    assertCurrent();
    const timer = setTimeout(() => timeout.abort(), Math.min(readTimeoutMs, Math.max(1, session.deadline - time)));
    // Bind to the adapter-confirmed generation, never the controller reset nonce. A new stack must
    // not receive a request delayed behind an old session or a role rotation.
    const operation = Promise.resolve().then(() => {
      assertCurrent();
      return transportRead(Object.freeze({ slotId: slot.id, generation,
        actorId: session.actorId, signal: combined }));
    });
    const chargedReads = slot.reads;
    chargedReads.add(operation);
    // Retain the concurrency charge until transport settles, even after abort.
    const settled = () => chargedReads.delete(operation);
    operation.then(settled, settled);
    let abortListener;
    try {
      const result = await Promise.race([operation, new Promise((unused, fail) => {
        abortListener = () => fail(new EvaluationError('SESSION_READ_REVOKED', 401));
        combined.addEventListener('abort', abortListener, { once: true });
        if (combined.aborted) abortListener();
      })]);
      assertCurrent();
      return result;
    } catch (error) {
      // Check state before reporting a backend failure; never return a late body.
      if (combined.aborted) {
        if (timeout.signal.aborted) reject('SESSION_READ_TIMEOUT', 504);
        reject('SESSION_READ_REVOKED', 401);
      }
      assertCurrent();
      reject('SESSION_READ_UNAVAILABLE', 503);
    } finally {
      clearTimeout(timer);
      if (abortListener) combined.removeEventListener('abort', abortListener);
    }
  }
  /** Commands are not replayed, and abort never claims that a write was rolled back. */
  async function dispatchBusiness(value, csrf, request, externalSignal) {
    if (!transportBusiness) reject('BUSINESS_NOT_CONNECTED', 503);
    const { slot, time } = sessionFor(value); csrfFor(slot, csrf);
    let route;
    try { route = evaluationBusinessRoute(request?.method, request?.target); }
    catch { reject('BUSINESS_ROUTE_REJECTED', 404); }
    if (!request || !Buffer.isBuffer(request.body) || request.body.length > 1_048_576
        || externalSignal !== undefined && !(externalSignal instanceof AbortSignal)) reject('INVALID_BUSINESS_REQUEST');
    if (slot.writeUncertain) reject('BUSINESS_RESET_REQUIRED', 503);
    const input = { ...request, body: Buffer.from(request.body) };
    const active = route.write ? slot.writes : slot.reads;
    if (active.size >= (route.write ? 1 : 2)) reject(route.write ? 'WRITE_IN_PROGRESS' : 'SESSION_READ_LIMIT', 409);
    const session = slot.session; const generation = slot.generation;
    const timeout = new AbortController();
    const signal = AbortSignal.any([session.access.signal, timeout.signal,
      ...(externalSignal ? [externalSignal] : [])]);
    const current = () => {
      const context = sessionFor(value);
      if (signal.aborted || context.slot !== slot || slot.session !== session
          || slot.generation !== generation) reject('BUSINESS_REQUEST_REVOKED', 401);
    };
    current();
    const timer = setTimeout(() => timeout.abort(), Math.min(10_000, Math.max(1, session.deadline - time)));
    let dispatched = false;
    const markUncertain = () => {
      if (route.write && dispatched && slot.generation === generation) slot.writeUncertain = true;
    };
    const operation = Promise.resolve().then(async () => {
      current();
      const normalized = await normalizeEvaluationBusinessRequest(input, scenario, session.actorId);
      current(); dispatched = true;
      return transportBusiness(Object.freeze({ slotId: slot.id, generation,
        actorId: session.actorId, request: normalized, signal }));
    });
    // A cancelled HTTP response does not release an unresolved write's ownership.
    active.add(operation);
    const settled = () => active.delete(operation);
    operation.then(settled, () => { markUncertain(); settled(); });
    let listener;
    try {
      const result = await Promise.race([operation, new Promise((unused, fail) => {
        listener = () => fail(new EvaluationError('BUSINESS_RESULT_UNCERTAIN', 503));
        signal.addEventListener('abort', listener, { once: true });
        if (signal.aborted) listener();
      })]);
      current();
      if (!result || !Number.isInteger(result.status) || result.status < 200 || result.status > 599
          || !Buffer.isBuffer(result.body) || result.body.length > 2_097_152
          || !result.headers || Object.getPrototypeOf(result.headers) !== Object.prototype) reject('BUSINESS_RESPONSE_REJECTED', 502);
      if (route.write && result.status >= 500) {
        markUncertain(); reject('BUSINESS_RESULT_UNCERTAIN', 503);
      }
      return result;
    } catch {
      markUncertain();
      if (signal.aborted) reject(route.write ? 'BUSINESS_RESULT_UNCERTAIN' : 'BUSINESS_REQUEST_REVOKED', route.write ? 503 : 401);
      current(); reject(dispatched ? 'BUSINESS_REQUEST_FAILED' : 'BUSINESS_REQUEST_REJECTED', dispatched ? 503 : 400);
    } finally {
      clearTimeout(timer); if (listener) signal.removeEventListener('abort', listener);
    }
  }
  async function reset(id) {
    now();
    const slot = slots.get(id);
    if (!slot) reject('UNKNOWN_SLOT');
    if (slot.inFlight || slot.state === 'RESETTING') reject('RESET_IN_PROGRESS', 409);
    invalidate(slot);
    slot.state = 'RESETTING';
    const revision = slot.revision;
    const resetNonce = token();
    const signal = new AbortController();
    slot.abort = signal;
    const started = performance.now();
    let timer;
    const operation = Promise.resolve().then(() => resetSlot(Object.freeze({
      slotId: id, resetNonce, signal: signal.signal,
    })));
    slot.inFlight = operation;
    // An adapter which ignores abort still owns this slot until it settles.
    const settled = () => { if (slot.inFlight === operation) slot.inFlight = null; };
    operation.then(settled, settled);
    try {
      const acknowledgement = await Promise.race([operation, new Promise((unused, fail) => {
        timer = setTimeout(() => { signal.abort(); fail(new EvaluationError('RESET_TIMEOUT', 503)); }, resetTimeoutMs);
      })]);
      now();
      // A blocked event loop must not turn an overdue adapter into a successful reset.
      if (performance.now() - started >= resetTimeoutMs) { signal.abort(); reject('RESET_TIMEOUT', 503); }
      if (disabled || slot.revision !== revision || slot.state !== 'RESETTING') reject('RESET_FENCED', 503);
      if (!acknowledgement || Object.getPrototypeOf(acknowledgement) !== Object.prototype
          || Object.keys(acknowledgement).sort().join(',') !== 'clean,generation,resetNonce,slotId'
          || acknowledgement.slotId !== id || acknowledgement.resetNonce !== resetNonce
          || acknowledgement.clean !== true
          || typeof acknowledgement.generation !== 'string'
          || !/^[0-9a-f]{32}$/u.test(acknowledgement.generation)
          || /^0+$/u.test(acknowledgement.generation)
          || acknowledgement.generation === slot.generation) reject('RESET_UNCONFIRMED', 503);
      slot.generation = acknowledgement.generation; slot.state = 'READY'; slot.writeUncertain = false;
      slot.reads = new Set(); slot.writes = new Set(); metrics.resets += 1;
      return { slotId: id, status: 'READY_AFTER_ADAPTER_ACKNOWLEDGEMENT' };
    } catch {
      invalidate(slot); metrics.resetFailures += 1;
      // Never publish adapter exception text, command output or credentials.
      reject('RESET_FAILED', 503);
    } finally { clearTimeout(timer); }
  }
  function end(value, csrf) {
    const { slot } = sessionFor(value); csrfFor(slot, csrf);
    return reset(slot.id);
  }
  async function sweep() {
    const time = now(); expire(time);
    const pending = [...slots.values()].filter(slot => slot.needsReset && !slot.inFlight);
    const results = await Promise.allSettled(pending.map(slot => reset(slot.id)));
    return { attempted: results.length, failed: results.filter(result => result.status === 'rejected').length };
  }
  function snapshot() {
    return { disabled, invitations: invitations.size, metrics: { ...metrics },
      slots: [...slots.values()].map(slot => ({ slotId: slot.id, state: slot.state,
        resetInFlight: Boolean(slot.inFlight), needsReset: slot.needsReset })) };
  }
  // No backend URL, signer, tenant selector or general-purpose write dispatch is exposed.
  return Object.freeze({ issueInvitation, revokeInvitation, redeem, status, changeActor,
    businessBinding, end, reset, sweep, snapshot, disable, readPending, dispatchBusiness,
    businessAccess: transportBusiness ? 'PURCHASE_PAYMENT_WORKFLOW' : transportRead ? 'SIGNED_PENDING_READ' : 'NOT_CONNECTED' });
}

export function startEvaluationExpiryWorker(controller, intervalMs = 1000) {
  bounded(intervalMs, 10, 10_000);
  let work = null;
  let stopped = false;
  const timer = setInterval(() => {
    if (work || stopped) return;
    work = controller.sweep().catch(() => { controller.disable(); })
      .finally(() => { work = null; });
  }, intervalMs);
  timer.unref();
  return async () => { stopped = true; clearInterval(timer); await work; };
}
