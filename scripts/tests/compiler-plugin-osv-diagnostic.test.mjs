import assert from 'node:assert/strict';
import test from 'node:test';
import { readCompilerDiagnostic, verifyCompilerDiagnostic, seal } from './fixtures/compiler-osv-diagnostic-verifier.mjs';

test('Compiler actual direct-API diagnostic binds all targets, complete findings, source bytes and independent provenance offline', () => {
  assert.deepEqual(verifyCompilerDiagnostic(readCompilerDiagnostic()), {
    targets: 473, findings: 22, zeroFindingTargets: 453, directApiRequests: 16, scannerCliExecuted: false, releaseBlocked: true,
  });
});

for (const [name, mutate] of [
  ['omitted zero-finding target', d => { d.coverage.targets.splice(d.coverage.targets.findIndex(t => !t.advisoryIds.length), 1); d.coverage = seal(d.coverage); }],
  ['shifted positional query attribution', d => { d.coverage.targets[0].queryIndex = 1; d.coverage = seal(d.coverage); }],
  ['changed component ownership', d => { d.coverage.targets[0].componentRefs = []; d.coverage = seal(d.coverage); }],
  ['changed scopes', d => { d.coverage.targets[0].scopes = ['test']; d.coverage = seal(d.coverage); }],
  ['incomplete pagination', d => { d.coverage.targets[0].allPagesRetrieved = false; d.coverage = seal(d.coverage); }],
  ['altered query bytes', d => { d.files['querybatch.request.json'] += '\n'; }],
  ['altered scanner-helper input bytes', d => { d.files['equivalent-scanner-input.json'] += '\n'; }],
  ['changed retained normalized fixed versions', d => { d.findings[0].fixedVersions = ['999']; }],
  ['omitted baseline finding', d => { d.baseline.pop(); }],
  ['omitted current finding', d => { d.findings.pop(); }],
  ['different bound runtime graph', d => { d.boundE2.maven.components[0].version = '999'; d.boundE2 = seal(d.boundE2); }],
  ['failed HTTP request', d => { d.http.receipts[0].status = 500; }],
  ['duplicate advisory request', d => { d.http.receipts[1].url = d.http.receipts[2].url; }],
  ['unapproved endpoint', d => { d.http.receipts[0].url = 'https://example.invalid/querybatch'; }],
  ['raw HTTP header disclosure', d => { d.http.receipts[0].responseHeaders = []; }],
  ['microsecond timestamp disagreement', d => { d.precision.differences[0].detailModified = '2026-01-01T00:00:00.000000001Z'; }],
  ['false exact timestamp equality', d => { d.precision.exactStringEqualityClaimed = true; }],
  ['false scanner execution', d => { d.summary.scannerCliExecuted = true; d.summary = seal(d.summary); }],
  ['false canonical CI', d => { d.summary.canonicalCiEvidence = true; d.summary = seal(d.summary); }],
  ['false final-head scanner execution', d => { d.binding.queryExecutedOnCapture3Claimed = true; }],
  ['false new-query freshness', d => { d.binding.newQueryOrFreshnessClaimed = true; }],
  ['private Git-object CI requirement', d => { d.binding.privateLocalGitObjectsRequiredByCi = true; }],
  ['waived release block', d => { d.summary.releaseBlocked = false; d.summary = seal(d.summary); }],
  ['transferred finding disposition', d => { d.summary.reviewDispositionTransferred = true; d.summary = seal(d.summary); }],
]) test(`Compiler offline diagnostic verification rejects ${name}`, () => {
  const diagnostic = readCompilerDiagnostic(); mutate(diagnostic); assert.throws(() => verifyCompilerDiagnostic(diagnostic));
});
