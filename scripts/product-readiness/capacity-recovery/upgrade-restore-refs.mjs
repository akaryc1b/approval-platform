import { readFileSync } from 'node:fs';

function commitSha(value, label) {
  if (typeof value !== 'string' || !/^[0-9a-f]{40}$/u.test(value)
      || /^0+$/u.test(value)) {
    throw new Error(`${label} must be a full nonzero commit SHA`);
  }
  return value;
}

function distinctRefs(source, baseSha, candidateSha) {
  commitSha(baseSha, 'upgrade baseline');
  commitSha(candidateSha, 'upgrade candidate');
  if (baseSha === candidateSha) {
    throw new Error('upgrade baseline and candidate must be distinct commits');
  }
  return { source, baseSha, candidateSha };
}

function readEvent(environment) {
  if (!environment.GITHUB_EVENT_PATH) {
    throw new Error('upgrade rehearsal requires the GitHub event payload in CI');
  }
  const event = JSON.parse(readFileSync(environment.GITHUB_EVENT_PATH, 'utf8'));
  if (!event || typeof event !== 'object' || Array.isArray(event)) {
    throw new Error('upgrade rehearsal GitHub event must be an object');
  }
  return event;
}

/** Resolve immutable versions, never a same-version restore labelled as upgrade. */
export function exactUpgradeRefs(runGit, environment = process.env) {
  if (typeof runGit !== 'function') throw new Error('runGit must be a function');
  const inCi = environment.GITHUB_ACTIONS === 'true';
  const eventName = inCi ? environment.GITHUB_EVENT_NAME : null;
  const event = inCi ? readEvent(environment) : null;

  if (eventName === 'pull_request') {
    const pullRequest = event.pull_request;
    if (pullRequest?.base?.ref !== 'main') {
      throw new Error('upgrade rehearsal PR must target main');
    }
    // A PR can check out GitHub's synthetic merge commit. The rehearsal's
    // existing sourceIdentity/tree check verifies its equivalence to this Head.
    return distinctRefs(
      'GITHUB_PULL_REQUEST_EVENT',
      pullRequest.base.sha,
      pullRequest.head?.sha,
    );
  }

  if (eventName === 'push') {
    if (event.ref !== 'refs/heads/main' || event.created === true
        || event.deleted === true || event.forced === true) {
      throw new Error('upgrade rehearsal requires a normal update of existing main');
    }
    const refs = distinctRefs('GITHUB_PUSH_EVENT', event.before, event.after);
    if (commitSha(environment.GITHUB_SHA, 'GitHub push SHA') !== refs.candidateSha
        || commitSha(runGit(['rev-parse', 'HEAD']), 'checked-out SHA')
          !== refs.candidateSha) {
      throw new Error('push candidate differs from the event or checked-out Head');
    }
    // before/after are the immutable transition for this push, even when main
    // advances again or the push contains multiple commits. Do not fetch main.
    return refs;
  }

  if (inCi && eventName !== 'workflow_dispatch') {
    throw new Error(`unsupported upgrade rehearsal CI event: ${eventName}`);
  }
  const candidateSha = commitSha(runGit(['rev-parse', 'HEAD']), 'local Head SHA');
  if (inCi && commitSha(environment.GITHUB_SHA, 'GitHub dispatch SHA') !== candidateSha) {
    throw new Error('dispatch candidate differs from the checked-out Head');
  }
  // Do not truncate an existing full graph to depth one before merge-base.
  runGit(['fetch', '--no-tags', 'origin', 'main']);
  const mergeBase = commitSha(
    runGit(['merge-base', 'HEAD', 'origin/main']),
    'local merge-base SHA',
  );
  if (mergeBase !== candidateSha) {
    return distinctRefs(
      inCi ? 'GITHUB_DISPATCH_ORIGIN_MAIN_MERGE_BASE' : 'LOCAL_ORIGIN_MAIN_MERGE_BASE',
      mergeBase,
      candidateSha,
    );
  }

  // On main, merge-base is HEAD itself. Fetch just two generations of the
  // pinned candidate so a depth-one checkout can resolve its first parent.
  runGit(['fetch', '--no-tags', '--depth=2', 'origin', candidateSha]);
  return distinctRefs(
    inCi ? 'GITHUB_DISPATCH_HEAD_FIRST_PARENT' : 'LOCAL_HEAD_FIRST_PARENT',
    runGit(['rev-parse', 'HEAD^1']),
    candidateSha,
  );
}
