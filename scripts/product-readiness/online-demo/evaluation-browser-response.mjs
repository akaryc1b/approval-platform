// CDP observation only: never issues, retries, intercepts or substitutes a browser request.
const maximumBytes = 2 * 1024 * 1024;
const networkReasons = new Set(['net::ERR_ABORTED', 'net::ERR_FAILED', 'net::ERR_TIMED_OUT',
  'net::ERR_CONNECTION_CLOSED', 'net::ERR_CONNECTION_RESET', 'net::ERR_CONNECTION_REFUSED',
  'net::ERR_CONNECTION_ABORTED', 'net::ERR_NAME_NOT_RESOLVED', 'net::ERR_INTERNET_DISCONNECTED',
  'net::ERR_CONTENT_LENGTH_MISMATCH', 'net::ERR_INCOMPLETE_CHUNKED_ENCODING',
  'net::ERR_BLOCKED_BY_CLIENT', 'net::ERR_BLOCKED_BY_ADMINISTRATOR', 'net::ERR_BLOCKED_BY_RESPONSE']);
const failureCodes = new Set(['BROWSER_STOPPED', 'BROWSER_PROTOCOL_CLOSED', 'BROWSER_PROTOCOL_TIMEOUT',
  'BROWSER_RESPONSE_LOAD_FAILED', 'BROWSER_RESPONSE_BODY_UNAVAILABLE', 'BROWSER_RESPONSE_BODY_INVALID',
  'BROWSER_RESPONSE_LIMIT', 'BROWSER_BUSINESS_RESPONSE_REJECTED', 'BROWSER_RESPONSE_MISSING',
  'BROWSER_INPUT_NOT_READY', 'BROWSER_CONTROL_NOT_READY', 'BROWSER_DOCUMENT_NOT_READY',
  'BROWSER_FILE_PICKER_MISSING', 'FORM_RUNTIME_RESPONSE_REQUIRED', 'ROLE_CONTROL_NOT_READY',
  'APPLICATION_NAVIGATION_NOT_READY', 'EXPIRED_BROWSER_STILL_AUTHORIZED', 'BROWSER_STAGE_FAILED']);
export const evaluationBrowserFailureCode = code => failureCodes.has(code) ? code : 'BROWSER_STAGE_FAILED';

function failed(item, code) {
  item.failure = code; item.data = null; item.ready = true;
}

/** A 200 response header followed by loadingFailed is terminal, never a passing JSON body. */
export function failEvaluationBrowserResponse(item, event = {}) {
  failed(item, 'BROWSER_RESPONSE_LOAD_FAILED');
  return { status: 'NETWORK_FAILED', canceled: event.canceled === true,
    reason: networkReasons.has(event.errorText) ? event.errorText : 'UNCLASSIFIED_NETWORK_ERROR' };
}

/** Complete one observed response without retaining protocol payloads in the evidence trace. */
export function finishEvaluationBrowserResponse(item, value) {
  // A late protocol reply must never resurrect a response already rejected by loadingFailed.
  if (item.failure || item.ready) return;
  if (!value || typeof value.body !== 'string' || typeof value.base64Encoded !== 'boolean') {
    failed(item, 'BROWSER_RESPONSE_BODY_UNAVAILABLE'); return;
  }
  if (value.body.length > maximumBytes * 2) { failed(item, 'BROWSER_RESPONSE_LIMIT'); return; }
  const bytes = Buffer.from(value.body, value.base64Encoded ? 'base64' : 'utf8');
  if (bytes.length > maximumBytes) { failed(item, 'BROWSER_RESPONSE_LIMIT'); return; }
  if (value.base64Encoded && bytes.toString('base64') !== value.body) {
    failed(item, 'BROWSER_RESPONSE_BODY_INVALID'); return;
  }
  if (item.status === 204) {
    if (bytes.length) { failed(item, 'BROWSER_RESPONSE_BODY_INVALID'); return; }
    item.data = null; item.ready = true; return;
  }
  // Attachment content is inspected by separate read-only byte/hash checks, not JSON assertions.
  if (/^\/api\/approval\/attachments\/[0-9a-f-]{36}\/content$/u.test(item.path)) {
    item.data = null; item.ready = true; return;
  }
  try { item.data = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)); }
  catch { failed(item, 'BROWSER_RESPONSE_BODY_INVALID'); return; }
  if (!item.data || typeof item.data !== 'object') { failed(item, 'BROWSER_RESPONSE_BODY_INVALID'); return; }
  item.ready = true;
}

export function evaluationBrowserResponseReady(item, expectedStatus) {
  if (!item || !item.ready) return false;
  if (item.failure) throw new Error(evaluationBrowserFailureCode(item.failure));
  if (item.status !== expectedStatus) throw new Error('BROWSER_BUSINESS_RESPONSE_REJECTED');
  return true;
}
