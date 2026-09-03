import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';

import { repositoryRoot } from './contract.mjs';

const relevantPaths = [
  /^\.gitignore$/u,
  /^package\.json$/u,
  /^apps\/mobile\/overlay\//u,
  /^apps\/mobile\/upstream\.json$/u,
  /^apps\/server\/src\/(?:main|test)\/java\/.*\/demo\//u,
  /^apps\/web\/overlay\/apps\/web-ele\/src\/(?:api\/approval|platform\/approval|views\/approval)/u,
  /^apps\/web\/overlay\/playground\/(?:__tests__\/e2e\/(?:product-readiness-pc-h5-runtime(?:-(?:api|diagnostics|ui)|\.spec)|product-readiness-h5-payment-runtime\.spec|product-readiness-quick-start-ready\.spec|product-readiness-browser-accessibility\.spec)\.ts|product-readiness\.playwright\.config\.ts|browser-accessibility\.playwright\.config\.ts)$/u,
  /^config\/demo\/purchase-payment-alpha-acceptance\.json$/u,
  /^config\/demo\/quick-start\.json$/u,
  /^config\/demo\/browser-accessibility-matrix\.json$/u,
  /^config\/demo\//u,
  /^scripts\/product-readiness\/(?:demo-backend|demo-client|demo-quickstart|pc-h5-runtime-smoke|purchase-payment-e2e|purchase-payment-scenario-contract|browser-accessibility)\.mjs$/u,
  /^scripts\/product-readiness\/browser-accessibility\//u,
  /^scripts\/product-readiness\/pc-h5-runtime\//u,
  /^scripts\/product-readiness\/purchase-payment-e2e\//u,
  /^scripts\/product-readiness\/quick-start\//u,
  /^scripts\/tests\/(?:m3-repository-hygiene|product-readiness-pc-h5-runtime-boundary|product-readiness-purchase-payment-e2e-boundary|product-readiness-quick-start-boundary|product-readiness-browser-accessibility-boundary)\.test\.mjs$/u,
  /^scripts\/upstream\/bootstrap-unibest\.mjs$/u,
];

const capacityCorePaths = [
  /^config\/demo\/capacity-recovery\.json$/u,
  /^scripts\/product-readiness\/capacity-recovery\.mjs$/u,
  /^scripts\/product-readiness\/capacity-recovery\//u,
  /^scripts\/tests\/product-readiness-capacity-recovery-[^/]+\.test\.mjs$/u,
  /^docs\/product-readiness\/CAPACITY_RECOVERY_ENVELOPE\.md$/u,
];

const capacityOnlyAllowedPaths = [
  ...capacityCorePaths,
  /^package\.json$/u,
  /^scripts\/tests\/m3-repository-hygiene\.test\.mjs$/u,
  /^scripts\/product-readiness\/pc-h5-runtime\/ci-scope\.mjs$/u,
  /^docs\/product-readiness\/README\.md$/u,
];

function gitExecutable() {
  return process.platform === 'win32' ? 'git.exe' : 'git';
}

function validCommit(value) {
  return typeof value === 'string' && /^[0-9a-f]{40}$/u.test(value);
}

function runGit(args) {
  return spawnSync(gitExecutable(), args, {
    cwd: repositoryRoot,
    encoding: 'utf8',
    env: process.env,
    shell: false,
  });
}

function fetchCommit(commitSha) {
  if (!validCommit(commitSha) || /^0+$/u.test(commitSha)) return false;
  const result = runGit([
    'fetch',
    '--no-tags',
    '--depth=1',
    'origin',
    commitSha,
  ]);
  return !result.error && result.status === 0;
}

function changedFiles(baseSha, headSha) {
  if (!fetchCommit(baseSha) || !fetchCommit(headSha)) return undefined;
  const result = runGit(['diff', '--name-only', baseSha, headSha]);
  if (result.error || result.status !== 0) return undefined;
  return result.stdout
    .split(/\r?\n/u)
    .map(value => value.trim())
    .filter(Boolean);
}

function githubEvent() {
  const eventPath = process.env.GITHUB_EVENT_PATH;
  if (!eventPath || !existsSync(eventPath)) return undefined;
  return JSON.parse(readFileSync(eventPath, 'utf8'));
}

function relevantChangeSet(files) {
  return files.some(path =>
    relevantPaths.some(pattern => pattern.test(path)));
}

function capacityOnlyChangeSet(files) {
  return files.some(path =>
    capacityCorePaths.some(pattern => pattern.test(path)))
    && files.every(path =>
      capacityOnlyAllowedPaths.some(pattern => pattern.test(path)));
}

export function shouldRunInCi() {
  if (process.env.GITHUB_ACTIONS !== 'true') {
    console.log('PC_H5_RUNTIME_SMOKE_SKIPPED_NON_CI');
    return false;
  }
  const eventName = process.env.GITHUB_EVENT_NAME;
  if (eventName === 'workflow_dispatch') return true;

  const event = githubEvent();
  let files;
  if (eventName === 'pull_request') {
    files = changedFiles(
      event?.pull_request?.base?.sha,
      event?.pull_request?.head?.sha,
    );
  } else if (eventName === 'push') {
    files = changedFiles(
      event?.before,
      event?.after || process.env.GITHUB_SHA,
    );
  }
  if (!files) {
    console.log('PC_H5_RUNTIME_SCOPE_UNAVAILABLE_RUNNING_FAIL_CLOSED');
    return true;
  }
  if (capacityOnlyChangeSet(files)) {
    console.log('PC_H5_RUNTIME_SCOPE=SKIPPED_CAPACITY_ONLY');
    return false;
  }

  const selected = relevantChangeSet(files);
  console.log(`PC_H5_RUNTIME_SCOPE=${selected ? 'SELECTED' : 'SKIPPED'}`);
  if (selected) {
    console.log(files.filter(path =>
      relevantPaths.some(pattern => pattern.test(path))).join('\n'));
  }
  return selected;
}
