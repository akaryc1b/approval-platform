import { existsSync, readFileSync } from 'node:fs';
import { delimiter, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const repositoryRoot = resolve(
  dirname(fileURLToPath(import.meta.url)),
  '../../..',
);
export const outputDirectory = resolve(
  repositoryRoot,
  'build/product-readiness/pc-h5-runtime',
);
export const evidencePath = resolve(
  outputDirectory,
  'pc-h5-runtime-evidence.json',
);

const commands = new Set(['plan', 'run', 'ci']);

export class UsageError extends Error {}

export function usage() {
  return `Usage: node scripts/product-readiness/pc-h5-runtime-smoke.mjs <command> [options]\n\nCommands:\n  plan  Print the bounded PC/H5 runtime smoke plan.\n  run   Execute the smoke explicitly.\n  ci    Execute only for a relevant GitHub Actions change set.\n\nOptions:\n  --json  Machine-readable plan output.\n  --help  Show this help.\n\nThe smoke starts the real local backend, PC client, H5 client and a system\nChromium browser. It completes managerApproval in PC, then financeReview and\nboth authoritative financeCountersign tasks in H5, and proves that the same\nseeded instance remains RUNNING with a distinct paymentConfirmation task for\nthe governed WeChat actor. The Seed, not a client initiation flow, creates the\ninstance. The smoke does not execute WeChat, complete the purchase approval\nE2E, verify a payment provider, establish browser compatibility, or claim\nproduction readiness.`;
}

export function parseArguments(argv) {
  const values = argv.filter(value => value !== '--');
  const command = values.shift() || 'plan';
  if (!commands.has(command)) {
    throw new UsageError(`Unknown command: ${command}`);
  }
  const flags = new Set(values);
  for (const flag of flags) {
    if (flag !== '--json' && flag !== '--help') {
      throw new UsageError(`Unknown option: ${flag}`);
    }
  }
  if (command !== 'plan' && flags.has('--json')) {
    throw new UsageError('--json is only available for plan');
  }
  return {
    command,
    help: flags.has('--help'),
    json: flags.has('--json'),
  };
}

export function smokePlan() {
  return {
    schemaVersion: 1,
    evidenceKind: 'PC_H5_BROWSER_APPROVAL_HANDOFF_V1',
    output: 'build/product-readiness/pc-h5-runtime/pc-h5-runtime-evidence.json',
    stages: [
      'install generated Vben and UniApp workspaces',
      'start PostgreSQL, Redis, Spring Boot, Flowable and deterministic seed',
      'start PC as the governed managerApproval actor',
      'start H5 with URL-scoped governed finance actors',
      'use a real Chromium page to approve managerApproval in PC',
      'use a real Chromium page to approve financeReview in H5',
      'use real Chromium H5 pages to approve two distinct financeCountersign tasks',
      'verify the same seeded instance remains RUNNING at paymentConfirmation',
      'verify the paymentConfirmation task is distinct and assigned to the governed WeChat actor',
      'retain screenshots, request headers, action results, task IDs and audit IDs',
    ],
    claim: 'PC_H5_APPROVAL_HANDOFF_PASSED',
    nonClaims: [
      'PURCHASE_APPROVAL_E2E_NOT_EXECUTED',
      'WECHAT_MINI_PROGRAM_RUNTIME_NOT_EXECUTED',
      'PC_H5_WECHAT_RUNTIME_NOT_EXECUTED',
      'BROWSER_COMPATIBILITY_NOT_VERIFIED',
      'ACCESSIBILITY_NOT_VERIFIED',
      'PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED',
      'PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED',
      'QUICK_START_10_MINUTES_NOT_EXECUTED',
    ],
  };
}

export function printPlan(jsonOutput) {
  const value = smokePlan();
  if (jsonOutput) {
    console.log(JSON.stringify(value, null, 2));
    return;
  }
  console.log('Approval Platform PC/H5 runtime smoke plan');
  console.log(JSON.stringify(value, null, 2));
}

export function java21Environment() {
  const candidates = [
    process.env.JAVA_HOME_21_X64,
    process.env.JAVA_HOME_21_ARM64,
    process.env.JAVA_HOME,
  ].filter(Boolean);
  for (const javaHome of candidates) {
    const executable = resolve(
      javaHome,
      'bin',
      process.platform === 'win32' ? 'java.exe' : 'java',
    );
    if (existsSync(executable)) {
      const javaBin = resolve(javaHome, 'bin');
      return {
        ...process.env,
        JAVA_HOME: javaHome,
        PATH: `${javaBin}${delimiter}${process.env.PATH || ''}`,
      };
    }
  }
  return process.env;
}

export function chromeExecutable() {
  for (const candidate of [
    '/usr/bin/google-chrome',
    '/usr/bin/google-chrome-stable',
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser',
  ]) {
    if (existsSync(candidate)) return candidate;
  }
  throw new Error('no system Chrome or Chromium executable is available');
}

export function readEvidence() {
  if (!existsSync(evidencePath)) {
    throw new Error('PC/H5 runtime evidence file was not created');
  }
  return JSON.parse(readFileSync(evidencePath, 'utf8'));
}
