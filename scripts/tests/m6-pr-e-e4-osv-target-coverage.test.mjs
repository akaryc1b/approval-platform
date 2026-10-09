import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { buildOsvCoverage, osvInputFromE2, requireOsvExecution, verifyOsvCoverage }
  from '../security/osv-scan-coverage.mjs';
import { requireOsvConfigAbsent } from '../security/m6-pr-e-e4-scan.mjs';

const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const canonical = value => JSON.stringify(stable(value));
const hash = value => createHash('sha256').update(value).digest('hex');
const seal = value => { const { contentSha256, ...payload } = value; return { ...payload, contentSha256: hash(canonical(payload)) }; };
const preparationBytes = readFileSync(new URL('../../docs/m6/m6-pr-e-e3-r4-osv-preparation.json', import.meta.url));
const preparation = JSON.parse(preparationBytes);
const fixture = () => structuredClone({ coverage: preparation.osv.coverage, findings: preparation.osv.findings,
  e2: preparation.e2CurrentEvidence, options: { head: preparation.commitSha, graphDigest: preparation.e2GraphDigest } });
const inputPath = '/tmp/test-only-osv/osv-scanner.json';
const diagnostics = `Starting filesystem walk for root: /\nScanned ${inputPath} file and found 535 packages\nEnd status: 0 dirs visited, 1 inodes visited, 1 Extract calls, 2.418282ms elapsed, 2.418352ms wall time\n`;

// A deliberately synthetic, in-memory report exercises producer rejection paths.
// It is never persisted, presented as an execution, or accepted as all-scanner E4.
function producerFixture() {
  const value = fixture();
  value.raw = { results: [{ source: { path: inputPath, type: 'lockfile' },
    packages: value.coverage.targets.map(target => ({ package: target.package,
      ...(target.advisoryIds.length ? { vulnerabilities: target.advisoryIds.map(id => ({ id })) } : {}) })) }],
  experimental_config: { licenses: { summary: false, allowlist: null } } };
  Object.assign(value.options, { inputPath, inputBytes: Buffer.from(JSON.stringify(osvInputFromE2(value.e2).scannerInput)),
    stderr: diagnostics, exitStatus: 1, findings: value.findings });
  return value;
}

test('actual partial OSV preparation retains complete 535-target coverage and all 70 findings without E4 promotion', () => {
  assert.equal(hash(preparationBytes), '8de75536a3b70e43764ae06828b6de7c8398dec8becc5be0b7a00730271b77f4');
  assert.deepEqual(seal(preparation), preparation);
  assert.equal(preparation.classification, 'PARTIAL_OSV_ONLY_DIAGNOSTIC_NOT_E4');
  assert.equal(preparation.allScannersCompleted, false);
  assert.equal(preparation.graphAdmitted, false);
  assert.equal(preparation.reviewDispositionTransferred, false);
  assert.equal(preparation.releaseBlocked, true);
  const { coverage, findings, e2, options } = fixture();
  assert.equal(verifyOsvCoverage(coverage, findings, e2, options), coverage);
  assert.equal(coverage.inputPackageCount, 535);
  assert.equal(coverage.reportedPackageCount, 535);
  assert.equal(coverage.findingPackageCount, 42);
  assert.equal(coverage.findingCount, 70);
  assert.equal(coverage.targets.filter(target => target.advisoryIds.length === 0).length, 493);
  assert.equal(findings.length, 70);
  assert.equal(hash(JSON.stringify(osvInputFromE2(e2).scannerInput)), preparation.sourceEvidence.inputBytesSha256);
  assert.equal(coverage.inputBytesSha256, '4ad6aa3c20e6bcd0621506cc97ac59b13df336e88900fba5624d1e645d031dfb');
  const pluginJackson = coverage.targets.filter(target => target.package.name.startsWith('tools.jackson.core:')
    && target.package.version === '3.1.5' && target.advisoryIds.length > 0);
  assert.equal(pluginJackson.length, 2);
  for (const target of pluginJackson) {
    assert.deepEqual(target.scopes, ['build-plugin']);
    assert.deepEqual(target.componentRefs, [`maven-plugin:${target.package.name}:jar:3.1.5`]);
  }
  assert.equal(pluginJackson.reduce((count, target) => count + target.advisoryIds.length, 0), 7);
  assert.equal(findings.filter(finding => finding.package.name.startsWith('tools.jackson.core:')
    && finding.package.version === '3.1.5').length, 7);
  assert.ok(coverage.targets.some(target => target.scopes.includes('import')));
  assert.ok(coverage.targets.some(target => target.package.ecosystem === 'npm'));
});

test('producer binds exact written input and keeps non-finding/plugin targets in a bounded coverage payload', () => {
  const value = producerFixture();
  const coverage = buildOsvCoverage(value.raw, value.e2, value.options);
  assert.deepEqual(coverage, value.coverage);
  assert.ok(coverage.targets.every(target => Object.keys(target).sort().join(',') === 'advisoryIds,componentRefs,package,scopes'));
  assert.ok(!canonical(coverage).includes('affected'));
});

