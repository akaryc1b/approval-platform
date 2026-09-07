import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { readFileSync, mkdtempSync, rmSync, writeFileSync, chmodSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { createEvaluationSessions } from '../product-readiness/online-demo/evaluation-sessions.mjs';
import { executeEvaluationSlotRehearsal } from '../product-readiness/online-demo/evaluation-slot-rehearsal.mjs';
import test from 'node:test';
import { createEvaluationDockerSlots, evaluationSlotIds, runEvaluationDocker }
  from '../product-readiness/online-demo/evaluation-docker-slots.mjs';

const h = char => char.repeat(64);
const iid = char => `sha256:${h(char)}`;
const label = 'io.approval.evaluation';
const nonce = () => randomBytes(32).toString('base64url');
function inputs() {
  return { source: { commitSha: 'a'.repeat(40), treeSha: 'b'.repeat(40), version: '0.1.0-SNAPSHOT' },
    backendImage: { component: 'backend', localImageId: iid('1'), user: '10001:10001' }, archiveSha256: h('2'),
    infrastructure: { postgres: { pin: `postgres:16@sha256:${h('3')}`, localImageId: iid('3') },
      redis: { pin: `redis:7@sha256:${h('4')}`, localImageId: iid('4') },
      probe: { pin: `node:22-bookworm@sha256:${h('5')}`, localImageId: iid('5') } } };
}
const take = (args, name) => args[args.indexOf(name) + 1];
const many = (args, name) => args.flatMap((arg, index) => arg === name ? [args[index + 1]] : []);
const memory = value => Number(value.slice(0, -1)) * (value.endsWith('g') ? 1024 ** 3 : 1024 ** 2);

// Docker is replaced here. The separate CI rehearsal invokes the real local engine.
function fixture(t, overrides = {}) {
  const config = inputs(); const calls = []; const receipts = []; const containers = new Map(); const networks = new Map();
  let sequence = 0; let engineId = 'local-test-engine'; let failure; let transform = value => value; let recordFailure = false;
  const id = () => (++sequence).toString(16).padStart(64, '0');
  const find = (map, ref) => [...map.values()].find(value => value.Id === ref || value.Name === ref || value.Name === `/${ref}`);
  const networkValue = value => ({ ...value, Containers: Object.fromEntries([...containers.values()]
    .filter(container => container.HostConfig.NetworkMode === value.Name).map(container => [container.Id, {}])) });
  const run = async (args, options = {}) => {
    calls.push({ args, options });
    if (failure) await failure(args, options);
    if (args[0] === 'info') return JSON.stringify({ OSType: 'linux', ID: engineId });
    if (args[0] === 'image') {
      if (args[2] === config.backendImage.localImageId) return JSON.stringify([transform({ Id: iid('1'), Os: 'linux', Architecture: 'amd64',
        Config: { User: '10001:10001', Labels: { 'org.opencontainers.image.revision': config.source.commitSha,
          'org.opencontainers.image.version': config.source.version, 'io.approval.source.tree': config.source.treeSha,
          'io.approval.source.archive': config.archiveSha256, 'io.approval.component': 'backend' } } })]);
      const image = Object.values(config.infrastructure).find(value => value.pin === args[2]);
      return JSON.stringify([{ Id: image.localImageId, Os: 'linux', Architecture: 'amd64' }]);
    }
    if (args[0] === 'network') {
      if (args[1] === 'create') {
        const value = { Id: id(), Name: args.at(-1), Internal: args.includes('--internal'),
          Labels: Object.fromEntries(many(args, '--label').map(value => value.split('='))) };
        networks.set(value.Id, value); return value.Id;
      }
      if (args[1] === 'inspect') return JSON.stringify([transform(networkValue(find(networks, args[2])))]);
      if (args[1] === 'ls') {
        const query = take(args, '--filter');
        return [...networks.values()].filter(value => query.startsWith('id=') ? value.Id === query.slice(3)
          : value.Name === query.slice(6, -1)).map(value => value.Id).join('\n');
      }
      if (args[1] === 'rm') { assert.equal(Object.keys(networkValue(find(networks, args[2])).Containers).length, 0); networks.delete(args[2]); return ''; }
    }
    if (args[0] === 'create') {
      const values = Object.fromEntries(many(args, '--label').map(value => value.split('=')));
      const role = values[`${label}.role`]; const network = take(args, '--network');
      const value = { Id: id(), Name: `/${take(args, '--name')}`, Image: role === 'backend' ? iid('1') : config.infrastructure[role].localImageId,
        Config: { Labels: values, User: role === 'backend' ? '10001:10001' : role === 'probe' ? '1000:1000' : '', Env: many(args, '--env') },
        State: { Running: false }, fixtureData: {}, Mounts: [], HostConfig: { NetworkMode: network, Privileged: false,
          PortBindings: {}, PublishAllPorts: false, Memory: memory(take(args, '--memory')), NanoCpus: Number(take(args, '--cpus')) * 1e9,
          PidsLimit: Number(take(args, '--pids-limit')), RestartPolicy: { Name: take(args, '--restart') },
          ReadonlyRootfs: args.includes('--read-only'), CapDrop: many(args, '--cap-drop'), SecurityOpt: many(args, '--security-opt'),
          Tmpfs: Object.fromEntries(many(args, '--tmpfs').map(value => [value.split(':')[0], value.split(':')[1]])) },
        NetworkSettings: { Ports: {}, Networks: network.startsWith('container:') ? {} : { [network]: { IPAddress: `172.25.${values[`${label}.slot`] === 'slot-a' ? 1 : 2}.${role === 'postgres' ? 2 : role === 'redis' ? 3 : 4}` } } } };
      containers.set(value.Id, value); return value.Id;
    }
    if (args[0] === 'start') { find(containers, args[1]).State.Running = true; return args[1]; }
    if (args[0] === 'container') {
      if (args[1] === 'inspect') return JSON.stringify([transform(find(containers, args[2]))]);
      if (args[1] === 'ls') {
        const query = take(args, '--filter');
        return [...containers.values()].filter(value => query.startsWith('id=') ? value.Id === query.slice(3)
          : value.Name === query.slice(6, -1)).map(value => value.Id).join('\n');
      }
      if (args[1] === 'rm') { containers.delete(args.at(-1)); return ''; }
    }
    if (args[0] === 'exec') {
      const current = find(containers, args[1]); const data = current.fixtureData;
      if (args.includes('pg_isready')) return 'accepting connections';
      if (args.includes('psql')) {
        const query = args.at(-1);
        if (query.startsWith('select to_regclass')) return data.database ? 'f' : 't';
        if (query === 'select value from evaluation_reset_probe;') return data.database || '';
        const value = query.match(/values \('([0-9a-f]{32})'\);$/u)?.[1];
        assert.ok(value); data.database = value; return '';
      }
      if (args.includes('redis-cli')) {
        if (args.at(-1) === 'ping') return 'PONG';
        if (args.includes('SET')) { data.cache = args.at(-1); return 'OK'; }
        if (args.includes('EXISTS')) return data.cache ? '1' : '0';
        if (args.includes('GET')) return data.cache || '';
      }
      if (args.includes('sh')) {
        if (args.some(value => value.includes('app.jar.sha256'))) return 'app.jar: OK';
        if (args.some(value => value.startsWith('test ! -e'))) { assert.equal(data.file, undefined); return 'CLEAN'; }
        data.file = args.at(-1); return '';
      }
      if (args.includes('cat')) return data.file || '';
      if (args.some(value => value.includes("from 'node:net'"))) {
        const host = args.at(-2);
        const destination = [...containers.values()].find(value => Object.values(value.NetworkSettings.Networks)
          .some(network => network.IPAddress === host));
        const backend = find(containers, current.HostConfig.NetworkMode.slice('container:'.length));
        return destination && destination.HostConfig.NetworkMode === backend.HostConfig.NetworkMode ? 'CONNECTED' : 'BLOCKED';
      }
      return 'UP';
    }
    throw new Error(`unexpected test Docker command: ${args[0]} ${args[1]}`);
  };
  const adapter = createEvaluationDockerSlots({ ...config, record: value => { if (recordFailure) throw new Error('disk failed'); receipts.push(value); }, run, ...overrides });
  t.after(async () => { failure = undefined; transform = value => value; recordFailure = false; await adapter.dispose(); });
  return { adapter, config, calls, receipts, containers, networks, run,
    fail: value => { failure = value; }, setEngine: value => { engineId = value; }, transform: value => { transform = value; }, failRecord: () => { recordFailure = true; },
    reset: (slotId = 'slot-a', signal = new AbortController().signal) => adapter.resetSlot({ slotId, resetNonce: nonce(), signal }) };
}

test('two slots receive different resources and reset removes only the selected generation', async t => {
  const f = fixture(t); await f.reset(); await f.reset('slot-b');
  const before = f.adapter.snapshot();
  assert.equal(before.slots.length, 2); assert.equal(f.containers.size, 8); assert.equal(f.networks.size, 2);
  const [a, b] = before.slots; assert.notEqual(a.generation, b.generation); assert.notEqual(a.network.id, b.network.id);
  const receipt = await f.reset(); assert.equal(receipt.clean, true);
  const after = f.adapter.snapshot(); assert.notEqual(after.slots[0].generation, a.generation);
  assert.deepEqual(after.slots[1], b);
  for (const value of Object.values(a.containers)) assert.equal(f.containers.has(value.id), false);
  assert.equal(f.networks.has(a.network.id), false);
  assert.equal(f.containers.size, 8); assert.equal(f.networks.size, 2);
  assert.equal((await f.adapter.dispose()).status, 'PASSED');
  assert.equal(f.containers.size, 0); assert.equal(f.networks.size, 0);
});
test('preflight rejects invalid inputs before recording or Docker access', () => {
  for (const mutate of [v => { v.source.commitSha = 'main'; }, v => { v.source.treeSha = '0'.repeat(40); },
    v => { v.archiveSha256 = ''; }, v => { v.backendImage.user = '0'; }, v => { v.infrastructure.postgres.pin = 'postgres:16'; },
    v => { v.infrastructure.redis.pin = `redis:latest@sha256:${h('4')}`; }, v => { v.infrastructure.probe.pin = `foreign/node@sha256:${h('5')}`; },
    v => { v.infrastructure.probe.localImageId = 'sha256:' + h('0'); }, v => { v.infrastructure.other = {}; }]) {
    const value = inputs(); mutate(value);
    assert.throws(() => createEvaluationDockerSlots({ ...value, record: () => assert.fail('must not record'), run: () => assert.fail('must not run') }));
  }
  assert.throws(() => createEvaluationDockerSlots(inputs()), /RECORDER/u);
});
for (const invalid of ['unknown', '../slot-a', '', null]) {
  test(`rejects unknown slot ${String(invalid)}`, t => assert.throws(() => fixture(t).reset(invalid), /SLOT_UNAVAILABLE/u));
}
test('invalid or cancelled reset request never touches Docker', t => {
  const f = fixture(t); const controller = new AbortController(); controller.abort();
  for (const value of [{}, { slotId: 'slot-a', resetNonce: nonce(), signal: controller.signal },
    { slotId: 'slot-a', resetNonce: 'not-a-token', signal: new AbortController().signal }]) assert.throws(() => f.adapter.resetSlot(value));
  assert.equal(f.calls.length, 0);
});
test('same-slot overlap is rejected while the other slot can initialize independently', async t => {
  const f = fixture(t); let release; let entered;
  const pending = new Promise(done => { entered = done; });
  f.fail(async args => { if (args[0] === 'create' && take(args, '--name').endsWith('slot-a-postgres')) { entered(); await new Promise(done => { release = done; }); } });
  const a = f.reset(); await pending;
  assert.throws(() => f.reset(), /SLOT_UNAVAILABLE/u);
  await f.reset('slot-b'); release(); await a;
  assert.ok(f.adapter.snapshot().slots.every(value => value.state === 'READY'));
});
for (const field of ['org.opencontainers.image.revision', 'io.approval.source.tree', 'io.approval.source.archive', 'io.approval.component']) {
  test(`mismatched backend label fails before resources are created: ${field}`, async t => {
    const f = fixture(t); f.transform(value => {
      const result = structuredClone(value); if (result.Config?.Labels?.[field]) result.Config.Labels[field] = 'wrong'; return result;
    });
    await assert.rejects(f.reset(), /RESET_FAILED/u); assert.equal(f.containers.size, 0); assert.equal(f.networks.size, 0);
  });
}
for (const mutate of [v => { v.Config.User = '0'; }, v => { v.HostConfig.ReadonlyRootfs = false; },
  v => { v.HostConfig.CapAdd = ['SYS_ADMIN']; }, v => { v.HostConfig.CapDrop = []; }, v => { v.HostConfig.SecurityOpt = []; },
  v => { v.HostConfig.PortBindings = { '8080/tcp': [{ HostPort: '8080' }] }; }, v => { v.HostConfig.Privileged = true; },
  v => { v.Mounts = [{ Type: 'volume' }]; }, v => { v.HostConfig.Memory = 0; }, v => { v.HostConfig.NanoCpus = 0; },
  v => { v.NetworkSettings.Networks.extra = {}; }, v => { v.HostConfig.Tmpfs['/extra'] = 'rw'; },
  v => { v.Config.Env = []; }, v => { v.State.Running = false; }]) {
  test(`inspection rejects unsafe backend: ${mutate.toString().slice(7, 65)}`, async t => {
    const f = fixture(t); f.transform(value => {
      const result = structuredClone(value); if (result.HostConfig && result.Config.Labels[`${label}.role`] === 'backend') mutate(result); return result;
    });
    await assert.rejects(f.reset(), /RESET_FAILED/u); assert.equal(f.adapter.snapshot().slots[0].state, 'QUARANTINED');
  });
}
test('an uncertain Docker mutation is permanently quarantined, never a successful retry', async t => {
  const f = fixture(t); f.fail(args => { if (args[0] === 'start') throw new Error('private daemon diagnostic'); });
  await assert.rejects(f.reset(), error => error.message === 'EVALUATION_SLOT_RESET_FAILED');
  assert.equal(f.adapter.snapshot().slots[0].uncertainMutation, true);
  f.fail(undefined); assert.throws(() => f.reset(), /SLOT_UNAVAILABLE/u);
  assert.equal((await f.adapter.dispose()).status, 'FAILED'); assert.equal(f.containers.size, 0);
});
test('cleanup refuses mismatched owner, slot, generation or resource IDs', async t => {
  for (const key of ['owner', 'slot', 'generation', 'role']) {
    const f = fixture(t); await f.reset(); const old = f.adapter.snapshot().slots[0].containers.backend.id;
    f.containers.get(old).Config.Labels[`${label}.${key}`] = 'foreign';
    const result = await f.adapter.dispose(); assert.equal(result.status, 'FAILED');
    assert.equal(f.containers.has(old), true);
    assert.equal(f.calls.some(({ args }) => args[0] === 'container' && args[1] === 'rm' && args.at(-1) === old), false);
  }
});
test('failure removing one resource does not skip the other slot cleanup', async t => {
  const f = fixture(t); await f.reset(); await f.reset('slot-b');
  const old = f.adapter.snapshot().slots[0].containers.backend.id;
  f.fail(args => { if (args[0] === 'container' && args[1] === 'rm' && args.at(-1) === old) throw new Error('removal failed'); });
  const result = await f.adapter.dispose(); assert.equal(result.status, 'FAILED');
  assert.equal(f.containers.size, 1); assert.equal(f.containers.has(old), true);
  assert.equal(result.slots[1].status, 'PASSED');
});
test('recorder failure cannot acknowledge reset or prevent cleanup attempts', async t => {
  const f = fixture(t); await f.reset(); f.failRecord();
  await assert.rejects(f.reset(), /RESET_FAILED/u);
  const result = await f.adapter.dispose(); assert.equal(result.status, 'FAILED'); assert.equal(result.recorded, false);
  assert.equal(f.containers.size, 0); assert.equal(f.networks.size, 0);
});
test('resource evidence excludes credentials and caller mutations cannot alter its internal scope', async t => {
  const f = fixture(t); const value = nonce(); await f.adapter.resetSlot({ slotId: 'slot-a', resetNonce: value, signal: new AbortController().signal });
  const output = JSON.stringify(f.receipts); assert.equal(output.includes(value), false);
  const passwords = f.calls.flatMap(({ args }) => many(args, '--env')).filter(value => /PASSWORD=|REDISCLI_AUTH=/u.test(value)).map(value => value.split('=').at(-1));
  assert.ok(passwords.length >= 4); for (const password of passwords) assert.equal(output.includes(password), false);
  const external = f.adapter.snapshot(); external.slots[0].containers.backend.id = 'wrong'; external.slots[0].state = 'ACTIVE';
  assert.notEqual(f.adapter.snapshot().slots[0].containers.backend.id, 'wrong'); assert.equal(f.adapter.snapshot().slots[0].state, 'READY');
});
test('commands never publish, pull, mount host paths, prune, or enable a local identity profile', async t => {
  const f = fixture(t); await f.reset(); const commands = f.calls.map(value => value.args);
  for (const forbidden of ['--publish', '-p', '--volume', '--mount', '--privileged', 'prune', 'pull', 'push', 'network connect'])
    assert.equal(commands.some(args => args.includes(forbidden)), false, forbidden);
  assert.equal(JSON.stringify(commands).includes('APPROVAL_IDENTITY_MODE=local'), false);
  assert.equal(evaluationSlotIds.length, 2);
});
test('closing fences an in-flight reset and attempts cleanup before resolving', async t => {
  const f = fixture(t); let entered;
  const pending = new Promise(done => { entered = done; });
  f.fail((args, { signal }) => { if (args[0] !== 'start') return; entered(); return new Promise((unused, reject) => signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true })); });
  const work = f.reset(); const rejected = assert.rejects(work, /RESET_FAILED/u); await pending;
  const cleanup = f.adapter.dispose(); await rejected; await cleanup;
  assert.equal(f.adapter.snapshot().closed, true); assert.throws(() => f.reset(), /SLOT_UNAVAILABLE/u);
  assert.equal(f.containers.size, 0);
});
test('runner validates command inputs and timeout before invoking Docker', () => {
  assert.throws(() => runEvaluationDocker(['info', 'bad\0value']), /INVALID/u);
  assert.throws(() => runEvaluationDocker(['info'], { timeoutMs: 0 }), /TIMEOUT/u);
  assert.throws(() => runEvaluationDocker(['info'], { timeoutMs: 60_001 }), /TIMEOUT/u);
});

