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
      + 'mvn -B -ntp -Pproduct-readiness-demo '
      + '-Drevision=0.1.0-SNAPSHOT '
      + '-pl :approval-server spring-boot:run '
      + '-Dspring-boot.run.profiles=local',
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
