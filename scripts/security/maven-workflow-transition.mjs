import { createHash } from 'node:crypto';
import { priorJava21Workflow, requireNoHygieneJava21Sources, verifyHygieneJava21WorkflowContinuation } from './hygiene-java21-workflow-transition.mjs';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const MANIFEST_SHA256 = '7179499a14be5d76a06d0ae24b1f80fee5268e3a18cb6dad41db6dded6c9fa6b';
const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const canonical = value => JSON.stringify(stable(value));
const hash = value => createHash('sha256').update(value).digest('hex');
export const gitBlob = value => createHash('sha1').update(`blob ${Buffer.byteLength(value)}\0`).update(value).digest('hex');
const requireValue = (value, message) => { if (!value) throw new Error(message); };

export function readMavenWorkflowTransition() {
  const raw = readFileSync(new URL('../../docs/m6/maven-toolchain-transition.json', import.meta.url));
  requireValue(hash(raw) === MANIFEST_SHA256, 'Maven toolchain transition manifest drift');
  return JSON.parse(raw);
}

/** Historical fixture only. Actual workflows remain the E2/R2B/scanner inputs. */
export function historicalMavenWorkflow(workflowPath, content) {
  const manifest = readMavenWorkflowTransition();
  const change = manifest.workflows[workflowPath];
  if (!change) return content;
  if (gitBlob(content) === change.fromBlob) return content;
  content = priorJava21Workflow(workflowPath, content);
  requireValue(gitBlob(content) === change.toBlob, `unrecognized toolchain workflow blob ${workflowPath}`);
  const pieces = content.split(manifest.insertedStep);
  requireValue(pieces.length - 1 === change.stepCount, `Maven setup step count drift ${workflowPath}`);
  const historical = pieces.join('');
  requireValue(gitBlob(historical) === change.fromBlob, `undeclared workflow change ${workflowPath}`);
  return historical;
}

export function verifyMavenWorkflowTransition(workflows, priorTargets, root = ROOT) {
  const manifest = readMavenWorkflowTransition();
  const paths = Object.keys(priorTargets).sort();
  requireValue(canonical(paths) === canonical(Object.keys(workflows).sort()), 'Maven workflow inventory drift');
  let changed = 0;
  for (const workflowPath of paths) {
    const value = workflows[workflowPath];
    requireValue(value && gitBlob(value.content) === value.blobSha, `workflow content/blob mismatch ${workflowPath}`);
    const change = manifest.workflows[workflowPath];
    if (change) requireValue(change.fromBlob === priorTargets[workflowPath], `toolchain prior workflow identity mismatch ${workflowPath}`);
    if (value.blobSha === priorTargets[workflowPath]) continue;
    requireValue(change, `unrecognized toolchain workflow ${workflowPath}`);
    requireValue(gitBlob(historicalMavenWorkflow(workflowPath, value.content)) === priorTargets[workflowPath], `toolchain historical workflow mismatch ${workflowPath}`);
    changed += 1;
  }
  if (!changed) {
    requireNoHygieneJava21Sources(root);
    return { targetBlobs: priorTargets, toolchainTransition: null };
  }
  requireValue(changed === Object.keys(manifest.workflows).length, 'mixed Maven toolchain workflow state');
  for (const [relative, expected] of Object.entries(manifest.toolchainSourceBlobs)) {
    requireValue(gitBlob(readFileSync(path.join(root, relative))) === expected, `Maven toolchain source blob drift ${relative}`);
  }
  const java21WorkflowContinuation = verifyHygieneJava21WorkflowContinuation(workflows, root);
  return {
    targetBlobs: Object.fromEntries(paths.map(relative => [relative, workflows[relative].blobSha])),
    toolchainTransition: { schemaVersion: manifest.schemaVersion, manifestSha256: MANIFEST_SHA256,
      version: manifest.maven.version, distributionSha512: manifest.maven.sha512,
      changedWorkflowCount: changed, historicalWorkflowIdentityRetained: true,
      ...(java21WorkflowContinuation ? { java21WorkflowContinuation } : {}) },
  };
}

export function resolveMavenWorkflowTargets(root, priorTargets) {
  const workflows = Object.fromEntries(Object.keys(priorTargets).map(relative => {
    const content = readFileSync(path.join(root, relative), 'utf8');
    return [relative, { content, blobSha: gitBlob(content) }];
  }));
  return verifyMavenWorkflowTransition(workflows, priorTargets, root);
}

/** An exact reviewed file continuation, never a general expression/path exemption. */
export function verifyMavenSourceContinuation(sourcePath, source, acceptedBlob) {
  const actualBlob = gitBlob(source.content);
  requireValue(actualBlob === source.blobSha, 'current Semgrep source blob drift');
  if (actualBlob === acceptedBlob) return null;
  const manifest = readMavenWorkflowTransition();
  const continuation = manifest.sourceContinuations[sourcePath];
  requireValue(continuation && continuation.fromBlob === acceptedBlob && continuation.toBlob === actualBlob,
    'current Semgrep source blob drift');
  return { manifestSha256: MANIFEST_SHA256, fromBlob: acceptedBlob, toBlob: actualBlob,
    reasonCode: 'PINNED_MAVEN_AND_EXACT_WORKFLOW_IDENTITY_CONTINUATION' };
}
