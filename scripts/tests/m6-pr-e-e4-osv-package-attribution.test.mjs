import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { e2GraphDigest, normalizeOsv } from '../security/m6-pr-e-e4-scan.mjs';
import { buildOsvCoverage, osvInputFromE2, verifyOsvCoverage } from '../security/osv-scan-coverage.mjs';

const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
const canonical = value => JSON.stringify(stable(value));
const hash = value => createHash('sha256').update(value).digest('hex');
const seal = value => ({ ...value, contentSha256: hash(canonical(value)) });
const inputPath = '/tmp/test-only-osv-attribution/osv-scanner.json';
const unpatched = { ecosystem: 'Maven', name: 'fixture:unpatched', version: '1.0.0' };
const patched = { ecosystem: 'Maven', name: 'fixture:patched', version: '1.0.0' };
const sameNameNpm = { ...unpatched, ecosystem: 'npm' };
const affected = (pkg, events = [{ introduced: '0' }]) => ({
  package: { ecosystem: pkg.ecosystem, name: pkg.name },
  ranges: [{ type: pkg.ecosystem === 'npm' ? 'SEMVER' : 'ECOSYSTEM', events }],
});

// Deliberately synthetic reports exercise the actual producer without executing a
// scanner. They are not persisted or promoted to current all-scanner evidence.
function fixture(packages = [unpatched, patched]) {
  const e2 = seal({ schemaVersion: 'M6_PR_E_E2_SBOM_V1', repository: 'akaryc1b/approval-platform',
    commitSha: 'a'.repeat(40), githubActions: {}, limitations: ['SYNTHETIC_TEST_ONLY'],
    maven: { components: [], importedBoms: [], resolvedPluginCoordinates: packages
      .filter(pkg => pkg.ecosystem === 'Maven').map(pkg => `${pkg.name}:jar:${pkg.version}`) },
    pnpm: { external: packages.filter(pkg => pkg.ecosystem === 'npm')
      .map(pkg => ({ name: pkg.name, version: pkg.version, scope: 'devDependencies' })) },
  });
  const advisory = { id: 'GHSA-fixture-multi-package', aliases: ['CVE-FIXTURE', 'CVE-FIXTURE'],
    severity: [{ type: 'CVSS_V3', score: 'CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H' }],
    affected: [affected(unpatched), affected(patched, [{ introduced: '0' }, { fixed: '9.9.9' }]),
      affected(sameNameNpm, [{ introduced: '0' }, { fixed: '8.8.8' }])] };
  const raw = { results: [{ source: { path: inputPath, type: 'lockfile' },
    packages: packages.map(pkg => ({ package: pkg, vulnerabilities: [structuredClone(advisory)] })) }] };
  const input = osvInputFromE2(e2);
  const options = { head: e2.commitSha, graphDigest: e2GraphDigest(e2), inputPath,
    inputBytes: JSON.stringify(input.scannerInput), exitStatus: 1,
    stderr: `Starting filesystem walk for root: /\nScanned ${inputPath} file and found ${packages.length} packages\nEnd status: 0 dirs visited, 1 inodes visited, 1 Extract calls, 1ms elapsed, 1ms wall time\n` };
  return { e2, raw, input, options };
}

function produce(value) {
  const findings = normalizeOsv(value.raw, value.input.lookup);
  const coverage = buildOsvCoverage(value.raw, value.e2, { ...value.options, findings });
  assert.equal(verifyOsvCoverage(coverage, findings, value.e2, value.options), coverage);
  return { findings, coverage };
}

