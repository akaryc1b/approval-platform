import assert from 'node:assert/strict';
import { after, test } from 'node:test';
import { randomBytes, createPublicKey, verify } from 'node:crypto';
import { execFile, execFileSync, spawn } from 'node:child_process';
import { once } from 'node:events';
import { performance } from 'node:perf_hooks';
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { createServer } from 'node:http';
import { request as httpsRequest } from 'node:https';
import { createInterface } from 'node:readline';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { promisify } from 'node:util';
import { createEvaluationSessions } from '../product-readiness/online-demo/evaluation-sessions.mjs';
import { createEvaluationReadRuntime } from '../product-readiness/online-demo/evaluation-read-runtime.mjs';
import { createEvaluationDockerSlots } from '../product-readiness/online-demo/evaluation-docker-slots.mjs';
import { createEvaluationReadSigner, evaluationReadProbeProgram, verifyEvaluationReadIdentity }
  from '../product-readiness/online-demo/evaluation-read-identity.mjs';
import { evaluationPendingPageProgram, fetchEvaluationPendingPage, validateEvaluationPendingPage }
  from '../product-readiness/online-demo/evaluation-pending-read.mjs';
import { createEvaluationHttpsServer } from '../product-readiness/online-demo/evaluation-http.mjs';
import { executeEvaluationSlotRehearsal } from '../product-readiness/online-demo/evaluation-slot-rehearsal.mjs';

const scenario = JSON.parse(readFileSync(new URL('../../config/demo/purchase-payment-golden-path.json', import.meta.url)));
const asyncExec = promisify(execFile);
const delay = ms => new Promise(done => setTimeout(done, ms));
const settle = () => new Promise(done => setImmediate(done));
const token = () => randomBytes(32).toString('base64url');
const empty = () => ({ items: [], total: 0, limit: 20, offset: 0, hasMore: false });
const page = label => ({ items: [{ taskId: '11111111-1111-4111-8111-111111111111',
  instanceId: '22222222-2222-4222-8222-222222222222', definitionKey: 'purchase-payment',
  taskDefinitionKey: 'managerApproval', taskName: '采购审批', businessKey: label,
  initiatorId: 'demo-employee', amount: 12500, supplier: 'Demo', purchaseOrderReference: 'PO-DEMO',
  taskCreatedAt: '2026-09-07T00:00:00Z', taskUpdatedAt: '2026-09-07T00:00:00Z' }],
  total: 1, limit: 20, offset: 0, hasMore: false });

function sessions(options = {}) {
  let time = 1000; const requests = [];
  const controller = createEvaluationSessions({ scenario, slotIds: ['slot-a', 'slot-b'],
    clock: () => time, sessionTtlMs: 1000,
    resetSlot: async ({ slotId, resetNonce }) => ({ slotId, resetNonce, generation: Buffer.from(resetNonce, 'base64url').toString('hex').slice(0, 32), clean: true }),
    readPending: request => { requests.push(request); return empty(); }, ...options });
  return { controller, requests, advance: ms => { time += ms; },
    ready: async () => { await controller.reset('slot-a'); await controller.reset('slot-b'); },
    enter: () => controller.redeem(controller.issueInvitation().invitation) };
}

test('session chooses slot/actor/reset generation privately and does not publish routing material', async () => {
  const f = sessions(); await f.ready(); const a = f.enter(); const b = f.enter();
  await f.controller.readPending(a.token, a.session.csrfToken);
  await f.controller.readPending(b.token, b.session.csrfToken);
  assert.deepEqual(f.requests.map(r => [r.slotId, r.actorId]), [['slot-a', 'demo-employee'], ['slot-b', 'demo-employee']]);
  assert.notEqual(f.requests[0].generation, f.requests[1].generation);
  assert.deepEqual(Object.keys(f.requests[0]).sort(), ['actorId', 'generation', 'signal', 'slotId']);
  assert.equal(a.session.businessAccess, 'SIGNED_PENDING_READ');
  const publicValue = JSON.stringify(a.session);
  for (const text of ['slot-a', f.requests[0].generation, a.token]) assert.equal(publicValue.includes(text), false);
});

test('read is off unless an explicit transport is supplied; invalid configuration stays closed', async () => {
  const f = sessions({ readPending: undefined }); await f.ready(); const a = f.enter();
  assert.equal(a.session.businessAccess, 'NOT_CONNECTED');
  await assert.rejects(f.controller.readPending(a.token, a.session.csrfToken), /BUSINESS_NOT_CONNECTED/u);
  for (const options of [{ readPending: null }, { readPending: {} }, { readTimeoutMs: 5001 }, { readTimeoutMs: 0 }]) {
    assert.throws(() => sessions(options), /INVALID_CONFIGURATION/u);
  }
});

test('cross-session CSRF, unknown tokens and already cancelled reads never reach transport', async () => {
  const f = sessions(); await f.ready(); const a = f.enter(); const b = f.enter();
  await assert.rejects(f.controller.readPending(a.token, b.session.csrfToken), /CSRF/u);
  await assert.rejects(f.controller.readPending(token(), a.session.csrfToken), /SESSION_REQUIRED/u);
  await assert.rejects(f.controller.readPending(a.token, a.session.csrfToken, AbortSignal.abort()), /REVOKED/u);
  await assert.rejects(f.controller.readPending(a.token, a.session.csrfToken, {}), /INVALID_READ_SIGNAL/u);
  assert.equal(f.requests.length, 0);
});

for (const cause of ['actor', 'end', 'reset', 'expiry', 'disable']) {
  test(`in-flight read is cancelled and its eventual response is fenced by ${cause}`, async () => {
    let finish; let request;
    const f = sessions({ readPending: value => { request = value; return new Promise(done => { finish = done; }); } });
    await f.ready(); const a = f.enter();
    const pending = f.controller.readPending(a.token, a.session.csrfToken);
    const rejected = assert.rejects(pending, /REVOKED|REQUIRED|DISABLED/u);
    await settle();
    if (cause === 'actor') f.controller.changeActor(a.token, a.session.csrfToken, 'demo-manager');
    if (cause === 'end') await f.controller.end(a.token, a.session.csrfToken);
    if (cause === 'reset') await f.controller.reset('slot-a');
    if (cause === 'expiry') { f.advance(1000); await f.controller.sweep(); }
    if (cause === 'disable') f.controller.disable();
    assert.equal(request.signal.aborted, true);
    finish(page('MUST-NOT-RETURN')); await rejected;
  });
}

