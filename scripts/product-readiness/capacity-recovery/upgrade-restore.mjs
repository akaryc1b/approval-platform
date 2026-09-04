import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import {
  appendFileSync,
  existsSync,
  lstatSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  realpathSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { relative, resolve, sep } from 'node:path';

import {
  runNodeChecked,
  startManagedNode,
  terminateManaged,
  waitForMarker,
} from '../pc-h5-runtime/processes.mjs';
import {
  executable,
  requireSuccess,
  runCaptured,
} from '../purchase-payment-e2e/contract.mjs';
import {
  readSandboxStatus,
  waitForPortAvailable,
  waitForState,
} from '../purchase-payment-e2e/evidence.mjs';
import { seededAttachmentIds } from './backlog-drain-evidence.mjs';
import {
  composeFile,
  composeProject,
  outputRoot,
  runIdentifier,
  sourceIdentity,
  writeJson,
} from './contract.mjs';
import {
  createInFlightPurchase,
  createRecorder,
  continueRestoredPurchase,
  readConsistency,
  requireExactRestoredConsistency,
} from './upgrade-restore-http.mjs';
import {
  backendTimeoutMs,
  baseEnvironment,
  candidateEnvironment,
  claim,
  exactUpgradeRefs,
  maximumRuntimeMs,
  nonClaims,
  rehearsalPrefixes,
  repositoryRoot,
  snapshot,
  stateTimeoutMs,
  upgradeRestorePlan,
} from './upgrade-restore-contract.mjs';

const worktreeRoot = resolve(
  repositoryRoot,
  '.runtime/capacity-recovery-worktrees',
);
const maximumDumpBytes = 128 * 1024 * 1024;
const retainedExtensions = new Set(['.json', '.md']);
const maximumEvidenceFileBytes = 24 * 1024 * 1024;
const maximumEvidenceTotalBytes = 64 * 1024 * 1024;
const envelopeBegin = 'CAPACITY_UPGRADE_RESTORE_CI_ARTIFACT_ENVELOPE_BEGIN';
const envelopeEnd = 'CAPACITY_UPGRADE_RESTORE_CI_ARTIFACT_ENVELOPE_END';

export { upgradeRestorePlan };

function command(name) {
  return process.platform === 'win32' ? `${name}.exe` : name;
}

function run(args, options = {}) {
  const result = spawnSync(args[0], args.slice(1), {
    cwd: options.cwd || repositoryRoot,
    encoding: options.encoding === undefined ? 'utf8' : options.encoding,
    env: options.environment || process.env,
    input: options.input,
    maxBuffer: options.maxBuffer || 256 * 1024 * 1024,
    shell: false,
    timeout: options.timeoutMs,
  });
  if (result.error || result.status !== 0) {
    throw new Error(
      `${options.label || args.join(' ')} failed: `
        + `${result.error?.message || result.stderr || result.stdout || result.status}`,
    );
  }
  return result.stdout;
}

function runGit(args, options = {}) {
  return String(run([command('git'), ...args], {
    ...options,
    label: options.label || `git ${args.join(' ')}`,
  })).trim();
}

function treeSha(ref) {
  const value = runGit(['rev-parse', `${ref}^{tree}`]);
  if (!/^[0-9a-f]{40}$/u.test(value)) {
    throw new Error(`invalid tree SHA for ${ref}: ${value}`);
  }
  return value;
}

function composeArguments(...args) {
  return [
    command('docker'),
    'compose',
    '--project-name',
    composeProject,
    '-f',
    composeFile,
    ...args,
  ];
}

function remainingMilliseconds(deadline, label) {
  const remaining = deadline - Date.now();
  if (remaining <= 0) {
    throw new Error(`${label} exceeded the upgrade/restore deadline`);
  }
  return remaining;
}

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
  if (!processState) return;
  terminateManaged(processState);
  let deadline = Date.now() + 10_000;
  while (!processExited(processState.child) && Date.now() < deadline) {
    await new Promise(resolvePromise => setTimeout(resolvePromise, 250));
  }
  if (processExited(processState.child)) return;
  terminateProcessGroup(processState.child, 'SIGKILL');
  deadline = Date.now() + 5_000;
  while (!processExited(processState.child) && Date.now() < deadline) {
    await new Promise(resolvePromise => setTimeout(resolvePromise, 250));
  }
  if (!processExited(processState.child)) {
    throw new Error(`${processState.label} did not terminate after SIGKILL`);
  }
}

