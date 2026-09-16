#!/usr/bin/env node
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { chmodSync, lstatSync, mkdtempSync, readFileSync, realpathSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { isAbsolute, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { verifyOutboxAlerting } from './verify-outbox-alerts.mjs';

// Upstream archive identity, not a mutable image tag or a checksum downloaded beside it.
// Source: https://prometheus.io/download/ (3.13.3 linux-amd64, 2026-09-07).
export const promtoolPin = Object.freeze({
  version: '3.13.3',
  url: 'https://github.com/prometheus/prometheus/releases/download/v3.13.3/prometheus-3.13.3.linux-amd64.tar.gz',
  sha256: 'b349c732d8a853e657d0e7ae1bbad4d11b586615fb65fdc59d896b9f869c001e',
  member: 'prometheus-3.13.3.linux-amd64/promtool',
});
const root = resolve(fileURLToPath(new URL('../..', import.meta.url)));
const maximumArchiveBytes = 120 * 1024 * 1024;
const files = Object.freeze([
  'approval-platform.rules.yml',
  'approval-platform.rules.test.yml',
  'approval-platform.rules.regression.test.yml',
]);
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const checked = (condition, message) => { if (!condition) throw new Error(message); };

export function verifyArchiveDigest(file, expected) {
  checked(/^[0-9a-f]{64}$/u.test(expected), 'PROMTOOL_DIGEST_REQUIRED');
  const stat = lstatSync(file);
  checked(stat.isFile() && !stat.isSymbolicLink() && stat.size > 0
    && stat.size <= maximumArchiveBytes, 'PROMTOOL_ARCHIVE_REJECTED');
  checked(hash(readFileSync(file)) === expected, 'PROMTOOL_ARCHIVE_DIGEST_MISMATCH');
}

function command(run, executable, args, cwd, timeout) {
  const result = run(executable, args, { cwd, shell: false, encoding: 'utf8',
    timeout, maxBuffer: 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'],
    // Scanners and downloaded tools receive no GitHub/cloud/Provider credentials.
    env: { PATH: process.env.PATH, LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8' } });
  checked(!result.error && result.status === 0, 'PROMTOOL_COMMAND_FAILED: '
    + (result.error?.code || result.signal || result.status) + '\n'
    + String(result.stdout || '').slice(-4000) + String(result.stderr || '').slice(-4000));
  return String(result.stdout || '') + String(result.stderr || '');
}

/** Executes the real parser and engine. Supplying run is a unit-test seam, not the CI path. */
export function runPromtoolChecks(executable, { repositoryRoot = root, run = spawnSync, log = console.log } = {}) {
  checked(typeof executable === 'string' && isAbsolute(executable), 'PROMTOOL_ABSOLUTE_PATH_REQUIRED');
  const stat = lstatSync(executable);
  checked(stat.isFile() && !stat.isSymbolicLink() && realpathSync(executable) === executable,
    'PROMTOOL_EXECUTABLE_REJECTED');
  const cwd = resolve(repositoryRoot, 'deploy/observability/prometheus');
  const inputs = files.map(file => ({ file, sha256: hash(readFileSync(resolve(cwd, file))) }));
  const version = command(run, executable, ['--version'], cwd, 10000);
  checked(/promtool, version 3\.13\.3(?:\s|$)/u.test(version), 'PROMTOOL_VERSION_MISMATCH');
  log(version.trim());
  log(command(run, executable, ['check', 'rules', files[0]], cwd, 30000).trim());
  log(command(run, executable, ['test', 'rules', files[1], files[2]], cwd, 60000).trim());
  const result = { status: 'OPS_PROMETHEUS_RULES_VERIFIED', toolVersion: promtoolPin.version,
    inputs, liveScrapeVerified: false, notificationDeliveryVerified: false };
  log(JSON.stringify(result));
  return result;
}

/** Bounded, digest-checked provisioning for the existing Linux x64 CI job. No retries. */
export function provisionAndVerifyPrometheusRules() {
  checked(process.platform === 'linux' && process.arch === 'x64', 'PROMTOOL_LINUX_X64_REQUIRED');
  const directory = mkdtempSync(resolve(tmpdir(), 'approval-promtool-'));
  try {
    const archive = resolve(directory, 'prometheus.tgz');
    command(spawnSync, 'curl', ['--fail', '--location', '--silent', '--show-error',
      '--proto', '=https', '--proto-redir', '=https', '--connect-timeout', '15', '--max-time', '90',
      '--max-filesize', String(maximumArchiveBytes), promtoolPin.url, '-o', archive], directory, 120000);
    verifyArchiveDigest(archive, promtoolPin.sha256);
    command(spawnSync, 'tar', ['--extract', '--gzip', '--file', archive, '--directory', directory,
      '--no-same-owner', '--no-same-permissions', promtoolPin.member,
      promtoolPin.member.replace('/promtool', '/prometheus')], directory, 15000);
    const executable = resolve(directory, promtoolPin.member);
    checked(lstatSync(executable).isFile() && !lstatSync(executable).isSymbolicLink()
      && realpathSync(executable) === executable, 'PROMTOOL_EXECUTABLE_REJECTED');
    chmodSync(executable, 0o700);
    console.log(`OPS_PROMTOOL_ARCHIVE_SHA256=${promtoolPin.sha256}`);
    const result = runPromtoolChecks(executable);
    const outbox = verifyOutboxAlerting({ directory, repositoryRoot: root, promtool: executable,
      prometheus: resolve(directory, promtoolPin.member.replace('/promtool', '/prometheus')),
      runCommand: (file, args, cwd, timeout) => command(spawnSync, file, args, cwd, timeout),
      verifyArchive: verifyArchiveDigest });
    console.log(JSON.stringify(outbox)); // Native execution only; fixture runners return data silently.
    return { ...result, outbox };
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const args = process.argv.slice(2);
    if (args.length === 0) provisionAndVerifyPrometheusRules();
    else if (args.length === 2 && args[0] === '--tool') runPromtoolChecks(args[1]);
    else throw new Error('usage: verify-prometheus-rules.mjs [--tool /absolute/path/to/promtool]');
  } catch (error) {
    console.error(error.message); process.exitCode = 1;
  }
}
