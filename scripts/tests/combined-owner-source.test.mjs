import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, writeFileSync, symlinkSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { reverseCombinedOwnerPom, requireCombinedOwnerSource,
  COMBINED_OWNER_PRIOR_POM_SHA256 } from '../security/combined-owner-source.mjs';
const root = fileURLToPath(new URL('../../', import.meta.url));
const current = readFileSync(join(root, 'pom.xml'));
test('exact seven owner edits reverse to independently pinned accepted source', () => {
  assert.equal(createHash('sha256').update(reverseCombinedOwnerPom(current)).digest('hex'), COMBINED_OWNER_PRIOR_POM_SHA256);
  assert.deepEqual(requireCombinedOwnerSource(root), {
    currentPomSha256: '9e91a467ef498a2b20c6cd971a336ea643d9c911f6dff9c651d07742ad778b7b',
    predecessorPomSha256: COMBINED_OWNER_PRIOR_POM_SHA256, editCount: 7,
    changedOwnerCount: 4, vulnerabilityReproductionExecuted: false,
  });
});
for (const [label, before, after] of [
  ['unsafe Aircompressor', '2.0.3', '0.27'], ['wrong BeanUtils', '1.11.0', '1.9.4'],
  ['unrelated Utils drift', '4.0.3', '4.0.2'], ['Assembly rollback', '3.8.0', '3.7.1'],
  ['extra whitespace', '<project', '\n<project'],
]) test(`reject ${label}`, () => assert.throws(() => reverseCombinedOwnerPom(Buffer.from(current.toString().replace(before, after))), /digest mismatch/));
test('reject symbolic source paths', () => {
  const dir = mkdtempSync(join(tmpdir(), 'combined-source-'));
  try { writeFileSync(join(dir, 'actual.xml'), current); symlinkSync('actual.xml', join(dir, 'pom.xml'));
    assert.throws(() => requireCombinedOwnerSource(dir), /real source file/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
