import { createHash } from 'node:crypto';

const workflowPath = '.github/workflows/approval-platform-validation.yml';
const priorBlobSha = '041705d7b2bf148658576b9d5b6d01e64e2d4e9d';
const currentBlobSha = '35f61934083abd086de4833f627568270b91528b';
const delimiter = '\n\n  online-images:\n';
const actionPins = Object.freeze([
  'actions/checkout@11d5960a326750d5838078e36cf38b85af677262',
  'actions/setup-node@49933ea5288caeca8642d1e84afbd3f7d6820020',
  'actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02',
]);
const evolution = Object.freeze({
  schemaVersion: 'APPROVAL_REVIEWED_WORKFLOW_EVOLUTION_V1',
  path: workflowPath,
  priorBlobSha,
  currentBlobSha,
  addedJob: 'online-images',
  addedActionUseCount: 3,
  addedCheckoutCount: 1,
  addedPhysicalJobCount: 1,
  addedArtifactClass: 'Online-images',
});
const stable = value => Array.isArray(value) ? value.map(stable)
  : value && typeof value === 'object'
    ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])]))
    : value;
const canonical = value => JSON.stringify(stable(value));
export function workflowBlobSha(source) {
  return createHash('sha1').update(`blob ${Buffer.byteLength(source)}\0`).update(source).digest('hex');
}

/**
 * Admit only the exact additive image job introduced in PR #148. Both hashes
 * are checked: no wildcard permission for later workflow changes is granted.
 * The original R2B plan and historical finding identities remain immutable.
 * Callers inspect the FULL current source; priorSource is for the explicitly
 * historical dependency-graph projection and legacy regression fixtures only.
 */
export function projectReviewedWorkflow(path, source, expectedPriorBlobSha = priorBlobSha) {
  const blobSha = workflowBlobSha(source);
  if (path !== workflowPath || blobSha !== currentBlobSha
      || expectedPriorBlobSha !== priorBlobSha) {
    return { priorSource: source, priorBlobSha: blobSha, evolution: null };
  }
  const pieces = source.split(delimiter);
  if (pieces.length !== 2 || workflowBlobSha(`${pieces[0]}\n`) !== priorBlobSha) {
    throw new Error('reviewed workflow extension does not preserve the exact prior workflow');
  }
  const appended = `  online-images:\n${pieces[1]}`;
  const uses = [...appended.matchAll(/^\s*-?\s*uses:\s*(\S+)\s+# v4\s*$/gm)].map(match => match[1]);
  if (canonical(uses) !== canonical(actionPins)
      || [...appended.matchAll(/^  [\w-]+:\s*$/gm)].length !== 1
      || !appended.includes('persist-credentials: false')
      || !appended.includes('contents: read')
      || /^\s*[\w-]+:\s*write\s*$/m.test(appended)
      || !appended.includes('name: approval-online-images-${{ github.run_id }}')) {
    throw new Error('reviewed image job security boundary mismatch');
  }
  return { priorSource: `${pieces[0]}\n`, priorBlobSha, evolution: { ...evolution } };
}

/** Validate serialized lineage independently rather than trusting claimed counts. */
export function reviewedWorkflowDeltas(evolutions = [], workflows) {
  if (!Array.isArray(evolutions) || evolutions.length > 1) {
    throw new Error('invalid reviewed workflow evolution inventory');
  }
  if (evolutions.length === 0) {
    if (workflows?.some(item => item.path === workflowPath && item.blobSha === currentBlobSha)) {
      throw new Error('current workflow is missing its reviewed evolution evidence');
    }
    return { actions: 0, checkouts: 0, jobs: 0, artifactClasses: [] };
  }
  if (canonical(evolutions[0]) !== canonical(evolution)) {
    throw new Error('unreviewed workflow evolution evidence');
  }
  if (workflows) {
    const matching = workflows.filter(item => item.path === workflowPath);
    if (matching.length !== 1 || matching[0].blobSha !== currentBlobSha) {
      throw new Error('workflow evolution does not bind the current workflow blob');
    }
  }
  return { actions: 3, checkouts: 1, jobs: 1, artifactClasses: ['Online-images'] };
}
