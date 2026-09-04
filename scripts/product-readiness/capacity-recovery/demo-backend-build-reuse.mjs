import { spawnSync } from 'node:child_process';
import {
  existsSync,
  mkdirSync,
  readFileSync,
  writeFileSync,
} from 'node:fs';
import { resolve } from 'node:path';

const sha = /^[0-9a-f]{40}$/u;
const evidenceKind = 'DEMO_BACKEND_EXACT_BUILD_IDENTITY_V1';
const markerRelativePath = '.runtime/demo-backend-build-identity.json';
const classesRelativePath = 'apps/server/target/classes';

function requiredRevision(value) {
  if (typeof value !== 'string'
      || !/^[0-9A-Za-z][0-9A-Za-z._-]*$/u.test(value)) {
    throw new Error('demo backend build revision is invalid');
  }
  return value;
}

function runGit(root, args) {
  const result = spawnSync('git', args, {
    cwd: root,
    encoding: 'utf8',
    env: process.env,
    shell: false,
    timeout: 10_000,
  });
  if (result.error || result.status !== 0) {
    throw new Error(
      `demo backend build identity git ${args.join(' ')} failed: `
        + `${result.error?.message || result.stderr || result.stdout}`,
    );
  }
  return result.stdout.trim();
}

export function observeCheckout(root) {
  const values = runGit(root, ['rev-parse', 'HEAD', 'HEAD^{tree}'])
    .split(/\s+/u)
    .filter(Boolean);
  if (values.length !== 2 || values.some(value => !sha.test(value))) {
    throw new Error('demo backend build identity is not an exact Git checkout');
  }
  return {
    commitSha: values[0],
    treeSha: values[1],
    clean: runGit(
      root,
      ['status', '--porcelain=v1', '--untracked-files=no'],
    ).length === 0,
  };
}

function markerPath(root) {
  return resolve(root, markerRelativePath);
}

function classesPath(root) {
  return resolve(root, classesRelativePath);
}

function exactMarker(value, revision, observation) {
  return value?.schemaVersion === 1
    && value.evidenceKind === evidenceKind
    && value.revision === revision
    && value.commitSha === observation.commitSha
    && value.treeSha === observation.treeSha
    && sha.test(value.commitSha || '')
    && sha.test(value.treeSha || '');
}

export function reusableBuild(
  root,
  revision,
  observation = observeCheckout(root),
) {
  const expectedRevision = requiredRevision(revision);
  if (!observation.clean
      || !existsSync(classesPath(root))
      || !existsSync(markerPath(root))) {
    return null;
  }
  let marker;
  try {
    marker = JSON.parse(readFileSync(markerPath(root), 'utf8'));
  } catch {
    return null;
  }
  return exactMarker(marker, expectedRevision, observation)
    ? Object.freeze({ ...marker })
    : null;
}

export function recordReusableBuild(
  root,
  revision,
  observation = observeCheckout(root),
) {
  const expectedRevision = requiredRevision(revision);
  if (!observation.clean) return null;
  if (!existsSync(classesPath(root))) {
    throw new Error(
      'successful demo backend build did not produce server target/classes',
    );
  }
  const marker = {
    schemaVersion: 1,
    evidenceKind,
    revision: expectedRevision,
    commitSha: observation.commitSha,
    treeSha: observation.treeSha,
    capturedAt: new Date().toISOString(),
  };
  mkdirSync(resolve(root, '.runtime'), { recursive: true, mode: 0o700 });
  writeFileSync(
    markerPath(root),
    `${JSON.stringify(marker)}\n`,
    { encoding: 'utf8', mode: 0o600 },
  );
  return Object.freeze({ ...marker });
}

export const exactBuildReuseContract = Object.freeze({
  classesRelativePath,
  evidenceKind,
  markerRelativePath,
});
