/** Browser transport only. The gateway remains the authority for role, tenant and stack. */
export interface EvaluationSessionView {
  actorId: string;
  actors: ReadonlyArray<{ id: string; displayName: string }>;
  expiresInSeconds: number;
}
interface Session extends EvaluationSessionView { csrfToken: string }
interface Options {
  origin: string;
  fetch: typeof globalThis.fetch;
  now?: () => number;
  onInvalidated?: (code: string) => void;
  requestTimeoutMs?: number;
}
export class EvaluationClientError extends Error {
  readonly retryable = false;
  readonly code: string;
  readonly status: number;
  constructor(code: string, status = 401) {
    super(code === 'EVALUATION_RESULT_UNCERTAIN'
      ? '操作结果尚未确认，请返回试用入口重置环境，勿重复提交。'
      : '试用会话已变化、到期或不可用，请返回试用入口确认。');
    this.name = 'EvaluationClientError';
    this.code = code;
    this.status = status;
  }
}
const actors = new Set(['demo-employee', 'demo-manager', 'demo-finance-reviewer',
  'demo-finance-approver-a', 'demo-finance-approver-b']);
const sessionPath = '/evaluation/session';
const deny = (code: string, status = 400): never => { throw new EvaluationClientError(code, status); };
const allowedHeaders = new Set(['accept', 'content-type', 'idempotency-key', 'x-request-id', 'x-trace-id']);

/** Explicit deployment switch; no URL query, browser storage or development-identity fallback. */
export function approvalEvaluationEnabled() {
  const enabled = import.meta.env.VITE_APPROVAL_ONLINE_EVALUATION === 'true';
  if (enabled && import.meta.env.VITE_APPROVAL_LOCAL_DEMO === 'true') return deny('EVALUATION_PROFILE_CONFLICT');
  return enabled;
}