const testScenario = { schemaVersion: 1, tenant: { id: 'fixture-tenant' },
  directory: { users: [{ id: 'employee', displayName: 'Employee fixture', roleCodes: ['EMPLOYEE'] },
    { id: 'manager', displayName: 'Manager fixture', roleCodes: ['MANAGER'] }] },
  assigneeRules: { initiatorUserId: { value: 'employee' } }, expectedWorkflow: [{ actorIds: ['manager'] }] };
function controller(f, options = {}) {
  return createEvaluationSessions({ scenario: testScenario, slotIds: evaluationSlotIds,
    resetSlot: f.adapter.resetSlot, resetTimeoutMs: 5000, ...options });
}
test('actual session controller uses Docker adapter acknowledgements and revokes before reset', async t => {
  const f = fixture(t); const sessions = controller(f); t.after(() => sessions.disable());
  await sessions.reset('slot-a'); await sessions.reset('slot-b');
  const a = sessions.redeem(sessions.issueInvitation().invitation);
  const b = sessions.redeem(sessions.issueInvitation().invitation);
  const previous = f.adapter.snapshot();
  const work = sessions.end(a.token, a.session.csrfToken);
  assert.throws(() => sessions.status(a.token), /SESSION_REQUIRED/u); await work;
  assert.equal(sessions.status(b.token).actorId, 'employee');
  assert.notEqual(f.adapter.snapshot().slots[0].generation, previous.slots[0].generation);
  assert.deepEqual(f.adapter.snapshot().slots[1], previous.slots[1]);
});
test('adapter cleanup failure leaves controller quarantined without affecting the other evaluator', async t => {
  const f = fixture(t); const sessions = controller(f); t.after(() => sessions.disable());
  await sessions.reset('slot-a'); await sessions.reset('slot-b');
  const a = sessions.redeem(sessions.issueInvitation().invitation);
  const b = sessions.redeem(sessions.issueInvitation().invitation);
  const old = f.adapter.snapshot().slots[0].containers.backend.id;
  f.fail(args => { if (args[0] === 'container' && args[1] === 'rm' && args.at(-1) === old) throw new Error('failed'); });
  await assert.rejects(sessions.end(a.token, a.session.csrfToken), /RESET_FAILED/u);
  assert.throws(() => sessions.status(a.token), /SESSION_REQUIRED/u);
  assert.equal(sessions.snapshot().slots[0].state, 'QUARANTINED');
  assert.equal(sessions.status(b.token).actorId, 'employee');
  assert.throws(() => sessions.redeem(sessions.issueInvitation().invitation), /NO_EVALUATION_SLOT/u);
});
test('controlled expiry invokes real controller and only resets its expired adapter slot', async t => {
  const f = fixture(t); let time = 1000; const sessions = controller(f, { clock: () => time, sessionTtlMs: 1000 });
  t.after(() => sessions.disable());
  await sessions.reset('slot-a'); await sessions.reset('slot-b');
  sessions.redeem(sessions.issueInvitation().invitation); time = 1500;
  const b = sessions.redeem(sessions.issueInvitation().invitation); const before = f.adapter.snapshot();
  time = 2000; assert.deepEqual(await sessions.sweep(), { attempted: 1, failed: 0 });
  assert.equal(sessions.status(b.token).actorId, 'employee');
  assert.notEqual(f.adapter.snapshot().slots[0].generation, before.slots[0].generation);
  assert.deepEqual(f.adapter.snapshot().slots[1], before.slots[1]);
});
function smoke(f) { return { status: 'LOCAL_IMAGE_STARTUP_SMOKE_PASSED', cleanup: { status: 'PASSED' }, source: f.config.source,
  infrastructure: f.config.infrastructure, build: { status: 'LOCAL_IMAGES_BUILT_NOT_RUNTIME_ACCEPTED',
    source: f.config.source, archiveSha256: f.config.archiveSha256, images: [f.config.backendImage] } }; }
