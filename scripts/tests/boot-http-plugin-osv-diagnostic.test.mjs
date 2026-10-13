import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { readBootHttpPluginDiagnostic, verifyBootHttpPluginDiagnostic, seal, sha, canonical } from './fixtures/boot-http-plugin-osv-diagnostic-verifier.mjs';

// Each mutation starts from independently pinned retained evidence; coverage mutations
// recompute their digests so semantic validation, not a stale self-hash, must reject them.
const resealCoverage = d => {
  d.coverage = seal(d.coverage);
  d.summary.coverageContentSha256 = d.coverage.contentSha256;
  d.summary = seal(d.summary);
};
const resealFindings = d => {
  d.summary.normalizedFindingsCanonicalSha256 = sha(canonical(d.findings));
  d.summary = seal(d.summary);
};

test('natural transport boundary registers each Boot HTTP graph and diagnostic gate exactly once', () => {
  const source = readFileSync(new URL('./m6-ai-transport-review-boundary.test.mjs', import.meta.url), 'utf8');
  for (const gate of ['boot-http-graph-transition', 'boot-http-plugin-osv-diagnostic'])
    assert.equal(source.split(`import './${gate}.test.mjs';`).length - 1, 1);
});

test('Boot HTTP actual direct API diagnostic binds all targets, full findings and bounded source/HTTP provenance offline', () => {
  assert.deepEqual(verifyBootHttpPluginDiagnostic(readBootHttpPluginDiagnostic()), {
    targets: 472, findings: 16, zeroFindingTargets: 457, directApiRequests: 11, scannerCliExecuted: false, releaseBlocked: true,
  });
});

