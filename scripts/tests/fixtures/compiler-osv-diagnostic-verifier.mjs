// Read-only/offline diagnostic checks. No scanner, network or Git process is invoked.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, readdirSync } from 'node:fs';
import { acceptedE2GraphProjection } from '../../security/m6-pr-e-e2-generate-sbom.mjs';
import { osvInputFromE2 } from '../../security/osv-scan-coverage.mjs';
import { COMPILER_PLUGIN_GRAPH, readCompilerPluginManifest } from '../../security/compiler-plugin-graph-transition.mjs';

export const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
export const canonical = value => JSON.stringify(stable(value));
export const sha = value => createHash('sha256').update(value).digest('hex');
export const seal = value => { const { contentSha256, ...payload } = value; return { ...payload, contentSha256: sha(canonical(payload)) }; };
const packageKey = p => `${p.ecosystem}\0${p.name}\0${p.version}`;
const root = new URL('../../../', import.meta.url);
const directory = new URL('docs/operations/compiler-plugin-evidence/osv-diagnostic/', root);
const CHECKSUMS = 'f3f0e818be87b3d498f33bc43c432cdb2503092b16cc65277802ed3aee0b6fe5';
const inputHash = '9372daf69ae2867257d7f5d801f36bbd60f4f673f1f4dd5cb2e95e8ec289c97e';
const queryHash = 'a506047c17a16eb0c8b34263bd150e7ccc6ebed3e367883d3e4d4eba9320b7d9';

export function readCompilerDiagnostic() {
  const checksum = readFileSync(new URL('public-file-checksums.sha256', directory));
  assert.equal(sha(checksum), CHECKSUMS, 'diagnostic checksum inventory byte pin');
  const files = {};
  for (const line of checksum.toString().trim().split('\n')) {
    const match = line.match(/^([a-f0-9]{64})  ([A-Za-z0-9_.-]+)$/);
    assert.ok(match, 'bounded diagnostic filename/hash required');
    const [, hash, name] = match; assert.equal(Object.hasOwn(files, name), false, 'duplicate diagnostic file');
    files[name] = readFileSync(new URL(name, directory)); assert.equal(sha(files[name]), hash, name);
  }
  assert.deepEqual(readdirSync(directory).sort(), [...Object.keys(files), 'public-file-checksums.sha256'].sort(), 'complete public diagnostic file inventory');
  const json = name => JSON.parse(files[name]);
  const binding = json('input-derivation.json');
  const originalE2 = readFileSync(new URL(binding.originalQuery.sourceE2.path, root));
  assert.equal(sha(originalE2), binding.originalQuery.sourceE2.rawSha256);
  for (const [path, expected] of Object.entries(binding.sourceHelpers))
    assert.equal(sha(readFileSync(new URL(path, root))), expected, 'current source helper bytes: ' + path);
  assert.equal(sha(readFileSync(new URL('pom.xml', root))), binding.originalQuery.sourcePomSha256);
  // The current Compiler source/graph witness checks all 28 POMs. Historical local
  // commit IDs remain data; portability does not depend on their Git object existence.
  assert.equal(readCompilerPluginManifest().observed.graphDigest, COMPILER_PLUGIN_GRAPH);
  return { files: Object.fromEntries(Object.entries(files).map(([k, v]) => [k, v.toString()])),
    binding, originalE2: JSON.parse(originalE2), boundE2: json('bound-local-capture3-E2.json'),
    summary: json('verified-osv-summary.json'), coverage: json('diagnostic-target-coverage.json'),
    findings: json('normalized-osv-findings.json'), baseline: json('baseline-normalized-osv-findings.json'),
    comparison: json('comparison-to-ci1836.json'), plan: json('query-plan.json'),
    targets: json('independent-targets.json'), rows: json('independent-derivation-rows.json'),
    results: json('query-target-results.json'), http: json('http-provenance.json'), execution: json('execution.complete.json'),
    precision: json('timestamp-precision-comparison.json'), review: json('independent-revalidation.json'),
    inputReview: json('independent-input-revalidation.json'), provenance: json('provenance-verification.json') };
}

