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

const envelopeBegin = 'CAPACITY_PROFILE_MATRIX_CI_ARTIFACT_ENVELOPE_BEGIN';
const envelopeEnd = 'CAPACITY_PROFILE_MATRIX_CI_ARTIFACT_ENVELOPE_END';
const retainedExtensions = new Set(['.json', '.md']);
const maximumFileBytes = 24 * 1024 * 1024;
const maximumTotalBytes = 64 * 1024 * 1024;

function collectEvidence(directory, files = []) {
  for (const name of readdirSync(directory).sort()) {
    const target = resolve(directory, name);
    const metadata = lstatSync(target);
    if (metadata.isSymbolicLink()) {
      throw new Error(`profile-matrix evidence must not contain a symbolic link: ${target}`);
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

export function appendProfileMatrixEnvelope(status, runDirectory, identity) {
  if (process.env.GITHUB_ACTIONS !== 'true') return;
  const artifactLog = resolve(repositoryRoot, 'root-install.log');
  if (!existsSync(artifactLog)) {
    throw new Error('root-install.log is unavailable for profile-matrix evidence');
  }
  let totalBytes = 0;
  const files = collectEvidence(runDirectory).map((target) => {
    const content = readFileSync(target);
    if (content.length > maximumFileBytes) {
      throw new Error(`profile-matrix evidence file is too large: ${target}`);
    }
    totalBytes += content.length;
    if (totalBytes > maximumTotalBytes) {
      throw new Error('profile-matrix evidence exceeds the bounded total size');
    }
    const path = relative(runDirectory, target).split(sep).join('/');
    if (!path || path.startsWith('../') || path.includes('/../')) {
      throw new Error(`profile-matrix evidence escaped its run directory: ${target}`);
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
      'profile-matrix-contract.json',
      'standard-deployment-profile.json',
      'large-tenant-profile.json',
      'profile-matrix-cleanup.json',
      'profile-matrix-summary.json',
    ]) {
      if (!files.some(file => file.path === required)) {
        throw new Error(`passed profile matrix did not retain ${required}`);
      }
    }
  }
  appendFileSync(
    artifactLog,
    `\n${envelopeBegin}\n${JSON.stringify({
      schemaVersion: 1,
      evidenceKind: 'CAPACITY_PROFILE_MATRIX_CI_ARTIFACT_ENVELOPE_V1',
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
