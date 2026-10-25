import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { runInNewContext } from 'node:vm';
import test from 'node:test';
import { approvalShellIcons, registerApprovalShellIcons } from '../../apps/web/overlay/apps/web-ele/src/platform/approval/local-icons.ts';
import { relevantChangeSet } from '../product-readiness/pc-h5-runtime/ci-scope.mjs';

// Independently bound to @iconify/json 2.2.476 and retained CI1826/1887 responses.
// ep:expand and lucide:bell-ring are pinned local shapes, not observed responses.
const expected = {
  "lucide:user-round-cog": {
    "width": 24,
    "height": 24,
    "bodySha256": "3aa014c43550b752b34229b5e76cfea7ae4b7d878f05d4cc5e0732717f8b28e0"
  },
  "lucide:calendar-clock": {
    "width": 24,
    "height": 24,
    "bodySha256": "0592a2f3cf313d31e83b693d77f9333fddeac0a5e0e445cb9e4b2f9ca477d68c"
  },
  "lucide:timer-reset": {
    "width": 24,
    "height": 24,
    "bodySha256": "08cbee51e78a7080f183bb720787ad339cfd10d4357c45cedca5c9c220da2eec"
  },
  "ep:expand": {
    "width": 1024,
    "height": 1024,
    "bodySha256": "90284b5083463be649e81058727bcf7febdb61d2d0e40ece03691cf4a060dee4"
  },
  "ep:fold": {
    "width": 1024,
    "height": 1024,
    "bodySha256": "0c49b7f5ee347480188a651363feea46c8dbc3aa79f43b9213662013e84de9dc"
  },
  "fluent-mdl2:world-clock": {
    "width": 2048,
    "height": 2048,
    "bodySha256": "ba9dd894ac115c60cd2bb839552a2610ffae5cd33b60e57b5b01ff828663fe50"
  },
  "lucide:bell-ring": {
    "width": 24,
    "height": 24,
    "bodySha256": "7c76f0b69f6b3bcd6f6144c0523e24bd787e844f55844b5f874f2a61b0a81be2"
  },
  "lucide:inbox": {
    "width": 24,
    "height": 24,
    "bodySha256": "17a61c59de89bd3202dd2f6512d31d9f32e4f57ea291cf4888bb623f8f5e68f8"
  },
  "lucide:user-round-plus": {
    "width": 24,
    "height": 24,
    "bodySha256": "2d332334197dfacda8468001c3156ee62e982083d2bd0ebe0fa0d31bd0325eb6"
  },
  "lucide:workflow": {
    "width": 24,
    "height": 24,
    "bodySha256": "647ddf6ef163d8363aebb3fb48a0599b12d910c8ea6a4b96d1f422f56ca2b468"
  }
};
function verifyDefinitions(icons) {
  assert.deepEqual(Object.keys(icons).sort(), Object.keys(expected).sort());
  for (const [name, pinned] of Object.entries(expected)) {
    const icon = icons[name];
    assert.equal(icon.width, pinned.width); assert.equal(icon.height, pinned.height);
    assert.equal(createHash('sha256').update(icon.body).digest('hex'), pinned.bodySha256);
    assert.equal(icon.left, 0); assert.equal(icon.top, 0); assert.equal(icon.rotate, 0);
    assert.equal(icon.hFlip, false); assert.equal(icon.vFlip, false);
  }
}
const bootstrapSource = readFileSync(new URL('../../apps/web/overlay/apps/web-ele/src/bootstrap.ts', import.meta.url), 'utf8');
function localDemoMenuIcons(administrator = false) {
  const localDemo = readFileSync(new URL('../../apps/web/overlay/apps/web-ele/src/platform/approval/local-demo.ts', import.meta.url), 'utf8');
  const flag = localDemo.match(/export function approvalLocalDemoEnabled\(\) \{[^}]+\}/u);
  assert.ok(flag, 'actual local-demo flag function must be present');
  const routeSource = readFileSync(new URL('../../apps/web/overlay/apps/web-ele/src/router/routes/modules/approval.ts', import.meta.url), 'utf8');
  const flagImport = "import { approvalLocalDemoEnabled, exposeApprovalLocalDemoRoutes } from '#/platform/approval/local-demo';";
  assert.equal(routeSource.split(flagImport).length, 2);
  assert.equal(routeSource.split('export default routes;').length, 2);
  const executable = stripTypeScriptTypes(routeSource).replace(flagImport, '')
    .replace('export default routes;', 'routes;');
  const routes = runInNewContext(
    `${stripTypeScriptTypes(localDemo).replaceAll('export ', '').replaceAll('import.meta.env', 'environment')}\n${executable}`,
    { environment: { DEV: true, VITE_APPROVAL_LOCAL_DEMO: 'true', VITE_APPROVAL_OPERATOR_ID: administrator ? 'demo-admin' : 'demo-manager' } },
    { timeout: 1000 },
  );
  const visible = [];
  function visit(route) {
    if (route.meta?.authority && !route.meta.authority.includes('super')) return;
    visible.push([route.name, route.meta?.icon]);
    for (const child of route.children ?? []) visit(child);
  }
  routes.forEach(visit);
  return Object.fromEntries(visible);
}
function verifyMenuCoverage(icons, menuIcons) {
  for (const [route, icon] of Object.entries(menuIcons)) {
    assert.equal(Object.hasOwn(icons, icon), true, `unregistered visible route icon: ${route}/${icon}`);
  }
}
async function bootstrapOrder(source) {
  const start = source.indexOf('async function bootstrap(');
  const end = source.indexOf('\nexport { bootstrap };', start);
  assert.ok(start >= 0 && end > start);
  const body = stripTypeScriptTypes(source.slice(start, end)).replaceAll('await import(', 'await __import(');
  const events = []; const registered = {};
  const app = { directive() {}, use() {}, mount() {
    verifyDefinitions(registered); events.push('mount');
  } };
  const context = {
    registerApprovalShellIcons, addIcon(name, icon) { events.push(`icon:${name}`); registered[name] = icon; return true; },
    initComponentAdapter: async () => events.push('adapter'), initSetupVbenForm: async () => {},
    createApp: () => app, App: {}, ElLoading: { directive: {} }, registerLoadingDirective() {},
    setupI18n: async () => {}, initStores: async () => {}, registerAccessDirective() {},
    __import: async () => ({ initTippy() {}, MotionPlugin: {} }), router: {},
    watchEffect: fn => fn(), preferences: { app: { dynamicTitle: false } },
  };
  const bootstrap = runInNewContext(`${body}
bootstrap`, context);
  await bootstrap('bounded-test');
  return events;
}

