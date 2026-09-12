import { createServer } from 'node:https';
import { isEvaluationApplicationAssets } from './evaluation-applications.mjs';
import { performance } from 'node:perf_hooks';
import { evaluationBusinessRoute, maximumBusinessBody } from './evaluation-business-request.mjs';
import { EvaluationError, startEvaluationExpiryWorker, validEvaluationToken } from './evaluation-sessions.mjs';
import { evaluationAsset, evaluationCsp, evaluationPage } from './evaluation-page.mjs';

export const evaluationCookie = '__Host-approval-evaluation';
const deny = (code, status) => { throw new EvaluationError(code, status); };
function singleHeader(request, name) {
  const count = request.rawHeaders.filter((value, index) => index % 2 === 0 && value.toLowerCase() === name).length;
  if (count > 1) deny('DUPLICATE_HEADER', 400);
  return request.headers[name];
}
function cookie(request) {
  const value = singleHeader(request, 'cookie');
  if (value === undefined) return null;
  if (typeof value !== 'string' || value.length > 1024) deny('COOKIE_REJECTED', 400);
  const matches = value.split(';').map(part => part.trim()).filter(part => part.startsWith(`${evaluationCookie}=`));
  if (matches.length > 1) deny('COOKIE_REJECTED', 400);
  if (!matches.length) return null;
  const result = matches[0].slice(evaluationCookie.length + 1);
  if (!validEvaluationToken(result)) deny('COOKIE_REJECTED', 400);
  return result;
}
function setCookie(response, value, seconds) {
  response.setHeader('Set-Cookie', `${evaluationCookie}=${value}; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=${seconds}`);
}
function readJson(request) {
  if (!/^application\/json(?:\s*;\s*charset=utf-8)?$/iu.test(singleHeader(request, 'content-type') || '')
      || request.headers['content-encoding']) deny('JSON_REQUIRED', 415);
  const declared = singleHeader(request, 'content-length');
  if (declared !== undefined && (!/^\d+$/u.test(declared) || Number(declared) > 512)) deny('BODY_TOO_LARGE', 413);
  return new Promise((resolve, reject) => {
    const chunks = []; let size = 0;
    const cleanup = () => { clearTimeout(timer); request.off('data', data); request.off('end', end);
      request.off('aborted', aborted); request.off('error', aborted); };
    const failed = (code, status) => { cleanup(); request.pause(); reject(new EvaluationError(code, status)); };
    const data = bytes => { size += bytes.length; if (size > 512) failed('BODY_TOO_LARGE', 413); else chunks.push(bytes); };
    const end = () => { cleanup(); try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); }
      catch { reject(new EvaluationError('INVALID_JSON', 400)); } };
    const aborted = () => failed('BODY_ABORTED', 400);
    const timer = setTimeout(() => failed('BODY_TIMEOUT', 408), 2000);
    request.on('data', data); request.once('end', end); request.once('aborted', aborted); request.once('error', aborted);
  });
}
function readBusinessBody(request) {
  const length = singleHeader(request, 'content-length');
  if (request.headers['content-encoding'] || request.headers['transfer-encoding']
      || length === undefined || !/^(?:0|[1-9][0-9]*)$/u.test(length)
      || Number(length) > maximumBusinessBody) deny('BUSINESS_BODY_REJECTED', 413);
  return new Promise((resolve, reject) => {
    let size = 0; const parts = [];
    const clear = () => { clearTimeout(timer); request.off('data', data); request.off('end', end);
      request.off('error', failed); request.off('aborted', failed); };
    const failed = () => { clear(); request.pause(); reject(new EvaluationError('BUSINESS_BODY_REJECTED', 400)); };
    const data = part => { size += part.length; if (size > maximumBusinessBody || size > Number(length)) failed(); else parts.push(part); };
    const end = () => { clear(); if (size !== Number(length)) { reject(new EvaluationError('BUSINESS_BODY_REJECTED', 400)); return; }
      resolve(Buffer.concat(parts)); };
    const timer = setTimeout(failed, 5000);
    request.on('data', data); request.once('end', end); request.once('error', failed); request.once('aborted', failed);
  });
}
function exactBody(value, fields) {
  if (!value || Object.getPrototypeOf(value) !== Object.prototype
      || Object.keys(value).sort().join(',') !== [...fields].sort().join(',')) deny('INVALID_BODY', 400);
}