function resetDisposableData(environment, timeoutMs, cwd = repositoryRoot) {
  runNodeChecked(
    'Delete only the disposable local demo volume for upgrade/restore',
    [
      'scripts/product-readiness/demo-backend.mjs',
      'reset',
      '--confirm-local-data-loss',
    ],
    environment,
    timeoutMs,
    cwd,
  );
}

function prepareWorktree(refs, runId, deadline) {
  mkdirSync(worktreeRoot, { recursive: true, mode: 0o700 });
  runGit([
    'fetch',
    '--no-tags',
    '--depth=1',
    'origin',
    refs.baseSha,
    refs.candidateSha,
  ], {
    timeoutMs: remainingMilliseconds(deadline, 'fetch exact upgrade refs'),
  });
  const baseTreeSha = treeSha(refs.baseSha);
  const candidateTreeSha = treeSha(refs.candidateSha);
  const worktree = resolve(worktreeRoot, `${runId}-base`);
  rmSync(worktree, { force: true, recursive: true });
  runGit(['worktree', 'add', '--detach', worktree, refs.baseSha], {
    timeoutMs: remainingMilliseconds(deadline, 'create exact-main worktree'),
  });
  return {
    path: worktree,
    baseTreeSha,
    candidateTreeSha,
  };
}

function removeWorktree(worktree) {
  if (!worktree) return;
  try {
    runGit(['worktree', 'remove', '--force', worktree]);
  } catch (error) {
    rmSync(worktree, { force: true, recursive: true });
    runGit(['worktree', 'prune']);
    throw error;
  }
}

function createBackup(dumpPath, deadline) {
  const startedAt = new Date();
  const dump = run(composeArguments(
    'exec',
    '-T',
    'postgres',
    'pg_dump',
    '-U',
    'approval',
    '-d',
    'approval',
    '--format=custom',
    '--no-owner',
    '--no-privileges',
  ), {
    encoding: null,
    label: 'capture PostgreSQL 16 custom-format backup from stdout',
    timeoutMs: remainingMilliseconds(deadline, 'capture PostgreSQL backup'),
  });
  const customFormatHeader = Buffer.from('PGDMP', 'ascii');
  if (!Buffer.isBuffer(dump)
      || dump.length === 0
      || dump.length > maximumDumpBytes
      || !dump.subarray(0, customFormatHeader.length).equals(customFormatHeader)) {
    throw new Error(`PostgreSQL custom-format dump is invalid: ${dump?.length}`);
  }
  run(composeArguments(
    'exec',
    '-T',
    'postgres',
    'pg_restore',
    '--list',
  ), {
    input: dump,
    encoding: null,
    label: 'validate PostgreSQL custom-format backup before volume replacement',
    timeoutMs: remainingMilliseconds(deadline, 'validate PostgreSQL backup'),
  });
  writeFileSync(dumpPath, dump, { mode: 0o600 });
  const completedAt = new Date();
  return {
    startedAt: startedAt.toISOString(),
    completedAt: completedAt.toISOString(),
    elapsedMs: completedAt.getTime() - startedAt.getTime(),
    sizeBytes: dump.length,
    sha256: createHash('sha256').update(dump).digest('hex'),
    format: 'POSTGRESQL_CUSTOM',
    archiveHeader: 'PGDMP',
    archiveValidatedBy: 'pg_restore --list',
    ownerRestored: false,
    privilegesRestored: false,
  };
}

