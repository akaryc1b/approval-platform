import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import test from 'node:test';

import { exactUpgradeRefs } from '../product-readiness/capacity-recovery/upgrade-restore-refs.mjs';

const base = 'a'.repeat(40);
const head = 'b'.repeat(40);
const other = 'c'.repeat(40);
const pr = () => ({ pull_request: { base: { ref: 'main', sha: base }, head: { sha: head } } });
const push = () => ({ ref: 'refs/heads/main', before: base, after: head, created: false, deleted: false, forced: false });

function temporary(t) {
  const directory = mkdtempSync(join(tmpdir(), 'approval-upgrade-refs-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  return directory;
}

function environment(t, name, event) {
  const path = join(temporary(t), 'event.json');
  writeFileSync(path, JSON.stringify(event));
  return { GITHUB_ACTIONS: 'true', GITHUB_EVENT_NAME: name, GITHUB_EVENT_PATH: path, GITHUB_SHA: head };
}

function runner(responses = {}) {
  const calls = [];
  const run = (args) => {
    calls.push(args);
    const key = args.join(' ');
    if (!(key in responses)) throw new Error(`unexpected git command: ${key}`);
    const result = responses[key];
    if (result instanceof Error) throw result;
    return result;
  };
  return { calls, run };
}

function localRunner(mergeBase = base, parent = base) {
  return runner({
    'rev-parse HEAD': head,
    'fetch --no-tags origin main': '',
    'merge-base HEAD origin/main': mergeBase,
    [`fetch --no-tags --depth=2 origin ${head}`]: '',
    'rev-parse HEAD^1': parent,
  });
}

test('PR refs remain exact event refs, not a synthetic merge checkout SHA', (t) => {
  const git = runner();
  const env = environment(t, 'pull_request', pr());
  env.GITHUB_SHA = other;
  assert.deepEqual(exactUpgradeRefs(git.run, env), {
    source: 'GITHUB_PULL_REQUEST_EVENT', baseSha: base, candidateSha: head,
  });
  assert.deepEqual(git.calls, [], 'PR source tree equivalence remains checked by the rehearsal');
});

test('main push pins before/after and never reads moving origin/main', (t) => {
  const git = runner({ 'rev-parse HEAD': head });
  assert.deepEqual(exactUpgradeRefs(git.run, environment(t, 'push', push())), {
    source: 'GITHUB_PUSH_EVENT', baseSha: base, candidateSha: head,
  });
  assert.deepEqual(git.calls, [['rev-parse', 'HEAD']]);
});

for (const value of ['', 'main', '--upload-pack=bad', '0'.repeat(40), 'a'.repeat(39), 'A'.repeat(40), null, 12]) {
  test(`rejects malformed baseline ${String(value)} before fetching`, (t) => {
    for (const mode of ['pull_request', 'push']) {
      const event = mode === 'push' ? push() : pr();
      if (mode === 'push') event.before = value;
      else event.pull_request.base.sha = value;
      const git = runner();
      assert.throws(() => exactUpgradeRefs(git.run, environment(t, mode, event)), /full nonzero commit SHA/u);
      assert.equal(git.calls.length, 0);
    }
  });
}

test('rejects identical versions in both PR and push modes', (t) => {
  const first = pr(); first.pull_request.base.sha = head;
  const second = push(); second.before = head;
  for (const [mode, event] of [['pull_request', first], ['push', second]]) {
    assert.throws(() => exactUpgradeRefs(runner().run, environment(t, mode, event)), /distinct commits/u);
  }
});

for (const flag of ['created', 'deleted', 'forced']) {
  test(`rejects ${flag} pushes rather than choosing an invented baseline`, (t) => {
    assert.throws(() => exactUpgradeRefs(runner().run, environment(t, 'push', { ...push(), [flag]: true })), /normal update/u);
  });
}

test('rejects branch/tag pushes and PRs that do not target main', (t) => {
  for (const ref of ['refs/heads/feature', 'refs/tags/v1', undefined]) {
    assert.throws(() => exactUpgradeRefs(runner().run, environment(t, 'push', { ...push(), ref })), /normal update/u);
  }
  const event = pr(); event.pull_request.base.ref = 'feature';
  assert.throws(() => exactUpgradeRefs(runner().run, environment(t, 'pull_request', event)), /target main/u);
});

test('rejects a stale push checkout or inconsistent GitHub SHA', (t) => {
  const env = environment(t, 'push', push());
  assert.throws(() => exactUpgradeRefs(runner({ 'rev-parse HEAD': other }).run, env), /differs/u);
  env.GITHUB_SHA = other;
  assert.throws(() => exactUpgradeRefs(runner().run, env), /differs/u);
  delete env.GITHUB_SHA;
  assert.throws(() => exactUpgradeRefs(runner().run, env), /full nonzero/u);
});

test('malformed or missing candidate refs cannot be selected', (t) => {
  const first = pr(); delete first.pull_request.head;
  const second = push(); second.after = '0'.repeat(40);
  assert.throws(() => exactUpgradeRefs(runner().run, environment(t, 'pull_request', first)), /full nonzero/u);
  assert.throws(() => exactUpgradeRefs(runner().run, environment(t, 'push', second)), /full nonzero/u);
});

test('CI never silently falls back when its event is missing, invalid or unsupported', (t) => {
  const env = { GITHUB_ACTIONS: 'true', GITHUB_EVENT_NAME: 'push' };
  assert.throws(() => exactUpgradeRefs(runner().run, env), /event payload/u);
  for (const event of [null, [], 'push']) {
    assert.throws(() => exactUpgradeRefs(runner().run, environment(t, 'push', event)), /must be an object/u);
  }
  const malformed = environment(t, 'push', {});
  writeFileSync(malformed.GITHUB_EVENT_PATH, '{bad JSON');
  assert.throws(() => exactUpgradeRefs(runner().run, malformed), SyntaxError);
  rmSync(malformed.GITHUB_EVENT_PATH);
  assert.throws(() => exactUpgradeRefs(runner().run, malformed), /ENOENT/u);
  assert.throws(() => exactUpgradeRefs(runner().run, environment(t, 'schedule', {})), /unsupported/u);
});

test('local branch retains its distinct main merge base and ignores stale CI payload', () => {
  const git = localRunner();
  assert.deepEqual(exactUpgradeRefs(git.run, { GITHUB_ACTIONS: 'false', GITHUB_EVENT_PATH: '/does-not-exist' }), {
    source: 'LOCAL_ORIGIN_MAIN_MERGE_BASE', baseSha: base, candidateSha: head,
  });
  assert.equal(git.calls.length, 3);
});

test('local main uses a bounded fetch of the pinned HEAD and its first parent', () => {
  const git = localRunner(head);
  assert.deepEqual(exactUpgradeRefs(git.run, {}), {
    source: 'LOCAL_HEAD_FIRST_PARENT', baseSha: base, candidateSha: head,
  });
  assert.deepEqual(git.calls.slice(-2), [
    ['fetch', '--no-tags', '--depth=2', 'origin', head], ['rev-parse', 'HEAD^1'],
  ]);
});

test('dispatch supports both distinct merge base and main first-parent mode', (t) => {
  const env = environment(t, 'workflow_dispatch', { ref: 'main' });
  assert.equal(exactUpgradeRefs(localRunner().run, env).source, 'GITHUB_DISPATCH_ORIGIN_MAIN_MERGE_BASE');
  assert.equal(exactUpgradeRefs(localRunner(head).run, env).source, 'GITHUB_DISPATCH_HEAD_FIRST_PARENT');
  env.GITHUB_SHA = other;
  assert.throws(() => exactUpgradeRefs(localRunner().run, env), /dispatch candidate differs/u);
});

test('missing ancestry, failed fetch and same-version first parent fail closed', () => {
  assert.throws(() => exactUpgradeRefs(localRunner(new Error('no merge base')).run, {}), /no merge base/u);
  assert.throws(() => exactUpgradeRefs(localRunner(head, new Error('no first parent')).run, {}), /no first parent/u);
  assert.throws(() => exactUpgradeRefs(localRunner(head, head).run, {}), /distinct commits/u);
  const git = runner({ 'rev-parse HEAD': head, 'fetch --no-tags origin main': new Error('fetch failed') });
  assert.throws(() => exactUpgradeRefs(git.run, {}), /fetch failed/u);
  assert.throws(() => exactUpgradeRefs(null, {}), /must be a function/u);
});

function gitAt(cwd, args) {
  return execFileSync('git', args, {
    cwd, encoding: 'utf8', timeout: 10_000,
    env: { ...process.env, GIT_TERMINAL_PROMPT: '0', GIT_CONFIG_NOSYSTEM: '1',
      GIT_CONFIG_GLOBAL: process.platform === 'win32' ? 'NUL' : '/dev/null',
      GIT_ALLOW_PROTOCOL: 'file' },
    stdio: ['ignore', 'pipe', 'pipe'],
  }).trim();
}

function repository(t) {
  const root = temporary(t);
  gitAt(root, ['init', '--bare', '--initial-branch=main', 'remote.git']);
  gitAt(root, ['init', '--initial-branch=main', 'source']);
  const source = join(root, 'source');
  const git = args => gitAt(source, args);
  git(['config', 'user.name', 'Upgrade Refs Test']);
  git(['config', 'user.email', 'upgrade-refs@example.invalid']);
  git(['config', 'commit.gpgsign', 'false']);
  writeFileSync(join(source, 'baseline.txt'), 'baseline\n');
  git(['add', '.']); git(['commit', '-m', 'baseline']);
  const baseline = git(['rev-parse', 'HEAD']);
  git(['checkout', '-b', 'feature']);
  writeFileSync(join(source, 'candidate.txt'), 'candidate\n');
  git(['add', '.']); git(['commit', '-m', 'candidate']);
  const feature = git(['rev-parse', 'HEAD']);
  git(['checkout', 'main']);
  writeFileSync(join(source, 'main.txt'), 'main advance\n');
  git(['add', '.']); git(['commit', '-m', 'main advance']);
  const previousMain = git(['rev-parse', 'HEAD']);
  git(['remote', 'add', 'origin', pathToFileURL(join(root, 'remote.git')).href]);
  git(['push', 'origin', 'main', 'feature']);
  return { root, source, git, baseline, feature, previousMain };
}

test('real full Git graph preserves a feature/main merge base after refresh', (t) => {
  const repo = repository(t);
  repo.git(['checkout', 'feature']);
  const refs = exactUpgradeRefs(repo.git, {});
  assert.equal(refs.baseSha, repo.baseline);
  assert.equal(refs.candidateSha, repo.feature);
  assert.equal(refs.source, 'LOCAL_ORIGIN_MAIN_MERGE_BASE');
  assert.equal(repo.git(['rev-parse', '--is-shallow-repository']), 'false');
});

test('real depth-one main merge resolves its first parent, never itself or second parent', (t) => {
  const repo = repository(t);
  repo.git(['merge', '--no-ff', 'feature', '-m', 'merge feature']);
  const candidate = repo.git(['rev-parse', 'HEAD']);
  repo.git(['push', 'origin', 'main']);
  gitAt(repo.root, ['clone', '--depth=1', '--branch=main', pathToFileURL(join(repo.root, 'remote.git')).href, 'shallow']);
  const git = args => gitAt(join(repo.root, 'shallow'), args);
  assert.equal(git(['rev-parse', '--is-shallow-repository']), 'true');
  assert.equal(git(['merge-base', 'HEAD', 'origin/main']), candidate, 'reproduce the previous same-version baseline');
  const refs = exactUpgradeRefs(git, {});
  assert.equal(refs.baseSha, repo.previousMain);
  assert.equal(refs.candidateSha, candidate);
  assert.notEqual(refs.baseSha, repo.feature);
  assert.equal(refs.source, 'LOCAL_HEAD_FIRST_PARENT');

  const event = { ...push(), before: repo.baseline, after: candidate };
  const env = environment(t, 'push', event); env.GITHUB_SHA = candidate;
  const pushRefs = exactUpgradeRefs(git, env);
  assert.equal(pushRefs.baseSha, repo.baseline, 'multi-commit push must retain event.before, not just first parent');
});

test('contract reexports the tested resolver and existing CI suite imports these regressions', () => {
  const contract = readFileSync(new URL('../product-readiness/capacity-recovery/upgrade-restore-contract.mjs', import.meta.url), 'utf8');
  const suite = readFileSync(new URL('./product-readiness-capacity-recovery-upgrade-restore.test.mjs', import.meta.url), 'utf8');
  assert.match(contract, /export \{ exactUpgradeRefs \} from '\.\/upgrade-restore-refs\.mjs'/u);
  assert.doesNotMatch(contract, /function exactUpgradeRefs|function githubEvent/u);
  assert.match(suite, /import '\.\/product-readiness-capacity-recovery-upgrade-refs\.test\.mjs'/u);
});
