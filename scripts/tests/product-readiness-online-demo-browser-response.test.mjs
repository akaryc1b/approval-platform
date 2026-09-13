import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { failEvaluationBrowserResponse, finishEvaluationBrowserResponse,
  evaluationBrowserFailureCode, evaluationBrowserResponseReady }
  from '../product-readiness/online-demo/evaluation-browser-response.mjs';

const item = (status = 200, path = '/api/approval/forms/purchase-payment/versions/1/runtime') =>
  ({ status, path, ready: false, data: null });
const body = value => ({ body: JSON.stringify(value), base64Encoded: false });

test('HTTP 200 followed by loadingFailed cannot leave form observation pending or passing', () => {
  const form = item(); const other = item();
  assert.equal(evaluationBrowserResponseReady(form, 200), false);
  const safe = failEvaluationBrowserResponse(form, { canceled: true, errorText: 'net::ERR_ABORTED',
    requestId: 'private-request', headers: { Cookie: 'private-cookie' } });
  assert.equal(form.ready, true);
  assert.deepEqual(safe, { status: 'NETWORK_FAILED', canceled: true, reason: 'net::ERR_ABORTED' });
  assert.throws(() => evaluationBrowserResponseReady(form, 200), /BROWSER_RESPONSE_LOAD_FAILED/u);
  assert.deepEqual(other, item());
});
test('a late body cannot resurrect a failed observation', () => {
  const form = item(); failEvaluationBrowserResponse(form, {});
  finishEvaluationBrowserResponse(form, body({ definition: { fields: [] } }));
  assert.equal(form.data, null);
  assert.throws(() => evaluationBrowserResponseReady(form, 200), /BROWSER_RESPONSE_LOAD_FAILED/u);
});
test('complete actual-shaped JSON and empty reset responses keep their original outcomes', () => {
  const created = item(201, '/evaluation/invitations/redeem');
  finishEvaluationBrowserResponse(created, body({ actorId: 'demo-employee' }));
  assert.equal(evaluationBrowserResponseReady(created, 201), true);
  assert.deepEqual(created.data, { actorId: 'demo-employee' });
  const reset = item(204, '/evaluation/session/end');
  finishEvaluationBrowserResponse(reset, { body: '', base64Encoded: false });
  assert.equal(evaluationBrowserResponseReady(reset, 204), true);
  assert.equal(reset.data, null);
});
test('protocol body retrieval failure and invalid JSON are not successful response evidence', () => {
  for (const value of [null, {}, { body: '{partial', base64Encoded: false },
    { body: 'null', base64Encoded: false }, { body: '', base64Encoded: false },
    { body: '!!!!', base64Encoded: true }]) {
    const response = item(); finishEvaluationBrowserResponse(response, value);
    assert.equal(response.ready, true); assert.equal(response.data, null);
    assert.throws(() => evaluationBrowserResponseReady(response, 200), /BROWSER_RESPONSE_BODY_(?:INVALID|UNAVAILABLE)/u);
  }
});
test('body bounds and exact base64 decoding remain enforced', () => {
  const response = item(); finishEvaluationBrowserResponse(response, { body: 'x'.repeat(2097153), base64Encoded: false });
  assert.throws(() => evaluationBrowserResponseReady(response, 200), /BROWSER_RESPONSE_LIMIT/u);
  const exact = item(); const json = JSON.stringify({ supplier: '测试' });
  finishEvaluationBrowserResponse(exact, { body: Buffer.from(json).toString('base64'), base64Encoded: true });
  assert.equal(evaluationBrowserResponseReady(exact, 200), true); assert.equal(exact.data.supplier, '测试');
});
test('binary attachment observation is not parsed as JSON and error statuses never become success', () => {
  const attachment = item(200, '/api/approval/attachments/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa/content');
  finishEvaluationBrowserResponse(attachment, { body: Buffer.from([0, 255, 1]).toString('base64'), base64Encoded: true });
  assert.equal(attachment.ready, true); assert.equal(attachment.data, null);
  const response = item(500); finishEvaluationBrowserResponse(response, body({ error: 'APPROVAL_TEMPORARILY_UNAVAILABLE' }));
  assert.throws(() => evaluationBrowserResponseReady(response, 200), /BROWSER_BUSINESS_RESPONSE_REJECTED/u);
});
test('arbitrary protocol messages and errors never become retained diagnostic text', () => {
  for (const message of ['Cookie=private-secret', 'https://outside.invalid/?token=private',
    'EVALUATION_PRIVATE_CREDENTIAL', 'net::ERR_ABORTED private-secret', undefined]) {
    const safe = failEvaluationBrowserResponse(item(), { errorText: message, canceled: 'true' });
    assert.deepEqual(safe, { status: 'NETWORK_FAILED', canceled: false, reason: 'UNCLASSIFIED_NETWORK_ERROR' });
    assert.equal(evaluationBrowserFailureCode(message), 'BROWSER_STAGE_FAILED');
  }
  assert.equal(evaluationBrowserFailureCode('BROWSER_INPUT_NOT_READY'), 'BROWSER_INPUT_NOT_READY');
});
test('existing driver waits for terminal outcomes in the current navigation and preserves failure code', () => {
  const source = readFileSync(new URL('../product-readiness/online-demo/evaluation-browser.mjs', import.meta.url), 'utf8');
  assert.match(source, /finishEvaluationBrowserResponse\(item, value\)/u);
  assert.match(source, /finishEvaluationBrowserResponse\(item, null\)/u);
  assert.match(source, /failEvaluationBrowserResponse\(item, params\)/u);
  assert.match(source, /session\.responses\.slice\(fromForm\)/u);
  assert.match(source, /evaluationBrowserResponseReady\(form, 200\)/u);
  assert.match(source, /evaluationBrowserResponseReady\(found, status\)/u);
  assert.match(source, /evaluationBrowserFailureCode\(evidence\.failure\?\.code\)/u);
  assert.doesNotMatch(source, /Fetch\.(?:enable|fulfillRequest)|Network\.replayXHR|--no-sandbox|--disable-web-security/u);
});

