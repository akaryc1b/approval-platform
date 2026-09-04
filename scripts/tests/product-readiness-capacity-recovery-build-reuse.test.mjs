import assert from 'node:assert/strict';
import {
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import test from 'node:test';

import {
  exactBuildReuseContract,
  recordReusableBuild,
  reusableBuild,
} from '../product-readiness/capacity-recovery/demo-backend-build-reuse.mjs';

const root = resolve(import.meta.dirname, '../..');
const text = path => readFileSync(resolve(root, path), 'utf8');
const demoBackend = text('scripts/product-readiness/demo-backend.mjs');
const launcher = text('scripts/product-readiness/capacity-recovery.mjs');
const ciScope = text('scripts/product-readiness/pc-h5-runtime/ci-scope.mjs');
const workflow = text('.github/workflows/approval-platform-validation.yml');

const exactObservation = Object.freeze({
  clean: true,
  commitSha: 'a'.repeat(40),
  treeSha: 'b'.repeat(40),
});

test('capacity build reuse accepts only the exact clean checkout identity', () => {
  const temporaryRoot = mkdtempSync(resolve(tmpdir(), 'capacity-build-reuse-'));
  try {
    mkdirSync(
      resolve(temporaryRoot, exactBuildReuseContract.classesRelativePath),
      { recursive: true },
    );
    assert.equal(
      reusableBuild(temporaryRoot, '0.1.0-SNAPSHOT', exactObservation),
      null,
    );
    const recorded = recordReusableBuild(
      temporaryRoot,
      '0.1.0-SNAPSHOT',
      exactObservation,
    );
    assert.equal(recorded.commitSha, exactObservation.commitSha);
    assert.equal(recorded.treeSha, exactObservation.treeSha);
    assert.equal(recorded.evidenceKind, exactBuildReuseContract.evidenceKind);

    const reused = reusableBuild(
      temporaryRoot,
      '0.1.0-SNAPSHOT',
      exactObservation,
    );
    assert.equal(reused.commitSha, exactObservation.commitSha);
    assert.equal(reused.treeSha, exactObservation.treeSha);
    assert.equal(
      reusableBuild(temporaryRoot, '0.1.1-SNAPSHOT', exactObservation),
      null,
    );
    assert.equal(
      reusableBuild(temporaryRoot, '0.1.0-SNAPSHOT', {
        ...exactObservation,
        treeSha: 'c'.repeat(40),
      }),
      null,
    );
    assert.equal(
      reusableBuild(temporaryRoot, '0.1.0-SNAPSHOT', {
        ...exactObservation,
        clean: false,
      }),
      null,
    );
  } finally {
    rmSync(temporaryRoot, { recursive: true, force: true });
  }
});

test('capacity build marker remains untracked bounded and non-authorizing', () => {
  assert.equal(
    exactBuildReuseContract.markerRelativePath,
    '.runtime/demo-backend-build-identity.json',
  );
  assert.equal(
    exactBuildReuseContract.classesRelativePath,
    'apps/server/target/classes',
  );
  assert.throws(
    () => recordReusableBuild('/tmp/not-used', '../invalid', exactObservation),
    /revision is invalid/u,
  );
  const helper = text(
    'scripts/product-readiness/capacity-recovery/demo-backend-build-reuse.mjs',
  );
  assert.match(helper, /--untracked-files=no/u);
  assert.match(helper, /successful demo backend build did not produce/u);
  assert.doesNotMatch(
    helper,
    /execSync|execFileSync|shell:\s*true|process\.env\[[^\]]+\]\s*=/u,
  );
});

test('only the capacity orchestrator enables exact build reuse', () => {
  const marker = "process.env[capacityBuildReuseVariable] = 'true'";
  assert.equal(launcher.includes(marker), true);
  assert.equal(
    launcher.indexOf(marker)
      < launcher.indexOf('await executeSmallDemoWithRetryEvidence(contract)'),
    true,
  );
  assert.match(
    demoBackend,
    /const capacityBuildReuseVariable = 'APPROVAL_DEMO_CAPACITY_REUSE_BUILD'/u,
  );
  assert.match(demoBackend, /capacityBuildReuseRequested\(\)/u);
  assert.match(demoBackend, /reusableBuild\(root, revision\)/u);
  assert.match(demoBackend, /recordReusableBuild\(root, revision\)/u);
  assert.match(demoBackend, /DEMO_BACKEND_EXACT_BUILD_REUSED/u);
  assert.match(demoBackend, /DEMO_BACKEND_EXACT_BUILD_RECORDED/u);
  assert.match(
    demoBackend,
    /runMavenChecked\('Build Maven reactor for local startup'/u,
  );
});

test('capacity-only CI retains the accepted permanent workflow budget', () => {
  assert.equal(
    ciScope.includes(
      '/^scripts\\/product-readiness\\/demo-backend\\.mjs$/u,',
    ),
    true,
  );
  assert.match(
    workflow,
    /web:\s*\n\s+name: Vben TypeScript \/ production build[\s\S]*?timeout-minutes: 45/u,
  );
  assert.doesNotMatch(
    workflow,
    /web:\s*\n\s+name: Vben TypeScript \/ production build[\s\S]*?timeout-minutes: 75/u,
  );
});
