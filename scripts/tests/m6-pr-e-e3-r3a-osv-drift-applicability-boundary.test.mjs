import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import {
  canonical,
  evaluateEvidence,
  findPluginResolutionPaths,
  parseResolvedPluginReport,
  resolveExactHead,
  sha256,
  stable,
} from '../security/m6-pr-e-e3-r3a-review-osv-drift.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const contractPath = path.join(
  root,
  'docs/m6/m6-pr-e-e3-r3a-osv-drift-review.json',
);
const acceptancePath = path.join(
  root,
  'docs/m6/M6_PR_E_E3_R3A_OSV_DRIFT_APPLICABILITY.md',
);
const reviewerPath = path.join(
  root,
  'scripts/security/m6-pr-e-e3-r3a-review-osv-drift.mjs',
);
const aggregatePath = path.join(
  root,
  'scripts/tests/m6-pr-e-e3-r2b-workflow-supply-chain-remediation-boundary.test.mjs',
);

function text(file) {
  assert.equal(existsSync(file), true, `${file} must exist`);
  return readFileSync(file, 'utf8');
}

const contract = JSON.parse(text(contractPath));

function pluginFixture() {
  return `The following plugins have been resolved:\n\
   org.springframework.boot:spring-boot-maven-plugin:maven-plugin:4.0.2\n\
      org.springframework.boot:spring-boot-buildpack-platform:jar:4.0.2\n\
      org.apache.httpcomponents.client5:httpclient5:jar:5.5.2\n\
      org.apache.httpcomponents.core5:httpcore5-h2:jar:5.3.6\n\
      org.apache.httpcomponents.core5:httpcore5:jar:5.3.6\n\
   org.apache.maven.plugins:maven-surefire-plugin:maven-plugin:3.5.5\n\
      org.junit.platform:junit-platform-launcher:jar:1.13.4\n`;
}

test('R3A review contract is canonical, exact and non-authorizing', () => {
  const { contentSha256, ...payload } = contract;
  assert.equal(sha256(canonical(payload)), contentSha256);
  assert.equal(
    contentSha256,
    'aee05d7ad0452b2f3a29212afa367b96aff73fea1f9e5fd307dc1f236d440f6b',
  );
  assert.equal(
    contract.sourceMain,
    '779c4fbd09dcf17d45cc523e725222797cc5cb85',
  );
  assert.deepEqual(
    contract.findings.map((finding) => [
      finding.upstreamFindingId,
      finding.alias,
      finding.package.name,
      finding.package.version,
      finding.disposition,
    ]),
    [
      [
        'GHSA-x4m4-345f-5h5g',
        'CVE-2026-34487',
        'org.apache.tomcat.embed:tomcat-embed-core',
        '11.0.15',
        'NOT_APPLICABLE',
      ],
      [
        'GHSA-hf6x-8p5f-cgmf',
        'CVE-2026-54399',
        'org.apache.httpcomponents.core5:httpcore5',
        '5.3.6',
        'UNRESOLVED',
      ],
    ],
  );
  assert.deepEqual(contract.decision, {
    currentFindingCount: 147,
    reviewedFindingCountDelta: 2,
    cumulativeReviewedFindings: 12,
    cumulativeRemediatedFindings: 61,
    cumulativeNotApplicableFindings: 4,
    cumulativeUnresolvedFindings: 143,
    releaseBlocked: true,
    authoritativeInventoryComplete: false,
    prb16: 'OPEN',
    prb17: 'OPEN',
    issue97: 'OPEN',
    issue82: 'OPEN',
    issue62: 'OPEN',
    issue91: 'OPEN',
    readyAuthorized: false,
    mergeAuthorized: false,
    deploymentAuthorized: false,
    productionPromotionAuthorized: false,
    findingDeletionClaimed: false,
    suppressionAdded: false,
    exceptionAdded: false,
    severityDowngradeAdded: false,
  });
});

