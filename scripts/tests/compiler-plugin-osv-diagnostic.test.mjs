import assert from 'node:assert/strict';
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { pathToFileURL } from 'node:url';
import { readCompilerDiagnostic, verifyCompilerDiagnostic, seal, sha } from './fixtures/compiler-osv-diagnostic-verifier.mjs';

const baselinePom = readFileSync(new URL('../../docs/operations/compiler-plugin-evidence/candidate-root-pom.xml', import.meta.url));
const checkstylePom = readFileSync(new URL('../../docs/operations/checkstyle-plugin-evidence/candidate-root-pom.xml', import.meta.url));
const guavaPom = readFileSync(new URL('../../docs/operations/checkstyle-guava-evidence/candidate-root-pom.xml', import.meta.url));
const currentPom = readFileSync(new URL('../../pom.xml', import.meta.url));
const historicalPomHash = '1569b0356f1bfc122066ced97c29038c3c38e891135ea277e7b7067726782e1a';

async function sourceFixture(t, pom) {
  const directory = mkdtempSync(path.join(tmpdir(), 'compiler-diagnostic-source-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const moduleDirectory = path.join(directory, 'scripts/security');
  const evidenceDirectory = path.join(directory, 'docs/operations/compiler-plugin-evidence');
  const checkstyleEvidenceDirectory = path.join(directory, 'docs/operations/checkstyle-plugin-evidence');
  mkdirSync(moduleDirectory, { recursive: true });
  mkdirSync(evidenceDirectory, { recursive: true });
  mkdirSync(checkstyleEvidenceDirectory, { recursive: true });
  const modulePath = path.join(moduleDirectory, 'checkstyle-plugin-source-continuation.mjs');
  copyFileSync(new URL('../security/checkstyle-plugin-source-continuation.mjs', import.meta.url), modulePath);
  copyFileSync(new URL('../security/checkstyle-guava-source-continuation.mjs', import.meta.url),
    path.join(moduleDirectory, 'checkstyle-guava-source-continuation.mjs'));
  copyFileSync(new URL('../security/boot-http-source-continuation.mjs', import.meta.url),
    path.join(moduleDirectory, 'boot-http-source-continuation.mjs'));
  const guavaEvidenceDirectory = path.join(directory, 'docs/operations/checkstyle-guava-evidence');
  mkdirSync(guavaEvidenceDirectory, { recursive: true });
  writeFileSync(path.join(guavaEvidenceDirectory, 'candidate-root-pom.xml'), guavaPom);
  writeFileSync(path.join(evidenceDirectory, 'candidate-root-pom.xml'), baselinePom);
  writeFileSync(path.join(checkstyleEvidenceDirectory, 'candidate-root-pom.xml'), checkstylePom);
  const pomPath = path.join(directory, 'pom.xml');
  writeFileSync(pomPath, pom);
  const { readPreservedCompilerSource } = await import(pathToFileURL(modulePath).href);
  return { directory, pomPath, readSource: readPreservedCompilerSource,
    read: () => readPreservedCompilerSource('pom.xml', historicalPomHash) };
}

for (const [name, pom] of [['baseline', baselinePom], ['accepted Checkstyle Lang', checkstylePom], ['accepted Checkstyle Guava', guavaPom], ['Boot HTTP candidate', currentPom]]) {
  test(`Compiler diagnostic source pin accepts the exact ${name} root`, async t => {
    const fixture = await sourceFixture(t, pom);
    assert.equal(sha(baselinePom), historicalPomHash);
    assert.deepEqual(fixture.read(), baselinePom);
  });
  test(`Compiler diagnostic source pin rejects an extra edit to the ${name} root`, async t => {
    const fixture = await sourceFixture(t, pom);
    assert.deepEqual(fixture.read(), baselinePom);
    writeFileSync(fixture.pomPath, Buffer.concat([pom, Buffer.from('\n')]));
    assert.throws(fixture.read, /(?:Checkstyle (?:Guava )?|Boot HTTP )continuation historical source byte digest mismatch/);
  });
}

test('Compiler diagnostic source pin rejects a wrong expected historical hash', async t => {
  const fixture = await sourceFixture(t, currentPom);
  assert.deepEqual(fixture.read(), baselinePom);
  assert.throws(() => fixture.readSource('pom.xml', '0'.repeat(64)),
    /Checkstyle continuation historical source byte digest mismatch/);
});

test('Compiler diagnostic source pin rejects changed bytes at a non-root path', async t => {
  const fixture = await sourceFixture(t, currentPom);
  const otherPath = path.join(fixture.directory, 'other.xml');
  writeFileSync(otherPath, baselinePom);
  assert.deepEqual(fixture.readSource('other.xml', historicalPomHash), baselinePom);
  writeFileSync(otherPath, currentPom);
  assert.throws(() => fixture.readSource('other.xml', historicalPomHash),
    /Checkstyle continuation historical source byte digest mismatch/);
});

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