async function waitForPostgres(deadline) {
  while (Date.now() < deadline) {
    const result = spawnSync(
      composeArguments(
        'exec',
        '-T',
        'postgres',
        'pg_isready',
        '-U',
        'approval',
        '-d',
        'approval',
      )[0],
      composeArguments(
        'exec',
        '-T',
        'postgres',
        'pg_isready',
        '-U',
        'approval',
        '-d',
        'approval',
      ).slice(1),
      {
        cwd: repositoryRoot,
        encoding: 'utf8',
        env: process.env,
        shell: false,
        timeout: Math.min(10_000, remainingMilliseconds(deadline, 'PostgreSQL readiness')),
      },
    );
    if (!result.error && result.status === 0) return;
    await new Promise(resolvePromise => setTimeout(resolvePromise, 500));
  }
  throw new Error('fresh PostgreSQL 16 did not become ready');
}

async function recreateInfrastructure(dumpPath, deadline) {
  run(composeArguments('down', '--volumes', '--remove-orphans'), {
    label: 'destroy pre-restore disposable PostgreSQL volume',
    timeoutMs: remainingMilliseconds(deadline, 'destroy pre-restore volume'),
  });
  run(composeArguments('up', '-d', 'postgres', 'redis'), {
    label: 'create fresh PostgreSQL 16 and Redis infrastructure',
    timeoutMs: remainingMilliseconds(deadline, 'create fresh infrastructure'),
  });
  await waitForPostgres(deadline);
  const restoreStartedAt = new Date();
  const dump = readFileSync(dumpPath);
  run(composeArguments(
    'exec',
    '-T',
    'postgres',
    'pg_restore',
    '-U',
    'approval',
    '-d',
    'approval',
    '--exit-on-error',
    '--no-owner',
    '--no-privileges',
  ), {
    input: dump,
    encoding: null,
    label: 'pg_restore PostgreSQL 16 approval database',
    timeoutMs: remainingMilliseconds(deadline, 'PostgreSQL pg_restore'),
  });
  const restoreCompletedAt = new Date();
  return {
    startedAt: restoreStartedAt.toISOString(),
    completedAt: restoreCompletedAt.toISOString(),
    elapsedMs: restoreCompletedAt.getTime() - restoreStartedAt.getTime(),
  };
}

function queryOutbox(instanceId) {
  if (!/^[0-9a-f-]{36}$/iu.test(instanceId)) {
    throw new Error('upgrade/restore Outbox query requires a UUID instance');
  }
  const sql = [
    'select',
    "  id::text, event_id::text, status, attempts::text,",
    "  coalesce(last_error, ''), coalesce(response_code::text, ''),",
    "  coalesce(provider_request_id, ''), coalesce(delivered_at::text, '')",
    'from ap_outbox',
    `where aggregate_id = '${instanceId}'`,
    "  and event_type = 'APPROVAL_INSTANCE_COMPLETED'",
    'order by id;',
  ].join('\n');
  const output = requireSuccess(
    'Read upgrade/restore completion Outbox row',
    runCaptured(
      executable('docker'),
      composeArguments(
        'exec',
        '-T',
        'postgres',
        'psql',
        '-U',
        'approval',
        '-d',
        'approval',
        '-At',
        '-F',
        '|',
        '-c',
        sql,
      ).slice(1),
    ),
  );
  if (!output) return [];
  return output.split(/\r?\n/u).filter(Boolean).map((line) => {
    const values = line.split('|');
    if (values.length !== 8) {
      throw new Error(`unexpected upgrade/restore Outbox row: ${line}`);
    }
    return {
      id: values[0],
      eventId: values[1],
      status: values[2],
      attempts: Number.parseInt(values[3], 10),
      lastError: values[4] || null,
      responseCode: values[5] ? Number.parseInt(values[5], 10) : null,
      providerRequestId: values[6] || null,
      deliveredAt: values[7] || null,
    };
  });
}

