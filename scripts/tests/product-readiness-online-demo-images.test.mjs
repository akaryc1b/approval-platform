import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve } from 'node:path';
import test from 'node:test';
import { digest, dockerBuildArguments, imageInputs, parseArguments, pinnedImage, plan,
  verifyImageInspection } from '../product-readiness/online-demo/images-contract.mjs';
import { executeImageBuild, processEnvironment, readSource, resolveImageOptions, runCommand,
  validateTreeListing } from '../product-readiness/online-demo/images-build.mjs';
import { collectStaticArtifacts, stageStaticArtifacts } from '../product-readiness/online-demo/static-artifacts.mjs';

const root = resolve(import.meta.dirname, '../..');
const text = path => readFileSync(resolve(root, path), 'utf8');
const source = { commitSha: 'a'.repeat(40), treeSha: 'b'.repeat(40), version: '0.1.0-SNAPSHOT',
  epoch: 1_788_566_400, upstreams: { pc: 'c'.repeat(40), h5: 'd'.repeat(40) } };
const images = { maven: `maven:3.9.16-eclipse-temurin-21@sha256:${'1'.repeat(64)}`,
  java: `eclipse-temurin:21-jre@sha256:${'2'.repeat(64)}`,
  node: `node:22-bookworm@sha256:${'3'.repeat(64)}`,
  nginx: `nginx:stable@sha256:${'4'.repeat(64)}` };
