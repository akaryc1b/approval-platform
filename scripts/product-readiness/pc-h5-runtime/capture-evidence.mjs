import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const present = value => typeof value === 'string' && value.length > 0;
const finite = value => typeof value === 'number' && Number.isFinite(value);

export function verifyReadySurface(value, surface, businessKey, context = {}) {
  if (value?.surface !== surface || !present(value.url)
    || !finite(value.documentWidth) || value.documentWidth <= 0
    || !finite(value.viewportWidth) || value.viewportWidth <= 0
    || !['x', 'y', 'width', 'height'].every(key => finite(value.bounds?.[key]))
    || value.bounds.width <= 0 || value.bounds.height <= 0) {
    throw new Error('Capture surface evidence is incomplete');
  }
  const url = new URL(value.url);
  if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || url.username || url.password
    || (context.actorId !== undefined && url.searchParams.get('demoOperator') !== context.actorId)
    || (context.url !== undefined && value.url !== context.url)
    || (context.origin !== undefined && url.origin !== context.origin)) {
    throw new Error('Capture URL differs from its governed actor/origin/route');
  }
  if (surface.startsWith('pc-') && url.pathname !== '/approval/workbench') {
    throw new Error('PC capture route is not the workbench');
  }
  if (surface.startsWith('h5-')) {
    const route = url.hash.split('?')[0];
    if (route !== (surface === 'h5-list' ? '#/pages/task/list' : '#/pages/task/detail')) {
      throw new Error('H5 capture route differs from its surface');
    }
    if (surface === 'h5-detail' && new URLSearchParams(url.hash.split('?')[1]).get('id') !== value.taskId) {
      throw new Error('H5 detail route differs from captured task');
    }
  }
  if (businessKey !== undefined && (value.businessKey !== businessKey
    || !present(value.taskId) || !present(value.instanceId))) {
    throw new Error('Capture lost its exact business/task identity');
  }
  if (surface.endsWith('-list') && (!Number.isInteger(value.refresh) || value.refresh < 1
    || value.activeTab !== 'pending')) throw new Error('Capture list refresh is incomplete');
  return value;
}

export function verifyActionCaptures(screenshots, stages, directory, instanceId, businessKey) {
  const expected = stages.flatMap(stage => ['before', 'after', 'settled'].map(phase => `${stage.name}-${phase}.png`));
  if (!Array.isArray(screenshots) || screenshots.map(item => item.file).sort().join(',') !== expected.sort().join(',')) {
    throw new Error('Action evidence must retain the exact before, acknowledged and settled captures');
  }
  for (const stage of stages) {
    const get = suffix => screenshots.find(item => item.file === `${stage.name}-${suffix}.png`);
    const before = get('before');
    const after = get('after');
    const settled = get('settled');
    if (before.phase !== 'READY_BEFORE_ACTION' || after.phase !== 'IMMEDIATE_ACKNOWLEDGED_RESULT'
      || settled.phase !== 'SETTLED_DESTINATION' || after.successMessage !== '审批已同意') {
      throw new Error('Action capture phases are missing or conflated');
    }
    if (!present(stage.actorId)) throw new Error('Capture stage requires its authoritative actor');
    const expectedUrl = stage.client === 'pc'
      ? `http://127.0.0.1:5777/approval/workbench?demoOperator=${encodeURIComponent(stage.actorId)}`
      : `http://127.0.0.1:9000/?demoOperator=${encodeURIComponent(stage.actorId)}#/pages/task/list`;
    const initial = verifyReadySurface(before.readiness, `${stage.client}-list`, businessKey, { actorId: stage.actorId, url: expectedUrl });
    const destination = verifyReadySurface(settled.readiness, `${stage.client}-list`);
    if (initial.taskId !== stage.taskId || initial.instanceId !== instanceId
      || initial.pendingTotal !== 1 || initial.processedTotal !== 0 || initial.absentTaskId !== null
      || destination.url !== initial.url || destination.refresh <= initial.refresh
      || destination.absentTaskId !== stage.taskId || destination.pendingTotal !== 0 || destination.processedTotal !== 1
      || destination.taskId !== null || destination.instanceId !== null || destination.businessKey !== null) {
      throw new Error('Settled destination does not bind the completed action and refreshed UI');
    }
  }
  for (const screenshot of screenshots) {
    if (!/^[0-9a-f]{64}$/u.test(screenshot.sha256 || '')
      || createHash('sha256').update(readFileSync(resolve(directory, screenshot.file))).digest('hex') !== screenshot.sha256) {
      throw new Error('Capture file digest differs from its receipt');
    }
  }
}

export function verifyMatrixCaptures(value, urls) {
  const expected = ['pc-task-list.png', 'pc-task-detail.png', 'h5-task-list.png', 'h5-task-detail.png',
    ...(value.projectId === 'system-chromium' ? ['pc-confirmation-dialog.png'] : [])];
  if (!Array.isArray(value.captures) || value.captures.map(item => item.file).sort().join(',') !== expected.sort().join(',')) {
    throw new Error('Matrix capture phases are incomplete');
  }
  let identity;
  for (const capture of value.captures) {
    const surface = capture.file === 'pc-confirmation-dialog.png' ? 'pc-confirmation'
      : `${capture.file.startsWith('pc-') ? 'pc' : 'h5'}-${capture.file.includes('list') ? 'list' : 'detail'}`;
    if (capture.phase !== 'READY_BEFORE_ACTION') throw new Error('Matrix capture phase differs');
    const client = surface.startsWith('pc-') ? 'pc' : 'h5';
    const expectedUrl = new URL(urls[client]);
    if (surface === 'h5-detail') expectedUrl.hash = `/pages/task/detail?id=${encodeURIComponent(capture.readiness?.taskId || '')}`;
    const ready = verifyReadySurface(capture.readiness, surface, value.businessKey, { actorId: value.actorId, url: expectedUrl.toString() });
    const current = `${ready.taskId}|${ready.instanceId}`;
    if (identity && current !== identity) throw new Error('Matrix captures refer to different tasks');
    identity = current;
    if (surface.endsWith('-list') && (ready.pendingTotal !== 1 || ready.processedTotal !== 0)) {
      throw new Error('Matrix task-list counts differ');
    }
  }
  if ([...value.pc.screenshots, ...value.h5.screenshots].sort().join(',') !== expected.sort().join(',')) {
    throw new Error('Matrix screenshot list differs from phase receipts');
  }
}
