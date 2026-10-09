import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';

const MANIFEST_SHA256 = '90399bc6500b2dcaf36f5897f134769b12ed6ff09677dd1d7edee0f5a47a43e8';
const blob = value => createHash('sha1').update(`blob ${Buffer.byteLength(value)}\0`).update(value).digest('hex');
const requireValue = (value, message) => { if (!value) throw new Error(message); };

export function readHygieneJava21WorkflowTransition() {
  const raw = readFileSync(new URL('../../docs/m6/hygiene-java21-workflow-transition.json', import.meta.url));
  requireValue(createHash('sha256').update(raw).digest('hex') === MANIFEST_SHA256, 'Java 21 workflow continuation manifest drift');
  return JSON.parse(raw);
}

// The original Maven transition and historical Action graph remain immutable.
// Only this exact one-step continuation can project to their reviewed bytes.
export function priorJava21Workflow(workflowPath, content) {
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
  for (const [workflowPath, change] of Object.entries(manifest.workflows)) {
    const current = workflows[workflowPath];
    requireValue(current && blob(current.content) === current.blobSha, `Java 21 workflow content/blob mismatch ${workflowPath}`);
    if (current.blobSha === change.fromBlob) continue;
    requireValue(current.blobSha === change.toBlob && blob(priorJava21Workflow(workflowPath, current.content)) === change.fromBlob,
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
    historicalWorkflowIdentityRetained: true };
}
