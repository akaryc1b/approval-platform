#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { appendFileSync, mkdirSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { MAVEN, requirePinnedMaven } from './maven-toolchain.mjs';

function run(command, args) {
  const result = spawnSync(command, args, { encoding: 'utf8', timeout: 240000, maxBuffer: 1024 * 1024 });
  if (result.error || result.status !== 0) throw new Error(`${command} failed: ${result.error?.message ?? result.stderr}`);
  return result;
}
export function verifyDistribution(bytes) {
  if (createHash('sha512').update(bytes).digest('hex') !== MAVEN.sha512) {
    throw new Error('Apache Maven distribution SHA-512 mismatch; refusing extraction or execution');
  }
}
export function publishMavenPath(bin, githubPath) {
  if (!path.isAbsolute(bin) || /[\r\n]/.test(bin)) throw new Error('Invalid Maven PATH entry');
  if (githubPath) appendFileSync(githubPath, `${bin}\n`);
}

// A fresh directory prevents an existing/cached installation from bypassing verification.
// No system packages, global Maven settings, credentials, or third-party setup actions.
export function installMaven(parent = process.env.RUNNER_TEMP || tmpdir()) {
  if (!path.isAbsolute(parent) || /[\r\n]/.test(parent)) throw new Error('Invalid Maven installation directory');
  if (process.env.GITHUB_ACTIONS === 'true' && !process.env.GITHUB_PATH) throw new Error('GITHUB_PATH is required in CI');
  mkdirSync(parent, { recursive: true });
  const directory = mkdtempSync(path.join(parent, 'approval-maven-'));
  try {
    const archive = path.join(directory, 'maven.tar.gz');
    run('curl', ['--proto', '=https', '--tlsv1.2', '--fail', '--location', '--silent', '--show-error', MAVEN.url, '-o', archive]);
    verifyDistribution(readFileSync(archive));
    run('tar', ['-xzf', archive, '-C', directory]);
    const bin = path.join(directory, `apache-maven-${MAVEN.version}`, 'bin');
    requirePinnedMaven(path.join(bin, 'mvn'));
    publishMavenPath(bin, process.env.GITHUB_PATH);
    rmSync(archive);
    return bin;
  } catch (error) {
    rmSync(directory, { recursive: true, force: true });
    throw error;
  }
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const bin = installMaven(process.argv[2]);
  console.log(`Verified Apache Maven ${MAVEN.version}; SHA-512 ${MAVEN.sha512}`);
  console.log(`Maven bin: ${bin}`);
}
