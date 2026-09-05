import { createHash } from 'node:crypto';

export const components = Object.freeze(['backend', 'pc', 'h5']);
export const imageInputs = Object.freeze(['maven', 'java', 'node', 'nginx']);
export const limits = Object.freeze({ archiveBytes: 256 * 1024 * 1024, buildMs: 60 * 60_000 });
const repositories = {
  maven: ['maven', 'docker.io/library/maven'],
  java: ['eclipse-temurin', 'docker.io/library/eclipse-temurin'],
  node: ['node', 'docker.io/library/node'],
  nginx: ['nginx', 'docker.io/library/nginx'],
};
export const nonClaims = Object.freeze([
  'ONLINE_DEMO_NOT_AVAILABLE', 'PUBLIC_URL_NOT_PUBLISHED',
  'ONLINE_SESSION_ISOLATION_NOT_VERIFIED', 'ONLINE_RESET_NOT_VERIFIED',
  'IMAGE_RUNTIME_NOT_VERIFIED', 'IMAGE_VULNERABILITY_SCAN_NOT_EXECUTED',
  'BIT_REPRODUCIBLE_BUILD_NOT_VERIFIED', 'REGISTRY_PUSH_NOT_EXECUTED',
  'PRODUCTION_DEPLOYMENT_NOT_VERIFIED', 'RELEASE_NOT_CREATED',
]);

