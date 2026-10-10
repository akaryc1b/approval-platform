#!/usr/bin/env node

import { createHash } from 'node:crypto';
import {
  existsSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
} from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { acceptedE2GraphProjection, generateEvidence as generateE2Evidence }
  from './m6-pr-e-e2-generate-sbom.mjs';
import { BASE_GRAPH, SERVER_DEPENDENCY_GRAPH, BUILD_PLUGIN_JACKSON_GRAPH, SITE_DEPENDENCY_PLUGIN_GRAPH, RELEASE_PLUGIN_GRAPH, CLEAN_PLUGIN_GRAPH, verifyCleanPluginDelta, verifyReleasePluginDelta, verifySiteDependencyPluginDelta, verifyBuildPluginJacksonDelta, verifyObservabilityGraph }
  from './observability-dependency-graph.mjs';
import { verifyScannerCheckout, requireScannerCheckoutUnchanged } from './m6-pr-e-e4-scan.mjs';

const CURRENT_TRANSITION_SHA256 = '6c093a36fbad3e87176f7e68b90ddf571db486b7b21025ab15dc744d3f20e209';

const SHA40 = /^[0-9a-f]{40}$/;
const SHA256 = /^[0-9a-f]{64}$/;
const DEPENDENCY_PLUGIN =
  'org.apache.maven.plugins:maven-dependency-plugin:3.11.0';
const TOMCAT_CLOUD_PREFIX =
  'org/apache/catalina/tribes/membership/cloud/';
const PRODUCTION_PREFIXES = [
  'apps/',
  'connector-adapters/',
  'engine-adapters/',
  'examples/',
  'host-sdks/',
  'integration-adapters/',
  'server-modules/',
];
const TEXT_EXTENSIONS = new Set([
  '.java',
  '.json',
  '.kts',
  '.properties',
  '.toml',
  '.xml',
  '.yaml',
  '.yml',
]);
const FORBIDDEN_PRODUCTION_MARKERS = [
  ['TRIBES_PACKAGE', /org\.apache\.catalina\.tribes/],
  ['KUBERNETES_MEMBERSHIP_PROVIDER', /KubernetesMembershipProvider/],
  ['TOKEN_STREAM_PROVIDER', /TokenStreamProvider/],
  ['ABSTRACT_STREAM_PROVIDER', /AbstractStreamProvider/],
  ['TOMCAT_TRIBES_ARTIFACT', /tomcat-tribes/],
  ['CLOUD_MEMBERSHIP_PATH', /membership[\\/]cloud/],
  ['SIMPLE_TCP_CLUSTER', /SimpleTcpCluster/],
  ['CLOUD_MEMBERSHIP_SERVICE', /CloudMembershipService/],
];

export function stable(value) {
  if (Array.isArray(value)) {
    return value.map(stable);
  }
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.keys(value)
        .sort()
        .map((key) => [key, stable(value[key])]),
    );
  }
  return value;
}

export function canonical(value) {
  return JSON.stringify(stable(value));
}

export function sha256(value) {
  return createHash('sha256').update(value).digest('hex');
}

function requireText(value, name) {
  const exact = String(value ?? '').trim();
  if (!exact) {
    throw new Error(`${name} must not be blank`);
  }
  return exact;
}

export function parseCoordinate(value) {
  const exact = requireText(value, 'coordinate');
  const parts = exact.split(':');
  if (parts.length !== 4 && parts.length !== 5) {
    throw new Error(`unsupported Maven coordinate: ${exact}`);
  }
  const [groupId, artifactId, type] = parts;
  const classifier = parts.length === 5 ? parts[3] : null;
  const version = parts.at(-1);
  for (const [name, item] of Object.entries({
    groupId,
    artifactId,
    type,
    version,
  })) {
    requireText(item, name);
  }
  return {
    coordinate: exact,
    groupId,
    artifactId,
    type,
    classifier,
    version,
    gav: `${groupId}:${artifactId}:${version}`,
  };
}

