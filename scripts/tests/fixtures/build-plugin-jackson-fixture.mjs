// Actual retained OSV-only diagnostic, wrapped only in SYNTHETIC UNIT envelopes.
// This is neither final clean-source E4 nor an all-scanner completion claim.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { verifyOsvCoverage } from '../../security/osv-scan-coverage.mjs';
import { BUILD_PLUGIN_JACKSON_GRAPH, graphHash, canonicalGraph } from '../../security/observability-dependency-graph.mjs';
const pins = {
  'diagnostic-target-coverage.json': 'b9dcad00686028f0ca5664d2483da2c5f027f246fe5e54e2f272cacd47f0e9be',
  'normalized-osv-findings.json': '893e8ba7a714b3fdca7cbaf5909bcb8d2b082fd48baef32eee3c93bb6fc12597',
  'package-target-coverage.json': '8ab21073fc735b4e2ee680bff7b0ba124fd0cbc62ef33dc7ec026ea35b318c4f',
  'verified-osv-summary.json': '08277323dc4340efe94bf92e3165a73a9ceb8f58f0ea696117ef915b5738c462',
};
const read = name => {
  const raw = readFileSync(new URL(`../../../docs/operations/build-plugin-jackson-evidence/osv-diagnostic/${name}`, import.meta.url));
  assert.equal(createHash('sha256').update(raw).digest('hex'), pins[name]);
  return JSON.parse(raw);
};
export function readPluginOsvDiagnostic(e2) {
  const summary = read('verified-osv-summary.json'), wrapper = read('diagnostic-target-coverage.json');
  const coverage = wrapper.coverage, findings = read('normalized-osv-findings.json');
  const inputTargets = read('package-target-coverage.json');
  assert.equal(summary.classification, 'STANDALONE_OSV_ONLY_DIAGNOSTIC_UNCOMMITTED_CANDIDATE_NOT_E4');
  assert.equal(summary.allScannersCompleted, false); assert.equal(summary.graphAdmitted, false);
  assert.equal(wrapper.notE4Evidence, true); assert.equal(wrapper.actualRawReportsRetained, true);
  assert.equal(summary.sourceE2ContentSha256, e2.contentSha256);
  assert.equal(summary.coverageContentSha256, coverage.contentSha256);
  assert.equal(summary.normalizedFindingsSha256, graphHash(findings));
  const { contentSha256, ...payload } = summary; assert.equal(graphHash(payload), contentSha256);
  assert.deepEqual(coverage.targets.map(({advisoryIds,...target}) => canonicalGraph(target)).sort(),
    inputTargets.map(target => canonicalGraph({ ...target, componentRefs: [...target.componentRefs].sort(), scopes: [...target.scopes].sort() })).sort());
  verifyOsvCoverage(coverage, findings, e2, { head: e2.commitSha, graphDigest: BUILD_PLUGIN_JACKSON_GRAPH });
  return { summary, coverage, findings };
}
export function pluginOsvDiagnosticFixture(e2, source) {
  const { summary, coverage, findings } = readPluginOsvDiagnostic(e2);
  return { ...structuredClone(source), findings, findingCount: findings.length, coverage,
    inputPackageCount: coverage.inputPackageCount, binarySha256: summary.scannerBinarySha256 };
}
