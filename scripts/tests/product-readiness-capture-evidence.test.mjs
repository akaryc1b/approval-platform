import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { verifyActionCaptures, verifyMatrixCaptures, verifyReadySurface } from '../product-readiness/pc-h5-runtime/capture-evidence.mjs';
import { relevantChangeSet } from '../product-readiness/pc-h5-runtime/ci-scope.mjs';

function readiness(surface = 'h5-list') {
  const pc = surface.startsWith('pc-');
  return {
    surface, url: pc ? 'http://127.0.0.1:5777/approval/workbench?demoOperator=actor'
      : `http://127.0.0.1:9000/?demoOperator=actor#${surface.endsWith('list') ? '/pages/task/list' : '/pages/task/detail?id=task'}`,
    taskId: 'task', instanceId: 'instance', businessKey: 'business',
    refresh: 1, activeTab: 'pending', pendingTotal: 1, processedTotal: 0, absentTaskId: null,
    documentWidth: 430, viewportWidth: 390, bounds: { x: 0, y: 0, width: 390, height: 844 },
  };
}

test('all original and added screenshot phases bind actual files, completed task and actor', () => {
  const directory = mkdtempSync(join(tmpdir(), 'capture-receipts-'));
  try {
    const bytes = Buffer.from('unchanged screenshot bytes');
    const sha256 = createHash('sha256').update(bytes).digest('hex');
    const initial = readiness();
    const settled = { ...initial, refresh: 2, taskId: null, instanceId: null, businessKey: null,
      pendingTotal: 0, processedTotal: 1, absentTaskId: 'task' };
    const screenshots = [
      { file: 'h5-payment-before.png', phase: 'READY_BEFORE_ACTION', sha256, readiness: initial },
      { file: 'h5-payment-after.png', phase: 'IMMEDIATE_ACKNOWLEDGED_RESULT', sha256, successMessage: '审批已同意' },
      { file: 'h5-payment-settled.png', phase: 'SETTLED_DESTINATION', sha256, readiness: settled },
    ];
    for (const screenshot of screenshots) writeFileSync(join(directory, screenshot.file), bytes);
    const stages = [{ name: 'h5-payment', client: 'h5', taskId: 'task', actorId: 'actor' }];
    const verify = value => verifyActionCaptures(value, stages, directory, 'instance', 'business');
    assert.doesNotThrow(() => verify(screenshots));
    for (const mutate of [
      value => value.pop(),
      value => value[1].phase = 'SETTLED_DESTINATION',
      value => delete value[1].successMessage,
      value => value[2].readiness.refresh = 1,
      value => value[2].readiness.absentTaskId = 'other',
      value => value[2].readiness.pendingTotal = 1,
      value => value[0].readiness.taskId = 'other',
      value => value[0].readiness.instanceId = 'other',
      value => value[0].readiness.activeTab = 'processed',
      value => value[0].readiness.url = value[0].readiness.url.replace('actor', 'other'),
      value => value[0].readiness.url = value[0].readiness.url.replace('127.0.0.1', 'example.test'),
      value => value[0].readiness.url = value[0].readiness.url.replace('9000', '9999'),
      value => value[1].sha256 = '0'.repeat(64),
      value => value[2].file = 'h5-payment-after.png',
    ]) {
      const changed = structuredClone(screenshots);
      mutate(changed);
      assert.throws(() => verify(changed));
    }
    // Overflow stays visible evidence. Readiness never labels it as a layout fix.
    assert.equal(initial.documentWidth > initial.viewportWidth, true);
  } finally {
    rmSync(directory, { force: true, recursive: true });
  }
});

test('matrix capture receipts preserve each original file and the same current task', () => {
  const value = {
    projectId: 'system-chromium', businessKey: 'business', actorId: 'actor',
    pc: { screenshots: ['pc-task-list.png', 'pc-task-detail.png', 'pc-confirmation-dialog.png'] },
    h5: { screenshots: ['h5-task-list.png', 'h5-task-detail.png'] },
    captures: ['pc-list', 'pc-detail', 'pc-confirmation', 'h5-list', 'h5-detail'].map(surface => ({
      file: surface === 'pc-confirmation' ? 'pc-confirmation-dialog.png' : surface.replace('-', '-task-') + '.png',
      phase: 'READY_BEFORE_ACTION', readiness: readiness(surface),
    })),
  };
  const urls = { pc: readiness('pc-list').url, h5: readiness('h5-list').url };
  assert.doesNotThrow(() => verifyMatrixCaptures(value, urls));
  for (const mutate of [
    v => v.captures.pop(),
    v => v.captures[2].readiness.taskId = 'other',
    v => v.captures[1].phase = 'IMMEDIATE_ACKNOWLEDGED_RESULT',
    v => v.h5.screenshots.pop(),
    v => v.captures[4].readiness.url = v.captures[4].readiness.url.replace('id=task', 'id=other'),
  ]) {
    const changed = structuredClone(value);
    mutate(changed);
    assert.throws(() => verifyMatrixCaptures(changed, urls));
  }
  assert.throws(() => verifyReadySurface({}, 'h5-list', 'business'));
});

test('validator and both cold-style graph configuration inputs select the real CI path', () => {
  for (const path of [
    'scripts/tests/product-readiness-capture-evidence.test.mjs',
    'scripts/tests/web-cold-start-style-graph.test.mjs',
    'apps/web/overlay/apps/web-ele/vite.config.ts',
    'apps/web/upstream.json',
    'scripts/upstream/bootstrap-vben.mjs',
  ]) assert.equal(relevantChangeSet([path]), true, path);
  assert.equal(relevantChangeSet(['docs/unrelated-notes.md']), false);
});