test('two queried Maven packages receive only their own fixed events from a shared advisory', () => {
  const value = fixture(), { findings, coverage } = produce(value);
  assert.equal(findings.length, 2);
  assert.deepEqual(findings.find(finding => finding.package.name === unpatched.name).fixedVersions, []);
  assert.deepEqual(findings.find(finding => finding.package.name === patched.name).fixedVersions, ['9.9.9']);
  assert.equal(coverage.reportedPackageCount, 2);
  assert.equal(coverage.findingCount, 2);
  assert.ok(coverage.targets.every(target => target.advisoryIds.length === 1));
  for (const finding of findings) {
    const pkg = finding.package;
    assert.equal(finding.findingId, hash(['OSV', 'GHSA-fixture-multi-package', pkg.ecosystem, pkg.name, pkg.version].join('\0')));
    assert.deepEqual(finding.aliases, ['CVE-FIXTURE']);
    assert.deepEqual(finding.upstreamSeverity, value.raw.results[0].packages[0].vulnerabilities[0].severity);
    assert.deepEqual(finding.componentRefs, [`maven-plugin:${pkg.name}:jar:${pkg.version}`]);
    assert.deepEqual(finding.scopes, ['build-plugin']);
    assert.equal(finding.sourceClass, 'E4_OSV_SCANNER');
  }
});

test('same package name in different ecosystems never shares fixed versions', () => {
  const { findings } = produce(fixture([unpatched, sameNameNpm]));
  assert.deepEqual(findings.find(finding => finding.package.ecosystem === 'Maven').fixedVersions, []);
  assert.deepEqual(findings.find(finding => finding.package.ecosystem === 'npm').fixedVersions, ['8.8.8']);
  assert.equal(new Set(findings.map(finding => finding.findingId)).size, 2);
});

test('single-package advisory keeps its sorted, deduplicated fixes and all normalized fields', () => {
  const value = fixture([patched]);
  value.raw.results[0].packages[0].vulnerabilities[0].affected = [affected(patched, [
    { introduced: '0' }, { fixed: '9.9.9' }, { introduced: '10' }, { fixed: '10.1.0' }, { fixed: '9.9.9' },
  ])];
  const before = structuredClone(value.raw);
  const { findings: [finding] } = produce(value);
  assert.deepEqual(finding.fixedVersions, ['10.1.0', '9.9.9']);
  assert.deepEqual(value.raw, before, 'normalization must not mutate advisory input');
});

test('all duplicate matching affected entries contribute fixes without duplicate findings or versions', () => {
  const value = fixture([patched]);
  const entries = value.raw.results[0].packages[0].vulnerabilities[0].affected;
  entries.push(affected(patched, [{ fixed: '2.0.0' }, { fixed: '9.9.9' }]), structuredClone(entries[1]));
  const { findings, coverage } = produce(value);
  assert.equal(findings.length, 1);
  assert.deepEqual(findings[0].fixedVersions, ['2.0.0', '9.9.9']);
  assert.equal(coverage.findingCount, 1);
});

test('matching entries with no fixed event retain a finding with no claimed patched version', () => {
  const value = fixture([unpatched]);
  value.raw.results[0].packages[0].vulnerabilities[0].affected.unshift(
    { package: { ecosystem: 'Maven', name: unpatched.name }, versions: ['1.0.0'] },
    affected(unpatched, [{ introduced: '0' }, { last_affected: '1.0.0' }, { limit: '2.0.0' }]),
  );
  const { findings, coverage } = produce(value);
  assert.deepEqual(findings[0].fixedVersions, []);
  assert.equal(coverage.findingCount, 1);
});

test('absent affected metadata retains the existing permitted empty-fix finding', () => {
  const value = fixture([unpatched]);
  delete value.raw.results[0].packages[0].vulnerabilities[0].affected;
  const { findings, coverage } = produce(value);
  assert.deepEqual(findings[0].fixedVersions, []);
  assert.equal(coverage.findingCount, 1);
});

for (const [label, entries] of [
  ['empty affected array', []],
  ['foreign package', [affected(patched, [{ fixed: '9.9.9' }])]],
  ['same name in another ecosystem', [affected(sameNameNpm, [{ fixed: '8.8.8' }])]],
  ['missing package coordinate', [{ ranges: [{ events: [{ fixed: '7.7.7' }] }] }]],
]) test(`coverage still rejects ${label} and normalization never borrows a fix`, () => {
  const value = fixture([unpatched]);
  value.raw.results[0].packages[0].vulnerabilities[0].affected = entries;
  assert.deepEqual(normalizeOsv(value.raw, value.input.lookup)[0].fixedVersions, []);
  assert.throws(() => produce(value), /OSV finding affected package differs from input target/);
});

