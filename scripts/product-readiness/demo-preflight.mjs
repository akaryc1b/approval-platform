#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const argumentsSet = new Set(process.argv.slice(2));
const allowedArguments = new Set(['--help', '--json', '--repository-only']);
const unknownArguments = [...argumentsSet].filter((argument) => !allowedArguments.has(argument));

if (argumentsSet.has('--help')) {
  console.log(`Usage: node scripts/product-readiness/demo-preflight.mjs [options]\n\nOptions:\n  --json             Print machine-readable JSON.\n  --repository-only  Validate repository contracts without checking local tools.\n  --help             Show this help.\n\nThe preflight is read-only. It does not start services and does not prove the\n10-minute Quick Start, cross-client E2E, payment integration, capacity, or recovery.`);
  process.exit(0);
}

if (unknownArguments.length > 0) {
  console.error(`Unknown option(s): ${unknownArguments.join(', ')}`);
  process.exit(2);
}

const jsonOutput = argumentsSet.has('--json');
const repositoryOnly = argumentsSet.has('--repository-only');
const results = [];

function addResult(category, name, passed, detail, remediation = null) {
  results.push({ category, name, passed, detail, remediation });
}

function readRequired(relativePath) {
  const absolutePath = resolve(root, relativePath);
  const present = existsSync(absolutePath);
  addResult(
    'repository',
    `file:${relativePath}`,
    present,
    present ? 'present' : 'missing',
    present ? null : `Restore ${relativePath} before running the demo.`,
  );
  return present ? readFileSync(absolutePath, 'utf8') : null;
}

function run(command, args) {
  const executable = process.platform === 'win32' && ['mvn', 'pnpm'].includes(command)
    ? `${command}.cmd`
    : command;
  const completed = spawnSync(executable, args, {
    cwd: root,
    encoding: 'utf8',
    shell: false,
    timeout: 10_000,
  });
  const output = `${completed.stdout ?? ''}\n${completed.stderr ?? ''}`.trim();
  return {
    available: !completed.error && completed.status === 0,
    output,
    error: completed.error?.message ?? null,
  };
}

function firstLine(value) {
  return value.split(/\r?\n/u).find((line) => line.trim().length > 0)?.trim() ?? 'no version output';
}

function semanticVersion(value) {
  const match = value.match(/(?:^|[^0-9])(\d+)\.(\d+)(?:\.(\d+))?/u);
  return match ? [Number(match[1]), Number(match[2]), Number(match[3] ?? 0)] : null;
}

function atLeast(actual, minimum) {
  for (let index = 0; index < minimum.length; index += 1) {
    if (actual[index] > minimum[index]) return true;
    if (actual[index] < minimum[index]) return false;
  }
  return true;
}

function checkCommand(name, command, args, validate, remediation) {
  const execution = run(command, args);
  if (!execution.available) {
    addResult('tool', name, false, execution.error ?? firstLine(execution.output), remediation);
    return;
  }
  const validation = validate(execution.output);
  addResult('tool', name, validation.passed, validation.detail, validation.passed ? null : remediation);
}

const packageJsonText = readRequired('package.json');
const pomText = readRequired('pom.xml');
const environmentExample = readRequired('.env.example');
const composeText = readRequired('deploy/compose/docker-compose.yml');
const baseConfiguration = readRequired('apps/server/src/main/resources/application.yml');
const localConfiguration = readRequired('apps/server/src/main/resources/application-local.yml');

if (packageJsonText) {
  try {
    const packageJson = JSON.parse(packageJsonText);
    const packageManager = String(packageJson.packageManager ?? '');
    const nodeEngine = String(packageJson.engines?.node ?? '');
    const pnpmEngine = String(packageJson.engines?.pnpm ?? '');
    addResult(
      'repository',
      'package-manager-contract',
      /^pnpm@10\./u.test(packageManager),
      `packageManager=${packageManager || '<missing>'}`,
      'Use the repository-declared pnpm 10 package manager.',
    );
    addResult(
      'repository',
      'node-engine-contract',
      nodeEngine.includes('22.18.0') && nodeEngine.includes('24.0.0'),
      `engines.node=${nodeEngine || '<missing>'}`,
      'Keep the documented Node support contract aligned with package.json.',
    );
    addResult(
      'repository',
      'pnpm-engine-contract',
      pnpmEngine.includes('10'),
      `engines.pnpm=${pnpmEngine || '<missing>'}`,
      'Keep the documented pnpm support contract aligned with package.json.',
    );
  } catch (error) {
    addResult('repository', 'package-json-parse', false, error.message, 'Repair package.json JSON syntax.');
  }
}

if (pomText) {
  addResult(
    'repository',
    'java-21-contract',
    /<java\.version>21<\/java\.version>/u.test(pomText),
    'expected <java.version>21</java.version>',
    'Keep the Quick Start Java version aligned with the Maven reactor.',
  );
  addResult(
    'repository',
    'maven-minimum-contract',
    /<version>\[3\.9\.6,\)<\/version>/u.test(pomText),
    'expected Maven [3.9.6,)',
    'Keep the Quick Start Maven minimum aligned with the enforcer rule.',
  );
}

if (environmentExample) {
  const requiredVariables = ['APPROVAL_DB_URL', 'APPROVAL_DB_USERNAME', 'APPROVAL_DB_PASSWORD'];
  const missingVariables = requiredVariables.filter((name) => !environmentExample.includes(`${name}=`));
  addResult(
    'repository',
    'database-environment-contract',
    missingVariables.length === 0,
    missingVariables.length === 0 ? 'database variables present' : `missing ${missingVariables.join(', ')}`,
    'Restore the non-secret local database variable example.',
  );
}