test('a zero-finding producer fixture still needs every dependency, import, and build-plugin target', () => {
  const value = producerFixture();
  for (const entry of value.raw.results[0].packages) delete entry.vulnerabilities;
  value.options.findings = []; value.options.exitStatus = 0;
  const coverage = buildOsvCoverage(value.raw, value.e2, value.options);
  assert.equal(coverage.reportedPackageCount, 535);
  assert.equal(coverage.findingCount, 0);
  assert.equal(coverage.findingPackageCount, 0);
  assert.equal(verifyOsvCoverage(coverage, [], value.e2, value.options), coverage);
  value.raw.results[0].packages.pop();
  assert.throws(() => buildOsvCoverage(value.raw, value.e2, value.options));
});

for (const config of ['osv-scanner.toml', '.osv-scanner.toml']) test(`live scanner rejects ${config} in repository or input directory`, () => {
  const root = mkdtempSync(path.join(os.tmpdir(), 'osv-config-test-'));
  const inputDirectory = mkdtempSync(path.join(os.tmpdir(), 'osv-input-config-test-'));
  try {
    assert.doesNotThrow(() => requireOsvConfigAbsent([root, inputDirectory]));
    for (const directory of [root, inputDirectory]) {
      const file = path.join(directory, config);
      writeFileSync(file, '[[IgnoredVulns]]\nid = "GHSA-test-only"\n');
      assert.throws(() => requireOsvConfigAbsent([root, inputDirectory]), /unreviewed OSV suppression\/config/);
      rmSync(file);
    }
  } finally { rmSync(root, { recursive: true, force: true }); rmSync(inputDirectory, { recursive: true, force: true }); }
});

for (const [name, mutate] of [
  ['missing clean package', value => { value.raw.results[0].packages.splice(value.raw.results[0].packages.findIndex(entry => !entry.vulnerabilities), 1); }],
  ['duplicate package', value => { value.raw.results[0].packages.push(value.raw.results[0].packages[0]); }],
  ['extra foreign target', value => { value.raw.results[0].packages.push({ package: { ecosystem: 'Maven', name: 'foreign:artifact', version: '1' } }); }],
  ['package version drift', value => { value.raw.results[0].packages[0].package.version = 'unknown-version'; }],
  ['package ecosystem drift', value => { value.raw.results[0].packages[0].package.ecosystem = 'npm'; }],
  ['foreign input source', value => { value.raw.results[0].source.path = '/tmp/foreign/osv-scanner.json'; }],
  ['duplicate source', value => { value.raw.results.push(value.raw.results[0]); }],
  ['missing source identity', value => { delete value.raw.results[0].source; }],
  ['unqueried plugin target', value => { value.raw.results[0].packages = value.raw.results[0].packages.filter(entry => entry.package.name !== 'tools.jackson.core:jackson-core'); }],
  ['finding-only report', value => { value.raw.results[0].packages = value.raw.results[0].packages.filter(entry => entry.vulnerabilities); }],
  ['query byte drift', value => { value.options.inputBytes = Buffer.concat([value.options.inputBytes, Buffer.from('\n')]); }],
  ['query package drift', value => { value.options.inputBytes = Buffer.from('{"results":[]}'); }],
  ['report error', value => { value.raw.errors = [{ message: 'query failed' }]; }],
  ['ignored source', value => { value.raw.results[0].ignored = true; }],
  ['unscanned package', value => { value.raw.results[0].packages[0].unscanned = true; }],
  ['skipped target', value => { value.raw.skipped = ['target']; }],
  ['suppressed finding', value => { value.raw.results[0].packages.find(entry => entry.vulnerabilities).vulnerabilities[0].ignored = true; }],
  ['duplicate advisory', value => { const entry = value.raw.results[0].packages.find(entry => entry.vulnerabilities); entry.vulnerabilities.push(entry.vulnerabilities[0]); }],
  ['foreign affected package', value => { value.raw.results[0].packages.find(entry => entry.vulnerabilities).vulnerabilities[0].affected = [{ package: { name: 'foreign:package', ecosystem: 'Maven' } }]; }],
  ['hidden grouped advisory', value => { const entry = value.raw.results[0].packages.find(entry => entry.vulnerabilities); entry.groups = [{ ids: ['not-returned-advisory'] }]; }],
  ['report null packages', value => { value.raw.results[0].packages = null; }],
  ['report native nil results', value => { value.raw.results = null; }],
  ['diagnostic query error', value => { value.options.stderr += 'ERROR: query failed\n'; }],
  ['diagnostic ignored target', value => { value.options.stderr += 'Ignored vulnerability\n'; }],
  ['diagnostic loaded filter', value => { value.options.stderr += 'Loaded filter from: osv-scanner.toml\n'; }],
  ['diagnostic missing package', value => { value.options.stderr = value.options.stderr.replace('535 packages', '534 packages'); }],
  ['unsuccessful exit', value => { value.options.exitStatus = 128; }],
  ['zero exit despite findings', value => { value.options.exitStatus = 0; }],
]) test(`OSV producer rejects ${name}`, () => {
  const value = producerFixture(); mutate(value);
  assert.throws(() => buildOsvCoverage(value.raw, value.e2, value.options));
});

