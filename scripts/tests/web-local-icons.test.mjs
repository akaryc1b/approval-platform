import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { runInNewContext } from 'node:vm';
import test from 'node:test';
import { approvalShellIcons, registerApprovalShellIcons } from '../../apps/web/overlay/apps/web-ele/src/platform/approval/local-icons.ts';
import { relevantChangeSet } from '../product-readiness/pc-h5-runtime/ci-scope.mjs';

// Independently bound to @iconify/json 2.2.476 and actual CI1826 responses
// (four observed names); ep:expand is the pinned paired sidebar state.
const expected = {
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
  "lucide:inbox": {
    "width": 24,
    "height": 24,
    "bodySha256": "17a61c59de89bd3202dd2f6512d31d9f32e4f57ea291cf4888bb623f8f5e68f8"
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
  assert.deepEqual(events.slice(0, 5).sort(), Object.keys(expected).map(name => `icon:${name}`).sort());
  assert.deepEqual(events.slice(5), ['adapter', 'mount']);
  const late = bootstrapSource.replace('  registerApprovalShellIcons(addIcon);\n', '')
    .replace("  app.mount('#app');", "  app.mount('#app');\n  registerApprovalShellIcons(addIcon);");
  await assert.rejects(bootstrapOrder(late));
});
await test('icon source bootstrap and regression changes independently select browser runtime', () => {
  for (const path of [
    'apps/web/overlay/apps/web-ele/src/bootstrap.ts',
    'apps/web/overlay/apps/web-ele/src/platform/approval/local-icons.ts',
    'apps/web/overlay/apps/web-ele/src/platform/approval/local-icons.NOTICE.md',
    'scripts/tests/web-local-icons.test.mjs',
  ]) assert.equal(relevantChangeSet([path]), true, path);
});
