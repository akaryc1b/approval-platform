import { createHash } from 'node:crypto';
import { acceptedE2GraphProjection } from './m6-pr-e-e2-generate-sbom.mjs';
import { requireOsvReport } from './scanner-report-structure.mjs';

const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const canonical = value => JSON.stringify(stable(value));
const hash = value => createHash('sha256').update(value).digest('hex');
const requireValue = (condition, message) => { if (!condition) throw new Error(message); };
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const text = value => typeof value === 'string' && value.length > 0 && value.length <= 4096 && !/[\x00-\x1f\x7f]/.test(value);
const packageKey = value => `${value.ecosystem}\0${value.name}\0${value.version}`;
const sortPackages = (left, right) => `${left.package.ecosystem}:${left.package.name}:${left.package.version}`
  .localeCompare(`${right.package.ecosystem}:${right.package.name}:${right.package.version}`);
const keys = (value, allowed, label) => requireValue(object(value) && Object.keys(value).every(key => allowed.includes(key)),
  `OSV unexpected ${label} field or structure`);
const strings = (value, label) => {
  requireValue(Array.isArray(value) && value.every(text) && new Set(value).size === value.length,
    `OSV ${label} must contain unique nonempty strings`);
  return [...value].sort();
};
const packageIdentity = value => {
  keys(value, ['ecosystem', 'name', 'version'], 'package');
  requireValue(['Maven', 'npm'].includes(value.ecosystem) && text(value.name) && text(value.version),
    'OSV exact package/ecosystem/version required');
  return { ecosystem: value.ecosystem, name: value.name, version: value.version };
};

function pluginPackage(coord) {
  const parts = coord.split(':');
  if (parts.length < 4) return null;
  return { name: `${parts[0]}:${parts[1]}`, version: parts.at(-1), ecosystem: 'Maven', sourceClass: 'BUILD_PLUGIN' };
}

/** The existing E4 query construction, including its insertion order and exact JSON bytes. */
export function osvInputFromE2(e2) {
  const map = new Map();
  const add = (name, version, ecosystem, componentRef, scope) => {
    if (!name || !version) return;
    const key = `${ecosystem}\0${name}\0${version}`;
    if (!map.has(key)) map.set(key, { package: { name, version, ecosystem }, componentRefs: [], scopes: [] });
    const value = map.get(key);
    if (componentRef && !value.componentRefs.includes(componentRef)) value.componentRefs.push(componentRef);
    if (scope && !value.scopes.includes(scope)) value.scopes.push(scope);
  };
  for (const component of e2.maven.components || []) if (component.group !== 'io.github.akaryc1b.approval')
    add(`${component.group}:${component.name}`, component.version, 'Maven', component.bomRef, component.scope || 'dependency');
  for (const bom of e2.maven.importedBoms || [])
    add(`${bom.group}:${bom.name}`, bom.version, 'Maven', `pkg:maven/${bom.group}/${bom.name}@${bom.version}?type=pom`, 'import');
  for (const coordinate of e2.maven.resolvedPluginCoordinates || []) {
    const plugin = pluginPackage(coordinate);
    if (plugin) add(plugin.name, plugin.version, plugin.ecosystem, `maven-plugin:${coordinate}`, 'build-plugin');
  }
  for (const dependency of e2.pnpm.external || [])
    add(dependency.name, dependency.version, 'npm', `pkg:npm/${encodeURIComponent(dependency.name)}@${dependency.version}`, dependency.scope || 'unknown');
  const packages = [...map.values()].sort(sortPackages);
  return { scannerInput: { results: [{ packages: packages.map(value => ({ package: value.package })) }] },
    lookup: map, packageCount: packages.length };
}

