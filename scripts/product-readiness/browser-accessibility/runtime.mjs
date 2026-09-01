import {
  existsSync,
  mkdirSync,
  readFileSync,
} from 'node:fs';
import { resolve } from 'node:path';

import { chromeExecutable } from '../pc-h5-runtime/contract.mjs';
import {
  delay,
  runPnpmChecked,
  startManagedNode,
  terminateManaged,
  waitForMarker,
} from '../pc-h5-runtime/processes.mjs';
import {
  outputRoot,
  repositoryRoot,
  runIdentifier,
  sourceIdentity,
  writeJson,
} from './contract.mjs';
import { appendCiEvidenceEnvelope } from './evidence.mjs';

const expectedProjectFiles = [
  'system-chromium/matrix-evidence.json',
  'bundled-firefox/matrix-evidence.json',
  'bundled-webkit/matrix-evidence.json',
];

function processExited(child) {
  return child.exitCode !== null || child.signalCode !== null;
}

function marker(buffer, name) {
  const match = buffer.match(
    new RegExp(`(?:^|\\n)${name}=([^\\r\\n]+)`, 'u'),
  );
  if (!match?.[1]) throw new Error(`Quick Start did not expose ${name}`);
  return match[1].trim();
}

async function waitForExit(processState, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (!processExited(processState.child) && Date.now() < deadline) {
    await delay(250);
  }
  if (processExited(processState.child)) return;
  const child = processState.child;
  if (child.pid) {
    try {
      if (process.platform === 'win32') child.kill('SIGKILL');
      else process.kill(-child.pid, 'SIGKILL');
    } catch (error) {
      if (error.code !== 'ESRCH') throw error;
    }
  }
  const killDeadline = Date.now() + 5_000;
  while (!processExited(child) && Date.now() < killDeadline) {
    await delay(250);
  }
  if (!processExited(child)) {
    throw new Error(`${processState.label} did not terminate after SIGKILL`);
  }
  throw new Error(
    `${processState.label} exceeded its graceful cleanup timeout`,
  );
}

function prepareBrowserRuntimes(environment, timeoutMs) {
  const installArguments = [
    '--dir',
    '.upstream/vben',
    'exec',
    'playwright',
    'install',
  ];
  if (process.platform === 'linux'
      && process.env.GITHUB_ACTIONS === 'true') {
    installArguments.push('--with-deps');
  }
  installArguments.push('firefox', 'webkit');
  runPnpmChecked(
    'Prepare bounded Playwright Firefox and WebKit runtimes',
    installArguments,
    environment,
    timeoutMs,
  );
}

function validateProjectEvidence(value, project, contract, identity) {
  if (value?.schemaVersion !== 1
      || value?.evidenceKind !== 'BROWSER_ACCESSIBILITY_PROJECT_V1'
      || value?.status !== 'PASSED'
      || value?.projectId !== project.id
      || value?.engine !== project.engine
      || value?.commitSha !== identity.commitSha
      || value?.treeSha !== identity.treeSha
      || value?.tenantId !== contract.scenario.tenantId
      || value?.businessKey !== contract.scenario.businessKey
      || value?.pc?.cjkGlyphsRendered !== true
      || value?.h5?.cjkGlyphsRendered !== true
      || value?.accessibility?.criticalViolations !== 0
      || value?.accessibility?.seriousViolations !== 0) {
    throw new Error(`inconsistent evidence for ${project.id}`);
  }
  if (project.id === 'system-chromium'
      && value?.keyboard?.authenticatedPcTaskFlow !== true) {
    throw new Error('Chromium keyboard evidence is incomplete');
  }
  return value;
}

function validateQuickStartCleanup(path) {
  const cleanupPath = resolve(path, 'cleanup-evidence.json');
  if (!existsSync(cleanupPath)) {
    throw new Error('Quick Start cleanup evidence was not created');
  }
  const cleanup = JSON.parse(readFileSync(cleanupPath, 'utf8'));
  const requiredActions = [
    'deleted:approval-platform-demo-volume',
    'released-port:5432',
    'released-port:5777',
    'released-port:6379',
    'released-port:8080',
    'released-port:9000',
  ];
  for (const action of requiredActions) {
    if (!cleanup.actions?.includes(action)) {
      throw new Error(`Quick Start cleanup missing ${action}`);
    }
  }
  return cleanup;
}

