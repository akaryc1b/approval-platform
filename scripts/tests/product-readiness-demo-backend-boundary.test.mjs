import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  backendApplicationArguments,
  backendEnvironment,
  backendReady,
  observeBackendOutput,
  restoreMarker,
  seedMarker,
  startupContract,
  startupMode,
} from '../product-readiness/demo-backend-startup.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const commandPath = resolve(root, 'scripts/product-readiness/demo-backend.mjs');
const packagePath = resolve(root, 'package.json');
const quickStartPath = resolve(root, 'docs/product-readiness/QUICK_START.md');
const statusPath = resolve(root, 'docs/product-readiness/README.md');
const aggregatePath = resolve(root, 'scripts/tests/m3-repository-hygiene.test.mjs');
const rootPomPath = resolve(root, 'pom.xml');

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
    'mvn -B -ntp -Pproduct-readiness-demo '
      + '-Drevision=0.1.0-SNAPSHOT -DskipTests '
      + '-Dapproval.persistence.tests.skip=true install',
  );
  const backendCommand = plan.steps.find(step => step.id === 'backend').command;
  assert.equal(
    backendCommand,
    'APPROVAL_DEMO_PURCHASE_PAYMENT_ENABLED=true '
      + 'APPROVAL_DEMO_PURCHASE_PAYMENT_SEED_ENABLED=true '
      + 'mvn -B -ntp -Pproduct-readiness-demo '
      + '-Drevision=0.1.0-SNAPSHOT '
      + '-pl :approval-server spring-boot:run '
      + '-Dspring-boot.run.profiles=local '
      + '-Dspring-boot.run.arguments="--approval.demo.purchase-payment.enabled=true '
      + '--approval.demo.purchase-payment.seed.enabled=true"',
  );
  assert.doesNotMatch(backendCommand, /-f apps\/server\/pom\.xml/u);
  assert.equal(plan.successMarkers.includes('BACKEND_LOCAL_START_VERIFIED'), true);
  assert.equal(
    plan.successMarkers.includes('QUICK_START_10_MINUTES_PASSED'),
    false,
  );
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