export function parseResolvedPluginReport(report) {
  const groups = [];
  let current = null;
  for (const line of String(report).split(/\r?\n/)) {
    const match = line.match(
      /^(\s+)([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+){1,2})\s*$/,
    );
    if (!match) {
      continue;
    }
    const indent = match[1].replaceAll('\t', '    ').length;
    const parsed = parseCoordinate(match[2]);
    if (indent <= 4) {
      current = {
        plugin: parsed,
        dependencies: [],
      };
      groups.push(current);
      continue;
    }
    if (!current) {
      throw new Error('plugin dependency appeared before its owner');
    }
    current.dependencies.push(parsed);
  }
  if (!groups.length) {
    throw new Error('no Maven plugin resolution groups were parsed');
  }
  return groups;
}

export function findPluginResolutionPaths(groups, target) {
  const exact = {
    groupId: requireText(target.groupId, 'target.groupId'),
    artifactId: requireText(target.artifactId, 'target.artifactId'),
    version: requireText(target.version, 'target.version'),
  };
  const paths = new Map();
  for (const group of groups) {
    const dependency = group.dependencies.find((item) =>
      item.groupId === exact.groupId
        && item.artifactId === exact.artifactId
        && item.version === exact.version);
    if (!dependency) {
      continue;
    }
    const key = `${group.plugin.gav}\u0000${dependency.gav}`;
    const current = paths.get(key) ?? {
      semantics: 'MAVEN_RESOLVE_PLUGINS_OWNER_TO_RESOLVED_COMPONENT',
      pluginOwner: group.plugin.gav,
      pluginCoordinate: group.plugin.coordinate,
      target: dependency.gav,
      targetCoordinate: dependency.coordinate,
      coResolvedComponents: new Set(),
    };
    for (const item of group.dependencies) {
      current.coResolvedComponents.add(item.gav);
    }
    paths.set(key, current);
  }
  return [...paths.values()]
    .map((item) => ({
      ...item,
      coResolvedComponents: [...item.coResolvedComponents].sort(),
    }))
    .sort((left, right) => left.pluginOwner.localeCompare(right.pluginOwner));
}

export function resolveExactHead(
  event,
  explicitHead,
  githubSha,
  githubActions,
  localHead,
) {
  const candidates = githubActions === 'true'
    ? [
        event?.pull_request?.head?.sha,
        event?.after,
        event?.head_commit?.id,
        explicitHead,
        githubSha,
      ]
    : [explicitHead, localHead];
  const head = candidates.find((candidate) =>
    SHA40.test(String(candidate ?? '')));
  if (!head) {
    throw new Error('R3A exact workflow head unavailable');
  }
  return head;
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd,
    env: options.env ?? process.env,
    encoding: 'utf8',
    maxBuffer: options.maxBuffer ?? 256 * 1024 * 1024,
    timeout: options.timeout ?? 600000,
  });
  if (result.error || result.status !== 0) {
    throw new Error(
      `${command} failed: ${result.error?.message ?? result.stderr ?? result.stdout}`,
    );
  }
  return result.stdout;
}

