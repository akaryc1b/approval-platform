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

const envelopeBegin = 'BROWSER_ACCESSIBILITY_EVIDENCE_ENVELOPE_BEGIN';
const envelopeEnd = 'BROWSER_ACCESSIBILITY_EVIDENCE_ENVELOPE_END';
const retainedExtensions = new Set(['.json', '.md', '.png', '.zip']);
const maximumFileBytes = 64 * 1024 * 1024;
const maximumTotalBytes = 128 * 1024 * 1024;

function collectEvidence(directory, files = []) {
  for (const name of readdirSync(directory).sort()) {
    const target = resolve(directory, name);
    const metadata = lstatSync(target);
    if (metadata.isSymbolicLink()) {
      throw new Error(
        `browser accessibility evidence must not contain a symlink: ${target}`,
      );
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

export function appendCiEvidenceEnvelope(
  status,
  runDirectory,
  identity,
) {
  if (process.env.GITHUB_ACTIONS !== 'true') return;
  const artifactLog = resolve(repositoryRoot, 'root-install.log');
  if (!existsSync(artifactLog)) {
    throw new Error(
      'root-install.log is unavailable for browser evidence retention',
    );
  }
  let totalBytes = 0;
  const files = collectEvidence(runDirectory).map((target) => {
    const content = readFileSync(target);
    if (content.length > maximumFileBytes) {
      throw new Error(`browser evidence file is too large: ${target}`);
    }
    totalBytes += content.length;
    if (totalBytes > maximumTotalBytes) {
      throw new Error('browser evidence exceeds the bounded total size');
    }
    const path = relative(runDirectory, target).split(sep).join('/');
    if (!path || path.startsWith('../') || path.includes('/../')) {
      throw new Error(`browser evidence escaped its run directory: ${target}`);
    }
    return {
      path,
      size: content.length,
      sha256: createHash('sha256').update(content).digest('hex'),
      base64: content.toString('base64'),
    };
  });
  if (status === 'PASSED') {
    const required = [
      'source-identity.json',
      'matrix-contract.json',
      'matrix-summary.json',
      'runtime-summary.json',
      'system-chromium/matrix-evidence.json',
      'system-chromium/pc-task-list.png',
      'system-chromium/pc-task-detail.png',
      'system-chromium/pc-confirmation-dialog.png',
      'system-chromium/h5-task-list.png',
      'system-chromium/h5-task-detail.png',
      'bundled-firefox/matrix-evidence.json',
      'bundled-firefox/pc-task-list.png',
      'bundled-firefox/pc-task-detail.png',
      'bundled-firefox/h5-task-list.png',
      'bundled-firefox/h5-task-detail.png',
      'bundled-webkit/matrix-evidence.json',
      'bundled-webkit/pc-task-list.png',
      'bundled-webkit/pc-task-detail.png',
      'bundled-webkit/h5-task-list.png',
      'bundled-webkit/h5-task-detail.png',
    ];
    for (const path of required) {
      if (!files.some(file => file.path === path)) {
        throw new Error(`passed browser matrix did not retain ${path}`);
      }
    }
    const traceCount = files.filter(file => file.path.endsWith('/trace.zip'))
      .length;
    if (traceCount < 3) {
      throw new Error('passed browser matrix did not retain three traces');
    }
  }
  const envelope = {
    schemaVersion: 1,
    evidenceKind: 'BROWSER_ACCESSIBILITY_CI_ARTIFACT_ENVELOPE_V1',
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
