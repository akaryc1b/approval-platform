// Read-only verification of retained evidence. No network, scanner, Git or subprocess.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync, readdirSync } from 'node:fs';
import { acceptedE2GraphProjection } from '../../security/m6-pr-e-e2-generate-sbom.mjs';
import { osvInputFromE2 } from '../../security/osv-scan-coverage.mjs';
import { BOOT_UTILITIES_GRAPH, readBootUtilitiesManifest } from '../../security/boot-utilities-graph-transition.mjs';
import { readPreservedBootHttpSource } from '../../security/boot-utilities-source-continuation.mjs';

export const stable = v => Array.isArray(v) ? v.map(stable) : v && typeof v === 'object'
  ? Object.fromEntries(Object.keys(v).sort().map(k => [k, stable(v[k])])) : v;
export const canonical = v => JSON.stringify(stable(v));
export const sha = v => createHash('sha256').update(v).digest('hex');
export const seal = v => { const { contentSha256, ...payload } = v; return { ...payload, contentSha256: sha(canonical(payload)) }; };
export const BOOT_UTILITIES_PLUGIN_DIAGNOSTIC_CHECKSUM_SHA256 = 'f5a026e48e41d0c2f9eec7c702bd8b00e78108504576f5a7d340bb1b794d4b46';
const QUERY = 'f46007cacb195d67cd66a19b763b7ada66778e03bf4d167184cbc6275827585f';
const INPUT = 'e3f74edd3700b42f8979cea19c964d90ae459eaea68a9f7c9c81601cbb4b0dc1';
const BASELINE = 'eebfd60d33cbd83a7db8ea5caae2e4d50e9a9b4c0a227a97e7b697917b88f762';
const HEAD = 'd38f57a82b13058d0e09839bdfb9a2ddf99d4508';
const root = new URL('../../../', import.meta.url);
const directory = new URL('docs/operations/boot-utilities-plugin-evidence/osv-diagnostic/', root);
const publicNames = ['README.md', 'baseline-normalized-osv-findings.json', 'comparison-to-main1846.json',
  'diagnostic-target-coverage.json', 'equivalent-scanner-input.json', 'execution.complete.json', 'http-provenance.json',
  'independent-derivation-rows.json', 'independent-targets.json', 'input-derivation.json', 'normalized-osv-findings.json',
  'query-target-results.json', 'querybatch.request.json', 'timestamp-precision-comparison.json', 'verified-osv-summary.json'].sort();
const packageKey = p => [p.ecosystem, p.name, p.version].join('\0');
const sortedTargets = e2 => [...osvInputFromE2(e2).lookup.values()].sort((a, b) =>
  `${a.package.ecosystem}:${a.package.name}:${a.package.version}`.localeCompare(`${b.package.ecosystem}:${b.package.name}:${b.package.version}`))
  .map(t => ({ package: t.package, componentRefs: [...t.componentRefs].sort(), scopes: [...t.scopes].sort() }));
const fields = (value, expected) => assert.deepEqual(Object.keys(value).sort(), [...expected].sort(), 'unexpected public data field');

export function readBootUtilitiesPluginDiagnostic() {
  const checksums = readFileSync(new URL('public-file-checksums.sha256', directory));
  assert.equal(sha(checksums), BOOT_UTILITIES_PLUGIN_DIAGNOSTIC_CHECKSUM_SHA256, 'public checksum inventory byte pin');
  const files = {};
  for (const line of checksums.toString().trim().split('\n')) {
    const match = line.match(/^([a-f0-9]{64})  ([A-Za-z0-9_.-]+)$/); assert.ok(match, 'bounded public filename/hash');
    const [, hash, name] = match; assert.equal(Object.hasOwn(files, name), false, 'duplicate public filename');
    const bytes = readFileSync(new URL(name, directory)); assert.equal(sha(bytes), hash, name); files[name] = bytes.toString();
  }
  assert.deepEqual(Object.keys(files).sort(), publicNames);
  assert.deepEqual(readdirSync(directory).sort(), [...publicNames, 'public-file-checksums.sha256'].sort());
  const json = n => JSON.parse(files[n]), binding = json('input-derivation.json');
  const pinned = ref => { const b = readFileSync(new URL(ref.path, root)); assert.equal(sha(b), ref.rawSha256, ref.path); return b; };
  const manifest = readBootUtilitiesManifest();
  const manifestBytesSha256 = sha(pinned(binding.bootUtilitiesManifest));
  assert.equal(sha(readPreservedBootHttpSource('pom.xml', binding.sourcePom.rawSha256)), binding.sourcePom.rawSha256);
  pinned(binding.sourcePom);
  for (const [p, h] of Object.entries(binding.referenceSourceHelpers)) assert.equal(sha(readFileSync(new URL(p, root))), h, 'reference source helper: ' + p);
  return { files, binding, manifest, manifestBytesSha256, originalE2: JSON.parse(pinned(binding.sourceE2)), baselineE2: JSON.parse(pinned(binding.baseline.e2)),
    httpBaseline: JSON.parse(pinned(binding.baseline.httpFindings)), summary: json('verified-osv-summary.json'),
    coverage: json('diagnostic-target-coverage.json'), findings: json('normalized-osv-findings.json'), baseline: json('baseline-normalized-osv-findings.json'),
    comparison: json('comparison-to-main1846.json'), targets: json('independent-targets.json'), rows: json('independent-derivation-rows.json'),
    results: json('query-target-results.json'), http: json('http-provenance.json'), execution: json('execution.complete.json'), precision: json('timestamp-precision-comparison.json') };
}