for (const [name, mutate, expectedError] of [
  ['omitted zero-result target', d => { const i = d.coverage.targets.findIndex(t => !t.advisoryIds.length); d.coverage.targets.splice(i, 1); d.results.splice(i, 1); resealCoverage(d); }],
  ['shifted positional attribution', d => { d.coverage.targets[0].queryIndex = 1; resealCoverage(d); }],
  ['altered component references', d => { d.coverage.targets[0].componentRefs = []; resealCoverage(d); }],
  ['altered scopes', d => { d.coverage.targets[0].scopes = ['test']; resealCoverage(d); }],
  ['incomplete target pagination', d => { d.coverage.targets[0].allPagesRetrieved = false; resealCoverage(d); }],
  ['incomplete zero-result response', d => { d.results.find(r => !r.advisories.length).complete = false; }],
  ['unconsumed page token', d => { d.results[0].pageTokens.push('UNCONSUMED'); }],
  ['missing positional page receipt', d => { d.results[0].pageReceipts = []; }],
  ['changed exact query bytes', d => { d.files['querybatch.request.json'] += '\n'; }],
  ['mismatched batch request hash', d => { d.http.receipts.find(r => r.method === 'POST').requestBodySha256 = '0'.repeat(64); }],
  ['mismatched positional response receipt', d => { d.coverage.targets[0].responseReceipts = ['http/batch-2.receipt.json']; d.results[0].pageReceipts = ['http/batch-2.receipt.json']; resealCoverage(d); }],
  ['mismatched advisory response receipt', d => { const gets = d.http.receipts.filter(r => r.method === 'GET'); gets[0].sourceReceipt = gets[1].sourceReceipt; }],
  ['changed helper-equivalent input bytes', d => { d.files['equivalent-scanner-input.json'] += '\n'; }],
  ['changed target version', d => { d.targets[0].package.version = '999'; }],
  ['changed source derivation row', d => { d.rows[0].scope = 'test'; }],
  ['changed retained fixed-version metadata', d => { d.findings[0].fixedVersions = ['999']; resealFindings(d); }, /all retained normalized fields/],
  ['changed retained severity metadata', d => { d.findings[0].upstreamSeverity = []; resealFindings(d); }, /all retained normalized fields/],
  ['changed retained alias metadata', d => { d.findings[0].aliases = []; resealFindings(d); }, /all retained normalized fields/],
  ['changed retained component references', d => { d.findings[0].componentRefs = []; resealFindings(d); }],
  ['changed retained scopes', d => { d.findings[0].scopes = ['test']; resealFindings(d); }],
  ['missing accepted baseline finding', d => { d.baseline.pop(); }],
  ['missing candidate finding', d => { d.findings.pop(); }],
  ['duplicate candidate finding', d => { d.findings.push(structuredClone(d.findings[0])); }],
  ['different rehashed candidate graph', d => { d.originalE2.maven.components[0].version = '999'; d.originalE2 = seal(d.originalE2); }],
  ['false semantic comparison', d => { d.comparison.comparison.removed = 0; }],
  ['altered removed advisory identity', d => { d.comparison.removedFindings[0].upstreamFindingId = 'GHSA-wrong-advisory'; }],
  ['failed HTTP response', d => { d.http.receipts[0].status = 500; }],
  ['missing detail request', d => { d.http.receipts.pop(); d.http.requestCount--; }],
  ['duplicate detail request attribution', d => { d.http.receipts[1].endpoint = d.http.receipts[2].endpoint; }],
  ['foreign HTTP endpoint', d => { d.http.receipts[0].officialHost = 'example.invalid'; }],
  ['transport URL disclosure', d => { d.http.receipts[0].url = 'https://api.osv.dev/v1/querybatch'; }],
  ['wrong advisory endpoint', d => { d.http.receipts[1].advisoryId = 'GHSA-forged-id'; }],
  ['omitted removed Boot HTTP target', d => { d.binding.removedTargets.pop(); }],
  ['omitted added Boot HTTP target', d => { d.binding.addedTargets.pop(); }],
  ['false retained response bytes', d => { d.summary.responseBytesRetainedPrivately++; d.summary = seal(d.summary); }],
  ['HTTP receipt header disclosure', d => { d.http.receipts[0].headers = []; }],
  ['HTTP envelope header disclosure', d => { d.http.headers = []; }],
  ['extra public raw-data file', d => { d.files['raw-response.json'] = '{}'; }],
  ['modified timestamp outside batch precision', d => { d.precision.pairs[0].detailModified = '2026-01-01T00:00:00.000000001Z'; }],
  ['false exact timestamp equality', d => { d.precision.pairs[0].exactStringEqual = true; }],
  ['false exclusion of sub-microsecond change', d => { d.precision.pairs[0].subMicrosecondChangeExcluded = true; }],
  ['changed query-modified attribution', d => { d.results.find(r => r.advisories.length).advisories[0].modified = '2026-01-01T00:00:00.000000Z'; }],
  ['false scanner CLI execution', d => { d.summary.scannerCliExecuted = true; d.summary = seal(d.summary); }],
  ['false canonical CI', d => { d.summary.canonicalCiEvidence = true; d.summary = seal(d.summary); }],
  ['false committed-candidate query', d => { d.binding.queryExecutedOnCommittedCandidateClaimed = true; }],
  ['false graph admission', d => { d.execution.graphAdmitted = true; }],
  ['waived release block', d => { d.summary.releaseBlocked = false; d.summary = seal(d.summary); }],
  ['waived execution release block', d => { d.execution.releaseBlocked = false; }],
  ['transferred finding disposition', d => { d.summary.reviewDispositionTransferred = true; d.summary = seal(d.summary); }],
  ['incomplete collection status', d => { d.summary.status = 'INCOMPLETE'; d.summary = seal(d.summary); }],
  ['false full-detail completion', d => { d.summary.allAdvisoryDetailsRetrieved = false; d.summary = seal(d.summary); }],
]) test(`Boot HTTP offline diagnostic rejects ${name}`, () => {
  const diagnostic = readBootHttpPluginDiagnostic(); mutate(diagnostic);
  assert.throws(() => verifyBootHttpPluginDiagnostic(diagnostic), expectedError);
});
