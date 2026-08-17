import assert from 'node:assert/strict';
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  rmSync,
  statSync,
} from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const DEPENDENCY_PLUGIN =
  'org.apache.maven.plugins:maven-dependency-plugin:3.11.0';
const TOMCAT_VERSION = '11.0.15';
const TOMCAT_ARTIFACT =
  `org.apache.tomcat.embed:tomcat-embed-core:${TOMCAT_VERSION}:jar`;
const TOMCAT_CLOUD_PREFIX =
  'org/apache/catalina/tribes/membership/cloud/';
const root = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '../..',
);

export function exactTomcatJar(repository) {
  return path.join(
    repository,
    'org',
    'apache',
    'tomcat',
    'embed',
    'tomcat-embed-core',
    TOMCAT_VERSION,
    `tomcat-embed-core-${TOMCAT_VERSION}.jar`,
  );
}

function safeEnvironment() {
  const environment = { ...process.env };
  for (const name of [
    'GH_TOKEN',
    'GITHUB_TOKEN',
    'SEMGREP_APP_TOKEN',
    'ZIZMOR_GITHUB_TOKEN',
  ]) {
    delete environment[name];
  }
  return environment;
}

export function materializeExactTomcatJar(repositoryRoot) {
  const jar = exactTomcatJar(repositoryRoot);
  const outputDirectory = path.dirname(jar);
  mkdirSync(outputDirectory, { recursive: true });

  const result = spawnSync('mvn', [
    '-B',
    '-ntp',
    '-N',
    `${DEPENDENCY_PLUGIN}:copy`,
    `-Dartifact=${TOMCAT_ARTIFACT}`,
    `-DoutputDirectory=${outputDirectory}`,
    '-Dmdep.stripVersion=false',
  ], {
    cwd: root,
    env: safeEnvironment(),
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
    timeout: 600000,
  });

  if (result.error || result.status !== 0) {
    throw new Error(
      `exact Tomcat JAR materialization failed: ${
        result.error?.message ?? result.stderr ?? result.stdout
      }`,
    );
  }
  if (!existsSync(jar) || !statSync(jar).isFile()) {
    throw new Error(`exact Tomcat JAR was not materialized: ${jar}`);
  }
  return jar;
}

let materializedRepository = null;
let materializedJar = null;
let cleanupRegistered = false;

function cleanupMaterializedRepository() {
  if (materializedRepository !== null) {
    rmSync(materializedRepository, { recursive: true, force: true });
  }
}

export function ensureExactTomcatRepository() {
  if (materializedRepository !== null) return materializedRepository;

  const repository = mkdtempSync(
    path.join(os.tmpdir(), 'm6-pr-e-r3a-artifact-'),
  );
  try {
    const jar = materializeExactTomcatJar(repository);
    materializedRepository = repository;
    materializedJar = jar;
    if (!cleanupRegistered) {
      process.once('exit', cleanupMaterializedRepository);
      cleanupRegistered = true;
    }
    return materializedRepository;
  } catch (error) {
    rmSync(repository, { recursive: true, force: true });
    throw error;
  }
}

test('R3A CI materializes the exact Tomcat JAR without parent environment leakage', {
  timeout: 600000,
}, () => {
  if (process.env.GITHUB_ACTIONS !== 'true') return;

  const inheritedRepository =
    process.env.M6_PR_E_E2_MAVEN_REPOSITORY;
  const repository = ensureExactTomcatRepository();

  assert.equal(
    process.env.M6_PR_E_E2_MAVEN_REPOSITORY,
    inheritedRepository,
  );
  assert.equal(materializedJar, exactTomcatJar(repository));
  assert.equal(path.basename(materializedJar),
    'tomcat-embed-core-11.0.15.jar');
  assert.equal(existsSync(materializedJar), true);

  const listing = spawnSync('jar', ['tf', materializedJar], {
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
    timeout: 120000,
  });
  assert.equal(listing.status, 0, listing.stderr || listing.stdout);
  const entries = listing.stdout.split(/\r?\n/).filter(Boolean);
  assert.ok(entries.length > 0, 'materialized Tomcat JAR must contain entries');
  assert.equal(
    entries.filter((entry) => entry.startsWith(TOMCAT_CLOUD_PREFIX)).length,
    0,
  );
});
