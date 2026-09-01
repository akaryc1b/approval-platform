import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  appendFileSync,
  existsSync,
  lstatSync,
  mkdirSync,
  readFileSync,
  readdirSync,
} from 'node:fs';
import { arch, cpus, platform, release, totalmem } from 'node:os';
import { relative, resolve, sep } from 'node:path';

import { chromeExecutable } from '../pc-h5-runtime/contract.mjs';
import {
  ledgerPath,
  outputRoot,
  repositoryRoot,
  writeJson,
} from './contract.mjs';

const envelopeBegin = 'APPROVAL_QUICK_START_EVIDENCE_ENVELOPE_BEGIN';
const envelopeEnd = 'APPROVAL_QUICK_START_EVIDENCE_ENVELOPE_END';
const retainedExtensions = new Set(['.json', '.md', '.png', '.zip']);
const maximumFileBytes = 64 * 1024 * 1024;
const maximumTotalBytes = 96 * 1024 * 1024;

function executable(name) {
  if (process.platform === 'win32' && ['mvn', 'pnpm'].includes(name)) {
    return `${name}.cmd`;
  }
  return process.platform === 'win32' && ['docker', 'git', 'java'].includes(name)
    ? `${name}.exe`
    : name;
}

function captureVersion(command, args, environment) {
  const result = spawnSync(command, args, {
    cwd: repositoryRoot,
    encoding: 'utf8',
    env: environment,
    shell: false,
    timeout: 10_000,
  });
  if (result.error || result.status !== 0) {
    throw new Error(
      `${command} version capture failed: ${result.error?.message || result.stderr || result.stdout}`,
    );
  }
  return `${result.stdout || ''}\n${result.stderr || ''}`
    .split(/\r?\n/u)
    .map(line => line.trim())
    .filter(Boolean)
    .slice(0, 4);
}

function commandVersion(command, args, environment) {
  return captureVersion(executable(command), args, environment);
}

export function environmentSnapshot(environment = process.env) {
  const browserExecutable = chromeExecutable();
  return {
    schemaVersion: 1,
    evidenceKind: 'QUICK_START_ENVIRONMENT_V1',
    capturedAt: new Date().toISOString(),
    operatingSystem: {
      platform: platform(),
      release: release(),
      architecture: arch(),
    },
    cpu: {
      logicalCount: cpus().length,
      models: [...new Set(cpus().map(cpu => cpu.model))],
    },
    memoryBytes: totalmem(),
    tools: {
      node: [process.version],
      java: commandVersion('java', ['-version'], environment),
      maven: commandVersion('mvn', ['-version'], environment),
      pnpm: commandVersion('pnpm', ['--version'], environment),
      docker: commandVersion('docker', ['--version'], environment),
      compose: commandVersion('docker', ['compose', 'version'], environment),
      git: commandVersion('git', ['--version'], environment),
      browser: {
        executable: browserExecutable,
        version: captureVersion(browserExecutable, ['--version'], environment),
      },
    },
  };
}

function emptyLedger(identity) {
  return {
    schemaVersion: 1,
    evidenceKind: 'QUICK_START_CONSECUTIVE_CLEAN_RUNS_V1',
    commitSha: identity.commitSha,
    treeSha: identity.treeSha,
    successfulRunIds: [],
  };
}

function readLedger(identity) {
  if (!existsSync(ledgerPath)) return emptyLedger(identity);
  const value = JSON.parse(readFileSync(ledgerPath, 'utf8'));
  if (value.commitSha !== identity.commitSha || value.treeSha !== identity.treeSha) {
    return emptyLedger(identity);
  }
  return value;
}

export function resetLedger(identity, failureRunId) {
  mkdirSync(outputRoot, { recursive: true, mode: 0o700 });
  writeJson(ledgerPath, {
    ...emptyLedger(identity),
    failureRunId,
    resetAt: new Date().toISOString(),
  });
}

export function nextSuccessfulLedger(identity, runId) {
  const ledger = readLedger(identity);
  const successfulRunIds = [...ledger.successfulRunIds, runId]
    .filter((value, index, values) => values.indexOf(value) === index)
    .slice(-2);
  return {
    ...emptyLedger(identity),
    successfulRunIds,
    updatedAt: new Date().toISOString(),
  };
}

function collectEvidence(directory, files = []) {
  for (const name of readdirSync(directory).sort()) {
    const target = resolve(directory, name);
    const metadata = lstatSync(target);
    if (metadata.isSymbolicLink()) {
      throw new Error(`Quick Start evidence must not contain a symbolic link: ${target}`);
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

export function appendCiEvidenceEnvelope(status, runDirectory, identity) {
  if (process.env.GITHUB_ACTIONS !== 'true') return;
  const artifactLog = resolve(repositoryRoot, 'root-install.log');
  if (!existsSync(artifactLog)) {
    throw new Error('root-install.log is unavailable for Quick Start evidence retention');
  }
  let totalBytes = 0;
  const files = collectEvidence(runDirectory).map((target) => {
    const content = readFileSync(target);
    if (content.length > maximumFileBytes) {
      throw new Error(`Quick Start evidence file is too large: ${target}`);
    }
    totalBytes += content.length;
    if (totalBytes > maximumTotalBytes) {
      throw new Error('Quick Start retained evidence exceeds the bounded total size');
    }
    const path = relative(runDirectory, target).split(sep).join('/');
    if (!path || path.startsWith('../') || path.includes('/../')) {
      throw new Error(`Quick Start evidence escaped its run directory: ${target}`);
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
      'environment.json',
      'backend-health.json',
      'quick-start-browser-evidence.json',
      'quick-start-pc.png',
      'quick-start-h5.png',
      'startup-summary.json',
      'cleanup-evidence.json',
      'runtime-summary.json',
    ]) {
      if (!files.some(file => file.path === required)) {
        throw new Error(`passed Quick Start did not retain ${required}`);
      }
    }
    if (!files.some(file => file.path.endsWith('/trace.zip'))) {
      throw new Error('passed Quick Start did not retain its Playwright trace.zip');
    }
  }
  const envelope = {
    schemaVersion: 1,
    evidenceKind: 'QUICK_START_CI_ARTIFACT_ENVELOPE_V1',
    status,
    commitSha: identity.commitSha,
    treeSha: identity.treeSha,
    githubRunId: process.env.GITHUB_RUN_ID || null,
    capturedAt: new Date().toISOString(),
    totalBytes,
    files,
  };
  appendFileSync(
    artifactLog,
    `\n${envelopeBegin}\n${JSON.stringify(envelope)}\n${envelopeEnd}\n`,
    'utf8',
  );
}
