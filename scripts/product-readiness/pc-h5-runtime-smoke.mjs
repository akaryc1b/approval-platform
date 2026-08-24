#!/usr/bin/env node

import { createHash } from 'node:crypto';
import {
  appendFileSync,
  existsSync,
  lstatSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  rmSync,
} from 'node:fs';
import { relative, resolve, sep } from 'node:path';

import { shouldRunInCi } from './pc-h5-runtime/ci-scope.mjs';
import {
  chromeExecutable,
  java21Environment,
  outputDirectory,
  parseArguments,
  printPlan,
  repositoryRoot,
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
const evidenceEnvelopeBegin = 'APPROVAL_PC_H5_RUNTIME_EVIDENCE_ENVELOPE_BEGIN';
const evidenceEnvelopeEnd = 'APPROVAL_PC_H5_RUNTIME_EVIDENCE_ENVELOPE_END';
const maximumEvidenceFileBytes = 64 * 1024 * 1024;
const maximumEvidenceTotalBytes = 96 * 1024 * 1024;
const retainedEvidenceExtensions = new Set([
  '.json',
  '.md',
  '.png',
  '.zip',
]);
const requiredPassFiles = [
  'pc-h5-runtime-evidence.json',
  'pc-manager-before.png',
  'pc-manager-after.png',
  'h5-finance-before.png',
  'h5-finance-after.png',
  'h5-countersign-a-before.png',
  'h5-countersign-a-after.png',
  'h5-countersign-b-before.png',
  'h5-countersign-b-after.png',
];
const sha40 = /^[0-9a-f]{40}$/u;

function exactWorkflowHead() {
  const eventPath = process.env.GITHUB_EVENT_PATH;
  if (eventPath && existsSync(eventPath)) {
    try {
      const event = JSON.parse(readFileSync(eventPath, 'utf8'));
      const candidate = event?.pull_request?.head?.sha;
      if (sha40.test(candidate || '')) return candidate;
    } catch (error) {
      const detail = error instanceof Error ? error.message : String(error);
      throw new Error(`GitHub event payload could not be read: ${detail}`);
    }
  }
  const candidate = process.env.APPROVAL_DEMO_EXACT_HEAD_SHA
    || process.env.GITHUB_SHA;
  if (sha40.test(candidate || '')) return candidate;
  if (process.env.GITHUB_ACTIONS === 'true') {
    throw new Error('exact pull request Head SHA is unavailable');
  }
  return null;
}

function collectEvidenceFiles(directory, files = []) {
  if (!existsSync(directory)) return files;
  for (const name of readdirSync(directory).sort()) {
    const target = resolve(directory, name);
    const metadata = lstatSync(target);
    if (metadata.isSymbolicLink()) {
      throw new Error(`runtime evidence must not contain a symbolic link: ${target}`);
    }
    if (metadata.isDirectory()) {
      collectEvidenceFiles(target, files);
      continue;
    }
    if (!metadata.isFile()) continue;
    const extension = name.slice(name.lastIndexOf('.')).toLowerCase();
    if (retainedEvidenceExtensions.has(extension)) files.push(target);
  }
  return files;
}

function evidenceRelativePath(target) {
  const path = relative(outputDirectory, target).split(sep).join('/');
  if (!path || path === '..' || path.startsWith('../') || path.includes('/../')) {
    throw new Error(`runtime evidence escaped its output directory: ${target}`);
  }
  return path;
}

function appendCiEvidenceEnvelope(status, exactHeadSha) {
  if (process.env.GITHUB_ACTIONS !== 'true') return;
  const artifactLog = resolve(repositoryRoot, 'root-install.log');
  if (!existsSync(artifactLog)) {
    throw new Error('root-install.log is unavailable for permanent CI evidence');
  }

  let totalBytes = 0;
  const files = collectEvidenceFiles(outputDirectory).map(target => {
    const content = readFileSync(target);
    if (content.length > maximumEvidenceFileBytes) {
      throw new Error(
        `runtime evidence file exceeds ${maximumEvidenceFileBytes} bytes: ${target}`,
      );
    }
    totalBytes += content.length;
    if (totalBytes > maximumEvidenceTotalBytes) {
      throw new Error(
        `runtime evidence exceeds ${maximumEvidenceTotalBytes} total bytes`,
      );
    }
    return {
      path: evidenceRelativePath(target),
      size: content.length,
      sha256: createHash('sha256').update(content).digest('hex'),
      base64: content.toString('base64'),
    };
  });

  if (status === 'PASSED') {
    for (const required of requiredPassFiles) {
      if (!files.some(file => file.path === required)) {
        throw new Error(`passed runtime did not create required evidence: ${required}`);
      }
    }
    if (!files.some(file => file.path.endsWith('/trace.zip'))) {
      throw new Error('passed runtime did not create a Playwright trace.zip');
    }
  }

  const envelope = {
    schemaVersion: 1,
    evidenceKind: 'PC_H5_BROWSER_RUNTIME_CI_ARTIFACT_ENVELOPE_V1',
    status,
    exactHeadSha,
    githubRunId: process.env.GITHUB_RUN_ID || null,
    capturedAt: new Date().toISOString(),
    expectedFailureArtifacts: [
      'runtime-diagnostics.json',
      'runtime-diagnostics.md',
      'pc-demo-manager-runtime-failure.png',
      'h5-demo-finance-reviewer-runtime-failure.png',
      'h5-demo-finance-approver-a-runtime-failure.png',
      'h5-demo-finance-approver-b-runtime-failure.png',
      'playwright/**/trace.zip',
      'playwright/**/error-context.md',
      'playwright/**/*test-failed*.png',
    ],
    totalBytes,
    files,
  };
  appendFileSync(
    artifactLog,
    `\n${evidenceEnvelopeBegin}\n${JSON.stringify(envelope)}\n${evidenceEnvelopeEnd}\n`,
    'utf8',
  );
}

function verifyRetainedRuntimeEvidence(evidence, exactHeadSha) {
  if (exactHeadSha && evidence.commitSha !== exactHeadSha) {
    throw new Error(
      `runtime evidence Head mismatch: expected ${exactHeadSha}, got ${evidence.commitSha}`,
    );
  }
  const taskIds = evidence.countersignStage?.taskIds || [];
  const processedTaskIds = evidence.steps?.slice(2).map(step => step.taskId) || [];
  if (taskIds.length !== 2
    || new Set(taskIds).size !== 2
    || taskIds.slice().sort().join(',') !== processedTaskIds.slice().sort().join(',')) {
    throw new Error('runtime evidence must retain two processed countersign task IDs');
  }
  if (evidence.finalState?.status !== 'COMPLETED'
    || evidence.finalState?.currentTaskDefinitionKey) {
    throw new Error('runtime evidence must retain the completed process state');
  }
}

async function executeSmoke() {
  rmSync(outputDirectory, { force: true, recursive: true });
  mkdirSync(outputDirectory, { mode: 0o700, recursive: true });

  const javaEnvironment = java21Environment();
  const browserPath = chromeExecutable();
  const exactHeadSha = exactWorkflowHead();
  const managed = [];
  let ciEvidenceRetained = process.env.GITHUB_ACTIONS !== 'true';
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
        APPROVAL_DEMO_EXACT_HEAD_SHA: exactHeadSha || '',
        APPROVAL_DEMO_H5_URL:
          'http://127.0.0.1:9000/#/pages/task/list',
        APPROVAL_DEMO_PC_URL:
          'http://127.0.0.1:5777/approval/workbench?demoOperator=demo-manager',
        APPROVAL_DEMO_PLAYWRIGHT_TIMEOUT_MS: String(browserTimeoutMs),
      },
    );

    const evidence = verifyEvidence();
    verifyRetainedRuntimeEvidence(evidence, exactHeadSha);
    appendCiEvidenceEnvelope('PASSED', exactHeadSha);
    ciEvidenceRetained = true;
    console.log('\nPC_H5_APPROVAL_HANDOFF_PASSED');
    console.log(`exactHeadSha=${evidence.commitSha}`);
    console.log(`instanceId=${evidence.instanceId}`);
    console.log(`managerTaskId=${evidence.steps[0].taskId}`);
    console.log(`financeReviewTaskId=${evidence.steps[1].taskId}`);
    console.log(`financeCountersignATaskId=${evidence.steps[2].taskId}`);
    console.log(`financeCountersignBTaskId=${evidence.steps[3].taskId}`);
    console.log(`finalInstanceStatus=${evidence.finalState.status}`);
    for (const nonClaim of smokePlan().nonClaims) {
      console.log(nonClaim);
    }
  } catch (error) {
    if (!ciEvidenceRetained) {
      try {
        appendCiEvidenceEnvelope('FAILED', exactHeadSha);
        ciEvidenceRetained = true;
      } catch (retentionError) {
        const original = error instanceof Error ? error.message : String(error);
        const retention = retentionError instanceof Error
          ? retentionError.message
          : String(retentionError);
        throw new Error(
          `${original}; CI runtime evidence retention failed: ${retention}`,
          { cause: error },
        );
      }
    }
    throw error;
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
      const detail = error instanceof Error ? error.message : String(error);
      console.error(`PC_H5_RUNTIME_CLEANUP_WARNING: ${detail}`);
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
  const detail = error instanceof Error ? error.message : String(error);
  console.error(`PC_H5_RUNTIME_SMOKE_FAILED: ${detail}`);
  if (error instanceof UsageError) console.error(usage());
  process.exitCode = error instanceof UsageError ? 2 : 1;
});