test('role rotation before the transport microtask prevents an old request from even starting', async () => {
  const f = sessions(); await f.ready(); const a = f.enter();
  const pending = f.controller.readPending(a.token, a.session.csrfToken);
  const next = f.controller.changeActor(a.token, a.session.csrfToken, 'demo-manager');
  await assert.rejects(pending, /REVOKED|REQUIRED/u);
  assert.equal(f.requests.length, 0);
  await f.controller.readPending(next.token, next.session.csrfToken);
  assert.equal(f.requests[0].actorId, 'demo-manager');
});

test('hung transport retains its concurrency charge across timeouts and token rotations', async () => {
  const finishes = [];
  const f = sessions({ readTimeoutMs: 15, readPending: () => new Promise(done => finishes.push(done)) });
  await f.ready(); const a = f.enter();
  await assert.rejects(f.controller.readPending(a.token, a.session.csrfToken), /TIMEOUT/u);
  const next = f.controller.changeActor(a.token, a.session.csrfToken, 'demo-manager');
  await assert.rejects(f.controller.readPending(next.token, next.session.csrfToken), /TIMEOUT/u);
  await assert.rejects(f.controller.readPending(next.token, next.session.csrfToken), /READ_LIMIT/u);
  finishes.forEach(done => done(empty())); await settle();
  const pending = f.controller.readPending(next.token, next.session.csrfToken); await settle();
  finishes.at(-1)(empty()); assert.deepEqual(await pending, empty());
});

test('transport exceptions cannot expose diagnostic material to the browser', async () => {
  const secret = token(); const f = sessions({ readPending: () => { throw new Error(secret); } });
  await f.ready(); const a = f.enter();
  await assert.rejects(f.controller.readPending(a.token, a.session.csrfToken), e => e.message === 'SESSION_READ_UNAVAILABLE');
  assert.equal(JSON.stringify(f.controller.snapshot()).includes(secret), false);
});

test('two readers in one slot cannot exhaust the other slot', async () => {
  const finishes = [];
  const f = sessions({ readPending: ({ slotId }) => slotId === 'slot-a'
    ? new Promise(done => finishes.push(done)) : page('B') });
  await f.ready(); const a = f.enter(); const b = f.enter();
  const first = f.controller.readPending(a.token, a.session.csrfToken);
  const second = f.controller.readPending(a.token, a.session.csrfToken);
  await assert.rejects(f.controller.readPending(a.token, a.session.csrfToken), /READ_LIMIT/u);
  assert.equal((await f.controller.readPending(b.token, b.session.csrfToken)).items[0].businessKey, 'B');
  finishes.forEach(done => done(empty())); await Promise.all([first, second]);
});

for (const [name, change] of [
  ['extra field', value => { value.privateKey = 'secret'; }],
  ['too many items', value => { value.items = new Array(21).fill(page('A').items[0]); value.total = 21; value.hasMore = true; }],
  ['wrong limit', value => { value.limit = 100; }],
  ['negative total', value => { value.total = -1; }],
  ['noninteger total', value => { value.total = 1.5; }],
  ['false hasMore', value => { value.hasMore = true; }],
  ['item secret', value => { value.items[0].privateKey = 'secret'; }],
  ['wrong identifier', value => { value.items[0].taskId = 'x'; }],
  ['duplicate task', value => { value.items.push({ ...value.items[0] }); value.total = 2; }],
  ['oversized label', value => { value.items[0].taskName = 'x'.repeat(513); }],
  ['invalid amount', value => { value.items[0].amount = NaN; }],
]) test(`pending-page schema rejects ${name}`, () => {
  const value = page('A'); change(value); assert.throws(() => validateEvaluationPendingPage(value), /REJECTED/u);
});

test('pending-page validator copies accepted data and preserves the real page fields', () => {
  const value = page('A'); const result = validateEvaluationPendingPage(value);
  value.items[0].businessKey = 'changed'; assert.equal(result.items[0].businessKey, 'A');
  assert.deepEqual(validateEvaluationPendingPage(empty()), empty());
});

test('preflight failures retain a fixed stage/status but never response or proof data', async () => {
  const signer = createEvaluationReadSigner(scenario, 'a'.repeat(32)); const observations = [];
  await assert.rejects(verifyEvaluationReadIdentity(signer, 'b'.repeat(64), async () =>
    JSON.stringify({ status: 500, taskCount: null }), value => observations.push(value)), /REJECTED/u);
  assert.deepEqual(observations, [
    { number: 1, stage: 'UNSIGNED', outcome: 'STARTED', status: null, taskCount: null },
    { number: 1, stage: 'UNSIGNED', outcome: 'OBSERVED', status: 500, taskCount: null },
  ]);
  const secret = token(); const failed = [];
  await assert.rejects(verifyEvaluationReadIdentity(signer, 'b'.repeat(64), async () => { throw new Error(secret); },
    value => failed.push(value)), /REJECTED/u);
  assert.equal(failed.at(-1).outcome, 'PROBE_FAILED');
  assert.equal(JSON.stringify(failed).includes(secret), false); signer.disable();
});

