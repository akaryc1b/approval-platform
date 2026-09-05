import { randomBytes } from 'node:crypto';
import { lstatSync, mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { performance } from 'node:perf_hooks';
import { executeImageBuild, readSource, runCommand } from './images-build.mjs';
import { components, plan, verifyImageInspection } from './images-contract.mjs';
import { deniedStaticPaths, probeProgram, verifyStaticInventory, verifyStaticResponse } from './runtime-probes.mjs';

const ownerLabel = 'io.approval.image-smoke.owner';
const platform = 'linux/amd64';
const maximumRuntimeMs = 38 * 60_000;
export const runtimeNonClaims = Object.freeze([
  'ONLINE_DEMO_NOT_AVAILABLE', 'PUBLIC_URL_NOT_PUBLISHED',
  'ONLINE_SESSION_ISOLATION_NOT_VERIFIED', 'ONLINE_RESET_NOT_VERIFIED',
  'BROWSER_BUSINESS_E2E_NOT_EXECUTED', 'IMAGE_VULNERABILITY_SCAN_NOT_EXECUTED',
  'BIT_REPRODUCIBLE_BUILD_NOT_VERIFIED', 'REGISTRY_PUSH_NOT_EXECUTED',
  'PRODUCTION_DEPLOYMENT_NOT_VERIFIED', 'RELEASE_NOT_CREATED',
]);
const requireCondition = (value, message) => { if (!value) throw new Error(message); };
const imageId = value => /^sha256:[0-9a-f]{64}$/u.test(value || '');
const resourceId = value => /^[0-9a-f]{64}$/u.test(value || '');
const json = (path, value) => writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });

export function validateRuntimePins(value) {
  requireCondition(value?.schemaVersion === 1 && value.images
    && Object.keys(value.images).sort().join(',') === 'postgres,redis', 'invalid runtime image manifest');
  for (const [name, version] of [['postgres', '16'], ['redis', '7']]) {
    const expected = new RegExp(`^${name}:${version}@sha256:[0-9a-f]{64}$`, 'u');
    requireCondition(expected.test(value.images[name]) && !value.images[name].endsWith('0'.repeat(64)),
      'runtime infrastructure must use approved version/digest pins');
  }
  return value.images;
}

export function verifyApplicationContainer(value, image, network, owner) {
  requireCondition(value?.Config?.Labels?.[ownerLabel] === owner && value.Image === image.localImageId,
    'container does not match its owned image');
  requireCondition(value.Config.User === image.user && value.State?.Running === true,
    'application container is not running as the declared non-root user');
  const host = value.HostConfig;
  requireCondition(host?.NetworkMode === network && !host.Privileged && host.ReadonlyRootfs === true
    && host.CapDrop?.includes('ALL') && !(host.CapAdd?.length)
    && host.SecurityOpt?.some(option => ['no-new-privileges', 'no-new-privileges=true'].includes(option))
    && Object.keys(host.PortBindings || {}).length === 0
    && Object.values(value.NetworkSettings?.Ports || {}).every(bindings => bindings === null)
    && Object.keys(value.NetworkSettings?.Networks || {}).join(',') === network
    && !(value.Mounts || []).some(mount => mount.Type === 'bind' || mount.Type === 'volume'),
  'application container escaped its private read-only runtime contract');
  if (image.component === 'backend') {
    requireCondition(value.Config.Env?.includes('SERVER_ADDRESS=127.0.0.1'),
      'backend packaging default must remain loopback-only');
  }
  return { component: image.component, containerId: value.Id, localImageId: value.Image,
    user: value.Config.User, readOnlyRootfs: true, publishedPorts: [], privateNetwork: network };
}

