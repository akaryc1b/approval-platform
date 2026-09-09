import { createHash, generateKeyPairSync, randomBytes, sign } from 'node:crypto';
import { evaluationActors } from './evaluation-sessions.mjs';

export const businessTicketHeader = 'X-Evaluation-Business-Ticket';
export const maximumBusinessBody = 1_048_576;
const uuid = '[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}';
const identity = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/u;
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const requireValue = (condition, code = 'EVALUATION_BUSINESS_REQUEST_REJECTED') => {
  if (!condition) throw new Error(code);
};

/** Literal product routes, not a prefix proxy. The original target is signed unchanged. */
export function evaluationBusinessRoute(method, target) {
  requireValue(['GET', 'POST'].includes(method) && typeof target === 'string'
    && target.length <= 1024 && /^[\x21-\x7e]+$/u.test(target)
    && !/[#\\;]/u.test(target) && !target.includes('//'));
  const pieces = target.split('?');
  requireValue(pieces.length <= 2);
  const [path, query] = pieces;
  requireValue(!path.includes('%') && !path.includes('/.') && path.startsWith('/api/approval/'));
  const page = ['/api/approval/tasks/pending', '/api/approval/instances/started', '/api/approval/tasks/processed'].includes(path);
  if (method === 'GET' && page) {
    if (query !== undefined) {
      requireValue(query.length > 0 && !/%(?![0-9a-f]{2})/iu.test(query)
        && query.split('&').every(part => part.includes('=')));
      const params = new URLSearchParams(query); const seen = new Set();
      for (const [name, value] of params) {
        requireValue(!seen.has(name)); seen.add(name);
        if (name === 'limit' || name === 'offset') {
          requireValue(/^(?:0|[1-9][0-9]{0,2})$/u.test(value)
            && Number(value) <= (name === 'limit' ? 20 : 100)
            && (name !== 'limit' || Number(value) >= 1));
        } else requireValue(name === 'keyword' && value.length <= 80 && !/[\x00-\x1f\x7f\ufffd]/u.test(value));
      }
    }
    return Object.freeze({ name: 'page', write: false });
  }
  if (method === 'GET' && path === '/api/approval/tasks') {
    requireValue(typeof query === 'string' && new RegExp(`^instanceId=${uuid}$`, 'u').test(query));
    return Object.freeze({ name: 'tasks', write: false });
  }
  requireValue(query === undefined);
  if (method === 'GET' && new RegExp(`^/api/approval/(?:instances/${uuid}(?:/timeline|/form-snapshot)?|tasks/pending/${uuid}|tasks/${uuid}/form-runtime|attachments/${uuid}(?:/content)?)$`, 'u').test(path)) {
    return Object.freeze({ name: path.includes('/attachments/') ? 'attachment-read' : 'read', write: false });
  }
  if (method === 'GET' && /^\/api\/approval\/(?:forms\/purchase-payment\/versions\/1(?:\/runtime)?|ui-schemas\/forms\/purchase-payment\/versions\/1\/latest)$/u.test(path)) {
    return Object.freeze({ name: 'form', write: false });
  }
  if (method === 'POST' && new RegExp(`^/api/approval/tasks/${uuid}/approve$`, 'u').test(path)) return Object.freeze({ name: 'approve', write: true });
  if (method === 'POST' && path === '/api/approval/forms/purchase-payment/versions/1/submissions') return Object.freeze({ name: 'form-start', write: true });
  if (method === 'POST' && path === '/api/approval/attachments') return Object.freeze({ name: 'upload', write: true });
  throw new Error('EVALUATION_BUSINESS_ROUTE_REJECTED');
}

function exact(value, keys) {
  requireValue(value && Object.getPrototypeOf(value) === Object.prototype
    && Object.keys(value).sort().join(',') === [...keys].sort().join(','));
}
function checkJson(route, body, scenario, actorId) {
  requireValue(body.length <= 65_536);
  let data = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(body));
  if (route.name === 'form-start') {
    exact(data, ['businessKey', 'values', 'startParameters']);
    exact(data.values, ['amount', 'supplier', 'purchaseOrderReference', 'attachments']);
    data = { businessKey: data.businessKey, amount: data.values.amount, supplier: data.values.supplier,
      purchaseOrderReference: data.values.purchaseOrderReference, attachmentIds: data.values.attachments, assigneeRules: data.startParameters };
  }
  if (route.name === 'approve') {
    exact(data, ['comment']); requireValue(data.comment === null || typeof data.comment === 'string' && data.comment.length <= 1000);
  } else {
    exact(data, ['businessKey', 'amount', 'supplier', 'purchaseOrderReference', 'attachmentIds', 'assigneeRules']);
    requireValue(actorId === scenario.assigneeRules.initiatorUserId.value);
    for (const name of ['businessKey', 'purchaseOrderReference']) requireValue(typeof data[name] === 'string'
      && /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/u.test(data[name]));
    requireValue(typeof data.amount === 'number' && Number.isFinite(data.amount)
      && data.amount === Number(scenario.request.amount)
      && data.supplier === scenario.request.supplier);
    requireValue(Array.isArray(data.attachmentIds) && data.attachmentIds.length >= 1 && data.attachmentIds.length <= 4
      && new Set(data.attachmentIds).size === data.attachmentIds.length
      && data.attachmentIds.every(value => typeof value === 'string' && new RegExp(`^${uuid}$`, 'u').test(value)));
    const expected = scenario.assigneeRules;
    exact(data.assigneeRules, Object.keys(expected));
    for (const [key, value] of Object.entries(expected)) {
      if (key === 'initiatorUserId') {
        exact(data.assigneeRules[key], Object.keys(value));
        for (const [part, literal] of Object.entries(value)) requireValue(data.assigneeRules[key][part] === literal);
      } else requireValue(data.assigneeRules[key] === value);
    }
  }
}

export function evaluationUploadDigest(fileName, contentType, bytes) {
  requireValue(typeof fileName === 'string' && /^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/u.test(fileName)
    && !fileName.includes('..') && Buffer.isBuffer(bytes) && bytes.length > 0 && bytes.length <= maximumBusinessBody - 4096);
  const types = { 'application/pdf': /\.pdf$/iu, 'image/png': /\.png$/iu, 'image/jpeg': /\.jpe?g$/iu, 'text/plain': /\.txt$/iu };
  requireValue(Object.hasOwn(types, contentType) && types[contentType].test(fileName));
  if (contentType === 'application/pdf') requireValue(bytes.subarray(0, 5).toString('ascii') === '%PDF-');
  if (contentType === 'image/png') requireValue(bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])));
  if (contentType === 'image/jpeg') requireValue(bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255);
  if (contentType === 'text/plain') new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  return sha(['AP-EVALUATION-UPLOAD-V1', fileName, contentType, sha(bytes)].join('\n'));
}