await test('governed local icon definitions preserve the exact pinned and observed shapes', () => {
  verifyDefinitions(approvalShellIcons);
  const missing = structuredClone(approvalShellIcons); delete missing['ep:fold'];
  assert.throws(() => verifyDefinitions(missing));
  const wrong = structuredClone(approvalShellIcons); wrong['lucide:workflow'].body = '<path d="M0 0"/>';
  assert.throws(() => verifyDefinitions(wrong));
});
await test('registration covers only governed names and preserves existing custom registry entries', () => {
  const custom = { body: '<path d="M1 1"/>' }; const registered = new Map([['custom:retained', custom]]);
  const calls = [];
  registerApprovalShellIcons((name, icon) => { calls.push(name); registered.set(name, icon); return true; });
  assert.deepEqual(calls.sort(), Object.keys(expected).sort());
  assert.equal(registered.get('custom:retained'), custom);
  assert.equal(registered.has('custom:unknown'), false);
  assert.throws(() => registerApprovalShellIcons(() => false), /icon registration failed/u);
});
await test('actual bootstrap registers local names before adapter initialization and first mount', async () => {
  assert.match(bootstrapSource, /import \{ addIcon \} from '@vben\/icons'/u);
  const events = await bootstrapOrder(bootstrapSource);
  const iconCount = Object.keys(expected).length;
  assert.deepEqual(events.slice(0, iconCount).sort(), Object.keys(expected).map(name => `icon:${name}`).sort());
  assert.deepEqual(events.slice(iconCount), ['adapter', 'mount']);
  const late = bootstrapSource.replace('  registerApprovalShellIcons(addIcon);\n', '')
    .replace("  app.mount('#app');", "  app.mount('#app');\n  registerApprovalShellIcons(addIcon);");
  await assert.rejects(bootstrapOrder(late));
});
await test('every actual local-demo super menu icon is registered before navigation', () => {
  const menuIcons = localDemoMenuIcons();
  assert.deepEqual(menuIcons, {
    ApprovalPlatform: 'lucide:workflow',
    ApprovalWorkbench: 'lucide:inbox',
    ApprovalTaskCollaboration: 'lucide:user-round-plus',
    ApprovalNotifications: 'lucide:bell-ring',
    ApprovalDelegations: 'lucide:user-round-cog',
  });
  verifyMenuCoverage(approvalShellIcons, menuIcons);
  const adminIcons = localDemoMenuIcons(true);
  assert.deepEqual(adminIcons, { ...menuIcons, ApprovalSlaCalendars: 'lucide:calendar-clock', ApprovalSlaPolicies: 'lucide:timer-reset' });
  verifyMenuCoverage(approvalShellIcons, adminIcons);
  for (const name of ['lucide:bell-ring', 'lucide:user-round-plus']) {
    const missing = structuredClone(approvalShellIcons); delete missing[name];
    assert.throws(() => verifyMenuCoverage(missing, menuIcons), /unregistered visible route icon/u);
  }
  assert.throws(() => verifyMenuCoverage(approvalShellIcons, {
    ...menuIcons, ApprovalNotifications: 'lucide:unregistered-fixture',
  }), /unregistered visible route icon/u);
});
await test('icon source bootstrap and regression changes independently select browser runtime', () => {
  for (const path of [
    'apps/web/overlay/apps/web-ele/src/bootstrap.ts',
    'apps/web/overlay/apps/web-ele/src/platform/approval/local-icons.ts',
    'apps/web/overlay/apps/web-ele/src/platform/approval/local-icons.NOTICE.md',
    'scripts/tests/web-local-icons.test.mjs',
  ]) assert.equal(relevantChangeSet([path]), true, path);
});
