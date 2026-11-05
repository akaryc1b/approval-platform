// Historical comparison only. Never use this projection as current scan evidence.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { canonical, seal } from './public-commit-review-fixture.mjs';
import { readDesignerPrototypeRemediationPlan, requireDesignerPrototypeRemediationReceipt }
  from '../../security/m6-pr-e-e3-verify-designer-prototype-remediation.mjs';

export function requireLegacyCapturePrototypeContinuation(e4, outputs, frozenHashes) {
  const before = canonical(outputs), prior = readDesignerPrototypeRemediationPlan();
  const current = outputs.i2.designerPrototypeRemediation;
  // The production verifier checks the complete V2 receipt and freshly validates
  // all six current artifacts and their exact guarded-clone predecessor relation.
  requireDesignerPrototypeRemediationReceipt(e4, current);
  requireDesignerPrototypeRemediationReceipt(e4, outputs.reconciliation.designerPrototypeRemediation);
  assert.equal(current.schemaVersion, 'M6_PR_E_E3_DESIGNER_PROTOTYPE_REMEDIATION_EVIDENCE_V2');
  assert.deepEqual(outputs.reconciliation.designerPrototypeRemediation, current);
  assert.deepEqual(seal(outputs.i2), outputs.i2, 'current I2 must retain its canonical seal');
  assert.deepEqual(outputs.i2.remediatedHistoricalFindings, current.remediatedFindings);
  assert.deepEqual(outputs.i2.historicallyRemediatedFindings, current.remediatedFindings);

  // Reverse only the reviewed V1 -> V2 source-continuation fields. Every other
  // field survives the complete frozen-hash comparison, including decisions,
  // finding identities, counts, coverage, current E4 binding and release block.
  const archived = structuredClone(current);
  delete archived.sourceContinuation;
  archived.schemaVersion = 'M6_PR_E_E3_DESIGNER_PROTOTYPE_REMEDIATION_EVIDENCE_V1';
  archived.reviewedArtifacts = structuredClone(prior.reviewedArtifacts);
  assert.equal(archived.remediatedFindings.length, 1);
  archived.remediatedFindings[0].correctedSourceBlobSha = prior.reviewedArtifacts[prior.historicalFinding.sourcePath].blobSha;
  const archivedReceipt = seal(archived), projected = structuredClone(outputs);
  projected.i2.designerPrototypeRemediation = archivedReceipt;
  projected.i2.remediatedHistoricalFindings = structuredClone(archivedReceipt.remediatedFindings);
  projected.i2.historicallyRemediatedFindings = structuredClone(archivedReceipt.remediatedFindings);
  projected.i2 = seal(projected.i2);
  projected.reconciliation.designerPrototypeRemediation = archivedReceipt;
  const hashes = Object.fromEntries(Object.entries(projected).map(([name, value]) =>
    [name, createHash('sha256').update(canonical(value)).digest('hex')]));
  assert.deepEqual(hashes, frozenHashes, 'only the reviewed prototype source continuation may differ');
  assert.equal(canonical(outputs), before, 'historical comparison cannot mutate current evidence');
  return { historicalOnly: true, currentAcceptanceClaimed: false, frozenHashes: hashes };
}
