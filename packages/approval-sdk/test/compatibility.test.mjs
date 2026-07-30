import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import {
  UnsupportedCompatibilityManifestError,
  compareSemanticVersions,
  negotiateCompatibility,
  parseSemanticVersion,
} from '../dist/public.js';

const fixture = JSON.parse(await readFile(new URL('../../../contracts/sdk/v1/fixtures/sdk-compatibility-v1.json', import.meta.url), 'utf8'));
const negotiate = (client = fixture.client, server = fixture.server, evaluatedAt = fixture.evaluatedAt) =>
  negotiateCompatibility(client, server, evaluatedAt);

test('fixture negotiation is deterministic', () => {
  assert.deepEqual(negotiate(), fixture.expectations);
});

test('minimum client version fails closed', () => {
  assert.equal(negotiate({ ...fixture.client, sdkVersion: '0.9.9' }).status, 'client_upgrade_required');
});

test('schema and protocol intersections are required', () => {
  assert.equal(negotiate({ ...fixture.client, eventSchemaVersions: ['2.0'] }).status, 'no_common_event_schema');
  assert.equal(negotiate({ ...fixture.client, webhookProtocolVersions: ['approval-webhook-v2'] }).status, 'no_common_webhook_protocol');
});

test('unknown required capability fails while optional capability only warns', () => {
  const required = { ...fixture.client, capabilities: [{ name: 'missing.capability', required: true }] };
  assert.equal(negotiate(required).status, 'required_capability_unavailable');
  const optional = { ...fixture.client, capabilities: [{ name: 'missing.capability', required: false }] };
  assert.deepEqual(negotiate(optional), {
    status: 'compatible',
    contractVersion: fixture.server.contractVersion,
    eventSchemaVersion: '1.0',
    webhookProtocolVersion: 'approval-webhook-v1',
    enabledCapabilities: [],
    warnings: ['optional capability unavailable: missing.capability'],
  });
});

test('support expiry and capability sunset are enforced', () => {
  assert.equal(negotiate(fixture.client, fixture.server, fixture.server.supportedUntil).status, 'contract_support_expired');
  const requiredLegacy = { ...fixture.client, capabilities: [{ name: 'fixture.legacy-error-shape.v1', required: true }] };
  assert.equal(negotiate(requiredLegacy, fixture.server, '2027-01-01T00:00:00Z').status, 'required_capability_sunset');
  const optionalLegacy = { ...fixture.client, capabilities: [{ name: 'fixture.legacy-error-shape.v1', required: false }] };
  const optionalResult = negotiate(optionalLegacy, fixture.server, '2027-01-01T00:00:00Z');
  assert.equal(optionalResult.status, 'compatible');
  assert.deepEqual(optionalResult.enabledCapabilities, []);
  assert.match(optionalResult.warnings[0], /^optional capability sunset:/);
});

test('unknown manifest and invalid semantic versions are rejected', () => {
  assert.throws(
    () => negotiate({ ...fixture.client, manifestVersion: '2' }),
    UnsupportedCompatibilityManifestError,
  );
  assert.deepEqual(parseSemanticVersion('12.3.4'), { major: 12, minor: 3, patch: 4 });
  assert.ok(compareSemanticVersions('1.10.0', '1.9.9') > 0);
  for (const invalid of ['1.0', '01.0.0', '1.0.0-beta', '1.0.0.0']) {
    assert.throws(() => parseSemanticVersion(invalid), TypeError);
  }
});

test('compatibility profiles cannot carry trusted server evidence', () => {
  for (const profile of [fixture.client, fixture.server]) {
    for (const forbidden of ['tenantId', 'operator', 'permission', 'authority', 'auditEvidence']) {
      assert.equal(forbidden in profile, false);
    }
  }
});
