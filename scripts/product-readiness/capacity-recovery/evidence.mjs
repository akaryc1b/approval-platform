import { createHash } from 'node:crypto';
import {
  appendFileSync,
  existsSync,
  lstatSync,
  readFileSync,
  readdirSync,
} from 'node:fs';
import { relative, resolve, sep } from 'node:path';

import { repositoryRoot } from './contract.mjs';

const envelopeBegin = 'CAPACITY_RECOVERY_CI_ARTIFACT_ENVELOPE_BEGIN';
const envelopeEnd = 'CAPACITY_RECOVERY_CI_ARTIFACT_ENVELOPE_END';
const retainedExtensions = new Set(['.json', '.md']);
const maximumFileBytes = 16 * 1024 * 1024;
const maximumTotalBytes = 48 * 1024 * 1024;

function collectEvidence(directory, files = []) {
  for (const name of readdirSync(directory).sort()) {
    const target = resolve(directory, name);
    const metadata = lstatSync(target);
    if (metadata.isSymbolicLink()) {
      throw new Error(`capacity evidence must not contain a symbolic link: ${target}`);
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

export function sha256File(path) {
  return createHash('sha256').update(readFileSync(path)).digest('hex');
}

export function appendCiEvidenceEnvelope(status, runDirectory, identity) {
  if (process.env.GITHUB_ACTIONS !== 'true') return;
  const artifactLog = resolve(repositoryRoot, 'root-install.log');
  if (!existsSync(artifactLog)) {
    throw new Error('root-install.log is unavailable for capacity evidence retention');
  }
  let totalBytes = 0;
  const files = collectEvidence(runDirectory).map((target) => {
    const content = readFileSync(target);
    if (content.length > maximumFileBytes) {
      throw new Error(`capacity evidence file is too large: ${target}`);
    }
    totalBytes += content.length;
    if (totalBytes > maximumTotalBytes) {
      throw new Error('capacity retained evidence exceeds the bounded total size');
    }
    const path = relative(runDirectory, target).split(sep).join('/');
    if (!path || path.startsWith('../') || path.includes('/../')) {
      throw new Error(`capacity evidence escaped its run directory: ${target}`);
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
      'profile-contract.json',
      'environment.json',
      'postgres-before.json',
      'postgres-after.json',
      'request-samples.json',
      'small-demo-profile.json',
      'small-demo-cleanup.json',
      'recovery-summary.json',
      'runtime-summary.json',
    ]) {
      if (!files.some(file => file.path === required)) {
        throw new Error(`passed capacity slice did not retain ${required}`);
      }
    }
  }
  const envelope = {
    schemaVersion: 1,
    evidenceKind: 'CAPACITY_RECOVERY_CI_ARTIFACT_ENVELOPE_V1',
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