function validPending(value) {
  return value.rows?.length === 1
    && value.rows[0].status === 'PENDING'
    && value.rows[0].attempts >= 1
    && value.rows[0].responseCode === null
    && value.rows[0].providerRequestId === null
    && value.rows[0].deliveredAt === null
    && value.rows[0].lastError?.startsWith(
      'HTTP 503: payment sandbox unavailable',
    )
    && value.sandbox?.available === false
    && value.sandbox.deliveryAttempts >= 1
    && value.sandbox.acceptedPaymentResults === 0
    && value.sandbox.lastHttpStatus === 503
    && value.sandbox.failure === null;
}

function validDelivered(value) {
  return value.rows?.length === 1
    && value.rows[0].status === 'DELIVERED'
    && value.rows[0].attempts >= 1
    && value.rows[0].responseCode === 200
    && value.rows[0].providerRequestId
      === `local-payment-sandbox-${value.rows[0].eventId}`
    && value.rows[0].deliveredAt !== null
    && value.rows[0].lastError === null
    && value.sandbox?.available === true
    && value.sandbox.acceptedPaymentResults === 1
    && value.sandbox.lastHttpStatus === 200
    && value.sandbox.failure === null;
}

function collectEvidence(directory, files = []) {
  for (const name of readdirSync(directory).sort()) {
    const target = resolve(directory, name);
    const metadata = lstatSync(target);
    if (metadata.isSymbolicLink()) {
      throw new Error(`upgrade/restore evidence rejects symbolic link: ${target}`);
    }
    if (metadata.isDirectory()) {
      collectEvidence(target, files);
      continue;
    }
    if (!metadata.isFile()) continue;
    const extension = name.slice(name.lastIndexOf('.')).toLowerCase();
    if (retainedExtensions.has(extension)) files.push(target);
  }
  return files;
}

function appendEvidenceEnvelope(status, runDirectory, identity) {
  if (process.env.GITHUB_ACTIONS !== 'true') return;
  const artifactLog = resolve(repositoryRoot, 'root-install.log');
  if (!existsSync(artifactLog)) {
    throw new Error('root-install.log is unavailable for upgrade/restore evidence');
  }
  const canonicalRunDirectory = realpathSync(runDirectory);
  let totalBytes = 0;
  const files = collectEvidence(canonicalRunDirectory).map((target) => {
    const content = readFileSync(target);
    if (content.length > maximumEvidenceFileBytes) {
      throw new Error(`upgrade/restore evidence file is too large: ${target}`);
    }
    totalBytes += content.length;
    if (totalBytes > maximumEvidenceTotalBytes) {
      throw new Error('upgrade/restore evidence exceeds bounded total size');
    }
    const path = relative(canonicalRunDirectory, target).split(sep).join('/');
    if (!path || path.startsWith('../') || path.includes('/../')) {
      throw new Error(`upgrade/restore evidence escaped its run directory: ${target}`);
    }
    return {
      path,
      size: content.length,
      sha256: createHash('sha256').update(content).digest('hex'),
      base64: content.toString('base64'),
    };
  });
  if (status === 'PASSED') {
    for (const required of [
      'source-identity.json',
      'upgrade-restore-contract.json',
      'pre-backup-consistency.json',
      'backup-manifest.json',
      'post-restore-consistency.json',
      'continuation-evidence.json',
      'outbox-pending-evidence.json',
      'outbox-delivered-evidence.json',
      'upgrade-restore-cleanup.json',
      'upgrade-restore-summary.json',
    ]) {
      if (!files.some(file => file.path === required)) {
        throw new Error(`passed upgrade/restore did not retain ${required}`);
      }
    }
  }
  appendFileSync(
    artifactLog,
    `\n${envelopeBegin}\n${JSON.stringify({
      schemaVersion: 1,
      evidenceKind: 'CAPACITY_UPGRADE_RESTORE_CI_ARTIFACT_ENVELOPE_V1',
      status,
      commitSha: identity.commitSha,
      treeSha: identity.treeSha,
      githubRunId: process.env.GITHUB_RUN_ID || null,
      capturedAt: new Date().toISOString(),
      totalBytes,
      files,
    })}\n${envelopeEnd}\n`,
    'utf8',
  );
}

