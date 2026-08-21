import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import {
  evidencePath,
  outputDirectory,
} from './contract.mjs';

function sha256File(path) {
  return createHash('sha256').update(readFileSync(path)).digest('hex');
}

export function verifyEvidence() {
  if (!existsSync(evidencePath)) {
    throw new Error('PC/H5 runtime evidence file was not created');
  }
  const evidence = JSON.parse(readFileSync(evidencePath, 'utf8'));
  if (evidence.claim !== 'PC_H5_APPROVAL_HANDOFF_PASSED') {
    throw new Error(`unexpected runtime claim: ${evidence.claim}`);
  }
  if (evidence.businessKey !== 'DEMO-PP-0001'
    || evidence.tenantId !== 'demo-purchase-payment') {
    throw new Error('runtime evidence does not match the deterministic scenario');
  }
  if (!evidence.instanceId || evidence.steps?.length !== 2) {
    throw new Error('runtime evidence does not retain the two client steps');
  }
  if (evidence.nextStage?.taskDefinitionKey !== 'financeCountersign'
    || evidence.nextStage?.taskIds?.length !== 2) {
    throw new Error(
      'runtime evidence did not reach the two-person countersign stage',
    );
  }
  for (const screenshot of evidence.screenshots || []) {
    const absolute = resolve(outputDirectory, screenshot.file);
    if (!existsSync(absolute)
      || sha256File(absolute) !== screenshot.sha256) {
      throw new Error(`screenshot digest mismatch: ${screenshot.file}`);
    }
  }
  return evidence;
}
