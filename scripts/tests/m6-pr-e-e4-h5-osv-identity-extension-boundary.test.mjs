import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { reconcileH5OsvFindings, retainedH5OsvIdentityIds } from '../security/m6-pr-e-e3-verify-workflow-supply-chain-remediation.mjs';

const retained = retainedH5OsvIdentityIds();
const id = (value) => createHash('sha256').update(value).digest('hex');
const addition = (index) => ({
  sourceClass: 'E4_OSV_SCANNER',
  findingId: id(`h5-osv-addition-${index}`),
  upstreamFindingId: `GHSA-h5-test-${index}`,
  aliases: [`CVE-2099-${String(index).padStart(4, '0')}`],
  package: { ecosystem: 'Maven', name: `example:h5-${index}`, version: '1.0.0' },
  componentRefs: [`pkg:maven/example/h5-${index}@1.0.0?type=jar`],
  scopes: ['test'],
  upstreamSeverity: [],
  fixedVersions: [],
});
const scanner = (extras = [addition(1)]) => ({
  scanCompleted: true,
  rawReportRetained: false,
  findingCount: retained.length + extras.length,
  findings: [
    ...retained.map((findingId) => ({ sourceClass: 'E4_OSV_SCANNER', findingId })),
    ...extras,
  ],
});

test('H5 OSV extension retains all 117 accepted identities and surfaces additions unresolved', () => {
  const result = reconcileH5OsvFindings(scanner());
  assert.equal(result.retainedOsvFindingCount, 117);
  assert.equal(result.retainedOsvFindingSetSha256, '42d4ce93ce58eb76e07faa556d32c6b8f7feb1e9a3f3f600eab9c971c3fe5da6');
  assert.equal(result.addedOsvFindingCount, 1);
  assert.equal(result.addedOsvFindings[0].disposition, 'UNRESOLVED');
  assert.equal(result.addedOsvFindings[0].reviewRequired, true);
});

test('H5 OSV extension fails closed on retained identity deletion', () => {
  const value = scanner();
  value.findings.shift();
  value.findingCount -= 1;
  assert.throws(() => reconcileH5OsvFindings(value), /retained identity missing/);
});

test('H5 OSV extension fails closed on duplicate or incomplete additions', () => {
  const duplicate = scanner([addition(1), addition(1)]);
  assert.throws(() => reconcileH5OsvFindings(duplicate), /duplicate finding identity/);
  const incomplete = scanner([{ sourceClass: 'E4_OSV_SCANNER', findingId: id('incomplete') }]);
  assert.throws(() => reconcileH5OsvFindings(incomplete), /unresolved addition evidence incomplete/);
});

test('H5 OSV extension keeps unreviewed evidence bounded', () => {
  assert.throws(
    () => reconcileH5OsvFindings(scanner(Array.from({ length: 65 }, (_, index) => addition(index + 1)))),
    /unresolved addition bound exceeded 65/,
  );
});
