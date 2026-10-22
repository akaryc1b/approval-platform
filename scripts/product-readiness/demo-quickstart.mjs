#!/usr/bin/env node

import { publicFailureDetail } from './artifact-privacy/publication.mjs';

import { shouldRunInCi } from './pc-h5-runtime/ci-scope.mjs';
import {
  loadContract,
  parseArguments,
  printPlan,
  sourceIdentity,
  UsageError,
  usage,
} from './quick-start/contract.mjs';
import { ensureCjkFontRuntime } from './quick-start/cjk-fonts.mjs';
import { resetLedger } from './quick-start/evidence.mjs';
import { execute } from './quick-start/runtime.mjs';

const commandTimeoutVariable = 'APPROVAL_DEMO_COMMAND_TIMEOUT_MS';

// Public diagnostics contain only the installer's typed phase receipt, never its
// command output or Error. Five existing phases produce at most ten records.
let fontPhaseRecords = 0;
function observeFontPhase(value) {
  if (fontPhaseRecords >= 10 || !value || typeof value !== 'object' || Array.isArray(value)
    || Object.keys(value).sort().join(',') !== 'elapsedMs,error,exitCode,phase,signal,state'
    || !['probe-before','package-index','package-install','font-cache','probe-after'].includes(value.phase)
    || !['start','complete'].includes(value.state)
    || !Number.isInteger(value.elapsedMs) || value.elapsedMs < 0 || value.elapsedMs > 570000
    || !(value.exitCode === null || Number.isInteger(value.exitCode) && value.exitCode >= 0 && value.exitCode <= 255)
    || ![null,'SIGTERM','SIGKILL','SIGABRT','SIGSEGV','SIGBUS','SIGINT','other'].includes(value.signal)
    || ![null,'ETIMEDOUT','ENOENT','EACCES','EPERM','ENOBUFS','EIO','EAGAIN','other'].includes(value.error)) return;
  // Reconstruct the receipt so no extra serialization behavior can expose data.
  const receipt = { phase: value.phase, state: value.state, elapsedMs: value.elapsedMs,
    exitCode: value.exitCode, signal: value.signal, error: value.error };
  const line = 'QUICK_START_FONT_PHASE=' + JSON.stringify(receipt);
  if (line.length > 256) return;
  fontPhaseRecords += 1;
  console.log(line);
}

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
      const detail = publicFailureDetail(resetError);
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
    ensureCjkFontRuntime({ observe: observeFontPhase });
    await executeWithLedgerReset({ keepAlive: false });
    console.log('QUICK_START_SECOND_CLEAN_RUN_STARTING');
    await executeWithLedgerReset({ keepAlive: false });
    return;
  }
  ensureCjkFontRuntime({ observe: observeFontPhase });
  await executeWithLedgerReset({ keepAlive: true });
}

main().catch((error) => {
  const detail = publicFailureDetail(error);
  console.error(`DEMO_QUICK_START_FAILED: ${detail}`);
  if (error instanceof UsageError) console.error(usage());
  process.exitCode = error instanceof UsageError ? 2 : 1;
});