// Attempt every owned resource independently; an error cannot skip later cleanup.
// IDs are resolved from exact names and ownership labels before any removal.
export function cleanupImageRuntime(scope, run = runCommand) {
  const actions = [];
  const failures = [];
  for (const name of [...scope.containers].reverse()) {
    try {
      const listed = run('docker', ['container', 'ls', '-aq', '--no-trunc', '--filter', `name=^/${name}$`]);
      if (!listed) { actions.push({ name, status: 'ALREADY_ABSENT' }); continue; }
      requireCondition(resourceId(listed), 'ambiguous cleanup container');
      const [value] = JSON.parse(run('docker', ['container', 'inspect', listed]));
      requireCondition(value?.Config?.Labels?.[ownerLabel] === scope.owner, 'cleanup ownership mismatch');
      run('docker', ['container', 'rm', '--force', '--volumes', listed]);
      const remaining = run('docker', ['container', 'ls', '-aq', '--no-trunc', '--filter', `id=${listed}`]);
      requireCondition(!remaining, 'container still exists after removal');
      actions.push({ name, status: 'REMOVED' });
    } catch { failures.push(`container:${name}`); }
  }
  if (scope.network) {
    try {
      const listed = run('docker', ['network', 'ls', '-q', '--no-trunc', '--filter', `name=^${scope.network}$`]);
      if (listed) {
        requireCondition(resourceId(listed), 'ambiguous cleanup network');
        const [value] = JSON.parse(run('docker', ['network', 'inspect', listed]));
        requireCondition(value?.Labels?.[ownerLabel] === scope.owner, 'cleanup network ownership mismatch');
        run('docker', ['network', 'rm', listed]);
        requireCondition(!run('docker', ['network', 'ls', '-q', '--no-trunc', '--filter', `id=${listed}`]),
          'network still exists after removal');
      }
      actions.push({ name: scope.network, status: listed ? 'REMOVED' : 'ALREADY_ABSENT' });
    } catch { failures.push(`network:${scope.network}`); }
  }
  return { status: failures.length ? 'FAILED' : 'PASSED', actions, failures,
    scope: 'OWNED_CONTAINERS_AND_NETWORK_ONLY_IMAGES_RETAINED' };
}

function privateOutput(root) {
  for (const suffix of ['.runtime', '.runtime/online-demo-image-runtime']) {
    const path = resolve(root, suffix);
    try { mkdirSync(path, { mode: 0o700 }); }
    catch (error) { if (error.code !== 'EEXIST') throw error; }
    requireCondition(lstatSync(path).isDirectory() && !lstatSync(path).isSymbolicLink(), 'unsafe runtime directory');
  }
  return mkdtempSync(resolve(root, '.runtime/online-demo-image-runtime/run-'));
}

