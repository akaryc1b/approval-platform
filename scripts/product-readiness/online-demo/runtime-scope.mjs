import { readFileSync } from 'node:fs';
import { requireSha } from './images-contract.mjs';
import { runCommand } from './images-build.mjs';

const relevant = [
  /^deploy\/online-demo\/images\//u,
  /^scripts\/product-readiness\/online-demo(?:\/|[^/]*\.mjs$)/u,
  /^scripts\/tests\/product-readiness-online-demo-[^/]+\.test\.mjs$/u,
  /^(?:apps\/server|server-modules|integrations\/host-sdk|packages)\//u,
  /^apps\/(?:web|mobile)\//u,
  /^scripts\/upstream\//u,
  /^config\/(?:demo|checkstyle)\//u,
  /^(?:pom\.xml|package\.json|pnpm-lock\.yaml|pnpm-workspace\.yaml|\.npmrc)$/u,
  /^\.github\/workflows\/approval-platform-validation\.yml$/u,
];

export function relevantImageChanges(files) {
  if (!Array.isArray(files) || files.some(path => typeof path !== 'string'
      || !path || path.startsWith('/') || path.includes('\\')
      || /[\x00-\x1f\x7f]/u.test(path) || path.split('/').includes('..'))) {
    throw new Error('invalid image scope path list');
  }
  return files.some(path => !/\.(?:md|txt)$/iu.test(path)
    && relevant.some(pattern => pattern.test(path)));
}

export function selectImageRuntimeScope(root, environment = process.env, run = runCommand) {
  if (environment.GITHUB_ACTIONS !== 'true') {
    return { selected: false, reason: 'NON_CI_USE_RUN_EXPLICITLY' };
  }
  const event = JSON.parse(readFileSync(environment.GITHUB_EVENT_PATH, 'utf8'));
  const type = environment.GITHUB_EVENT_NAME;
  const head = requireSha(run('git', ['rev-parse', 'HEAD'], { cwd: root }), 'checkout');
  let base;
  let candidate;
  if (type === 'pull_request') {
    if (event.pull_request?.base?.ref !== 'main') throw new Error('unexpected PR target');
    base = requireSha(event.pull_request.base.sha, 'PR base');
    candidate = requireSha(event.pull_request.head?.sha, 'PR head');
  } else if (type === 'push') {
    if (event.ref !== 'refs/heads/main' || event.created || event.deleted || event.forced) {
      throw new Error('unsupported image verification push');
    }
    base = requireSha(event.before, 'push before');
    candidate = requireSha(event.after, 'push after');
    if (candidate !== environment.GITHUB_SHA) throw new Error('push SHA mismatch');
  } else if (type === 'workflow_dispatch') {
    candidate = requireSha(environment.GITHUB_SHA, 'dispatch SHA');
  } else {
    throw new Error('unsupported image verification event');
  }
  // This job deliberately checks out the exact PR head, not a synthetic merge.
  if (candidate !== head) throw new Error('image verification checkout is not the event candidate');
  if (base === candidate) throw new Error('image scope requires distinct revisions');
  if (!base) return { selected: true, reason: 'EXPLICIT_DISPATCH', candidate };
  const output = run('git', ['diff', '--name-only', '-z', base, candidate], { cwd: root });
  const files = String(output).split('\0').filter(Boolean);
  const selected = relevantImageChanges(files);
  return { selected, reason: selected ? 'IMAGE_INPUTS_CHANGED' : 'NO_IMAGE_INPUT_CHANGE', base, candidate, files };
}