// The following fixture substitutes Docker and persistent storage. Its labels,
// health and JAR responses are not container acceptance. In realProbe mode only
// the TLS, Node probe, loopback HTTP and actual JDK verifier are real processes.
function imageFixture() {
  const id = value => `sha256:${value.repeat(64)}`;
  return { source: { commitSha: 'a'.repeat(40), treeSha: 'b'.repeat(40), version: '0.1.0-SNAPSHOT' },
    backendImage: { component: 'backend', localImageId: id('1'), user: '10001:10001' },
    archiveSha256: 'c'.repeat(64), infrastructure: {
      postgres: { localImageId: id('2'), pin: `postgres:16@sha256:${'d'.repeat(64)}` },
      redis: { localImageId: id('3'), pin: `redis:7@sha256:${'e'.repeat(64)}` },
      probe: { localImageId: id('4'), pin: `node:22@sha256:${'f'.repeat(64)}` },
    } };
}
let javaDirectory;
async function javaVerifier(key, generation) {
  if (!javaDirectory) {
    javaDirectory = mkdtempSync(resolve(tmpdir(), 'evaluation-dispatch-jdk-'));
    const source = resolve(javaDirectory, 'EvaluationVerifierHarness.java');
    writeFileSync(source, `import io.github.akaryc1b.approval.security.OnlineEvaluationReadTicket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Set;
public class EvaluationVerifierHarness {
  public static void main(String[] args) throws Exception {
    var verifier = new OnlineEvaluationReadTicket(args[0], args[1], Set.of("demo-employee", "demo-manager",
      "demo-finance-reviewer", "demo-finance-approver-a", "demo-finance-approver-b"), Clock.systemUTC());
    var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    System.out.println("READY");
    String line;
    while ((line = reader.readLine()) != null) {
      try { System.out.println("OK:" + verifier.verify(line, "GET", OnlineEvaluationReadTicket.PATH).actorId()); }
      catch (SecurityException expected) { System.out.println("REJECTED"); }
    }
  }
}
`);
    execFileSync('javac', ['--release', '17', '-d', javaDirectory,
      new URL('../../apps/server/src/main/java/io/github/akaryc1b/approval/security/OnlineEvaluationReadTicket.java', import.meta.url).pathname,
      source], { stdio: 'pipe', timeout: 20_000 });
  }
  const child = spawn('java', ['-cp', javaDirectory, 'EvaluationVerifierHarness', key, generation], { stdio: ['pipe', 'pipe', 'pipe'] });
  const lines = createInterface({ input: child.stdout }); const queue = [];
  const ready = new Promise((done, fail) => {
    const timeout = setTimeout(() => { child.kill('SIGKILL'); fail(new Error('JDK startup failed')); }, 5000);
    lines.once('line', line => { clearTimeout(timeout); line === 'READY' ? done() : fail(new Error('JDK startup rejected')); });
    child.once('error', fail);
  });
  await ready;
  lines.on('line', line => queue.shift()?.(line));
  let chain = Promise.resolve();
  return {
    verify(proof) {
      const operation = chain.then(() => new Promise((done, fail) => {
        const timer = setTimeout(() => fail(new Error('JDK verification timeout')), 3000);
        queue.push(line => { clearTimeout(timer); done(line.startsWith('OK:') ? line.slice(3) : null); });
        child.stdin.write(`${proof}\n`);
      }));
      chain = operation.catch(() => {}); return operation;
    },
    async close() {
      child.stdin.end(); const timer = setTimeout(() => child.kill('SIGKILL'), 1000);
      if (child.exitCode === null) await once(child, 'exit'); clearTimeout(timer); lines.close();
    },
  };
}
after(() => { if (javaDirectory) rmSync(javaDirectory, { recursive: true, force: true }); });

