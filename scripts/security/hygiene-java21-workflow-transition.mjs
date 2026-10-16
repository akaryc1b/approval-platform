import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';

const MANIFEST_SHA256 = '90399bc6500b2dcaf36f5897f134769b12ed6ff09677dd1d7edee0f5a47a43e8';
const M3_RETENTION_MANIFEST_SHA256 = '452c856216d8213b7705a5a83b0eaf801c146a782c1a1759fe5a7d99e75f8efc';
const M3_RETENTION_WORKFLOW_BLOB = '4cdfa529d6f5209d8c0dd913b7ae845d2829ce30';
const blob = value => createHash('sha1').update(`blob ${Buffer.byteLength(value)}\0`).update(value).digest('hex');
const requireValue = (value, message) => { if (!value) throw new Error(message); };

export function readHygieneJava21WorkflowTransition() {
  const raw = readFileSync(new URL('../../docs/m6/hygiene-java21-workflow-transition.json', import.meta.url));
  requireValue(createHash('sha256').update(raw).digest('hex') === MANIFEST_SHA256, 'Java 21 workflow continuation manifest drift');
  return JSON.parse(raw);
}

export function readHygieneM3LogRetentionTransition() {
  const raw = readFileSync(new URL('../../docs/m6/hygiene-m3-log-retention-transition.json', import.meta.url));
  requireValue(createHash('sha256').update(raw).digest('hex') === M3_RETENTION_MANIFEST_SHA256,
    'M3 log retention manifest drift');
  return JSON.parse(raw);
}

// Historical fixtures need no new manifest. Only this exact later blob may
// reverse the two reviewed logging edits; all other content remains unaltered.
export function priorM3LogRetentionWorkflow(workflowPath, content) {
  if (blob(content) !== M3_RETENTION_WORKFLOW_BLOB) return content;
  const manifest = readHygieneM3LogRetentionTransition();
  const change = manifest.workflows[workflowPath];
  requireValue(change && change.toBlob === M3_RETENTION_WORKFLOW_BLOB, 'M3 log retention workflow identity drift');
  let prior = content;
  for (const replacement of change.replacements) {
    const pieces = prior.split(replacement.after);
    requireValue(pieces.length === 2, 'M3 log retention replacement count drift');
    prior = pieces.join(replacement.before);
  }
  requireValue(blob(prior) === change.fromBlob, 'M3 log retention prior workflow blob drift');
  return prior;
}

// The original Maven transition and historical Action graph remain immutable.
// Only this exact one-step continuation can project to their reviewed bytes.
export function priorJava21Workflow(workflowPath, content) {
  content = priorM3LogRetentionWorkflow(workflowPath, content);
  const manifest = readHygieneJava21WorkflowTransition();
  const change = manifest.workflows[workflowPath];
  if (!change || blob(content) !== change.toBlob) return content;
  const pieces = content.split(manifest.insertedStep);
  requireValue(pieces.length === 2, 'Java 21 workflow step count drift');
  const prior = pieces.join('');
  requireValue(blob(prior) === change.fromBlob, 'Java 21 prior workflow blob drift');
  return prior;
}

export function requireNoHygieneJava21Sources(root) {
  const manifest = readHygieneJava21WorkflowTransition();
  requireValue(Object.keys(manifest.toolchainSourceBlobs).every(relative => !existsSync(path.join(root, relative))),
    'mixed Java 21 workflow/source state');
}

export function verifyHygieneJava21WorkflowContinuation(workflows, root) {
  const manifest = readHygieneJava21WorkflowTransition();
  let changed = 0;
  const retentionWorkflows = {};
  for (const [workflowPath, change] of Object.entries(manifest.workflows)) {
    const current = workflows[workflowPath];
    requireValue(current && blob(current.content) === current.blobSha, `Java 21 workflow content/blob mismatch ${workflowPath}`);
    const projected = priorM3LogRetentionWorkflow(workflowPath, current.content);
    const projectedBlob = blob(projected);
    if (projected !== current.content) retentionWorkflows[workflowPath] = { fromBlob: projectedBlob, toBlob: current.blobSha };
    if (projectedBlob === change.fromBlob) continue;
    requireValue(projectedBlob === change.toBlob && blob(priorJava21Workflow(workflowPath, current.content)) === change.fromBlob,
      `unrecognized Java 21 workflow blob ${workflowPath}`);
    changed += 1;
  }
  if (!changed) {
    requireNoHygieneJava21Sources(root);
    return null;
  }
  requireValue(changed === Object.keys(manifest.workflows).length, 'mixed Java 21 workflow state');
  for (const [relative, expected] of Object.entries(manifest.toolchainSourceBlobs)) {
    requireValue(existsSync(path.join(root, relative)) && blob(readFileSync(path.join(root, relative))) === expected,
      `Java 21 toolchain source blob drift ${relative}`);
  }
  return { schemaVersion: manifest.schemaVersion, manifestSha256: MANIFEST_SHA256,
    changedWorkflowCount: changed, javaMajorVersion: manifest.java.majorVersion,
    selectionEnvironment: manifest.java.selectionEnvironment,
    priorMavenTransitionManifestSha256: manifest.priorMavenTransitionManifestSha256,
    workflowBlobs: manifest.workflows, toolchainSourceBlobs: manifest.toolchainSourceBlobs,
    ...(Object.keys(retentionWorkflows).length ? { m3LogRetentionContinuation: {
      schemaVersion: readHygieneM3LogRetentionTransition().schemaVersion,
      manifestSha256: M3_RETENTION_MANIFEST_SHA256,
      workflowBlobs: retentionWorkflows,
      originalTestCommandAndExitStatusRetained: true,
    } } : {}),
    historicalWorkflowIdentityRetained: true };
}
