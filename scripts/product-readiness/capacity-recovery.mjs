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
import {
  executeUpgradeRestoreRehearsal,
  upgradeRestorePlan,
} from './capacity-recovery/upgrade-restore.mjs';

const implementedRecoveryMarkers = new Set([
  'OUTBOX_CONNECTOR_BACKLOG_DRAIN_VOLUME_NOT_VERIFIED',
  'UPGRADE_REHEARSAL_NOT_VERIFIED',
  'BACKUP_RESTORE_NOT_VERIFIED',
  'RPO_RTO_NOT_VERIFIED',
]);

function printPlan(contract, jsonOutput) {
  const value = plan(contract);
  const backlog = backlogDrainPlan(contract);
  const upgradeRestore = upgradeRestorePlan(contract);
  value.stages = [
    ...value.stages,
    backlog.stage,
    upgradeRestore.stage,
  ];
  value.extendedClaims = [
    ...value.extendedClaims,
    backlog.claim,
    upgradeRestore.claim,
  ];
  value.nonClaims = [...new Set([
    ...value.nonClaims.filter(marker =>
      !implementedRecoveryMarkers.has(marker)),
    ...backlog.nonClaims,
    ...upgradeRestore.nonClaims,
  ])];
  value.backlogDrain = backlog;
  value.upgradeRestore = upgradeRestore;
  console.log(jsonOutput
    ? JSON.stringify(value, null, 2)
    : `Approval Platform capacity/recovery plan\n${JSON.stringify(value, null, 2)}`);
}

async function executeSmallDemoWithRetryEvidence(contract) {
  const retryEvidence = installProfileCommandRetryEvidence({
    outputRoot,
    identity: sourceIdentity(),
    runDirectorySuffix: null,
    contractFileName: 'profile-contract.json',
    evidenceFileName: 'small-demo-command-retry-evidence.json',
  });
  try {
    await execute(contract, {
      reuseRecoveryEvidence: false,
    });
  } finally {
    retryEvidence.restore();
  }
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
  await executeSmallDemoWithRetryEvidence(contract);
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
  await executeUpgradeRestoreRehearsal(contract);
}

main().catch((error) => {
  const detail = error instanceof Error ? error.message : String(error);
  console.error(`CAPACITY_RECOVERY_FAILED: ${detail}`);
  if (error instanceof UsageError) console.error(usage());
  process.exitCode = error instanceof UsageError ? 2 : 1;
});