/** Browser input contains no internal role/slot/destination. Tenant and actor come from the controller. */
export async function normalizeEvaluationBusinessRequest(request, scenario, actorId) {
  exact(request, ['method', 'target', 'contentType', 'idempotencyKey', 'body']);
  const route = evaluationBusinessRoute(request.method, request.target);
  requireValue(Buffer.isBuffer(request.body) && request.body.length <= maximumBusinessBody
    && typeof request.contentType === 'string' && request.contentType.length <= 160
    && !/[\r\n]/u.test(request.contentType));
  const body = Buffer.from(request.body);
  if (!route.write) {
    requireValue(body.length === 0 && request.contentType === '' && request.idempotencyKey === '');
    return { ...request, body, bodySha256: sha(body), ticketContentType: '', route };
  }
  requireValue(identity.test(request.idempotencyKey || ''));
  let bodySha256;
  let ticketContentType;
  if (route.name === 'upload') {
    requireValue(actorId === scenario.assigneeRules.initiatorUserId.value
      && /^multipart\/form-data;\s*boundary=(?:[A-Za-z0-9'()+_,.\/:=?-]{1,70}|"[A-Za-z0-9'()+_,.\/:=?-]{1,70}")$/u.test(request.contentType));
    const form = await new Response(body, { headers: { 'Content-Type': request.contentType } }).formData();
    const entries = [...form];
    requireValue(entries.length === 1 && entries[0][0] === 'file' && entries[0][1] instanceof File);
    const file = entries[0][1];
    bodySha256 = evaluationUploadDigest(file.name, file.type, Buffer.from(await file.arrayBuffer()));
    ticketContentType = 'multipart/form-data';
  } else {
    requireValue(/^application\/json(?:;\s*charset=utf-8)?$/iu.test(request.contentType));
    checkJson(route, body, scenario, actorId);
    bodySha256 = sha(body); ticketContentType = 'application/json';
  }
  return { ...request, body, bodySha256, ticketContentType, route };
}

export function createEvaluationBusinessSigner(scenario, generation, clock = Date.now) {
  const actors = evaluationActors(scenario);
  requireValue(actors.tenantId === 'demo-purchase-payment' && actors.actors.length === 5
    && /^[0-9a-f]{32}$/u.test(generation || '') && !/^0+$/u.test(generation));
  const keys = generateKeyPairSync('ed25519'); let retired = false; let last = -1;
  return Object.freeze({
    publicKeyBase64: keys.publicKey.export({ format: 'der', type: 'spki' }).toString('base64'),
    issue(actorId, request, requestId) {
      requireValue(!retired && actors.actors.some(actor => actor.id === actorId));
      evaluationBusinessRoute(request.method, request.target);
      requireValue(identity.test(requestId || '') && /^[0-9a-f]{64}$/u.test(request.bodySha256 || '')
        && ['', 'application/json', 'multipart/form-data'].includes(request.ticketContentType)
        && (request.idempotencyKey === '' || identity.test(request.idempotencyKey || '')));
      let time;
      try { time = clock(); } catch { retired = true; throw new Error('EVALUATION_CLOCK_REJECTED'); }
      if (!Number.isSafeInteger(time) || time <= 0 || time < last || time > 999_999_999_989_999) {
        retired = true; throw new Error('EVALUATION_CLOCK_REJECTED');
      }
      last = time;
      const bytes = Buffer.from(['AP-EVALUATION-BUSINESS-V1', request.method, request.target, generation,
        actorId, String(time), String(time + 10_000), randomBytes(32).toString('hex'),
        request.ticketContentType || '-', request.bodySha256, request.idempotencyKey || '-', requestId].join('\n'), 'ascii');
      return `${bytes.toString('base64url')}.${sign(null, bytes, keys.privateKey).toString('base64url')}`;
    },
    disable() { retired = true; },
  });
}
