import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { request } from 'node:https';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { createServer } from 'node:net';
import test from 'node:test';
import { createEvaluationApplicationAssets, evaluationApplicationPaths, isEvaluationApplicationAssets } from '../product-readiness/online-demo/evaluation-applications.mjs';
import { createEvaluationHttpsServer, evaluationCookie } from '../product-readiness/online-demo/evaluation-http.mjs';
import { createEvaluationSessions } from '../product-readiness/online-demo/evaluation-sessions.mjs';
import { exportEvaluationApplications } from '../product-readiness/online-demo/evaluation-application-export.mjs';

const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const scenario = JSON.parse(readFileSync(new URL('../../config/demo/purchase-payment-golden-path.json', import.meta.url)));
function fixtures(t) {
  const directory = mkdtempSync(resolve(tmpdir(), 'evaluation-entry-test-'));
  t.after(() => rmSync(directory, { force: true, recursive: true }));
  const source = { commitSha: 'a'.repeat(40), treeSha: 'b'.repeat(40), epoch: 1700000000 }; const roots = {};
  for (const component of ['pc', 'h5']) {
    const publicDirectory = resolve(directory, component); mkdirSync(publicDirectory);
    const files = [];
    for (const [path, text] of Object.entries({ 'index.html': `<html><head><script>window.component=${JSON.stringify(component)}</script><script src="/evaluation/${component}/app.js"></script></head><body>Existing build fixture ${component}</body></html>`,
      'app.js': `console.log(${JSON.stringify(component)})`, 'app.css': 'body{font:inherit}' })) {
      const bytes = Buffer.from(text); writeFileSync(resolve(publicDirectory, path), bytes);
      files.push({ path, size: bytes.length, sha256: hash(bytes) });
    }
    const manifest = { schemaVersion: 1, kind: 'ONLINE_DEMO_STATIC_ARTIFACT_INVENTORY', component, ...source,
      evaluation: { enabled: true, basePath: `/evaluation/${component}/` }, files, inventorySha256: hash(JSON.stringify(files)), totalBytes: files.reduce((sum, f) => sum + f.size, 0) };
    const inventoryFile = resolve(directory, component + '.json'); writeFileSync(inventoryFile, JSON.stringify(manifest));
    roots[component] = { publicDirectory, inventoryFile };
  }
  return { directory, source, roots, create: () => createEvaluationApplicationAssets({ source, roots }),
    edit(component, change) { const path = roots[component].inventoryFile; const value = JSON.parse(readFileSync(path)); change(value); writeFileSync(path, JSON.stringify(value)); } };
}
test('snapshots only exact source inventories and returns independent immutable resource copies', t => {
  const f = fixtures(t); const registry = f.create(); assert.equal(isEvaluationApplicationAssets(registry), true);
  assert.equal(isEvaluationApplicationAssets({ get() {} }), false); assert.deepEqual(registry.entries, evaluationApplicationPaths);
  assert.equal(registry.has('/evaluation/pc/'), true); assert.equal(registry.has('/evaluation/h5/app.js'), true);
  const result = registry.get('/evaluation/pc/'); assert.match(result.csp, /script-src 'self' 'sha256-/u);
  assert.doesNotMatch(result.csp, /unsafe-eval|script-src[^;]*unsafe-inline/u);
  result.body.fill(0); assert.equal(registry.get('/evaluation/pc/').body.includes(Buffer.from('<html>')), true);
  writeFileSync(resolve(f.roots.pc.publicDirectory, 'app.js'), 'changed after snapshot');
  assert.notEqual(registry.get('/evaluation/pc/app.js').body.toString(), 'changed after snapshot');
  for (const path of ['/evaluation/pc/../.env', '/evaluation/pc/%2e%2e/app.js', '/evaluation/pc/app.js?cache=1', '/api/approval/tasks/pending', '/evaluation/pc/missing', '/evaluation/pc/.env']) assert.equal(registry.get(path), null);
});
for (const field of ['commit', 'tree', 'ordinary', 'base', 'digest', 'size', 'duplicate', 'path', 'map']) {
  test(`refuses an invalid static inventory: ${field}`, t => {
    const f = fixtures(t); f.edit('pc', v => {
      if (field === 'commit') v.commitSha = 'c'.repeat(40);
      if (field === 'tree') v.treeSha = 'd'.repeat(40);
      if (field === 'ordinary') delete v.evaluation;
      if (field === 'base') v.evaluation.basePath = '/';
      if (field === 'digest') v.files[0].sha256 = '0'.repeat(64);
      if (field === 'size') v.totalBytes += 1;
      if (field === 'duplicate') v.files.push(v.files[0]);
      if (field === 'path') v.files[0].path = '../index.html';
      if (field === 'map') v.files[0].path = 'source.js.map';
      v.inventorySha256 = hash(JSON.stringify(v.files));
    }); assert.throws(f.create, /ARTIFACT_REJECTED/u);
  });
}
test('rejects symlink files and symlink root directories', t => {
  const f = fixtures(t); const path = resolve(f.roots.pc.publicDirectory, 'app.js');
  rmSync(path); symlinkSync(resolve(f.roots.h5.publicDirectory, 'app.js'), path); assert.throws(f.create);
});
async function httpsFixture(t) {
  const f = fixtures(t); const keyFile = resolve(f.directory, 'key.pem'); const certFile = resolve(f.directory, 'cert.pem');
  execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-subj', '/CN=localhost',
    '-addext', 'subjectAltName=DNS:localhost,IP:127.0.0.1', '-keyout', keyFile, '-out', certFile], { stdio: 'ignore', timeout: 10000 });
  const reservation = createServer(); await new Promise(done => reservation.listen(0, '127.0.0.1', done));
  const port = reservation.address().port; await new Promise(done => reservation.close(done));
  let clock = 1000;
  const controller = createEvaluationSessions({ scenario, slotIds: ['slot-a', 'slot-b'], clock: () => clock,
    dispatchBusiness: async () => ({ status: 200, body: Buffer.from('{}'), headers: { 'content-type': 'application/json' } }),
    resetSlot: async ({ slotId, resetNonce }) => ({ slotId, resetNonce, generation: randomBytes(16).toString('hex'), clean: true }) });
  await controller.reset('slot-a'); await controller.reset('slot-b');
  const origin = `https://localhost:${port}`; const cert = readFileSync(certFile);
  const server = createEvaluationHttpsServer({ origin, controller, applications: f.create(), key: readFileSync(keyFile), cert });
  await new Promise(done => server.listen(port, '127.0.0.1', done));
  t.after(async () => { server.closeAllConnections(); await new Promise(done => server.close(done)); });
  async function call(path, headers = {}) {
    return new Promise((done, fail) => {
      const r = request(origin + path, { ca: cert, rejectUnauthorized: true, headers, lookup: (_host, options, cb) => options.all
        ? cb(null, [{ address: '127.0.0.1', family: 4 }]) : cb(null, '127.0.0.1', 4) }, response => {
        const parts = []; response.on('data', b => parts.push(b)); response.on('end', () => done({ status: response.statusCode, headers: response.headers, body: Buffer.concat(parts) }));
      }); r.once('error', fail); r.end();
    });
  }
  const enter = () => controller.redeem(controller.issueInvitation().invitation);
  return { ...f, controller, call, enter, advance: ms => { clock += ms; } };
}
test('actual TLS gateway requires a session for all application bytes, redirects only documents, and keeps APIs isolated', async t => {
  const f = await httpsFixture(t);
  assert.equal((await f.call('/evaluation/pc/')).status, 401);
  const navigation = await f.call('/evaluation/pc/', { 'Sec-Fetch-Dest': 'document' });
  assert.equal(navigation.status, 303); assert.equal(navigation.headers.location, '/evaluation');
  assert.equal((await f.call('/evaluation/pc/app.js', { 'Sec-Fetch-Dest': 'script' })).status, 401);
  const a = f.enter(); const b = f.enter(); const cookie = token => ({ Cookie: evaluationCookie + '=' + token });
  assert.equal((await f.call('/evaluation/pc/', cookie(a.token))).status, 200);
  const h5 = await f.call('/evaluation/h5/app.js', cookie(b.token)); assert.equal(h5.status, 200);
  assert.equal(h5.headers['cache-control'], 'no-store'); assert.equal(h5.headers['x-content-type-options'], 'nosniff');
  const links = await f.call('/evaluation/applications', cookie(a.token)); assert.deepEqual(JSON.parse(links.body), evaluationApplicationPaths);
  assert.equal((await f.call('/evaluation/pc/app.js', { ...cookie(a.token), 'X-Tenant-Id': 'forged' })).status, 403);
  assert.equal((await f.call('/evaluation/pc/app.js', { ...cookie(a.token), Origin: 'https://wrong.invalid' })).status, 403);
  assert.equal((await f.call('/evaluation/pc/.env', cookie(a.token))).status, 404);
  assert.equal((await f.call('/evaluation/pc/api/approval/tasks/pending', cookie(a.token))).status, 404);
  assert.equal((await f.call('/api/approval/tasks/pending', cookie(a.token))).status, 403);
  await f.controller.end(a.token, a.session.csrfToken);
  assert.equal((await f.call('/evaluation/h5/app.js', cookie(a.token))).status, 401);
  assert.equal((await f.call('/evaluation/h5/app.js', cookie(b.token))).status, 200);
  f.advance(1800001); assert.equal((await f.call('/evaluation/pc/', cookie(b.token))).status, 401);
});
test('application exporting never starts a container and requires exact owned images before copying', async t => {
  const f = fixtures(t); const containers = new Map(); const calls = []; const images = ['pc', 'h5'].map((component, i) => ({ component, localImageId: 'sha256:' + String(i + 1).repeat(64) }));
  const smoke = { status: 'LOCAL_IMAGE_STARTUP_SMOKE_PASSED', cleanup: { status: 'PASSED' }, source: f.source, build: { source: f.source, images } };
  let index = 0;
  const run = async args => {
    calls.push(args);
    if (args[0] === 'image') { const image = images.find(v => v.localImageId === args[2]); return JSON.stringify([{ Id: image.localImageId, Config: { User: '101:101', Labels: {
      'org.opencontainers.image.revision': f.source.commitSha, 'io.approval.source.tree': f.source.treeSha, 'io.approval.component': image.component } } }]); }
    if (args[1] === 'create') {
      const id = String(++index).repeat(64); const name = args[args.indexOf('--name') + 1]; const [label, value] = args[args.indexOf('--label') + 1].split('=');
      const image = images.find(v => v.localImageId === args.at(-1));
      containers.set(id, { Id: id, Name: '/' + name, Image: image.localImageId, State: { Running: false }, Config: { Labels: { [label]: value } }, component: image.component }); return id;
    }
    if (args[1] === 'cp') {
      const value = containers.get(args[2].split(':')[0]); const root = f.roots[value.component];
      cpSync(args[2].endsWith('/.') ? root.publicDirectory : root.inventoryFile, args[3], { recursive: true }); return '';
    }
    if (args[1] === 'inspect') return JSON.stringify([containers.get(args[2])]);
    if (args[1] === 'rm') { containers.delete(args[2]); return ''; }
    if (args[1] === 'ls') return '';
    throw new Error('unexpected docker action');
  };
  const result = await exportEvaluationApplications({ smoke, run }); t.after(result.dispose);
  assert.equal(result.cleanup.status, 'PASSED'); assert.equal(containers.size, 0);
  assert.equal(calls.some(args => args.includes('start') || args.includes('run') || args.includes('build') || args.includes('pull')), false);
  const registry = createEvaluationApplicationAssets({ source: f.source, roots: result.roots }); assert.ok(registry.get('/evaluation/h5/'));
});
