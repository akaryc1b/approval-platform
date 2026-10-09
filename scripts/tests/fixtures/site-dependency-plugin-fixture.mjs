// Synthetic scanner response for contract tests only. The E2 is an authentic
// precommit capture; no scan, finding absence or exact-source execution is claimed.
import { createHash } from 'node:crypto';
import { osvInputFromE2, buildOsvCoverage } from '../../security/osv-scan-coverage.mjs';
import { SITE_DEPENDENCY_PLUGIN_GRAPH } from '../../security/site-dependency-plugin-graph-transition.mjs';
export function syntheticSitePluginOsv(e2, source) {
  const input = osvInputFromE2(e2), inputPath = '/synthetic-site-plugin-input.json';
  const selected = [...input.lookup.values()].find(t => t.package.name === 'org.jsoup:jsoup' && t.package.version === '1.23.2');
  const advisory = 'GHSA-synthetic-site-plugin';
  const finding = { sourceClass: 'E4_OSV_SCANNER',
    findingId: createHash('sha256').update(['OSV', advisory, selected.package.ecosystem,
      selected.package.name, selected.package.version].join('\0')).digest('hex'),
    upstreamFindingId: advisory, aliases: [], package: selected.package,
    componentRefs: [...selected.componentRefs].sort(), scopes: [...selected.scopes].sort(),
    upstreamSeverity: [], fixedVersions: [] };
  const raw = { results: [{ source: { path: inputPath, type: 'lockfile' },
    packages: input.scannerInput.results[0].packages.map(({ package: identity }) => ({ package: identity,
      vulnerabilities: identity.name === selected.package.name && identity.version === selected.package.version
        ? [{ id: advisory }] : [] })) }] };
  const coverage = buildOsvCoverage(raw, e2, { head: e2.commitSha, graphDigest: SITE_DEPENDENCY_PLUGIN_GRAPH,
    inputBytes: JSON.stringify(input.scannerInput), inputPath, findings: [finding], exitStatus: 1,
    stderr: `Starting filesystem walk for root: /\nScanned ${inputPath} file and found ${input.packageCount} packages\nEnd status: 0 dirs visited, 1 inodes visited, 1 Extract calls, 1ms elapsed, 1ms wall time\n` });
  return { ...structuredClone(source), findings: [finding], findingCount: 1, coverage,
    inputPackageCount: input.packageCount, binarySha256: 'a'.repeat(64) };
}
