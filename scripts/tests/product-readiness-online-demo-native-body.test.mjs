import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
const execute = promisify(execFile);

// Child process loads the ephemeral CA before native fetch. No TLS verification is disabled.
// Real client source, HTTPS and body loaders execute; business responses are fixtures.
async function framingCases() {
  const assert = (await import('node:assert/strict')).default;
  const { readFileSync } = await import('node:fs');
  const { createServer } = await import('node:https');
  const { gzipSync } = await import('node:zlib');
  const { stripTypeScriptTypes } = await import('node:module');
  const [sourcePath, keyPath, certPath] = process.argv.slice(1);
  const source = stripTypeScriptTypes(readFileSync(sourcePath, 'utf8'));
  const { createEvaluationBrowserSession } = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
  const actors = ['demo-employee', 'demo-manager', 'demo-finance-reviewer', 'demo-finance-approver-a', 'demo-finance-approver-b'];
  const view = { actorId: actors[0], actors: actors.map(id => ({ id, displayName: id })),
    csrfToken: 'a'.repeat(43), expiresInSeconds: 300,
    scope: 'DISPOSABLE_EVALUATION_CONTROL_PLANE', businessAccess: 'PURCHASE_PAYMENT_WORKFLOW' };
  const target = '/approval/forms/purchase-payment/versions/1/runtime';
  const value = { definition: { fields: ['采购金额', '€'] } }; const text = JSON.stringify(value);
  let mode = 'fixed'; let commands = 0; let changed = false;
  const server = createServer({ key: readFileSync(keyPath), cert: readFileSync(certPath) }, (req, res) => {
    req.resume(); res.setHeader('Connection', 'close'); res.setHeader('Cache-Control', 'no-store');
    res.setHeader('Content-Type', 'application/json');
    const send = body => { res.setHeader('Content-Length', Buffer.byteLength(body)); res.end(body); };
    if (req.url === '/evaluation/session') { send(JSON.stringify({ ...view, actorId: changed ? actors[1] : actors[0] })); return; }
    commands++;
    if (mode === 'redirect') { res.writeHead(302, { Location: '/unexpected' }); res.end(); return; }
    if (mode === 'huge') { res.setHeader('Content-Length', 2_097_153); res.flushHeaders(); res.write('x'); return; }
    if (mode === 'truncated') { res.setHeader('Content-Length', Buffer.byteLength(text) + 5); res.end(text); return; }
    if (mode === 'stalled') { res.setHeader('Content-Length', 99); res.flushHeaders(); res.write('{'); return; }
    if (mode === 'compressed') { res.setHeader('Content-Encoding', 'gzip'); send(gzipSync('x'.repeat(2_097_153))); return; }
    if (mode === 'limit') { send('x'.repeat(2_097_152)); return; }
    if (mode === 'chunked') { res.writeHead(200); res.write(text.slice(0, 7)); res.end(text.slice(7)); return; }
    if (mode === 'changed') changed = true;
    const bytes = Buffer.from(text); res.setHeader('Content-Length', bytes.length);
    res.write(bytes.subarray(0, 5)); setTimeout(() => res.end(bytes.subarray(5)), 5);
  });
  await new Promise(done => server.listen(0, '127.0.0.1', done));
  const origin = 'https://127.0.0.1:' + server.address().port;
  const sessions = []; const checks = [];
  function fixture(timeout = 1000) {
    const seen = [];
    const session = createEvaluationBrowserSession({ origin, now: () => 1000, requestTimeoutMs: timeout,
      fetch: async (path, init) => {
        assert.equal(init.mode, 'same-origin'); assert.equal(init.credentials, 'same-origin');
        assert.equal(init.redirect, 'error'); assert.equal(init.cache, 'no-store');
        const response = await fetch(origin + path, init); assert.equal(response.type, 'basic');
        const observation = { path, native: 0, pipes: 0 }; seen.push(observation);
        const arrayBuffer = response.arrayBuffer.bind(response);
        response.arrayBuffer = () => { observation.native++; return arrayBuffer(); };
        const pipeTo = response.body.pipeTo.bind(response.body);
        response.body.pipeTo = (...args) => { observation.pipes++; return pipeTo(...args); };
        return response;
      },
    });
    sessions.push(session); return { session, seen };
  }
  const business = f => f.seen.filter(item => item.path !== '/evaluation/session');
  const error = code => result => result.code === code;
  try {
    let f = fixture();
    for (let n = 0; n < 12; n++) assert.deepEqual(await (await f.session.fetch(target)).json(), value);
    assert.equal(f.seen.length, 37);
    assert.ok(f.seen.every(item => item.native === 1 && item.pipes === 0), 'fixed network bodies must use the native loader');
    checks.push('repeated fixed-length UTF-8 session/business responses use native completion');
    mode = 'chunked'; f = fixture(); assert.deepEqual(await (await f.session.fetch(target)).json(), value);
    assert.equal(business(f)[0].native, 0); assert.equal(business(f)[0].pipes, 1);
    checks.push('chunked responses retain bounded incremental reading');
    mode = 'huge'; f = fixture(); await assert.rejects(f.session.fetch(target), error('EVALUATION_RESPONSE_LIMIT'));
    assert.equal(business(f)[0].native, 0); assert.equal(business(f)[0].pipes, 0);
    checks.push('oversized declared lengths reject before native allocation');
    mode = 'compressed'; f = fixture(); await assert.rejects(f.session.fetch(target), error('EVALUATION_RESPONSE_LIMIT'));
    assert.equal(business(f)[0].native, 0); assert.equal(business(f)[0].pipes, 1);
    checks.push('compressed bodies are limited by decoded bytes, not compressed length');
    mode = 'limit'; f = fixture(); assert.equal((await (await f.session.fetch(target)).text()).length, 2_097_152);
    assert.equal(business(f)[0].native, 1); checks.push('exactly-at-limit identity bodies remain accepted');
    mode = 'truncated'; f = fixture(); const before = commands;
    await assert.rejects(f.session.fetch(target), error('EVALUATION_REQUEST_UNAVAILABLE'));
    await assert.rejects(f.session.fetch(target)); assert.equal(commands, before + 1);
    checks.push('complete JSON with incomplete HTTP framing rejects without retry');
    mode = 'stalled'; f = fixture(100);
    await assert.rejects(f.session.fetch('/approval/tasks/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa/approve', {
      method: 'POST', headers: { 'Idempotency-Key': 'native-body-once' }, body: '{"comment":null}',
    }), error('EVALUATION_RESULT_UNCERTAIN'));
    const afterWrite = commands; await assert.rejects(f.session.fetch(target)); assert.equal(commands, afterWrite);
    checks.push('timeout still fences uncertain writes without retry');
    mode = 'redirect'; f = fixture(); const beforeRedirect = commands;
    await assert.rejects(f.session.fetch(target)); assert.equal(commands, beforeRedirect + 1);
    checks.push('redirects are not followed');
    mode = 'changed'; f = fixture(); await assert.rejects(f.session.fetch(target), error('EVALUATION_SESSION_CHANGED'));
    checks.push('post-response identity verification is retained');
    console.log(JSON.stringify({ checks }));
  } finally {
    sessions.forEach(session => session.dispose()); server.closeAllConnections();
    await new Promise(done => server.close(done));
  }
}
for (const client of ['mobile/overlay/src', 'web/overlay/apps/web-ele/src']) {
  test(`${client}: fixed-length native body and fallback HTTPS regressions`, { timeout: 30_000 }, async t => {
    const directory = mkdtempSync(resolve(tmpdir(), 'evaluation-native-body-'));
    const key = resolve(directory, 'key.pem'); const cert = resolve(directory, 'cert.pem');
    try {
      await execute('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
        '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost,IP:127.0.0.1', '-keyout', key, '-out', cert],
      { timeout: 10_000, maxBuffer: 16_384 });
      const source = fileURLToPath(new URL(`../../apps/${client}/platform/approval/evaluation-session.ts`, import.meta.url));
      const { stdout } = await execute(process.execPath, ['--input-type=module', '-e', `await (${framingCases.toString()})();`, source, key, cert],
        { timeout: 20_000, maxBuffer: 32_768, env: { ...process.env, NODE_EXTRA_CA_CERTS: cert } });
      const result = JSON.parse(stdout); assert.equal(result.checks.length, 9);
      result.checks.forEach(check => t.diagnostic(check));
    } finally { rmSync(directory, { recursive: true, force: true }); }
  });
}
