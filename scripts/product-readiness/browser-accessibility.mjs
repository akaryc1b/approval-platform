#!/usr/bin/env node

import { shouldRunInCi } from './pc-h5-runtime/ci-scope.mjs';
import {
  loadContract,
  parseArguments,
  printPlan,
  UsageError,
  usage,
} from './browser-accessibility/contract.mjs';
import { execute } from './browser-accessibility/runtime.mjs';

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
  if (options.command === 'ci' && !shouldRunInCi()) {
    console.log('BROWSER_ACCESSIBILITY_MATRIX_SCOPE=SKIPPED');
    return;
  }
  console.log('BROWSER_ACCESSIBILITY_MATRIX_SCOPE=SELECTED');
  await execute(contract);
}

main().catch((error) => {
  const detail = error instanceof Error ? error.message : String(error);
  console.error(`BROWSER_ACCESSIBILITY_MATRIX_FAILED: ${detail}`);
  if (error instanceof UsageError) console.error(usage());
  process.exitCode = error instanceof UsageError ? 2 : 1;
});
