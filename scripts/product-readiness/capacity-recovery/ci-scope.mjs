import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';

import { repositoryRoot } from './contract.mjs';

const relevantPaths = [
  /^\.github\/workflows\/approval-platform-validation\.yml$/u,
  /^package\.json$/u,
  /^config\/demo\/capacity-recovery\.json$/u,
  /^config\/demo\/purchase-payment-(?:alpha-acceptance|golden-path)\.json$/u,
  /^scripts\/product-readiness\/(?:capacity-recovery|demo-backend|purchase-payment-e2e)\.mjs$/u,
  /^scripts\/product-readiness\/capacity-recovery\//u,
  /^scripts\/product-readiness\/purchase-payment-e2e\//u,
  /^scripts\/tests\/(?:m3-repository-hygiene|product-readiness-capacity-recovery-boundary)\.test\.mjs$/u,
  /^apps\/server\/src\/(?:main|test)\/java\/.*\/(?:api|demo)\//u,
  /^server-modules\/approval-(?:application|domain|persistence-jdbc)\//u,
  /^deploy\/compose\/docker-compose\.yml$/u,
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

function selectedFiles(files) {
  return files.filter(path =>
    relevantPaths.some(pattern => pattern.test(path)));
}

export function shouldRunInCi() {
  if (process.env.GITHUB_ACTIONS !== 'true') {
    console.log('CAPACITY_RECOVERY_SCOPE=SKIPPED_NON_CI');
    return false;
  }
  const eventName = process.env.GITHUB_EVENT_NAME;
  if (eventName === 'workflow_dispatch') {
    console.log('CAPACITY_RECOVERY_SCOPE=SELECTED_WORKFLOW_DISPATCH');
    return true;
  }

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
    console.log('CAPACITY_RECOVERY_SCOPE=SELECTED_FAIL_CLOSED');
    return true;
  }
  const selected = selectedFiles(files);
  console.log(
    `CAPACITY_RECOVERY_SCOPE=${selected.length > 0 ? 'SELECTED' : 'SKIPPED'}`,
  );
  if (selected.length > 0) console.log(selected.join('\n'));
  return selected.length > 0;
}