export async function executeImageRuntime(root, {
  run = runCommand, build = executeImageBuild, now = () => performance.now(),
  sleep = milliseconds => new Promise(done => setTimeout(done, milliseconds)),
} = {}) {
  const started = now();
  const deadline = started + maximumRuntimeMs;
  const directory = privateOutput(root);
  const owner = randomBytes(16).toString('hex');
  const prefix = `ap-image-smoke-${owner}`;
  const scope = { owner, containers: [], network: null };
  const receipt = { schemaVersion: 1, kind: 'ONLINE_DEMO_IMAGE_RUNTIME_RECEIPT', status: 'RUNNING',
    platform, startedAt: new Date().toISOString(), source: null, checks: [], containers: [],
    cleanup: null, nonClaims: runtimeNonClaims };
  const secret = randomBytes(32).toString('hex');
  const write = () => json(resolve(directory, 'runtime-receipt.json'), receipt);
  const saveScope = () => json(resolve(directory, 'resource-scope.json'), scope);
  const boundedRun = (command, args, options = {}) => {
    const remaining = Math.floor(deadline - now());
    requireCondition(remaining > 0, 'image runtime exceeded its total deadline');
    return run(command, args, { ...options, cwd: root,
      timeoutMs: Math.min(options.timeoutMs || 30_000, remaining) });
  };
  const docker = (args, options) => boundedRun('docker', args, options);
  async function wait(label, check, milliseconds = 120_000) {
    const end = Math.min(deadline, now() + milliseconds);
    while (now() < end) {
      try { if (check()) return; } catch { /* A startup read is retried only within this deadline. */ }
      await sleep(500);
    }
    throw new Error(`${label} did not become ready before its deadline`);
  }
  const create = (component, image, args, command = [], network = scope.network) => {
    const name = `${prefix}-${component}`;
    scope.containers.push(name);
    saveScope(); // An uncertain create result still has an owned cleanup target.
    const id = docker(['create', '--name', name, '--label', `${ownerLabel}=${owner}`,
      '--network', network, '--platform', platform, '--restart', 'no', ...args, image, ...command]);
    requireCondition(resourceId(id), 'Docker did not return one container ID');
    docker(['start', id]);
    return { name, id };
  };
  const hardened = ['--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
    '--pids-limit', '256', '--tmpfs', '/tmp:rw,nosuid,noexec,size=134217728'];
  const inspect = name => {
    const result = JSON.parse(docker(['container', 'inspect', name]));
    requireCondition(Array.isArray(result) && result.length === 1, 'ambiguous container inspection');
    return result[0];
  };
  let executionError;
  write();
  saveScope();
  try {
    receipt.source = readSource(root, boundedRun);
    write();
    const engine = JSON.parse(docker(['info', '--format', '{{json .}}']));
    requireCondition(engine.OSType === 'linux', 'Linux Docker engine is required; runtime is not skipped');
    receipt.engine = { version: engine.ServerVersion, operatingSystem: engine.OperatingSystem };
    const built = build(root, { command: 'build', platform, images: {} }, boundedRun);
    const result = built.receipt;
    requireCondition(result.status === 'LOCAL_IMAGES_BUILT_NOT_RUNTIME_ACCEPTED'
      && JSON.stringify(result.source) === JSON.stringify(receipt.source)
      && result.images?.map(image => image.component).join(',') === components.join(','),
    'runtime requires all three images from this exact source');
    receipt.build = result;
    const buildPlan = plan(result.source, { platform, images: result.baseImages });
    for (const image of result.images) {
      const [value] = JSON.parse(docker(['image', 'inspect', image.localImageId]));
      verifyImageInspection(value, buildPlan, image, image.localImageId, result.archiveSha256);
    }
    write();
    const pins = validateRuntimePins(JSON.parse(boundedRun('git', ['show',
      `${receipt.source.commitSha}:deploy/online-demo/images/runtime-images.json`])));
    receipt.infrastructure = {};
    for (const [component, pin] of Object.entries({ ...pins, probe: result.baseImages.node })) {
      docker(['pull', '--platform', platform, pin], { timeoutMs: 5 * 60_000 });
      const [value] = JSON.parse(docker(['image', 'inspect', pin]));
      requireCondition(imageId(value?.Id) && `${value.Os}/${value.Architecture}` === platform, 'infrastructure image mismatch');
      receipt.infrastructure[component] = { pin, localImageId: value.Id };
    }
    scope.network = `${prefix}-net`;
    saveScope();
    const networkId = docker(['network', 'create', '--internal', '--label', `${ownerLabel}=${owner}`, scope.network]);
    requireCondition(resourceId(networkId), 'invalid internal network ID');
    const [network] = JSON.parse(docker(['network', 'inspect', networkId]));
    requireCondition(network.Internal === true && network.Labels?.[ownerLabel] === owner,
      'runtime network is not owned and internal');
    const postgres = create('postgres', receipt.infrastructure.postgres.localImageId,
      ['--memory', '768m', '--cpus', '1', '--tmpfs', '/var/lib/postgresql/data:rw,nosuid,size=536870912',
        '--env', 'POSTGRES_DB=approval', '--env', 'POSTGRES_USER=approval', '--env', `POSTGRES_PASSWORD=${secret}`]);
    const redis = create('redis', receipt.infrastructure.redis.localImageId,
      ['--memory', '128m', '--cpus', '0.5', '--tmpfs', '/data:rw,nosuid,size=67108864'],
      ['redis-server', '--save', '', '--appendonly', 'no']);
    // pg_isready alone may observe the initialization-only Unix socket server.
    await wait('PostgreSQL TCP', () => docker(['exec', postgres.id, 'pg_isready',
      '-h', '127.0.0.1', '-U', 'approval', '-d', 'approval']).includes('accepting connections'));
    await wait('Redis', () => docker(['exec', redis.id, 'redis-cli', 'ping']) === 'PONG');
    const backendImage = result.images[0];
    const backend = create('backend', backendImage.localImageId, [...hardened,
      '--memory', '2g', '--cpus', '2', '--env', 'JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=65.0 -XX:ActiveProcessorCount=2',
      '--env', `APPROVAL_DB_URL=jdbc:postgresql://${postgres.name}:5432/approval`,
      '--env', 'APPROVAL_DB_USERNAME=approval', '--env', `APPROVAL_DB_PASSWORD=${secret}`,
      '--env', `SPRING_DATA_REDIS_HOST=${redis.name}`, '--env', 'FLOWABLE_DATABASE_SCHEMA_UPDATE=true',
      '--env', 'APPROVAL_IDENTITY_MODE=principal', '--env', 'APPROVAL_GENERIC_CONNECTOR_ENABLED=false',
      '--env', 'APPROVAL_GENERIC_DISPATCH_ENABLED=false', '--env', 'MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health']);
    // Probe the backend through its network namespace; do not widen SERVER_ADDRESS.
    const probe = create('probe', receipt.infrastructure.probe.localImageId,
      [...hardened, '--memory', '256m', '--cpus', '0.5', '--user', '1000:1000', '--entrypoint', 'node'],
      ['-e', 'setTimeout(() => process.exit(0), 900000)'], `container:${backend.id}`);
    const request = (url, options = {}) => JSON.parse(docker(['exec', probe.id, 'node',
      '--input-type=module', '-e', probeProgram, JSON.stringify({ url, ...options })]));
    await wait('packaged backend health', () => request('http://127.0.0.1:8080/actuator/health', { health: true }).healthy, 180_000);
    const health = request('http://127.0.0.1:8080/actuator/health', { health: true });
    requireCondition(health.healthy === true, 'backend health regressed after startup');
    receipt.checks.push({ component: 'backend', check: 'ACTUATOR_UP', result: health });
    requireCondition(docker(['exec', backend.id, 'sh', '-c', 'cd /app && sha256sum -c app.jar.sha256']) === 'app.jar: OK',
      'packaged JAR checksum mismatch');
    for (const path of ['/actuator/env', '/actuator/metrics']) {
      const response = request(`http://127.0.0.1:8080${path}`);
      requireCondition(response.status === 404, 'unexpected management exposure');
      receipt.checks.push({ component: 'backend', path, status: response.status });
    }
    receipt.containers.push(verifyApplicationContainer(inspect(backend.id), backendImage, scope.network, owner));
    for (const image of result.images.slice(1)) {
      const container = create(image.component, image.localImageId, [...hardened, '--memory', '256m', '--cpus', '0.5']);
      const origin = `http://${container.name}:8080`;
      await wait(`${image.component} static server`, () => request(`${origin}/healthz`).status === 200, 30_000);
      const raw = docker(['exec', container.id, 'cat', '/opt/approval/build-info.json'], { maxBuffer: 4 * 1024 * 1024 });
      const inventory = JSON.parse(raw);
      const files = verifyStaticInventory(inventory, receipt.source, image.component);
      const lockHash = docker(['exec', container.id, 'sha256sum', '/opt/approval/resolved-pnpm-lock.yaml']).split(/\s/u)[0];
      requireCondition(lockHash === inventory.lockSha256, 'packaged dependency lock digest mismatch');
      for (const file of files) {
        const response = request(`${origin}/${file.path.split('/').map(encodeURIComponent).join('/')}`);
        verifyStaticResponse(response, 200, file);
        receipt.checks.push({ component: image.component, path: file.path, result: response });
      }
      for (const path of deniedStaticPaths) {
        const response = request(`${origin}${path}`);
        verifyStaticResponse(response, 404);
        receipt.checks.push({ component: image.component, path, status: response.status });
      }
      verifyStaticResponse(request(`${origin}/`, { method: 'POST' }), 405);
      receipt.containers.push(verifyApplicationContainer(inspect(container.id), image, scope.network, owner));
      json(resolve(directory, `${image.component}-inventory.json`), inventory);
      write();
    }
    receipt.runtimeChecksPassed = true;
  } catch (error) {
    executionError = error;
    receipt.failure = 'IMAGE_BUILD_OR_RUNTIME_CHECK_FAILED';
    receipt.failureDetail = String(error.message || 'unknown failure').replaceAll(secret, '[REDACTED]').slice(0, 1000);
    // Preserve bounded diagnostics while never retaining generated database credentials.
    for (const name of scope.containers) {
      try {
        const text = String(run('docker', ['logs', '--tail', '120', name], { timeoutMs: 5_000, maxBuffer: 256 * 1024 }));
        json(resolve(directory, `${name.slice(prefix.length + 1)}-diagnostic.json`),
          { logTail: text.replaceAll(secret, '[REDACTED]').slice(-32_768) });
      } catch { /* Diagnostic failure is explicit and cannot prevent cleanup. */
        receipt.diagnosticsUnavailable = true;
      }
    }
  } finally {
    // Cleanup has its own per-operation bounds even when the execution deadline expires.
    receipt.cleanup = cleanupImageRuntime(scope, (command, args) => run(command, args, { cwd: root, timeoutMs: 10_000 }));
    receipt.status = !executionError && receipt.runtimeChecksPassed && receipt.cleanup.status === 'PASSED'
      ? 'LOCAL_IMAGE_STARTUP_SMOKE_PASSED' : 'FAILED';
    receipt.elapsedMs = Math.round(now() - started);
    receipt.completedAt = new Date().toISOString();
    write();
  }
  if (receipt.status !== 'LOCAL_IMAGE_STARTUP_SMOKE_PASSED') {
    throw new Error(`image runtime failed; bounded evidence: ${directory}`, { cause: executionError });
  }
  return { directory, receipt };
}
