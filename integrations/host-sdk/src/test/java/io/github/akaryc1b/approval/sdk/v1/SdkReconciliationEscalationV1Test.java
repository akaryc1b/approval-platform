package io.github.akaryc1b.approval.sdk.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SdkReconciliationEscalationV1Test {
    @Test
    void sharedFixtureProducesExactEscalationAndFinalization() throws IOException {
        Map<String, Object> fixture = CheckpointEscalationFixtureSupport.fixture();
        SdkReconciliationEscalationV1.ReconciliationEscalationPolicy policy =
            CheckpointEscalationFixtureSupport.escalationPolicy(fixture);
        SdkReconciliationEscalationV1.ScriptedReconciliationEscalationStore unresolved =
            new SdkReconciliationEscalationV1.ScriptedReconciliationEscalationStore(8);
        SdkReconciliationEscalationV1.ScriptedReconciliationEscalationStore conflict =
            new SdkReconciliationEscalationV1.ScriptedReconciliationEscalationStore(8);
        SdkReconciliationEscalationV1.ScriptedReconciliationEscalationStore finalize =
            new SdkReconciliationEscalationV1.ScriptedReconciliationEscalationStore(8);
        List<SdkReconciliationEscalationV1.ReconciliationEscalationResult> results =
            new ArrayList<>();
        results.add(evaluate(fixture, policy, "acknowledgementMissing", 33, unresolved));
        results.add(evaluate(fixture, policy, "acknowledgementMissing", 40, unresolved));
        results.add(evaluate(fixture, policy, "conflicting", 55, conflict));
        results.add(evaluate(fixture, policy, "acknowledged", 41, finalize));
        results.add(evaluate(fixture, policy, "acknowledged", 41, finalize));
        Map<String, Object> expectations = CheckpointEscalationFixtureSupport.object(
            fixture,
            "expectations"
        );
        assertEquals(
            CanonicalJson.canonicalizeValue(expectations.get("escalationResults")),
            CanonicalJson.canonicalizeValue(
                results.stream()
                    .map(SdkReconciliationEscalationV1::resultMap)
                    .map(value -> (Object) value)
                    .toList()
            )
        );
    }

    @Test
    void callerOrdinalEscalatesObserveInvestigateAndBlock() throws IOException {
        Map<String, Object> fixture = CheckpointEscalationFixtureSupport.fixture();
        SdkReconciliationEscalationV1.ReconciliationEscalationPolicy policy =
            CheckpointEscalationFixtureSupport.escalationPolicy(fixture);
        SdkReconciliationEscalationV1.ScriptedReconciliationEscalationStore unresolved =
            new SdkReconciliationEscalationV1.ScriptedReconciliationEscalationStore(8);
        SdkReconciliationEscalationV1.ReconciliationEscalationResult observe = evaluate(
            fixture,
            policy,
            "acknowledgementMissing",
            33,
            unresolved
        );
        SdkReconciliationEscalationV1.ReconciliationEscalationResult investigate = evaluate(
            fixture,
            policy,
            "acknowledgementMissing",
            40,
            unresolved
        );
        SdkReconciliationEscalationV1.ReconciliationEscalationResult block = evaluate(
            fixture,
            policy,
            "conflicting",
            55,
            new SdkReconciliationEscalationV1.ScriptedReconciliationEscalationStore(8)
        );
        assertEquals(
            List.of(
                SdkReconciliationEscalationV1.ReconciliationEscalationLevel.OBSERVE,
                SdkReconciliationEscalationV1.ReconciliationEscalationLevel.INVESTIGATE,
                SdkReconciliationEscalationV1.ReconciliationEscalationLevel.BLOCK
            ),
            List.of(
                observe.proof().escalationLevel(),
                investigate.proof().escalationLevel(),
                block.proof().escalationLevel()
            )
        );
        assertFalse(observe.proof().requiresManualAction());
        assertTrue(investigate.proof().requiresManualAction());
        assertTrue(block.proof().requiresManualAction());
        assertEquals(null, observe.finalizationCheckpoint());
        assertEquals(null, investigate.finalizationCheckpoint());
        assertEquals(null, block.finalizationCheckpoint());
    }

    @Test
    void onlyAcknowledgedConfirmationCreatesFinalizationCheckpoint() throws IOException {
        Map<String, Object> fixture = CheckpointEscalationFixtureSupport.fixture();
        SdkReconciliationEscalationV1.ScriptedReconciliationEscalationStore store =
            new SdkReconciliationEscalationV1.ScriptedReconciliationEscalationStore(8);
        SdkReconciliationEscalationV1.ReconciliationEscalationResult resolved = evaluate(
            fixture,
            CheckpointEscalationFixtureSupport.escalationPolicy(fixture),
            "acknowledged",
            41,
            store
        );
        assertEquals(
            SdkReconciliationEscalationV1.ReconciliationEscalationLevel.RESOLVED,
            resolved.proof().escalationLevel()
        );
        assertTrue(resolved.proof().safeToFinalize());
        assertEquals(
            "e108d3017d3cf456882ac3518e3cacd5d5805b9d2c8ebd47dd4eb585cb938156",
            resolved.finalizationCheckpoint().checkpointDigest()
        );
        assertEquals(1, store.finalizationCount());
        assertEquals(
            SdkReconciliationEscalationV1.ReconciliationEscalationStatus.DUPLICATE,
            evaluate(
                fixture,
                CheckpointEscalationFixtureSupport.escalationPolicy(fixture),
                "acknowledged",
                41,
                store
            ).status()
        );
        assertEquals(1, store.finalizationCount());
    }

    @Test
    void regressionCapacityFailureAndPolicyBoundsAreAtomic() throws IOException {
        Map<String, Object> fixture = CheckpointEscalationFixtureSupport.fixture();
        SdkReconciliationEscalationV1.ReconciliationEscalationPolicy policy =
            CheckpointEscalationFixtureSupport.escalationPolicy(fixture);
        SdkReconciliationEscalationV1.ScriptedReconciliationEscalationStore store =
            new SdkReconciliationEscalationV1.ScriptedReconciliationEscalationStore(8);
        evaluate(fixture, policy, "acknowledgementMissing", 40, store);
        assertEquals(
            "reconciliation_escalation_ordinal_regression",
            evaluate(fixture, policy, "acknowledgementMissing", 35, store).reasonCode()
        );
        assertEquals(1, store.size());

        SdkReconciliationEscalationV1.ReconciliationEscalationPolicy one =
            new SdkReconciliationEscalationV1.ReconciliationEscalationPolicy(
                "1",
                5,
                10,
                20,
                1
            );
        SdkReconciliationEscalationV1.ScriptedReconciliationEscalationStore capacity =
            new SdkReconciliationEscalationV1.ScriptedReconciliationEscalationStore(1);
        evaluate(fixture, one, "acknowledgementMissing", 33, capacity);
        assertEquals(
            SdkReconciliationEscalationV1.ReconciliationEscalationStatus.CAPACITY_REJECTED,
            evaluate(fixture, one, "conflicting", 55, capacity).status()
        );
        assertEquals(1, capacity.size());

        SdkReconciliationEscalationV1.ScriptedReconciliationEscalationStore failing =
            new SdkReconciliationEscalationV1.ScriptedReconciliationEscalationStore(
                8,
                List.of(1)
            );
        assertEquals(
            "reconciliation_escalation_store_failed",
            evaluate(fixture, policy, "acknowledged", 41, failing).reasonCode()
        );
        assertEquals(0, failing.size());
        assertEquals(0, failing.finalizationCount());
        assertThrows(
            SdkAggregateExportCheckpointV1.UnsupportedCheckpointEscalationVersionException.class,
            () -> new SdkReconciliationEscalationV1.ReconciliationEscalationPolicy(
                "2",
                5,
                10,
                20,
                8
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new SdkReconciliationEscalationV1.ReconciliationEscalationPolicy(
                "1",
                10,
                5,
                20,
                8
            )
        );
    }

    private static SdkReconciliationEscalationV1.ReconciliationEscalationResult evaluate(
        Map<String, Object> fixture,
        SdkReconciliationEscalationV1.ReconciliationEscalationPolicy policy,
        String proofName,
        long evaluationOrdinal,
        SdkReconciliationEscalationV1.ScriptedReconciliationEscalationStore store
    ) {
        return SdkReconciliationEscalationV1.evaluate(
            policy,
            new SdkReconciliationEscalationV1.ReconciliationEscalationInput(
                "1",
                CheckpointEscalationFixtureSupport.proof(fixture, proofName),
                evaluationOrdinal
            ),
            store
        );
    }
}
