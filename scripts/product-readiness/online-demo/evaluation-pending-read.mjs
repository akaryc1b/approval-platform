/** The existing PendingTaskPage shape, with bounded data and no extra transport fields. */
export function validateEvaluationPendingPage(page) {
  const reject = () => { throw new Error('EVALUATION_PENDING_PAGE_REJECTED'); };
  const plain = value => value && Object.getPrototypeOf(value) === Object.prototype;
  if (!plain(page) || Object.keys(page).sort().join(',') !== 'hasMore,items,limit,offset,total'
      || !Array.isArray(page.items) || page.items.length > 20 || page.limit !== 20 || page.offset !== 0
      || !Number.isSafeInteger(page.total) || page.total < page.items.length || page.total > 100_000
      || page.hasMore !== (page.items.length < page.total)) reject();
  const fields = ['taskId', 'instanceId', 'definitionKey', 'taskDefinitionKey', 'taskName', 'businessKey',
    'initiatorId', 'amount', 'supplier', 'purchaseOrderReference', 'taskCreatedAt', 'taskUpdatedAt'];
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u;
  const ids = new Set();
  for (const item of page.items) {
    if (!plain(item) || Object.keys(item).sort().join(',') !== [...fields].sort().join(',')) reject();
    if (!uuid.test(item.taskId || '') || !uuid.test(item.instanceId || '') || ids.has(item.taskId)) reject();
    ids.add(item.taskId);
    for (const name of fields.filter(name => !['amount', 'taskId', 'instanceId'].includes(name))) {
      if (item[name] !== null && (typeof item[name] !== 'string' || item[name].length > 512
          || /[\u0000-\u0008\u000b\u000c\u000e-\u001f]/u.test(item[name]))) reject();
    }
    if (item.amount !== null && !(typeof item.amount === 'number' && Number.isFinite(item.amount)
        && item.amount >= 0 && item.amount <= Number.MAX_SAFE_INTEGER)
        && !(typeof item.amount === 'string' && /^(?:0|[1-9][0-9]{0,14})(?:\.[0-9]{1,2})?$/u.test(item.amount))) reject();
  }
  return structuredClone(page);
}

// Runs in the exact owned backend namespace-sharing probe. No caller URL/header/role
// is accepted; the actor exists only inside the signed proof made by the adapter.
export async function fetchEvaluationPendingPage(ticket, port = 8080) {
  if (typeof ticket !== 'string' || ticket.length > 1024
      || !/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/u.test(ticket)
      || !Number.isSafeInteger(port) || port < 1 || port > 65535) {
    throw new Error('EVALUATION_PENDING_REQUEST_REJECTED');
  }
  let status = null;
  try {
    const response = await fetch(`http://127.0.0.1:${port}/api/approval/tasks/pending`, {
      method: 'GET', headers: { 'X-Evaluation-Read-Ticket': ticket },
      redirect: 'error', signal: AbortSignal.timeout(1500),
    });
    status = response.status;
    if (status !== 200) { await response.body?.cancel(); return { status, page: null, code: 'HTTP_REJECTED' }; }
    if (!/^(?:application\/json|application\/[^;]+\+json)(?:;|$)/iu.test(response.headers.get('content-type') || '')) {
      await response.body?.cancel(); return { status, page: null, code: 'CONTENT_TYPE_REJECTED' };
    }
    const parts = []; let size = 0;
    for await (const part of response.body) {
      size += part.length;
      if (size > 65_536) throw new Error('RESPONSE_LIMIT');
      parts.push(part);
    }
    const page = validateEvaluationPendingPage(JSON.parse(Buffer.concat(parts).toString('utf8')));
    return { status, page, code: 'PENDING_PAGE' };
  } catch {
    // No decoder, response, proof, private network or transport diagnostic is emitted.
    return { status, page: null, code: status === null ? 'TRANSPORT_REJECTED' : 'BODY_REJECTED' };
  }
}

export const evaluationPendingPageProgram = [
  validateEvaluationPendingPage.toString(),
  fetchEvaluationPendingPage.toString(),
  "const [ticket, rawPort = '8080'] = process.argv.slice(1);",
  'console.log(JSON.stringify(await fetchEvaluationPendingPage(ticket, Number(rawPort))));',
].join('\n');
