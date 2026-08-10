import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const contract = path.join(root, 'docs/m6/M6_PR_E_E2_DETERMINISTIC_DEPENDENCY_GRAPH_AND_SBOM.md');
const schema = path.join(root, 'docs/m6/m6-pr-e-e2-sbom.schema.json');
const actionBaseline = path.join(root, 'docs/m6/m6-pr-e-e2-action-resolution-baseline.json');
const generator = path.join(root, 'scripts/security/m6-pr-e-e2-generate-sbom.mjs');

function text(file) {
  assert.equal(existsSync(file), true, `${file} must exist`);
  return readFileSync(file, 'utf8');
}

test('E2 contract stays dependency-only and keeps later gates closed', () => {
  const body = text(contract);
  for (const marker of [
    'DEPENDENCY_GRAPH != VULNERABILITY_FINDING',
    'MAINTENANCE_PR != APPLICABLE_VULNERABILITY',
    'SBOM != REACHABILITY_ANALYSIS',
    'MUTABLE_ACTION_REF != IMMUTABLE_ACTION_IDENTITY',
    'NO_LOCKFILE != ZERO_DEPENDENCIES',
    'PRB_16_REMAINS_OPEN',
    'PRB_17_REMAINS_OPEN',
    'NO_READY',
    'NO_MERGE',
    'AI_IS_NOT_AN_OPERATOR',
  ]) assert.match(body, new RegExp(marker.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
  assert.doesNotMatch(body, /M6_PR_E_E3_ACCEPTED|M6_PR_E_E4_ACCEPTED|M6_PR_E_E6_ACCEPTED/);
});

test('E2 action baseline is exact and does not reinterpret mutable tags', () => {
  const baseline = JSON.parse(text(actionBaseline));
  assert.equal(baseline.sourceHead, '05b830864913b8213267e1eaf4c100d013ba880b');
  assert.equal(baseline.sourceRunId, 31367846138);
  assert.equal(baseline.workflowFiles.length, 9);
  assert.equal(baseline.workflowFiles.filter((item) => item.automatic).length, 1);
  for (const sha of Object.values(baseline.actionRefs)) assert.match(sha, /^[0-9a-f]{40}$/);
  assert.equal(baseline.interpretation.mutableMajorRefsRemainReleaseInputs, true);
  assert.equal(baseline.interpretation.maintenancePullRequestIsNotVulnerabilityFinding, true);
  assert.equal(baseline.maintenancePullRequests.length, 5);
  assert.deepEqual(
    baseline.maintenancePullRequests.map((entry) => entry.number),
    [1, 2, 4, 73, 94],
  );
  for (const entry of baseline.maintenancePullRequests) {
    assert.equal(entry.state, 'open');
    assert.equal(
      entry.classification,
      'MAINTENANCE_MAJOR_UPDATE_NOT_VULNERABILITY_FINDING',
    );
  }
});

test('E2 schema and generator freeze exact SHA, graph and redaction boundaries', () => {
  const parsed = JSON.parse(text(schema));
  assert.equal(parsed.properties.commitSha.pattern, '^[0-9a-f]{40}$');
  const source = text(generator);
  assert.match(source, /maven-dependency-plugin:3\.11\.0/);
  assert.match(source, /EXPECTED_REACTOR_PROJECTS = 26/);
  assert.match(source, /Expected 6 pnpm workspace projects/);
  assert.match(source, /ROOT_PNPM_LOCKFILE_ABSENT/);
  assert.match(source, /PACKAGE_TARBALL_INTEGRITY_NOT_REPOSITORY_PINNED/);
  assert.match(source, /PNPM_PACKAGE_METADATA/);
  assert.match(source, /sourceManifestBlobSha/);
  assert.match(source, /importedBoms/);
  assert.match(source, /workflow blob drift/);
  assert.match(source, /resolve-plugins/);
  assert.match(source, /-DoutputType=json/);
  assert.match(source, /-DappendOutput=true/);
  assert.doesNotMatch(source, /process\.env\.(?:TOKEN|SECRET|PASSWORD)|GITHUB_TOKEN/);
});

test('E2 full generator executes only in GitHub Actions and emits retained canonical evidence', { timeout: 600000 }, () => {
  if (process.env.GITHUB_ACTIONS !== 'true') return;
  const run = spawnSync(process.execPath, [generator, `--root=${root}`, '--full-maven', '--markers'], {
    cwd: root,
    encoding: 'utf8',
    maxBuffer: 160 * 1024 * 1024,
    env: process.env,
  });
  assert.equal(run.status, 0, run.stderr || run.stdout);
  assert.match(run.stdout, /M6_PR_E_E2_SBOM_BEGIN/);
  assert.match(run.stdout, /M6_PR_E_E2_SBOM_END/);
  const match = run.stdout.match(/M6_PR_E_E2_SBOM_BEGIN\n([^\n]+)\nM6_PR_E_E2_SBOM_END/);
  assert.ok(match, 'canonical E2 payload must be one retained line');
  const evidence = JSON.parse(match[1]);
  assert.match(evidence.commitSha, /^[0-9a-f]{40}$/);
  assert.equal(evidence.maven.reactorProjectCount, 26);
  assert.equal(evidence.maven.importedBoms.length, 3);
  assert.equal(evidence.pnpm.workspaceProjectCount, 6);
  assert.equal(evidence.pnpm.external.length, 1);
  assert.equal(evidence.pnpm.external[0].name, 'typescript');
  assert.equal(evidence.pnpm.external[0].version, '5.9.3');
  assert.equal(evidence.pnpm.external[0].license, 'Apache-2.0');
  assert.equal(evidence.githubActions.workflowCount, 9);
  assert.equal(evidence.githubActions.automaticWorkflowCount, 1);
  assert.equal(evidence.githubActions.maintenancePullRequests.length, 5);
  assert.ok(evidence.maven.components.length > 26);
  assert.ok(evidence.maven.edges.length > 0);
  assert.ok(evidence.maven.resolvedPluginCoordinates.length > 0);
  console.log('M6_PR_E_E2_CANONICAL_SHA256=' + evidence.contentSha256);
  console.log(match[0]);
});