export function verifyCompilerDiagnostic(data) {
  const { binding, originalE2, boundE2, summary, coverage, findings, baseline, comparison, plan,
    targets, rows, results, http, execution, precision, review, inputReview, provenance, files } = data;
  for (const value of [originalE2, boundE2, summary, coverage]) assert.equal(value.contentSha256, seal(value).contentSha256, 'canonical content digest');
  const { commitSha: priorHead, contentSha256: priorHash, ...prior } = originalE2;
  const { commitSha: localHead, contentSha256: localHash, ...current } = boundE2;
  assert.deepEqual(prior, current, 'entire E2 payload except commit/content digest retained');
  assert.equal(priorHead, '0c55ff5e7d974022e5c76d9a88a4312d2af81827');
  assert.equal(priorHash, binding.originalQuery.sourceE2.contentSha256);
  assert.equal(localHead, binding.boundLocalCapture3.head); assert.equal(localHash, binding.boundLocalCapture3.sourceE2.contentSha256);
  assert.equal(sha(files['bound-local-capture3-E2.json']), binding.boundLocalCapture3.sourceE2.rawSha256);
  assert.equal(sha(canonical(acceptedE2GraphProjection(boundE2))), COMPILER_PLUGIN_GRAPH);
  assert.equal(binding.candidateGraphDigest, COMPILER_PLUGIN_GRAPH);
  assert.equal(binding.privateLocalGitObjectsRequiredByCi, false);
  assert.equal(binding.queryExecutedOnCapture3Claimed, false); assert.equal(binding.newQueryOrFreshnessClaimed, false);
  for (const key of ['fullE2PayloadOtherThanCommitAndContentIdentical', 'ownerReportBytesIdentical', 'allTuplesRefsScopesAndOrderIdentical', 'sourceHelpersIdentical'])
    assert.equal(binding.boundLocalCapture3[key], true, key);

  const input = osvInputFromE2(originalE2), expected = [...input.lookup.values()].sort((a, b) =>
    `${a.package.ecosystem}:${a.package.name}:${a.package.version}`.localeCompare(`${b.package.ecosystem}:${b.package.name}:${b.package.version}`))
    .map(t => ({ package: t.package, componentRefs: [...t.componentRefs].sort(), scopes: [...t.scopes].sort() }));
  assert.equal(input.packageCount, 473); assert.deepEqual(targets, expected);
  assert.equal(JSON.stringify(input.scannerInput), files['equivalent-scanner-input.json']);
  assert.equal(sha(files['equivalent-scanner-input.json']), inputHash);
  const query = JSON.stringify({ queries: expected.map(({ package: p }) => ({ package: { name: p.name, ecosystem: p.ecosystem }, version: p.version })) });
  assert.equal(query, files['querybatch.request.json']); assert.equal(Buffer.byteLength(query), 47016); assert.equal(sha(query), queryHash);
  assert.equal(plan.queryBytesSha256, queryHash); assert.equal(binding.originalQuery.exactQueryBytesSha256, queryHash);
  assert.equal(binding.equivalentScannerInputBytesSha256, inputHash);
  assert.equal(new Set(targets.map(t => packageKey(t.package))).size, 473);
  assert.equal(targets.filter(t => t.package.ecosystem === 'Maven').length, 472);
  assert.equal(targets.filter(t => t.package.ecosystem === 'npm').length, 1);
  assert.equal(targets.some(t => t.package.name === 'commons-io:commons-io' && t.package.version === '2.11.0'), false);
  assert.equal(targets.some(t => t.package.name === 'commons-io:commons-io' && t.package.version === '2.20.0'), true);
  const fromRows = new Map();
  for (const row of rows) {
    assert.ok(['maven.components', 'maven.importedBoms', 'maven.resolvedPluginCoordinates', 'pnpm.external'].includes(row.source));
    const key = packageKey(row.package); if (!fromRows.has(key)) fromRows.set(key, { package: row.package, componentRefs: new Set(), scopes: new Set() });
    fromRows.get(key).componentRefs.add(row.componentRef); fromRows.get(key).scopes.add(row.scope);
  }
  assert.equal(fromRows.size, 473);
  for (const target of targets) {
    const row = fromRows.get(packageKey(target.package));
    assert.deepEqual([...row.componentRefs].sort(), target.componentRefs); assert.deepEqual([...row.scopes].sort(), target.scopes);
  }

  assert.equal(coverage.targets.length, 473); assert.equal(results.length, 473);
  let affected = 0, count = 0; const advisoryIds = new Set(), keys = new Set();
  for (const [index, target] of coverage.targets.entries()) {
    assert.equal(target.queryIndex, index); assert.equal(results[index].queryIndex, index);
    assert.deepEqual(target.package, targets[index].package); assert.deepEqual(results[index].package, target.package);
    assert.deepEqual(target.componentRefs, targets[index].componentRefs); assert.deepEqual(target.scopes, targets[index].scopes);
    assert.equal(target.allPagesRetrieved, true); assert.deepEqual(target.responseReceipts, ['http/batch-page-1.receipt.json']);
    assert.equal(new Set(target.advisoryIds).size, target.advisoryIds.length);
    assert.deepEqual(target.advisoryIds, results[index].advisories.map(a => a.id).sort());
    assert.deepEqual(results[index].pageTokens, []); assert.deepEqual(results[index].pageReceipts, target.responseReceipts);
    const matched = findings.filter(f => packageKey(f.package) === packageKey(target.package));
    assert.deepEqual(matched.map(f => f.upstreamFindingId).sort(), target.advisoryIds);
    for (const finding of matched) {
      assert.deepEqual(finding.componentRefs, target.componentRefs); assert.deepEqual(finding.scopes, target.scopes);
      assert.equal(finding.sourceClass, 'E4_OSV_SCANNER');
      assert.equal(finding.findingId, sha(['OSV', finding.upstreamFindingId, finding.package.ecosystem, finding.package.name, finding.package.version].join('\0')));
      assert.equal(keys.has(finding.findingId), false); keys.add(finding.findingId); advisoryIds.add(finding.upstreamFindingId);
    }
    affected += target.advisoryIds.length > 0 ? 1 : 0; count += target.advisoryIds.length;
  }
  assert.equal(affected, 20); assert.equal(count, 22); assert.equal(findings.length, 22); assert.equal(advisoryIds.size, 15);
  assert.equal(coverage.zeroFindingPackageTargetCount, 453); assert.equal(coverage.completeResultPackageCount, 473);
  assert.equal(coverage.queriedPackageCount, 473); assert.equal(coverage.allPagesRetrieved, true);
  assert.equal(summary.coverageContentSha256, coverage.contentSha256);
  assert.equal(summary.normalizedFindingsCanonicalSha256, sha(canonical(findings)));

  assert.equal(baseline.length, 23); assert.equal(new Set(baseline.map(f => f.findingId)).size, 23);
  const old = new Map(baseline.map(f => [f.findingId, f])), now = new Map(findings.map(f => [f.findingId, f]));
  const removed = baseline.filter(f => !now.has(f.findingId)), added = findings.filter(f => !old.has(f.findingId));
  assert.deepEqual(removed, comparison.removedFindings); assert.deepEqual(added, []); assert.deepEqual(comparison.addedFindings, []);
  assert.equal(removed.length, 1); assert.equal(removed[0].upstreamFindingId, 'GHSA-78wr-2p64-hpwj');
  assert.deepEqual(removed[0].package, { ecosystem: 'Maven', name: 'commons-io:commons-io', version: '2.11.0' });
  for (const finding of findings) assert.deepEqual(finding, old.get(finding.findingId), 'all retained normalized fields');
  assert.deepEqual(comparison.changedRetainedFindings, []);
  assert.deepEqual(comparison.unchangedRetainedFindingIds, findings.map(f => f.findingId).sort());
  assert.deepEqual(comparison.comparison, { prior: 23, current: 22, removed: 1, added: 0, unchanged: 22, changed: 0 });
  assert.deepEqual(summary.comparison, comparison.comparison); assert.deepEqual(review.comparison, comparison.comparison);
  assert.equal(sha(files['baseline-normalized-osv-findings.json']), binding.baseline.normalizedFindingsRawSha256);
  assert.equal(comparison.priorE4BytesSha256, binding.baseline.e4BytesSha256);

  assert.equal(http.receipts.length, 16); assert.equal(http.rawBodiesAndHeadersIncluded, false);
  assert.equal(http.receipts.filter(r => r.method === 'POST').length, 1); assert.equal(http.receipts.filter(r => r.method === 'GET').length, 15);
  assert.equal(new Set(http.receipts.map(r => r.url)).size, 16);
  for (const receipt of http.receipts) {
    assert.equal(receipt.status, 200); assert.equal(receipt.error, null);
    assert.ok(/^https:\/\/api\.osv\.dev\/v1\/(querybatch|vulns\/GHSA-[a-z0-9-]+)$/.test(receipt.url));
    assert.match(receipt.responseBodySha256, /^[a-f0-9]{64}$/); assert.match(receipt.sourceReceiptSha256, /^[a-f0-9]{64}$/);
    for (const field of ['requestHeaders', 'responseHeaders', 'responseBody', 'requestBody', 'credentials']) assert.equal(Object.hasOwn(receipt, field), false);
  }
  assert.equal(http.receipts[0].requestBodySha256, queryHash); assert.equal(http.receipts[0].requestBodyBytes, 47016);
  assert.deepEqual(execution.httpReceipts, http.receipts.map(r => r.sourceReceipt));
  assert.equal(execution.inventoryQueryRepeated, false); assert.equal(execution.successfulDetailRepeated, false);
  assert.equal(summary.queryStartedAt, execution.startedAtUtc); assert.equal(summary.queryCompletedAt, execution.completedAtUtc);
  assert.equal(binding.originalQuery.queryStartedAt, execution.startedAtUtc); assert.equal(binding.originalQuery.queryCompletedAt, execution.completedAtUtc);
  assert.equal(precision.differences.length, 15); assert.equal(precision.exactStringEqualityClaimed, false);
  for (const row of precision.differences) {
    assert.equal(row.classification, 'DETAIL_HAS_ADDITIONAL_FRACTIONAL_PRECISION');
    const batch = row.queryModified.match(/^(.*\.)(\d{6})Z$/), detail = row.detailModified.match(/^(.*\.)(\d{9})Z$/);
    assert.ok(batch && detail); assert.equal(batch[1], detail[1]); assert.equal(detail[2].slice(0, 6), batch[2]);
    assert.ok(advisoryIds.has(row.advisoryId));
  }
  for (const object of [summary, coverage, binding, execution, http, provenance]) assert.equal(object.scannerCliExecuted, false);
  for (const object of [summary, binding, provenance]) { assert.equal(object.reviewDispositionTransferred, false); assert.equal(object.releaseBlocked, true); }
  assert.equal(summary.canonicalCiEvidence, false); assert.equal(summary.allScannersCompleted, false);
  assert.equal(summary.exactFinalCandidateEvidence, false); assert.equal(summary.graphAdmitted, false);
  assert.equal(comparison.rawAdvisoryMetadataComparisonAvailable, false);
  assert.equal(review.status, 'PASS_BOUNDED_DIRECT_API_DIAGNOSTIC'); assert.equal(review.claims.canonicalE4, false);
  assert.equal(review.claims.exactHeadScannerRun, false); assert.equal(review.claims.subMicrosecondDriftExcluded, false);
  assert.equal(inputReview.status, 'FINAL_COMMITTED_SOURCE_CAPTURE_AND_QUERY_INPUT_EQUIVALENCE_VERIFIED');
  assert.equal(inputReview.helperInputSha256, inputHash); assert.equal(inputReview.head, boundE2.commitSha);
  assert.equal(provenance.independentDiagnosticReviewSealSha256, '08694460cb8bf80653cc119a173c0d39292c1a2f5bb9cfb9bd3060b21e37097a');
  assert.equal(provenance.independentCommittedInputReviewChecksumsSha256, 'b6708a06cbb3fd447f73c396f1caba9b6cab8b09db757ffb9e412be759ea71eb');
  assert.equal(provenance.independentDiagnosticReceiptSha256, sha(files['independent-revalidation.json']));
  assert.equal(provenance.independentCommittedInputReceiptSha256, sha(files['independent-input-revalidation.json']));
  return { targets: 473, findings: 22, zeroFindingTargets: 453, directApiRequests: 16, scannerCliExecuted: false, releaseBlocked: true };
}