async function dockerDouble({ realProbe = false } = {}) {
  const input = imageFixture(); const containers = new Map(); const networks = new Map();
  const records = []; const calls = []; let sequence = 1; let beforeRun = async () => {};
  const hex = () => (sequence++).toString(16).padStart(64, '0');
  const option = (args, name) => args[args.indexOf(name) + 1];
  const options = (args, name) => args.flatMap((value, index) => value === name ? [args[index + 1]] : []);
  const get = target => [...containers.values()].find(c => c.Id === target || c.Name === `/${target}`);
  const environment = c => Object.fromEntries(c.Config.Env.map(value => [value.slice(0, value.indexOf('=')), value.slice(value.indexOf('=') + 1)]));
  const verifyLocal = (backend, proof) => {
    try {
      const [message, signature] = proof.split('.'); const bytes = Buffer.from(message, 'base64url'); const fields = bytes.toString().split('\n');
      const env = environment(backend);
      assert.equal(fields[3], env.APPROVAL_EVALUATION_GENERATION);
      const publicKey = createPublicKey({ key: Buffer.from(env.APPROVAL_EVALUATION_PUBLIC_KEY, 'base64'), format: 'der', type: 'spki' });
      assert.equal(verify(null, bytes, publicKey, Buffer.from(signature, 'base64url')), true);
      assert.equal(backend.nonces.has(fields[7]), false); backend.nonces.add(fields[7]); return fields[4];
    } catch { return null; }
  };
  async function startBackend(backend) {
    backend.pending = empty(); backend.nonces = new Set(); backend.actors = [];
    if (!realProbe) return;
    const env = environment(backend);
    backend.verifier = await javaVerifier(env.APPROVAL_EVALUATION_PUBLIC_KEY, env.APPROVAL_EVALUATION_GENERATION);
    backend.http = createServer(async (request, response) => {
      response.setHeader('Content-Type', 'application/json');
      if (request.url !== '/api/approval/tasks/pending' || request.method !== 'GET') { response.writeHead(404); response.end('{}'); return; }
      if (request.headers['x-operator-id']) { response.writeHead(403); response.end('{}'); return; }
      const actor = request.headers['x-evaluation-read-ticket']
        ? await backend.verifier.verify(request.headers['x-evaluation-read-ticket']) : null;
      if (!actor) { response.writeHead(401); response.end('{}'); return; }
      backend.actors.push(actor); response.end(JSON.stringify(backend.pending));
    });
    backend.http.listen(0, '127.0.0.1'); await once(backend.http, 'listening');
  }
  async function remove(backend) {
    if (backend.http) { backend.http.closeAllConnections(); await new Promise(done => backend.http.close(done)); }
    await backend.verifier?.close();
  }
  const run = async (args, opts = {}) => {
    calls.push(args); await beforeRun(args, opts);
    if (args[0] === 'info') return JSON.stringify({ OSType: 'linux', ID: 'TEST_ONLY_ENGINE' });
    if (args[0] === 'image') {
      const backend = args[2] === input.backendImage.localImageId;
      const role = Object.keys(input.infrastructure).find(r => input.infrastructure[r].pin === args[2]);
      return JSON.stringify([{ Id: backend ? input.backendImage.localImageId : input.infrastructure[role].localImageId,
        Os: 'linux', Architecture: 'amd64', Config: { User: '10001:10001', Labels: {
          'org.opencontainers.image.revision': input.source.commitSha,
          'org.opencontainers.image.version': input.source.version,
          'io.approval.source.tree': input.source.treeSha, 'io.approval.source.archive': input.archiveSha256,
          'io.approval.component': 'backend' } } }]);
    }
    if (args[0] === 'network') {
      const find = value => [...networks.values()].find(n => n.Id === value || n.Name === value);
      if (args[1] === 'ls') {
        const filter = option(args, '--filter');
        return [...networks.values()].filter(n => filter.startsWith('id=') ? n.Id === filter.slice(3)
          : n.Name === filter.slice(6, -1)).map(n => n.Id).join('\n');
      }
      if (args[1] === 'create') {
        const network = { Id: hex(), Name: args.at(-1), Internal: true,
          Labels: Object.fromEntries(options(args, '--label').map(v => v.split('='))) };
        networks.set(network.Id, network); return network.Id;
      }
      if (args[1] === 'inspect') {
        const n = find(args[2]);
        return JSON.stringify([{ ...n, Containers: Object.fromEntries([...containers.values()]
          .filter(c => c.HostConfig.NetworkMode === n.Name).map(c => [c.Id, {}])) }]);
      }
      if (args[1] === 'rm') { networks.delete(args[2]); return ''; }
    }
    if (args[0] === 'create') {
      const label = Object.fromEntries(options(args, '--label').map(v => v.split('=')));
      const role = label['io.approval.evaluation.role'];
      const c = { Id: hex(), Name: `/${option(args, '--name')}`, State: { Running: false },
        Image: role === 'backend' ? input.backendImage.localImageId : input.infrastructure[role].localImageId,
        Config: { Labels: label, Env: options(args, '--env'), User: role === 'backend' ? '10001:10001' : role === 'probe' ? '1000:1000' : '' },
        HostConfig: { NetworkMode: option(args, '--network'), Privileged: false, CapAdd: [],
          PortBindings: {}, PublishAllPorts: false, RestartPolicy: { Name: 'no' }, Memory: 128 * 1024 ** 2,
          NanoCpus: 1e9, PidsLimit: 256, Tmpfs: Object.fromEntries(options(args, '--tmpfs').map(v => [v.split(':')[0], v.split(':')[1]])),
          ReadonlyRootfs: args.includes('--read-only'), CapDrop: ['ALL'], SecurityOpt: ['no-new-privileges'] },
        NetworkSettings: { Ports: {}, Networks: role === 'probe' ? {} : { [option(args, '--network')]: { IPAddress: '172.30.' + (label['io.approval.evaluation.slot'] === 'slot-a' ? '1.' : '2.') + sequence } } }, Mounts: [] };
      containers.set(c.Id, c); return c.Id;
    }
    if (args[0] === 'start') { const c = get(args[1]); c.State.Running = true;
      if (c.Config.Labels['io.approval.evaluation.role'] === 'backend') await startBackend(c); return c.Id; }
    if (args[0] === 'container') {
      if (args[1] === 'ls') { const filter = option(args, '--filter');
        return [...containers.values()].filter(c => filter.startsWith('id=') ? c.Id === filter.slice(3)
          : c.Name === filter.slice(6, -1)).map(c => c.Id).join('\n'); }
      if (args[1] === 'inspect') { const { http, verifier, pending, nonces, actors, ...c } = get(args[2]); return JSON.stringify([c]); }
      if (args[1] === 'rm') { await remove(get(args.at(-1))); containers.delete(args.at(-1)); return ''; }
    }
    if (args[0] === 'exec') {
      if (args.includes('pg_isready')) return 'accepting connections';
      if (args.includes('psql')) {
        const c = get(args[1]); const query = args.at(-1);
        if (query.includes('to_regclass')) return c.marker ? 'f' : 't';
        if (query.startsWith('create table')) { c.marker = query.match(/values \('([0-9a-f]{32})'\)/u)[1]; return ''; }
        if (query === 'select value from evaluation_reset_probe;') return c.marker || '';
      }
      if (args.includes('redis-cli')) {
        const c = get(args[1]);
        if (args.includes('ping')) return 'PONG';
        if (args.includes('SET')) { c.marker = args.at(-1); return 'OK'; }
        if (args.includes('GET')) return c.marker || '';
        if (args.includes('EXISTS')) return c.marker ? '1' : '0';
      }
      if (args.includes('sh')) {
        const c = get(args[1]);
        if (args.some(value => value.includes('sha256sum -c'))) return 'app.jar: OK';
        if (args.some(value => value.startsWith('test ! -e'))) return c.marker ? 'DIRTY' : 'CLEAN';
        if (args.includes('probe')) { c.marker = args.at(-1); return ''; }
      }
      if (args.includes('cat')) return get(args[1]).marker || '';

      const program = args[args.indexOf('-e') + 1];
      if (program.includes('/actuator/health')) return 'UP';
      if (program.includes("from 'node:net'")) {
        const backend = get(get(args[1]).HostConfig.NetworkMode.slice('container:'.length));
        const target = [...containers.values()].find(c => Object.values(c.NetworkSettings.Networks).some(n => n.IPAddress === args.at(-2)));
        return backend.HostConfig.NetworkMode === target?.HostConfig.NetworkMode ? 'CONNECTED' : 'BLOCKED';
      }
      const backend = get(get(args[1]).HostConfig.NetworkMode.slice('container:'.length));
      const values = args.slice(args.indexOf('-e') + 2);
      if (realProbe) {
        const child = await asyncExec(process.execPath, ['--input-type=module', '-e', program, ...values,
          String(backend.http.address().port)], { encoding: 'utf8', signal: opts.signal, timeout: opts.timeoutMs || 5000, maxBuffer: 128 * 1024 });
        return child.stdout.trim();
      }
      if (program === evaluationReadProbeProgram) {
        const [proof, path, extra] = values;
        const status = extra ? 403 : path !== '/api/approval/tasks/pending' ? 404 : proof && verifyLocal(backend, proof) ? 200 : 401;
        return JSON.stringify({ status, taskCount: status === 200 ? 0 : null });
      }
      if (program === evaluationPendingPageProgram) {
        const actor = verifyLocal(backend, values[0]);
        if (actor) backend.actors.push(actor);
        return JSON.stringify({ status: actor ? 200 : 401, page: actor ? backend.pending : null, code: actor ? 'PENDING_PAGE' : 'HTTP_REJECTED' });
      }
    }
    throw new Error('UNEXPECTED_TEST_DOCKER_COMMAND');
  };
  return { ...input, run, containers, networks, records, calls,
    record: value => records.push(value),
    hook: callback => { beforeRun = callback; },
    backend: slot => [...containers.values()].find(c => c.Config.Labels['io.approval.evaluation.slot'] === slot
      && c.Config.Labels['io.approval.evaluation.role'] === 'backend'),
    close: async () => { for (const c of containers.values()) await remove(c); containers.clear(); networks.clear(); } };
}

