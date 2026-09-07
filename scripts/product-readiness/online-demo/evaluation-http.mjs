import { createServer } from 'node:https';
import { performance } from 'node:perf_hooks';
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
function exactBody(value, fields) {
  if (!value || Object.getPrototypeOf(value) !== Object.prototype
      || Object.keys(value).sort().join(',') !== [...fields].sort().join(',')) deny('INVALID_BODY', 400);
}

/** TLS terminates here. Forwarded headers are never authentication or TLS proof. */
export function createEvaluationRequestHandler({ controller, origin }) {
  const configured = new URL(origin);
  if (configured.protocol !== 'https:' || configured.origin !== origin
      || configured.username || configured.password) throw new Error('exact HTTPS origin required');
  if (!controller || typeof controller.redeem !== 'function') throw new Error('session controller required');
  let active = 0; let windowStart = performance.now(); let requests = 0;
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
      if (time - windowStart >= 60_000) { requests = 0; windowStart = time; }
      if (++requests > 120 || active >= 8) deny('REQUEST_LIMIT', 429);
      active += 1; admitted = true;
      for (const name of ['authorization', 'x-tenant-id', 'x-operator-id', 'x-approval-trusted-permissions']) {
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
      const known = error instanceof EvaluationError;
      response.statusCode = known ? error.status : 500;
      if (response.statusCode === 429) response.setHeader('Retry-After', '60');
      response.end(JSON.stringify({ error: known ? error.code : 'EVALUATION_UNAVAILABLE' }));
    } finally { if (admitted) active -= 1; }
  };
}

/** Caller owns listen/close and the trusted adapter; this never opens a public port. */
export function createEvaluationHttpsServer({ key, cert, controller, origin }) {
  if (!key || !cert) throw new Error('TLS key and certificate are required');
  const server = createServer({ key, cert, minVersion: 'TLSv1.2', handshakeTimeout: 5000, maxHeaderSize: 8192 },
    createEvaluationRequestHandler({ controller, origin }));
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
