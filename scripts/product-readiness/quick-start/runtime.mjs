import {
  existsSync,
  mkdirSync,
  readFileSync,
} from 'node:fs';
import { resolve } from 'node:path';

import {
  chromeExecutable,
  java21Environment,
} from '../pc-h5-runtime/contract.mjs';
import {
  delay,
  runNodeChecked,
  runPnpmChecked,
  startManagedNode,
  terminateManaged,
  waitForHttp,
  waitForMarker,
} from '../pc-h5-runtime/processes.mjs';
import { waitForPortAvailable } from '../purchase-payment-e2e/evidence.mjs';
import {
  browserEvidenceFile,
  clientUrl,
  ledgerPath,
  loadContract,
  outputRoot,
  repositoryRoot,
  runIdentifier,
  sourceIdentity,
  writeJson,
} from './contract.mjs';
import {
  appendCiEvidenceEnvelope,
  environmentSnapshot,
  nextSuccessfulLedger,
  resetLedger,
} from './evidence.mjs';

function processExited(child) {
  return child.exitCode !== null || child.signalCode !== null;
}

function terminateProcessGroup(child, signal) {
  if (!child?.pid || processExited(child)) return;
  try {
    if (process.platform === 'win32') child.kill(signal);
    else process.kill(-child.pid, signal);
  } catch (error) {
    if (error.code !== 'ESRCH') throw error;
  }
}

async function stopManaged(processState) {
  terminateManaged(processState);
  let deadline = Date.now() + 10_000;
  while (!processExited(processState.child) && Date.now() < deadline) {
    await delay(250);
  }
  if (processExited(processState.child)) return;
  terminateProcessGroup(processState.child, 'SIGKILL');
  deadline = Date.now() + 5_000;
  while (!processExited(processState.child) && Date.now() < deadline) {
    await delay(250);
  }
  if (!processExited(processState.child)) {
    throw new Error(`${processState.label} did not terminate after SIGKILL`);
  }
}

function resetDisposableData(environment) {
  runNodeChecked(
    'Delete disposable local Quick Start data',
    [
      'scripts/product-readiness/demo-backend.mjs',
      'reset',
      '--confirm-local-data-loss',
    ],
    environment,
  );
}

function remainingMilliseconds(deadline, label) {
  const remaining = deadline - Date.now();
  if (remaining <= 0) {
    throw new Error(`${label} exceeded the 10-minute Quick Start deadline`);
  }
  return remaining;
}

async function healthEvidence(url, deadline) {
  let lastDetail = 'not requested';
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url, {
        headers: { Accept: 'application/json' },
        signal: AbortSignal.timeout(2_000),
      });
      const body = await response.text();
      lastDetail = `HTTP ${response.status}: ${body}`;
      if (response.ok && JSON.parse(body)?.status === 'UP') {
        return {
          status: response.status,
          body: JSON.parse(body),
          capturedAt: new Date().toISOString(),
        };
      }
    } catch (error) {
      lastDetail = error instanceof Error ? error.message : String(error);
    }
    await delay(500);
  }
  throw new Error(`backend health did not become UP: ${lastDetail}`);
}

function validateBrowserEvidence(value, contract, identity) {
  if (value?.schemaVersion !== 1
      || value?.evidenceKind !== 'QUICK_START_BROWSER_READY_V1'
      || value?.status !== 'PASSED'
      || value?.commitSha !== identity.commitSha
      || value?.tenantId !== contract.scenario.tenant.id
      || value?.businessKey !== contract.scenario.request.businessKey
      || value?.pc?.actorId !== contract.clients.pc.actorId
      || value?.h5?.actorId !== contract.clients.h5.actorId
      || value?.pc?.businessKeyVisible !== true
      || value?.h5?.businessKeyVisible !== true) {
    throw new Error('Quick Start browser evidence is inconsistent');
  }
  return value;
}

