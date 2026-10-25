import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import test from 'node:test';
import { runInNewContext } from 'node:vm';

const web = new URL('../../apps/web/overlay/apps/web-ele/src/views/approval/notifications/index.vue', import.meta.url);
const mobile = new URL('../../apps/mobile/overlay/src/pages/notification/index.vue', import.meta.url);
const routeModule = new URL('../../apps/web/overlay/apps/web-ele/src/router/routes/modules/approval.ts', import.meta.url);
const localDemoModule = new URL('../../apps/web/overlay/apps/web-ele/src/platform/approval/local-demo.ts', import.meta.url);
const otherEvents = [
  'TASK_ASSIGNED', 'AUTOMATIC_DELEGATION', 'EMPLOYEE_HANDOVER',
  'APPROVAL_COMPLETED', 'APPROVAL_REJECTED', 'COMMENT_MENTION',
];

// Evaluate the real development flag and route objects without loading components.
function routeAuthorities(environment) {
  const localDemoSource = readFileSync(localDemoModule, 'utf8');
  const flag = localDemoSource.match(/export function approvalLocalDemoEnabled\(\) \{[^}]+\}/u);
  assert.ok(flag, 'the actual local-demo flag function must be present');
  const source = readFileSync(routeModule, 'utf8');
  const typeImport = "import type { RouteRecordRaw } from 'vue-router';";
  const flagImport = "import { approvalLocalDemoEnabled, exposeApprovalLocalDemoRoutes } from '#/platform/approval/local-demo';";
  for (const part of [typeImport, flagImport, 'const routes: RouteRecordRaw[] =', 'export default routes;']) {
    assert.equal(source.split(part).length, 2, `expected one route syntax boundary: ${part}`);
  }
  const executable = source.replace(typeImport, '').replace(flagImport, '')
    .replace('const routes: RouteRecordRaw[] =', 'const routes =')
    .replace('export default routes;', 'routes;');
  const routes = runInNewContext(
    `${stripTypeScriptTypes(localDemoSource).replaceAll('export ', '').replaceAll('import.meta.env', 'environment')}\n${executable}`,
    { environment },
    { timeout: 1000 },
  );
  return JSON.parse(JSON.stringify(Object.fromEntries(
    routes.flatMap(route => route.children).map(route => [route.name, route.meta.authority]),
  )));
}

test('local-demo shell super reaches only the explicitly supported participant pages', () => {
  const production = routeAuthorities({ DEV: false, VITE_APPROVAL_LOCAL_DEMO: 'true' });
  const demo = routeAuthorities({ DEV: true, VITE_APPROVAL_LOCAL_DEMO: 'true' });
  assert.deepEqual(Object.keys(demo).sort(), Object.keys(production).sort());
  assert.deepEqual(Object.keys(demo).filter(name => demo[name].includes('super')).sort(), [
    'ApprovalDelegations', 'ApprovalNotifications', 'ApprovalTaskCollaboration', 'ApprovalWorkbench',
  ]);
  assert.deepEqual(demo.ApprovalTaskCollaboration, ['approval:workbench:view', 'super']);
  assert.deepEqual(demo.ApprovalNotifications, ['approval:message:view', 'super']);
  for (const [name, authority] of Object.entries(demo)) {
    assert.deepEqual(authority.filter(value => value !== 'super'), production[name]);
    if (!['ApprovalWorkbench', 'ApprovalTaskCollaboration', 'ApprovalNotifications', 'ApprovalDelegations'].includes(name)) {
      assert.deepEqual(authority, production[name], `unrelated route changed: ${name}`);
    }
  }
});

