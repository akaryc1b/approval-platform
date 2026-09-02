#!/usr/bin/env node

import { shouldRunInCi } from './capacity-recovery/ci-scope.mjs';
import {
  loadContract,
  parseArguments,
  printPlan,
  UsageError,
  usage,
} from './capacity-recovery/contract.mjs';
import { executeProfileMatrix } from './capacity-recovery/profile-matrix.mjs';
import { execute } from './capacity-recovery/runtime.mjs';

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
  await executeProfileMatrix(contract);
}

main().catch((error) => {
  const detail = error instanceof Error ? error.message : String(error);
  console.error(`CAPACITY_RECOVERY_FAILED: ${detail}`);
  if (error instanceof UsageError) console.error(usage());
  process.exitCode = error instanceof UsageError ? 2 : 1;
});
