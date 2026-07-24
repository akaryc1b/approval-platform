package io.github.akaryc1b.approval.sdk.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.akaryc1b.approval.sdk.v1.SdkAuditHandoffReconciliationV1.AuditHandoffReconciliationInput;
import io.github.akaryc1b.approval.sdk.v1.SdkAuditHandoffReconciliationV1.AuditHandoffReconciliationResult;
import io.github.akaryc1b.approval.sdk.v1.SdkAuditHandoffReconciliationV1.ExpectedHandoffState;
import io.github.akaryc1b.approval.sdk.v1.SdkAuditHandoffReconciliationV1.ScriptedHandoffReconciliationStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SdkAuditHandoffReconciliationV1Test {
    @Test
    void sharedFixtureProducesExactClassificationsAndDuplicateProof() throws IOException {
        Map<String, Object> fixture = AggregationReconciliationFixtureSupport.fixture();
        SdkAuditHandoffV1.AuditHandoffEnvelope envelope =
            AggregationReconciliationFixtureSupport.envelope(fixture);
        SdkAuditHandoffV1.AuditHandoffAcknowledgement acknowledgement =
            AggregationReconciliationFixtureSupport.acknowledgement(fixture);
        ScriptedHandoffReconciliationStore store = new ScriptedHandoffReconciliationStore(8);
        List<AuditHandoffReconciliationResult> results = new ArrayList<>();
        results.add(reconcile(envelope, envelope, null, ExpectedHandoffState.ACKNOWLEDGED, 30, store));
        results.add(reconcile(envelope, null, acknowledgement, ExpectedHandoffState.ACKNOWLEDGED, 31, store));
        results.add(reconcile(envelope, null, null, ExpectedHandoffState.PENDING, 32, store));
        results.add(reconcile(envelope, envelope, acknowledgement, ExpectedHandoffState.ACKNOWLEDGED, 33, store));
        results.add(reconcile(envelope, null, acknowledgement, ExpectedHandoffState.ACKNOWLEDGED, 31, store));
        Map<String, Object> expectations = AggregationReconciliationFixtureSupport.object(
            fixture,
            "expectations"
        );
        assertEquals(
            CanonicalJson.canonicalizeValue(expectations.get("reconciliationResults")),
            CanonicalJson.canonicalizeValue(
                results.stream()
                    .map(SdkAuditHandoffReconciliationV1::resultMap)
                    .map(value -> (Object) value)
                    .toList()
            )
        );
        assertTrue(results.get(1).proof().safeToFinalize());
        assertFalse(results.get(3).proof().safeToFinalize());
    }

    @Test
    void pendingAndConflictingEvidenceRemainUnsafeToFinalize() throws IOException {
        Map<String, Object> fixture = AggregationReconciliationFixtureSupport.fixture();
        SdkAuditHandoffV1.AuditHandoffEnvelope envelope =
            AggregationReconciliationFixtureSupport.envelope(fixture);
        SdkAuditHandoffV1.AuditHandoffAcknowledgement acknowledgement =
            AggregationReconciliationFixtureSupport.acknowledgement(fixture);
        ScriptedHandoffReconciliationStore store = new ScriptedHandoffReconciliationStore(4);
        AuditHandoffReconciliationResult pending = reconcile(
            envelope,
            envelope,
            null,
            ExpectedHandoffState.PENDING,
            40,
            store
        );
        assertEquals("pending_confirmed", pending.proof().classification().name().toLowerCase());
        SdkAuditHandoffV1.AuditHandoffAcknowledgement mismatch =
            new SdkAuditHandoffV1.AuditHandoffAcknowledgement(
                acknowledgement.contractVersion(),
                acknowledgement.acknowledgementId(),
                acknowledgement.handoffId(),
                acknowledgement.destinationReference(),
                "different",
                acknowledgement.auditBatchDigest(),
                acknowledgement.deliveryAttempt(),
                acknowledgement.acknowledgedOrdinal()
            );
        AuditHandoffReconciliationResult conflict = reconcile(
            envelope,
            null,
            mismatch,
            ExpectedHandoffState.ACKNOWLEDGED,
            41,
            store
        );
        assertEquals("conflicting_evidence", conflict.proof().classification().name().toLowerCase());
        assertFalse(conflict.proof().safeToFinalize());
    }

    @Test
    void capacityAndScriptedFailureStoreNoPartialProof() throws IOException {
        Map<String, Object> fixture = AggregationReconciliationFixtureSupport.fixture();
        SdkAuditHandoffV1.AuditHandoffEnvelope envelope =
            AggregationReconciliationFixtureSupport.envelope(fixture);
        ScriptedHandoffReconciliationStore capacity = new ScriptedHandoffReconciliationStore(1);
        reconcile(envelope, envelope, null, ExpectedHandoffState.PENDING, 50, capacity);
        AuditHandoffReconciliationResult rejected = reconcile(
            envelope,
            null,
            null,
            ExpectedHandoffState.PENDING,
            51,
            capacity
        );
        assertEquals("capacity_rejected", rejected.status().name().toLowerCase());
        assertEquals(1, capacity.size());
        ScriptedHandoffReconciliationStore failing = new ScriptedHandoffReconciliationStore(
            2,
            List.of(1)
        );
        AuditHandoffReconciliationResult failed = reconcile(
            envelope,
            envelope,
            null,
            ExpectedHandoffState.PENDING,
            52,
            failing
        );
        assertEquals("failed_closed", failed.status().name().toLowerCase());
        assertEquals(0, failing.size());
    }

    @Test
    void acknowledgedProofIsTheOnlyFinalizableClassification() throws IOException {
        Map<String, Object> fixture = AggregationReconciliationFixtureSupport.fixture();
        SdkAuditHandoffV1.AuditHandoffEnvelope =
            AggregationReconciliationFixtureSupport.envelope(fixture);
        SdkAuditHandoffV1.AuditHandoffAcknowledgement acknowledgement =
            AggregationReconciliationFixtureSupport.acknowledgement(fixture);
        ScriptedHandoffReconciliationStore store = new ScriptedHandoffReconciliationStore(5);
        List<AuditHandoffReconciliationResult> results = List.of(
            reconcile(envelope, envelope, null, ExpectedHandoffState.ACKNOWLEDGED, 60, store),
            reconcile(envelope, null, acknowledgement, ExpectedHandoffState.ACKNOWLEDGED, 61, store),
            reconcile(envelope, null, null, ExpectedHandoffState.PENDING, 62, store),
            reconcile(envelope, envelope, acknowledgement, ExpectedHandoffState.ACKNOWLEDGED, 63, store)
        );
        assertEquals(1L, results.stream().filter(value -> value.proof().safeToFinalize()).count());
        assertEquals(
            "acknowledged_confirmed",
            results.stream()
                .filter(value -> value.proof().safeToFinalize())
                .findFirst()
                .orElseThrow()
                .proof()
                .classification()
                .name()
                .toLowerCase()
        );
    }

    private static AuditHandoffReconciliationResult reconcile(
        SdkAuditHandoffV1.AuditHandoffEnvelope envelope,
        SdkAuditHandoffV1.AuditHandoffEnvelope pending,
        SdkAuditHandoffV1.AuditHandoffAcknowledgement acknowledgement,
        ExpectedHandoffState expectedState,
        long ordinal,
        ScriptedHandoffReconciliationStore store
    ) {
        return SdkAuditHandoffReconciliationV1.reconcile(
            new AuditHandoffReconciliationInput(
                "1",
                expectedState,
                envelope,
                pending,
                acknowledgement,
                ordinal
            ),
            store
        );
    }
}
