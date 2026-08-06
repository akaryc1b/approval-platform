import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

function read(relativePath) {
  const file = path.join(root, relativePath);
  assert.equal(existsSync(file), true, `${relativePath} must exist`);
  return readFileSync(file, 'utf8');
}

const configuration = read(
  'apps/server/src/main/java/io/github/akaryc1b/approval/config/'
    + 'ControlledAutomationGovernanceConfiguration.java',
);
const stability = read(
  'apps/server/src/test/java/io/github/akaryc1b/approval/config/'
    + 'ControlledAutomationGovernanceRuntimeStabilityAcceptanceTest.java',
);

test('P7-C revalidates circuit and usage after composite construction', () => {
  assert.match(configuration, /requireStableRuntimeObservation/);
  assert.match(configuration, /sameControlState/);
  assert.match(configuration, /sameUsageState/);
  assert.match(
    configuration,
    /IncidentReadinessView view = IncidentReadinessView\.from[\s\S]*requireStableRuntimeObservation[\s\S]*return view;/,
  );
  assert.match(
    configuration,
    /AI governance runtime changed during incident-readiness composition/,
  );
});

test('P7-C runtime drift tests fail closed without retry or binding', () => {
  for (const scenario of [
    'circuitTransitionDuringHistoryQueryFailsClosedWithoutRetryOrBinding',
    'usageMutationDuringHistoryQueryFailsClosedWithoutRetryOrBinding',
    'stableRuntimeProducesOneCompositeWithoutBindingOrProviderAccess',
  ]) {
    assert.match(stability, new RegExp(scenario));
  }
  assert.match(stability, /assertEquals\(1, historyCalls\.get\(\)\)/);
  assert.match(stability, /assertEquals\(1, fixture\.snapshotReads\(\)\.get\(\)\)/);
  assert.match(stability, /assertEquals\(0, bindingCount\(fixture\.factory\(\)\)\)/);
  assert.doesNotMatch(stability, /Thread\.sleep|Math\.random|\.bind\s*\(|\.advise\s*\(/);
});

test('P7-C stability recheck cannot mutate or execute production work', () => {
  assert.doesNotMatch(
    configuration,
    /ApprovalTaskCommandService|ApprovalProcessCommandService|Runtime\.getRuntime\(\)\.exec/,
  );
  assert.doesNotMatch(
    configuration,
    /@Scheduled\b|TaskScheduler|\b(class|interface|record)\s+\w*(Worker|Queue|Scheduler)/,
  );
  assert.doesNotMatch(
    configuration,
    /System\.getenv|System\.getProperty|openLease\s*\(|\.bind\s*\(/,
  );
});
