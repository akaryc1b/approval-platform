import { execFile } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { performance } from 'node:perf_hooks';

export const evaluationSlotIds = Object.freeze(['slot-a', 'slot-b']);
const roles = Object.freeze(['postgres', 'redis', 'backend', 'probe']);
const labelRoot = 'io.approval.evaluation';
const hex = value => typeof value === 'string' && /^[0-9a-f]{64}$/u.test(value) && !/^0+$/u.test(value);
const imageId = value => typeof value === 'string' && value.startsWith('sha256:') && hex(value.slice(7));
const assert = (value, code) => { if (!value) throw new Error(code); };
const copy = value => structuredClone(value);
const pause = ms => new Promise(done => setTimeout(done, ms));
const one = text => { const values = JSON.parse(text); assert(Array.isArray(values) && values.length === 1, 'AMBIGUOUS_DOCKER_RESULT'); return values[0]; };

/** Local Linux engine only. Neither command output nor credentials enter errors. */
export function runEvaluationDocker(args, { signal, timeoutMs = 5000 } = {}) {
  assert(Array.isArray(args) && args.every(value => typeof value === 'string' && !value.includes('\0')), 'INVALID_DOCKER_ARGUMENTS');
  assert(Number.isSafeInteger(timeoutMs) && timeoutMs > 0 && timeoutMs <= 60_000, 'INVALID_DOCKER_TIMEOUT');
  return new Promise((resolve, reject) => {
    execFile('docker', ['--host', 'unix:///var/run/docker.sock', ...args], {
      encoding: 'utf8', timeout: timeoutMs, signal, killSignal: 'SIGKILL', maxBuffer: 1024 * 1024,
      env: { PATH: process.env.PATH || '/usr/bin:/bin', LANG: 'C.UTF-8' }, shell: false,
    }, (error, stdout) => error ? reject(new Error('EVALUATION_DOCKER_COMMAND_FAILED')) : resolve(stdout.trim()));
  });
}

function validateInputs(source, backend, infrastructure, archiveSha256) {
  for (const field of ['commitSha', 'treeSha']) assert(/^[0-9a-f]{40}$/u.test(source?.[field] || '')
    && !/^0+$/u.test(source[field]), 'INVALID_SOURCE_IDENTITY');
  assert(typeof source.version === 'string' && /^[0-9][0-9A-Za-z._-]{0,79}$/u.test(source.version), 'INVALID_SOURCE_VERSION');
  assert(hex(archiveSha256) && backend?.component === 'backend' && imageId(backend.localImageId)
    && backend.user === '10001:10001', 'INVALID_BACKEND_IMAGE');
  assert(infrastructure && Object.keys(infrastructure).sort().join(',') === 'postgres,probe,redis', 'INVALID_INFRASTRUCTURE');
  const patterns = { postgres: /^postgres:16@sha256:[0-9a-f]{64}$/u,
    redis: /^redis:7@sha256:[0-9a-f]{64}$/u,
    probe: /^(?:node|docker.io\/library\/node)(?::[A-Za-z0-9_.-]+)?@sha256:[0-9a-f]{64}$/u };
  for (const role of ['postgres', 'redis', 'probe']) assert(imageId(infrastructure[role]?.localImageId)
    && patterns[role].test(infrastructure[role]?.pin || '') && hex(infrastructure[role].pin.split('@sha256:')[1]), 'INVALID_INFRASTRUCTURE_PIN');
}
function names(namespace, slotId) {
  const prefix = `ap-evaluation-${namespace}-${slotId}`;
  return { network: `${prefix}-net`, containers: Object.fromEntries(roles.map(role => [role, `${prefix}-${role}`])) };
}
function labels(namespace, slotId, generation, role) {
  return { [`${labelRoot}.owner`]: namespace, [`${labelRoot}.slot`]: slotId,
    [`${labelRoot}.generation`]: generation, [`${labelRoot}.role`]: role };
}
function requireLabels(actual, expected) {
  for (const [key, value] of Object.entries(expected)) assert(actual?.[key] === value, 'FOREIGN_RESOURCE_OWNERSHIP');
}
function requirePrivate(value, role, scope, expectedImage) {
  assert(value.Name === `/${scope.names.containers[role]}` && hex(value.Id) && value.Image === expectedImage
    && value.State?.Running === true, 'CONTAINER_IDENTITY_OR_READINESS_MISMATCH');
  requireLabels(value.Config?.Labels, labels(scope.namespace, scope.slotId, scope.generation, role));
  const host = value.HostConfig;
  const network = role === 'probe' ? `container:${scope.containers.backend.id}` : scope.names.network;
  assert(host?.NetworkMode === network && host.Privileged === false
    && !(host.CapAdd?.length) && Object.keys(host.PortBindings || {}).length === 0
    && Object.values(value.NetworkSettings?.Ports || {}).every(port => port === null)
    && !host.PublishAllPorts && host.RestartPolicy?.Name === 'no'
    && host.Memory > 0 && host.Memory <= 2 * 1024 ** 3
    && host.NanoCpus > 0 && host.NanoCpus <= 2 * 1e9 && host.PidsLimit > 0 && host.PidsLimit <= 256,
  'CONTAINER_RUNTIME_BOUNDARY_MISMATCH');
  const networks = Object.keys(value.NetworkSettings?.Networks || {});
  assert(role === 'probe' ? networks.length === 0 : networks.length === 1 && networks[0] === scope.names.network,
    'UNEXPECTED_CONTAINER_NETWORK');
  assert((value.Mounts || []).every(mount => mount.Type === 'tmpfs'), 'PERSISTENT_OR_HOST_MOUNT_REJECTED');
  const mounts = Object.keys(host.Tmpfs || {}).sort();
  const path = role === 'postgres' ? '/var/lib/postgresql/data' : role === 'redis' ? '/data' : '/tmp';
  assert(mounts.length === 1 && mounts[0] === path, 'UNEXPECTED_TMPFS_LAYOUT');
  if (role === 'backend' || role === 'probe') {
    assert(host.ReadonlyRootfs === true && host.CapDrop?.includes('ALL')
      && host.SecurityOpt?.some(value => ['no-new-privileges', 'no-new-privileges=true'].includes(value))
      && value.Config.User === (role === 'backend' ? '10001:10001' : '1000:1000'), 'APPLICATION_HARDENING_MISMATCH');
  }
  if (role === 'backend') assert(value.Config.Env?.includes('SERVER_ADDRESS=127.0.0.1')
    && value.Config.Env.includes('APPROVAL_IDENTITY_MODE=principal')
    && value.Config.Env.includes('APPROVAL_GENERIC_DISPATCH_ENABLED=false')
    && value.Config.Env.includes('APPROVAL_GENERIC_CONNECTOR_ENABLED=false'), 'BACKEND_EXPOSURE_MISMATCH');
}