function certificates(t) {
  const directory = mkdtempSync(resolve(tmpdir(), 'evaluation-dispatch-tls-'));
  execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-sha256', '-nodes', '-days', '1',
    '-keyout', resolve(directory, 'key.pem'), '-out', resolve(directory, 'cert.pem'), '-subj', '/CN=evaluation.example.invalid',
    '-addext', 'subjectAltName=DNS:evaluation.example.invalid'], { stdio: 'pipe', timeout: 10_000 });
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  return { key: readFileSync(resolve(directory, 'key.pem')), cert: readFileSync(resolve(directory, 'cert.pem')), origin: 'https://evaluation.example.invalid' };
}
function client(server, tls) {
  return (path, { body, cookie, csrf, headers = {}, method = body === undefined ? 'GET' : 'POST' } = {}) => new Promise((done, fail) => {
    const request = httpsRequest({ hostname: '127.0.0.1', port: server.address().port, servername: 'evaluation.example.invalid',
      ca: tls.cert, path, method, agent: false, timeout: 8000,
      headers: { Host: 'evaluation.example.invalid', ...(cookie ? { Cookie: cookie } : {}),
        ...(csrf ? { 'X-Evaluation-CSRF': csrf } : {}), ...(method === 'POST' ? { Origin: tls.origin, 'Content-Type': 'application/json' } : {}), ...headers } }, response => {
      let text = ''; response.on('data', bytes => { text += bytes; });
      response.on('end', () => done({ status: response.statusCode, text, json: () => JSON.parse(text),
        headers: response.headers, cookie: response.headers['set-cookie']?.[0]?.split(';')[0] }));
    });
    request.on('error', fail); request.on('timeout', () => request.destroy(new Error('TLS test request timed out')));
    request.end(body === undefined ? undefined : JSON.stringify(body));
  });
}

async function runtimeFixture(t, realProbe = false) {
  const docker = await dockerDouble({ realProbe }); const tls = certificates(t); let time = 1000;
  const runtime = await createEvaluationReadRuntime({ ...docker, ...tls, scenario, clock: () => time, sessionTtlMs: 1000 });
  t.after(async () => { await runtime.dispose(); await docker.close(); });
  runtime.server.listen(0, '127.0.0.1'); await once(runtime.server, 'listening');
  const call = client(runtime.server, tls);
  const enter = async () => {
    const invitation = runtime.controller.issueInvitation().invitation;
    const result = await call('/evaluation/invitations/redeem', { body: { invitation } });
    assert.equal(result.status, 201); return { cookie: result.cookie, csrf: result.json().csrfToken };
  };
  return { docker, runtime, call, enter, advance: value => { time += value; } };
}

test('real TLS -> controller -> adapter -> Node HTTP probe -> actual JDK verifier routes and revokes two contexts', { timeout: 45_000 }, async t => {
  const f = await runtimeFixture(t, true);
  const a = await f.enter(); const b = await f.enter();
  f.docker.backend('slot-a').pending = page('A'); f.docker.backend('slot-b').pending = page('B');
  const readA = await f.call('/api/approval/tasks/pending', a);
  const readB = await f.call('/api/approval/tasks/pending', b);
  assert.equal(readA.status, 200); assert.equal(readA.json().items[0].businessKey, 'A');
  assert.equal(readB.json().items[0].businessKey, 'B'); assert.equal(readA.headers['cache-control'], 'no-store');
  assert.equal(readA.headers['set-cookie'], undefined); assert.equal(readA.headers['x-evaluation-read-ticket'], undefined);
  const oldA = f.docker.backend('slot-a').Id; const oldB = f.docker.backend('slot-b').Id;
  assert.equal((await f.call('/evaluation/session/end', { ...a, body: {} })).status, 204);
  assert.equal((await f.call('/api/approval/tasks/pending', a)).status, 401);
  assert.notEqual(f.docker.backend('slot-a').Id, oldA); assert.equal(f.docker.backend('slot-b').Id, oldB);
  assert.equal((await f.call('/api/approval/tasks/pending', b)).json().items[0].businessKey, 'B');
  f.advance(500); const replacement = await f.enter();
  assert.deepEqual((await f.call('/api/approval/tasks/pending', replacement)).json(), empty());
  const rotated = await f.call('/evaluation/session/actor', { ...replacement, body: { actorId: 'demo-manager' } });
  assert.equal(rotated.status, 200);
  const manager = { cookie: rotated.cookie, csrf: rotated.json().csrfToken };
  assert.equal((await f.call('/api/approval/tasks/pending', replacement)).status, 401);
  assert.equal((await f.call('/api/approval/tasks/pending', manager)).status, 200);
  assert.equal(f.docker.backend('slot-a').actors.at(-1), 'demo-manager');
  f.advance(500); await f.runtime.controller.sweep();
  assert.equal((await f.call('/api/approval/tasks/pending', b)).status, 401);
  assert.equal((await f.call('/api/approval/tasks/pending', manager)).status, 200);
  const resourceText = JSON.stringify(f.runtime.snapshot());
  for (const value of [a.cookie.split('=')[1], a.csrf, b.csrf, manager.csrf]) assert.equal(resourceText.includes(value), false);
  const cleanup = await f.runtime.dispose(); assert.equal(cleanup.status, 'PASSED');
  assert.equal(f.docker.containers.size, 0); assert.equal(f.docker.networks.size, 0);
});

