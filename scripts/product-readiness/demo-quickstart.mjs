#!/usr/bin/env node

import { shouldRunInCi } from './pc-h5-runtime/ci-scope.mjs';
import {
  loadContract,
  parseArguments,
  printPlan,
  UsageError,
  usage,
} from './quick-start/contract.mjs';
import { execute } from './quick-start/runtime.mjs';

const commandTimeoutVariable = 'APPROVAL_DEMO_COMMAND_TIMEOUT_MS';

function configureBoundedChildCommands(contract) {
  process.env[commandTimeoutVariable] = String(
    contract.maximumReadySeconds * 1_000,
  );
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
    await execute({ keepAlive: false });
    console.log('QUICK_START_SECOND_CLEAN_RUN_STARTING');
    await execute({ keepAlive: false });
    return;
  }
  await execute({ keepAlive: true });
}

main().catch((error) => {
  const detail = error instanceof Error ? error.message : String(error);
  console.error(`DEMO_QUICK_START_FAILED: ${detail}`);
  if (error instanceof UsageError) console.error(usage());
  process.exitCode = error instanceof UsageError ? 2 : 1;
});