for (const [name, mutate] of [
  ['missing clean target', value => { value.coverage.targets.splice(value.coverage.targets.findIndex(target => !target.advisoryIds.length), 1); }],
  ['duplicate target', value => { value.coverage.targets.push(value.coverage.targets[0]); }],
  ['foreign target', value => { value.coverage.targets[0].package.name = 'foreign:package'; }],
  ['version drift', value => { value.coverage.targets[0].package.version = '2'; }],
  ['scope drift', value => { value.coverage.targets[0].scopes = ['runtime']; }],
  ['reference drift', value => { value.coverage.targets[0].componentRefs = ['foreign-reference']; }],
  ['unqueried plugin', value => { value.coverage.targets = value.coverage.targets.filter(target => target.package.name !== 'tools.jackson.core:jackson-core'); }],
  ['missing normalized advisory', value => { value.findings.pop(); }],
  ['duplicate normalized advisory', value => { value.findings.push(value.findings[0]); }],
  ['normalized foreign target', value => { value.findings[0].package.name = 'foreign:package'; }],
  ['normalized scope drift', value => { value.findings[0].scopes = ['compile']; }],
  ['normalized reference drift', value => { value.findings[0].componentRefs = ['foreign-reference']; }],
  ['normalized advisory-ID drift', value => { value.findings[0].upstreamFindingId = 'GHSA-foreign-advisory'; }],
  ['normalized finding-ID drift', value => { value.findings[0].findingId = '0'.repeat(64); }],
  ['normalized severity drift', value => { value.findings[0].upstreamSeverity = []; }],
  ['coverage hidden advisory', value => { value.coverage.targets.find(target => target.advisoryIds.length).advisoryIds.pop(); }],
  ['coverage invented advisory', value => { value.coverage.targets[0].advisoryIds.push('GHSA-foreign-advisory'); }],
  ['wrong expected Head', value => { value.options.head = '0'.repeat(40); }],
  ['wrong expected graph digest', value => { value.options.graphDigest = '0'.repeat(64); }],
  ['missing independent Head', value => { delete value.options.head; }],
  ['wrong E2 content', value => { value.e2.limitations.push('foreign-claim'); }],
  ['rehashed foreign E2 content', value => { value.e2.limitations.push('foreign-claim'); value.e2 = seal(value.e2); }],
  ['coverage Head drift', value => { value.coverage.commitSha = '0'.repeat(40); }],
  ['coverage E2 digest drift', value => { value.coverage.e2CurrentContentSha256 = '0'.repeat(64); }],
  ['coverage graph digest drift', value => { value.coverage.e2GraphDigest = '0'.repeat(64); }],
  ['coverage input byte hash drift', value => { value.coverage.inputBytesSha256 = '0'.repeat(64); }],
  ['coverage input target hash drift', value => { value.coverage.inputTargetSha256 = '0'.repeat(64); }],
  ['coverage count drift', value => { value.coverage.reportedPackageCount--; }],
  ['coverage completion drift', value => { value.coverage.allInputPackagesReported = false; }],
  ['unexpected coverage assertion', value => { value.coverage.safe = true; }],
]) test(`OSV verifier rejects ${name} even with a recomputed coverage self-hash`, () => {
  const value = fixture(); mutate(value); value.coverage = seal(value.coverage);
  assert.throws(() => verifyOsvCoverage(value.coverage, value.findings, value.e2, value.options));
});

test('OSV producer and verifier reject missing evidence rather than treating no report as a clean scan', () => {
  const value = fixture();
  assert.throws(() => verifyOsvCoverage(undefined, [], value.e2, value.options));
  assert.throws(() => requireOsvExecution({ stderr: '', exitStatus: 0, inputPath, packageCount: 535, findingCount: 0 }));
});

test('live scanner uses one complete-output OSV query and retains the exact current E2 identity', () => {
  const source = readFileSync(new URL('../security/m6-pr-e-e4-scan.mjs', import.meta.url), 'utf8');
  assert.equal((source.match(/run\(osv,\['scan'/g) || []).length, 1);
  assert.ok(source.includes("['scan','--all-packages','--format','json','--lockfile',`osv-scanner:${osvInput}`]"));
  assert.ok(source.includes('osvInputFromE2(e2)'));
  assert.ok(source.includes('inputBytes:readFileSync(osvInput)'));
  assert.ok(source.includes('stderr:rr.stderr,exitStatus:rr.status,findings:osvFindings'));
  assert.ok(source.includes('coverage:osvCoverage'));
  assert.ok(source.includes('e2CurrentEvidence:e2'));
  assert.ok(source.includes('verifyObservabilityGraph(e2,acceptedE2GraphProjection(e2),baseline.inheritedE2GraphDigest,head)'));
  const configGuard = source.indexOf("['osv-scanner.toml','.osv-scanner.toml']");
  assert.ok(configGuard >= 0 && configGuard < source.indexOf("run(osv,['scan'"));
  assert.ok(source.includes('requireOsvConfigAbsent([root,tmp])'));
  assert.ok(source.includes('unreviewed OSV suppression/config present'));
});