test('real TLS refuses alternate routes, spoofed routing/proofs and cross-context CSRF without upstream dispatch', async t => {
  const f = await runtimeFixture(t); const a = await f.enter(); const b = await f.enter();
  const atStart = f.docker.calls.length;
  for (const path of ['/api/approval/tasks/pending?slotId=slot-b', '/api/approval/tasks/pending/', '/api/approval/tasks',
    '/api/approval/tasks%2fpending', '/api/approval/tasks/../tasks/pending', '/actuator/env']) {
    assert.equal((await f.call(path, a)).status, 404);
  }
  for (const name of ['X-Evaluation-Slot', 'X-Slot-Id', 'X-Evaluation-Generation', 'X-Evaluation-Read-Ticket', 'X-Actor-Id', 'X-Tenant-Id']) {
    assert.equal((await f.call('/api/approval/tasks/pending', { ...a, headers: { [name]: 'untrusted' } })).status, 403);
  }
  assert.equal((await f.call('/api/approval/tasks/pending', { cookie: a.cookie, csrf: b.csrf })).status, 403);
  assert.equal((await f.call('/api/approval/tasks/pending', { cookie: a.cookie })).status, 403);
  assert.equal((await f.call('/api/approval/tasks/pending', { ...a, method: 'POST', body: {} })).status, 404);
  assert.equal((await f.call('/api/approval/tasks/pending', { ...a, headers: { 'Sec-Fetch-Site': 'cross-site' } })).status, 403);
  assert.equal(f.docker.calls.length, atStart);
});

test('TLS disconnect cancels downstream work without storing a browser credential', async t => {
  const tls = certificates(t);
  let input; let started;
  const began = new Promise(done => { started = done; });
  const controlled = sessions({ readPending: request => { input = request; started(); return new Promise((unused, fail) => {
    request.signal.addEventListener('abort', () => fail(new Error('cancelled')), { once: true });
  }); } });
  await controlled.ready(); const session = controlled.enter();
  const server = createEvaluationHttpsServer({ ...tls, controller: controlled.controller });
  t.after(() => new Promise(done => { server.closeAllConnections(); server.close(done); }));
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  const request = httpsRequest({ hostname: '127.0.0.1', port: server.address().port, servername: 'evaluation.example.invalid',
    ca: tls.cert, path: '/api/approval/tasks/pending', headers: { Host: 'evaluation.example.invalid',
      Cookie: `__Host-approval-evaluation=${session.token}`, 'X-Evaluation-CSRF': session.session.csrfToken } });
  request.on('error', () => {}); request.end(); await began; request.destroy();
  for (let i = 0; !input.signal.aborted && i < 50; i++) await delay(5);
  assert.equal(input.signal.aborted, true);
});

test('read runtime refuses incomplete image/identity configuration and cleans initialization failures', async t => {
  const docker = await dockerDouble(); const tls = certificates(t);
  assert.throws(() => createEvaluationDockerSlots({ ...docker, scenario, readOnlyIdentity: false, sessionReads: true }), /INVALID_SESSION_READ_MODE/u);
  docker.hook(async args => { if (args[0] === 'exec' && args.includes(evaluationReadProbeProgram)) throw new Error('fixture startup failure'); });
  await assert.rejects(createEvaluationReadRuntime({ ...docker, ...tls, scenario }), e =>
    e.message === 'EVALUATION_RUNTIME_START_FAILED' && e.cleanupStatus === 'PASSED');
  assert.equal(docker.containers.size, 0); assert.equal(docker.networks.size, 0);
  assert.equal(docker.records.some(record => record.slots[0].identityChecks?.some(value => value.outcome === 'PROBE_FAILED')), true);
});

test('closed TLS entry retires the runtime, all signers and both disposable fixture environments', async t => {
  const f = await runtimeFixture(t); await f.enter();
  await new Promise(done => f.runtime.server.close(done));
  const result = await f.runtime.dispose(); assert.equal(result.status, 'PASSED');
  assert.equal(f.runtime.controller.snapshot().disabled, true); assert.equal(f.docker.containers.size, 0);
});

test('typed adapter failures are redacted just like untyped failures', async () => {
  const { EvaluationError } = await import('../product-readiness/online-demo/evaluation-sessions.mjs');
  const secret = token(); const f = sessions({ readPending: () => { throw new EvaluationError(secret, 500); } });
  await f.ready(); const a = f.enter();
  await assert.rejects(f.controller.readPending(a.token, a.session.csrfToken), error => error.code === 'SESSION_READ_UNAVAILABLE');
});

test('runtime lifetime is bounded even when no listener was opened or evaluator admitted', async t => {
  const docker = await dockerDouble(); const tls = certificates(t);
  for (const maximumLifetimeMs of [0, 99, 40 * 60_000 + 1, Infinity, NaN, '1000']) {
    await assert.rejects(createEvaluationReadRuntime({ ...docker, ...tls, scenario, maximumLifetimeMs }), /CONFIGURATION_REQUIRED/u);
  }
  const runtime = await createEvaluationReadRuntime({ ...docker, ...tls, scenario, maximumLifetimeMs: 500 });
  t.after(() => runtime.dispose());
  const deadline = performance.now() + 2000;
  while (!runtime.snapshot().controller.disabled && performance.now() < deadline) await delay(10);
  assert.equal(runtime.snapshot().controller.disabled, true);
  assert.equal((await runtime.dispose()).status, 'PASSED');
  assert.equal(docker.containers.size, 0); assert.equal(docker.networks.size, 0);
});

