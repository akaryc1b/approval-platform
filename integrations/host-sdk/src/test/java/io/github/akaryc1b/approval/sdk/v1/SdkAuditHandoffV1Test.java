package io.github.akaryc1b.approval.sdk.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.akaryc1b.approval.sdk.v1.SdkAuditCompletenessV1.AuditCompletenessProof;
import io.github.akaryc1b.approval.sdk.v1.SdkAuditHandoffV1.AuditHandoffEnvelope;
import io.github.akaryc1b.approval.sdk.v1.SdkAuditHandoffV1.AuditHandoffInput;
import io.github.akaryc1b.approval.sdk.v1.SdkAuditHandoffV1.AuditHandoffStatus;
import io.github.akaryc1b.approval.sdk.v1.SdkAuditHandoffV1.ScriptedAuditHandoffQueue;
import io.github.akaryc1b.approval.sdk.v1.SdkAuditHandoffV1.ScriptedHandoffOutcome;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SdkAuditHandoffV1Test {
    @Test
    void sharedFixtureProducesExactEnvelopeAndAcknowledgementSequence() throws IOException {
        Map<String, Object> fixture = TelemetryHandoffFixtureSupport.fixture();
        AuditHandoffEnvelope envelope = envelope(fixture);
        ScriptedAuditHandoffQueue queue = queue(fixture);
        List<Object> actual = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            actual.add(SdkAuditHandoffV1.auditHandoffResultMap(queue.handoff(envelope)));
        }
        Map<String, Object> expectations = TelemetryHandoffFixtureSupport.object(
            fixture,
            "expectations"
        );
        assertEquals(
            CanonicalJson.canonicalizeValue(expectations.get("auditHandoffEnvelope")),
            CanonicalJson.canonicalizeValue(
                SdkAuditHandoffV1.auditHandoffEnvelopeMap(envelope)
            )
        );
        assertEquals(
            CanonicalJson.canonicalizeValue(expectations.get("auditHandoffResults")),
            CanonicalJson.canonicalizeValue(actual)
        );
    }

    @Test
    void nackTimeoutAndFailureKeepPendingUntilAtomicAcknowledgement() throws IOException {
        AuditHandoffEnvelope envelope = envelope(TelemetryHandoffFixtureSupport.fixture());
        ScriptedAuditHandoffQueue queue = new ScriptedAuditHandoffQueue(
            1,
            List.of(
                ScriptedHandoffOutcome.NACK,
                ScriptedHandoffOutcome.TIMEOUT_LIKE,
                ScriptedHandoffOutcome.FAILURE,
                ScriptedHandoffOutcome.ACK
            )
        );
        assertEquals(AuditHandoffStatus.PENDING, queue.handoff(envelope).status());
        assertEquals(AuditHandoffStatus.PENDING, queue.handoff(envelope).status());
        var failed = queue.handoff(envelope);
        assertEquals(AuditHandoffStatus.FAILED_CLOSED, failed.status());
        assertEquals(1, failed.pendingCount());
        var acknowledged = queue.handoff(envelope);
        assertEquals(AuditHandoffStatus.ACKNOWLEDGED, acknowledged.status());
        assertEquals(0, acknowledged.pendingCount());
        assertEquals(1, acknowledged.acknowledgedCount());
    }

    @Test
    void capacityAndIdentityConflictFailClosedWithoutReplacingExistingState() throws IOException {
        AuditHandoffEnvelope first = envelope(TelemetryHandoffFixtureSupport.fixture());
        AuditHandoffEnvelope second = SdkAuditHandoffV1.createAuditHandoffEnvelope(
            new AuditHandoffInput(
                "1",
                "handoff-fixture-002",
                first.destinationReference(),
                30,
                proof(TelemetryHandoffFixtureSupport.fixture())
            )
        );
        ScriptedAuditHandoffQueue full = new ScriptedAuditHandoffQueue(
            1,
            List.of(ScriptedHandoffOutcome.NACK)
        );
        full.handoff(first);
        assertEquals(AuditHandoffStatus.CAPACITY_REJECTED, full.handoff(second).status());
        assertEquals(1, full.pendingCount());

        ScriptedAuditHandoffQueue acknowledged = new ScriptedAuditHandoffQueue(
            1,
            List.of(ScriptedHandoffOutcome.ACK)
        );
        acknowledged.handoff(first);
        AuditHandoffEnvelope conflict = new AuditHandoffEnvelope(
            first.contractVersion(),
            first.handoffId(),
            first.destinationReference(),
            first.handoffOrdinal(),
            first.auditBatchDigest(),
            first.auditIdentityDigest(),
            first.recordCount(),
            first.attemptCount(),
            first.firstEventId(),
            first.terminalEventId(),
            "conflicting-digest"
        );
        assertEquals(
            AuditHandoffStatus.FAILED_CLOSED,
            acknowledged.handoff(conflict).status()
        );
        assertEquals(1, acknowledged.acknowledgedCount());
    }

    @Test
    void duplicateAcknowledgementIsIdempotentAndIncompleteProofIsRejected() throws IOException {
        Map<String, Object> fixture = TelemetryHandoffFixtureSupport.fixture();
        AuditHandoffEnvelope envelope = envelope(fixture);
        ScriptedAuditHandoffQueue queue = new ScriptedAuditHandoffQueue(
            1,
            List.of(ScriptedHandoffOutcome.ACK)
        );
        var first = queue.handoff(envelope);
        var duplicate = queue.handoff(envelope);
        assertEquals(AuditHandoffStatus.DUPLICATE_ACKNOWLEDGED, duplicate.status());
        assertEquals(
            first.acknowledgement().acknowledgementId(),
            duplicate.acknowledgement().acknowledgementId()
        );
        AuditCompletenessProof valid = proof(fixture);
        assertThrows(
            IllegalArgumentException.class,
            () -> new AuditHandoffInput(
                "1",
                "invalid",
                "audit-queue-fixture",
                0,
                new AuditCompletenessProof(
                    "1",
                    3,
                    valid.attemptCount(),
                    valid.firstEventId(),
                    valid.terminalEventId(),
                    valid.identityDigest(),
                    valid.batchDigest()
                )
            )
        );
    }

    private static AuditHandoffEnvelope envelope(Map<String, Object> fixture) {
        Map<String, Object> raw = TelemetryHandoffFixtureSupport.object(
            fixture,
            "auditHandoff"
        );
        return SdkAuditHandoffV1.createAuditHandoffEnvelope(
            new AuditHandoffInput(
                (String) raw.get("contractVersion"),
                (String) raw.get("handoffId"),
                (String) raw.get("destinationReference"),
                ((Number) raw.get("handoffOrdinal")).longValue(),
                proof(fixture)
            )
        );
    }

    private static AuditCompletenessProof proof(Map<String, Object> fixture) {
        Map<String, Object> raw = TelemetryHandoffFixtureSupport.object(
            fixture,
            "auditProof"
        );
        return new AuditCompletenessProof(
            (String) raw.get("contractVersion"),
            ((Number) raw.get("recordCount")).intValue(),
            ((Number) raw.get("attemptCount")).intValue(),
            (String) raw.get("firstEventId"),
            (String) raw.get("terminalEventId"),
            (String) raw.get("identityDigest"),
            (String) raw.get("batchDigest")
        );
    }

    @SuppressWarnings("unchecked")
    private static ScriptedAuditHandoffQueue queue(Map<String, Object> fixture) {
        Map<String, Object> raw = TelemetryHandoffFixtureSupport.object(
            fixture,
            "auditHandoff"
        );
        List<ScriptedHandoffOutcome> outcomes = ((List<Object>) raw.get("scriptedOutcomes"))
            .stream()
            .map(String.class::cast)
            .map(value -> ScriptedHandoffOutcome.valueOf(value.toUpperCase()))
            .toList();
        return new ScriptedAuditHandoffQueue(
            ((Number) raw.get("queueCapacity")).intValue(),
            outcomes
        );
    }
}