export class UsageError extends Error {}
export function digest(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}
export function requireSha(value, name) {
  if (!/^[0-9a-f]{40}$/u.test(value || '') || /^0+$/u.test(value)) {
    throw new Error(`${name} must be a nonzero, full Git SHA`);
  }
  return value;
}
export function pinnedImage(value, name) {
  const match = typeof value === 'string'
    && value.match(/^([a-z0-9][a-z0-9./_-]*)(?::([A-Za-z0-9_][A-Za-z0-9_.-]{0,127}))?@sha256:([0-9a-f]{64})$/u);
  if (!match || !repositories[name]?.includes(match[1]) || /^0+$/u.test(match[3])) {
    throw new UsageError(`${name} image requires an approved repository and an explicit sha256 digest`);
  }
  return value;
}
export function parseArguments(argv) {
  const values = [...argv];
  const command = values.shift() || 'plan';
  if (!['plan', 'build'].includes(command)) throw new UsageError('command must be plan or build');
  if (values[0] === '--') values.shift();
  const result = { command, platform: 'linux/amd64', images: {} };
  const seen = new Set();
  for (let index = 0; index < values.length; index += 1) {
    const flag = values[index];
    if (seen.has(flag)) throw new UsageError(`duplicate option ${flag}`);
    seen.add(flag);
    if (flag === '--json' && command === 'plan') continue;
    const name = imageInputs.find(item => flag === `--${item}-image`);
    if (flag !== '--platform' && !name) throw new UsageError(`unknown option ${flag}`);
    const value = values[++index];
    if (!value || value.startsWith('--')) throw new UsageError(`${flag} requires a value`);
    if (name) result.images[name] = pinnedImage(value, name);
    else result.platform = value;
  }
  if (!['linux/amd64', 'linux/arm64'].includes(result.platform)) {
    throw new UsageError('platform must be linux/amd64 or linux/arm64');
  }
  return result;
}
export function validateSource(source) {
  requireSha(source.commitSha, 'commitSha');
  requireSha(source.treeSha, 'treeSha');
  if (!/^[0-9][0-9A-Za-z._-]{0,79}$/u.test(source.version || '')) {
    throw new Error('invalid application revision');
  }
  if (!Number.isSafeInteger(source.epoch) || source.epoch <= 0) throw new Error('invalid commit time');
  for (const name of ['pc', 'h5']) requireSha(source.upstreams?.[name], `${name} upstream`);
  return source;
}
export function plan(source, options) {
  validateSource(source);
  const missing = imageInputs.filter(name => !options.images[name]);
  const inputs = Object.fromEntries(imageInputs.map(name => [
    name, options.images[name] ? pinnedImage(options.images[name], name) : 'REQUIRED_DIGEST_PIN',
  ]));
  const buildKey = digest(JSON.stringify({ source, inputs, platform: options.platform })).slice(0, 16);
  return {
    schemaVersion: 1,
    kind: 'ONLINE_DEMO_IMAGE_BUILD_PLAN',
    status: missing.length ? 'INPUTS_REQUIRED' : 'BUILD_INPUTS_VALIDATED_ONLY',
    source,
    platform: options.platform,
    baseImages: inputs,
    missingInputs: missing,
    sourceContext: 'PINNED_GIT_ARCHIVE_NOT_WORKING_DIRECTORY',
    outputRoot: '.runtime/online-demo-images/<run-id>/',
    images: components.map(component => ({
      component,
      tag: `approval-online-${component}:${source.commitSha.slice(0, 12)}-${buildKey}`,
      dockerfile: component === 'backend'
        ? 'deploy/online-demo/images/backend.Dockerfile'
        : 'deploy/online-demo/images/clients.Dockerfile',
      target: component,
    })),
    dependencyPolicy: {
      root: 'FROZEN_LOCKFILE', pc: 'FROZEN_LOCKFILE',
      h5: 'EXISTING_NON_FROZEN_INSTALL_RESOLVED_LOCK_RETAINED_IN_IMAGE',
    },
    nonClaims,
  };
}
export function dockerBuildArguments(buildPlan, image, archiveSha256, iidFile) {
  if (buildPlan.missingInputs.length) throw new Error('base-image inputs are incomplete');
  if (!/^[0-9a-f]{64}$/u.test(archiveSha256)) throw new Error('invalid archive digest');
  const args = ['build', '--pull', '--platform', buildPlan.platform, '--file', image.dockerfile,
    '--target', image.target, '--tag', image.tag, '--iidfile', iidFile];
  const values = {
    SOURCE_COMMIT: buildPlan.source.commitSha, SOURCE_TREE: buildPlan.source.treeSha,
    SOURCE_DATE_EPOCH: String(buildPlan.source.epoch), SOURCE_ARCHIVE_SHA256: archiveSha256,
    APP_VERSION: buildPlan.source.version,
    MAVEN_IMAGE: buildPlan.baseImages.maven, JAVA_IMAGE: buildPlan.baseImages.java,
    NODE_IMAGE: buildPlan.baseImages.node, NGINX_IMAGE: buildPlan.baseImages.nginx,
  };
  for (const [key, value] of Object.entries(values)) args.push('--build-arg', `${key}=${value}`);
  args.push('-');
  return args;
}
export function verifyImageInspection(value, buildPlan, image, imageId, archiveSha256) {
  const expected = {
    'org.opencontainers.image.revision': buildPlan.source.commitSha,
    'org.opencontainers.image.version': buildPlan.source.version,
    'io.approval.source.tree': buildPlan.source.treeSha,
    'io.approval.source.archive': archiveSha256,
    'io.approval.component': image.component,
  };
  if (!/^sha256:[0-9a-f]{64}$/u.test(imageId || '') || value?.Id !== imageId) {
    throw new Error('Docker image ID differs from the build result');
  }
  if (`${value.Os}/${value.Architecture}` !== buildPlan.platform) throw new Error('image platform mismatch');
  const user = image.component === 'backend' ? '10001:10001' : '101:101';
  if (value.Config?.User !== user) throw new Error('image runtime user mismatch');
  for (const [key, item] of Object.entries(expected)) {
    if (value.Config?.Labels?.[key] !== item) throw new Error(`image label mismatch: ${key}`);
  }
  return { component: image.component, tag: image.tag, localImageId: imageId,
    platform: buildPlan.platform, user, registryDigest: null };
}
