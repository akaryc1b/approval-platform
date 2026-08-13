import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const root = new URL('../../', import.meta.url);

async function text(path) {
  return readFile(new URL(path, root), 'utf8');
}

async function json(path) {
  return JSON.parse(await text(path));
}

const rebaselinePath = 'docs/m6/M6_PR_E_E0_SECURITY_DEPENDENCY_REBASELINE_AND_THREAT_MODEL.md';
const contractPath = 'docs/m6/M6_PR_E_SECURITY_EVIDENCE_SOURCE_CONTRACT.md';
const ownershipPath = 'docs/m6/M6_PR_E_SECURITY_OWNERSHIP_AND_REMEDIATION_MATRIX.md';
const schemaPath = 'docs/m6/m6-pr-e-security-evidence-envelope.schema.json';

const dispositions = [
  'APPLICABLE',
  'NOT_APPLICABLE',
  'UNREACHABLE',
  'MITIGATED',
  'ACCEPTED_WITH_EXPIRY',
  'UNRESOLVED',
  'EVIDENCE_UNAVAILABLE',
];

const availabilityStates = [
  'AVAILABLE_COMPLETE',
  'AVAILABLE_EMPTY',
  'AVAILABLE_PARTIAL',
  'DISABLED',
  'INELIGIBLE',
  'PERMISSION_DENIED',
  'AUTHENTICATION_FAILED',
  'RATE_LIMITED',
  'TRANSIENT_FAILURE',
  'EVIDENCE_UNAVAILABLE',
];

test('E0 retains the exact no-zero-claim boundary', async () => {
  const document = await text(rebaselinePath);
  for (const marker of [
    'NO_ALERT_API_VISIBILITY != ZERO_ALERTS',
    'PERMISSION_DENIED != ZERO_ALERTS',
    'FEATURE_DISABLED != ZERO_ALERTS',
    'RESOURCE_NOT_FOUND != ZERO_ALERTS',
    'EMPTY_FIRST_PAGE != COMPLETE_EMPTY_INVENTORY',
    'DEPENDENCY_UPDATE_PR != APPLICABLE_VULNERABILITY',
    'SBOM_EXPORT != COMPLETE_REACHABILITY_ANALYSIS',
  ]) {
    assert.match(document, new RegExp(marker.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
  }
});

test('finding dispositions are closed and identical in docs and schema', async () => {
  const contract = await text(contractPath);
  const schema = await json(schemaPath);
  const schemaDispositions = schema.properties.findingDispositions.items.enum;
  assert.deepEqual(schemaDispositions, dispositions);
  for (const disposition of dispositions) {
    assert.match(contract, new RegExp(`\\b${disposition}\\b`));
  }
});

test('availability states are closed and fail-closed', async () => {
  const contract = await text(contractPath);
  const schema = await json(schemaPath);
  assert.deepEqual(schema.properties.availability.enum, availabilityStates);
  assert.match(contract, /`403` \| `PERMISSION_DENIED`/);
  assert.match(contract, /`404` \| `EVIDENCE_UNAVAILABLE`; never infer empty/);
});

test('evidence envelope requires exact SHA, redaction and retention', async () => {
  const schema = await json(schemaPath);
  assert.equal(schema.properties.commitSha.pattern, '^[0-9a-f]{40}$');
  assert.equal(schema.properties.payloadDigest.pattern, '^[0-9a-f]{64}$');
  assert.equal(schema.properties.redaction.properties.candidateSecretsExcluded.const, true);
  assert.equal(schema.properties.redaction.properties.customerDataExcluded.const, true);
  assert.equal(schema.properties.redaction.properties.rawCredentialsExcluded.const, true);
  for (const required of ['commitSha', 'redaction', 'retention', 'payloadDigest', 'result']) {
    assert.ok(schema.required.includes(required));
  }
});

test('E0 does not claim alert or applicability closure', async () => {
  const rebaseline = await text(rebaselinePath);
  const contract = await text(contractPath);
  for (const marker of [
    'PRB_16_REMAINS_OPEN',
    'PRB_17_REMAINS_OPEN',
    'M6_PR_E_E0_REMOTE_BINDING_PENDING',
    'NO_SCANNER_IMPLEMENTATION_IN_E0',
    'NO_DEPENDENCY_UPGRADE_IN_E0',
  ]) {
    assert.match(rebaseline, new RegExp(marker));
  }
  assert.match(contract, /ALERT_INVENTORIES_NOT_YET_OBTAINED/);
  assert.match(contract, /DEPENDENCY_APPLICABILITY_NOT_YET_PROVEN/);
});

test('logical ownership is explicit without invented identities', async () => {
  const ownership = await text(ownershipPath);
  assert.match(ownership, /CODEOWNERS_NOT_PROVEN/);
  assert.match(ownership, /UNASSIGNED_GITHUB_IDENTITY/);
  assert.match(ownership, /CODEOWNERS_ENFORCEMENT_PENDING/);
  assert.match(ownership, /NO_EXCEPTION_ACCEPTED_IN_E0/);
});

test('permanent M6 authority and production boundaries remain frozen', async () => {
  const rebaseline = await text(rebaselinePath);
  for (const marker of [
    'NO_PRODUCT_AUTHORITY_EXPANSION',
    'AI_IS_NOT_AN_OPERATOR',
    'Production Reauthentication',
    'Production Promotion',
  ]) {
    assert.match(rebaseline, new RegExp(marker));
  }
});
