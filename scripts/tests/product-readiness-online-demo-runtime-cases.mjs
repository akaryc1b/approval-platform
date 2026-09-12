import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve } from 'node:path';
import test from 'node:test';
import { digest, plan } from '../product-readiness/online-demo/images-contract.mjs';
import { readSource, runCommand } from '../product-readiness/online-demo/images-build.mjs';
import { relevantImageChanges, selectImageRuntimeScope } from '../product-readiness/online-demo/runtime-scope.mjs';
import { cleanupImageRuntime, executeImageRuntime, validateRuntimePins, verifyApplicationContainer } from '../product-readiness/online-demo/images-runtime.mjs';
import { deniedStaticPaths, probeHttp, verifyStaticInventory, verifyStaticResponse } from '../product-readiness/online-demo/runtime-probes.mjs';

const repoRoot = resolve(import.meta.dirname, '../..');
const pins = JSON.parse(readFileSync(resolve(repoRoot, 'deploy/online-demo/images/runtime-images.json'), 'utf8'));
const hex = value => value.repeat(64);
const fakeId = value => `sha256:${hex(value)}`;
const source = { commitSha: 'a'.repeat(40), treeSha: 'b'.repeat(40), epoch: 1234567890 };
const headers = { 'content-type': 'text/html', 'x-content-type-options': 'nosniff',
  'x-frame-options': 'DENY', 'cache-control': 'no-store', 'x-robots-tag': 'noindex, nofollow, noarchive' };
const bytes = { 'index.html': '<html>packaged application fixture</html>',
  'assets/main.js': 'console.log("fixture");', 'assets/main.css': 'body { margin: 0; }' };