/**
 * Two private, independently disposable backend/data stacks. No browser routing,
 * business principal, seed or payment side effect is enabled here. The operator
 * must supply a durable bounded recorder; acknowledgement follows its write.
 * Namespace is generated internally, preventing a new process adopting stale
 * stacks. A crashed operator needs explicit resource recovery, not auto-adoption.
 */
export function createEvaluationDockerSlots({ source, backendImage, infrastructure, archiveSha256,
  record, run = runEvaluationDocker, resetBudgetMs = 55_000 } = {}) {
  validateInputs(source, backendImage, infrastructure, archiveSha256);
  assert(typeof record === 'function' && typeof run === 'function', 'RECORDER_AND_RUNNER_REQUIRED');
  assert(Number.isSafeInteger(resetBudgetMs) && resetBudgetMs >= 100 && resetBudgetMs <= 55_000, 'INVALID_RESET_BUDGET');
  const input = copy({ source, backendImage, infrastructure, archiveSha256 });
  const namespace = randomBytes(16).toString('hex');
  const stop = new AbortController();
  let closed = false;
  let disposeWork;
  let engineId;
  let prepared;
  const slots = new Map(evaluationSlotIds.map(slotId => [slotId, { namespace, slotId, generation: null,
    names: names(namespace, slotId), network: null, containers: {}, state: 'QUARANTINED',
    inFlight: null, phase: 'CREATED', uncertainMutation: false, checks: [], cleanup: null }]));
  function snapshot() {
    return { schemaVersion: 1, kind: 'EVALUATION_DOCKER_SLOT_RESOURCES', namespace, closed,
      source: copy(input.source), engineId: engineId || null,
      slots: [...slots.values()].map(({ inFlight, ...slot }) => ({ ...copy(slot), resetInFlight: Boolean(inFlight) })),
      scope: 'PRIVATE_BACKEND_DATA_STACKS_NOT_BUSINESS_AUTHORIZATION' };
  }
  const save = () => record(snapshot());
  const docker = (args, options) => run(args, options);
  async function prepare() {
    if (prepared) return prepared;
    prepared = (async () => {
      const engine = JSON.parse(await docker(['info', '--format', '{{json .}}'], { signal: stop.signal }));
      assert(engine.OSType === 'linux' && typeof engine.ID === 'string' && engine.ID.length > 0, 'LOCAL_LINUX_ENGINE_REQUIRED');
      engineId = engine.ID;
      const value = one(await docker(['image', 'inspect', input.backendImage.localImageId], { signal: stop.signal }));
      assert(value.Id === input.backendImage.localImageId && value.Os === 'linux' && value.Architecture === 'amd64'
        && value.Config?.User === '10001:10001', 'BACKEND_IMAGE_IDENTITY_MISMATCH');
      for (const [key, expected] of Object.entries({ 'org.opencontainers.image.revision': input.source.commitSha,
        'org.opencontainers.image.version': input.source.version, 'io.approval.source.tree': input.source.treeSha,
        'io.approval.source.archive': input.archiveSha256, 'io.approval.component': 'backend' })) {
        assert(value.Config.Labels?.[key] === expected, 'BACKEND_IMAGE_LABEL_MISMATCH');
      }
      for (const role of ['postgres', 'redis', 'probe']) {
        const image = one(await docker(['image', 'inspect', input.infrastructure[role].pin], { signal: stop.signal }));
        assert(image.Id === input.infrastructure[role].localImageId && image.Os === 'linux' && image.Architecture === 'amd64',
          'INFRASTRUCTURE_IMAGE_IDENTITY_MISMATCH');
      }
      save();
    })();
    return prepared;
  }
  async function cleanup(slot) {
    const result = { status: 'PASSED', removed: [], failures: [] };
    for (const role of [...roles].reverse()) {
      const target = slot.containers[role];
      if (!target) continue;
      try {
        const id = await docker(['container', 'ls', '-aq', '--no-trunc', '--filter', `name=^/${target.name}$`], { timeoutMs: 2000 });
        if (id) {
          assert(hex(id) && (!target.id || id === target.id), 'CLEANUP_ID_MISMATCH');
          const value = one(await docker(['container', 'inspect', id], { timeoutMs: 2000 }));
          assert(value.Id === id && value.Name === `/${target.name}`, 'CLEANUP_NAME_MISMATCH');
          requireLabels(value.Config?.Labels, labels(namespace, slot.slotId, slot.generation, role));
          // Volumes are never created by this adapter; do not remove unexpected ones.
          assert((value.Mounts || []).every(mount => mount.Type === 'tmpfs'), 'CLEANUP_UNEXPECTED_MOUNT');
          await docker(['container', 'rm', '--force', id], { timeoutMs: 2000 });
          assert(!await docker(['container', 'ls', '-aq', '--no-trunc', '--filter', `id=${id}`], { timeoutMs: 2000 }), 'CLEANUP_NOT_ABSENT');
          result.removed.push({ role, id });
        }
        delete slot.containers[role];
      } catch { result.failures.push(role); }
    }
    if (slot.network) {
      try {
        const id = await docker(['network', 'ls', '-q', '--no-trunc', '--filter', `name=^${slot.names.network}$`], { timeoutMs: 2000 });
        if (id) {
          assert(hex(id) && (!slot.network.id || slot.network.id === id), 'CLEANUP_NETWORK_ID_MISMATCH');
          const value = one(await docker(['network', 'inspect', id], { timeoutMs: 2000 }));
          assert(value.Id === id && value.Name === slot.names.network && value.Internal === true
            && Object.keys(value.Containers || {}).length === 0, 'CLEANUP_NETWORK_NOT_EMPTY');
          requireLabels(value.Labels, labels(namespace, slot.slotId, slot.generation, 'network'));
          await docker(['network', 'rm', id], { timeoutMs: 2000 });
          assert(!await docker(['network', 'ls', '-q', '--no-trunc', '--filter', `id=${id}`], { timeoutMs: 2000 }), 'CLEANUP_NETWORK_NOT_ABSENT');
          result.removed.push({ role: 'network', id });
        }
        slot.network = null;
      } catch { result.failures.push('network'); }
    }
    result.status = result.failures.length ? 'FAILED' : 'PASSED';
    slot.cleanup = result;
    return result;
  }
  async function replace(slot, resetNonce, externalSignal) {
    const signal = AbortSignal.any([stop.signal, externalSignal]);
    const deadline = performance.now() + resetBudgetMs;
    const check = () => assert(!closed && !signal.aborted && performance.now() < deadline, 'RESET_ABORTED_OR_OVERDUE');
    async function command(args, mutation = false) {
      check();
      try {
        const result = await docker(args, { signal, timeoutMs: Math.max(1, Math.min(5000, Math.floor(deadline - performance.now()))) });
        check(); return result;
      } catch { if (mutation) slot.uncertainMutation = true; throw new Error('RESET_COMMAND_FAILED'); }
    }
    async function wait(checkReady) {
      while (true) {
        check();
        try { if (await checkReady()) return; } catch { check(); }
        await pause(250);
      }
    }
    try {
      slot.phase = 'IMAGE_PREFLIGHT'; save(); await prepare(); check();
      const engine = JSON.parse(await command(['info', '--format', '{{json .}}']));
      assert(engine.ID === engineId && engine.OSType === 'linux', 'DOCKER_ENGINE_CHANGED');
      slot.state = 'RESETTING'; slot.phase = 'OLD_STACK_REMOVAL'; save();
      assert((await cleanup(slot)).status === 'PASSED', 'OLD_STACK_CLEANUP_FAILED'); check();
      slot.generation = randomBytes(16).toString('hex'); slot.checks = []; slot.phase = 'PRIVATE_NETWORK_CREATION';
      const flags = role => Object.entries(labels(namespace, slot.slotId, slot.generation, role))
        .flatMap(([key, value]) => ['--label', `${key}=${value}`]);
      assert(!await command(['network', 'ls', '-q', '--no-trunc', '--filter', `name=^${slot.names.network}$`]), 'NETWORK_NAME_ALREADY_EXISTS');
      slot.network = { name: slot.names.network, id: null }; save();
      slot.network.id = await command(['network', 'create', '--driver', 'bridge', '--internal', ...flags('network'), slot.names.network], true);
      assert(hex(slot.network.id), 'INVALID_NETWORK_ID'); save();
      const network = one(await command(['network', 'inspect', slot.network.id]));
      assert(network.Internal === true && network.Id === slot.network.id && network.Name === slot.names.network, 'INTERNAL_NETWORK_REQUIRED');
      requireLabels(network.Labels, labels(namespace, slot.slotId, slot.generation, 'network'));
      const password = randomBytes(32).toString('hex');
      const cachePassword = randomBytes(32).toString('hex');
      const hard = ['--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
        '--tmpfs', '/tmp:rw,nosuid,noexec,size=134217728'];
      async function create(role, image, args, entry = [], networkMode = slot.names.network) {
        slot.phase = `CREATE_${role.toUpperCase()}`; save();
        const name = slot.names.containers[role];
        assert(!await command(['container', 'ls', '-aq', '--no-trunc', '--filter', `name=^/${name}$`]), 'CONTAINER_NAME_ALREADY_EXISTS');
        slot.containers[role] = { name, id: null }; save();
        const id = await command(['create', '--name', name, ...flags(role), '--platform', 'linux/amd64',
          '--network', networkMode, '--restart', 'no', '--pids-limit', '256', ...args, image, ...entry], true);
        assert(hex(id), 'INVALID_CONTAINER_ID'); slot.containers[role].id = id; save();
        await command(['start', id], true); return id;
      }
      const pg = await create('postgres', input.infrastructure.postgres.localImageId, [
        '--memory', '768m', '--cpus', '1', '--tmpfs', '/var/lib/postgresql/data:rw,nosuid,size=536870912',
        '--env', 'POSTGRES_DB=approval', '--env', 'POSTGRES_USER=approval', '--env', `POSTGRES_PASSWORD=${password}`]);
      const redis = await create('redis', input.infrastructure.redis.localImageId, [
        '--memory', '128m', '--cpus', '0.5', '--tmpfs', '/data:rw,nosuid,size=67108864', '--env', `REDISCLI_AUTH=${cachePassword}`],
      ['redis-server', '--save', '', '--appendonly', 'no', '--requirepass', cachePassword]);
      await wait(async () => (await command(['exec', pg, 'pg_isready', '-h', '127.0.0.1', '-U', 'approval', '-d', 'approval'])).includes('accepting connections'));
      await wait(async () => await command(['exec', redis, 'redis-cli', 'ping']) === 'PONG');
      const backend = await create('backend', input.backendImage.localImageId, [...hard,
        '--memory', '1536m', '--cpus', '2', '--env', 'SERVER_ADDRESS=127.0.0.1',
        '--env', 'JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=65.0 -XX:ActiveProcessorCount=2',
        '--env', `APPROVAL_DB_URL=jdbc:postgresql://${slot.names.containers.postgres}:5432/approval`,
        '--env', 'APPROVAL_DB_USERNAME=approval', '--env', `APPROVAL_DB_PASSWORD=${password}`,
        '--env', `SPRING_DATA_REDIS_HOST=${slot.names.containers.redis}`, '--env', `SPRING_DATA_REDIS_PASSWORD=${cachePassword}`,
        '--env', 'FLOWABLE_DATABASE_SCHEMA_UPDATE=true', '--env', 'APPROVAL_IDENTITY_MODE=principal',
        '--env', 'APPROVAL_GENERIC_CONNECTOR_ENABLED=false', '--env', 'APPROVAL_GENERIC_DISPATCH_ENABLED=false',
        '--env', 'MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health']);
      const probe = await create('probe', input.infrastructure.probe.localImageId, [...hard,
        '--memory', '128m', '--cpus', '0.5', '--user', '1000:1000', '--entrypoint', 'node'],
      ['-e', 'setTimeout(() => process.exit(0), 2700000)'], `container:${backend}`);
      slot.phase = 'BACKEND_READINESS'; save();
      const healthProgram = "const r=await fetch('http://127.0.0.1:8080/actuator/health',{signal:AbortSignal.timeout(1500),redirect:'error'});if(r.status!==200||(await r.json()).status!=='UP')process.exit(1);console.log('UP');";
      await wait(async () => await command(['exec', probe, 'node', '--input-type=module', '-e', healthProgram]) === 'UP');
      assert(await command(['exec', backend, 'sh', '-c', 'cd /app && sha256sum -c app.jar.sha256']) === 'app.jar: OK', 'JAR_CHECKSUM_FAILED');
      slot.phase = 'RUNTIME_BOUNDARIES'; save();
      for (const role of roles) {
        const value = one(await command(['container', 'inspect', slot.containers[role].id]));
        requirePrivate(value, role, slot, role === 'backend' ? input.backendImage.localImageId : input.infrastructure[role].localImageId);
      }
      check(); slot.checks = ['BACKEND_UP', 'JAR_CHECKSUM', 'EXACT_PRIVATE_CONTAINERS', 'NO_HOST_OR_PERSISTENT_MOUNTS'];
      slot.state = 'READY'; slot.phase = 'ACKNOWLEDGED'; save(); check();
      return { slotId: slot.slotId, resetNonce, clean: true };
    } catch {
      slot.state = 'QUARANTINED';
      try { save(); } catch { /* A recording failure never produces an acknowledgement. */ }
      throw new Error('EVALUATION_SLOT_RESET_FAILED');
    }
  }
  function resetSlot({ slotId, resetNonce, signal } = {}) {
    const slot = slots.get(slotId);
    assert(slot && !closed && !slot.inFlight && !slot.uncertainMutation, 'SLOT_UNAVAILABLE');
    assert(typeof resetNonce === 'string' && /^[A-Za-z0-9_-]{43}$/u.test(resetNonce)
      && Buffer.from(resetNonce, 'base64url').toString('base64url') === resetNonce
      && signal instanceof AbortSignal && !signal.aborted, 'INVALID_RESET_REQUEST');
    const operation = replace(slot, resetNonce, signal);
    slot.inFlight = operation;
    operation.then(() => { slot.inFlight = null; }, () => { slot.inFlight = null; });
    return operation;
  }
  function dispose() {
    if (disposeWork) return disposeWork;
    closed = true; stop.abort();
    disposeWork = (async () => {
      await Promise.allSettled([...slots.values()].map(slot => slot.inFlight));
      const results = [];
      for (const slot of slots.values()) {
        const result = await cleanup(slot); slot.state = 'QUARANTINED';
        results.push({ slotId: slot.slotId, ...result, uncertainMutation: slot.uncertainMutation });
      }
      let recorded = true; try { save(); } catch { recorded = false; }
      return { status: recorded && results.every(item => item.status === 'PASSED' && !item.uncertainMutation) ? 'PASSED' : 'FAILED',
        recorded, slots: results };
    })();
    return disposeWork;
  }
  save();
  return Object.freeze({ resetSlot, snapshot, dispose });
}
