import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve } from 'node:path';
import test from 'node:test';
import { pathToFileURL } from 'node:url';
import './product-readiness-restore-database-readiness.test.mjs';

const source = readFileSync(new URL(
  '../product-readiness/capacity-recovery/upgrade-restore-contract.mjs', import.meta.url), 'utf8');
const reuse = 'APPROVAL_DEMO_CAPACITY_REUSE_BUILD';
const contract = { scenario: { directory: { connectorKey: 'controlled-organization' } } };

// Import the exact production module bytes. Only its Java/environment and sibling
// dependencies are controlled fixtures; no source rewriting or Maven/Docker run.
async function isolatedContract(t, inherited) {
  const root = mkdtempSync(resolve(tmpdir(), 'upgrade-build-environment-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const files = {
    'capacity-recovery/upgrade-restore-contract.mjs': source,
    'pc-h5-runtime/contract.mjs': `export const inherited = Object.freeze(${JSON.stringify(inherited)});
export function java21Environment() { return inherited; }
`,
    'capacity-recovery/contract.mjs': `export const backendOrigin = 'http://127.0.0.1:8080';
export const repositoryRoot = ${JSON.stringify(root)};
`,
    'capacity-recovery/upgrade-restore-refs.mjs': `export function exactUpgradeRefs() {
throw new Error('environment construction must not resolve Git refs');
}
`,
  };
  for (const [name, content] of Object.entries(files)) {
    const file = resolve(root, name);
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, content);
  }
  const module = await import(pathToFileURL(resolve(root, 'capacity-recovery/upgrade-restore-contract.mjs')));
  return { root, module };
}

for (const inheritedReuse of [undefined, 'true', 'false', 'invalid-inherited-value']) {
  test(`upgrade version switch forces fresh baseline and candidate builds with inherited ${String(inheritedReuse)}`,
    async t => {
      const inherited = { JAVA_HOME: '/controlled/java21', PATH: '/controlled/bin', KEEP_SETTING: 'retained' };
      if (inheritedReuse !== undefined) inherited[reuse] = inheritedReuse;
      const original = structuredClone(inherited);
      const { root, module } = await isolatedContract(t, inherited);
      const baseline = module.baseEnvironment();
      const candidate = module.candidateEnvironment(root, contract);
      assert.equal(baseline[reuse], 'false');
      assert.equal(candidate[reuse], 'false');
      for (const name of ['JAVA_HOME', 'PATH', 'KEEP_SETTING']) {
        assert.equal(baseline[name], inherited[name]);
        assert.equal(candidate[name], inherited[name]);
      }
      assert.deepEqual(inherited, original);
      const dependency = await import(pathToFileURL(resolve(root, 'pc-h5-runtime/contract.mjs')));
      assert.deepEqual(dependency.inherited, original, 'do not disable parent capacity-stage reuse');
    });
}

test('candidate retains exact sandbox paths, reserved host configuration and bounded retries', async t => {
  const { root, module } = await isolatedContract(t, { [reuse]: 'true', JAVA_HOME: '/controlled/java21' });
  const candidate = module.candidateEnvironment(root, contract);
  assert.equal(candidate.APPROVAL_GENERIC_CONNECTOR_KEY, 'controlled-organization');
  assert.equal(candidate.APPROVAL_GENERIC_CALLBACK_URI, 'http://127.0.0.1:8080/payment-sandbox/v1/events');
  assert.equal(candidate.APPROVAL_DEMO_PAYMENT_SANDBOX_ENDPOINT, candidate.APPROVAL_GENERIC_CALLBACK_URI);
  for (const [name, file] of [
    ['CONTROL_FILE', 'payment-sandbox-recover.control'], ['STATUS_FILE', 'payment-sandbox-status.json'],
    ['EVENT_ALLOWLIST_FILE', 'payment-sandbox-events.allowlist'],
  ]) assert.equal(candidate['APPROVAL_DEMO_PAYMENT_SANDBOX_' + name], resolve(root, file));
  assert.equal(candidate.APPROVAL_GENERIC_DISPATCH_ENABLED, 'true');
  assert.equal(candidate.APPROVAL_GENERIC_TIMEOUT, 'PT2S');
  assert.equal(candidate.APPROVAL_GENERIC_DISPATCH_LEASE, 'PT30S');
  assert.equal(candidate.APPROVAL_GENERIC_RETRY_MAXIMUM_ATTEMPTS, '20');
  assert.equal(candidate.APPROVAL_GENERIC_RETRY_JITTER_RATIO, '0');
  const baseline = module.baseEnvironment();
  assert.deepEqual(Object.keys(baseline).sort(), ['APPROVAL_DEMO_CAPACITY_REUSE_BUILD', 'JAVA_HOME']);
});

test('repeated environment construction does not retain changes from a previous version', async t => {
  const { root, module } = await isolatedContract(t, { [reuse]: 'true' });
  const first = module.baseEnvironment(); first[reuse] = 'true'; first.INJECTED_SETTING = 'not-retained';
  const candidate = module.candidateEnvironment(root, contract); candidate[reuse] = 'true';
  const next = module.baseEnvironment();
  assert.equal(next[reuse], 'false');
  assert.equal(next.INJECTED_SETTING, undefined);
  assert.equal(module.candidateEnvironment(root, contract)[reuse], 'false');
});

test('fresh-build correction preserves the original rehearsal deadlines and non-production claims', async t => {
  const { module } = await isolatedContract(t, {});
  assert.equal(module.maximumRuntimeMs, 15 * 60_000);
  assert.equal(module.backendTimeoutMs, 10 * 60_000);
  assert.equal(module.stateTimeoutMs, 5 * 60_000);
  assert.equal(module.upgradeRestorePlan().claim, 'LOCAL_IN_FLIGHT_POSTGRES_UPGRADE_RESTORE_REHEARSAL_PASSED');
  assert.ok(module.nonClaims.includes('PRODUCTION_RPO_NOT_VERIFIED'));
  assert.ok(module.nonClaims.includes('PRODUCTION_RTO_NOT_VERIFIED'));
});
