#!/usr/bin/env node

import { mkdirSync, rmSync } from 'node:fs';
import { resolve } from 'node:path';

import { shouldRunInCi } from './pc-h5-runtime/ci-scope.mjs';
import {
  chromeExecutable,
  java21Environment,
  outputDirectory,
  parseArguments,
  printPlan,
  smokePlan,
  UsageError,
  usage,
} from './pc-h5-runtime/contract.mjs';
import { verifyEvidence } from './pc-h5-runtime/evidence.mjs';
import {
  delay,
  runNodeChecked,
  runPnpmChecked,
  startManagedNode,
  terminateManaged,
  waitForHttp,
  waitForMarker,
} from './pc-h5-runtime/processes.mjs';

const backendTimeoutMs = 15 * 60_000;
const clientTimeoutMs = 5 * 60_000;
const browserTimeoutMs = 5 * 60_000;

async function executeSmoke() {
  rmSync(outputDirectory, { force: true, recursive: true });
  mkdirSync(outputDirectory, { mode: 0o700, recursive: true });

  const javaEnvironment = java21Environment();
  const browserPath = chromeExecutable();
  const managed = [];
  try {
    runPnpmChecked('Install generated Vben workspace', ['web:install']);
    runPnpmChecked('Install generated UniApp workspace', ['mobile:install']);

    const backend = startManagedNode(
      'Start real local backend and deterministic seed',
      ['scripts/product-readiness/demo-backend.mjs', 'start'],
      resolve(outputDirectory, 'backend.log'),
      javaEnvironment,
    );
    managed.push(backend);
    await waitForMarker(
      backend,
      'BACKEND_LOCAL_START_VERIFIED',
      backendTimeoutMs,
    );
    await waitForMarker(
      backend,
      'PURCHASE_PAYMENT_DEMO_SEED_APPLIED',
      backendTimeoutMs,
    );

    const pc = startManagedNode(
      'Start PC client as demo-manager',
      [
        'scripts/product-readiness/demo-client.mjs',
        'pc',
        '--actor',
        'demo-manager',
        '--port',
        '5777',
        '--skip-install',
      ],
      resolve(outputDirectory, 'pc.log'),
      process.env,
    );
    managed.push(pc);

    const h5 = startManagedNode(
      'Start H5 client as demo-finance-reviewer',
      [
        'scripts/product-readiness/demo-client.mjs',
        'h5',
        '--actor',
        'demo-finance-reviewer',
        '--port',
        '9000',
        '--skip-install',
      ],
      resolve(outputDirectory, 'h5.log'),
      process.env,
    );
    managed.push(h5);

    await Promise.all([
      waitForHttp('http://127.0.0.1:5777/', clientTimeoutMs),
      waitForHttp('http://127.0.0.1:9000/', clientTimeoutMs),
    ]);

    runPnpmChecked(
      'Execute PC/H5 approval handoff in system Chromium',
      [
        '--dir',
        '.upstream/vben',
        '--filter',
        '@vben/playground',
        'exec',
        'playwright',
        'test',
        '--config=product-readiness.playwright.config.ts',
        '__tests__/e2e/product-readiness-pc-h5-runtime.spec.ts',
      ],
      {
        ...process.env,
        APPROVAL_DEMO_BACKEND_ORIGIN: 'http://127.0.0.1:8080',
        APPROVAL_DEMO_CHROME_PATH: browserPath,
        APPROVAL_DEMO_EVIDENCE_DIR: outputDirectory,
        APPROVAL_DEMO_H5_URL:
          'http://127.0.0.1:9000/#/pages/task/list?demoOperator=demo-finance-reviewer',
        APPROVAL_DEMO_PC_URL:
          'http://127.0.0.1:5777/approval/workbench?demoOperator=demo-manager',
        APPROVAL_DEMO_PLAYWRIGHT_TIMEOUT_MS: String(browserTimeoutMs),
      },
    );

    const evidence = verifyEvidence();
    console.log('\nPC_H5_APPROVAL_HANDOFF_PASSED');
    console.log(`instanceId=${evidence.instanceId}`);
    console.log(`managerTaskId=${evidence.steps[0].taskId}`);
    console.log(`financeReviewTaskId=${evidence.steps[1].taskId}`);
    console.log(
      `financeCountersignTasks=${evidence.nextStage.taskIds.join(',')}`,
    );
    for (const nonClaim of smokePlan().nonClaims) {
      console.log(nonClaim);
    }
  } finally {
    for (const processState of managed.reverse()) {
      terminateManaged(processState);
    }
    await delay(1_000);
    try {
      runNodeChecked(
        'Stop isolated local infrastructure without deleting evidence',
        ['scripts/product-readiness/demo-backend.mjs', 'stop'],
        javaEnvironment,
      );
    } catch (error) {
      console.error(`PC_H5_RUNTIME_CLEANUP_WARNING: ${error.message}`);
    }
  }
}

async function main() {
  const options = parseArguments(process.argv.slice(2));
  if (options.help) {
    console.log(usage());
    return;
  }
  if (options.command === 'plan') {
    printPlan(options.json);
    return;
  }
  if (options.command === 'ci' && !shouldRunInCi()) return;
  await executeSmoke();
}

main().catch(error => {
  console.error(`PC_H5_RUNTIME_SMOKE_FAILED: ${error.message}`);
  if (error instanceof UsageError) console.error(usage());
  process.exitCode = error instanceof UsageError ? 2 : 1;
});
