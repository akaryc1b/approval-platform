import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { relevantChangeSet } from '../product-readiness/pc-h5-runtime/ci-scope.mjs';
import { selectedFiles } from '../product-readiness/capacity-recovery/ci-scope.mjs';

// The hygiene aggregate runs on the normal validation workflow. Keep the Python
// codec's adversarial tests on that path, without launching any browser.
for (const suite of ['privacy', 'framing']) {
  test(`artifact publication ${suite} synthetic tests`, () => {
    const result = spawnSync('python3', [fileURLToPath(new URL(
      `./product-readiness-artifact-${suite}.test.py`, import.meta.url,
    ))], { encoding: 'utf8', shell: false, timeout: 60_000, maxBuffer: 1_048_576 });
    assert.equal(result.error, undefined, 'Python privacy test process failed');
    assert.equal(result.status, 0, result.stderr || result.stdout);
  });
}

test('privacy-only changes select both browser evidence and capacity producer gates', () => {
  for (const path of [
    'scripts/product-readiness/artifact-privacy/sanitize.py',
    'scripts/product-readiness/artifact-privacy/publication.mjs',
    'scripts/product-readiness/pc-h5-runtime/safe-output.mjs',
    'scripts/tests/product-readiness-artifact-privacy.test.py',
    'scripts/tests/product-readiness-artifact-framing.test.py',
    'scripts/tests/product-readiness-artifact-privacy.test.mjs',
    'scripts/tests/product-readiness-safe-output.test.mjs',
  ]) {
    assert.equal(relevantChangeSet([path]), true, path);
    assert.deepEqual(selectedFiles([path]), [path]);
  }
});