// Controlled DOM cases are not visual/browser acceptance.
class ReadElement {
  value = ''; disabled = false; hidden = false; textContent = ''; children = []; listeners = new Map();
  attributes = new Map();
  addEventListener(name, fn) { this.listeners.set(name, fn); }
  removeEventListener(name) { this.listeners.delete(name); }
  setAttribute(name, value) { this.attributes.set(name, value); }
  replaceChildren() { this.children = []; }
  append(value) { this.children.push(value); }
  emit(name) { this.listeners.get(name)?.({ preventDefault() {}, target: this }); }
}
async function readPageFixture(t, connected = true) {
  const { mountEvaluationPage } = await import('../product-readiness/online-demo/page/entry.mjs');
  const elements = new Map(['login', 'session', 'message', 'actor', 'invitation', 'submit', 'end', 'refresh',
    'expiry', 'controls', 'pending', 'pending-tasks', 'business-notice'].map(id => [id, new ReadElement()]));
  const get = id => elements.get(id); get('login').querySelector = () => get('submit');
  const document = new ReadElement(); document.querySelector = selector => get(selector.slice(1));
  document.createElement = () => new ReadElement(); let now = 0; let tick; const calls = [];
  const session = { businessAccess: connected ? 'SIGNED_PENDING_READ' : 'NOT_CONNECTED',
    actorId: 'demo-employee', actors: [{ id: 'demo-employee', displayName: '申请人' }, { id: 'demo-manager', displayName: '经理' }],
    csrfToken: 'a'.repeat(43), expiresInSeconds: 60, scope: 'DISPOSABLE_EVALUATION_CONTROL_PLANE' };
  const dispose = mountEvaluationPage({ document, now: () => now, setInterval: f => { tick = f; return 1; },
    clearInterval: () => {}, fetch: (path, options) => new Promise((done, reject) => calls.push({ path, options, done, reject })) });
  t.after(dispose);
  async function respond(status, body) {
    calls.at(-1).done({ status, ok: status >= 200 && status < 300, json: async () => body }); await settle();
  }
  await respond(200, session);
  return { get, calls, respond, session, advance: ms => { now += ms; tick(); } };
}

test('page opt-in exposes only the exact cookie/CSRF read and serializes controls', async t => {
  const f = await readPageFixture(t);
  assert.equal(f.get('pending').hidden, false);
  f.get('pending').emit('click'); f.get('pending').emit('click'); f.get('actor').emit('change');
  assert.equal(f.calls.length, 2); assert.equal(f.get('end').disabled, true);
  const request = f.calls.at(-1); assert.equal(request.path, '/api/approval/tasks/pending');
  assert.equal(request.options.method, 'GET'); assert.equal(request.options.credentials, 'same-origin');
  assert.deepEqual(request.options.headers, { 'X-Evaluation-CSRF': f.session.csrfToken });
  assert.equal(request.options.body, undefined);
  const value = page('A'); value.items[0].taskName = '<script>not HTML</script>';
  await f.respond(200, value);
  assert.equal(f.get('pending-tasks').children[0].textContent, '<script>not HTML</script> · A');
  assert.equal(f.get('pending-tasks').children[0].children.length, 0);
  assert.equal(f.get('pending').disabled, false);
});

test('disconnected page retains the old closed behavior and cannot call a business route', async t => {
  const f = await readPageFixture(t, false); f.get('pending').emit('click');
  assert.equal(f.get('pending').hidden, true); assert.equal(f.calls.length, 1);
  assert.match(f.get('business-notice').textContent, /尚未连接/u);
});

test('a pending-page response cannot extend expiry or revive a locally expired view', async t => {
  const f = await readPageFixture(t); f.get('pending').emit('click');
  f.advance(60_000); await f.respond(200, page('EXPIRED'));
  assert.equal(f.get('session').hidden, true); assert.equal(f.get('pending-tasks').children.length, 0);
  assert.equal(f.calls.length, 2);
});

test('confirmed actor rotation clears the prior role data and uses the new CSRF on its next read', async t => {
  const f = await readPageFixture(t); f.get('pending').emit('click'); await f.respond(200, page('OLD-ACTOR'));
  f.get('actor').value = 'demo-manager'; f.get('actor').emit('change');
  await f.respond(200, { ...f.session, actorId: 'demo-manager', csrfToken: 'b'.repeat(43) });
  assert.equal(f.get('pending-tasks').children.length, 0);
  f.get('pending').emit('click'); assert.equal(f.calls.at(-1).options.headers['X-Evaluation-CSRF'], 'b'.repeat(43));
  await f.respond(200, empty()); assert.match(f.get('message').textContent, /没有待办/u);
});

test('failed or malformed pending reads remove old visible data and are not replayed', async t => {
  const f = await readPageFixture(t); f.get('pending').emit('click'); await f.respond(200, page('OLD'));
  f.get('pending').emit('click'); await f.respond(200, { items: [null], total: 1, limit: 20, offset: 0 });
  assert.equal(f.get('pending-tasks').children.length, 0); assert.equal(f.get('session').hidden, true);
  assert.equal(f.get('refresh').hidden, false); assert.equal(f.calls.length, 3);
  f.get('refresh').emit('click'); assert.equal(f.calls.at(-1).path, '/evaluation/session');
  await f.respond(401, { error: 'SESSION_REQUIRED' });
});

function smokeReceipt(docker) {
  return { status: 'LOCAL_IMAGE_STARTUP_SMOKE_PASSED', cleanup: { status: 'PASSED' }, source: docker.source,
    build: { status: 'LOCAL_IMAGES_BUILT_NOT_RUNTIME_ACCEPTED', source: docker.source,
      images: [docker.backendImage], archiveSha256: docker.archiveSha256 }, infrastructure: docker.infrastructure };
}
test('existing runtime rehearsal now requires real TLS session reads without rebuilding; Docker remains substituted here', { timeout: 45_000 }, async t => {
  const docker = await dockerDouble({ realProbe: true });
  const directory = mkdtempSync(resolve(tmpdir(), 'evaluation-rehearsal-read-'));
  t.after(async () => { await docker.close(); rmSync(directory, { recursive: true, force: true }); });
  const receipt = await executeEvaluationSlotRehearsal({ smoke: smokeReceipt(docker), directory, scenario, readOnlyIdentity: true }, { run: docker.run });
  assert.equal(receipt.status, 'PRIVATE_TWO_SLOT_RESET_REHEARSAL_PASSED');
  assert.equal(receipt.sessionReadBinding.status, 'HTTPS_SESSION_READ_BINDING_PASSED');
  assert.equal(receipt.sessionReadBinding.realBrowserExecuted, false);
  const checks = receipt.checks.filter(value => value.check === 'HTTPS_SESSION_BOUND_PENDING_READ');
  assert.deepEqual(checks.map(value => [value.stage, value.status]), [
    ['INITIAL_A', 200], ['INITIAL_B', 200], ['CROSS_CONTEXT_CSRF', 403], ['REVOKED_A', 401],
    ['PRESERVED_B', 200], ['REPLACEMENT_A', 200], ['EXPIRED_B', 401], ['PRESERVED_REPLACEMENT_A', 200],
  ]);
  assert.equal(receipt.privateReadIdentity.verifiedGenerations, 4);
  assert.equal(receipt.cleanup.status, 'PASSED');
  assert.equal(docker.containers.size, 0); assert.equal(docker.networks.size, 0);
  assert.equal(docker.calls.some(args => ['build', 'pull', 'push'].includes(args[0])), false);
});

