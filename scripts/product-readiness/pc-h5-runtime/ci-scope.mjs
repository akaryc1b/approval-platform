import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';

import { repositoryRoot } from './contract.mjs';

const relevantPaths = [
  /^apps\/mobile\/overlay\/src\/(?:api\/approval|pages\/task|platform\/approval)/u,
  /^apps\/server\/src\/(?:main|test)\/java\/.*\/demo\//u,
  /^apps\/web\/overlay\/apps\/web-ele\/src\/(?:api\/approval|platform\/approval|views\/approval)/u,
  /^apps\/web\/overlay\/playground\/(?:__tests__\/e2e\/product-readiness-pc-h5-runtime(?:-(?:api|diagnostics|ui)|\.spec)\.ts|product-readiness\.playwright\.config\.ts)$/u,
  /^config\/demo\//u,
  /^scripts\/product-readiness\/(?:demo-backend|demo-client|pc-h5-runtime-smoke|purchase-payment-scenario-contract)\.mjs$/u,
  /^scripts\/product-readiness\/pc-h5-runtime\//u,
  /^scripts\/tests\/product-readiness-pc-h5-runtime-boundary\.test\.mjs$/u,
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

  const selected = relevantChangeSet(files);
  console.log(`PC_H5_RUNTIME_SCOPE=${selected ? 'SELECTED' : 'SKIPPED'}`);
  if (selected) {
    console.log(files.filter(path =>
      relevantPaths.some(pattern => pattern.test(path))).join('\n'));
  }
  return selected;
}
