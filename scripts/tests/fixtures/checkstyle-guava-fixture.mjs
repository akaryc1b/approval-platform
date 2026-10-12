// SYNTHETIC UNIT FIXTURE ONLY. This constructs scanner responses and provenance
// for contract tests. The input E2 is a retained precommit candidate; this helper
// does not execute a scanner, prove finding absence, or claim exact-head evidence.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { osvInputFromE2, buildOsvCoverage } from '../../security/osv-scan-coverage.mjs';
import { CHECKSTYLE_GUAVA_GRAPH } from '../../security/checkstyle-guava-graph-transition.mjs';

export function syntheticCheckstyleGuavaOsv(e2, source) {
  const input = osvInputFromE2(e2), inputPath = '/synthetic-unit-only-checkstyle-guava-input.json';
  const selected = [...input.lookup.values()].find(target => target.package.name === 'com.google.guava:guava'
    && target.package.version === '32.0.1-jre');
  assert.ok(selected, 'synthetic Checkstyle fixture requires the declared current Guava target');
  const advisory = 'GHSA-synthetic-unit-only-checkstyle-guava';
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
  const coverage = buildOsvCoverage(raw, e2, { head: e2.commitSha, graphDigest: CHECKSTYLE_GUAVA_GRAPH,
    inputBytes: JSON.stringify(input.scannerInput), inputPath, findings: [finding], exitStatus: 1,
    stderr: `Starting filesystem walk for root: /\nScanned ${inputPath} file and found ${input.packageCount} packages\nEnd status: 0 dirs visited, 1 inodes visited, 1 Extract calls, 1ms elapsed, 1ms wall time\n` });
  return { ...structuredClone(source), findings: [finding], findingCount: 1, coverage,
    inputPackageCount: input.packageCount, binarySha256: 'a'.repeat(64) };
}