test('a failed bound TLS read makes the existing rehearsal fail and still disposes both slots', async t => {
  const docker = await dockerDouble(); const directory = mkdtempSync(resolve(tmpdir(), 'evaluation-rehearsal-failed-read-'));
  t.after(async () => { await docker.close(); rmSync(directory, { recursive: true, force: true }); });
  docker.hook(async args => { if (args.includes(evaluationPendingPageProgram)) throw new Error('fixture read failed'); });
  await assert.rejects(executeEvaluationSlotRehearsal({ smoke: smokeReceipt(docker), directory, scenario, readOnlyIdentity: true }, { run: docker.run }), /REHEARSAL_FAILED/u);
  const result = JSON.parse(readFileSync(resolve(directory, 'evaluation-slot-rehearsal.json')));
  assert.equal(result.status, 'FAILED'); assert.equal(result.cleanup.status, 'PASSED');
  assert.equal(docker.containers.size, 0); assert.equal(docker.networks.size, 0);
});


// Integration regressions for merging the uploaded generation binding with API dispatch.
test('actual adapter generation, not reset challenge, reaches both trusted binding and transport', async () => {
  const generations = { 'slot-a': 'a'.repeat(32), 'slot-b': 'b'.repeat(32) };
  const f = sessions({ resetSlot: ({ slotId, resetNonce }) => ({ slotId, resetNonce, generation: generations[slotId], clean: true }) });
  await f.ready(); const a = f.enter(); const b = f.enter();
  await f.controller.readPending(a.token, a.session.csrfToken);
  await f.controller.readPending(b.token, b.session.csrfToken);
  assert.equal(f.controller.businessBinding(a.token).generation, generations['slot-a']);
  assert.equal(f.controller.businessBinding(b.token).generation, generations['slot-b']);
  assert.deepEqual(f.requests.map(request => request.generation), Object.values(generations));
  assert.ok(f.requests.every(request => !Object.hasOwn(request, 'resetNonce')));
  f.controller.disable();
});

test('every actor rotation changes revision, even selecting the same actor or rotating back', async () => {
  const f = sessions(); await f.ready(); let a = f.enter(); const b = f.enter();
  const other = f.controller.businessBinding(b.token); const first = f.controller.businessBinding(a.token);
  let previous = first;
  for (const actor of ['demo-employee', 'demo-manager', 'demo-employee']) {
    const oldToken = a.token;
    a = f.controller.changeActor(a.token, a.session.csrfToken, actor);
    const current = f.controller.businessBinding(a.token);
    assert.ok(current.sessionRevision > previous.sessionRevision);
    assert.equal(current.generation, first.generation); assert.equal(current.expiresAt, first.expiresAt);
    assert.throws(() => f.controller.businessBinding(oldToken), /SESSION_REQUIRED/u);
    assert.deepEqual(f.controller.businessBinding(b.token), other); previous = current;
  }
  assert.equal(previous.actorId, first.actorId); assert.notEqual(previous.sessionRevision, first.sessionRevision);
  f.controller.disable();
});

for (const invalid of [undefined, null, '', '0'.repeat(32), 'A'.repeat(32), 'a'.repeat(31), 'a'.repeat(33), 'reused']) {
  test(`strict reset generation rejects ${String(invalid)} without releasing or altering the other slot`, async () => {
    let fault = false; const generations = { 'slot-a': 'a'.repeat(32), 'slot-b': 'b'.repeat(32) };
    const f = sessions({ resetSlot: ({ slotId, resetNonce }) => {
      const ack = { slotId, resetNonce, generation: generations[slotId], clean: true };
      if (fault && slotId === 'slot-a') {
        if (invalid === undefined) delete ack.generation;
        else ack.generation = invalid === 'reused' ? generations[slotId] : invalid;
      }
      return ack;
    } });
    await f.ready(); const a = f.enter(); const b = f.enter(); const other = f.controller.businessBinding(b.token);
    fault = true; await assert.rejects(f.controller.end(a.token, a.session.csrfToken), /RESET_FAILED/u);
    assert.equal(f.controller.snapshot().slots[0].state, 'QUARANTINED');
    assert.throws(() => f.controller.businessBinding(a.token), /SESSION_REQUIRED/u);
    assert.throws(() => f.enter(), /NO_EVALUATION_SLOT/u);
    assert.deepEqual(f.controller.businessBinding(b.token), other); f.controller.disable();
  });
}

test('late failure of an old-generation write cannot lock a freshly reset business session', async () => {
  let failOld; let started; const dispatched = new Promise(done => { started = done; });
  const f = sessions({ dispatchBusiness: () => { started(); return new Promise((unused, fail) => { failOld = fail; }); } });
  await f.ready(); const a = f.enter();
  const request = { method: 'POST', target: '/api/approval/tasks/11111111-1111-4111-8111-111111111111/approve',
    body: Buffer.from('{"comment":"approve"}'), contentType: 'application/json', idempotencyKey: 'integration-approve' };
  const pending = f.controller.dispatchBusiness(a.token, a.session.csrfToken, request);
  const rejected = assert.rejects(pending, /UNCERTAIN|REVOKED|REQUIRED/u); await dispatched;
  await f.controller.reset('slot-a'); await rejected; const replacement = f.enter();
  failOld(new Error('late old transport failure')); await settle();
  // Changing actor is allowed only while the replacement has no pending/uncertain write.
  const rotated = f.controller.changeActor(replacement.token, replacement.session.csrfToken, 'demo-manager');
  assert.equal(rotated.session.actorId, 'demo-manager'); f.controller.disable();
});
