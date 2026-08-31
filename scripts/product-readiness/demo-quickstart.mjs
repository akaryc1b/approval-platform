#!/usr/bin/env node

import { shouldRunInCi } from './pc-h5-runtime/ci-scope.mjs';
import {
  loadContract,
  parseArguments,
  printPlan,
  sourceIdentity,
  UsageError,
  usage,
} from './quick-start/contract.mjs';
import { resetLedger } from './quick-start/evidence.mjs';
import { execute } from './quick-start/runtime.mjs';

const commandTimeoutVariable = 'APPROVAL_DEMO_COMMAND_TIMEOUT_MS';

function configureBoundedChildCommands(contract) {
  process.env[commandTimeoutVariable] = String(
    contract.maximumReadySeconds * 1_000,
  );
}

function launcherFailureId() {
  return `launcher-${new Date().toISOString().replace(/[:.]/gu, '-')}`;
}

async function executeWithLedgerReset(options) {
  try {
    return await execute(options);
  } catch (error) {
    try {
      resetLedger(sourceIdentity(), launcherFailureId());
    } catch (resetError) {
      const detail = resetError instanceof Error
        ? resetError.message
        : String(resetError);
      console.error(`QUICK_START_LEDGER_RESET_FAILED: ${detail}`);
    }
    throw error;
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
  configureBoundedChildCommands(contract);
  if (options.command === 'ci') {
    if (!shouldRunInCi()) return;
    await executeWithLedgerReset({ keepAlive: false });
    console.log('QUICK_START_SECOND_CLEAN_RUN_STARTING');
    await executeWithLedgerReset({ keepAlive: false });
    return;
  }
  await executeWithLedgerReset({ keepAlive: true });
}

main().catch((error) => {
  const detail = error instanceof Error ? error.message : String(error);
  console.error(`DEMO_QUICK_START_FAILED: ${detail}`);
  if (error instanceof UsageError) console.error(usage());
  process.exitCode = error instanceof UsageError ? 2 : 1;
});