test('corrected metadata changes coverage binding without changing target or advisory identity', () => {
  const value = fixture([unpatched]), { findings, coverage } = produce(value);
  const stale = structuredClone(findings); stale[0].fixedVersions = ['8.8.8', '9.9.9'];
  assert.throws(() => verifyOsvCoverage(coverage, stale, value.e2, value.options), /binding mismatch/);
  const fixed = fixture([patched]), before = produce(fixed);
  fixed.raw.results[0].packages[0].vulnerabilities[0].affected.push(affected(patched, [{ fixed: '10.1.0' }]));
  const after = produce(fixed);
  assert.notEqual(after.coverage.normalizedFindingsSha256, before.coverage.normalizedFindingsSha256);
  assert.notEqual(after.coverage.contentSha256, before.coverage.contentSha256);
  assert.deepEqual(after.coverage.targets, before.coverage.targets);
  assert.equal(after.coverage.inputTargetSha256, before.coverage.inputTargetSha256);
  assert.equal(after.coverage.inputBytesSha256, before.coverage.inputBytesSha256);
  assert.deepEqual(after.findings.map(({ fixedVersions, ...rest }) => rest),
    before.findings.map(({ fixedVersions, ...rest }) => rest));
});

// Traversal is part of the accepted scanner's rejection boundary. A valid
// matching entry must not hide malformed ranges/events in any other entry.
for (const [coordinate, pkg] of [
  ['foreign package', { ecosystem: 'Maven', name: patched.name }],
  ['foreign ecosystem', { ecosystem: 'npm', name: unpatched.name }],
  ['missing package coordinate', undefined],
  ['duplicate matching package', { ecosystem: 'Maven', name: unpatched.name }],
]) for (const [shape, ranges] of [
  ['ranges object', { malformed: true }],
  ['null range', [null]],
  ['events object', [{ events: { malformed: true } }]],
  ['null event', [{ events: [null] }]],
]) for (const position of ['before', 'after']) test(`reject malformed ${shape} in ${coordinate} ${position} a valid match`, () => {
  const value = fixture([unpatched]);
  const invalid = { ...(pkg ? { package: pkg } : {}), ranges: structuredClone(ranges) };
  const valid = affected(unpatched);
  value.raw.results[0].packages[0].vulnerabilities[0].affected = position === 'before'
    ? [invalid, valid] : [valid, invalid];
  assert.throws(() => normalizeOsv(value.raw, value.input.lookup), TypeError);
  assert.throws(() => produce(value), TypeError);
});

// The original normalizer sorts the complete advisory fixed-event inventory.
// Scoping must not hide malformed values whose coercion makes that sort fail.
for (const [coordinate, pkg] of [
  ['foreign package', { ecosystem: 'Maven', name: patched.name }],
  ['foreign ecosystem', { ecosystem: 'npm', name: unpatched.name }],
  ['missing package coordinate', undefined],
  ['duplicate matching package', { ecosystem: 'Maven', name: unpatched.name }],
]) for (const [shape, fixed] of [
  ['non-callable null toString', { toString: null }],
  ['non-callable string toString', { toString: 'malformed' }],
  ['array containing malformed value', [{ toString: null }]],
]) for (const position of ['before', 'after']) test(`reject fixed-event ${shape} in ${coordinate} ${position} an ordinary fixed version`, () => {
  const value = fixture([unpatched]);
  const invalid = { ...(pkg ? { package: pkg } : {}), ranges: [{ events: [{ fixed: structuredClone(fixed) }] }] };
  const valid = affected(unpatched, [{ introduced: '0' }, { fixed: '1.0.1' }]);
  value.raw.results[0].packages[0].vulnerabilities[0].affected = position === 'before'
    ? [invalid, valid] : [valid, invalid];
  assert.throws(() => normalizeOsv(value.raw, value.input.lookup), TypeError);
  assert.throws(() => produce(value), TypeError);
});
