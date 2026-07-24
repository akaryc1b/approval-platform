package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationProtocolStore.MigrationProtocolConflictException;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationReconciliation;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationVerification;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.ReconciliationStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.TENANT;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.attempt;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.initialAttemptEvent;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.initialIntentEvent;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.intent;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.reconciliation;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.verification;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcApprovalMigrationEvidenceStoreIntegrationTest
    extends AbstractJdbcApprovalMigrationProtocolIntegrationTest {

    @Test
    void verificationAndReconciliationAreIdempotentGapFreeAppendOnlyEvidence() {
        ApprovalMigrationIntent intent = intent();
        store.createIntent(intent, initialIntentEvent(intent, "intent"));
        ApprovalMigrationAttempt attempt = attempt();
        store.createAttempt(attempt, initialAttemptEvent(attempt, "attempt"));

        ApprovalMigrationVerification first = verification(intent, attempt, 1);
        store.appendVerification(first);
        store.appendVerification(first);
        assertEquals(1, store.findVerifications(TENANT, attempt.attemptId()).size());

        ApprovalMigrationVerification gap = new ApprovalMigrationVerification(
            UUID.randomUUID(), first.tenantId(), first.intentId(), first.attemptId(), 3,
            first.expectedBindingEvidenceHash(), first.observedBindingEvidenceHash(),
            first.sourceEngineDefinitionId(), first.targetEngineDefinitionId(),
            first.observedEngineDefinitionId(), first.expectedActiveTaskKeys(),
            first.observedActiveTaskKeys(), first.runtimePresent(), first.historyPresent(),
            first.outcome(), ApprovalMigrationJdbcFixtures.hash('9'), first.recordedAt().plusSeconds(1),
            "request-gap", first.traceId()
        );
        assertThrows(MigrationProtocolConflictException.class, () -> store.appendVerification(gap));

        ApprovalMigrationReconciliation open = reconciliation(
            intent,
            attempt,
            1,
            ReconciliationStatus.OPEN
        );
        ApprovalMigrationReconciliation resolved = reconciliation(
            intent,
            attempt,
            2,
            ReconciliationStatus.RESOLVED_TARGET
        );
        store.appendReconciliation(open);
        store.appendReconciliation(open);
        store.appendReconciliation(resolved);
        assertEquals(2, store.findReconciliations(TENANT, attempt.attemptId()).size());

        ApprovalMigrationReconciliation afterTerminal = reconciliation(
            intent,
            attempt,
            3,
            ReconciliationStatus.UNRESOLVED
        );
        assertThrows(
            MigrationProtocolConflictException.class,
            () -> store.appendReconciliation(afterTerminal)
        );
    }
}