function inventory(component = 'pc', identity = source) {
  const files = Object.entries(bytes).map(([path, content]) => ({ path, size: Buffer.byteLength(content), sha256: digest(content) }));
  return { schemaVersion: 1, kind: 'ONLINE_DEMO_STATIC_ARTIFACT_INVENTORY', component, ...identity,
    files, totalBytes: files.reduce((sum, file) => sum + file.size, 0),
    inventorySha256: digest(JSON.stringify(files)), lockSha256: hex('e') };
}
function response(path, status = 200) {
  const content = bytes[path] || '';
  return { status, size: Buffer.byteLength(content), sha256: digest(content), headers: { ...headers,
    'content-type': path.endsWith('.js') ? 'application/javascript' : path.endsWith('.css') ? 'text/css' : 'text/html' } };
}
function fixture(t) {
  const root = mkdtempSync(resolve(tmpdir(), 'online-runtime-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const write = (path, content) => { mkdirSync(dirname(resolve(root, path)), { recursive: true }); writeFileSync(resolve(root, path), content); };
  const git = args => runCommand('git', args, { cwd: root });
  git(['init', '-b', 'main']);
  write('pom.xml', '<project><revision>0.1.0-SNAPSHOT</revision></project>');
  for (const name of ['web', 'mobile']) write(`apps/${name}/upstream.json`, JSON.stringify({ commit: 'd'.repeat(40) }));
  write('deploy/online-demo/images/runtime-images.json', JSON.stringify(pins));
  git(['add', '.']);
  git(['-c', 'user.name=fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-m', 'fixture base']);
  const base = git(['rev-parse', 'HEAD']);
  return { root, git, write, base };
}

for (const path of ['README.md', 'docs/product-readiness/ONLINE_DEMO.md', 'deploy/online-demo/README.md']) {
  test(`documentation-only change skips Docker: ${path}`, () => assert.equal(relevantImageChanges([path]), false));
}
for (const path of ['deploy/online-demo/images/backend.Dockerfile', 'scripts/product-readiness/online-demo-images-runtime.mjs',
  'apps/server/pom.xml', 'apps/mobile/upstream.json', 'apps/web/overlay/view.vue',
  'server-modules/approval-application/src/main/Foo.java', 'pnpm-lock.yaml', 'packages/sdk/src/index.ts',
  '.github/workflows/approval-platform-validation.yml']) {
  test(`image input selects runtime: ${path}`, () => assert.equal(relevantImageChanges([path]), true));
}
for (const paths of [null, [''], ['/etc/passwd'], ['../outside'], ['path\nsecond'], ['a\\b']]) {
  test(`scope rejects malformed paths ${JSON.stringify(paths)}`, () => assert.throws(() => relevantImageChanges(paths), /invalid/u));
}
test('non-CI selection never invokes Git or Docker', () => {
  assert.equal(selectImageRuntimeScope('.', {}, () => { throw new Error('must not run'); }).selected, false);
});
test('real Git histories select exact PR/push input changes and reject stale checkouts', t => {
  const f = fixture(t);
  f.write('apps/server/application.yml', 'fixture'); f.git(['add', '.']);
  f.git(['-c', 'user.name=fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-m', 'image input']);
  const head = f.git(['rev-parse', 'HEAD']);
  const eventPath = resolve(f.root, 'event.json');
  const environment = { GITHUB_ACTIONS: 'true', GITHUB_EVENT_NAME: 'pull_request', GITHUB_EVENT_PATH: eventPath };
  writeFileSync(eventPath, JSON.stringify({ pull_request: { base: { ref: 'main', sha: f.base }, head: { sha: head } } }));
  assert.equal(selectImageRuntimeScope(f.root, environment).selected, true);
  writeFileSync(eventPath, JSON.stringify({ ref: 'refs/heads/main', before: f.base, after: head }));
  assert.equal(selectImageRuntimeScope(f.root, { ...environment, GITHUB_EVENT_NAME: 'push', GITHUB_SHA: head }).selected, true);
  assert.throws(() => selectImageRuntimeScope(f.root, { ...environment, GITHUB_EVENT_NAME: 'push', GITHUB_SHA: f.base }), /mismatch/u);
  writeFileSync(eventPath, JSON.stringify({ pull_request: { base: { ref: 'main', sha: head }, head: { sha: f.base } } }));
  assert.throws(() => selectImageRuntimeScope(f.root, environment), /checkout/u);
});
for (const change of [{ forced: true }, { deleted: true }, { created: true }, { ref: 'refs/tags/v1' }, { before: '0'.repeat(40) }]) {
  test(`invalid push fails closed: ${Object.keys(change)[0]}`, t => {
    const f = fixture(t); const path = resolve(f.root, 'event.json');
    writeFileSync(path, JSON.stringify({ ref: 'refs/heads/main', before: 'a'.repeat(40), after: f.base, ...change }));
    assert.throws(() => selectImageRuntimeScope(f.root, { GITHUB_ACTIONS: 'true', GITHUB_EVENT_NAME: 'push', GITHUB_SHA: f.base, GITHUB_EVENT_PATH: path }));
  });
}

test('runtime infrastructure pins are real versioned digest references', () => {
  assert.equal(Object.keys(validateRuntimePins(pins)).length, 2);
  for (const replacement of ['postgres:16', `postgres:17@sha256:${hex('a')}`, `other:16@sha256:${hex('a')}`, `postgres:16@sha256:${hex('0')}`]) {
    assert.throws(() => validateRuntimePins({ ...pins, images: { ...pins.images, postgres: replacement } }), /pins/u);
  }
});
test('static inventory preserves exact source, hashes and served representative assets', () => {
  const value = inventory();
  assert.equal(verifyStaticInventory(value, source, 'pc').length, 3);
  for (const file of value.files) verifyStaticResponse(response(file.path), 200, file);
});
for (const [field, replacement] of [['commitSha', 'c'.repeat(40)], ['treeSha', 'c'.repeat(40)], ['epoch', 1],
  ['inventorySha256', hex('0')], ['totalBytes', 1], ['lockSha256', 'invalid']]) {
  test(`static inventory rejects changed ${field}`, () => assert.throws(() => verifyStaticInventory({ ...inventory(), [field]: replacement }, source, 'pc')));
}
for (const path of ['../index.html', '.env', '/index.html', 'foo\\bar.js', 'app.js?x', 'a\n.js']) {
  test(`unsafe packaged path fails ${JSON.stringify(path)}`, () => {
    const value = inventory(); value.files[0].path = path;
    assert.throws(() => verifyStaticInventory(value, source, 'pc'));
  });
}
test('static response mismatches cannot become runtime success', () => {
  const file = inventory().files[1];
  assert.throws(() => verifyStaticResponse({ ...response(file.path), sha256: hex('0') }, 200, file), /bytes/u);
  assert.throws(() => verifyStaticResponse({ ...response(file.path), status: 404 }, 200, file), /status/u);
  assert.throws(() => verifyStaticResponse({ ...response(file.path), headers: {} }, 200, file), /headers/u);
  assert.throws(() => verifyStaticResponse({ ...response(file.path), headers: { ...headers } }, 200, file), /JavaScript/u);
});
test('actual loopback HTTP probe hashes bytes, does not follow redirects or expose bodies', async t => {
  const server = createServer((request, result) => {
    if (request.url === '/redirect') { result.writeHead(302, { Location: '/health' }); result.end(); return; }
    if (request.url === '/health') { result.end('{"status":"UP","secret":"not-returned"}'); return; }
    result.writeHead(200, headers); result.end(bytes['index.html']);
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(() => { server.closeAllConnections(); server.close(); });
  const origin = `http://127.0.0.1:${server.address().port}`;
  const html = await probeHttp({ url: origin });
  assert.equal(html.sha256, digest(bytes['index.html']));
  assert.equal(html.body, undefined);
  const health = await probeHttp({ url: `${origin}/health`, health: true });
  assert.equal(health.healthy, true);
  assert.equal(JSON.stringify(health).includes('not-returned'), false);
  assert.equal((await probeHttp({ url: `${origin}/redirect` })).status, 302);
});

// The following orchestration tests substitute Docker. They are not image-build evidence.
function dockerFixture(t, failure) {
  const f = fixture(t);
  const identity = readSource(f.root);
  const imageBases = { maven: `maven@sha256:${hex('a')}`, java: `eclipse-temurin@sha256:${hex('a')}`,
    node: `node@sha256:${hex('a')}`, nginx: `nginx@sha256:${hex('a')}` };
  const buildPlan = plan(identity, { platform: 'linux/amd64', images: imageBases });
  const images = buildPlan.images.map((image, index) => ({ ...image, localImageId: fakeId(String(index + 1)),
    user: index === 0 ? '10001:10001' : '101:101' }));
  const buildReceipt = { status: 'LOCAL_IMAGES_BUILT_NOT_RUNTIME_ACCEPTED', source: identity,
    platform: 'linux/amd64', archiveSha256: hex('c'), baseImages: imageBases, images };
  const containers = new Map(); const calls = []; let network = null; let sequence = 0; let time = 0; let password = '';
  const get = key => [...containers.values()].find(value => value.Id === key || value.Name === key);
  const option = (args, key) => args[args.indexOf(key) + 1];
  const run = (command, args, options) => {
    if (command === 'git') return runCommand(command, args, { ...options, cwd: f.root });
    calls.push(args);
    const key = args.slice(0, 2).join(' ');
    if (args[0] === 'info') { if (failure === 'no-docker') throw new Error('Docker unavailable'); return JSON.stringify({ OSType: 'linux', ServerVersion: 'fixture' }); }
    if (key === 'image inspect') {
      const image = images.find(image => image.localImageId === args[2]);
      if (!image) return JSON.stringify([{ Id: fakeId('8'), Os: 'linux', Architecture: 'amd64' }]);
      return JSON.stringify([{ Id: image.localImageId, Os: 'linux', Architecture: 'amd64', Config: {
        User: image.user, Labels: { 'org.opencontainers.image.revision': identity.commitSha,
          'org.opencontainers.image.version': identity.version, 'io.approval.source.tree': identity.treeSha,
          'io.approval.source.archive': hex('c'), 'io.approval.component': image.component } } }]);
    }
    if (args[0] === 'pull') return '';
    if (key === 'network create') { network = { Id: hex('b'), Name: args.at(-1), Internal: true,
      Labels: { 'io.approval.image-smoke.owner': option(args, '--label').split('=')[1] } }; return network.Id; }
    if (key === 'network inspect') return JSON.stringify([network]);
    if (key === 'network ls') return network ? network.Id : '';
    if (key === 'network rm') { if (containers.size) throw new Error('network still in use'); network = null; return ''; }
    if (args[0] === 'create') {
      const name = option(args, '--name'); const component = name.split('-').at(-1);
      const image = images.find(image => image.component === component);
      const env = args.flatMap((value, index) => value === '--env' ? [args[index + 1]] : []);
      if (component === 'backend') env.push('SERVER_ADDRESS=127.0.0.1');
      password = env.find(value => value.startsWith('POSTGRES_PASSWORD='))?.slice('POSTGRES_PASSWORD='.length) || password;
      const value = { Id: (++sequence).toString(16).padStart(64, '0'), Name: name,
        Image: image?.localImageId || fakeId('8'), State: { Running: false },
        Config: { User: image?.user || '1000:1000', Env: env,
          Labels: { 'io.approval.image-smoke.owner': option(args, '--label').split('=')[1] } },
        HostConfig: { NetworkMode: option(args, '--network'), Privileged: false, ReadonlyRootfs: args.includes('--read-only'),
          CapDrop: ['ALL'], SecurityOpt: ['no-new-privileges'], PortBindings: {} },
        NetworkSettings: { Ports: { '8080/tcp': null }, Networks: { [option(args, '--network')]: {} } }, Mounts: [] };
      containers.set(name, value);
      if (failure === 'uncertain-create' && component === 'backend') throw new Error('create result unavailable');
      return value.Id;
    }
    if (args[0] === 'start') { get(args[1]).State.Running = true; return args[1]; }
    if (key === 'container inspect') return JSON.stringify([get(args[2])]);
    if (key === 'container ls') {
      const filter = option(args, '--filter');
      const values = [...containers.values()].filter(value => filter.startsWith('id=')
        ? value.Id === filter.slice(3) : value.Name === filter.slice('name=^/'.length, -1));
      return values.map(value => value.Id).join('\n');
    }
    if (key === 'container rm') {
      const value = get(args.at(-1));
      if (failure === 'cleanup' && value.Name.endsWith('-pc')) throw new Error('removal failed');
      containers.delete(value.Name); return '';
    }
    if (args[0] === 'logs') return `diagnostic password=${password}`;
    if (args[0] === 'exec') {
      if (args.includes('pg_isready')) return '127.0.0.1:5432 - accepting connections';
      if (args.includes('redis-cli')) return 'PONG';
      if (args.includes('sh')) return failure === 'jar' ? 'wrong' : 'app.jar: OK';
      const component = get(args[1]).Name.split('-').at(-1);
      if (args.includes('cat')) return JSON.stringify(inventory(component, identity));
      if (args.includes('sha256sum')) return `${hex('e')}  /opt/approval/resolved-pnpm-lock.yaml`;
      const request = JSON.parse(args.at(-1)); const url = new URL(request.url);
      if (request.health) return JSON.stringify({ healthy: failure !== 'health', status: failure === 'health' ? 503 : 200 });
      if (url.pathname === '/healthz') return JSON.stringify({ status: 200 });
      const path = decodeURIComponent(url.pathname.slice(1));
      if (Object.hasOwn(bytes, path)) return JSON.stringify(failure === 'asset' && path.endsWith('.js')
        ? { ...response(path), sha256: hex('0') } : response(path));
      return JSON.stringify(response('', request.method === 'POST' ? 405 : 404));
    }
    throw new Error(`unexpected fake Docker call ${key}`);
  };
  const build = () => {
    if (failure === 'build') throw new Error('build failed');
    if (failure === 'deadline') time += 40 * 60_000;
    return { receipt: buildReceipt, directory: resolve(f.root, '.runtime/fixture-build') };
  };
  const options = { run, build, now: () => time, sleep: async ms => { time += ms; } };
  const receipt = () => JSON.parse(readFileSync(resolve(f.root, '.runtime/online-demo-image-runtime',
    readdirSync(resolve(f.root, '.runtime/online-demo-image-runtime'))[0], 'runtime-receipt.json'), 'utf8'));
  return { ...f, options, calls, containers, receipt, password: () => password, network: () => network };
}

test('orchestration builds once, probes all components and removes only its own containers/network', async t => {
  const f = dockerFixture(t);
  const value = await executeImageRuntime(f.root, f.options);
  assert.equal(value.receipt.status, 'LOCAL_IMAGE_STARTUP_SMOKE_PASSED');
  assert.equal(value.receipt.containers.length, 3);
  assert.equal(value.receipt.cleanup.status, 'PASSED');
  assert.equal(f.containers.size, 0); assert.equal(f.network(), null);
  assert.equal(f.calls.some(args => args.includes('--publish') || args.includes('--privileged') || args.includes('--volume')), false);
  assert.equal(f.calls.some(args => args.includes('prune') || args[0] === 'push'), false);
  assert.equal(value.receipt.checks.filter(check => deniedStaticPaths.includes(check.path)).length, deniedStaticPaths.length * 2);
  assert.equal(JSON.stringify(value.receipt).includes(f.password()), false);
});
for (const failure of ['no-docker', 'build', 'uncertain-create', 'jar', 'asset', 'health', 'deadline', 'cleanup']) {
  test(`failure stays FAILED and attempts complete cleanup: ${failure}`, async t => {
    const f = dockerFixture(t, failure);
    await assert.rejects(executeImageRuntime(f.root, f.options), /runtime failed/u);
    assert.equal(f.receipt().status, 'FAILED');
    if (failure === 'cleanup') {
      assert.equal(f.receipt().cleanup.status, 'FAILED');
      assert.equal(f.containers.size, 1);
      assert.ok([...f.containers.keys()][0].endsWith('-pc'));
    } else {
      assert.equal(f.containers.size, 0); assert.equal(f.network(), null);
      assert.equal(f.receipt().cleanup.status, 'PASSED');
    }
    if (f.password()) {
      const root = resolve(f.root, '.runtime/online-demo-image-runtime', readdirSync(resolve(f.root, '.runtime/online-demo-image-runtime'))[0]);
      for (const file of readdirSync(root)) assert.equal(readFileSync(resolve(root, file), 'utf8').includes(f.password()), false);
    }
    if (failure === 'health') assert.ok(f.calls.filter(args => args[0] === 'exec').length < 400);
  });
}
test('cleanup refuses foreign ownership instead of deleting another container', () => {
  const calls = [];
  const value = cleanupImageRuntime({ owner: 'ours', containers: ['ours-name'], network: null }, (command, args) => {
    calls.push(args);
    if (args[1] === 'ls') return hex('1');
    if (args[1] === 'inspect') return JSON.stringify([{ Config: { Labels: { 'io.approval.image-smoke.owner': 'theirs' } } }]);
    throw new Error('must not remove');
  });
  assert.equal(value.status, 'FAILED'); assert.equal(calls.some(args => args[1] === 'rm'), false);
});
test('container inspection rejects root, writable rootfs, host ports and wrong network', () => {
  const image = { component: 'pc', localImageId: fakeId('1'), user: '101:101' };
  const value = { Id: hex('1'), Image: image.localImageId, Config: { User: image.user, Labels: { 'io.approval.image-smoke.owner': 'ours' } },
    State: { Running: true }, HostConfig: { NetworkMode: 'internal', Privileged: false, ReadonlyRootfs: true,
      CapDrop: ['ALL'], SecurityOpt: ['no-new-privileges'], PortBindings: {} }, NetworkSettings: { Ports: { '8080/tcp': null }, Networks: { internal: {} } }, Mounts: [] };
  assert.equal(verifyApplicationContainer(value, image, 'internal', 'ours').readOnlyRootfs, true);
  for (const mutate of [v => { v.Config.User = '0'; }, v => { v.HostConfig.ReadonlyRootfs = false; },
    v => { v.HostConfig.NetworkMode = 'host'; }, v => { v.HostConfig.PortBindings = { '8080/tcp': [{ HostPort: '8080' }] }; },
    v => { v.HostConfig.Privileged = true; }, v => { v.Mounts = [{ Type: 'bind' }]; }]) {
    const changed = structuredClone(value); mutate(changed);
    assert.throws(() => verifyApplicationContainer(changed, image, 'internal', 'ours'));
  }
});
test('real image job is in the existing workflow with bounded timeout, read-only permission and always-retained evidence', () => {
  const workflow = readFileSync(resolve(repoRoot, '.github/workflows/approval-platform-validation.yml'), 'utf8');
  const job = workflow.slice(workflow.indexOf('\n  online-images:'));
  assert.match(job, /timeout-minutes: 45/u);
  assert.match(job, /contents: read/u);
  assert.match(job, /persist-credentials: false/u);
  assert.match(job, /github\.event\.pull_request\.head\.sha \|\| github\.sha/u);
  assert.match(job, /online-demo-images-runtime\.mjs ci/u);
  assert.match(job, /if: always\(\)/u);
  assert.doesNotMatch(job, /secrets\.|continue-on-error|pull_request_target/u);
});
