import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import {
  ScriptedSecurityBoundAdapter,
  StaticAuthenticationContextResolver,
  StaticCredentialLeaseProvider,
  UnsupportedAdapterBindingVersionError,
  credentialLeaseBindingId,
  executeSecurityBoundTransportConformance,
  validateLogicalEndpointDescriptor,
} from '../dist/adapter-binding.js';

const fixture = JSON.parse(await readFile(new URL('../../../contracts/sdk/v1/fixtures/adapter-binding-v1.json', import.meta.url), 'utf8'));
function setup(overrides = {}) {
  const resolver = new StaticAuthenticationContextResolver(overrides.authenticationContext ?? fixture.authenticationContext);
  const provider = new StaticCredentialLeaseProvider(overrides.credentialLease ?? fixture.credentialLease);
  const adapter = new ScriptedSecurityBoundAdapter(overrides.script ?? fixture.script);
  return { resolver, provider, adapter };
}

test('cross-language binding fixture executes deterministically', () => {
  const { resolver, provider, adapter } = setup();
  const result = executeSecurityBoundTransportConformance({
    request: fixture.request,
    policy: fixture.policy,
    endpoint: fixture.endpoint,
    authenticationContextResolver: resolver,
    credentialLeaseProvider: provider,
    adapter,
    nowEpochSeconds: fixture.nowEpochSeconds,
  });
  assert.equal(result.status, fixture.expectations.bindingStatus);
  assert.equal(result.transport.status, fixture.expectations.transportStatus);
  assert.deepEqual(result.lifecycle, fixture.expectations.lifecycle);
  assert.equal(result.credentialReleased, true);
  assert.equal(result.transport.totalElapsedMillis, fixture.expectations.totalElapsedMillis);
  assert.equal(resolver.invocations.length, 1);
  assert.equal(provider.acquisitions.length, 1);
  assert.deepEqual(provider.releases, [{leaseId: fixture.credentialLease.leaseId, reason: 'completed'}]);
  assert.equal(adapter.invocations.length, 2);
  assert.ok(adapter.invocations.every((attempt) => attempt.request === fixture.request));
  assert.ok(adapter.invocations.every((attempt) => attempt.authenticationContext.contextId === fixture.authenticationContext.contextId));
  assert.ok(adapter.invocations.every((attempt) => attempt.credentialLease.leaseId === fixture.credentialLease.leaseId));
});

test('binding id is deterministic and contains references only', () => {
  assert.equal(credentialLeaseBindingId(
    fixture.endpoint.endpointId,
    fixture.authenticationContext.contextId,
    fixture.endpoint.credentialReference.credentialId,
    fixture.request.operation,
  ), fixture.credentialLease.bindingId);
  assert.deepEqual(Object.keys(fixture.endpoint).sort(), ['adapterKind','audience','bindingVersion','credentialReference','endpointId','supportedOperations']);
  assert.deepEqual(Object.keys(fixture.endpoint.credentialReference).sort(), ['credentialId','kind','providerId']);
});

test('unknown binding version and duplicate operations fail closed', () => {
  assert.throws(() => validateLogicalEndpointDescriptor({...fixture.endpoint,bindingVersion:'2'}), UnsupportedAdapterBindingVersionError);
  assert.throws(() => validateLogicalEndpointDescriptor({...fixture.endpoint,supportedOperations:['approval.task.read','approval.task.read']}), /duplicate/i);
});

test('unsupported operation is rejected before server binding resolution', () => {
  const { resolver, provider, adapter } = setup();
  const result = executeSecurityBoundTransportConformance({
    request: {...fixture.request, operation:'approval.task.write'},
    policy: {...fixture.policy, operation:'approval.task.write'},
    endpoint: fixture.endpoint,
    authenticationContextResolver: resolver,
    credentialLeaseProvider: provider,
    adapter,
    nowEpochSeconds: fixture.nowEpochSeconds,
  });
  assert.equal(result.status, 'binding_failed');
  assert.equal(result.error.code, 'adapter_operation_not_supported');
  assert.equal(resolver.invocations.length, 0);
  assert.equal(provider.acquisitions.length, 0);
  assert.deepEqual(adapter.lifecycleEvents(), ['created']);
});

test('expired authentication context and credential lease fail closed', () => {
  const expiredContext = {...fixture.authenticationContext, expiresAtEpochSeconds: fixture.nowEpochSeconds};
  const contextSetup = setup({authenticationContext: expiredContext});
  const contextResult = executeSecurityBoundTransportConformance({
    request: fixture.request, policy: fixture.policy, endpoint: fixture.endpoint,
    authenticationContextResolver: contextSetup.resolver, credentialLeaseProvider: contextSetup.provider,
    adapter: contextSetup.adapter, nowEpochSeconds: fixture.nowEpochSeconds,
  });
  assert.equal(contextResult.error.code, 'authentication_context_expired');
  assert.equal(contextSetup.provider.acquisitions.length, 0);

  const expiredLease = {...fixture.credentialLease, issuedAtEpochSeconds: fixture.nowEpochSeconds - 10, expiresAtEpochSeconds: fixture.nowEpochSeconds};
  const leaseSetup = setup({credentialLease: expiredLease});
  const leaseResult = executeSecurityBoundTransportConformance({
    request: fixture.request, policy: fixture.policy, endpoint: fixture.endpoint,
    authenticationContextResolver: leaseSetup.resolver, credentialLeaseProvider: leaseSetup.provider,
    adapter: leaseSetup.adapter, nowEpochSeconds: fixture.nowEpochSeconds,
  });
  assert.equal(leaseResult.error.code, 'credential_lease_expired');
  assert.equal(leaseResult.credentialReleased, true);
  assert.equal(leaseSetup.provider.releases[0].reason, 'binding_failed');
});

test('credential binding mismatch is rejected and released', () => {
  const mismatched = {...fixture.credentialLease, bindingId: 'wrong'};
  const { resolver, provider, adapter } = setup({credentialLease:mismatched});
  const result = executeSecurityBoundTransportConformance({
    request: fixture.request, policy: fixture.policy, endpoint: fixture.endpoint,
    authenticationContextResolver: resolver, credentialLeaseProvider: provider,
    adapter, nowEpochSeconds: fixture.nowEpochSeconds,
  });
  assert.equal(result.error.code, 'credential_binding_mismatch');
  assert.equal(result.credentialReleased, true);
  assert.deepEqual(adapter.lifecycleEvents(), ['created']);
});

test('public request and resolver input cannot carry trusted evidence', () => {
  const { resolver, provider, adapter } = setup();
  executeSecurityBoundTransportConformance({
    request: fixture.request, policy: fixture.policy, endpoint: fixture.endpoint,
    authenticationContextResolver: resolver, credentialLeaseProvider: provider,
    adapter, nowEpochSeconds: fixture.nowEpochSeconds,
  });
  assert.deepEqual(Object.keys(fixture.request).sort(), ['correlation','idempotencyKey','operation','payload']);
  assert.deepEqual(Object.keys(resolver.invocations[0]).sort(), ['correlation','endpointId','operation']);
  for (const forbidden of ['tenantId','operatorId','permissionSnapshotHash','auditReference','credentialLease','credentialMaterial']) {
    assert.equal(forbidden in fixture.request, false);
    assert.equal(forbidden in resolver.invocations[0], false);
  }
});
