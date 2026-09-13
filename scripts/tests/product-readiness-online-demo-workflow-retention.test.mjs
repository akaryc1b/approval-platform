import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { projectReviewedWorkflow, reviewedWorkflowDeltas, workflowBlobSha } from '../security/workflow-evolution.mjs';

const path = '.github/workflows/approval-platform-validation.yml';
const raw = readFileSync(new URL('../../' + path, import.meta.url), 'utf8');
const line = '            .runtime/online-demo-image-runtime/*/evaluation-browser-*.png\n';
const original = raw.replace(line, '');
const prior = '041705d7b2bf148658576b9d5b6d01e64e2d4e9d';
const previous = '35f61934083abd086de4833f627568270b91528b';
const current = '4dc0b527a38dde0bf8057222ab4d82e4fc635c5e';

test('exact screenshot-only successor projects to the same historical graph with unchanged counts', () => {
  assert.equal(workflowBlobSha(raw), current);
  assert.equal(workflowBlobSha(original), previous);
  const before = projectReviewedWorkflow(path, original, prior);
  const after = projectReviewedWorkflow(path, raw, prior);
  assert.equal(after.priorSource, before.priorSource);
  assert.equal(after.priorBlobSha, prior);
  assert.equal(after.evolution.currentBlobSha, current);
  assert.deepEqual({ ...after.evolution, currentBlobSha: previous }, before.evolution);
  assert.deepEqual(reviewedWorkflowDeltas([after.evolution], [{ path, blobSha: current }]),
    { actions: 3, checkouts: 1, jobs: 1, artifactClasses: ['Online-images'] });
  assert.deepEqual(reviewedWorkflowDeltas([before.evolution], [{ path, blobSha: previous }]),
    reviewedWorkflowDeltas([after.evolution], [{ path, blobSha: current }]));
});
test('other screenshot globs, permissions, comments and action changes are not admitted', () => {
  for (const altered of [raw.replace(line, line.replace('evaluation-browser-*.png', '**/*')),
    raw.replace('contents: read', 'contents: write'), raw + '\n# unreviewed\n',
    raw.replace('11d5960a326750d5838078e36cf38b85af677262', '0'.repeat(40)),
    raw.replace(line, line + line)]) {
    const result = projectReviewedWorkflow(path, altered, prior);
    assert.equal(result.evolution, null);
    assert.equal(result.priorSource, altered);
    assert.equal(result.priorBlobSha, workflowBlobSha(altered));
  }
  assert.equal(projectReviewedWorkflow('unrelated.yml', raw, prior).evolution, null);
  assert.equal(projectReviewedWorkflow(path, raw, '0'.repeat(40)).evolution, null);
});
test('serialized retention evidence must bind the exact observed blob and reviewed action counts', () => {
  const { evolution } = projectReviewedWorkflow(path, raw, prior);
  assert.throws(() => reviewedWorkflowDeltas([], [{ path, blobSha: current }]), /missing/u);
  assert.throws(() => reviewedWorkflowDeltas([evolution], [{ path, blobSha: previous }]), /bind/u);
  assert.throws(() => reviewedWorkflowDeltas([{ ...evolution, addedActionUseCount: 4 }]), /unreviewed/u);
  assert.throws(() => reviewedWorkflowDeltas([{ ...evolution, currentBlobSha: '0'.repeat(40) }]), /unreviewed/u);
  assert.throws(() => reviewedWorkflowDeltas([evolution, evolution]), /inventory/u);
  assert.deepEqual(reviewedWorkflowDeltas([], [{ path, blobSha: prior }]),
    { actions: 0, checkouts: 0, jobs: 0, artifactClasses: [] });
});
