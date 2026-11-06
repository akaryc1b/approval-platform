import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { PROTOTYPE_SOURCE_PATH, PRIOR_PROTOTYPE_PLAN, verifyPrototypeCloneContinuation,
  readDesignerPrototypeSourceContinuation } from '../security/designer-prototype-source-continuation.mjs';
import './approval-designer-conflict.test.mjs';
import './approval-designer-save-lifecycle.test.mjs';
import './approval-designer-modal-state.test.mjs';
const source = readFileSync(new URL(`../../${PROTOTYPE_SOURCE_PATH}`, import.meta.url), 'utf8');
const blob = value => createHash('sha1').update(`blob ${Buffer.byteLength(value)}\0`).update(value).digest('hex');
test('proxy-safe clone continuation reverses exactly to accepted guarded source and retains prior plan', () => {
  assert.equal(blob(verifyPrototypeCloneContinuation(source)), 'dd7c59dd73a86ec9687cd0033a2d1e311a7bbcc6');
  const proof = readDesignerPrototypeSourceContinuation();
  assert.equal(proof.priorPlanCanonicalSha256, PRIOR_PROTOTYPE_PLAN);
  assert.equal(proof.cloneCallSubstitutionCount, 3); assert.equal(proof.helperInsertionCount, 1);
  assert.equal(Object.keys(proof.reviewedArtifacts).length, 6);
  assert.equal(proof.releaseBlocked, true); assert.equal(proof.findingAbsenceClaimed, false);
  assert.equal(proof.scannerExecutionClaimed, false); assert.equal(proof.historicalReviewUnchanged, true);
});
for (const [name, mutate] of [
  ['unreviewed whitespace', s => s + '\n'],
  ['removed input validation', s => s.replace('  assertSafeDesignerValue(value);', '')],
  ['removed serialized-output validation', s => s.replace('  assertSafeDesignerValue(result);', '')],
  ['changed clone operation', s => s.replace('JSON.parse(JSON.stringify(value))', 'value')],
  ['relaxed reserved-key guard', s => s.replace("token === '__proto__'", "token === 'unrelated'")],
  ['inherited-property write', s => s.replace('Object.defineProperty(target, key, {', 'Object.assign(target, {')],
  ['accepted predecessor replay', () => verifyPrototypeCloneContinuation(source)],
]) test(`proxy-safe source continuation rejects ${name}`, () => {
  const changed = mutate(source); assert.notEqual(changed, source);
  assert.throws(() => verifyPrototypeCloneContinuation(changed), /unreviewed current clone source/);
});