/** TLS terminates here. Forwarded headers are never authentication or TLS proof. */
export function createEvaluationRequestHandler({ controller, origin, applications }) {
  const configured = new URL(origin);
  if (configured.protocol !== 'https:' || configured.origin !== origin
      || configured.username || configured.password) throw new Error('exact HTTPS origin required');
  if (!controller || typeof controller.redeem !== 'function') throw new Error('session controller required');
  if (applications !== undefined && (!isEvaluationApplicationAssets(applications)
      || controller.businessAccess !== 'PURCHASE_PAYMENT_WORKFLOW')) throw new Error('workflow application registry required');
  const sessionReads = new Map();
  let active = 0; let windowStart = performance.now(); let requests = 0; let assetRequests = 0; let assetBytes = 0;
  return async (request, response) => {
    response.setHeader('Connection', 'close');
    response.setHeader('Cache-Control', 'no-store');
    response.setHeader('X-Robots-Tag', 'noindex, nofollow, noarchive');
    response.setHeader('X-Content-Type-Options', 'nosniff');
    response.setHeader('X-Frame-Options', 'DENY');
    response.setHeader('Referrer-Policy', 'no-referrer');
    response.setHeader('Content-Security-Policy', evaluationCsp);
    response.setHeader('Content-Type', 'application/json; charset=utf-8');
    let admitted = false;
    try {
      if (request.socket.encrypted !== true) deny('HTTPS_REQUIRED', 403);
      response.setHeader('Strict-Transport-Security', 'max-age=31536000');
      if (singleHeader(request, 'host') !== configured.host) deny('HOST_REJECTED', 403);
      const time = performance.now();
      if (time - windowStart >= 60_000) {
        requests = 0; assetRequests = 0; assetBytes = 0; sessionReads.clear(); windowStart = time;
      }
      const staticRequest = request.method === 'GET' && applications?.has(request.url);
      // Pre/post identity checks read only this controller's memory, not a business backend.
      // Reserve a bounded allowance per trusted slot; token/actor/generation rotation cannot refill it.
      let statusSlot = null;
      if (controller.businessAccess === 'PURCHASE_PAYMENT_WORKFLOW' && request.method === 'GET'
          && request.url === '/evaluation/session' && typeof controller.businessBinding === 'function') {
        try {
          const binding = controller.businessBinding(cookie(request));
          if (typeof binding?.slotId === 'string' && /^[a-z][a-z0-9-]{0,63}$/u.test(binding.slotId)) statusSlot = binding.slotId;
        } catch { /* Invalid credentials keep the general budget and the original authentication checks below. */ }
      }
      let overLimit;
      if (statusSlot !== null) {
        // The existing controller has at most two configured slots. Never retain a browser-supplied key.
        if (!sessionReads.has(statusSlot) && sessionReads.size >= 2) deny('REQUEST_LIMIT', 429);
        const used = sessionReads.get(statusSlot) || 0;
        sessionReads.set(statusSlot, Math.min(used + 1, 241));
        overLimit = used >= 240;
      } else {
        overLimit = staticRequest ? ++assetRequests > 1000
          : ++requests > (controller.businessAccess === 'PURCHASE_PAYMENT_WORKFLOW' ? 240 : 120);
      }
      if (overLimit || active >= 8) deny('REQUEST_LIMIT', 429);
      active += 1; admitted = true;
      for (const name of ['authorization', 'x-tenant-id', 'x-operator-id', 'x-approval-trusted-permissions',
        'x-evaluation-slot', 'x-slot-id', 'x-actor-id', 'x-evaluation-generation',
        'x-evaluation-reset-nonce', 'x-evaluation-read-ticket', 'x-evaluation-business-ticket']) {
        if (request.headers[name] !== undefined) deny('CLIENT_IDENTITY_REJECTED', 403);
      }
      const suppliedOrigin = singleHeader(request, 'origin');
      if (suppliedOrigin !== undefined && suppliedOrigin !== origin) deny('ORIGIN_REJECTED', 403);
      if (request.headers['sec-fetch-site'] === 'cross-site') deny('ORIGIN_REJECTED', 403);
      const path = request.url;
      if (request.method === 'GET' && (request.headers['transfer-encoding']
          || Number(singleHeader(request, 'content-length') || 0) !== 0)) deny('BODY_REJECTED', 400);
      if (request.method === 'GET' && path === '/evaluation') {
        response.setHeader('Content-Type', 'text/html; charset=utf-8'); response.end(evaluationPage); return;
      }
      const asset = request.method === 'GET' ? evaluationAsset(path) : null;
      if (asset) {
        response.setHeader('Content-Type', asset.type); response.end(asset.body); return;
      }
      const credential = cookie(request);
      if (applications && request.method === 'GET') {
        if (applications.has(path) || path === '/evaluation/applications') {
          try { controller.status(credential); }
          catch (error) {
            if (error instanceof EvaluationError && error.status === 401
                && request.headers['sec-fetch-dest'] === 'document') {
              response.writeHead(303, { Location: '/evaluation' }); response.end(); return;
            }
            throw error;
          }
          const application = applications.get(path);
          if (application) {
            assetBytes += application.body.length;
            if (assetBytes > 512 * 1024 * 1024) deny('REQUEST_LIMIT', 429);
            response.setHeader('Content-Security-Policy', application.csp);
            response.setHeader('Content-Type', application.type);
            response.end(application.body);
          } else response.end(JSON.stringify(applications.entries));
          return;
        }
      }
      if (controller.businessAccess === 'PURCHASE_PAYMENT_WORKFLOW' && typeof path === 'string' && path.startsWith('/api/approval/')) {
        let route;
        try { route = evaluationBusinessRoute(request.method, path); } catch { deny('NOT_FOUND', 404); }
        controller.status(credential); // Reject unauthenticated bodies before buffering them.
        if (route.write && suppliedOrigin !== origin) deny('ORIGIN_REJECTED', 403);
        const csrf = singleHeader(request, 'x-evaluation-csrf');
        const cancellation = new AbortController();
        const abandoned = () => { if (!response.writableEnded) cancellation.abort(); };
        request.once('aborted', abandoned); response.once('close', abandoned);
        try {
          const body = route.write ? await readBusinessBody(request) : Buffer.alloc(0);
          const result = await controller.dispatchBusiness(credential, csrf, {
            method: request.method, target: path, contentType: route.write ? singleHeader(request, 'content-type') || '' : '',
            idempotencyKey: route.write ? singleHeader(request, 'idempotency-key') || '' : '', body,
          }, cancellation.signal);
          if (response.destroyed) return;
          for (const [name, value] of Object.entries(result.headers)) {
            if (['content-type', 'content-disposition', 'x-content-sha256', 'x-request-id'].includes(name)
                && typeof value === 'string' && value.length <= 512 && !/[\r\n]/u.test(value)) response.setHeader(name, value);
          }
          response.statusCode = result.status; response.end(result.body);
        } finally { request.off('aborted', abandoned); response.off('close', abandoned); }
        return;
      }
      if (request.method === 'GET' && path === '/api/approval/tasks/pending'
          && controller.businessAccess === 'SIGNED_PENDING_READ') {
        if (request.headers['content-encoding']) deny('BODY_REJECTED', 400);
        const csrf = singleHeader(request, 'x-evaluation-csrf');
        const cancellation = new AbortController();
        const abandoned = () => { if (!response.writableEnded) cancellation.abort(); };
        request.once('aborted', abandoned); response.once('close', abandoned);
        try {
          const result = await controller.readPending(credential, csrf, cancellation.signal);
          if (!response.destroyed) response.end(JSON.stringify(result));
        } finally {
          request.off('aborted', abandoned); response.off('close', abandoned);
        }
        return;
      }
      if (request.method === 'GET' && path === '/evaluation/session') {
        response.end(JSON.stringify(controller.status(credential))); return;
      }
      if (request.method !== 'POST' || !['/evaluation/invitations/redeem', '/evaluation/session/actor',
        '/evaluation/session/end'].includes(path)) deny('NOT_FOUND', 404);
      if (suppliedOrigin !== origin) deny('ORIGIN_REJECTED', 403);
      const csrf = singleHeader(request, 'x-evaluation-csrf');
      const body = await readJson(request);
      if (path === '/evaluation/invitations/redeem') {
        exactBody(body, ['invitation']);
        if (credential) {
          try { controller.status(credential); deny('SESSION_ALREADY_ACTIVE', 409); }
          catch (error) { if (!(error instanceof EvaluationError) || error.code !== 'SESSION_REQUIRED') throw error; }
        }
        const result = controller.redeem(body.invitation);
        setCookie(response, result.token, result.session.expiresInSeconds);
        response.writeHead(201); response.end(JSON.stringify(result.session)); return;
      }
      if (path === '/evaluation/session/actor') {
        exactBody(body, ['actorId']);
        const result = controller.changeActor(credential, csrf, body.actorId);
        setCookie(response, result.token, result.session.expiresInSeconds);
        response.end(JSON.stringify(result.session)); return;
      }
      exactBody(body, []);
      const reset = controller.end(credential, csrf); // Credentials revoked synchronously.
      setCookie(response, '', 0);
      await reset;
      response.writeHead(204); response.end();
    } catch (error) {
      if (response.destroyed) return;
      const known = error instanceof EvaluationError;
      response.statusCode = known ? error.status : 500;
      if (response.statusCode === 429) response.setHeader('Retry-After', '60');
      response.end(JSON.stringify({ error: known ? error.code : 'EVALUATION_UNAVAILABLE' }));
    } finally { if (admitted) active -= 1; }
  };
}

/** Caller owns listen/close and the trusted adapter; this never opens a public port. */
export function createEvaluationHttpsServer({ key, cert, controller, origin, applications }) {
  if (!key || !cert) throw new Error('TLS key and certificate are required');
  const server = createServer({ key, cert, minVersion: 'TLSv1.2', handshakeTimeout: 5000, maxHeaderSize: 8192 },
    createEvaluationRequestHandler({ controller, origin, applications }));
  server.maxConnections = 16;
  server.maxHeadersCount = 24;
  server.headersTimeout = 5000;
  server.requestTimeout = 5000;
  server.timeout = 65_000; // Allow the bounded reset to return a typed failure.
  server.keepAliveTimeout = 1000;
  server.maxRequestsPerSocket = 20;
  let stopWorker;
  server.once('listening', () => { stopWorker = startEvaluationExpiryWorker(controller); });
  server.once('close', () => { controller.disable(); void stopWorker?.(); });
  return server;
}