// Execute the real driver's terminal-event block with only the CDP transport substituted.
const driver = readFileSync(new URL('../product-readiness/online-demo/evaluation-browser.mjs', import.meta.url), 'utf8');
const eventStart = driver.indexOf("        if (message.method === 'Network.loadingFinished')");
const eventEnd = driver.indexOf('\n      });', eventStart);
assert.ok(eventStart >= 0 && eventEnd > eventStart);
const handle = new Function('message', 'params', 'session', 'cdp', 'record',
  'finishEvaluationBrowserResponse', 'failEvaluationBrowserResponse',
  "const sessionId='owned-test-session',label='A';\n" + driver.slice(eventStart, eventEnd));
function handleEvent(method, params, session, cdp, events = []) {
  handle({ method }, params, session, cdp, value => events.push(value),
    finishEvaluationBrowserResponse, failEvaluationBrowserResponse);
  return events;
}
test('actual event handler records pre-header failure and isolates other pending requests', () => {
  const request = { method: 'GET', path: '/api/approval/forms/purchase-payment/versions/1/runtime' };
  const other = item();
  const session = { requests: new Map([['request', request], ['other', other]]), responses: [] };
  const events = handleEvent('Network.loadingFailed', { requestId: 'request', errorText: 'net::ERR_CONNECTION_RESET' },
    session, { send: () => assert.fail('no body lookup for a failed request') });
  assert.equal(session.responses[0], request);
  assert.throws(() => evaluationBrowserResponseReady(request, 200), /BROWSER_RESPONSE_LOAD_FAILED/u);
  assert.equal(session.requests.get('other'), other);
  assert.equal(events[0].reason, 'net::ERR_CONNECTION_RESET');
});
test('actual event handler retains terminal failure while an earlier body lookup is outstanding', async () => {
  const request = { ...item(), method: 'GET' };
  const session = { requests: new Map([['request', request]]), responses: [request] };
  let complete;
  const cdp = { send: () => new Promise(resolve => { complete = resolve; }) };
  handleEvent('Network.loadingFinished', { requestId: 'request' }, session, cdp);
  assert.equal(session.requests.get('request'), request);
  handleEvent('Network.loadingFailed', { requestId: 'request', canceled: true, errorText: 'net::ERR_ABORTED' }, session, cdp);
  complete(body({ instanceId: 'must-not-be-accepted' }));
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(session.requests.size, 0); assert.equal(request.data, null);
  assert.throws(() => evaluationBrowserResponseReady(request, 200), /BROWSER_RESPONSE_LOAD_FAILED/u);
});
test('actual completed 204 reset does not require an unavailable Chromium response body', () => {
  const request = { ...item(204, '/evaluation/session/end'), method: 'POST' };
  const session = { requests: new Map([['request', request]]), responses: [request] };
  handleEvent('Network.loadingFinished', { requestId: 'request' }, session,
    { send: () => assert.fail('204 has no representation to fetch') });
  assert.equal(evaluationBrowserResponseReady(request, 204), true);
  assert.equal(session.requests.size, 0); assert.equal(request.data, null);
});