export function createEvaluationBrowserSession(options: Options) {
  const configured = new URL(options.origin);
  if (configured.protocol !== 'https:' || configured.origin !== options.origin
    || configured.username || configured.password || typeof options.fetch !== 'function') {
    return deny('EVALUATION_HTTPS_ORIGIN_REQUIRED');
  }
  const timeoutMs = options.requestTimeoutMs ?? 10_000;
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 10 || timeoutMs > 15_000) {
    return deny('EVALUATION_TIMEOUT_INVALID');
  }
  const clock = options.now ?? (() => performance.now());
  let lastTime = -1;
  let session: Session | null = null;
  let deadline = 0;
  let epoch = 0;
  let terminal: EvaluationClientError | null = null;
  let preparing: Promise<void> | null = null;
  let tail: Promise<void> = Promise.resolve();
  let queued = 0;
  let writeQueued = false;
  const active = new Set<AbortController>();

  function invalidate(code: string, status = 401): never {
    if (!terminal) {
      terminal = new EvaluationClientError(code, status);
      epoch += 1;
      session = null;
      deadline = 0;
      for (const operation of active) operation.abort();
      options.onInvalidated?.(code);
    }
    throw terminal;
  }
  function now() {
    if (terminal) throw terminal;
    let time: number;
    try { time = clock(); } catch { return invalidate('EVALUATION_CLOCK_INVALID', 503); }
    if (!Number.isFinite(time) || time < 0 || time < lastTime) return invalidate('EVALUATION_CLOCK_INVALID', 503);
    lastTime = time;
    return time;
  }
  function current(version: number) {
    const time = now();
    if (version !== epoch || !session) return invalidate('EVALUATION_SESSION_CHANGED');
    if (time >= deadline) return invalidate('EVALUATION_SESSION_EXPIRED');
    return session;
  }
  async function bytes(response: Response, maximum: number, signal: AbortSignal) {
    if (!response.body) return new Uint8Array(0);
    // Fixed-length, unencoded network bodies are bounded by HTTP framing itself.
    // Use the native body loader rather than exposing the raw fetch stream: Blink's
    // stream Close path also cancels its consumer. Never infer completion from JSON.
    const length = response.headers.get('Content-Length');
    const encoding = response.headers.get('Content-Encoding');
    if (response.type === 'basic' && length !== null
      && !response.headers.has('Transfer-Encoding') && (!encoding || encoding === 'identity')) {
      if (!/^(?:0|[1-9][0-9]{0,9})$/u.test(length) || Number(length) > maximum) {
        await response.body.cancel().catch(() => undefined);
        return deny('EVALUATION_RESPONSE_LIMIT', 502);
      }
      const result = new Uint8Array(await response.arrayBuffer());
      if (signal.aborted) return deny('EVALUATION_REQUEST_CANCELLED', 499);
      if (result.byteLength !== Number(length)) return deny('EVALUATION_RESPONSE_INVALID', 502);
      return result;
    }
    // Unframed, encoded and synthetic responses still need incremental byte limits.
    const chunks: Uint8Array[] = [];
    let size = 0;
    // Let the native pipe own the reader through source closure. Do not manually
    // cancel/release a fetch reader while its network completion is being observed.
    try {
      await response.body.pipeTo(new WritableStream<Uint8Array>({
        write(chunk) {
          if (signal.aborted) return deny('EVALUATION_REQUEST_CANCELLED', 499);
          if (!(chunk instanceof Uint8Array)) return deny('EVALUATION_RESPONSE_INVALID', 502);
          size += chunk.byteLength;
          if (size > maximum) return deny('EVALUATION_RESPONSE_LIMIT', 502);
          chunks.push(chunk);
        },
      }), { signal });
    } catch (error) {
      if (signal.aborted) return deny('EVALUATION_REQUEST_CANCELLED', 499);
      throw error;
    }
    if (signal.aborted) return deny('EVALUATION_REQUEST_CANCELLED', 499);
    const result = new Uint8Array(size);
    let position = 0;
    for (const chunk of chunks) { result.set(chunk, position); position += chunk.byteLength; }
    return result;
  }
  async function bounded<T>(operation: (signal: AbortSignal) => Promise<T>, caller?: AbortSignal | null) {
    if (caller?.aborted) return deny('EVALUATION_REQUEST_CANCELLED', 499);
    const start = now();
    const cancellation = new AbortController();
    const abort = () => cancellation.abort();
    caller?.addEventListener('abort', abort, { once: true });
    active.add(cancellation);
    const timer = setTimeout(abort, timeoutMs);
    let failed: (() => void) | undefined;
    try {
      const result = await Promise.race([operation(cancellation.signal), new Promise<never>((_unused, reject) => {
        failed = () => reject(new EvaluationClientError('EVALUATION_REQUEST_CANCELLED', 499));
        cancellation.signal.addEventListener('abort', failed, { once: true });
        if (cancellation.signal.aborted) failed();
      })]);
      if (cancellation.signal.aborted || now() - start >= timeoutMs) return deny('EVALUATION_REQUEST_CANCELLED', 499);
      return result;
    } finally {
      clearTimeout(timer);
      caller?.removeEventListener('abort', abort);
      if (failed) cancellation.signal.removeEventListener('abort', failed);
      active.delete(cancellation);
    }
  }
  async function readSession(signal: AbortSignal): Promise<Session> {
    const response = await options.fetch(sessionPath, {
      method: 'GET', credentials: 'same-origin', mode: 'same-origin', cache: 'no-store', redirect: 'error', signal,
    });
    if (response.status !== 200 || response.redirected) return invalidate('EVALUATION_SESSION_REQUIRED');
    const body = await bytes(response, 8192, signal);
    let value: unknown;
    try { value = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(body)); }
    catch { return invalidate('EVALUATION_SESSION_INVALID', 502); }
    const v = value as Record<string, unknown> | null;
    if (!v || Object.keys(v).sort().join(',') !== 'actorId,actors,businessAccess,csrfToken,expiresInSeconds,scope'
      || v.scope !== 'DISPOSABLE_EVALUATION_CONTROL_PLANE' || v.businessAccess !== 'PURCHASE_PAYMENT_WORKFLOW'
      || typeof v.csrfToken !== 'string' || !/^[A-Za-z0-9_-]{43}$/u.test(v.csrfToken)
      || typeof v.actorId !== 'string' || !actors.has(v.actorId)
      || typeof v.expiresInSeconds !== 'number' || !Number.isInteger(v.expiresInSeconds)
      || v.expiresInSeconds < 1 || v.expiresInSeconds > 1800 || !Array.isArray(v.actors)
      || v.actors.length !== actors.size || new Set(v.actors.map(a => a?.id)).size !== actors.size
      || v.actors.some(a => !a || !actors.has(a.id) || typeof a.displayName !== 'string' || a.displayName.length > 100)) {
      return invalidate('EVALUATION_SESSION_INVALID', 502);
    }
    return { actorId: v.actorId, csrfToken: v.csrfToken, expiresInSeconds: v.expiresInSeconds,
      actors: v.actors.map(a => ({ id: a.id, displayName: a.displayName })) };
  }
  async function verifySession(version: number, signal: AbortSignal) {
    const captured = current(version);
    const start = now();
    const updated = await readSession(signal);
    current(version);
    if (updated.csrfToken !== captured.csrfToken || updated.actorId !== captured.actorId) {
      return invalidate('EVALUATION_SESSION_CHANGED');
    }
    // The server reports rounded-up seconds. Never extend an already-observed deadline.
    deadline = Math.min(deadline, start + Math.max(0, updated.expiresInSeconds - 1) * 1000);
    current(version);
  }
  function initialize() {
    if (terminal) return Promise.reject(terminal);
    if (session) { current(epoch); return Promise.resolve(); }
    if (preparing) return preparing;
    const version = epoch;
    const start = now();
    preparing = bounded(async signal => {
      const candidate = await readSession(signal);
      if (version !== epoch || terminal) return invalidate('EVALUATION_SESSION_CHANGED');
      session = candidate;
      deadline = start + Math.max(0, candidate.expiresInSeconds - 1) * 1000;
      current(version);
    }).catch(() => invalidate('EVALUATION_SESSION_REQUIRED'));
    return preparing;
  }
  async function verify() {
    await initialize();
    try { await bounded(signal => verifySession(epoch, signal)); }
    catch { return invalidate('EVALUATION_SESSION_REQUIRED'); }
  }
  function view(): EvaluationSessionView {
    if (!session && !terminal) return deny('EVALUATION_SESSION_NOT_READY', 503);
    const value = current(epoch);
    return { actorId: value.actorId, actors: value.actors.map(a => ({ ...a })),
      expiresInSeconds: Math.max(0, Math.floor((deadline - now()) / 1000)) };
  }
  function prepare(path: string, init: RequestInit) {
    // Paths are relative to the existing API contract, never a caller-selected host.
    if (typeof path !== 'string' || path.length > 1024 || !path.startsWith('/approval/') || /[#\\\x00-\x20]/u.test(path)
      || path.slice(0, path.indexOf('?') < 0 ? undefined : path.indexOf('?')).includes('%') || path.includes('//') || path.includes('/.')) {
      return deny('EVALUATION_PATH_REJECTED');
    }
    const method = init.method ?? 'GET';
    if (!['GET', 'POST'].includes(method)) return deny('EVALUATION_METHOD_REJECTED');
    const headers = new Headers(init.headers);
    headers.forEach((_unused, key) => { if (!allowedHeaders.has(key)) deny('EVALUATION_HEADER_REJECTED'); });
    const write = method === 'POST';
    let body = init.body;
    if (!write && body != null) return deny('EVALUATION_BODY_REJECTED');
    if (write) {
      if (!/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/u.test(headers.get('Idempotency-Key') || '')) return deny('EVALUATION_IDEMPOTENCY_REQUIRED');
      if (typeof FormData !== 'undefined' && body instanceof FormData) {
        const clone = new FormData();
        body.forEach((value, name) => { clone.append(name, value); });
        body = clone;
        headers.delete('Content-Type'); // Browser chooses the multipart boundary and length.
      } else {
        if (typeof body !== 'string') return deny('EVALUATION_BODY_REJECTED');
        if (!headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
      }
    }
    headers.set('Accept', 'application/json');
    return { target: '/api' + path, headers, body, method, write };
  }
  async function request(path: string, init: RequestInit = {}): Promise<Response> {
    const prepared = prepare(path, init);
    if (queued >= 8 || (prepared.write && writeQueued)) return deny('EVALUATION_REQUEST_BUSY', 409);
    const version = epoch;
    queued += 1;
    if (prepared.write) writeQueued = true;
    const operation = tail.then(async () => {
      await initialize();
      current(version);
      let dispatched = false;
      try {
        return await bounded(async signal => {
          await verifySession(version, signal);
          if (signal.aborted) return deny('EVALUATION_REQUEST_CANCELLED', 499);
          prepared.headers.set('X-Evaluation-CSRF', current(version).csrfToken);
          dispatched = true;
          const response = await options.fetch(prepared.target, {
            method: prepared.method, body: prepared.body, headers: prepared.headers,
            credentials: 'same-origin', mode: 'same-origin', cache: 'no-store', redirect: 'error', signal,
          });
          if (response.redirected) return deny('EVALUATION_REDIRECT_REJECTED', 502);
          const body = await bytes(response, 2_097_152, signal);
          current(version);
          if (prepared.write && response.status >= 500) return invalidate('EVALUATION_RESULT_UNCERTAIN', 503);
          await verifySession(version, signal);
          current(version);
          return new Response([204, 205, 304].includes(response.status) ? null : body, {
            status: response.status, headers: response.headers,
          });
        }, init.signal);
      } catch (error) {
        if (terminal) throw terminal;
        if (prepared.write && dispatched) return invalidate('EVALUATION_RESULT_UNCERTAIN', 503);
        if (error instanceof EvaluationClientError) throw error;
        return invalidate('EVALUATION_REQUEST_UNAVAILABLE', 503);
      }
    });
    // Keep the scheduling chain usable; every caller receives its own original outcome.
    tail = operation.then(() => undefined, () => undefined);
    try { return await operation; }
    finally { queued -= 1; if (prepared.write) writeQueued = false; }
  }
  return Object.freeze({ initialize, verify, view, fetch: request,
    dispose() {
      if (terminal) return;
      try { invalidate('EVALUATION_SESSION_CLOSED'); } catch { /* Revocation is already applied. */ }
    } });
}

let browserSession: ReturnType<typeof createEvaluationBrowserSession> | undefined;
export function getEvaluationBrowserSession() {
  if (!approvalEvaluationEnabled() || typeof window === 'undefined') return deny('EVALUATION_BROWSER_REQUIRED');
  if (!browserSession) browserSession = createEvaluationBrowserSession({
    origin: window.location.origin, fetch: window.fetch.bind(window),
    onInvalidated: () => { window.location.replace('/evaluation'); },
  });
  return browserSession;
}
