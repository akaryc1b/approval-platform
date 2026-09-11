import { spawn } from 'node:child_process';
import { createHash, X509Certificate } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { performance } from 'node:perf_hooks';
import { evaluationApplicationPaths } from './evaluation-applications.mjs';
import { evaluationCookie } from './evaluation-http.mjs';

const pause = ms => new Promise(done => setTimeout(done, ms));
const required = (value, code) => { if (!value) throw new Error(code); };
const visible = "e => !!e && e.getClientRects().length > 0 && getComputedStyle(e).visibility !== 'hidden' && !e.disabled && !e.classList.contains('is-disabled') && e.getAttribute('aria-disabled') !== 'true'";

/** One owned Chromium connection; protocol replies never become retained evidence. */
export class EvaluationCdp {
  constructor(socket, signal) {
    this.socket = socket; this.sequence = 0; this.pending = new Map(); this.listeners = new Set(); this.closed = false;
    socket.addEventListener('message', event => {
      let message; try { message = JSON.parse(String(event.data)); } catch { this.close(); return; }
      if (message.id) {
        const item = this.pending.get(message.id); if (!item) return;
        this.pending.delete(message.id); clearTimeout(item.timer);
        if (message.error) item.reject(new Error('BROWSER_PROTOCOL_REJECTED')); else item.resolve(message.result);
      } else {
        try { for (const listener of this.listeners) listener(message); }
        catch { this.close(); }
      }
    });
    socket.addEventListener('close', () => this.close());
    this.abort = () => this.close(); this.signal = signal;
    signal?.addEventListener('abort', this.abort, { once: true });
    if (signal?.aborted) this.close();
  }
  send(method, params = {}, sessionId) {
    if (this.closed || this.signal?.aborted) return Promise.reject(new Error('BROWSER_PROTOCOL_CLOSED'));
    const id = ++this.sequence;
    return new Promise((done, reject) => {
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error('BROWSER_PROTOCOL_TIMEOUT')); }, 15000);
      this.pending.set(id, { resolve: done, reject, timer });
      try { this.socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) })); }
      catch { clearTimeout(timer); this.pending.delete(id); reject(new Error('BROWSER_PROTOCOL_CLOSED')); }
    });
  }
  close() {
    if (this.closed) return; this.closed = true;
    this.signal?.removeEventListener('abort', this.abort);
    for (const item of this.pending.values()) { clearTimeout(item.timer); item.reject(new Error('BROWSER_PROTOCOL_CLOSED')); }
    this.pending.clear(); this.listeners.clear(); this.socket.close();
  }
}