function waitForInteractiveStop(managed) {
  return new Promise((resolvePromise, reject) => {
    let settled = false;
    const listeners = [];
    const finish = (result, error) => {
      if (settled) return;
      settled = true;
      for (const [emitter, event, listener] of listeners) {
        emitter.removeListener(event, listener);
      }
      if (error) reject(error);
      else resolvePromise(result);
    };
    for (const signal of ['SIGINT', 'SIGTERM']) {
      const listener = () => finish({ signal });
      process.once(signal, listener);
      listeners.push([process, signal, listener]);
    }
    for (const processState of managed) {
      const listener = (code, signal) => finish(
        undefined,
        new Error(
          `${processState.label} exited while Quick Start was attached: `
          + `code=${code ?? '<none>'} signal=${signal ?? '<none>'}`,
        ),
      );
      processState.child.once('close', listener);
      listeners.push([processState.child, 'close', listener]);
    }
  });
}

async function cleanup(managed, environment, runDirectory) {
  const actions = [];
  for (const processState of [...managed].reverse()) {
    await stopManaged(processState);
    actions.push(`stopped:${processState.label}`);
  }
  resetDisposableData(environment);
  actions.push('deleted:approval-platform-demo-volume');
  for (const port of [5432, 5777, 6379, 8080, 9000]) {
    await waitForPortAvailable(port);
    actions.push(`released-port:${port}`);
  }
  const evidence = {
    schemaVersion: 1,
    evidenceKind: 'QUICK_START_CLEANUP_V1',
    actions,
    completedAt: new Date().toISOString(),
  };
  writeJson(resolve(runDirectory, 'cleanup-evidence.json'), evidence);
  return evidence;
}

