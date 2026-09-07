import { generateKeyPairSync, randomBytes, sign } from 'node:crypto';
import { evaluationActors } from './evaluation-sessions.mjs';

export const evaluationReadPath = '/api/approval/tasks/pending';
export const evaluationReadHeader = 'X-Evaluation-Read-Ticket';
const requireValue = value => { if (!value) throw new Error('EVALUATION_READ_IDENTITY_REJECTED'); };

/** Private per-generation signer. No private key export, browser token or generic signing API. */
export function createEvaluationReadSigner(scenario, generation, clock = Date.now) {
  const identity = evaluationActors(scenario);
  requireValue(identity.tenantId === 'demo-purchase-payment' && identity.actors.length === 5);
  requireValue(typeof generation === 'string' && /^[0-9a-f]{32}$/u.test(generation)
    && !/^0+$/u.test(generation) && typeof clock === 'function');
  const { privateKey, publicKey } = generateKeyPairSync('ed25519');
  const publicKeyBase64 = publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
  let lastTime = -1;
  let disabled = false;
  return Object.freeze({
    publicKeyBase64,
    actors: Object.freeze(identity.actors.map(actor => actor.id)),
    issue(actorId) {
      requireValue(!disabled && identity.actors.some(actor => actor.id === actorId));
      let now;
      try { now = clock(); } catch { disabled = true; requireValue(false); }
      if (!Number.isSafeInteger(now) || now <= 0 || now < lastTime || now > 999_999_999_989_999) {
        disabled = true; requireValue(false);
      }
      lastTime = now;
      const message = Buffer.from(['AP-EVALUATION-READ-V1', 'GET', evaluationReadPath,
        generation, actorId, String(now), String(now + 10_000), randomBytes(32).toString('hex')].join('\n'));
      return `${message.toString('base64url')}.${sign(null, message, privateKey).toString('base64url')}`;
    },
    disable() { disabled = true; },
  });
}

// Executed in the existing non-root namespace-sharing probe, never in the browser.
// The caller retains only status/count evidence. Tickets and response bodies are not logged.
export const evaluationReadProbeProgram = `
const [ticket, path, extraHeader, rawPort = '8080'] = process.argv.slice(1);
if (!['/api/approval/tasks/pending', '/api/approval/definitions'].includes(path)) throw new Error('INVALID_PROBE_PATH');
const port = Number(rawPort);
if (!Number.isSafeInteger(port) || port < 1 || port > 65535) throw new Error('INVALID_PROBE_PORT');
const headers = ticket ? { 'X-Evaluation-Read-Ticket': ticket } : {};
if (extraHeader) headers['X-Operator-Id'] = 'demo-admin';
const response = await fetch('http://127.0.0.1:' + port + path, {
  method: 'GET', headers, redirect: 'error', signal: AbortSignal.timeout(1500),
});
let bytes = 0; const parts = [];
for await (const part of response.body) {
  bytes += part.length; if (bytes > 65536) throw new Error('RESPONSE_LIMIT'); parts.push(part);
}
let taskCount = null;
if (response.status === 200) {
  const page = JSON.parse(Buffer.concat(parts).toString('utf8'));
  if (!page || !Array.isArray(page.items) || page.total !== 0 || page.limit !== 20
      || page.offset !== 0) throw new Error('FRESH_PENDING_PAGE_REQUIRED');
  taskCount = page.items.length;
}
console.log(JSON.stringify({ status: response.status, taskCount }));
`;

/** Read-only authentication checks against the existing, freshly initialized task API. */
export async function verifyEvaluationReadIdentity(signer, probeId, command) {
  const checks = [];
  const request = async (ticket = '', path = evaluationReadPath, extra = '') => {
    const result = JSON.parse(await command(['exec', probeId, 'node', '--input-type=module', '-e',
      evaluationReadProbeProgram, ticket, path, extra]));
    requireValue(result && Object.keys(result).sort().join(',') === 'status,taskCount');
    return result;
  };
  requireValue((await request()).status === 401);
  checks.push('UNSIGNED_READ_REJECTED');
  for (const actor of signer.actors) {
    const ticket = signer.issue(actor);
    const result = await request(ticket);
    requireValue(result.status === 200 && result.taskCount === 0);
    requireValue((await request(ticket)).status === 401);
  }
  checks.push('CANONICAL_ACTORS_READ_PENDING', 'READ_TICKET_REPLAY_REJECTED');
  requireValue((await request(signer.issue(signer.actors[0]), evaluationReadPath, 'spoof')).status === 403);
  requireValue((await request(signer.issue(signer.actors[0]), '/api/approval/definitions')).status === 404);
  checks.push('CLIENT_IDENTITY_REJECTED', 'MANAGEMENT_PATH_REJECTED');
  return checks;
}