test('full rehearsal control flow probes both marker sets and all eight network paths without rebuilding', async t => {
  const f = fixture(t); const directory = mkdtempSync(resolve(tmpdir(), 'slot-rehearsal-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const receipt = await executeEvaluationSlotRehearsal({ smoke: smoke(f), directory, scenario: testScenario }, { run: f.run });
  assert.equal(receipt.status, 'PRIVATE_TWO_SLOT_RESET_REHEARSAL_PASSED');
  assert.equal(receipt.checks.filter(value => value.check === 'OWNED_DATABASE_CACHE_TCP').length, 8);
  assert.equal(receipt.checks.filter(value => value.result === 'BLOCKED').length, 4);
  assert.equal(receipt.controller.metrics.resets, 4); assert.equal(receipt.controller.metrics.expired, 1);
  assert.equal(receipt.cleanup.status, 'PASSED'); assert.equal(f.containers.size, 0); assert.equal(f.networks.size, 0);
  assert.equal(f.calls.some(({ args }) => ['build', 'pull', 'push'].includes(args[0])), false);
  assert.deepEqual(JSON.parse(readFileSync(resolve(directory, 'evaluation-slot-rehearsal.json'))), receipt);
});
for (const kind of ['marker', 'network', 'cleanup']) {
  test(`rehearsal never publishes success after ${kind} failure`, async t => {
    const f = fixture(t); const directory = mkdtempSync(resolve(tmpdir(), 'slot-rehearsal-failed-'));
    t.after(() => rmSync(directory, { recursive: true, force: true }));
    f.fail(args => {
      if (kind === 'marker' && args.at(-1) === 'select value from evaluation_reset_probe;') throw new Error('marker failure');
      if (kind === 'network' && args.some(value => value.includes("from 'node:net'"))) throw new Error('network failure');
      if (kind === 'cleanup' && args[0] === 'container' && args[1] === 'rm') throw new Error('cleanup failure');
    });
    await assert.rejects(executeEvaluationSlotRehearsal({ smoke: smoke(f), directory, scenario: testScenario }, { run: f.run }), /REHEARSAL_FAILED/u);
    const receipt = JSON.parse(readFileSync(resolve(directory, 'evaluation-slot-rehearsal.json')));
    assert.equal(receipt.status, 'FAILED');
    if (kind !== 'cleanup') { assert.equal(f.containers.size, 0); assert.equal(f.networks.size, 0); }
  });
}

test('existing CI entrypoint runs the new rehearsal only after successful image smoke and retains all old tests', () => {
  const entry = readFileSync(new URL('../product-readiness/online-demo-images-runtime.mjs', import.meta.url), 'utf8');
  assert.ok(entry.indexOf('const result = await executeImageRuntime(root)') >= 0);
  assert.ok(entry.indexOf('const slots = await executeEvaluationSlotRehearsal(') > entry.indexOf('const result = await executeImageRuntime(root)'));
  assert.match(entry, /smoke: result\.receipt, directory: result\.directory/u);
  assert.match(entry, /42 \* 60_000/u);
  const tests = readFileSync(new URL('./product-readiness-online-demo-runtime.test.mjs', import.meta.url), 'utf8');
  for (const name of ['runtime-cases.mjs', 'sessions.test.mjs', 'slots.test.mjs']) assert.ok(tests.includes(`product-readiness-online-demo-${name}`));
});


test('the actual child-process runner fixes its engine, strips ambient credentials and never invokes a shell', async t => {
  const directory = mkdtempSync(resolve(tmpdir(), 'evaluation-docker-cli-'));
  const binary = resolve(directory, 'docker');
  writeFileSync(binary, `#!${process.execPath}\n` + "process.stdout.write(JSON.stringify({args:process.argv.slice(2),environment:process.env}));\n");
  chmodSync(binary, 0o700);
  const prior = { PATH: process.env.PATH, DOCKER_HOST: process.env.DOCKER_HOST, APPROVAL_DB_PASSWORD: process.env.APPROVAL_DB_PASSWORD };
  t.after(() => { for (const [key, value] of Object.entries(prior)) { if (value === undefined) delete process.env[key]; else process.env[key] = value; }
    rmSync(directory, { recursive: true, force: true }); });
  process.env.PATH = `${directory}:${prior.PATH}`; process.env.DOCKER_HOST = 'tcp://wrong.invalid:2375';
  process.env.APPROVAL_DB_PASSWORD = 'not-inherited-fixture';
  const value = JSON.parse(await runEvaluationDocker(['info', '; echo not-a-shell']));
  assert.deepEqual(value.args, ['--host', 'unix:///var/run/docker.sock', 'info', '; echo not-a-shell']);
  assert.equal(value.environment.DOCKER_HOST, undefined); assert.equal(value.environment.APPROVAL_DB_PASSWORD, undefined);
});
test('real child failures and aborts are bounded and redact process diagnostics', async t => {
  const directory = mkdtempSync(resolve(tmpdir(), 'evaluation-docker-cli-abort-'));
  const binary = resolve(directory, 'docker');
  writeFileSync(binary, `#!${process.execPath}\n` + "if(process.argv.includes('fail')){process.stderr.write('private-fixture-diagnostic');process.exit(1)}else setInterval(()=>{},1000);\n");
  chmodSync(binary, 0o700); const prior = process.env.PATH;
  t.after(() => { process.env.PATH = prior; rmSync(directory, { recursive: true, force: true }); });
  process.env.PATH = `${directory}:${prior}`;
  await assert.rejects(runEvaluationDocker(['fail']), error => error.message === 'EVALUATION_DOCKER_COMMAND_FAILED');
  await assert.rejects(runEvaluationDocker(['hang'], { timeoutMs: 30 }), /EVALUATION_DOCKER_COMMAND_FAILED/u);
  const signal = new AbortController(); const pending = runEvaluationDocker(['hang'], { signal: signal.signal });
  setTimeout(() => signal.abort(), 30); await assert.rejects(pending, /EVALUATION_DOCKER_COMMAND_FAILED/u);
});
test('a changed Docker engine cannot reset existing resources', async t => {
  const f = fixture(t); await f.reset(); const count = f.calls.length;
  f.setEngine('a-different-docker-engine');
  await assert.rejects(f.reset(), /RESET_FAILED/u);
  assert.equal(f.calls.slice(count).some(({ args }) => args.includes('rm') || args.includes('create')), false);
});
test('not-ready health cannot produce an acknowledgement after the reset deadline', async t => {
  const f = fixture(t, { resetBudgetMs: 100 });
  f.fail(args => { if (args[0] === 'exec' && args.some(value => value.includes('/actuator/health'))) throw new Error('not-ready'); });
  await assert.rejects(f.reset(), /RESET_FAILED/u); assert.equal(f.adapter.snapshot().slots[0].state, 'QUARANTINED');
  assert.equal(f.adapter.snapshot().slots[0].phase, 'BACKEND_READINESS');
});