export async function execute({ keepAlive }) {
  const contract = loadContract();
  const identity = sourceIdentity();
  const runId = runIdentifier();
  const runDirectory = resolve(outputRoot, runId);
  mkdirSync(runDirectory, { recursive: true, mode: 0o700 });
  const startedAt = new Date();
  const deadline = startedAt.getTime() + contract.maximumReadySeconds * 1_000;
  writeJson(resolve(runDirectory, 'source-identity.json'), {
    schemaVersion: 1,
    evidenceKind: 'QUICK_START_SOURCE_IDENTITY_V1',
    runId,
    capturedAt: startedAt.toISOString(),
    ...identity,
  });
  writeJson(resolve(runDirectory, 'contract.json'), {
    schemaVersion: 1,
    maximumReadySeconds: contract.maximumReadySeconds,
    tenantId: contract.scenario.tenant.id,
    businessKey: contract.scenario.request.businessKey,
    clients: contract.clients,
    claims: contract.claims,
    nonClaims: contract.nonClaims,
  });
  const environment = java21Environment();
  writeJson(
    resolve(runDirectory, 'environment.json'),
    environmentSnapshot(environment),
  );
  const managed = [];
  let startup;
  let cleanupEvidence;
  let executionError;
  let cleanupError;
  try {
    resetDisposableData(environment);

    const backend = startManagedNode(
      'Start the existing demo backend lifecycle',
      ['scripts/product-readiness/demo-backend.mjs', 'start'],
      resolve(runDirectory, 'backend.log'),
      environment,
    );
    managed.push(backend);

    runPnpmChecked(
      'Install generated Vben workspace while the backend starts',
      ['web:install'],
      process.env,
    );
    runPnpmChecked(
      'Install generated UniApp workspace while the backend starts',
      ['mobile:install'],
      process.env,
    );

    await waitForMarker(
      backend,
      'BACKEND_LOCAL_START_VERIFIED',
      remainingMilliseconds(deadline, 'backend readiness'),
    );
    await waitForMarker(
      backend,
      'PURCHASE_PAYMENT_DEMO_SEED_APPLIED',
      remainingMilliseconds(deadline, 'deterministic Seed readiness'),
    );
    const health = await healthEvidence(contract.healthUrl, deadline);
    writeJson(resolve(runDirectory, 'backend-health.json'), health);

    const pc = startManagedNode(
      'Start governed PC demo role',
      [
        'scripts/product-readiness/demo-client.mjs',
        'pc',
        '--actor',
        contract.clients.pc.actorId,
        '--port',
        String(contract.clients.pc.port),
        '--skip-install',
      ],
      resolve(runDirectory, 'pc.log'),
      process.env,
    );
    managed.push(pc);
    const h5 = startManagedNode(
      'Start governed H5 demo role',
      [
        'scripts/product-readiness/demo-client.mjs',
        'h5',
        '--actor',
        contract.clients.h5.actorId,
        '--port',
        String(contract.clients.h5.port),
        '--skip-install',
      ],
      resolve(runDirectory, 'h5.log'),
      process.env,
    );
    managed.push(h5);

    const pcUrl = clientUrl('pc', contract.clients.pc);
    const h5Url = clientUrl('h5', contract.clients.h5);
    await Promise.all([
      waitForHttp(
        `http://127.0.0.1:${contract.clients.pc.port}/`,
        remainingMilliseconds(deadline, 'PC readiness'),
      ),
      waitForHttp(
        `http://127.0.0.1:${contract.clients.h5.port}/`,
        remainingMilliseconds(deadline, 'H5 readiness'),
      ),
    ]);

    runPnpmChecked(
      'Verify the seeded request is visible in real PC and H5 pages',
      [
        '--dir',
        '.upstream/vben',
        '--filter',
        '@vben/playground',
        'exec',
        'playwright',
        'test',
        '--config=product-readiness.playwright.config.ts',
        '__tests__/e2e/product-readiness-quick-start-ready.spec.ts',
      ],
      {
        ...process.env,
        APPROVAL_DEMO_BACKEND_ORIGIN: 'http://127.0.0.1:8080',
        APPROVAL_DEMO_CHROME_PATH: chromeExecutable(),
        APPROVAL_DEMO_EVIDENCE_DIR: runDirectory,
        APPROVAL_DEMO_EXACT_HEAD_SHA: identity.commitSha,
        APPROVAL_DEMO_H5_URL: h5Url,
        APPROVAL_DEMO_PC_URL: pcUrl,
        APPROVAL_DEMO_PLAYWRIGHT_TIMEOUT_MS: String(
          remainingMilliseconds(deadline, 'browser readiness'),
        ),
        APPROVAL_DEMO_QUICK_START_BUSINESS_KEY:
          contract.scenario.request.businessKey,
        APPROVAL_DEMO_QUICK_START_H5_ACTOR: contract.clients.h5.actorId,
        APPROVAL_DEMO_QUICK_START_PC_ACTOR: contract.clients.pc.actorId,
        APPROVAL_DEMO_QUICK_START_TENANT: contract.scenario.tenant.id,
        APPROVAL_DEMO_REPOSITORY_ROOT: repositoryRoot,
      },
    );

    const browserEvidencePath = resolve(runDirectory, browserEvidenceFile);
    if (!existsSync(browserEvidencePath)) {
      throw new Error('Quick Start browser evidence was not created');
    }
    const browser = validateBrowserEvidence(
      JSON.parse(readFileSync(browserEvidencePath, 'utf8')),
      contract,
      identity,
    );
    const readyAt = new Date();
    const elapsedSeconds = Number(
      ((readyAt.getTime() - startedAt.getTime()) / 1_000).toFixed(3),
    );
    if (elapsedSeconds > contract.maximumReadySeconds) {
      throw new Error(
        `Quick Start required ${elapsedSeconds}s; maximum is ${contract.maximumReadySeconds}s`,
      );
    }
    startup = {
      schemaVersion: 1,
      evidenceKind: 'QUICK_START_STARTUP_V1',
      runId,
      commitSha: identity.commitSha,
      treeSha: identity.treeSha,
      startedAt: startedAt.toISOString(),
      readyAt: readyAt.toISOString(),
      elapsedSeconds,
      maximumReadySeconds: contract.maximumReadySeconds,
      tenantId: contract.scenario.tenant.id,
      businessKey: contract.scenario.request.businessKey,
      backendHealth: health,
      pc: browser.pc,
      h5: browser.h5,
    };
    writeJson(resolve(runDirectory, 'startup-summary.json'), startup);

    console.log('\nApproval Platform Quick Start is ready.');
    console.log(`QUICK_START_RUN_ID=${runId}`);
    console.log(`QUICK_START_READY_SECONDS=${elapsedSeconds}`);
    console.log(`QUICK_START_PC_URL=${pcUrl}`);
    console.log(`QUICK_START_H5_URL=${h5Url}`);
    console.log(`QUICK_START_TENANT=${contract.scenario.tenant.id}`);
    console.log(`QUICK_START_BUSINESS_KEY=${contract.scenario.request.businessKey}`);
    console.log(`QUICK_START_PC_ACTOR=${contract.clients.pc.actorId}`);
    console.log(`QUICK_START_H5_ACTOR=${contract.clients.h5.actorId}`);
    console.log(`QUICK_START_EVIDENCE=${runDirectory}`);

    if (keepAlive) {
      console.log('Press Ctrl-C to stop and clean the local Quick Start runtime.');
      await waitForInteractiveStop(managed);
    }
  } catch (error) {
    executionError = error;
  } finally {
    try {
      cleanupEvidence = await cleanup(managed, environment, runDirectory);
    } catch (error) {
      cleanupError = error;
    }
  }

  if (executionError || cleanupError) {
    resetLedger(identity, runId);
    const failure = {
      schemaVersion: 1,
      evidenceKind: 'QUICK_START_FAILURE_V1',
      runId,
      failedAt: new Date().toISOString(),
      execution: executionError instanceof Error
        ? executionError.message
        : executionError ? String(executionError) : null,
      cleanup: cleanupError instanceof Error
        ? cleanupError.message
        : cleanupError ? String(cleanupError) : null,
    };
    writeJson(resolve(runDirectory, 'runtime-failure.json'), failure);
    appendCiEvidenceEnvelope('FAILED', runDirectory, identity);
    throw new Error(`Quick Start failed: ${JSON.stringify(failure)}`, {
      cause: executionError || cleanupError,
    });
  }

  const ledger = nextSuccessfulLedger(identity, runId);
  const claimsDeclared = ledger.successfulRunIds.length >= 2;
  const summary = {
    schemaVersion: 1,
    evidenceKind: 'QUICK_START_10_MINUTE_RUNTIME_V1',
    runId,
    commitSha: identity.commitSha,
    treeSha: identity.treeSha,
    startedAt: startup.startedAt,
    readyAt: startup.readyAt,
    elapsedSeconds: startup.elapsedSeconds,
    maximumReadySeconds: startup.maximumReadySeconds,
    tenantId: startup.tenantId,
    businessKey: startup.businessKey,
    pc: startup.pc,
    h5: startup.h5,
    cleanup: cleanupEvidence,
    successfulRunIds: ledger.successfulRunIds,
    claimsDeclared,
    claims: claimsDeclared ? contract.claims : [],
    nonClaims: contract.nonClaims,
    completedAt: new Date().toISOString(),
  };
  try {
    writeJson(resolve(runDirectory, 'runtime-summary.json'), summary);
    writeJson(ledgerPath, ledger);
    appendCiEvidenceEnvelope('PASSED', runDirectory, identity);
  } catch (error) {
    resetLedger(identity, runId);
    throw error;
  }

  if (!claimsDeclared) {
    console.log('QUICK_START_FIRST_CLEAN_RUN_RECORDED');
    console.log('TWO_CONSECUTIVE_CLEAN_QUICK_START_RUNS_REQUIRED');
  } else {
    for (const claim of contract.claims) console.log(claim);
  }
  for (const nonClaim of contract.nonClaims) console.log(nonClaim);
  return summary;
}
