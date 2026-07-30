package io.github.akaryc1b.approval.domain.migration;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.EngineOutcome;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.FailureClass;
import org.junit.jupiter.api.Test;

import static io.github.akaryc1b.approval.domain.migration.ApprovalMigrationTestFixtures.NOW;
import static io.github.akaryc1b.approval.domain.migration.ApprovalMigrationTestFixtures.attempt;
import static io.github.akaryc1b.approval.domain.migration.ApprovalMigrationTestFixtures.hash;
import static io.github.akaryc1b.approval.domain.migration.ApprovalMigrationTestFixtures.id;
import static io.github.akaryc1b.approval.domain.migration.ApprovalMigrationTestFixtures.intent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalMigrationAttemptTest {

    @Test
    void attemptMakesLeaseUnknownAndReconciliationExplicit() {
        assertThrows(IllegalArgumentException.class, () -> attempt().transitioned(
            transition(AttemptStatus.CLAIMED, EngineOutcome.NOT_REQUESTED, null, null, null)
        ));

        ApprovalMigrationAttempt claimed = attempt().transitioned(new ApprovalMigrationAttemptTransition(
            AttemptStatus.CLAIMED, EngineOutcome.NOT_REQUESTED,
            "worker-one", NOW.plusSeconds(60), null,
            FailureClass.NONE, null, NOW.plusSeconds(1)
        ));
        ApprovalMigrationAttempt requested = claimed.transitioned(
            transition(AttemptStatus.ENGINE_REQUESTED, EngineOutcome.ACCEPTED, "request-one", null, null)
        );
        ApprovalMigrationAttempt unknown = requested.transitioned(
            transition(
                AttemptStatus.UNKNOWN,
                EngineOutcome.UNKNOWN,
                "request-one",
                FailureClass.ENGINE_OUTCOME_UNKNOWN,
                "Engine response was not observed"
            )
        );
        ApprovalMigrationAttempt reconciling = unknown.transitioned(
            transition(
                AttemptStatus.RECONCILING,
                EngineOutcome.UNKNOWN,
                "request-one",
                FailureClass.RECONCILIATION_REQUIRED,
                "Authoritative readback is required"
            )
        );

        assertEquals(5, reconciling.revision());
        assertEquals(AttemptStatus.RECONCILING, reconciling.status());
    }

    @Test
    void retryLineageAndUnknownFailureEvidenceFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> new ApprovalMigrationAttempt(
            id(20), ApprovalMigrationTestFixtures.TENANT, intent().intentId(), id(21),
            "engine-instance-two", 2, null, hash('e'),
            "source-definition", "target-definition",
            AttemptStatus.PENDING, EngineOutcome.NOT_REQUESTED, 1,
            null, null, null, FailureClass.NONE, null,
            NOW, NOW, "request-invalid", null
        ));
        ApprovalMigrationAttempt requested = attempt()
            .transitioned(new ApprovalMigrationAttemptTransition(
                AttemptStatus.CLAIMED, EngineOutcome.NOT_REQUESTED,
                "worker", NOW.plusSeconds(30), null,
                FailureClass.NONE, null, NOW.plusSeconds(1)
            ))
            .transitioned(transition(
                AttemptStatus.ENGINE_REQUESTED,
                EngineOutcome.ACCEPTED,
                "request-one",
                null,
                null
            ));
        assertThrows(IllegalArgumentException.class, () -> requested.transitioned(
            transition(AttemptStatus.UNKNOWN, EngineOutcome.UNKNOWN, "request-one", null, null)
        ));
    }

    private static ApprovalMigrationAttemptTransition transition(
        AttemptStatus status,
        EngineOutcome outcome,
        String requestReference,
        FailureClass failureClass,
        String summary
    ) {
        return new ApprovalMigrationAttemptTransition(
            status, outcome, null, null, requestReference,
            failureClass == null ? FailureClass.NONE : failureClass,
            summary, NOW.plusSeconds(status.ordinal() + 2L)
        );
    }
}