async function cleanup({
  baseBackend,
  candidateBackend,
  environment,
  runDirectory,
  dumpPath,
  worktree,
  mutated,
}) {
  const actions = [];
  if (baseBackend) {
    await stopManaged(baseBackend);
    actions.push('stopped:exact-main-baseline-backend');
  }
  if (candidateBackend) {
    await stopManaged(candidateBackend);
    actions.push('stopped:candidate-head-backend');
  }
  if (mutated) {
    resetDisposableData(environment, 15 * 60_000);
    actions.push('deleted:approval-platform-demo-volume');
    for (const port of [5432, 6379, 8080]) {
      await waitForPortAvailable(port);
      actions.push(`released-port:${port}`);
    }
  } else {
    actions.push('skipped-reset:failure-before-runtime-mutation');
  }
  if (existsSync(dumpPath)) {
    rmSync(dumpPath, { force: true });
    actions.push('deleted:temporary-postgresql-backup');
  }
  if (worktree) {
    removeWorktree(worktree);
    actions.push('removed:exact-main-baseline-worktree');
  }
  const evidence = snapshot('CAPACITY_UPGRADE_RESTORE_CLEANUP_V1', {
    actions,
    status: 'PASSED',
  });
  writeJson(resolve(runDirectory, 'upgrade-restore-cleanup.json'), evidence);
  return evidence;
}