test('R3A parses exact Maven plugin ownership without inventing an edge tree', () => {
  const groups = parseResolvedPluginReport(pluginFixture());
  assert.equal(groups.length, 2);
  const paths = findPluginResolutionPaths(groups, {
    groupId: 'org.apache.httpcomponents.core5',
    artifactId: 'httpcore5',
    version: '5.3.6',
  });
  assert.deepEqual(paths.map((item) => ({
    semantics: item.semantics,
    owner: item.pluginOwner,
    target: item.target,
  })), [
    {
      semantics: 'MAVEN_RESOLVE_PLUGINS_OWNER_TO_RESOLVED_COMPONENT',
      owner: 'org.springframework.boot:spring-boot-maven-plugin:4.0.2',
      target: 'org.apache.httpcomponents.core5:httpcore5:5.3.6',
    },
  ]);
  assert.ok(paths[0].coResolvedComponents.includes(
    'org.springframework.boot:spring-boot-buildpack-platform:4.0.2',
  ));
  assert.ok(paths[0].coResolvedComponents.includes(
    'org.apache.httpcomponents.client5:httpclient5:5.5.2',
  ));
  assert.ok(paths[0].coResolvedComponents.includes(
    'org.apache.httpcomponents.core5:httpcore5-h2:5.3.6',
  ));
});

test('R3A exact-head resolution covers PR and natural push events', () => {
  const pullRequestHead = 'a'.repeat(40);
  const pushHead = 'b'.repeat(40);
  const fallback = 'c'.repeat(40);
  assert.equal(resolveExactHead(
    { pull_request: { head: { sha: pullRequestHead } }, after: pushHead },
    null,
    fallback,
    'true',
    null,
  ), pullRequestHead);
  assert.equal(resolveExactHead(
    { after: pushHead },
    null,
    fallback,
    'true',
    null,
  ), pushHead);
  assert.equal(resolveExactHead(
    { head_commit: { id: pushHead } },
    null,
    fallback,
    'true',
    null,
  ), pushHead);
  assert.throws(() => resolveExactHead(
    {}, null, null, 'true', null,
  ), /exact workflow head unavailable/);
});

test('R3A evidence requires positive Tomcat absence and retains httpcore unresolved', () => {
  const evidence = evaluateEvidence({
    contract,
    commitSha: 'd'.repeat(40),
    runtimeComponents: [
      {
        groupId: 'io.github.akaryc1b.approval',
        artifactId: 'approval-server',
        version: '0.1.0-SNAPSHOT',
        path: ['io.github.akaryc1b.approval:approval-server:0.1.0-SNAPSHOT'],
      },
      {
        groupId: 'org.apache.tomcat.embed',
        artifactId: 'tomcat-embed-core',
        version: '11.0.15',
        path: [
          'io.github.akaryc1b.approval:approval-server:0.1.0-SNAPSHOT',
          'org.springframework.boot:spring-boot-starter-tomcat:4.0.2',
          'org.apache.tomcat.embed:tomcat-embed-core:11.0.15',
        ],
      },
    ],
    jarEvidence: {
      jar: 'tomcat-embed-core-11.0.15.jar',
      entryCount: 1000,
      vulnerableCloudMembershipEntryCount: 0,
      vulnerableCloudMembershipEntries: [],
    },
    sourceMatches: [],
    pluginGroups: parseResolvedPluginReport(pluginFixture()),
  });
  assert.deepEqual(
    evidence.findings.map((finding) => [
      finding.upstreamFindingId,
      finding.disposition,
      finding.rationaleCode,
    ]),
    [
      [
        'GHSA-x4m4-345f-5h5g',
        'NOT_APPLICABLE',
        'VULNERABLE_CLOUD_MEMBERSHIP_CODE_NOT_PACKAGED_OR_CONFIGURED',
      ],
      [
        'GHSA-hf6x-8p5f-cgmf',
        'UNRESOLVED',
        'BUILD_PLUGIN_HTTP1_PARSE_PATH_REQUIRES_SEPARATE_REMEDIATION',
      ],
    ],
  );
  assert.equal(
    evidence.findings[1].evidence.remoteBuildResponsePathProvenUnreachable,
    false,
  );
  assert.equal(evidence.decision.releaseBlocked, true);
  assert.equal(evidence.decision.cumulativeUnresolvedFindings, 143);
  assert.match(evidence.contentSha256, /^[0-9a-f]{64}$/);

  assert.throws(() => evaluateEvidence({
    contract,
    commitSha: 'd'.repeat(40),
    runtimeComponents: [
      {
        groupId: 'org.apache.tomcat.embed',
        artifactId: 'tomcat-embed-core',
        version: '11.0.15',
        path: ['org.apache.tomcat.embed:tomcat-embed-core:11.0.15'],
      },
      {
        groupId: 'org.apache.tomcat',
        artifactId: 'tomcat-tribes',
        version: '11.0.15',
        path: ['org.apache.tomcat:tomcat-tribes:11.0.15'],
      },
    ],
    jarEvidence: {
      jar: 'tomcat-embed-core-11.0.15.jar',
      entryCount: 1000,
      vulnerableCloudMembershipEntryCount: 0,
      vulnerableCloudMembershipEntries: [],
    },
    sourceMatches: [],
    pluginGroups: parseResolvedPluginReport(pluginFixture()),
  }), /Tomcat tribes entered/);
});

