#!/usr/bin/env node

import { rmSync } from 'node:fs';

import { shouldRunInCi } from './pc-h5-runtime/ci-scope.mjs';
import {
  loadContract,
  parseArguments,
  pcH5OutputDirectory,
  printPlan,
  UsageError,
  usage,
} from './purchase-payment-e2e/contract.mjs';
import { execute } from './purchase-payment-e2e/runtime.mjs';

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
  if (options.command === 'ci') {
    if (!shouldRunInCi()) return;
    await execute(true);
    return;
  }
  rmSync(pcH5OutputDirectory, { force: true, recursive: true });
  await execute(false);
}

main().catch((error) => {
  const detail = error instanceof Error ? error.message : String(error);
  console.error(`PURCHASE_PAYMENT_E2E_FAILED: ${detail}`);
  if (error instanceof UsageError) console.error(usage());
  process.exitCode = error instanceof UsageError ? 2 : 1;
});
