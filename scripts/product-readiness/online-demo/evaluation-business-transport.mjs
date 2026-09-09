import { randomUUID } from 'node:crypto';
import { businessTicketHeader } from './evaluation-business-request.mjs';

/** Runs inside the exact owned probe namespace. Only stdin carries proof and body bytes. */
export async function fetchEvaluationBusinessResponse(input, port = 8080) {
  if (!input || Object.keys(input).sort().join(',') !== 'bodyBase64,contentType,idempotencyKey,method,requestId,target,ticket'
      || !['GET', 'POST'].includes(input.method) || !/^\/api\/approval\/[\x21-\x7e]{1,1000}$/u.test(input.target || '')
      || /[\\#\r\n]/u.test(input.target) || input.target.includes('//')
      || typeof input.bodyBase64 !== 'string' || input.bodyBase64.length > 1_400_000
      || typeof input.ticket !== 'string' || !/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/u.test(input.ticket)
      || input.ticket.length > 4096 || !Number.isInteger(port) || port < 1 || port > 65535) throw new Error('INVALID_BUSINESS_TRANSPORT');
  const body = Buffer.from(input.bodyBase64, 'base64');
  if (body.toString('base64') !== input.bodyBase64 || body.length > 1_048_576
      || input.method === 'GET' && body.length) throw new Error('INVALID_BUSINESS_BODY');
  const headers = { 'X-Evaluation-Business-Ticket': input.ticket, 'X-Request-Id': input.requestId };
  if (input.contentType) headers['Content-Type'] = input.contentType;
  if (input.idempotencyKey) headers['Idempotency-Key'] = input.idempotencyKey;
  const response = await fetch(`http://127.0.0.1:${port}${input.target}`, {
    method: input.method, headers, body: input.method === 'POST' ? body : undefined,
    redirect: 'error', signal: AbortSignal.timeout(5000),
  });
  let length = 0; const parts = [];
  for await (const part of response.body) {
    length += part.length; if (length > 2_097_152) throw new Error('BUSINESS_RESPONSE_LIMIT'); parts.push(part);
  }
  const outgoing = {};
  for (const name of ['content-type', 'content-disposition', 'x-content-sha256', 'x-request-id']) {
    const value = response.headers.get(name);
    if (value !== null && value.length <= 512 && !/[\r\n]/u.test(value)) outgoing[name] = value;
  }
  // Never relay an internal exception body to the evaluator.
  if (response.status >= 500) return { status: response.status, headers: { 'content-type': 'application/json' },
    bodyBase64: Buffer.from(JSON.stringify({ error: 'APPROVAL_TEMPORARILY_UNAVAILABLE' })).toString('base64') };
  return { status: response.status, headers: outgoing, bodyBase64: Buffer.concat(parts).toString('base64') };
}
export const evaluationBusinessTransportProgram = [
  fetchEvaluationBusinessResponse.toString(),
  'let count=0; const parts=[];',
  'for await (const bytes of process.stdin) { count+=bytes.length; if(count>1500000) throw new Error("INPUT_LIMIT"); parts.push(bytes); }',
  'try { const result=await fetchEvaluationBusinessResponse(JSON.parse(Buffer.concat(parts).toString("utf8"))); console.log(JSON.stringify(result)); }',
  'catch { console.log(JSON.stringify({error:"EVALUATION_TRANSPORT_UNCERTAIN"})); process.exitCode=1; }',
].join('\n');

export async function sendEvaluationBusinessRequest(signer, actorId, request, probeId, command) {
  if (!/^[0-9a-f]{64}$/u.test(probeId || '')) throw new Error('EXACT_PROBE_REQUIRED');
  const requestId = `evaluation-${randomUUID()}`;
  const payload = { method: request.method, target: request.target, contentType: request.contentType,
    idempotencyKey: request.idempotencyKey, bodyBase64: request.body.toString('base64'), requestId,
    ticket: signer.issue(actorId, request, requestId) };
  const input = Buffer.from(JSON.stringify(payload));
  const raw = await command(['exec', '-i', probeId, 'node', '--input-type=module', '-e', evaluationBusinessTransportProgram], input);
  if (typeof raw !== 'string' || Buffer.byteLength(raw) > 3_000_000) throw new Error('BUSINESS_RESPONSE_LIMIT');
  const value = JSON.parse(raw);
  if (!value || Object.keys(value).sort().join(',') !== 'bodyBase64,headers,status'
      || !Number.isInteger(value.status) || value.status < 200 || value.status > 599
      || !value.headers || Object.getPrototypeOf(value.headers) !== Object.prototype
      || typeof value.bodyBase64 !== 'string') throw new Error('BUSINESS_RESPONSE_REJECTED');
  for (const [name, text] of Object.entries(value.headers)) {
    if (!['content-type', 'content-disposition', 'x-content-sha256', 'x-request-id'].includes(name)
        || typeof text !== 'string' || text.length > 512 || /[\r\n]/u.test(text)) throw new Error('BUSINESS_HEADER_REJECTED');
  }
  const body = Buffer.from(value.bodyBase64, 'base64');
  if (body.length > 2_097_152 || body.toString('base64') !== value.bodyBase64) throw new Error('BUSINESS_RESPONSE_REJECTED');
  return { status: value.status, headers: value.headers, body };
}