test('R3A document and permanent aggregate preserve M6 closure blockers', () => {
  const document = text(acceptancePath);
  for (const marker of [
    'M6_PR_E_E3_R3A_REVIEW_DEFINED',
    'M6_PR_E_SECURITY_CLOSURE_NOT_ACCEPTED',
    'GHSA-x4m4-345f-5h5g = NOT_APPLICABLE',
    'GHSA-hf6x-8p5f-cgmf = UNRESOLVED',
    'PRB_16_REMAINS_OPEN',
    'PRB_17_REMAINS_OPEN',
    'ISSUE_97_REMAINS_OPEN',
    'ISSUE_82_REMAINS_OPEN',
    'ISSUE_62_REMAINS_OPEN',
    'NO_FINDING_DELETION',
    'NO_SUPPRESSION',
    'NO_EXCEPTION',
    'NO_SEVERITY_DOWNGRADE',
    'NO_READY',
    'NO_MERGE',
    'NO_DEPLOYMENT',
    'NO_PRODUCTION_PROMOTION',
   'AI_IS_NOT_AN_OPERATOR',
  ]) {
    assert.ok(document.includes(marker), marker);
  }
  assert.doesNotMatch(
    document,
    /PRB_16_(CLOSED|PASSED)|PRB_17_(CLOSED|PASSED)|ISSUE_97_(CLOSED|COMPLETED)|M6_PRODUCTION_READINESS_PASSED/,
   );
  const aggregate = text(aggregatePath);
  assert.ok(aggregate.includes(
    "import './m6-pr-e-e3-r3a-osv-drift-applicability-boundary.test.mjs';",
  ));
  assert.doesNotMatch(
    text(reviewerPath),
    /suppressionAdded:\s*true|exceptionAdded:\s*true|severityDowngradeAdded:\s*true|findingDeletionClaimed:\s*true/,
  );
});

test('R3A exact Maven and JAR evidence executes in GitHub Actions', {
  timeout: 900000,
}, () => {
  if (process.env.GITHUB_ACTIONS !== 'true') {
    return;
  }
  const result = spawnSync(process.execPath, [
    reviewerPath,
    `--root=${root}`,
    '--markers',
  ], {
    cwd: root,
    env: process.env,
    encoding: 'utf8',
    maxBuffer: 256 * 1024 * 1024,
    timeout: 900000,
  });
  assert.equal(result.status, 0, result.stderr || result.stdout);
  assert.match(result.stdout, /M6_PR_E_E3_R3A_CANONICAL_SHA256=[0-9a-f]{64}/);
  const match = result.stdout.match(
    /M6_PR_E_E3_R3A_REVIEW_BEGIN\n([^\n]+)\nM6_PR_E_E3_R3A_REVIEW_END/,
  );
  assert.ok(match, 'R3A canonical review evidence must be retained');
  const evidence = JSON.parse(match[1]);
  assert.match(evidence.commitSha, /^[0-9a-f]{40}$/);
  assert.equal(evidence.findings[0].disposition, 'NOT_APPLICABLE');
  assert.equal(
    evidence.findings[0].evidence.tomcatTribesRuntimeCount,
    0,
  );
  assert.equal(
    evidence.findings[0].evidence.jar
      .vulnerableCloudMembershipEntryCount,
    0,
  );
  assert.deepEqual(
    evidence.findings[0].evidence.firstPartyProductionMarkerMatches,
    [],
  );
  assert.equal(evidence.findings[1].disposition, 'UNRESOLVED');
  assert.deepEqual(
    evidence.findings[1].evidence.pluginResolutionPaths
      .map((item) => item.pluginOwner),
    ['org.springframework.boot:spring-boot-maven-plugin:4.0.2'],
  );
  assert.equal(evidence.decision.releaseBlocked, true);
  assert.equal(evidence.decision.issue97, 'OPEN');
});

test('R3A stable canonicalizer remains deterministic', () => {
  const value = { z: 1, a: { y: 2, x: [3, { b: 4, a: 5 }] } };
  assert.equal(
    canonical(value),
    '{"a":{"x":[3,{"a":5,"b":4}],"y":2},"z":1}',
  );
  assert.equal(
    createHash('sha256').update(canonical(value)).digest('hex'),
    sha256(canonical(stable(value))),
  );
});
