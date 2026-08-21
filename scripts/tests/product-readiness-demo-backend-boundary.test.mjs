import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const commandPath = resolve(root, 'scripts/product-readiness/demo-backend.mjs');
const packagePath = resolve(root, 'package.json');
const quickStartPath = resolve(root, 'docs/product-readiness/QUICK_START.md');
const statusPath = resolve(root, 'docs/product-readiness/README.md');
const aggregatePath = resolve(root, 'scripts/tests/m3-repository-hygiene.test.mjs');

function text(path) {
  assert.equal(existsSync(path), true, `missing ${path}`);
  return readFileSync(path, 'utf8');
}

test('one-command backend plan is exact, read-only and retains every non-claim', () => {
  const execution = spawnSync(process.execPath, [commandPath, 'plan', '--json'], {
    cwd: root,
    encoding: 'utf8',
  });
  assert.equal(execution.status, 0, execution.stderr || execution.stdout);
  const plan = JSON.parse(execution.stdout);
  assert.equal(plan.schemaVersion, 1);
  assert.equal(plan.entrypoint, 'pnpm demo:backend:start');
  assert.equal(plan.destructive, false);
  assert.equal(plan.revision, '0.1.0-SNAPSHOT');
  assert.deepEqual(
    plan.steps.map(step => step.id),
    [
      'preflight',
      'infrastructure',
      'postgres-readiness',
      'redis-readiness',
      'reactor-build',
      'backend',
      'health',
      'seed',
    ],
  );
  assert.equal(
    plan.steps.find(step => step.id === 'reactor-build').command,
    'mvn -B -ntp -Drevision=0.1.0-SNAPSHOT -DskipTests install',
  );
  const backendCommand = plan.steps.find(step => step.id === 'backend').command;
  assert.equal(
    backendCommand,
    'APPROVAL_DEMO_PURCHASE_PAYMENT_ENABLED=true '
      + 'mvn -B -ntp -Drevision=0.1.0-SNAPSHOT '
      + '-pl :approval-server spring-boot:run '
      + '-Dspring-boot.run.profiles=local',
  );
  assert.doesNotMatch(backendCommand, /-f apps\/server\/pom\.xml/u);
  assert.equal(plan.successMarkers.includes('BACKEND_LOCAL_START_VERIFIED'), true);
  for (const marker of [
    'QUICK_START_10_MINUTES_NOT_EXECUTED',
    'PURCHASE_APPROVAL_E2E_NOT_EXECUTED',
    'PURCHASE_TO_PAYMENT_SANDBOX_E2E_NOT_EXECUTED',
    'CROSS_CLIENT_RUNTIME_NOT_EXECUTED',
    'PRODUCTION_PAYMENT_INTEGRATION_NOT_VERIFIED',
  ]) {
    assert.equal(plan.nonClaims.includes(marker), true, `missing ${marker}`);
  }
});

test('revision is read from the root pom and passed to both Maven invocations', () => {
  const source = text(commandPath);
  assert.match(source, /const rootPomPath = resolve\(root, 'pom\.xml'\)/u);
  assert.match(source, /function rootRevision\(\)/u);
  assert.match(source, /<revision>\(\[\^<\]\+\)<\\\/revision>/u);
  assert.match(source, /revision\.includes\('\$\{'\)/u);
  assert.match(source, /\^\[0-9A-Za-z\]\[0-9A-Za-z\._-\]\*\$/u);
  assert.match(source, /`-Drevision=\$\{revision\}`/u);
  assert.match(
    source,
    /runMavenChecked\('Build Maven reactor for local startup',[\s\S]*`-Drevision=\$\{revision\}`,[\s\S]*'-DskipTests',[\s\S]*'install'/u,
  );
  assert.match(
    source,
    /spawn\(mavenExecutable\(\), \[[\s\S]*`-Drevision=\$\{revision\}`,[\s\S]*'-pl',[\s\S]*':approval-server',[\s\S]*'spring-boot:run'/u,
  );
});

test('backend command uses fixed executables, local values and no direct database writes', () => {
  const source = text(commandPath);
  assert.match(source, /shell: false/gu);
  assert.match(source, /APPROVAL_DEMO_PURCHASE_PAYMENT_ENABLED: 'true'/u);
  assert.match(source, /readLocalDatabaseEnvironment/u);
  assert.match(source, /--project-name', composeProject/u);
  assert.match(source, /PURCHASE_PAYMENT_DEMO_SEED_APPLIED/u);
  assert.match(source, /function mavenExecutable\(\)/u);
  assert.match(source, /spawnSync\(process\.execPath, args/u);
  assert.match(source, /spawnSync\('docker', args/u);
  assert.match(source, /spawnSync\(mavenExecutable\(\), args/u);
  assert.match(source, /function waitForDockerCommand\(label, args, predicate, timeoutMs\)/u);
  assert.doesNotMatch(source, /'-f',\s*'apps\/server\/pom\.xml'/u);
  assert.doesNotMatch(source, /function executable\(name\)/u);
  assert.doesNotMatch(source, /function runChecked\(label, command, args\)/u);
  assert.doesNotMatch(source, /function runCaptured\(command, args\)/u);
  assert.doesNotMatch(source, /execSync|execFileSync|\bexec\s*\(/u);
  assert.doesNotMatch(source, /JdbcTemplate|DataSource|psql|ACT_[A-Z_]+|DELETE\s+FROM|DROP\s+TABLE/iu);
  assert.doesNotMatch(source, /spring-boot\.run\.profiles=prod|SPRING_PROFILES_ACTIVE.*prod/iu);
});

test('local data reset is fail-closed before Docker execution', () => {
  const execution = spawnSync(process.execPath, [commandPath, 'reset'], {
    cwd: root,
    encoding: 'utf8',
  });
  assert.equal(execution.status, 2);
  assert.match(execution.stderr, /requires --confirm-local-data-loss/u);
  assert.match(execution.stderr, /no Docker command was executed/u);
});

test('package and docs expose one command without manufacturing product acceptance', () => {
  const packageJson = JSON.parse(text(packagePath));
  assert.equal(
    packageJson.scripts?.['demo:backend:plan'],
    'node scripts/product-readiness/demo-backend.mjs plan --json',
  );
  assert.equal(
    packageJson.scripts?.['demo:backend:start'],
    'node scripts/product-readiness/demo-backend.mjs start',
  );
  assert.equal(
    packageJson.scripts?.['demo:backend:stop'],
    'node scripts/product-readiness/demo-backend.mjs stop',
  );
  const quickStart = text(quickStartPath);
  const status = text(statusPath);
  for (const command of [
    'pnpm demo:backend:plan',
    'pnpm demo:backend:start',
    'pnpm demo:backend:stop',
  ]) {
    assert.equal(quickStart.includes(command), true, `Quick Start missing ${command}`);
  }
  for (const source of [quickStart, status]) {
    assert.match(source, /DEMO_BACKEND_ONE_COMMAND_IMPLEMED/u);
    assert.match(source, /QUICK_START_10_MINUTES_NOT_EXECUTED/u);
    assert.match(source, /PURCHASE_APPROVAL_E2E_NOT_EXECUTED/u);
  }
  assert.doesNotMatch(quickStart, /^QUICK_START_10_MINUTES_PASSED$/mu);
});

test('the permanent Hygiene aggregate loads the backend command boundary', () => {
  assert.match(
    text(aggregatePath),
    /import '\.\/product-readiness-demo-backend-boundary\.test\.mjs';/u,
  );
});