if (composeText) {
  addResult(
    'repository',
    'postgres-compose-contract',
    composeText.includes('postgres:16-alpine'),
    'expected postgres:16-alpine',
    'Keep the demo database image aligned with the documented PostgreSQL 16 baseline.',
  );
  addResult(
    'repository',
    'redis-compose-contract',
    composeText.includes('redis:7.4-alpine'),
    'expected redis:7.4-alpine',
    'Keep the demo infrastructure description aligned with the compose file.',
  );
}

if (baseConfiguration) {
  addResult(
    'repository',
    'base-flowable-fail-closed-contract',
    /database-schema-update:\s*false/u.test(baseConfiguration),
    'base profile must keep Flowable schema updates disabled',
    'Do not enable local schema mutation in the base configuration.',
  );
  addResult(
    'repository',
    'actuator-health-contract',
    /include:\s*health,info,metrics,prometheus/u.test(baseConfiguration),
    'health endpoint is exposed for startup verification',
    'Expose a bounded health endpoint or update the Quick Start verification contract.',
  );
}

if (localConfiguration) {
  addResult(
    'repository',
    'local-flowable-bootstrap-contract',
    /database-schema-update:\s*true/u.test(localConfiguration),
    'local profile permits initial Flowable schema bootstrap',
    'Keep local bootstrap explicit and isolated from the base profile.',
  );
  addResult(
    'repository',
    'local-identity-contract',
    /mode:\s*local-headers/u.test(localConfiguration),
    'local profile uses local-headers identity mode',
    'Document or restore the local identity mode before claiming a runnable demo.',
  );
  addResult(
    'repository',
    'local-management-permission-contract',
    /management-permissions:[\s\S]*?enforced:\s*true/u.test(localConfiguration),
    'management permissions remain enforced in the local profile',
    'The demo profile must not disable management permission enforcement.',
  );
}

if (!repositoryOnly) {
  const nodeVersion = semanticVersion(process.versions.node);
  const supportedNode = nodeVersion
    && ((nodeVersion[0] === 22 && atLeast(nodeVersion, [22, 18, 0])) || nodeVersion[0] === 24);
  addResult(
    'tool',
    'node',
    Boolean(supportedNode),
    `Node ${process.versions.node}`,
    'Install Node 22.18+ within the 22.x line, or Node 24.x.',
  );

  checkCommand(
    'java',
    'java',
    ['-version'],
    (output) => {
      const match = output.match(/(?:openjdk|java) version "?(\d+)/iu);
      const major = match ? Number(match[1]) : null;
      return { passed: major === 21, detail: firstLine(output) };
    },
    'Install a Java 21 JDK and make it the active java command.',
  );

  checkCommand(
    'maven',
    'mvn',
    ['-version'],
    (output) => {
      const version = semanticVersion(output);
      return { passed: Boolean(version && atLeast(version, [3, 9, 6])), detail: firstLine(output) };
    },
    'Install Maven 3.9.6 or newer.',
  );

  checkCommand(
    'pnpm',
    'pnpm',
    ['--version'],
    (output) => {
      const version = semanticVersion(output);
      return { passed: Boolean(version && version[0] === 10), detail: `pnpm ${firstLine(output)}` };
    },
    'Install pnpm 10; the repository currently declares pnpm 10.33.4.',
  );

  checkCommand(
    'docker',
    'docker',
    ['--version'],
    (output) => ({ passed: /Docker version/iu.test(output), detail: firstLine(output) }),
    'Install Docker Engine or Docker Desktop and make docker available.',
  );

  checkCommand(
    'docker-compose-plugin',
    'docker',
    ['compose', 'version'],
    (output) => ({ passed: /Docker Compose version/iu.test(output), detail: firstLine(output) }),
    'Install the Docker Compose v2 plugin.',
  );
}

const failed = results.filter((result) => !result.passed);
const summary = {
  repositoryRoot: root,
  mode: repositoryOnly ? 'repository-only' : 'full',
  passed: failed.length === 0,
  passedChecks: results.length - failed.length,
  failedChecks: failed.length,
  claim: failed.length === 0 ? 'DEMO_PREFLIGHT_PASSED' : 'DEMO_PREFLIGHT_FAILED',
  quickStartAcceptance: 'QUICK_START_10_MINUTES_NOT_EXECUTED',
  results,
};

if (jsonOutput) {
  console.log(JSON.stringify(summary, null, 2));
} else {
  console.log('Approval Platform Demo Preflight');
  console.log(`Repository: ${relative(process.cwd(), root) || '.'}`);
  console.log(`Mode: ${summary.mode}`);
  console.log('Read-only check: no service is started and no product acceptance is claimed.\n');
  for (const result of results) {
    const status = result.passed ? 'PASS' : 'FAIL';
    console.log(`${status.padEnd(4)}  ${result.category.padEnd(10)}  ${result.name} — ${result.detail}`);
    if (!result.passed && result.remediation) console.log(`      remediation: ${result.remediation}`);
  }
  console.log(`\nSummary: ${summary.passedChecks} passed, ${summary.failedChecks} failed`);
  console.log(summary.claim);
  console.log(summary.quickStartAcceptance);
  console.log('Next document: docs/product-readiness/QUICK_START.md');
}

process.exitCode = failed.length === 0 ? 0 : 1;
