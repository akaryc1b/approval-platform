package io.github.akaryc1b.approval.domain.migration;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.FailureClass;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.ReconciliationStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.VerificationOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.akaryc1b.approval.domain.migration.ApprovalMigrationTestFixtures.NOW;
import static io.github.akaryc1b.approval.domain.migration.ApprovalMigrationTestFixtures.TENANT;
import static io.github.akaryc1b.approval.domain.migration.ApprovalMigrationTestFixtures.attempt;
import static io.github.akaryc1b.approval.domain.migration.ApprovalMigrationTestFixtures.hash;
import static io.github.akaryc1b.approval.domain.migration.ApprovalMigrationTestFixtures.id;
import static io.github.akaryc1b.approval.domain.migration.ApprovalMigrationTestFixtures.intent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalMigrationEvidenceTest {

    @Test
    void verificationCanonicalizesTaskKeysAndRejectsFalseConfirmation() {
        ApprovalMigrationVerification verification = new ApprovalMigrationVerification(
            id(30), TENANT, intent().intentId(), attempt().attemptId(), 1,
            hash('e'), hash('f'), "source-definition", "target-definition", "target-definition",
            List.of("task-b", "task-a", "task-a"), List.of("task-a", "task-b"),
            true, true, VerificationOutcome.TARGET_CONFIRMED,
            hash('1'), NOW, "request-verification", "trace-verification"
        );
        assertEquals(List.of("task-a", "task-b"), verification.expectedActiveTaskKeys());
        assertThrows(IllegalArgumentException.class, () -> new ApprovalMigrationVerification(
            id(31), TENANT, intent().intentId(), attempt().attemptId(), 1,
            hash('e'), hash('f'), "source-definition", "target-definition", "source-definition",
            List.of(), List.of(), true, true, VerificationOutcome.TARGET_CONFIRMED,
            hash('2'), NOW, "request-invalid", null
        ));
    }

    @Test
    void reconciliationRequiresOpenAndTerminalResolutionEvidence() {
        ApprovalMigrationReconciliation open = new ApprovalMigrationReconciliation(
            id(40), TENANT, intent().intentId(), attempt().attemptId(), 1,
            ReconciliationStatus.OPEN, FailureClass.RECONCILIATION_REQUIRED,
            "Mixed source and target evidence", hash('3'), null, null,
            NOW, null, "request-open", null, "audit:open"
        );
        assertEquals(ReconciliationStatus.OPEN, open.status());
        assertThrows(IllegalArgumentException.class, () -> new ApprovalMigrationReconciliation(
            id(41), TENANT, intent().intentId(), attempt().attemptId(), 2,
            ReconciliationStatus.RESOLVED_TARGET, FailureClass.RECONCILIATION_REQUIRED,
            "Resolved", hash('4'), null, null,
            NOW, NOW.plusSeconds(1), "request-resolved", null, "audit:resolved"
        ));
    }
}
