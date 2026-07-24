package io.github.akaryc1b.approval.sdk.v1;

import static io.github.akaryc1b.approval.sdk.v1.SdkAuditCompletenessV1.emitCompleteAuditBatch;
import static io.github.akaryc1b.approval.sdk.v1.SdkAuditCompletenessV1.validateAuditCompleteness;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.akaryc1b.approval.sdk.v1.SdkAuditCompletenessV1.AuditBatchEmissionResult;
import io.github.akaryc1b.approval.sdk.v1.SdkAuditCompletenessV1.AuditBatchEmissionStatus;
import io.github.akaryc1b.approval.sdk.v1.SdkAuditCompletenessV1.AuditCompletenessPolicy;
import io.github.akaryc1b.approval.sdk.v1.SdkAuditCompletenessV1.AuditCompletenessReason;
import io.github.akaryc1b.approval.sdk.v1.SdkAuditCompletenessV1.AuditCompletenessValidation;
import io.github.akaryc1b.approval.sdk.v1.SdkAuditCompletenessV1.AuditEmissionRecord;
import io.github.akaryc1b.approval.sdk.v1.SdkAuditCompletenessV1.ScriptedAtomicAuditSink;
import io.github.akaryc1b.approval.sdk.v1.SdkDiagnosticsAuditV1.AdapterAuditEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SdkAuditCompletenessV1Test {
    @Test
    void fixtureProducesDeterministicProofAndAtomicCommit() throws IOException {
        Map<String, Object> fixture = EmissionPolicyFixtureSupport.fixture();
        Map<String, Object> expected = EmissionPolicyFixtureSupport.object(fixture, "expectations");
        AuditCompletenessPolicy policy = EmissionPolicyFixtureSupport.auditPolicy(fixture);
        List<AuditEmissionRecord> records = EmissionPolicyFixtureSupport.auditRecords(fixture);
        AuditCompletenessValidation validation = validateAuditCompleteness(policy, records);
        assertTrue(validation.complete());
        assertEquals(expected.get("identityDigest"), validation.proof().identityDigest());
        assertEquals(expected.get("batchDigest"), validation.proof().batchDigest());
        ScriptedAtomicAuditSink sink = new ScriptedAtomicAuditSink(8);
        AuditBatchEmissionResult result = emitCompleteAuditBatch(policy, records, sink);
        assertEquals(AuditBatchEmissionStatus.COMMITTED, result.status());
        assertEquals(4, sink.size());
    }

    @Test
    void missingReorderedAndIdentityDriftRecordsFailClosed() throws IOException {
        Map<String, Object> fixture = EmissionPolicyFixtureSupport.fixture();
        AuditCompletenessPolicy policy = EmissionPolicyFixtureSupport.auditPolicy(fixture);
        List<AuditEmissionRecord> missing = new ArrayList<>(
            EmissionPolicyFixtureSupport.auditRecords(fixture)
        );
        missing.remove(missing.size() - 1);
        assertEquals(
            AuditCompletenessReason.RECORD_COUNT_MISMATCH,
            validateAuditCompleteness(policy, missing).reason()
        );
        List<AuditEmissionRecord> reordered = new ArrayList<>(
            EmissionPolicyFixtureSupport.auditRecords(fixture)
        );
        AuditEmissionRecord original = reordered.get(1);
        reordered.set(1, new AuditEmissionRecord("1", 2, original.phase(), original.event()));
        assertEquals(
            AuditCompletenessReason.SEQUENCE_MISMATCH,
            validateAuditCompleteness(policy, reordered).reason()
        );
        List<AuditEmissionRecord> drift = new ArrayList<>(
            EmissionPolicyFixtureSupport.auditRecords(fixture)
        );
        AuditEmissionRecord attempt = drift.get(2);
        AdapterAuditEvent event = attempt.event();
        drift.set(2, new AuditEmissionRecord(
            "1",
            attempt.sequence(),
            attempt.phase(),
            new AdapterAuditEvent(
                event.contractVersion(),
                event.eventId(),
                event.eventType(),
                event.endpointId(),
                event.operation(),
                "req-drift",
                event.traceId(),
                event.bindingId(),
                event.authenticationContextId(),
                event.outcome(),
                event.reasonCode(),
                event.occurredAtEpochSeconds(),
                event.provenanceDigest()
            )
        ));
        assertEquals(
            AuditCompletenessReason.IDENTITY_MISMATCH,
            validateAuditCompleteness(policy, drift).reason()
        );
        ScriptedAtomicAuditSink sink = new ScriptedAtomicAuditSink(8);
        assertEquals(
            AuditBatchEmissionStatus.FAILED_CLOSED,
            emitCompleteAuditBatch(policy, drift, sink).status()
        );
        assertEquals(0, sink.size());
    }

    @Test
    void capacityFailureAndDuplicateBatchesNeverPartiallyCommit() throws IOException {
        Map<String, Object> fixture = EmissionPolicyFixtureSupport.fixture();
        AuditCompletenessPolicy policy = EmissionPolicyFixtureSupport.auditPolicy(fixture);
        List<AuditEmissionRecord> records = EmissionPolicyFixtureSupport.auditRecords(fixture);
        ScriptedAtomicAuditSink capacitySink = new ScriptedAtomicAuditSink(3);
        assertEquals(
            "audit_sink_capacity",
            emitCompleteAuditBatch(policy, records, capacitySink).reasonCode()
        );
        assertEquals(0, capacitySink.size());
        ScriptedAtomicAuditSink failedSink = new ScriptedAtomicAuditSink(8, List.of(1));
        AuditBatchEmissionResult failed = emitCompleteAuditBatch(policy, records, failedSink);
        assertEquals("audit_sink_failed", failed.reasonCode());
        assertFalse(failed.toString().contains("scripted audit sink failure"));
        assertEquals(0, failedSink.size());
        ScriptedAtomicAuditSink duplicateSink = new ScriptedAtomicAuditSink(12);
        assertEquals(
            AuditBatchEmissionStatus.COMMITTED,
            emitCompleteAuditBatch(policy, records, duplicateSink).status()
        );
        assertEquals(
            "audit_duplicate_batch",
            emitCompleteAuditBatch(policy, records, duplicateSink).reasonCode()
        );
        assertEquals(4, duplicateSink.size());
    }
}