test('only explicit local demo administrator gains calendar and policy navigation', () => {
  const administrator = routeAuthorities({ DEV: true, VITE_APPROVAL_LOCAL_DEMO: 'true', VITE_APPROVAL_OPERATOR_ID: 'demo-admin' });
  assert.deepEqual(Object.keys(administrator).filter(name => administrator[name].includes('super')).sort(), [
    'ApprovalDelegations', 'ApprovalNotifications', 'ApprovalSlaCalendars', 'ApprovalSlaPolicies', 'ApprovalTaskCollaboration', 'ApprovalWorkbench',
  ]);
  const production = routeAuthorities({ DEV: false, VITE_APPROVAL_LOCAL_DEMO: 'true', VITE_APPROVAL_OPERATOR_ID: 'demo-admin' });
  assert.ok(Object.values(production).every(authority => !authority.includes('super')));
});

for (const [label, environment] of [
  ['production even with explicit demo flag', { DEV: false, VITE_APPROVAL_LOCAL_DEMO: 'true' }],
  ['development with false flag', { DEV: true, VITE_APPROVAL_LOCAL_DEMO: 'false' }],
  ['development with missing flag', { DEV: true }],
  ['development with uppercase near-match flag', { DEV: true, VITE_APPROVAL_LOCAL_DEMO: 'TRUE' }],
  ['development with trailing-space near-match flag', { DEV: true, VITE_APPROVAL_LOCAL_DEMO: 'true ' }],
]) {
  test(`${label} preserves capability-only approval route authorities`, () => {
    const routes = routeAuthorities(environment);
    assert.deepEqual(routes.ApprovalTaskCollaboration, ['approval:workbench:view']);
    assert.deepEqual(routes.ApprovalNotifications, ['approval:message:view']);
    assert.deepEqual(routes, routeAuthorities({ DEV: false }));
    assert.equal(Object.values(routes).some(authority => authority.includes('super')), false);
  });
}

// Execute the actual Vue click handler with transport/navigation doubles; this is not a browser click test.
function handler(path, runtime = { localDemo: false, operatorId: 'production-user' }) {
  const source = readFileSync(path, 'utf8');
  const start = source.indexOf('async function openNotification(');
  const end = source.indexOf('\nasync function replay(', start);
  assert.ok(start >= 0 && end > start, 'notification click handler must be present');
  const body = source.slice(start, end).replace('item: NotificationIntent', 'item');
  const routes = [];
  const reads = [];
  const errors = [];
  const messages = [];
  let reloads = 0;
  const open = runInNewContext(`${body}\nopenNotification`, {
    router: { push: async route => routes.push(JSON.parse(JSON.stringify(route))) },
    uni: { navigateTo: ({ url }) => routes.push(url), showToast: toast => messages.push(toast) },
    ElMessage: { success: value => messages.push(value), error: error => errors.push(error) },
    markNotificationRead: async id => reads.push(id),
    loadHistory: async () => { reloads++; },
    errorMessage: error => { errors.push(error); return String(error); },
    encodeURIComponent,
    getApprovalRuntimeConfig: () => runtime,
  });
  return { open, routes, reads, errors, messages, reloads: () => reloads };
}

function notification(eventType, extra = {}) {
  return { intentId: 'notification-1', eventType, instanceId: 'instance-1', taskId: 'task-1',
    metadata: { route: '/approval/discussion/detail?instanceId=instance-1' }, ...extra };
}

for (const [client, path] of [['Web', web], ['H5', mobile]]) {
  for (const eventType of ['TASK_COLLABORATION_ASSIGNED', 'TASK_COLLABORATION_RESULT']) {
    test(`${client} exact ${eventType} overrides historical wrong metadata with top-level taskId`, async () => {
      for (const extra of [
        {},
        { metadata: { route: '/some-historic-route', eventType: 'COMMENT_MENTION', taskId: 'untrusted-other-task' } },
        { metadata: {}, instanceId: undefined },
      ]) {
        const subject = handler(path);
        await subject.open(notification(eventType, { taskId: 'top level/task', ...extra }));
        assert.deepEqual(subject.routes, client === 'Web'
          ? [{ path: '/approval/collaboration', query: { taskId: 'top level/task' } }]
          : ['/pages/collaboration/index?taskId=top%20level%2Ftask']);
        assert.deepEqual(subject.reads, ['notification-1']);
        assert.deepEqual(subject.errors, []);
      }
    });

    test(`${client} ${eventType} without top-level taskId opens collaboration list, never discussion`, async () => {
      const subject = handler(path);
      await subject.open(notification(eventType, {
        taskId: undefined,
        metadata: { route: '/approval/discussion/detail?instanceId=instance-1', taskId: 'metadata-only' },
      }));
      assert.deepEqual(subject.routes, client === 'Web'
        ? [{ path: '/approval/collaboration', query: {} }]
        : ['/pages/collaboration/index']);
    });
  }
}

