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
  return parseResolvedPluginReport(readFileSync(output, 'utf8'));
}

function jarEntryEvidence(version) {
  const repository = process.env.M6_PR_E_E2_MAVEN_REPOSITORY
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

function main() {
  const rootArgument = process.argv.find((argument) =>
    argument.startsWith('--root='));
  const root = rootArgument
    ? path.resolve(rootArgument.slice('--root='.length))
    : path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
  const directory = mkdtempSync(path.join(os.tmpdir(), 'm6-pr-e-r3a-'));
  try {
    const contract = readContract(root);
    const runtimeComponents = serverRuntimeGraph(root, directory);
    const pluginGroups = pluginResolution(root, directory);
    const evidence = evaluateEvidence({
      contract,
      commitSha: exactHead(root),
      runtimeComponents,
      jarEvidence: jarEntryEvidence('11.0.15'),
      sourceMatches: productionSourceMatches(root),
      pluginGroups,
    });
    if (process.argv.includes('--markers')) {
      console.log(`M6_PR_E_E3_R3A_CANONICAL_SHA256=${evidence.contentSha256}`);
      console.log('M6_PR_E_E3_R3A_REVIEW_BEGIN');
      console.log(JSON.stringify(evidence));
      console.log('M6_PR_E_E3_R3A_REVIEW_END');
    } else {
      console.log(JSON.stringify(evidence, null, 2));
    }
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}

const invoked = process.argv[1]
  && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (invoked) {
  main();
}