function safeEnvironment(extra = {}) {
  const environment = { ...process.env, ...extra };
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

function exactHead(root) {
  let event = null;
  if (process.env.GITHUB_EVENT_PATH
      && existsSync(process.env.GITHUB_EVENT_PATH)) {
    event = JSON.parse(readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
  }
  let localHead = null;
  if (process.env.GITHUB_ACTIONS !== 'true') {
    localHead = run('git', ['rev-parse', 'HEAD'], { cwd: root }).trim();
  }
  return resolveExactHead(
    event,
    process.env.M6_PR_E_E3_R3A_HEAD_SHA,
    process.env.GITHUB_SHA,
    process.env.GITHUB_ACTIONS,
    localHead,
  );
}

function jsonDocuments(text) {
  const values = [];
  let start = -1;
  let depth = 0;
  let quoted = false;
  let escaped = false;
  for (let index = 0; index < text.length; index += 1) {
    const character = text[index];
    if (start < 0) {
      if (character === '{') {
        start = index;
        depth = 1;
      }
      continue;
    }
    if (quoted) {
      if (escaped) {
        escaped = false;
      } else if (character === '\\') {
        escaped = true;
      } else if (character === '"') {
        quoted = false;
      }
      continue;
    }
    if (character === '"') {
      quoted = true;
    } else if (character === '{' || character === '[') {
      depth += 1;
    } else if (character === '}' || character === ']') {
      depth -= 1;
      if (depth === 0) {
        values.push(JSON.parse(text.slice(start, index + 1)));
        start = -1;
      }
    }
  }
  return values;
}

function flattenDependencyTree(root) {
  const components = [];
  const visit = (node, pathValue) => {
    const coordinate = `${node.groupId}:${node.artifactId}:${node.version}`;
    const nextPath = [...pathValue, coordinate];
    components.push({
      groupId: node.groupId,
      artifactId: node.artifactId,
      version: node.version,
      type: node.type ?? 'jar',
      scope: node.scope ?? null,
      path: nextPath,
    });
    for (const child of node.children ?? []) {
      visit(child, nextPath);
    }
  };
  visit(root, []);
  return components;
}

function serverRuntimeGraph(root, directory) {
  const output = path.join(directory, 'r3a-runtime-tree.json');
  run('mvn', [
    '-B',
    '-ntp',
    `${DEPENDENCY_PLUGIN}:tree`,
    '-Dscope=runtime',
    '-DoutputType=json',
    '-DappendOutput=true',
    `-DoutputFile=${output}`,
  ], {
    cwd: root,
    env: safeEnvironment(),
    timeout: 600000,
  });
  const roots = jsonDocuments(readFileSync(output, 'utf8'));
  const server = roots.find((item) =>
    item.groupId === 'io.github.akaryc1b.approval'
      && item.artifactId === 'approval-server');
  if (!server) {
    throw new Error('approval-server runtime dependency root was not resolved');
  }
  return flattenDependencyTree(server);
}

function pluginResolution(root, directory) {
  const output = path.join(directory, 'r3a-resolved-plugins.txt');
  run('mvn', [
    '-B',
    '-ntp',
    `${DEPENDENCY_PLUGIN}:resolve-plugins`,
    '-DappendOutput=true',
    `-DoutputFile=${output}`,
  ], {
    cwd: root,
    env: safeEnvironment(),
    timeout: 600000,
  });
  return readFileSync(output, 'utf8');
}

function jarEntryEvidence(version) {
  const repository = process.env.M6_PR_E_E3_R3A_JAR_REPOSITORY
    ?? process.env.M6_PR_E_E2_MAVEN_REPOSITORY
    ?? path.join(os.homedir(), '.m2', 'repository');
  const jar = path.join(
    repository,
    'org',
    'apache',
    'tomcat',
    'embed',
    'tomcat-embed-core',
    version,
    `tomcat-embed-core-${version}.jar`,
  );
  if (!existsSync(jar)) {
    throw new Error(`Tomcat embed-core JAR is unavailable: ${jar}`);
  }
  const entries = run('jar', ['tf', jar], { timeout: 120000 })
    .split(/\r?\n/)
    .filter(Boolean);
  const cloudEntries = entries.filter((entry) =>
    entry.startsWith(TOMCAT_CLOUD_PREFIX));
  return {
    jar: path.basename(jar),
    jarSha256: sha256(readFileSync(jar)),
    jarBytes: statSync(jar).size,
    entryCount: entries.length,
    vulnerableCloudMembershipEntryCount: cloudEntries.length,
    vulnerableCloudMembershipEntries: cloudEntries,
  };
}

function productionSourceMatches(root) {
  const files = run('git', ['ls-files', '-z'], { cwd: root })
    .split('\0')
    .filter(Boolean)
    .filter((file) => PRODUCTION_PREFIXES.some((prefix) =>
      file.startsWith(prefix)))
    .filter((file) => {
      const base = path.basename(file);
      return base === 'Dockerfile'
        || base === 'pom.xml'
        || TEXT_EXTENSIONS.has(path.extname(file));
    });
  const matches = [];
  for (const file of files) {
    const absolute = path.join(root, file);
    if (!existsSync(absolute) || statSync(absolute).size > 2 * 1024 * 1024) {
      continue;
    }
    const content = readFileSync(absolute, 'utf8');
    for (const [marker, expression] of FORBIDDEN_PRODUCTION_MARKERS) {
      if (expression.test(content)) {
        matches.push({ file, marker });
      }
    }
  }
  return matches.sort((left, right) =>
    `${left.file}:${left.marker}`.localeCompare(`${right.file}:${right.marker}`));
}

function readContract(root) {
  const file = path.join(
    root,
    'docs/m6/m6-pr-e-e3-r3a-osv-drift-review.json',
  );
  const contract = JSON.parse(readFileSync(file, 'utf8'));
  const { contentSha256, ...payload } = contract;
  if (!SHA256.test(contentSha256 ?? '')
      || sha256(canonical(payload)) !== contentSha256) {
    throw new Error('R3A review contract canonical hash mismatch');
  }
  return contract;
}

export function readCurrentSourceTransition(root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')) {
  const raw = readFileSync(path.join(root, 'docs/m6/m6-pr-e-e3-r3a-current-source-transition.json'));
  if (sha256(raw) !== CURRENT_TRANSITION_SHA256) throw new Error('R3A current transition file hash mismatch');
  const transition = JSON.parse(raw), { contentSha256, ...payload } = transition;
  if (sha256(canonical(payload)) !== contentSha256
    || transition.schemaVersion !== 'M6_PR_E_E3_R3A_CURRENT_SOURCE_TRANSITION_V1'
    || transition.repository !== 'akaryc1b/approval-platform'
    || transition.currentGraphDigest !== SERVER_DEPENDENCY_GRAPH
    || transition.decision.releaseBlocked !== true
    || transition.decision.currentTomcatFindingDispositionTransferred !== false
    || transition.decision.currentOsvTotalsClaimed !== false) throw new Error('R3A current transition identity drift');
  return transition;
}

/** Current source/JAR observations never relabel the retained 11.0.15 finding or old totals. */
export function evaluateCurrentEvidence({ contract, transition, commitSha, currentE2, checkout,
  runtimeComponents, jarEvidence, sourceMatches, pluginReport }) {
  const expected = readCurrentSourceTransition();
  if (canonical(transition) !== canonical(expected)) throw new Error('R3A supplied transition differs from pinned current contract');
  const { contentSha256, ...oldPayload } = contract;
  if (contract.repository !== transition.repository || contentSha256 !== transition.historicalContractContentSha256
    || sha256(canonical(oldPayload)) !== contentSha256) throw new Error('R3A historical review must remain exact');
  if (!SHA40.test(commitSha || '') || currentE2?.commitSha !== commitSha
    || checkout?.expectedHeadSha !== commitSha || checkout.exactTreeMatches !== true || checkout.trackedWorktreeClean !== true
    || !SHA40.test(checkout.checkedOutSha || '') || !SHA40.test(checkout.expectedHeadTreeSha || '')
    || checkout.checkedOutTreeSha !== checkout.expectedHeadTreeSha) throw new Error('R3A current source identity mismatch');
  const graphTransition = verifyObservabilityGraph(currentE2, acceptedE2GraphProjection(currentE2), BASE_GRAPH, commitSha);
  if (typeof pluginReport !== 'string') throw new Error('R3A raw current plugin report required');
  const pluginReportSha256 = sha256(pluginReport), pluginGroups = parseResolvedPluginReport(pluginReport);
  const subsequentPluginGraph = [BUILD_PLUGIN_JACKSON_GRAPH, SITE_DEPENDENCY_PLUGIN_GRAPH, RELEASE_PLUGIN_GRAPH, CLEAN_PLUGIN_GRAPH]
    .includes(graphTransition.currentE2GraphDigest);
  if (subsequentPluginGraph) {
    const current = acceptedE2GraphProjection(currentE2);
    const beforeClean = graphTransition.currentE2GraphDigest === CLEAN_PLUGIN_GRAPH ? verifyCleanPluginDelta(current) : current;
    const beforeRelease = [RELEASE_PLUGIN_GRAPH, CLEAN_PLUGIN_GRAPH].includes(graphTransition.currentE2GraphDigest)
      ? verifyReleasePluginDelta(beforeClean) : beforeClean;
    const prior = [SITE_DEPENDENCY_PLUGIN_GRAPH, RELEASE_PLUGIN_GRAPH, CLEAN_PLUGIN_GRAPH].includes(graphTransition.currentE2GraphDigest)
      ? verifySiteDependencyPluginDelta(beforeRelease) : beforeRelease;
    verifyBuildPluginJacksonDelta(prior);
  }
  if ((!subsequentPluginGraph && graphTransition.currentE2GraphDigest !== transition.currentGraphDigest)
    || pluginReportSha256 !== currentE2.maven.pluginResolutionSha256) throw new Error('R3A current Maven graph/plugin evidence mismatch');
  const tomcat = transition.tomcat, httpcore = transition.httpcore;
  for (const [target, historical] of [[tomcat, contract.findings[0]], [httpcore, contract.findings[1]]]) {
    if (target.historicalFindingId !== historical.findingId || target.upstreamFindingId !== historical.upstreamFindingId
      || target.alias !== historical.alias) throw new Error('R3A historical advisory identity drift');
  }
  const embeds = runtimeComponents.filter(item => item.groupId === tomcat.groupId && item.artifactId === tomcat.artifactId);
  const versions = [...new Set(embeds.map(item => item.version))];
  if (canonical(versions) !== canonical([tomcat.version]) || embeds.some(item => item.type !== 'jar'
    || !['compile', 'runtime'].includes(item.scope) || item.path?.[0] !== 'io.github.akaryc1b.approval:approval-server:0.1.0-SNAPSHOT'
    || item.path?.at(-1) !== `${tomcat.groupId}:${tomcat.artifactId}:${tomcat.version}`)) {
    throw new Error('R3A exact current Tomcat runtime component/path mismatch');
  }
  const edges = new Set(currentE2.maven.edges.map(edge => `${edge.from}\0${edge.to}`));
  const components = new Set(currentE2.maven.components.map(item => item.bomRef));
  for (const item of embeds) {
    const refs = item.path.map(coordinate => {
      const [group, name, version, ...extra] = coordinate.split(':');
      if (!group || !name || !version || extra.length) throw new Error('R3A runtime path coordinate malformed');
      return `pkg:maven/${group}/${name}@${version}?type=jar`;
    });
    if (refs.some(ref => !components.has(ref)) || refs.slice(1).some((ref, index) => !edges.has(`${refs[index]}\0${ref}`))) {
      throw new Error('R3A runtime path edge is absent from admitted current E2');
    }
  }
  if (runtimeComponents.some(item => item.groupId === 'org.apache.tomcat' && item.artifactId === 'tomcat-tribes')) {
    throw new Error('Tomcat tribes entered the executable runtime graph');
  }
  if (jarEvidence.jar !== tomcat.jarFileName || jarEvidence.jarSha256 !== tomcat.jarSha256
    || jarEvidence.jarBytes !== tomcat.jarBytes || !Number.isSafeInteger(jarEvidence.entryCount) || jarEvidence.entryCount < 1
    || jarEvidence.vulnerableCloudMembershipEntryCount !== 0
    || canonical(jarEvidence.vulnerableCloudMembershipEntries) !== '[]') throw new Error('R3A exact current Tomcat JAR/cloud evidence mismatch');
  if (!Array.isArray(sourceMatches) || sourceMatches.length) throw new Error('R3A current Tomcat source/config drift');
  const pluginPaths = findPluginResolutionPaths(pluginGroups, httpcore);
  requireExactSet(pluginPaths.map(item => item.pluginOwner), httpcore.expectedPluginOwners, 'current httpcore5 plugin owner set');
  const owner = pluginPaths.find(item => item.pluginOwner === httpcore.expectedPluginOwners[0]);
  requireExactSet(httpcore.requiredCoResolvedComponents,
    httpcore.requiredCoResolvedComponents.filter(component => owner.coResolvedComponents.includes(component)),
    'current httpcore5 co-resolved component set');
  const historical = contract.findings[1];
  const payload = {
    schemaVersion: 'M6_PR_E_E3_R3A_OSV_DRIFT_EVIDENCE_V2', repository: transition.repository, commitSha, checkout,
    sourceE2ContentSha256: currentE2.contentSha256, currentE2GraphDigest: graphTransition.currentE2GraphDigest,
    ...(subsequentPluginGraph ? { preservedCurrentSourceGraphDigest: transition.currentGraphDigest } : {}),
    currentGraphTransition: graphTransition, currentTransitionContentSha256: transition.contentSha256,
    currentTransitionFileSha256: CURRENT_TRANSITION_SHA256,
    historicalReview: { sourceMain: contract.sourceMain, sourceRun: contract.sourceRun,
      contractSha256: contract.contentSha256, findings: contract.findings, decision: contract.decision },
    currentTomcatObservation: { package: { ecosystem: 'Maven', name: `${tomcat.groupId}:${tomcat.artifactId}`, version: tomcat.version },
      historicalFindingId: tomcat.historicalFindingId, upstreamFindingId: tomcat.upstreamFindingId, alias: tomcat.alias,
      currentFindingPresenceClaimed: false, dispositionTransferred: false,
      evidenceStatus: 'CURRENT_CODE_AND_CONFIGURATION_REVALIDATED_WITHOUT_FINDING_DISPOSITION',
      runtimeDependencyPath: embeds[0].path, tomcatTribesRuntimeCount: 0, jar: jarEvidence,
      firstPartyProductionMarkerMatches: sourceMatches },
    findings: [{ findingId: httpcore.historicalFindingId, upstreamFindingId: httpcore.upstreamFindingId,
      alias: httpcore.alias, severity: historical.severity, package: historical.package, disposition: 'UNRESOLVED',
      rationaleCode: 'BUILD_PLUGIN_HTTP1_PARSE_PATH_REQUIRES_SEPARATE_REMEDIATION',
      evidence: { pluginResolutionPaths: pluginPaths, pluginReportSha256,
        remoteBuildResponsePathProvenUnreachable: false, revalidationTriggers: historical.revalidationTriggers } }],
    decision: transition.decision,
  };
  return stable({ ...payload, contentSha256: sha256(canonical(payload)) });
}

function requireExactSet(actual, expected, boundary) {
  const left = [...new Set(actual)].sort();
  const right = [...new Set(expected)].sort();
  if (canonical(left) !== canonical(right)) {
    throw new Error(`${boundary} mismatch: ${canonical(left)}`);
  }
}

export function evaluateEvidence({
  contract,
  commitSha,
  runtimeComponents,
  jarEvidence,
  sourceMatches,
  pluginGroups,
}) {
  const tomcat = contract.findings.find((item) =>
    item.upstreamFindingId === 'GHSA-x4m4-345f-5h5g');
  const httpcore = contract.findings.find((item) =>
    item.upstreamFindingId === 'GHSA-hf6x-8p5f-cgmf');
  if (!tomcat || !httpcore) {
    throw new Error('R3A finding contract is incomplete');
  }

  const embedMatches = runtimeComponents.filter((item) =>
    item.groupId === 'org.apache.tomcat.embed'
      && item.artifactId === 'tomcat-embed-core'
      && item.version === tomcat.package.version);
  const embed = [...new Map(embedMatches.map((item) => [
    `${item.groupId}:${item.artifactId}:${item.version}`,
    item,
  ])).values()];
  const tribes = runtimeComponents.filter((item) =>
    item.groupId === 'org.apache.tomcat'
      && item.artifactId === 'tomcat-tribes');
  if (embed.length !== 1) {
    throw new Error('exact Tomcat embed-core runtime component is missing');
  }
  if (tribes.length !== 0) {
    throw new Error('Tomcat tribes entered the executable runtime graph');
  }
  if (jarEvidence.vulnerableCloudMembershipEntryCount !== 0) {
    throw new Error('vulnerable Tomcat cloud membership code is packaged');
  }
  if (sourceMatches.length !== 0) {
    throw new Error(`Tomcat cloud membership source/config drift: ${canonical(sourceMatches)}`);
  }

  const pluginPaths = findPluginResolutionPaths(pluginGroups, {
    groupId: 'org.apache.httpcomponents.core5',
    artifactId: 'httpcore5',
    version: httpcore.package.version,
  });
  requireExactSet(
    pluginPaths.map((item) => item.pluginOwner),
    httpcore.expectedPluginOwners,
    'httpcore5 plugin owner set',
  );
  const ownerPath = pluginPaths.find((item) =>
    item.pluginOwner === httpcore.expectedPluginOwners[0]);
  requireExactSet(
    httpcore.requiredCoResolvedComponents,
    httpcore.requiredCoResolvedComponents.filter((component) =>
      ownerPath.coResolvedComponents.includes(component)),
    'httpcore5 co-resolved component set',
  );

  const findings = [
    {
      findingId: tomcat.findingId,
      upstreamFindingId: tomcat.upstreamFindingId,
      alias: tomcat.alias,
      severity: tomcat.severity,
      package: tomcat.package,
      disposition: 'NOT_APPLICABLE',
      rationaleCode:
        'VULNERABLE_CLOUD_MEMBERSHIP_CODE_NOT_PACKAGED_OR_CONFIGURED',
      evidence: {
        runtimeDependencyPath: embed[0].path,
        tomcatTribesRuntimeCount: tribes.length,
        jar: jarEvidence,
        firstPartyProductionMarkerMatches: sourceMatches,
        revalidationTriggers: tomcat.revalidationTriggers,
      },
    },
    {
      findingId: httpcore.findingId,
      upstreamFindingId: httpcore.upstreamFindingId,
      alias: httpcore.alias,
      severity: httpcore.severity,
      package: httpcore.package,
      disposition: 'UNRESOLVED',
      rationaleCode:
        'BUILD_PLUGIN_HTTP1_PARSE_PATH_REQUIRES_SEPARATE_REMEDIATION',
      evidence: {
        pluginResolutionPaths: pluginPaths.map((item) => ({
          semantics: item.semantics,
          pluginOwner: item.pluginOwner,
          pluginCoordinate: item.pluginCoordinate,
          target: item.target,
          targetCoordinate: item.targetCoordinate,
          requiredCoResolvedComponents:
            httpcore.requiredCoResolvedComponents,
        })),
        remoteBuildResponsePathProvenUnreachable: false,
        revalidationTriggers: httpcore.revalidationTriggers,
      },
    },
  ];

  const payload = {
    schemaVersion: 'M6_PR_E_E3_R3A_OSV_DRIFT_EVIDENCE_V1',
    repository: contract.repository,
    commitSha,
    sourceMain: contract.sourceMain,
    sourceRun: contract.sourceRun,
    contractSha256: contract.contentSha256,
    findings,
    decision: contract.decision,
  };
  return stable({
    ...payload,
    contentSha256: sha256(canonical(payload)),
  });
}

export function collectCurrentEvidence(root) {
  const directory = mkdtempSync(path.join(os.tmpdir(), 'm6-pr-e-r3a-'));
  try {
    const commitSha = exactHead(root), checkout = verifyScannerCheckout(root, commitSha);
    const transition = readCurrentSourceTransition(root), contract = readContract(root);
    if (sha256(readFileSync(path.join(root, 'docs/m6/m6-pr-e-e3-r3a-osv-drift-review.json')))
      !== transition.historicalContractFileSha256) throw new Error('R3A retained historical file drift');
    const currentE2 = generateE2Evidence(root, { fullMaven: true });
    const runtimeComponents = serverRuntimeGraph(root, directory);
    const pluginReport = pluginResolution(root, directory);
    const evidence = evaluateCurrentEvidence({ contract, transition, commitSha, currentE2, checkout, runtimeComponents,
      jarEvidence: jarEntryEvidence(transition.tomcat.version), sourceMatches: productionSourceMatches(root),
      pluginReport });
    requireScannerCheckoutUnchanged(root, commitSha, checkout);
    return evidence;
  } finally { rmSync(directory, { recursive: true, force: true }); }
}

function main() {
  const rootArgument = process.argv.find((argument) =>
    argument.startsWith('--root='));
  const root = rootArgument
    ? path.resolve(rootArgument.slice('--root='.length))
    : path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
    const evidence = collectCurrentEvidence(root);
    if (process.argv.includes('--markers')) {
      console.log(`M6_PR_E_E3_R3A_CANONICAL_SHA256=${evidence.contentSha256}`);
      console.log('M6_PR_E_E3_R3A_REVIEW_BEGIN');
      console.log(JSON.stringify(evidence));
      console.log('M6_PR_E_E3_R3A_REVIEW_END');
    } else {
      console.log(JSON.stringify(evidence, null, 2));
    }
}

const invoked = process.argv[1]
  && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (invoked) {
  main();
}
