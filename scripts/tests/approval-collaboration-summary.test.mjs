import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { runInNewContext } from 'node:vm';

const paths = [
  ['Web', new URL('../../apps/web/overlay/apps/web-ele/src/views/approval/collaboration/index.vue', import.meta.url)],
  ['H5', new URL('../../apps/mobile/overlay/src/pages/collaboration/index.vue', import.meta.url)],
];
const firstId = '60000000-0000-0000-0000-000000000001';
const secondId = '60000000-0000-0000-0000-000000000002';

function loader(source, find) {
  const start = source.indexOf('async function loadNotificationSummary(');
  const end = source.indexOf('\nasync function loadData(', start);
  assert.ok(start >= 0 && end > start);
  const body = source.slice(start, end).replace('value: unknown', 'value');
  assert.doesNotMatch(body, /selectedTask|selectedPolicy|decideTaskCollaboration|createTaskCollaboration|addTaskCollaborators/);
  const state = {
    notificationSummary: { value: undefined }, notificationSummaryLoading: { value: false },
    notificationSummaryError: { value: '' }, notificationTaskId: { value: '' },
  };
  const api = runInNewContext(`let notificationSummaryRequest = 0;\n${body}
    ({ load: loadNotificationSummary, invalidate: () => { notificationSummaryRequest++; } })`, {
    ...state, findTaskCollaboration: find,
  });
  return { ...api, ...state };
}

function policy(taskId, status = 'SATISFIED', participantStatus = 'PENDING') {
  return { taskId, taskName: 'Approval task', reason: 'Review request', mode: 'ANY', status,
    progress: { approvedCount: 1, pendingCount: 1 },
    participants: [{ participantUserId: 'viewer', status: participantStatus }] };
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((accept, fail) => { resolve = accept; reject = fail; });
  return { promise, resolve, reject };
}

for (const [client, path] of paths) {
  const source = readFileSync(path, 'utf8');
  test(`${client} independently reads current and terminal policy summaries, including ANY undecided and removed members`, async () => {
    for (const [status, participant] of [
      ['ACTIVE', 'PENDING'], ['SATISFIED', 'PENDING'], ['SATISFIED', 'CANCELED'],
      ['SATISFIED', 'APPROVED'], ['ACTIVE', 'REMOVED'], ['CANCELED', 'CANCELED'], ['REJECTED', 'REJECTED'],
    ]) {
      const value = policy(firstId, status, participant);
      const calls = [];
      const subject = loader(source, async id => { calls.push(id); return value; });
      await subject.load(firstId);
      assert.deepEqual(calls, [firstId]);
      assert.equal(subject.notificationSummary.value, value);
      assert.equal(subject.notificationSummaryError.value, '');
      assert.equal(subject.notificationSummaryLoading.value, false);
    }
  });

  test(`${client} missing or malformed taskId never performs a policy read or opens discussion`, async () => {
    const calls = [];
    const subject = loader(source, async id => calls.push(id));
    for (const value of [undefined, null, '']) {
      await subject.load(value);
      assert.equal(subject.notificationSummary.value, undefined);
      assert.equal(subject.notificationSummaryError.value, '');
    }
    for (const value of ['bad-id', '../other', ' ' + firstId, [firstId], 42]) {
      await subject.load(value);
      assert.equal(subject.notificationSummary.value, undefined);
      assert.equal(subject.notificationSummaryError.value, '通知中的任务标识无效，请从协作列表查看。');
    }
    assert.deepEqual(calls, []);
  });

  test(`${client} absent, unauthorized and cross-tenant policy reads share a non-disclosing empty state`, async () => {
    // The existing authenticated API normalizes these resource404 outcomes to undefined.
    for (const outcome of ['missing', 'unauthorized', 'cross-tenant']) {
      const subject = loader(source, async () => undefined);
      await subject.load(firstId);
      assert.equal(subject.notificationSummary.value, undefined, outcome);
      assert.equal(subject.notificationSummaryError.value, '协作事项不存在或当前账号无权查看。', outcome);
      assert.equal(subject.notificationSummaryLoading.value, false);
    }
  });

  test(`${client} stale successes and failures cannot replace a newer query result`, async () => {
    for (const staleOutcome of ['resolve', 'reject']) {
      const old = deferred();
      const fresh = deferred();
      const subject = loader(source, id => id === firstId ? old.promise : fresh.promise);
      const pendingOld = subject.load(firstId);
      const pendingNew = subject.load(secondId);
      const newest = policy(secondId);
      fresh.resolve(newest);
      await pendingNew;
      if (staleOutcome === 'resolve') old.resolve(policy(firstId));
      else old.reject(new Error('stale failure'));
      await pendingOld;
      assert.equal(subject.notificationSummary.value, newest);
      assert.equal(subject.notificationTaskId.value, secondId);
      assert.equal(subject.notificationSummaryError.value, '');
      assert.equal(subject.notificationSummaryLoading.value, false);
    }
  });

  test(`${client} invalidated or removed queries cannot publish a late response`, async () => {
    for (const invalidate of ['empty-query', 'invalid-query', 'unload']) {
      const pending = deferred();
      const subject = loader(source, () => pending.promise);
      const request = subject.load(firstId);
      if (invalidate === 'unload') subject.invalidate();
      else await subject.load(invalidate === 'empty-query' ? undefined : 'bad-id');
      pending.resolve(policy(firstId));
      await request;
      assert.equal(subject.notificationSummary.value, undefined);
    }
  });

  test(`${client} summary card is read-only and never enables management or decisions`, () => {
    const cardStart = source.indexOf(client === 'Web' ? '      <ElCard\n        v-if="notificationSummary' : '    <view\n      v-if="notificationSummary');
    const cardEnd = source.indexOf(client === 'Web' ? '\n      <ElCard shadow="never">' : '\n    <view class="mode-tabs">', cardStart);
    assert.ok(cardStart > 0 && cardEnd > cardStart);
    const card = source.slice(cardStart, cardEnd);
    assert.match(card, /notificationSummary\.taskName/);
    assert.match(card, /policyStatusLabel\(notificationSummary\.status\)/);
    assert.match(card, /participantStatusLabel\(notificationParticipant\.status\)/);
    assert.match(card, /notificationSummary\.reason/);
    assert.match(card, /notificationSummary\.progress\.approvedCount/);
    assert.doesNotMatch(card, /@click|<ElButton|<wd-button|selectedTask|selectedPolicy|decide\(/);
    assert.match(source, /notificationSummary\.value\?\.participants\.find\([\s\S]*?participant\.participantUserId === currentOperatorId/);
    // Decision controls continue to consume the existing server-owned pending list only.
    assert.match(source, client === 'Web' ? /:data="collaborationTasks"/ : /v-for="item in collaborationTasks"/);
    if (client === 'Web') {
      assert.match(source, /watch\(\(\) => route\.query\.taskId, loadNotificationSummary, \{ immediate: true \}\)/);
      assert.match(source, /onUnmounted\(\(\) => \{ notificationSummaryRequest\+\+;/);
    } else {
      assert.match(source, /onLoad\(query => \{ void loadNotificationSummary\(query\?\.taskId\)/);
      assert.match(source, /onUnload\(\(\) => \{ notificationSummaryRequest\+\+/);
    }
  });
}
