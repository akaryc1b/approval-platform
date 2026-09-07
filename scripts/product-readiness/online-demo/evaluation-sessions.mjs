import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';
import { performance } from 'node:perf_hooks';

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
  resetTimeoutMs = 30_000, clock = () => performance.now() } = {}) {
  const identity = evaluationActors(scenario);
  if (!Array.isArray(slotIds) || slotIds.length < 1 || slotIds.length > 2
      || slotIds.some(id => !identifier(id)) || new Set(slotIds).size !== slotIds.length
      || typeof resetSlot !== 'function' || typeof clock !== 'function') reject('INVALID_CONFIGURATION');
  bounded(sessionTtlMs, 1000, 30 * 60_000);
  bounded(invitationTtlMs, 1000, 15 * 60_000);
  bounded(resetTimeoutMs, 10, 60_000);
  const slots = new Map(slotIds.map(id => [id, { id, state: 'QUARANTINED',
    generation: null, session: null, inFlight: null, needsReset: false, revision: 0, abort: null }]));
  const invitations = new Map();
  const sessions = new Map();
  let disabled = false;
  let lastClock = -Infinity;
  let rateWindow = -Infinity;
  let attempts = 0;
  const metrics = { admitted: 0, expired: 0, resets: 0, resetFailures: 0 };

  function invalidate(slot, automatic = false) {
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
      businessAccess: 'NOT_CONNECTED', scope: 'DISPOSABLE_EVALUATION_CONTROL_PLANE' };
  }
  function rotate(slot, time, actorId, deadline = time + sessionTtlMs) {
    const value = token();
    const csrf = token();
    if (slot.session) sessions.delete(slot.session.key);
    slot.session = { key: hash(value), csrf, actorId, deadline };
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
    return rotate(slot, time, actorId, slot.session.deadline);
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
          || Object.keys(acknowledgement).sort().join(',') !== 'clean,resetNonce,slotId'
          || acknowledgement.slotId !== id || acknowledgement.resetNonce !== resetNonce
          || acknowledgement.clean !== true) reject('RESET_UNCONFIRMED', 503);
      slot.generation = resetNonce; slot.state = 'READY'; metrics.resets += 1;
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
  // Deliberately no backend URL, headers, principal or business dispatch method.
  return Object.freeze({ issueInvitation, revokeInvitation, redeem, status, changeActor,
    end, reset, sweep, snapshot, disable });
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
