import { createEvaluationApplicationAssets } from './evaluation-applications.mjs';
import { createEvaluationDockerSlots, evaluationSlotIds } from './evaluation-docker-slots.mjs';
import { createEvaluationSessions } from './evaluation-sessions.mjs';
import { createEvaluationPaymentEvidence } from './evaluation-payment-evidence.mjs';
import { createEvaluationHttpsServer } from './evaluation-http.mjs';

/** Connect the real controller, per-generation Docker signer and existing HTTPS entry.
 * Trusted operator API, not a public invitation issuer or a second identity store.
 * Does not build, pull, publish or bind a listening socket. Workflow opt-in reuses the existing Seed and real application APIs.
 */
export async function createEvaluationReadRuntime({ source, backendImage, infrastructure, archiveSha256,
  scenario, record, key, cert, origin, signal, sessionTtlMs = 30 * 60_000,
  invitationTtlMs = 5 * 60_000, resetBudgetMs = 55_000, maximumLifetimeMs = 40 * 60_000, clock, run, workflow = false, applicationRoots } = {}) {
  if (typeof workflow !== 'boolean' || !key || !cert || !(signal === undefined || signal instanceof AbortSignal)
      || !Number.isSafeInteger(maximumLifetimeMs) || maximumLifetimeMs < 100
      || maximumLifetimeMs > 40 * 60_000) {
    throw new Error('EVALUATION_RUNTIME_CONFIGURATION_REQUIRED');
  }
  if (applicationRoots !== undefined && !workflow) throw new Error('WORKFLOW_APPLICATIONS_REQUIRED');
  const applications = applicationRoots === undefined ? undefined
    : createEvaluationApplicationAssets({ source, roots: applicationRoots });
  let adapter; let controller; let server; let disposing; let lifetime;
  const lifetimeStop = new AbortController();
  const operationSignal = signal ? AbortSignal.any([signal, lifetimeStop.signal]) : lifetimeStop.signal;
  async function dispose() {
    if (disposing) return disposing;
    disposing = Promise.resolve().then(async () => {
      clearTimeout(lifetime); lifetimeStop.abort();
      controller?.disable();
      server?.closeAllConnections();
      if (server?.listening) await new Promise(done => server.close(done));
      const cleanup = adapter ? await adapter.dispose() : { status: 'NOT_CREATED' };
      operationSignal.removeEventListener('abort', onAbort);
      return cleanup;
    });
    return disposing;
  }
  const onAbort = () => { void dispose().catch(() => { /* Caller checks dispose result. */ }); };
  try {
    if (operationSignal.aborted) throw new Error('EVALUATION_RUNTIME_ABORTED');
    lifetime = setTimeout(() => lifetimeStop.abort(), maximumLifetimeMs);
    lifetime.unref();
    adapter = createEvaluationDockerSlots({ source, backendImage, infrastructure, archiveSha256,
      scenario, record, resetBudgetMs, run, readOnlyIdentity: !workflow, sessionReads: !workflow, workflow });
    controller = createEvaluationSessions({ scenario, slotIds: evaluationSlotIds, clock,
      sessionTtlMs, invitationTtlMs, resetTimeoutMs: 60_000,
      resetSlot: request => adapter.resetSlot({ ...request,
        signal: AbortSignal.any([request.signal, operationSignal]) }),
      readPending: workflow ? undefined : request => adapter.readPending(request),
      dispatchBusiness: workflow ? request => adapter.dispatchBusiness(request) : undefined });
    server = createEvaluationHttpsServer({ key, cert, controller, origin, applications });
    operationSignal.addEventListener('abort', onAbort, { once: true });
    for (const id of evaluationSlotIds) await controller.reset(id);
    if (operationSignal.aborted || controller.snapshot().disabled) throw new Error('EVALUATION_RUNTIME_ABORTED');
    // A closed listener must never leave an active signer or reusable environment.
    server.once('close', onAbort);
    const payment = workflow ? createEvaluationPaymentEvidence({ adapter, run }) : null;
    return Object.freeze({ server, controller, dispose, payment,
      snapshot: () => ({ controller: controller.snapshot(), resources: adapter.snapshot() }),
      scope: workflow ? 'SESSION_BOUND_PURCHASE_PAYMENT_API_NOT_BROWSER_ACCEPTANCE' : 'SESSION_BOUND_PENDING_READ_ONLY_NOT_FULL_APPROVAL' });
  } catch {
    let cleanup;
    try { cleanup = await dispose(); } catch { cleanup = { status: 'FAILED' }; }
    const error = new Error('EVALUATION_RUNTIME_START_FAILED');
    error.cleanupStatus = cleanup.status;
    throw error;
  }
}