/** Two real incognito contexts and existing PC/H5 screens. No intercepted network or mock backend. */
export async function createEvaluationBrowserActions({ origin, cert, privateDirectory, directory, scenario, signal }) {
  const url = new URL(origin);
  required(url.hostname === 'localhost' && url.protocol === 'https:' && url.origin === origin, 'BROWSER_LOCAL_ORIGIN_REQUIRED');
  const executable = [process.env.APPROVAL_DEMO_CHROME_PATH, '/usr/bin/google-chrome',
    '/usr/bin/google-chrome-stable', '/usr/bin/chromium', '/usr/bin/chromium-browser'].filter(Boolean).find(path => existsSync(path));
  required(executable, 'SYSTEM_BROWSER_UNAVAILABLE');
  const profile = resolve(privateDirectory, 'chrome'); mkdirSync(profile, { mode: 0o700 });
  const spki = createHash('sha256').update(new X509Certificate(cert).publicKey.export({ type: 'spki', format: 'der' })).digest('base64');
  // Trust only this ephemeral certificate's key, not arbitrary TLS errors. Keep the browser sandbox enabled.
  const child = spawn(executable, ['--headless=new', '--remote-debugging-address=127.0.0.1', '--remote-debugging-port=0',
    '--user-data-dir=' + profile, '--no-first-run', '--disable-gpu', '--disable-background-networking', '--disable-sync',
    '--ignore-certificate-errors-spki-list=' + spki, 'about:blank'], { stdio: 'ignore', detached: true, shell: false });
  let spawnFailed = false; child.on('error', () => { spawnFailed = true; });
  let cdp; let cleanupWork; let stage = 'LAUNCH'; const sessions = [];
  const evidence = { schemaVersion: 1, kind: 'EXISTING_PC_H5_BROWSER_INTERACTION', driver: 'SYSTEM_CHROMIUM_CDP',
    contexts: 0, stages: [], screenshots: [], network: [], requestInterception: false, credentialsRetained: false };
  const save = () => writeFileSync(resolve(directory, 'evaluation-browser-trace.json'), JSON.stringify(evidence, null, 2), { mode: 0o600 });
  const record = item => { required(evidence.network.length < 10000, 'BROWSER_TRACE_LIMIT'); evidence.network.push(item); };
  async function wait(predicate, code, timeoutMs = 25000) {
    const start = performance.now();
    while (performance.now() - start < timeoutMs) {
      required(!signal?.aborted && !spawnFailed && child.exitCode === null && child.signalCode === null, 'BROWSER_STOPPED');
      try { if (await predicate()) return; }
      catch (error) { if (!['BROWSER_PAGE_EVALUATION_FAILED', 'BROWSER_PROTOCOL_REJECTED'].includes(error.message)) throw error; }
      await pause(100);
    }
    throw new Error(code);
  }
  async function evaluate(session, expression) {
    const result = await cdp.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true }, session.id);
    required(!result.exceptionDetails, 'BROWSER_PAGE_EVALUATION_FAILED'); return result.result.value;
  }
  async function click(session, expression) {
    let point;
    await wait(async () => {
      point = await evaluate(session, `(()=>{const e=${expression};if(!(${visible})(e))return null;e.scrollIntoView({block:'center',inline:'nearest'});const r=e.getBoundingClientRect();const x=r.left+r.width/2,y=r.top+r.height/2;const top=document.elementFromPoint(x,y);return top&&(e===top||e.contains(top))?{x,y}:null;})()`);
      return Boolean(point);
    }, 'BROWSER_CONTROL_NOT_READY');
    await cdp.send('Input.dispatchMouseEvent', { type: 'mousePressed', ...point, button: 'left', clickCount: 1 }, session.id);
    await cdp.send('Input.dispatchMouseEvent', { type: 'mouseReleased', ...point, button: 'left', clickCount: 1 }, session.id);
  }
  async function fill(session, expression, value) {
    await wait(() => evaluate(session, `(()=>{const e=${expression};if(!(${visible})(e))return false;e.focus();e.select();return true;})()`), 'BROWSER_INPUT_NOT_READY');
    await cdp.send('Input.insertText', { text: value }, session.id);
  }
  const button = (root, text) => `Array.from((${root}).querySelectorAll('button,.wd-button,uni-button,.uni-modal__btn_primary')).find(e=>(${visible})(e)&&e.textContent.trim()===${JSON.stringify(text)})`;
  async function navigate(session, path) {
    required(path === '/evaluation' || Object.values(evaluationApplicationPaths).includes(path), 'BROWSER_NAVIGATION_REJECTED');
    const mobile = path.startsWith('/evaluation/h5/');
    await cdp.send('Emulation.setDeviceMetricsOverride', { width: mobile ? 390 : 1280, height: mobile ? 844 : 900, deviceScaleFactor: 1, mobile }, session.id);
    const result = await cdp.send('Page.navigate', { url: origin + path }, session.id);
    required(!result.errorText, 'BROWSER_NAVIGATION_FAILED');
    await wait(() => evaluate(session, `document.readyState==='complete' && location.pathname===${JSON.stringify(path.split('#')[0])}`), 'BROWSER_DOCUMENT_NOT_READY');
  }
  async function response(session, from, method, path, status) {
    let found;
    await wait(() => { found = session.responses.slice(from).find(item => item.method === method && item.path === path && item.ready); return Boolean(found); },
      'BROWSER_RESPONSE_MISSING', path.endsWith('/end') ? 65000 : 25000);
    if (found.status !== status) evidence.failureResponse = { method, path, status: found.status, expected: status };
    required(found.status === status, 'BROWSER_BUSINESS_RESPONSE_REJECTED'); return found.data;
  }
  async function refresh(session) {
    const cookies = await cdp.send('Storage.getCookies', { browserContextId: session.context });
    const tokens = cookies.cookies.filter(item => item.name === evaluationCookie);
    required(tokens.length === 1 && tokens[0].secure && tokens[0].httpOnly && tokens[0].sameSite === 'Strict', 'BROWSER_SESSION_COOKIE_REQUIRED');
    session.cookie = evaluationCookie + '=' + tokens[0].value;
    const latest = [...session.responses].reverse().find(item => item.ready && item.data?.csrfToken && item.status < 300);
    required(latest, 'BROWSER_SESSION_VIEW_REQUIRED'); session.view = latest.data; return session;
  }
  async function screenshot(session, name) {
    required(/^[A-Za-z0-9_-]{1,120}$/u.test(name), 'BROWSER_SCREENSHOT_NAME');
    await evaluate(session, "(()=>{const e=document.querySelector('#invitation');if(e)e.value='';})()");
    const image = await cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false }, session.id);
    const bytes = Buffer.from(image.data, 'base64'); required(bytes.length <= 8 * 1024 * 1024, 'BROWSER_SCREENSHOT_LIMIT');
    const file = 'evaluation-browser-' + name + '.png'; writeFileSync(resolve(directory, file), bytes, { mode: 0o600 });
    evidence.screenshots.push(file); save();
  }
  function dispose() {
    if (cleanupWork) return cleanupWork;
    cleanupWork = (async () => {
      try { if (cdp && !cdp.closed) await cdp.send('Browser.close'); } catch { /* Stop the owned process below. */ }
      cdp?.close();
      const terminate = signum => { try { if (child.pid && child.exitCode === null && child.signalCode === null) process.kill(-child.pid, signum); }
        catch (error) { if (error.code !== 'ESRCH') throw error; } };
      terminate('SIGTERM'); let end = performance.now() + 5000;
      while (child.exitCode === null && child.signalCode === null && !spawnFailed && performance.now() < end) await pause(50);
      if (child.exitCode === null && child.signalCode === null && !spawnFailed) terminate('SIGKILL');
      end = performance.now() + 3000;
      while (child.exitCode === null && child.signalCode === null && !spawnFailed && performance.now() < end) await pause(50);
      const status = child.exitCode !== null || child.signalCode !== null || spawnFailed ? 'PASSED' : 'FAILED';
      evidence.cleanup = status; save(); return { status };
    })();
    return cleanupWork;
  }
  try {
    await wait(() => existsSync(resolve(profile, 'DevToolsActivePort')), 'BROWSER_DEBUG_SOCKET_UNAVAILABLE');
    const [port, path] = readFileSync(resolve(profile, 'DevToolsActivePort'), 'utf8').trim().split('\n');
    required(/^[0-9]{1,5}$/u.test(port) && Number(port) > 0 && Number(port) <= 65535 && /^\/devtools\/browser\/[A-Za-z0-9-]+$/u.test(path), 'BROWSER_DEBUG_ENDPOINT_INVALID');
    const socket = new WebSocket(`ws://127.0.0.1:${port}${path}`);
    await new Promise((done, fail) => {
      const timer = setTimeout(() => { socket.close(); fail(new Error('BROWSER_SOCKET_TIMEOUT')); }, 10000);
      socket.addEventListener('open', () => { clearTimeout(timer); done(); }, { once: true });
      socket.addEventListener('error', () => { clearTimeout(timer); fail(new Error('BROWSER_SOCKET_FAILED')); }, { once: true });
    });
    cdp = new EvaluationCdp(socket, signal);
    for (const label of ['A', 'B']) {
      const { browserContextId } = await cdp.send('Target.createBrowserContext');
      const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank', browserContextId });
      const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true });
      const session = { id: sessionId, context: browserContextId, label, responses: [], requests: new Map(), chooser: null };
      sessions.push(session);
      cdp.listeners.add(message => {
        if (message.sessionId !== sessionId) return;
        const params = message.params || {};
        if (message.method === 'Page.fileChooserOpened') session.chooser = params.backendNodeId;
        if (message.method === 'Network.requestWillBeSent') {
          const target = new URL(params.request.url);
          if (target.origin === origin) session.requests.set(params.requestId, { method: params.request.method, path: target.pathname + target.search });
        }
        if (message.method === 'Network.responseReceived') {
          const request = session.requests.get(params.requestId); if (!request) return;
          record({ context: label, method: request.method, path: request.path, status: params.response.status });
          if (!request.path.startsWith('/api/') && !request.path.startsWith('/evaluation/session') && !request.path.includes('/invitations/')) return;
          const item = { ...request, status: params.response.status, ready: false, data: null };
          session.responses.push(item); session.requests.set(params.requestId, item);
        }
        if (message.method === 'Network.loadingFinished') {
          const item = session.requests.get(params.requestId); session.requests.delete(params.requestId);
          if (!item || !Object.hasOwn(item, 'ready')) return;
          void cdp.send('Network.getResponseBody', { requestId: params.requestId }, sessionId).then(value => {
            const bytes = Buffer.from(value.body, value.base64Encoded ? 'base64' : 'utf8');
            if (bytes.length && bytes.length <= 2 * 1024 * 1024) { try { item.data = JSON.parse(bytes.toString('utf8')); } catch { /* Binary attachment, not a JSON record. */ } }
            item.ready = true;
          }, () => { item.ready = true; });
        }
        if (message.method === 'Network.loadingFailed') {
          const item = session.requests.get(params.requestId); session.requests.delete(params.requestId);
          if (item) record({ context: label, method: item.method, path: item.path, status: 'NETWORK_FAILED' });
        }
      });
      await cdp.send('Page.enable', {}, sessionId); await cdp.send('Runtime.enable', {}, sessionId); await cdp.send('Network.enable', {}, sessionId);
      await cdp.send('Page.setInterceptFileChooserDialog', { enabled: true }, sessionId);
    }
    evidence.contexts = 2; save();
  } catch (error) {
    evidence.failure = { stage, code: /^[A-Z_]+$/u.test(error.message) ? error.message : 'BROWSER_INITIALIZATION_FAILED' };
    await dispose(); throw new Error('EVALUATION_BROWSER_INITIALIZATION_FAILED');
  }
  const completed = (session, action, more = {}) => { evidence.stages.push({ context: session.label, action, ...more }); save(); };
  return {
    async enter(invitation) {
      stage = 'INVITATION_LOGIN'; const session = sessions.find(item => !item.cookie);
      required(session, 'BROWSER_CONTEXT_LIMIT'); await navigate(session, '/evaluation');
      await fill(session, "document.querySelector('#invitation')", invitation);
      const from = session.responses.length; await click(session, "document.querySelector('#login button')");
      await response(session, from, 'POST', '/evaluation/invitations/redeem', 201);
      await click(session, "document.querySelector('#applications')");
      await wait(() => evaluate(session, "document.querySelector('#application-links')?.hidden === false"), 'APPLICATION_NAVIGATION_NOT_READY');
      await screenshot(session, session.label + '-invited'); completed(session, stage); return refresh(session);
    },
    async actor(session, actorId) {
      stage = 'ROLE_SWITCH'; await navigate(session, '/evaluation');
      await wait(() => evaluate(session, "!!document.querySelector('#actor') && !document.querySelector('#actor').disabled"), 'ROLE_CONTROL_NOT_READY');
      const from = session.responses.length;
      await evaluate(session, `(()=>{const e=document.querySelector('#actor');e.value=${JSON.stringify(actorId)};e.dispatchEvent(new Event('change',{bubbles:true}));})()`);
      await response(session, from, 'POST', '/evaluation/session/actor', 200); await refresh(session);
    },
    async uploadAndStart(session, label, content, businessKey, purchaseOrderReference) {
      stage = 'H5_UPLOAD_AND_INITIATE'; await navigate(session, evaluationApplicationPaths.purchase);
      await fill(session, "document.querySelector('.business-card input')", businessKey);
      let form;
      await wait(() => { form = [...session.responses].reverse().find(item => item.path === '/api/approval/forms/purchase-payment/versions/1/runtime' && item.ready && item.status === 200); return Boolean(form); }, 'FORM_RUNTIME_RESPONSE_REQUIRED');
      const field = key => {
        const label = form.data.definition.fields.find(item => item.key === key)?.label;
        required(typeof label === 'string' && label.length <= 200, 'FORM_FIELD_LABEL_REQUIRED');
        return `Array.from(document.querySelectorAll('.field-card')).find(e=>e.querySelector('.field-label')?.textContent.includes(${JSON.stringify(label)}))?.querySelector('input,textarea')`;
      };
      await fill(session, field('amount'), String(scenario.request.amount)); await fill(session, field('supplier'), scenario.request.supplier);
      await fill(session, field('purchaseOrderReference'), purchaseOrderReference);
      const fromUpload = session.responses.length; session.chooser = null;
      await click(session, button('document', '选择并上传文件')); await wait(() => Boolean(session.chooser), 'BROWSER_FILE_PICKER_MISSING');
      const file = resolve(privateDirectory, `evaluation-${label}.txt`); writeFileSync(file, content, { mode: 0o600 });
      await cdp.send('DOM.setFileInputFiles', { files: [file], backendNodeId: session.chooser }, session.id);
      const attachment = await response(session, fromUpload, 'POST', '/api/approval/attachments', 200);
      const fromStart = session.responses.length; await click(session, button("document.querySelector('.action-bar')", '发起审批'));
      await click(session, "document.querySelector('uni-modal .uni-modal__btn_primary')");
      const started = await response(session, fromStart, 'POST', '/api/approval/forms/purchase-payment/versions/1/submissions', 200);
      await click(session, "document.querySelector('uni-modal .uni-modal__btn_primary')");
      await screenshot(session, label + '-purchase'); completed(session, stage, { instanceId: started.instanceId, attachmentId: attachment.attachmentId });
      return { attachment, started };
    },
    async approve(session, instance, step, taskId) {
      stage = step.client === 'pc' ? 'PC_MANAGER_APPROVAL' : 'H5_APPROVAL'; const pc = step.client === 'pc';
      await navigate(session, pc ? evaluationApplicationPaths.pc : evaluationApplicationPaths.h5);
      if (pc) await click(session, button(`Array.from(document.querySelectorAll('.task-item')).find(e=>e.textContent.includes(${JSON.stringify(instance.businessKey)}))`, '处理'));
      else await click(session, `Array.from(document.querySelectorAll('.task-card')).find(e=>e.textContent.includes(${JSON.stringify(instance.businessKey)}))`);
      const from = session.responses.length;
      if (pc) { await click(session, button("document.querySelector('.el-drawer')", '同意')); await click(session, button("document.querySelector('.el-message-box')", '确认同意')); }
      else { await click(session, button("document.querySelector('.action-bar')", '同意')); await click(session, "document.querySelector('uni-modal .uni-modal__btn_primary')"); }
      const result = await response(session, from, 'POST', `/api/approval/tasks/${taskId}/approve`, 200);
      await screenshot(session, session.label + '-' + step.taskDefinitionKey + '-' + session.view.actorId);
      completed(session, stage, { taskId, instanceId: instance.instanceId }); return result;
    },
    async probeCross(session, other, taskId) {
      // These are negative access probes only; successful business operations above use page controls.
      stage = 'CROSS_CONTEXT_ACCESS_DENIED';
      const targets = taskId ? [[`/api/approval/tasks/${taskId}/approve`, 'POST']]
        : [[`/api/approval/instances/${other.instanceId}`, 'GET'], [`/api/approval/attachments/${other.attachmentId}/content`, 'GET']];
      for (const [path, method] of targets) {
        const status = await evaluate(session, `(async()=>{const r=await fetch(${JSON.stringify(path)}, {method:${JSON.stringify(method)},credentials:'same-origin',redirect:'error',cache:'no-store',headers:{'X-Evaluation-CSRF':${JSON.stringify(session.view.csrfToken)},${method === 'POST' ? "'Content-Type':'application/json','Idempotency-Key':'browser-cross-denial'," : ''}},${method === 'POST' ? "body:JSON.stringify({comment:null})," : ''}});return r.status;})()`);
        required((method === 'GET' ? [404] : [400,403,404,409]).includes(status), 'BROWSER_CROSS_CONTEXT_ACCESS_ALLOWED');
      }
      completed(session, stage, { operations: targets.length });
    },
    async verifyExpired(session) {
      stage = 'EXPIRED_BROWSER_REVOKED'; const result = await cdp.send('Page.navigate', { url: origin + evaluationApplicationPaths.h5 }, session.id);
      required(!result.errorText, 'BROWSER_NAVIGATION_FAILED');
      await wait(() => evaluate(session, "location.pathname==='/evaluation' && !!document.querySelector('#login') && !document.querySelector('#login').hidden"), 'EXPIRED_BROWSER_STILL_AUTHORIZED');
      await screenshot(session, session.label + '-expired'); completed(session, stage);
    },
    async end(session) {
      stage = 'RESET_SELECTED_EVALUATOR'; await navigate(session, '/evaluation');
      const from = session.responses.length; await click(session, "document.querySelector('#end')");
      await response(session, from, 'POST', '/evaluation/session/end', 204); session.cookie = ''; session.view = null;
      await screenshot(session, session.label + '-reset'); completed(session, stage);
    },
    async failure() {
      evidence.failure = { stage };
      for (const session of sessions) { try { await screenshot(session, session.label + '-failure'); } catch { /* The protocol may already be closed. */ } }
      save();
    },
    dispose,
  };
}
