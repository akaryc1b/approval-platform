import { execFile } from 'node:child_process';
import { once } from 'node:events';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { request } from 'node:https';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { promisify } from 'node:util';
import { createEvaluationHttpsServer } from './evaluation-http.mjs';
import { validEvaluationToken } from './evaluation-sessions.mjs';
import { validateEvaluationPendingPage } from './evaluation-pending-read.mjs';

const run = promisify(execFile);
const hostname = 'evaluation.example.invalid';
/** CI-only verified loopback TLS exercise; it is not a browser or hosted URL.
 * Uses the existing handler/controller and only the exact pending-read route.
 * Key files are private, temporary and removed before the harness is returned.
 */
export async function openEvaluationHttpsReadCheck(controller, signal) {
  if (controller?.businessAccess !== 'SIGNED_PENDING_READ' || !(signal instanceof AbortSignal) || signal.aborted) {
    throw new Error('SIGNED_SESSION_CONTROLLER_REQUIRED');
  }
  const directory = mkdtempSync(resolve(tmpdir(), 'evaluation-read-check-'));
  let server; let certificate; let closing;
  const close = () => {
    if (closing) return closing;
    closing = Promise.resolve().then(async () => {
      server?.closeAllConnections();
      if (server?.listening) await new Promise(done => server.close(done));
      signal.removeEventListener('abort', onAbort);
    });
    return closing;
  };
  const onAbort = () => { void close().catch(() => { /* The awaited close still fails the rehearsal. */ }); };
  try {
    const keyFile = resolve(directory, 'key.pem'); const certFile = resolve(directory, 'cert.pem');
    await run('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-sha256', '-nodes', '-days', '1',
      '-keyout', keyFile, '-out', certFile, '-subj', `/CN=${hostname}`,
      '-addext', `subjectAltName=DNS:${hostname}`], { timeout: 10_000, signal, maxBuffer: 65536 });
    certificate = readFileSync(certFile);
    server = createEvaluationHttpsServer({ key: readFileSync(keyFile), cert: certificate,
      origin: `https://${hostname}`, controller });
    const listening = once(server, 'listening');
    server.listen(0, '127.0.0.1'); await listening;
    if (signal.aborted) throw new Error('READ_CHECK_ABORTED');
    signal.addEventListener('abort', onAbort, { once: true });
  } catch {
    await close(); throw new Error('HTTPS_READ_CHECK_START_FAILED');
  } finally { rmSync(directory, { recursive: true, force: true }); }

  async function read(token, csrf, expectedStatus) {
    if (!validEvaluationToken(token) || !validEvaluationToken(csrf)
        || ![200, 401, 403].includes(expectedStatus) || signal.aborted || closing) {
      throw new Error('INVALID_READ_CHECK');
    }
    try {
      return await new Promise((done, fail) => {
        const client = request({ hostname: '127.0.0.1', port: server.address().port,
          servername: hostname, ca: certificate, agent: false, timeout: 8000, signal,
          method: 'GET', path: '/api/approval/tasks/pending',
          headers: { Host: hostname, Cookie: `__Host-approval-evaluation=${token}`, 'X-Evaluation-CSRF': csrf } }, response => {
          let bytes = 0; const parts = [];
          response.on('data', part => { bytes += part.length;
            if (bytes > 65536) { response.destroy(); fail(new Error('READ_RESPONSE_LIMIT')); } else parts.push(part); });
          response.on('error', fail);
          response.on('aborted', () => fail(new Error('READ_RESPONSE_ABORTED')));
          response.on('end', () => {
            try {
              if (response.statusCode !== expectedStatus || response.headers['cache-control'] !== 'no-store'
                  || response.headers['set-cookie'] || response.headers['x-evaluation-read-ticket']) throw new Error('READ_REJECTED');
              let items = null;
              if (expectedStatus === 200) {
                const page = validateEvaluationPendingPage(JSON.parse(Buffer.concat(parts).toString('utf8')));
                if (page.total !== 0 || page.items.length !== 0) throw new Error('FRESH_READ_REQUIRED');
                items = 0;
              }
              done({ status: response.statusCode, items });
            } catch { fail(new Error('READ_REJECTED')); }
          });
        });
        client.once('error', fail);
        client.once('timeout', () => client.destroy(new Error('READ_TIMEOUT')));
        client.end();
      });
    } catch { throw new Error('HTTPS_SESSION_READ_CHECK_FAILED'); }
  }
  return Object.freeze({ read, close });
}
