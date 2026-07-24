import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import {
  FakeConfigurationSource,
  InMemoryAdapterAuditSink,
  UnsupportedDiagnosticsAuditVersionError,
  assertNoSensitiveLiteral,
  createAdapterAuditEvent,
  emitAdapterAuditEvent,
  renderExceptionDiagnostic,
  renderSafeDiagnostic,
  resolveFakeConfiguration,
} from '../dist/diagnostics-audit.js';

const fixture = JSON.parse(await readFile(new URL('../../../contracts/sdk/v1/fixtures/diagnostics-audit-v1.json', import.meta.url), 'utf8'));

async function resolved() {
  return new FakeConfigurationSource(fixture.configuration).load();
}

test('shared fixture produces deterministic provenance and reference-only sensitive values', async () => {
  const configuration = await resolved();
  assert.equal(configuration.provenance.contentDigest, fixture.expectations.contentDigest);
  assert.deepEqual(configuration.publicValues, fixture.expectations.publicValues);
  assert.deepEqual(configuration.sensitiveReferences, fixture.expectations.sensitiveReferences);
  assert.equal(JSON.stringify(configuration).includes('DO-NOT-LEAK'), false);
});

test('diagnostic rendering redacts sensitive keys and literal occurrences', async () => {
  const configuration = await resolved();
  const safe = renderSafeDiagnostic(configuration, fixture.diagnostic);
  assert.deepEqual(safe, fixture.expectations.safeDiagnostic);
  assert.doesNotMatch(JSON.stringify(safe), /DO-NOT-LEAK/);
});

test('exception diagnostics never include raw exception message or stack', async () => {
  const configuration = await resolved();
  const diagnostic = renderExceptionDiagnostic(configuration, {
    code: 'adapter.exception', severity: 'error', requestId: 'req-fixture-001',
    error: new Error('fixture-secret-DO-NOT-LEAK at stack'),
  });
  assert.equal(diagnostic.message, 'Adapter operation failed');
  assert.equal(diagnostic.context.exceptionType, 'Error');
  assert.doesNotMatch(JSON.stringify(diagnostic), /DO-NOT-LEAK|stack/);
});

test('audit event contains references and provenance only', async () => {
  const configuration = await resolved();
  const event = createAdapterAuditEvent(configuration, fixture.audit);
  assert.equal(event.provenanceDigest, fixture.expectations.auditProvenanceDigest);
  assert.deepEqual(Object.keys(event).sort(), [
    'authenticationContextId','bindingId','contractVersion','endpointId','eventId','eventType',
    'occurredAtEpochSeconds','operation','outcome','provenanceDigest','reasonCode','requestId','traceId',
  ]);
  assert.doesNotMatch(JSON.stringify(event), /tenant|operator|permission|auditReference|DO-NOT-LEAK/i);
});

test('in-memory audit sink records immutable validated events', async () => {
  const configuration = await resolved();
  const sink = new InMemoryAdapterAuditSink();
  emitAdapterAuditEvent(sink, createAdapterAuditEvent(configuration, fixture.audit));
  assert.equal(sink.events.length, fixture.expectations.sinkCount);
  assert.equal(sink.events[0].eventId, fixture.audit.eventId);
});

test('unknown version and duplicate configuration keys fail closed', async () => {
  await assert.rejects(
    () => resolveFakeConfiguration({ ...fixture.configuration, contractVersion: '2' }),
    UnsupportedDiagnosticsAuditVersionError,
  );
  await assert.rejects(
    () => resolveFakeConfiguration({
      ...fixture.configuration,
      entries: [...fixture.configuration.entries, fixture.configuration.entries[0]],
    }),
    /Duplicate configuration key/,
  );
});

test('redaction assertion rejects leaked sensitive literals and sensitive output keys', async () => {
  const configuration = await resolved();
  assert.throws(() => assertNoSensitiveLiteral(configuration, 'fixture-secret-DO-NOT-LEAK'), /escaped redaction/);
  const safe = renderSafeDiagnostic(configuration, {
    contractVersion: '1', code: 'safe', severity: 'info', message: 'ok',
    context: { password: 'not-a-fixture-secret' },
  });
  assert.equal(safe.context.password, '[REDACTED]');
});