for (const eventType of ['TASK_COLLABORATION_ASSIGNED', 'TASK_COLLABORATION_RESULT']) {
  test(`Web ${eventType} preserves validated demo actor only in local-demo runtime`, async () => {
    for (const localDemo of [true, false]) {
      for (const taskId of ['task-1', undefined]) {
        const subject = handler(web, { localDemo, operatorId: 'demo-finance-reviewer' });
        await subject.open(notification(eventType, {
          taskId,
          metadata: {
            route: '/approval/discussion/detail?demoOperator=metadata-actor',
            demoOperator: 'metadata-actor',
          },
        }));
        assert.deepEqual(subject.routes, [{
          path: '/approval/collaboration',
          query: {
            ...(taskId ? { taskId } : {}),
            ...(localDemo ? { demoOperator: 'demo-finance-reviewer' } : {}),
          },
        }]);
        assert.deepEqual(subject.errors, []);
      }
    }
  });
}

test('Web collaboration navigation takes demo identity only from the validated runtime adapter', () => {
  const source = readFileSync(web, 'utf8');
  assert.match(source, /import \{ getApprovalRuntimeConfig \} from '#\/platform\/approval\/runtime'/);
  assert.doesNotMatch(source, /URLSearchParams|metadata\.demoOperator|metadata\.operatorId/);
});

test('Web preserves routes for every other event', async () => {
  for (const eventType of otherEvents) {
    const subject = handler(web);
    await subject.open(notification(eventType, {
      readAt: '2026-10-16T10:00:00Z',
      metadata: { route: '/existing-destination', eventType: 'TASK_COLLABORATION_ASSIGNED' },
    }));
    assert.deepEqual(subject.routes, ['/existing-destination']);
    assert.deepEqual(subject.reads, []);
    assert.equal(subject.reloads(), 1);
    assert.deepEqual(subject.errors, []);
  }
});

test('Web retains instance, task and no-target fallback semantics for other events', async () => {
  for (const eventType of otherEvents) {
    const instance = handler(web);
    await instance.open(notification(eventType, { metadata: {} }));
    assert.deepEqual(instance.routes, [{ path: '/approval/discussion/detail', query: { instanceId: 'instance-1' } }]);
    const task = handler(web);
    await task.open(notification(eventType, { metadata: {}, instanceId: undefined }));
    assert.deepEqual(task.routes, ['/approval/workbench']);
    const empty = handler(web);
    await empty.open(notification(eventType, { metadata: {}, instanceId: undefined, taskId: undefined }));
    assert.deepEqual(empty.routes, []);
    assert.equal(empty.messages.length, 1);
  }
});

test('H5 preserves existing instance/task behavior and ignores route metadata for other events', async () => {
  for (const eventType of otherEvents) {
    const subject = handler(mobile);
    await subject.open(notification(eventType, {
      instanceId: 'instance with space',
      metadata: { route: '/approval/collaboration', eventType: 'TASK_COLLABORATION_ASSIGNED' },
    }));
    assert.deepEqual(subject.routes, ['/pages/discussion/detail?instanceId=instance%20with%20space']);
    const task = handler(mobile);
    await task.open(notification(eventType, { instanceId: undefined }));
    assert.deepEqual(task.routes, ['/pages/task/list']);
    const empty = handler(mobile);
    await empty.open(notification(eventType, { instanceId: undefined, taskId: undefined }));
    assert.deepEqual(empty.routes, []);
    assert.equal(empty.messages.length, 1);
    assert.equal(empty.reloads(), 1);
  }
});