function currentInput(e2, { head, graphDigest } = {}) {
  requireValue(/^[0-9a-f]{40}$/.test(head || '') && e2?.commitSha === head,
    'OSV independent expected Head mismatch');
  requireValue(e2.schemaVersion === 'M6_PR_E_E2_SBOM_V1' && e2.repository === 'akaryc1b/approval-platform',
    'OSV current E2 identity mismatch');
  const { contentSha256, ...payload } = e2;
  requireValue(/^[0-9a-f]{64}$/.test(contentSha256 || '') && hash(canonical(payload)) === contentSha256,
    'OSV current E2 canonical digest mismatch');
  requireValue(/^[0-9a-f]{64}$/.test(graphDigest || '') && hash(canonical(acceptedE2GraphProjection(e2))) === graphDigest,
    'OSV current graph digest mismatch');
  requireValue(Array.isArray(e2.maven?.components) && Array.isArray(e2.maven.importedBoms)
    && Array.isArray(e2.maven.resolvedPluginCoordinates) && Array.isArray(e2.pnpm?.external),
  'OSV fully resolved current E2 package inventories required');
  const input = osvInputFromE2(e2);
  requireValue(input.packageCount > 0, 'OSV exact input target inventory empty');
  const targets = [...input.lookup.values()].sort(sortPackages).map(target => ({
    package: packageIdentity(target.package), componentRefs: strings(target.componentRefs, 'component references'),
    scopes: strings(target.scopes, 'scopes'),
  }));
  requireValue(targets.every(target => target.componentRefs.length > 0 && target.scopes.length > 0),
    'OSV input target component references and scopes required');
  return { ...input, targets, bytes: Buffer.from(JSON.stringify(input.scannerInput)) };
}

/** OSV 2.5.0 may log scan/filter failures outside its JSON. Accept only this invocation's complete diagnostics. */
export function requireOsvExecution({ stderr, exitStatus, inputPath, packageCount, findingCount }) {
  requireValue(text(inputPath) && typeof stderr === 'string', 'OSV actual input path and diagnostics required');
  const lines = stderr.trim().split(/\r?\n/);
  requireValue(lines.length === 3 && lines[0] === 'Starting filesystem walk for root: /'
    && lines[1] === `Scanned ${inputPath} file and found ${packageCount} packages`
    && /^End status: 0 dirs visited, 1 inodes visited, 1 Extract calls, [0-9.a-zµμ]+ elapsed, [0-9.a-zµμ]+ wall time$/.test(lines[2]),
  'OSV scan errors, ignored/unscanned targets, filtering, or unexpected diagnostics');
  requireValue(exitStatus === (findingCount > 0 ? 1 : 0), 'OSV exit status inconsistent with reported findings');
}

function rawTargets(raw, input, inputPath) {
  requireOsvReport(raw);
  // Pinned JSON envelope: fail closed on errors, ignored, skipped, unscanned,
  // analysis/filter metadata, or another output mode, even when packages exist.
  keys(raw, ['results', 'experimental_config'], 'report');
  requireValue(Array.isArray(raw.results) && raw.results.length === 1, 'OSV exact input source required');
  if (Object.hasOwn(raw, 'experimental_config')) {
    requireValue(canonical(raw.experimental_config) === canonical({ licenses: { summary: false, allowlist: null } }),
      'OSV unexpected experimental analysis configuration');
  }
  const result = raw.results[0];
  keys(result, ['source', 'packages'], 'source result');
  keys(result.source, ['path', 'type'], 'source');
  requireValue(result.source.path === inputPath && result.source.type === 'lockfile', 'OSV foreign input source');
  requireValue(Array.isArray(result.packages), 'OSV complete package array required');
  const seen = new Set();
  const reported = result.packages.map(entry => {
    keys(entry, ['package', 'vulnerabilities', 'groups'], 'package result');
    const identity = packageIdentity(entry.package), key = packageKey(identity), target = input.lookup.get(key);
    requireValue(target, 'OSV reported package is outside exact input targets');
    requireValue(!seen.has(key), 'OSV duplicate reported input package');
    seen.add(key);
    const vulnerabilities = entry.vulnerabilities || [];
    const advisoryIds = strings(vulnerabilities.map(vulnerability => {
      keys(vulnerability, ['schema_version', 'id', 'modified', 'published', 'withdrawn', 'aliases', 'related',
        'summary', 'details', 'affected', 'references', 'severity', 'credits', 'database_specific', 'upstream'], 'vulnerability');
      if (Object.hasOwn(vulnerability, 'affected')) requireValue(Array.isArray(vulnerability.affected)
        && vulnerability.affected.some(affected => affected.package?.ecosystem === identity.ecosystem
          && affected.package?.name === identity.name), 'OSV finding affected package differs from input target');
      return vulnerability.id;
    }), 'reported advisory IDs');
    if (Object.hasOwn(entry, 'groups')) {
      requireValue(Array.isArray(entry.groups), 'OSV vulnerability groups array required');
      const grouped = strings(entry.groups.flatMap(group => {
        keys(group, ['ids', 'aliases', 'max_severity'], 'vulnerability group');
        return strings(group.ids, 'group advisory IDs');
      }), 'grouped advisory IDs');
      requireValue(canonical(grouped) === canonical(advisoryIds), 'OSV grouped advisory inventory mismatch');
    }
    return { package: identity, componentRefs: strings(target.componentRefs, 'component references'),
      scopes: strings(target.scopes, 'scopes'), advisoryIds };
  }).sort(sortPackages);
  requireValue(seen.size === input.packageCount, 'OSV missing input package, including non-finding/plugin targets');
  return reported;
}