test('revision and the demo-only Maven profile reach both Maven invocations', () => {
  const source = text(commandPath);
  assert.match(source, /const rootPomPath = resolve\(root, 'pom\.xml'\)/u);
  assert.match(source, /const demoMavenProfile = 'product-readiness-demo'/u);
  assert.match(source, /function rootRevision\(\)/u);
  assert.match(source, /<revision>\(\[\^<\]\+\)<\\\/revision>/u);
  assert.match(source, /revision\.includes\('\$\{'\)/u);
  assert.match(source, /\^\[0-9A-Za-z\]\[0-9A-Za-z\._-\]\*\$/u);
  assert.match(source, /`-P\$\{demoMavenProfile\}`/u);
  assert.match(source, /`-Drevision=\$\{revision\}`/u);
  assert.match(
    source,
    /runMavenChecked\('Build Maven reactor for local startup',[\s\S]*`-P\$\{demoMavenProfile\}`,[\s\S]*`-Drevision=\$\{revision\}`,[\s\S]*'-DskipTests',[\s\S]*'-Dapproval\.persistence\.tests\.skip=true',[\s\S]*'install'/u,
  );
  assert.match(
    source,
    /spawn\(mavenExecutable\(\), \[[\s\S]*`-P\$\{demoMavenProfile\}`,[\s\S]*`-Drevision=\$\{revision\}`,[\s\S]*'-pl',[\s\S]*':approval-server',[\s\S]*'spring-boot:run'/u,
  );
});

test('setup skips duplicate JDBC tests only while dedicated CI verification stays mandatory', () => {
  const source = text(commandPath);
  const buildArguments = source.match(
    /runMavenChecked\('Build Maven reactor for local startup', \[([\s\S]*?)\]\);/u,
  )?.[1];
  assert.ok(buildArguments, 'startup must still build the Maven reactor');
  assert.deepEqual(
    [...buildArguments.matchAll(/'([^']+)'/gu)].map(match => match[1]),
    ['-B', '-ntp', '-DskipTests', '-Dapproval.persistence.tests.skip=true', 'install'],
  );
  const runtimeArguments = source.match(
    /const child = spawn\(mavenExecutable\(\), \[([\s\S]*?)\], \{/u,
  )?.[1];
  assert.ok(runtimeArguments, 'startup must still launch the real backend');
  assert.doesNotMatch(runtimeArguments, /skip|test|exclude/iu);
  assert.doesNotMatch(source, /(?:MAVEN_OPTS|JAVA_TOOL_OPTIONS|maven\.test\.skip)/u);

  const jdbcPom = text(resolve(root, 'server-modules/approval-persistence-jdbc/pom.xml'));
  assert.match(jdbcPom, /<approval\.persistence\.tests\.skip>false<\/approval\.persistence\.tests\.skip>/u);
  assert.match(jdbcPom, /<skipTests>\$\{approval\.persistence\.tests\.skip\}<\/skipTests>/u);
  assert.doesNotMatch(text(rootPomPath), /approval\.persistence\.tests\.skip/u);
  const workflow = text(resolve(root, '.github/workflows/approval-platform-validation.yml'));
  const jdbcJob = workflow.match(/\n  persistence-jdbc:\n([\s\S]*?)\n  backend:\n/u)?.[1];
  assert.ok(jdbcJob, 'dedicated JDBC shards must remain present');
  assert.match(jdbcJob, /-am verify/u);
  assert.match(jdbcJob, /-Dtest="\$SELECTED_TESTS"/u);
  assert.doesNotMatch(jdbcJob, /-D(?:skipTests|maven\.test\.skip|approval\.persistence\.tests\.skip)\b/u);
  assert.match(workflow, /needs:\s*\n\s+- backend-core\s*\n\s+- persistence-jdbc/u);
  assert.match(workflow, /--expected-shards 4/u);
});

test('consumer-safe POM flattening is isolated to the explicit demo profile', () => {
  const source = text(rootPomPath);
  const profileStart = source.indexOf('<profiles>');
  assert.notEqual(profileStart, -1);
  assert.doesNotMatch(
    source.slice(0, profileStart),
    /<artifactId>flatten-maven-plugin<\/artifactId>/u,
  );
  const profiles = source.slice(profileStart);
  assert.match(profiles, /<id>product-readiness-demo<\/id>/u);
  assert.doesNotMatch(profiles, /<activation>/u);
  assert.match(
    source,
    /<flatten\.maven\.version>1\.7\.3<\/flatten\.maven\.version>/u,
  );
  assert.match(profiles, /<artifactId>flatten-maven-plugin<\/artifactId>/u);
  assert.match(
    profiles,
    /<flattenMode>resolveCiFriendliesOnly<\/flattenMode>/u,
  );
  assert.match(profiles, /<updatePomFile>true<\/updatePomFile>/u);
  assert.match(
    profiles,
    /<outputDirectory>\$\{project\.build\.directory\}<\/outputDirectory>/u,
  );
  assert.match(
    profiles,
    /<flattenedPomFilename>flattened-pom\.xml<\/flattenedPomFilename>/u,
  );
  assert.match(
    profiles,
    /<id>flatten<\/id>[\s\S]*<phase>process-resources<\/phase>[\s\S]*<goal>flatten<\/goal>/u,
  );
  assert.match(
    profiles,
    /<id>flatten\.clean<\/id>[\s\S]*<goal>clean<\/goal>/u,
  );
});

test('backend command uses fixed executables, local values and no direct database writes', () => {
  const source = text(commandPath);
  assert.match(source, /shell: false/gu);
  const startup = text(resolve(root, 'scripts/product-readiness/demo-backend-startup.mjs'));
  assert.match(startup, /APPROVAL_DEMO_PURCHASE_PAYMENT_ENABLED: 'true'/u);
  assert.match(source, /env: backendEnvironment\(mode, process\.env, localDatabaseEnvironment\)/u);
  assert.match(source, /readLocalDatabaseEnvironment/u);
  assert.match(source, /--project-name', composeProject/u);
  assert.match(startup, /PURCHASE_PAYMENT_DEMO_SEED_APPLIED/u);
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
  assert.doesNotMatch(
    source,
    /JdbcTemplate|DataSource|psql|ACT_[A-Z_]+|DELETE\s+FROM|DROP\s+TABLE/iu,
  );
  assert.doesNotMatch(
    source,
    /spring-boot\.run\.profiles=prod|SPRING_PROFILES_ACTIVE.*prod/iu,
  );
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

test('docs separate backend command scope from accepted Product Alpha evidence', () => {
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
    assert.match(source, /DEMO_BACKEND_ONE_COMMAND_IMPLEMENTED/u);
    assert.match(source, /QUICK_START_10_MINUTES_NOT_EXECUTED/u);
    assert.match(source, /PURCHASE_APPROVAL_E2E_NOT_EXECUTED/u);
  }
  assert.match(
    quickStart,
    /QUICK_START_ACCEPTANCE_STATUS=MERGED_LOCAL_ALPHA_ACCEPTED/u,
  );
  assert.match(quickStart, /^QUICK_START_10_MINUTES_PASSED$/mu);
  assert.match(status, /MERGED_MEASURED_LOCAL_ALPHA_ACCEPTED/u);
  assert.doesNotMatch(quickStart, /^PRODUCTION_DEPLOYMENT_STATUS=VERIFIED$/mu);
  assert.doesNotMatch(status, /^PRODUCTION_SUPPORT_STATUS=DECLARED$/mu);
});

test('the permanent Hygiene aggregate loads the backend command boundary', () => {
  assert.match(
    text(aggregatePath),
    /import '\.\/product-readiness-demo-backend-boundary\.test\.mjs';/u,
  );
});

test('restore-only plan requires its own application marker and never claims a seed or data verification', () => {
  const execution = spawnSync(process.execPath, [commandPath, 'plan-restored', '--json'], {
    cwd: root,
    encoding: 'utf8',
    env: { ...process.env, APPROVAL_DEMO_PURCHASE_PAYMENT_SEED_ENABLED: 'true' },
  });
  assert.equal(execution.status, 0, execution.stderr || execution.stdout);
  const plan = JSON.parse(execution.stdout);
  assert.equal(plan.startupMode, 'restore-only');
  assert.equal(plan.seedEnabled, false);
  assert.equal(plan.destructive, false);
  assert.equal(plan.entrypoint, 'node scripts/product-readiness/demo-backend.mjs start-restored');
  assert.deepEqual(plan.successMarkers, [
    'DEMO_BACKEND_RESTORE_ONLY_STARTED',
    'BACKEND_LOCAL_RESTORE_ONLY_STARTED',
    restoreMarker,
  ]);
  assert.equal(plan.steps.at(-1).id, 'restore-only-startup');
  assert.match(plan.steps.find(step => step.id === 'backend').command,
    /APPROVAL_DEMO_PURCHASE_PAYMENT_ENABLED=true APPROVAL_DEMO_PURCHASE_PAYMENT_SEED_ENABLED=false/u);
  assert.match(plan.steps.find(step => step.id === 'backend').command,
    /--approval\.demo\.purchase-payment\.seed\.enabled=false/u);
  assert.doesNotMatch(execution.stdout, /SEED_APPLIED|BACKEND_LOCAL_START_VERIFIED/u);
  assert.ok(plan.nonClaims.includes('DETERMINISTIC_DEMO_SEED_NOT_EXECUTED'));
  assert.ok(plan.nonClaims.includes('RESTORED_DATA_CONSISTENCY_NOT_VERIFIED'));
});

test('both startup modes force demo dependencies and their seed choice over inherited settings', () => {
  for (const [mode, seedEnabled, conflictingSeed] of [
    ['seed', 'true', 'false'],
    ['restore-only', 'false', 'true'],
  ]) {
    const inherited = {
      APPROVAL_DEMO_PURCHASE_PAYMENT_ENABLED: 'false',
      APPROVAL_DEMO_PURCHASE_PAYMENT_SEED_ENABLED: conflictingSeed,
      APPROVAL_DEMO_PAYMENT_SANDBOX_ENABLED: 'true',
      SPRING_APPLICATION_JSON: JSON.stringify({
        'approval.demo.purchase-payment.enabled': false,
        'approval.demo.purchase-payment.seed.enabled': conflictingSeed === 'true',
      }),
    };
    const environment = backendEnvironment(mode, inherited, { APPROVAL_DB_URL: 'jdbc:existing-demo' });
    assert.equal(environment.APPROVAL_DEMO_PURCHASE_PAYMENT_ENABLED, 'true');
    assert.equal(environment.APPROVAL_DEMO_PURCHASE_PAYMENT_SEED_ENABLED, seedEnabled);
    assert.equal(environment.APPROVAL_DEMO_PAYMENT_SANDBOX_ENABLED, 'true');
    assert.equal(environment.APPROVAL_DB_URL, 'jdbc:existing-demo');
    assert.equal(inherited.APPROVAL_DEMO_PURCHASE_PAYMENT_SEED_ENABLED, conflictingSeed);
    assert.equal(environment.SPRING_APPLICATION_JSON, inherited.SPRING_APPLICATION_JSON);
    assert.equal(backendApplicationArguments(mode),
      '--approval.demo.purchase-payment.enabled=true '
        + `--approval.demo.purchase-payment.seed.enabled=${seedEnabled}`);
  }
  const source = text(commandPath);
  assert.match(source, /`-Dspring-boot\.run\.arguments=\$\{backendApplicationArguments\(mode\)\}`/u);
  const execution = spawnSync(process.execPath, [commandPath, 'plan', '--json'], {
    cwd: root,
    encoding: 'utf8',
    env: { ...process.env, APPROVAL_DEMO_PURCHASE_PAYMENT_SEED_ENABLED: 'false' },
  });
  assert.equal(execution.status, 0, execution.stderr || execution.stdout);
  const plan = JSON.parse(execution.stdout);
  assert.equal(plan.startupMode, 'seed');
  assert.equal(plan.seedEnabled, true);
  assert.match(plan.steps.find(step => step.id === 'backend').command,
    /APPROVAL_DEMO_PURCHASE_PAYMENT_SEED_ENABLED=true/u);
});

test('mode-specific readiness requires health and its application marker and rejects the opposite mode', () => {
  const empty = { seedLine: null, restoreLine: null };
  const seeded = { ...empty, seedLine: seedMarker };
  const restored = { ...empty, restoreLine: restoreMarker };
  for (const mode of ['seed', 'restore-only']) {
    assert.equal(backendReady(mode, false, empty), false);
    assert.equal(backendReady(mode, true, empty), false);
  }
  assert.equal(backendReady('seed', false, seeded), false);
  assert.equal(backendReady('seed', true, seeded), true);
  assert.equal(backendReady('restore-only', false, restored), false);
  assert.equal(backendReady('restore-only', true, restored), true);
  for (const health of [false, true]) {
    assert.throws(() => backendReady('restore-only', health, seeded), /unexpectedly executed/u);
    assert.throws(() => backendReady('restore-only', health, { ...restored, ...seeded }),
      /unexpectedly executed/u);
    assert.throws(() => backendReady('seed', health, restored), /unexpectedly entered/u);
  }
  const source = text(commandPath);
  assert.match(source, /observeBackendOutput\(mode, state, text\)/u);
  assert.match(source, /backendReady\(mode, health\.ready, state\)/u);
  assert.match(source, /if \(mode === 'seed'\) console\.log\(state\.seedLine\)/u);
  assert.match(source, /if \(state\.spawnError\) throw state\.spawnError/u);
});

test('restore-only rejects seed output split over chunks, in large bursts or after readiness', () => {
  for (const initial of ['', `${restoreMarker}\n`]) {
    const state = { recentOutput: '', seedLine: null, restoreLine: null };
    observeBackendOutput('restore-only', state, initial);
    assert.equal(backendReady('restore-only', true, state), Boolean(initial));
    observeBackendOutput('restore-only', state, seedMarker.slice(0, 15));
    assert.throws(() => observeBackendOutput('restore-only', state,
      `${seedMarker.slice(15)} instanceId=unexpected\n${'x'.repeat(70_000)}`),
    /unexpectedly executed/u);
    assert.ok(state.seedLine.startsWith(seedMarker));
    assert.equal(state.recentOutput.length, 65_536);
  }
});

test('startup commands and invalid modes fail closed without executing external tools', () => {
  assert.equal(startupMode('start'), 'seed');
  assert.equal(startupMode('plan'), 'seed');
  assert.equal(startupMode('start-restored'), 'restore-only');
  assert.equal(startupMode('plan-restored'), 'restore-only');
  for (const invalid of ['', undefined, null, 'restore', 'false', 'toString', 'constructor']) {
    assert.throws(() => startupMode(invalid), /Unknown/u);
    assert.throws(() => startupContract(invalid), /Unknown/u);
    assert.throws(() => backendEnvironment(invalid, {}, {}), /Unknown/u);
    assert.throws(() => backendApplicationArguments(invalid), /Unknown/u);
    assert.throws(() => backendReady(invalid, true, {}), /Unknown/u);
  }
  for (const arguments_ of [
    ['restore'], ['start', '--restore-only'], ['start-restored', '--seed'],
    ['plan', '--mode=restore'], ['plan-restored', '--seed=false'],
  ]) {
    const execution = spawnSync(process.execPath, [commandPath, ...arguments_], {
      cwd: root, encoding: 'utf8',
    });
    assert.equal(execution.status, 2, execution.stderr || execution.stdout);
    assert.match(execution.stderr, /Unknown (?:command|option)/u);
    assert.equal(execution.stdout, '');
  }
});