const args = Object.entries(images).flatMap(([name, value]) => [`--${name}-image`, value]);
const options = () => parseArguments(['build', ...args]);
function temporary(t) {
  const directory = mkdtempSync(resolve(tmpdir(), 'approval-images-test-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  return directory;
}
function write(rootPath, path, content) {
  const target = resolve(rootPath, path);
  mkdirSync(dirname(target), { recursive: true });
  writeFileSync(target, content);
  return target;
}
function fixture(t) {
  const directory = temporary(t);
  const git = values => runCommand('git', values, { cwd: directory });
  git(['init', '-q']);
  git(['config', 'user.name', 'Image test']);
  git(['config', 'user.email', 'image-test@example.invalid']);
  write(directory, 'pom.xml', '<project><revision>0.1.0-SNAPSHOT</revision></project>\n');
  write(directory, 'apps/web/upstream.json', JSON.stringify({ commit: source.upstreams.pc }));
  write(directory, 'apps/mobile/upstream.json', JSON.stringify({ commit: source.upstreams.h5 }));
  write(directory, 'README.md', 'source fixture\n');
  write(directory, 'deploy/online-demo/images/base-images.json', text('deploy/online-demo/images/base-images.json'));
  write(directory, '.gitignore', '.runtime/\n');
  git(['add', '.']); git(['commit', '-qm', 'source fixture']);
  return { directory, git };
}
function inspection(buildPlan, image, id, archiveSha) {
  return { Id: id, Os: 'linux', Architecture: buildPlan.platform.split('/')[1],
    Config: { User: image.component === 'backend' ? '10001:10001' : '101:101', Labels: {
      'org.opencontainers.image.revision': buildPlan.source.commitSha,
      'org.opencontainers.image.version': buildPlan.source.version,
      'io.approval.source.tree': buildPlan.source.treeSha,
      'io.approval.source.archive': archiveSha, 'io.approval.component': image.component,
    } } };
}
function dockerDouble(directory, opts, failAt) {
  const buildPlan = plan(readSource(directory), opts);
  const calls = [];
  const id = `sha256:${'9'.repeat(64)}`;
  let current;
  const run = (command, values, settings) => {
    if (command !== 'docker') return runCommand(command, values, settings);
    calls.push({ command, values, settings });
    if (values[0] === 'build') {
      current = buildPlan.images.find(image => image.tag === values[values.indexOf('--tag') + 1]);
      if (current.component === failAt) throw new Error('injected docker failure');
      writeFileSync(values[values.indexOf('--iidfile') + 1], `${id}\n`);
      return '';
    }
    assert.deepEqual(values, ['image', 'inspect', current.tag]);
    return JSON.stringify([inspection(buildPlan, current, id, digest(calls.at(-2).settings.input))]);
  };
  return { calls, run };
}

test('plan is read-only and lists all missing digest inputs', () => {
  const value = plan(source, parseArguments([]));
  assert.equal(value.status, 'INPUTS_REQUIRED');
  assert.deepEqual(value.missingInputs, imageInputs);
  assert.deepEqual(value.images.map(image => image.component), ['backend', 'pc', 'h5']);
  assert.ok(value.nonClaims.includes('ONLINE_DEMO_NOT_AVAILABLE'));
  assert.equal(value.dependencyPolicy.h5, 'EXISTING_NON_FROZEN_INSTALL_RESOLVED_LOCK_RETAINED_IN_IMAGE');
});
test('complete immutable inputs produce deterministic build tags', () => {
  const one = plan(source, options());
  assert.deepEqual(one, plan(structuredClone(source), options()));
  assert.equal(one.missingInputs.length, 0);
  assert.notEqual(one.images[0].tag, plan(source, { ...options(), platform: 'linux/arm64' }).images[0].tag);
  const changed = options(); changed.images.node = images.node.replace('3'.repeat(64), 'a'.repeat(64));
  assert.notEqual(one.images[0].tag, plan(source, changed).images[0].tag);
});
for (const value of ['node:latest', 'node:22', 'https://node@sha256:' + '3'.repeat(64),
  'node@sha256:' + '0'.repeat(64), 'attacker/node@sha256:' + '3'.repeat(64),
  'node@sha256:1234', 'node@sha256:' + 'A'.repeat(64), images.node + '\n--push']) {
  test(`rejects unpinned or unapproved image ${value.slice(0, 30)}`, () => {
    assert.throws(() => pinnedImage(value, 'node'), /explicit sha256 digest/u);
  });
}
for (const argv of [['push'], ['build', '--json'], ['plan', '--push'], ['plan', '--platform', 'windows/amd64'],
  ['plan', '--java-image'], ['plan', '--json', '--json'], ['build', ...args, '--node-image', images.node],
  ['plan', '--platform=linux/amd64']]) {
  test(`rejects unsupported or ambiguous CLI ${argv.slice(0, 2).join(' ')}`, () => {
    assert.throws(() => parseArguments(argv));
  });
}
test('generated build commands use archive stdin and never push, run or mount host directories', () => {
  const buildPlan = plan(source, options());
  for (const image of buildPlan.images) {
    const values = dockerBuildArguments(buildPlan, image, '7'.repeat(64), '/tmp/result.iid');
    assert.equal(values.at(-1), '-');
    assert.equal(values[values.indexOf('--target') + 1], image.component);
    assert.ok(values.includes(`SOURCE_COMMIT=${source.commitSha}`));
    for (const flag of ['--push', '--secret', '--ssh', '--mount', '--privileged']) assert.ok(!values.includes(flag));
  }
});
test('inspection rejects wrong image, platform, runtime user or any source label', () => {
  const buildPlan = plan(source, options()); const image = buildPlan.images[0];
  const id = `sha256:${'9'.repeat(64)}`; const hash = '7'.repeat(64);
  const base = inspection(buildPlan, image, id, hash);
  const result = verifyImageInspection(base, buildPlan, image, id, hash);
  assert.equal(result.registryDigest, null); assert.equal(result.localImageId, id);
  for (const mutate of [value => { value.Id = 'sha256:' + '8'.repeat(64); },
    value => { value.Architecture = 'arm64'; }, value => { value.Config.User = '0'; },
    ...Object.keys(base.Config.Labels).map(key => value => { value.Config.Labels[key] = 'wrong'; })]) {
    const changed = structuredClone(base); mutate(changed);
    assert.throws(() => verifyImageInspection(changed, buildPlan, image, id, hash));
  }
});
test('source snapshot uses a real committed Git tree and does not change refs', t => {
  const { directory, git } = fixture(t);
  const before = git(['rev-parse', 'HEAD']);
  const value = readSource(directory);
  assert.equal(value.commitSha, before); assert.equal(value.treeSha, git(['rev-parse', 'HEAD^{tree}']));
  assert.deepEqual(value.upstreams, source.upstreams);
  assert.equal(git(['rev-parse', 'HEAD']), before);
  assert.equal(existsSync(resolve(directory, '.runtime')), false);
});
test('dirty tracked source fails before building or creating output', t => {
  const { directory } = fixture(t); write(directory, 'README.md', 'not committed');
  assert.throws(() => executeImageBuild(directory, options()), /commit tracked source/u);
  assert.equal(existsSync(resolve(directory, '.runtime')), false);
});
test('archive validation rejects symlinks, submodules and escaping paths', () => {
  const sha = 'a'.repeat(40);
  validateTreeListing(`100644 blob ${sha}\ta/b.mjs\0`);
  for (const value of [`120000 blob ${sha}\tlink\0`, `160000 commit ${sha}\tmodule\0`,
    `100644 blob ${sha}\t../escape\0`, `100644 blob ${sha}\t.runtime/secret\0`,
    `100644 blob ${sha}\ta\\b\0`, `100644 blob ${sha}\tline\nbreak\0`, '']) {
    assert.throws(() => validateTreeListing(value));
  }
});
test('real archive excludes untracked secret files and all three builds use identical bytes', t => {
  const { directory } = fixture(t);
  write(directory, '.env', 'SECRET_NOT_FOR_IMAGE=private\n');
  const fake = dockerDouble(directory, options());
  const { receipt, directory: output } = executeImageBuild(directory, options(), fake.run);
  assert.equal(receipt.status, 'LOCAL_IMAGES_BUILT_NOT_RUNTIME_ACCEPTED');
  assert.equal(receipt.images.length, 3);
  const builds = fake.calls.filter(call => call.values[0] === 'build');
  assert.equal(builds.length, 3);
  for (const call of builds) {
    assert.ok(Buffer.isBuffer(call.settings.input));
    assert.equal(call.settings.input.includes(Buffer.from('SECRET_NOT_FOR_IMAGE')), false);
    assert.equal(digest(call.settings.input), receipt.archiveSha256);
  }
  const list = spawnSync('tar', ['-tf', '-'], { input: builds[0].settings.input, encoding: 'utf8' });
  assert.equal(list.status, 0, list.stderr);
  assert.ok(list.stdout.includes('pom.xml')); assert.ok(!list.stdout.split('\n').includes('.env'));
  assert.deepEqual(JSON.parse(readFileSync(resolve(output, 'image-build.json'), 'utf8')), receipt);
  assert.ok(!readdirSync(output).some(file => file.endsWith('.iid')));
});
test('a later Docker failure retains a FAILED partial receipt and stops subsequent builds', t => {
  const { directory } = fixture(t); const fake = dockerDouble(directory, options(), 'pc');
  assert.throws(() => executeImageBuild(directory, options(), fake.run), /injected docker failure/u);
  const folder = resolve(directory, '.runtime/online-demo-images');
  const receipt = JSON.parse(readFileSync(resolve(folder, readdirSync(folder)[0], 'image-build.json')));
  assert.equal(receipt.status, 'FAILED'); assert.equal(receipt.failedComponent, 'pc');
  assert.equal(receipt.images.length, 1); assert.equal(receipt.partialImagesRetained, true);
  assert.equal(fake.calls.filter(call => call.values[0] === 'build').length, 2);
});
test('no fake success when the image ID file is missing', t => {
  const { directory } = fixture(t);
  const run = (command, values, settings) => command === 'docker' ? '' : runCommand(command, values, settings);
  assert.throws(() => executeImageBuild(directory, options(), run), /ENOENT/u);
  const folder = resolve(directory, '.runtime/online-demo-images');
  const receipt = JSON.parse(readFileSync(resolve(folder, readdirSync(folder)[0], 'image-build.json')));
  assert.equal(receipt.status, 'FAILED'); assert.equal(receipt.images.length, 0);
});
test('output cannot be redirected through a symbolic link', t => {
  const { directory } = fixture(t); const outside = temporary(t);
  symlinkSync(outside, resolve(directory, '.runtime'), 'dir');
  const fake = dockerDouble(directory, options());
  assert.throws(() => executeImageBuild(directory, options(), fake.run), /real directory/u);
  assert.equal(fake.calls.length, 0);
});
test('child command environment strips application, frontend, Git and proxy credentials', () => {
  const value = processEnvironment({ PATH: '/bin', HOME: '/home/test', VITE_SECRET: 'secret',
    GITHUB_TOKEN: 'secret', JAVA_TOOL_OPTIONS: 'unsafe', NODE_OPTIONS: 'unsafe',
    HTTP_PROXY: 'http://user:secret@proxy', APPROVAL_GENERIC_SECRET: 'secret' });
  assert.deepEqual(value, { PATH: '/bin', HOME: '/home/test' });
});

function assets(t) {
  const directory = temporary(t); const dist = resolve(directory, 'dist');
  write(dist, 'index.html', '<html><script src="/assets/app.js"></script></html>');
  write(dist, 'assets/app.js', 'console.log("fixture");');
  const lock = write(directory, 'pnpm-lock.yaml', 'lockfileVersion: 9.0\n');
  return { directory, dist, lock };
}
test('static inventory and staged bytes are deterministic and retain the actual resolved lock outside public files', t => {
  const { directory, dist, lock } = assets(t);
  const expected = collectStaticArtifacts(dist);
  const metadata = stageStaticArtifacts('h5', dist, lock, resolve(directory, 'out'), source);
  assert.deepEqual(metadata.files, expected.files);
  assert.equal(metadata.lockSha256, digest(readFileSync(lock)));
  assert.equal(metadata.dependencyResolution, 'NON_FROZEN_RESOLVED_LOCK');
  for (const item of metadata.files) {
    assert.equal(digest(readFileSync(resolve(directory, 'out/public', item.path))), item.sha256);
  }
  assert.ok(!existsSync(resolve(directory, 'out/public/resolved-pnpm-lock.yaml')));
  assert.equal(readFileSync(resolve(directory, 'out/resolved-pnpm-lock.yaml'), 'utf8'), readFileSync(lock, 'utf8'));
  assert.throws(() => stageStaticArtifacts('h5', dist, lock, resolve(directory, 'out'), source), /EEXIST/u);
});
for (const path of ['.env', '.git/config', 'app.js.map', 'app.js.map.gz', 'private.pem',
  'database.dump', 'node_modules/package.json', 'package.json']) {
  test(`static packaging rejects ${path} before creating output`, t => {
    const { directory, dist, lock } = assets(t); write(dist, path, 'do not ship');
    assert.throws(() => stageStaticArtifacts('pc', dist, lock, resolve(directory, 'out'), source));
    assert.equal(existsSync(resolve(directory, 'out')), false);
  });
}
test('static packaging rejects linked files and linked roots', t => {
  const { directory, dist } = assets(t);
  symlinkSync(resolve(dist, 'index.html'), resolve(dist, 'linked.html'));
  assert.throws(() => collectStaticArtifacts(dist), /symbolic links/u);
  symlinkSync(dist, resolve(directory, 'root-link'), 'dir');
  assert.throws(() => collectStaticArtifacts(resolve(directory, 'root-link')), /symbolic link/u);
});
test('static packaging requires index.html and a nonempty regular lockfile', t => {
  const { directory, dist, lock } = assets(t);
  writeFileSync(lock, '');
  assert.throws(() => stageStaticArtifacts('pc', dist, lock, resolve(directory, 'out'), source), /bounded regular/u);
  rmSync(resolve(dist, 'index.html'));
  assert.throws(() => collectStaticArtifacts(dist), /missing index.html/u);
});

test('executable JAR verifier runs the real jar tool on good and thin archive fixtures', t => {
  const directory = temporary(t);
  const content = resolve(directory, 'content');
  write(content, 'BOOT-INF/classes/demo/purchase-payment-golden-path.json', '{}');
  write(content, 'BOOT-INF/classes/demo/purchase-payment-demo-seed.json', '{}');
  write(content, 'BOOT-INF/lib/library.jar', 'layout fixture, not an executable dependency');
  const manifest = write(directory, 'MANIFEST.MF', 'Manifest-Version: 1.0\nMain-Class: org.springframework.boot.loader.launch.JarLauncher\nStart-Class: example.App\n\n');
  const file = resolve(directory, 'app.jar');
  const jar = spawnSync('jar', ['--create', '--file', file, '--manifest', manifest, '-C', content, '.'], { encoding: 'utf8' });
  assert.equal(jar.status, 0, jar.error?.message || jar.stderr);
  const verify = () => spawnSync('sh', [resolve(root, 'deploy/online-demo/images/verify-boot-jar.sh'), file], { encoding: 'utf8' });
  assert.equal(verify().status, 0);
  rmSync(resolve(content, 'BOOT-INF/lib/library.jar'));
  const thin = spawnSync('jar', ['--create', '--file', file, '--manifest', manifest, '-C', content, '.'], { encoding: 'utf8' });
  assert.equal(thin.status, 0); assert.notEqual(verify().status, 0);
});
test('image recipes use the current product build paths without creating another backend or local identity profile', () => {
  const backend = text('deploy/online-demo/images/backend.Dockerfile');
  const clients = text('deploy/online-demo/images/clients.Dockerfile');
  assert.match(backend, /-Pproduct-readiness-demo/u);
  assert.match(backend, /package spring-boot:repackage/u);
  assert.match(backend, /verify-boot-jar\.sh/u);
  assert.match(backend, /USER 10001:10001/u);
  assert.match(backend, /SERVER_ADDRESS=127\.0\.0\.1/u);
  assert.doesNotMatch(backend, /spring-boot:run|PROFILES_ACTIVE=local/u);
  assert.equal([...clients.matchAll(/node scripts\/upstream\/bootstrap-unibest\.mjs/gu)].length, 1);
  assert.match(clients, /VITE_APPROVAL_LOCAL_DEMO=false/u);
  assert.doesNotMatch(clients, /VITE_APPROVAL_(?:TENANT_ID|OPERATOR_ID)=/u);
  assert.match(clients, /install --frozen-lockfile/u);
  assert.match(clients, /NON_FROZEN|NOT a claim of bit-reproducible/u);
  assert.match(clients, /USER 101:101/u);
  for (const script of ['bootstrap-vben.mjs', 'bootstrap-unibest.mjs']) assert.ok(clients.includes(script));
});
test('static image refuses API/management/payment routes and does not fall back to HTML for missing JS', () => {
  const nginx = text('deploy/online-demo/images/nginx.conf');
  for (const path of ['api', 'approval-api', 'actuator', 'payment-sandbox']) {
    assert.ok(nginx.includes(`location = /${path} { return 404; }`));
    assert.ok(nginx.includes(`location ^~ /${path}/ { return 404; }`));
  }
  assert.match(nginx, /try_files \$uri =404/u);
  assert.match(nginx, /noindex, nofollow, noarchive/u);
  assert.match(nginx, /return 405/u);
  assert.doesNotMatch(nginx, /proxy_pass|listen 443|ssl_certificate/u);
});

test('no-argument build resolves the exact source-committed pins, with explicit validated overrides', t => {
  const { directory } = fixture(t);
  const metadata = readSource(directory);
  const resolved = resolveImageOptions(directory, metadata, parseArguments(['build']));
  assert.equal(plan(metadata, resolved).missingInputs.length, 0);
  assert.deepEqual(resolved.images, JSON.parse(text('deploy/online-demo/images/base-images.json')).images);
  const explicit = resolveImageOptions(directory, metadata, parseArguments(['build', '--node-image', images.node]));
  assert.equal(explicit.images.node, images.node);
  assert.equal(explicit.images.java, resolved.images.java);
});
test('pin resolution rejects invalid or missing committed pins and never falls back to mutable tags', t => {
  const { directory } = fixture(t); const metadata = readSource(directory);
  for (const pins of [{ schemaVersion: 2 }, { schemaVersion: 1, images: { node: 'node:latest' } },
    { schemaVersion: 1, images: { ...images, java: 'eclipse-temurin:21-jre' } }]) {
    assert.throws(() => resolveImageOptions(directory, metadata, parseArguments(['build']), () => JSON.stringify(pins)));
  }
  assert.throws(() => resolveImageOptions(directory, metadata, parseArguments(['build']), () => { throw new Error('missing pinned manifest'); }), /missing pinned manifest/u);
});

import './product-readiness-online-demo-nginx.test.mjs';

test('real CLI plan resolves committed defaults without creating outputs or contacting Docker', t => {
  const { directory, git } = fixture(t);
  for (const path of ['scripts/product-readiness/online-demo-images.mjs',
    'scripts/product-readiness/online-demo/images-build.mjs',
    'scripts/product-readiness/online-demo/images-contract.mjs']) write(directory, path, text(path));
  git(['add', '.']); git(['commit', '-qm', 'CLI fixture']);
  const result = spawnSync(process.execPath, [resolve(directory, 'scripts/product-readiness/online-demo-images.mjs'), 'plan', '--json'], {
    cwd: directory, encoding: 'utf8', timeout: 5_000,
  });
  assert.equal(result.status, 0, result.stderr);
  const value = JSON.parse(result.stdout);
  assert.equal(value.source.commitSha, git(['rev-parse', 'HEAD']));
  assert.equal(value.status, 'BUILD_INPUTS_VALIDATED_ONLY');
  assert.equal(value.images.length, 3); assert.deepEqual(value.missingInputs, []);
  assert.equal(existsSync(resolve(directory, '.runtime')), false);
});
test('existing repository hygiene imports image packaging checks without a second workflow', () => {
  assert.match(text('scripts/tests/m3-repository-hygiene.test.mjs'), /import '\.\/product-readiness-online-demo-images\.test\.mjs'/u);
  assert.match(text('deploy/online-demo/README.md'), /Docker is substituted in these unit tests/u);
});