function requireFindings(findings, targets) {
  requireValue(Array.isArray(findings), 'OSV normalized findings array required');
  const byPackage = new Map(targets.map(target => [packageKey(target.package), target]));
  const seen = new Set();
  for (const finding of findings) {
    requireValue(object(finding) && finding.sourceClass === 'E4_OSV_SCANNER' && text(finding.upstreamFindingId),
      'OSV normalized finding identity required');
    const identity = packageIdentity(finding.package), target = byPackage.get(packageKey(identity));
    const id = hash(['OSV', finding.upstreamFindingId, identity.ecosystem, identity.name, identity.version].join('\0'));
    requireValue(target && finding.findingId === id && target.advisoryIds.includes(finding.upstreamFindingId),
      'OSV normalized finding outside reported target/advisory inventory');
    requireValue(!seen.has(id), 'OSV duplicate normalized finding');
    seen.add(id);
    requireValue(canonical(finding.componentRefs) === canonical(target.componentRefs)
      && canonical(finding.scopes) === canonical(target.scopes), 'OSV normalized finding component/scope drift');
  }
  requireValue(seen.size === targets.reduce((count, target) => count + target.advisoryIds.length, 0),
    'OSV normalized findings omit reported advisory');
}

function coveragePayload(targets, findings, e2, input, { head, graphDigest }) {
  requireFindings(findings, targets);
  return {
    schemaVersion: 'M6_PR_E_E4_OSV_TARGET_COVERAGE_V1', repository: e2.repository, commitSha: head,
    e2CurrentContentSha256: e2.contentSha256, e2GraphDigest: graphDigest,
    inputPackageCount: input.packageCount, reportedPackageCount: targets.length,
    findingPackageCount: targets.filter(target => target.advisoryIds.length > 0).length,
    findingCount: findings.length, inputTargetSha256: hash(canonical(input.targets)),
    inputBytesSha256: hash(input.bytes), normalizedFindingsSha256: hash(canonical(findings)),
    targets, allInputPackagesReported: true, rawReportRetained: false,
  };
}

/** Build only from the actual complete raw report, actual input bytes, and pinned CLI execution. */
export function buildOsvCoverage(raw, e2, options) {
  const input = currentInput(e2, options);
  requireValue((typeof options.inputBytes === 'string' || Buffer.isBuffer(options.inputBytes))
    && Buffer.from(options.inputBytes).equals(input.bytes), 'OSV actual input bytes differ from exact current E2 input');
  const targets = rawTargets(raw, input, options.inputPath);
  requireOsvExecution({ ...options, packageCount: input.packageCount,
    findingCount: targets.reduce((count, target) => count + target.advisoryIds.length, 0) });
  const payload = coveragePayload(targets, options.findings, e2, input, options);
  return stable({ ...payload, contentSha256: hash(canonical(payload)) });
}

/**
 * Recheck coverage against independently supplied current E2/Head/graph and E4 findings.
 * This binds evidence; a self-hash does not authenticate a scanner execution. Callers
 * must separately verify the current E4 workflow/source and admit the graph.
 */
export function verifyOsvCoverage(coverage, findings, e2, options) {
  const input = currentInput(e2, options);
  requireValue(object(coverage) && Array.isArray(coverage.targets), 'OSV complete target coverage required');
  const targets = coverage.targets.map(target => {
    keys(target, ['package', 'componentRefs', 'scopes', 'advisoryIds'], 'coverage target');
    return { package: packageIdentity(target.package), componentRefs: strings(target.componentRefs, 'component references'),
      scopes: strings(target.scopes, 'scopes'), advisoryIds: strings(target.advisoryIds, 'coverage advisory IDs') };
  });
  const projected = targets.map(({ advisoryIds, ...target }) => target);
  requireValue(canonical(projected) === canonical(input.targets), 'OSV coverage differs from exact current input target/ref/scope inventory');
  const payload = coveragePayload(targets, findings, e2, input, options);
  requireValue(canonical(coverage) === canonical({ ...payload, contentSha256: hash(canonical(payload)) }),
    'OSV coverage identity/count/hash/finding binding mismatch');
  return coverage;
}