export function verifyBootUtilitiesPluginDiagnostic(d) {
  const { files, binding, manifest, originalE2, baselineE2, httpBaseline, summary, coverage, findings, baseline, comparison, targets, rows, results, http, execution, precision } = d;
  assert.deepEqual(Object.keys(files).sort(), publicNames);
  for (const v of [originalE2, baselineE2, manifest, summary, coverage]) assert.equal(v.contentSha256, seal(v).contentSha256, 'canonical content digest');
  assert.deepEqual(binding.sourceE2, manifest.capture.e2); assert.deepEqual(binding.sourcePom, manifest.capture.sourcePom);
  assert.deepEqual(binding.baseline.e2, manifest.base.e2); assert.equal(binding.bootUtilitiesManifest.contentSha256, manifest.contentSha256);
  assert.equal(binding.bootUtilitiesManifest.rawSha256, d.manifestBytesSha256);
  assert.equal(originalE2.commitSha, HEAD); assert.equal(baselineE2.commitSha, HEAD); assert.equal(binding.sourceBaseHead, HEAD);
  assert.equal(originalE2.contentSha256, binding.sourceE2.contentSha256); assert.equal(baselineE2.contentSha256, binding.baseline.e2.contentSha256);
  assert.equal(sha(canonical(acceptedE2GraphProjection(originalE2))), BOOT_UTILITIES_GRAPH);
  assert.equal(binding.candidateGraphDigest, BOOT_UTILITIES_GRAPH); assert.equal(manifest.observed.graphDigest, BOOT_UTILITIES_GRAPH);
  assert.equal(sha(canonical(acceptedE2GraphProjection(baselineE2))), manifest.base.graphDigest);
  assert.equal(binding.privateLocalGitObjectsRequiredByCi, false); assert.equal(binding.queryExecutedOnCommittedCandidateClaimed, false);
  assert.equal(binding.exactFinalCandidateEvidence, false); assert.equal(binding.baseline.head, HEAD); assert.equal(binding.baseline.runNumber, 1846);

  const expected = sortedTargets(originalE2), previous = sortedTargets(baselineE2), input = osvInputFromE2(originalE2);
  assert.equal(expected.length, 471); assert.equal(previous.length, 472); assert.deepEqual(targets, expected);
  assert.equal(JSON.stringify(input.scannerInput), files['equivalent-scanner-input.json']); assert.equal(sha(files['equivalent-scanner-input.json']), INPUT);
  const query = JSON.stringify({ queries: expected.map(({ package: p }) => ({ package: { name: p.name, ecosystem: p.ecosystem }, version: p.version })) });
  assert.equal(query, files['querybatch.request.json']); assert.equal(sha(query), QUERY); assert.equal(Buffer.byteLength(query), 46827);
  assert.equal(binding.exactQueryBytesSha256, QUERY); assert.equal(binding.exactQueryBytes, 46827); assert.equal(binding.equivalentScannerInputBytesSha256, INPUT);
  assert.equal(binding.inputTargetCanonicalSha256, sha(canonical(expected))); assert.equal(binding.inputPackageCount, 471);
  assert.deepEqual(binding.tupleCountsByEcosystem, { Maven: 470, npm: 1 }); assert.equal(new Set(targets.map(t => packageKey(t.package))).size, 471);
  assert.equal(targets.filter(t => t.package.ecosystem === 'Maven').length, 470); assert.equal(targets.filter(t => t.package.ecosystem === 'npm').length, 1);
  const previousMap = new Map(previous.map(t => [packageKey(t.package), t])), currentMap = new Map(targets.map(t => [packageKey(t.package), t]));
  const removedTargets = previous.filter(t => !currentMap.has(packageKey(t.package)));
  const addedTargets = targets.filter(t => !previousMap.has(packageKey(t.package)));
  assert.deepEqual(binding.removedTargets, removedTargets); assert.equal(removedTargets.length, 2);
  assert.deepEqual(binding.addedTargets, addedTargets); assert.equal(addedTargets.length, 1);
  assert.deepEqual(removedTargets.flatMap(t => t.componentRefs).map(ref => ref.replace('maven-plugin:', '')).sort(), manifest.removedPluginCoordinates);
  assert.deepEqual(addedTargets.flatMap(t => t.componentRefs).map(ref => ref.replace('maven-plugin:', '')).sort(), manifest.addedPluginCoordinates);
  assert.deepEqual(binding.changedRetainedAttribution, []);
  const lang318 = targets.filter(t => t.package.name === 'org.apache.commons:commons-lang3' && t.package.version === '3.18.0');
  assert.equal(lang318.length, 1, 'pre-existing Lang3 3.18 target deduplicated exactly once');
  assert.deepEqual(lang318[0], previousMap.get(packageKey(lang318[0].package)), 'pre-existing Lang3 3.18 attribution retained');
  assert.deepEqual(addedTargets.map(t => t.package), [{ ecosystem: 'Maven', name: 'org.codehaus.plexus:plexus-utils', version: '3.6.1' }]);
  for (const t of targets) if (previousMap.has(packageKey(t.package)))
    assert.deepEqual(t, previousMap.get(packageKey(t.package)), 'retained package/ref/scope inventory');
  const expectedRows = [], add = (p, ref, scope, source) => expectedRows.push({ package: p, componentRef: ref, scope, source });
  for (const c of originalE2.maven.components) if (c.group !== 'io.github.akaryc1b.approval') add({ name: c.group + ':' + c.name, version: c.version, ecosystem: 'Maven' }, c.bomRef, c.scope || 'dependency', 'maven.components');
  for (const b of originalE2.maven.importedBoms) add({ name: b.group + ':' + b.name, version: b.version, ecosystem: 'Maven' }, `pkg:maven/${b.group}/${b.name}@${b.version}?type=pom`, 'import', 'maven.importedBoms');
  for (const c of originalE2.maven.resolvedPluginCoordinates) { const p = c.split(':'); add({ name: p[0] + ':' + p[1], version: p.at(-1), ecosystem: 'Maven' }, 'maven-plugin:' + c, 'build-plugin', 'maven.resolvedPluginCoordinates'); }
  for (const n of originalE2.pnpm.external) add({ name: n.name, version: n.version, ecosystem: 'npm' }, 'pkg:npm/' + encodeURIComponent(n.name) + '@' + n.version, n.scope || 'unknown', 'pnpm.external');
  assert.deepEqual(rows, expectedRows, 'every source derivation row');

  assert.equal(coverage.targets.length, 471); assert.equal(results.length, 471);
  const advisoryIds = new Set(), findingIds = new Set(); let affected = 0, count = 0;
  for (const [i, t] of coverage.targets.entries()) {
    const r = results[i]; assert.equal(t.queryIndex, i); assert.equal(r.queryIndex, i);
    assert.deepEqual({ package: t.package, componentRefs: t.componentRefs, scopes: t.scopes }, targets[i]);
    assert.deepEqual({ package: r.package, componentRefs: r.componentRefs, scopes: r.scopes }, targets[i]);
    assert.equal(t.allPagesRetrieved, true); assert.equal(r.complete, true); assert.deepEqual(r.pageTokens, []);
    assert.deepEqual(t.responseReceipts, ['http/batch-1.receipt.json']); assert.deepEqual(r.pageReceipts, t.responseReceipts);
    assert.deepEqual(t.advisoryIds, r.advisories.map(v => v.id).sort()); assert.equal(new Set(t.advisoryIds).size, t.advisoryIds.length);
    const matched = findings.filter(f => packageKey(f.package) === packageKey(t.package));
    assert.deepEqual(matched.map(f => f.upstreamFindingId).sort(), t.advisoryIds);
    for (const f of matched) {
      assert.deepEqual(f.componentRefs, t.componentRefs); assert.deepEqual(f.scopes, t.scopes); assert.equal(f.sourceClass, 'E4_OSV_SCANNER');
      assert.equal(f.findingId, sha(['OSV', f.upstreamFindingId, f.package.ecosystem, f.package.name, f.package.version].join('\0')));
      assert.equal(findingIds.has(f.findingId), false); findingIds.add(f.findingId); advisoryIds.add(f.upstreamFindingId);
    }
    affected += t.advisoryIds.length > 0 ? 1 : 0; count += t.advisoryIds.length;
  }
  assert.equal(affected, 13); assert.equal(count, 14); assert.equal(findings.length, 14); assert.equal(advisoryIds.size, 10);
  for (const k of ['inputPackageCount', 'queriedPackageCount', 'completeResultPackageCount']) assert.equal(coverage[k], 471);
  assert.equal(coverage.affectedPackageTargetCount, 13); assert.equal(coverage.zeroFindingPackageTargetCount, 458); assert.equal(coverage.queryAdvisoryTupleCount, 14);
  for (const k of ['allInputPackagesQueried', 'allPositionalResponsesVerified', 'allPagesRetrieved', 'allAdvisoryDetailsRetrieved']) assert.equal(coverage[k], true);
  assert.equal(coverage.sourceE2ContentSha256, originalE2.contentSha256); assert.equal(coverage.candidateGraphDigest, BOOT_UTILITIES_GRAPH); assert.equal(coverage.exactQueryBytesSha256, QUERY);
  assert.equal(summary.coverageContentSha256, coverage.contentSha256); assert.equal(summary.normalizedFindingsCanonicalSha256, sha(canonical(findings)));

  assert.equal(baseline.length, 16); assert.equal(new Set(baseline.map(f => f.findingId)).size, 16); assert.equal(sha(canonical(baseline)), BASELINE); assert.deepEqual(baseline, httpBaseline);
  const old = new Map(baseline.map(f => [f.findingId, f])), current = new Map(findings.map(f => [f.findingId, f]));
  const removed = baseline.filter(f => !current.has(f.findingId)); assert.deepEqual(removed, comparison.removedFindings); assert.equal(removed.length, 2);
  const removedPackages = {
    'GHSA-6fmv-xxpf-w3cw': { ecosystem: 'Maven', name: 'org.codehaus.plexus:plexus-utils', version: '3.5.1' },
    'GHSA-j288-q9x7-2f5v': { ecosystem: 'Maven', name: 'org.apache.commons:commons-lang3', version: '3.16.0' },
  };
  assert.deepEqual(removed.map(f => f.upstreamFindingId).sort(), Object.keys(removedPackages).sort());
  for (const finding of removed) assert.deepEqual(finding.package, removedPackages[finding.upstreamFindingId]);
  assert.equal(binding.baseline.allHttpAndMain1846NormalizedFieldsIdentical, true);
  for (const f of findings) assert.deepEqual(f, old.get(f.findingId), 'all retained normalized fields');
  assert.deepEqual(comparison.addedFindings, []); assert.deepEqual(comparison.changedRetainedFindings, []);
  assert.deepEqual(comparison.unchangedRetainedFindingIds, findings.map(f => f.findingId).sort());
  const counts = { prior: 16, current: 14, removed: 2, added: 0, changed: 0, unchanged: 14 };
  assert.deepEqual(comparison.comparison, counts); assert.deepEqual(summary.comparison, counts);
  assert.equal(comparison.baselineSourceHead, HEAD); assert.equal(comparison.baselineSourceRunNumber, 1846);
  assert.equal(comparison.baselineNormalizedCanonicalSha256, BASELINE); assert.equal(comparison.currentNormalizedCanonicalSha256, sha(canonical(findings)));
  assert.equal(sha(files['baseline-normalized-osv-findings.json']), binding.baseline.normalizedFindingsRawSha256);
  assert.equal(comparison.baselineE4BytesSha256, binding.baseline.e4BytesSha256);

  fields(http, ['schemaVersion', 'requestCount', 'rawBodiesAndHeadersIncluded', 'scannerCliExecuted', 'receipts', 'transportUrlsIncluded']);
  assert.equal(http.transportUrlsIncluded, false); assert.equal(summary.transportUrlsIncluded, false);
  assert.equal(http.schemaVersion, 'REDACTED_DIRECT_OSV_HTTP_PROVENANCE_V2');
  assert.equal(http.receipts.length, 11); assert.equal(http.requestCount, 11); assert.equal(http.rawBodiesAndHeadersIncluded, false);
  assert.equal(new Set(http.receipts.map(r => r.endpoint)).size, 11); assert.equal(new Set(http.receipts.map(r => r.sourceReceipt)).size, 11);
  const receiptFields = ['label', 'method', 'officialHost', 'endpoint', 'advisoryId', 'status', 'startedAtUtc', 'completedAtUtc', 'requestBodySha256', 'requestBodyBytes', 'responseBodyBytes', 'responseBodySha256', 'error', 'sourceReceipt', 'sourceReceiptSha256'];
  const posts = http.receipts.filter(r => r.method === 'POST'), gets = http.receipts.filter(r => r.method === 'GET'); assert.equal(posts.length, 1); assert.equal(gets.length, 10);
  assert.equal(posts[0].endpoint, '/v1/querybatch'); assert.equal(posts[0].requestBodySha256, QUERY); assert.equal(posts[0].requestBodyBytes, 46827); assert.equal(posts[0].sourceReceipt, 'http/batch-1.receipt.json');
  assert.deepEqual(gets.map(r => r.advisoryId).sort(), [...advisoryIds].sort());
  for (const r of http.receipts) {
    fields(r, receiptFields); assert.equal(r.status, 200); assert.equal(r.error, null);
    assert.equal(r.officialHost, 'api.osv.dev'); assert.match(r.endpoint, /^\/v1\/(querybatch|vulns\/GHSA-[a-z0-9-]+)$/);
    assert.equal(r.endpoint, r.method === 'POST' ? '/v1/querybatch' : '/v1/vulns/' + r.advisoryId);
    if (r.method === 'POST') assert.equal(r.advisoryId, null);
    assert.match(r.responseBodySha256, /^[a-f0-9]{64}$/); assert.match(r.sourceReceiptSha256, /^[a-f0-9]{64}$/);
    assert.ok(Number.isInteger(r.responseBodyBytes) && r.responseBodyBytes > 0 && r.responseBodyBytes <= 16777216);
    assert.ok(Date.parse(r.startedAtUtc) >= Date.parse(execution.startedAtUtc) && Date.parse(r.completedAtUtc) >= Date.parse(r.startedAtUtc) && Date.parse(r.completedAtUtc) <= Date.parse(execution.completedAtUtc));
    if (r.method === 'GET') { assert.equal(r.requestBodySha256, null); assert.equal(r.requestBodyBytes, 0); assert.equal(r.sourceReceipt, 'http/detail-' + r.advisoryId + '.receipt.json'); }
  }
  assert.ok(http.receipts.reduce((n, r) => n + r.responseBodyBytes, 0) <= 67108864);
  assert.deepEqual(execution.httpReceipts, http.receipts.map(r => r.sourceReceipt)); assert.equal(execution.status, 'COMPLETE_HTTP_QUERY_AND_DETAILS'); assert.equal(execution.error, null);
  assert.equal(execution.inputTargetCount, 471); assert.equal(execution.completeTargetCount, 471); assert.equal(execution.validatedDetailCount, 10); assert.equal(execution.packageAdvisoryPairs, 14); assert.equal(execution.zeroFindingTargetCount, 458); assert.equal(execution.allTargetsAndDetailsComplete, true); assert.equal(execution.querySha256, QUERY); assert.equal(execution.httpRequestCount, 11);
  assert.equal(execution.automaticRetries, 0); assert.equal(execution.inventoryQueryRepeated, false); assert.equal(execution.successfulDetailRepeated, false);
  assert.equal(summary.queryStartedAt, execution.startedAtUtc); assert.equal(summary.queryCompletedAt, execution.completedAtUtc);
  assert.equal(binding.queryStartedAt, execution.startedAtUtc); assert.equal(binding.queryCompletedAt, execution.completedAtUtc);
  assert.equal(precision.rawTimestampsUnmodified, true); assert.equal(precision.pairs.length, 10); assert.deepEqual(precision.pairs.map(p => p.id).sort(), [...advisoryIds].sort());
  for (const p of precision.pairs) {
    const batch = p.queryModified.match(/^(.*\.)(\d{6})Z$/), detail = p.detailModified.match(/^(.*\.)(\d{9})Z$/);
    assert.ok(batch && detail); assert.equal(batch[1], detail[1]); assert.equal(detail[2].slice(0, 6), batch[2]);
    assert.equal(p.exactStringEqual, false); assert.equal(p.matchAtBatchPrecision, true); assert.equal(p.subMicrosecondChangeExcluded, false);
    for (const r of results) for (const v of r.advisories) if (v.id === p.id) assert.equal(v.modified, p.queryModified);
  }
  for (const v of [summary, coverage, binding, execution, http]) assert.equal(v.scannerCliExecuted, false);
  for (const v of [summary, binding, comparison]) assert.equal(v.reviewDispositionTransferred, false);
  for (const v of [summary, binding, comparison, execution]) assert.equal(v.releaseBlocked, true);
  for (const v of [summary, coverage, execution]) assert.equal(v.canonicalCiEvidence, false);
  for (const v of [summary, binding, execution]) assert.equal(v.graphAdmitted, false);
  assert.equal(binding.canonicalE4, false); assert.equal(summary.allScannersCompleted, false); assert.equal(summary.exactFinalCandidateEvidence, false);
  assert.equal(summary.exactModifiedStringEqualityClaimed, false); assert.equal(summary.subMicrosecondDriftExcluded, false); assert.equal(summary.rawBodiesAndHeadersIncluded, false);
  assert.equal(comparison.rawAdvisoryMetadataComparisonAvailable, false); assert.equal(summary.rawAdvisoryMetadataComparisonAvailable, false);
  assert.equal(summary.status, 'COMPLETE_ACTUAL_API_DIAGNOSTIC'); assert.equal(summary.schemaVersion, 'BOOT_UTILITIES_DIRECT_OSV_DIAGNOSTIC_V1');
  assert.equal(summary.classification, 'ACTUAL_PRECOMMIT_CAPTURE_DIRECT_OSV_API_DIAGNOSTIC');
  assert.equal(summary.sourceBaseHead, HEAD); assert.equal(summary.sourceE2BytesSha256, binding.sourceE2.rawSha256);
  assert.equal(summary.sourceE2ContentSha256, originalE2.contentSha256); assert.equal(summary.sourcePomSha256, binding.sourcePom.rawSha256);
  assert.equal(summary.candidateGraphDigest, BOOT_UTILITIES_GRAPH); assert.equal(summary.exactQueryBytesSha256, QUERY);
  for (const k of ['allInputPackagesQueried', 'allAdvisoryDetailsRetrieved', 'allNormalizedFieldsCompared']) assert.equal(summary[k], true);
  assert.equal(summary.queryPages, 1); assert.equal(summary.officialDestination, 'api.osv.dev');
  assert.equal(summary.sourceOrCredentialsTransmitted, false); assert.equal(summary.scannerMetadataClaimed, false);
  assert.equal(summary.findingsCount, 14); assert.equal(summary.inputPackageCount, 471); assert.equal(summary.completeResultPackageCount, 471);
  assert.equal(summary.affectedPackageTargetCount, 13); assert.equal(summary.zeroFindingPackageTargetCount, 458); assert.equal(summary.uniqueAdvisoryDetailCount, 10);
  assert.equal(summary.httpRequestCount, 11); assert.equal(summary.httpSuccessCount, 11); assert.equal(summary.automaticRetries, 0); assert.deepEqual(summary.errors, []);
  const responseBytes = http.receipts.reduce((n, receipt) => n + receipt.responseBodyBytes, 0);
  assert.equal(responseBytes, 66843); assert.equal(execution.responseBytesRetained, responseBytes);
  assert.equal(summary.responseBytesRetainedPrivately, responseBytes); assert.equal(summary.rawApiResponsesRetainedPrivately, true);
  return { targets: 471, findings: 14, zeroFindingTargets: 458, directApiRequests: 11, scannerCliExecuted: false, releaseBlocked: true };
}