export async function executeUpgradeRestoreRehearsal(contract) {
  const identity = sourceIdentity();
  const runId = `${runIdentifier()}-upgrade-restore`;
  const runDirectory = resolve(outputRoot, runId);
  mkdirSync(runDirectory, { recursive: true, mode: 0o700 });
  const dumpPath = resolve(runDirectory, 'temporary-postgresql-backup.dump');
  const deadline = Date.now() + maximumRuntimeMs;
  const refs = exactUpgradeRefs(args => runGit(args, {
    timeoutMs: remainingMilliseconds(deadline, 'resolve exact upgrade refs'),
  }));
  const prefixes = rehearsalPrefixes(contract);
  const environment = candidateEnvironment(runDirectory, contract, prefixes);

  let baseBackend;
  let candidateBackend;
  let worktree;
  let mutated = false;
  let executionError;
  let cleanupError;
  let cleanupEvidence;
  let summary;

  try {
    const prepared = prepareWorktree(refs, runId, deadline);
    worktree = prepared.path;
    if (identity.commitSha !== refs.candidateSha
        || identity.treeSha !== prepared.candidateTreeSha) {
      throw new Error('candidate source identity differs from exact PR Head');
    }
    writeJson(resolve(runDirectory, 'source-identity.json'), snapshot(
      'CAPACITY_UPGRADE_RESTORE_SOURCE_IDENTITY_V1',
      {
        runId,
        refSource: refs.source,
        baseSha: refs.baseSha,
        baseTreeSha: prepared.baseTreeSha,
        candidateSha: refs.candidateSha,
        candidateTreeSha: prepared.candidateTreeSha,
      },
    ));
    writeJson(resolve(runDirectory, 'upgrade-restore-contract.json'), snapshot(
      'CAPACITY_UPGRADE_RESTORE_CONTRACT_V1',
      {
        claim,
        nonClaims,
        prefixes,
        plan: upgradeRestorePlan(),
      },
    ));

    resetDisposableData(
      baseEnvironment(),
      remainingMilliseconds(deadline, 'initial upgrade/restore reset'),
      worktree,
    );
    mutated = true;
    baseBackend = startManagedNode(
      'Start exact-main baseline backend for in-flight backup',
      ['scripts/product-readiness/demo-backend.mjs', 'start'],
      resolve(runDirectory, 'base-backend.log'),
      baseEnvironment(),
      worktree,
    );
    await waitForMarker(
      baseBackend,
      'BACKEND_LOCAL_START_VERIFIED',
      Math.min(
        backendTimeoutMs,
        remainingMilliseconds(deadline, 'exact-main backend readiness'),
      ),
    );
    await waitForMarker(
      baseBackend,
      'PURCHASE_PAYMENT_DEMO_SEED_APPLIED',
      Math.min(
        backendTimeoutMs,
        remainingMilliseconds(deadline, 'exact-main Seed readiness'),
      ),
    );

    const token = runId.replace(/[^0-9A-Za-z]/gu, '').slice(-12);
    const baseRecorder = createRecorder(contract, `base-${token}`);
    const inFlight = await createInFlightPurchase(
      baseRecorder,
      contract,
      prefixes,
      seededAttachmentIds(contract),
      token,
    );
    const before = await readConsistency(
      baseRecorder,
      contract,
      inFlight.instanceId,
    );
    writeJson(resolve(runDirectory, 'pre-backup-consistency.json'), snapshot(
      'CAPACITY_PRE_BACKUP_CONSISTENCY_V1',
      {
        inFlight,
        summary: before,
        commandAttempts: baseRecorder.attempts,
      },
    ));

    await stopManaged(baseBackend);
    baseBackend = undefined;
    const outageStartedAt = Date.now();
    const backup = createBackup(dumpPath, deadline);
    const restore = await recreateInfrastructure(dumpPath, deadline);
    writeJson(resolve(runDirectory, 'backup-manifest.json'), snapshot(
      'CAPACITY_POSTGRES_BACKUP_RESTORE_MANIFEST_V1',
      {
        databaseVendor: 'PostgreSQL 16',
        quiescedApplication: true,
        backup,
        restore,
      },
    ));

    candidateBackend = startManagedNode(
      'Start exact candidate Head on restored PostgreSQL data',
      ['scripts/product-readiness/demo-backend.mjs', 'start'],
      resolve(runDirectory, 'candidate-backend.log'),
      environment,
    );
    await waitForMarker(
      candidateBackend,
      'BACKEND_LOCAL_START_VERIFIED',
      Math.min(
        backendTimeoutMs,
        remainingMilliseconds(deadline, 'candidate backend readiness'),
      ),
    );
    const candidateRecorder = createRecorder(contract, `candidate-${token}`);
    const after = await readConsistency(
      candidateRecorder,
      contract,
      inFlight.instanceId,
    );
    const firstBusinessReadAt = Date.now();
    requireExactRestoredConsistency(before, after);
    writeJson(resolve(runDirectory, 'post-restore-consistency.json'), snapshot(
      'CAPACITY_POST_RESTORE_CONSISTENCY_V1',
      {
        summary: after,
        exactMatch: true,
        lostCommittedBusinessRecords: 0,
      },
    ));

    const continuation = await continueRestoredPurchase(
      candidateRecorder,
      contract,
      after,
      token,
    );
    writeJson(resolve(runDirectory, 'continuation-evidence.json'), snapshot(
      'CAPACITY_RESTORED_APPROVAL_CONTINUATION_V1',
      {
        continuation,
        commandAttempts: candidateRecorder.attempts,
      },
    ));

    const sandboxStatusPath = environment.APPROVAL_DEMO_PAYMENT_SANDBOX_STATUS_FILE;
    const pending = await waitForState(
      'Restored approval completion Outbox under HTTP 503',
      () => ({
        rows: queryOutbox(inFlight.instanceId),
        sandbox: readSandboxStatus(sandboxStatusPath),
      }),
      validPending,
      Math.min(
        stateTimeoutMs,
        remainingMilliseconds(deadline, 'restored Outbox pending evidence'),
      ),
    );
    writeJson(resolve(runDirectory, 'outbox-pending-evidence.json'), snapshot(
      'CAPACITY_UPGRADE_RESTORE_OUTBOX_PENDING_V1',
      pending,
    ));
    const connectorRecoveryStartedAt = Date.now();
    writeFileSync(
      environment.APPROVAL_DEMO_PAYMENT_SANDBOX_CONTROL_FILE,
      'recover\n',
      { encoding: 'utf8', mode: 0o600 },
    );
    const delivered = await waitForState(
      'Restored approval completion Outbox delivered',
      () => ({
        rows: queryOutbox(inFlight.instanceId),
        sandbox: readSandboxStatus(sandboxStatusPath),
      }),
      validDelivered,
      Math.min(
        stateTimeoutMs,
        remainingMilliseconds(deadline, 'restored Outbox delivery evidence'),
      ),
    );
    const connectorRecoveryCompletedAt = Date.now();
    writeJson(resolve(runDirectory, 'outbox-delivered-evidence.json'), snapshot(
      'CAPACITY_UPGRADE_RESTORE_OUTBOX_DELIVERED_V1',
      delivered,
    ));

    summary = snapshot('CAPACITY_UPGRADE_RESTORE_SUMMARY_V1', {
      status: 'PASSED_LOCAL_QUIESCED_REHEARSAL_ONLY',
      claim,
      baseSha: refs.baseSha,
      baseTreeSha: prepared.baseTreeSha,
      candidateSha: refs.candidateSha,
      candidateTreeSha: prepared.candidateTreeSha,
      instanceId: inFlight.instanceId,
      businessKey: inFlight.businessKey,
      restoredConsistencyExactMatch: true,
      continuation,
      backup,
      restore,
      localRpo: {
        unit: 'COMMITTED_BUSINESS_RECORDS',
        value: 0,
        rpoSeconds: 0,
        boundary: 'QUIESCED_NO_WRITES_AFTER_APPLICATION_STOP',
      },
      localRto: {
        outageToFirstSuccessfulBusinessReadMs:
          firstBusinessReadAt - outageStartedAt,
        boundary:
          'LOCAL_SINGLE_NODE_APPLICATION_STOP_TO_RESTORED_BUSINESS_READ',
      },
      connectorRecovery: {
        pendingToDeliveredMs:
          connectorRecoveryCompletedAt - connectorRecoveryStartedAt,
        acceptedPaymentResults: delivered.sandbox.acceptedPaymentResults,
        duplicateAcceptedPaymentResults: 0,
      },
      interpretation:
        'LOCAL_QUIESCED_POSTGRESQL_16_REHEARSAL_NOT_PRODUCTION_RPO_RTO',
      nonClaims,
    });
  } catch (error) {
    executionError = error;
  } finally {
    try {
      cleanupEvidence = await cleanup({
        baseBackend,
        candidateBackend,
        environment,
        runDirectory,
        dumpPath,
        worktree,
        mutated,
      });
    } catch (error) {
      cleanupError = error;
    }
  }

  if (executionError || cleanupError) {
    const failure = snapshot('CAPACITY_UPGRADE_RESTORE_FAILURE_V1', {
      status: 'FAILED',
      execution: executionError instanceof Error
        ? executionError.message
        : executionError ? String(executionError) : null,
      cleanup: cleanupError instanceof Error
        ? cleanupError.message
        : cleanupError ? String(cleanupError) : null,
    });
    writeJson(resolve(runDirectory, 'upgrade-restore-failure.json'), failure);
    appendEvidenceEnvelope('FAILED', runDirectory, identity);
    throw new Error(
      `capacity upgrade/restore rehearsal failed: ${JSON.stringify(failure)}`,
      { cause: executionError || cleanupError },
    );
  }

  summary.cleanup = cleanupEvidence;
  writeJson(resolve(runDirectory, 'upgrade-restore-summary.json'), summary);
  appendEvidenceEnvelope('PASSED', runDirectory, identity);
  console.log(`CAPACITY_UPGRADE_RESTORE_RUN_ID=${runId}`);
  console.log(`CAPACITY_UPGRADE_RESTORE_EVIDENCE=${runDirectory}`);
  console.log(claim);
  for (const marker of nonClaims) console.log(marker);
  return summary;
}