export async function execute(contract) {
  const identity = sourceIdentity();
  const runId = runIdentifier();
  const runDirectory = resolve(outputRoot, runId);
  mkdirSync(runDirectory, { recursive: true, mode: 0o700 });
  const startedAt = new Date();
  const deadline = startedAt.getTime()
    + contract.maximumRuntimeSeconds * 1_000;
  const remaining = (label) => {
    const value = deadline - Date.now();
    if (value <= 0) {
      throw new Error(`${label} exceeded the browser matrix deadline`);
    }
    return value;
  };
  writeJson(resolve(runDirectory, 'source-identity.json'), {
    schemaVersion: 1,
    evidenceKind: 'BROWSER_ACCESSIBILITY_SOURCE_IDENTITY_V1',
    runId,
    capturedAt: startedAt.toISOString(),
    ...identity,
  });
  writeJson(resolve(runDirectory, 'matrix-contract.json'), contract);

  const environment = {
    ...process.env,
    APPROVAL_DEMO_COMMAND_TIMEOUT_MS: String(
      contract.maximumRuntimeSeconds * 1_000,
    ),
  };
  const quickStart = startManagedNode(
    'Start and retain the accepted Quick Start lifecycle',
    ['scripts/product-readiness/demo-quickstart.mjs', 'start'],
    resolve(runDirectory, 'quick-start.log'),
    environment,
  );
  let quickStartEvidence;
  let cleanup;
  let matrix;
  let executionError;
  let cleanupError;
  try {
    await waitForMarker(
      quickStart,
      'QUICK_START_EVIDENCE=',
      remaining('Quick Start readiness'),
    );
    const pcUrl = marker(quickStart.state.buffer, 'QUICK_START_PC_URL');
    const h5Url = marker(quickStart.state.buffer, 'QUICK_START_H5_URL');
    quickStartEvidence = marker(
      quickStart.state.buffer,
      'QUICK_START_EVIDENCE',
    );
    const tenantId = marker(quickStart.state.buffer, 'QUICK_START_TENANT');
    const businessKey = marker(
      quickStart.state.buffer,
      'QUICK_START_BUSINESS_KEY',
    );
    const pcActorId = marker(
      quickStart.state.buffer,
      'QUICK_START_PC_ACTOR',
    );
    const h5ActorId = marker(
      quickStart.state.buffer,
      'QUICK_START_H5_ACTOR',
    );
    if (tenantId !== contract.scenario.tenantId
        || businessKey !== contract.scenario.businessKey
        || pcActorId !== contract.scenario.pcActorId
        || h5ActorId !== contract.scenario.h5ActorId) {
      throw new Error('Quick Start identity differs from governed matrix');
    }

    prepareBrowserRuntimes(environment, remaining('browser installation'));
    runPnpmChecked(
      'Execute the real PC/H5 browser and accessibility matrix',
      [
        '--dir',
        '.upstream/vben',
        '--filter',
        '@vben/playground',
        'exec',
        'playwright',
        'test',
        '--config=browser-accessibility.playwright.config.ts',
        '__tests__/e2e/product-readiness-browser-accessibility.spec.ts',
      ],
      {
        ...environment,
        APPROVAL_BROWSER_ACCESSIBILITY_EVIDENCE_DIR: runDirectory,
        APPROVAL_BROWSER_ACCESSIBILITY_MANIFEST:
          'config/demo/browser-accessibility-matrix.json',
        APPROVAL_DEMO_BACKEND_ORIGIN: 'http://127.0.0.1:8080',
        APPROVAL_DEMO_CHROME_PATH: chromeExecutable(),
        APPROVAL_DEMO_EVIDENCE_DIR: runDirectory,
        APPROVAL_DEMO_EXACT_HEAD_SHA: identity.commitSha,
        APPROVAL_DEMO_EXACT_TREE_SHA: identity.treeSha,
        APPROVAL_DEMO_H5_URL: h5Url,
        APPROVAL_DEMO_PC_URL: pcUrl,
        APPROVAL_DEMO_QUICK_START_BUSINESS_KEY: businessKey,
        APPROVAL_DEMO_QUICK_START_H5_ACTOR: h5ActorId,
        APPROVAL_DEMO_QUICK_START_PC_ACTOR: pcActorId,
        APPROVAL_DEMO_QUICK_START_TENANT: tenantId,
        APPROVAL_DEMO_REPOSITORY_ROOT: repositoryRoot,
      },
      remaining('browser matrix execution'),
    );

    const projects = contract.projects.map((project, index) => {
      const relativePath = expectedProjectFiles[index];
      const absolutePath = resolve(runDirectory, relativePath);
      if (!existsSync(absolutePath)) {
        throw new Error(`missing browser evidence: ${relativePath}`);
      }
      return validateProjectEvidence(
        JSON.parse(readFileSync(absolutePath, 'utf8')),
        project,
        contract,
        identity,
      );
    });
    matrix = {
      schemaVersion: 1,
      evidenceKind: 'BROWSER_ACCESSIBILITY_MATRIX_V1',
      status: 'PASSED_PENDING_CLEANUP',
      runId,
      commitSha: identity.commitSha,
      treeSha: identity.treeSha,
      startedAt: startedAt.toISOString(),
      completedBrowserChecksAt: new Date().toISOString(),
      scenario: contract.scenario,
      projects,
      claims: [],
      nonClaims: contract.nonClaims,
    };
    writeJson(resolve(runDirectory, 'matrix-summary.json'), matrix);
  } catch (error) {
    executionError = error;
  } finally {
    try {
      terminateManaged(quickStart);
      await waitForExit(quickStart, 180_000);
      if (!quickStartEvidence) {
        const match = quickStart.state.buffer.match(
          /(?:^|\n)QUICK_START_EVIDENCE=([^\r\n]+)/u,
        );
        quickStartEvidence = match?.[1]?.trim();
      }
      if (!quickStartEvidence) {
        throw new Error('Quick Start evidence path is unavailable');
      }
      cleanup = validateQuickStartCleanup(quickStartEvidence);
    } catch (error) {
      cleanupError = error;
    }
  }

  if (executionError || cleanupError) {
    const failure = {
      schemaVersion: 1,
      evidenceKind: 'BROWSER_ACCESSIBILITY_FAILURE_V1',
      runId,
      failedAt: new Date().toISOString(),
      execution: executionError instanceof Error
        ? executionError.message
        : executionError ? String(executionError) : null,
      cleanup: cleanupError instanceof Error
        ? cleanupError.message
        : cleanupError ? String(cleanupError) : null,
      quickStartEvidence: quickStartEvidence ?? null,
      nonClaims: contract.nonClaims,
    };
    writeJson(resolve(runDirectory, 'runtime-failure.json'), failure);
    appendCiEvidenceEnvelope('FAILED', runDirectory, identity);
    throw new Error(`browser accessibility matrix failed: ${
      JSON.stringify(failure)
    }`, { cause: executionError || cleanupError });
  }

  const summary = {
    ...matrix,
    status: 'PASSED',
    completedAt: new Date().toISOString(),
    quickStartEvidence,
    cleanup,
    claims: contract.claims,
  };
  writeJson(resolve(runDirectory, 'runtime-summary.json'), summary);
  appendCiEvidenceEnvelope('PASSED', runDirectory, identity);
  for (const claim of contract.claims) console.log(claim);
  for (const nonClaim of contract.nonClaims) console.log(nonClaim);
  console.log(`BROWSER_ACCESSIBILITY_RUN_ID=${runId}`);
  console.log(`BROWSER_ACCESSIBILITY_EVIDENCE=${runDirectory}`);
  return summary;
}
