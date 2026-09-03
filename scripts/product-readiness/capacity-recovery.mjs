#!/usr/bin/env node

import { shouldRunInCi } from './capacity-recovery/ci-scope.mjs';
import {
  loadContract,
  outputRoot,
  parseArguments,
  plan,
  sourceIdentity,
  UsageError,
  usage,
} from './capacity-recovery/contract.mjs';
import {
  backlogDrainPlan,
  executeBacklogDrain,
} from './capacity-recovery/backlog-drain.mjs';
import { executeProfileMatrix } from './capacity-recovery/profile-matrix.mjs';
import { installProfileCommandRetryEvidence } from './capacity-recovery/retryable-command-fetch.mjs';
import { execute } from './capacity-recovery/runtime.mjs';

function printPlan(contract, jsonOutput) {
  const value = plan(contract);
  const backlog = backlogDrainPlan(contract);
  value.stages = [...value.stages, backlog.stage];
  value.extendedClaims = [
    ...value.extendedClaims,
    backlog.claim,
  ];
  value.nonClaims = value.nonClaims.filter(marker =>
    marker !== 'OUTBOX_CONNECTOR_BACKLOG_DRAIN_VOLUME_NOT_VERIFIED');
  value.nonClaims.push(...backlog.nonClaims);
  value.backlogDrain = backlog;
  console.log(jsonOutput
    ? JSON.stringify(value, null, 2)
    : `Approval Platform capacity/recovery plan\n${JSON.stringify(value, null, 2)}`);
}

async function main() {
  const options = parseArguments(process.argv.slice(2));
  if (options.help) {
    console.log(usage());
    return;
  }
  const contract = loadContract();
  if (options.command === 'plan') {
    printPlan(contract, options.json);
    return;
  }
  if (options.command === 'ci' && !shouldRunInCi()) return;
  await execute(contract, {
    reuseRecoveryEvidence: false,
  });
  const retryEvidence = installProfileCommandRetryEvidence({
    outputRoot,
    identity: sourceIdentity(),
  });
  try {
    await executeProfileMatrix(contract);
  } finally {
    retryEvidence.restore();
  }
  await executeBacklogDrain(contract);
}

main().catch((error) => {
  const detail = error instanceof Error ? error.message : String(error);
  console.error(`CAPACITY_RECOVERY_FAILED: ${detail}`);
  if (error instanceof UsageError) console.error(usage());
  process.exitCode = error instanceof UsageError ? 2 : 1;
});
