package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationReconciliation;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationVerification;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.ReconciliationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.TENANT;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.attempt;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.initialAttemptEvent;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.initialIntentEvent;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.intent;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.reconciliation;
import static io.github.akaryc1b.approval.persistence.jdbc.ApprovalMigrationJdbcFixtures.verification;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcApprovalMigrationProtocolDatabaseGuardIntegrationTest
    extends AbstractJdbcApprovalMigrationProtocolIntegrationTest {

    @Test
    void databaseRejectsDirectLifecycleBypassAndEvidenceMutation() {
        ApprovalMigrationIntent intent = intent();
        store.createIntent(intent, initialIntentEvent(intent, "intent"));
        ApprovalMigrationAttempt attempt = attempt();
        store.createAttempt(attempt, initialAttemptEvent(attempt, "attempt"));
        ApprovalMigrationVerification verification = verification(intent, attempt, 1);
        ApprovalMigrationReconciliation reconciliation = reconciliation(
            intent,
            attempt,
            1,
            ReconciliationStatus.OPEN
        );
        store.appendVerification(verification);
        store.appendReconciliation(reconciliation);

        assertThrows(DataAccessException.class, () -> jdbc.update(
            "update ap_process_migration_intent set status='COMPLETED',revision=revision+1 where tenant_id=? and intent_id=?",
            TENANT,
            intent.intentId()
        ));
        assertThrows(DataAccessException.class, () -> jdbc.update(
            "delete from ap_process_migration_intent_event where tenant_id=? and intent_id=?",
            TENANT,
            intent.intentId()
        ));
        assertThrows(DataAccessException.class, () -> jdbc.update(
            "update ap_process_migration_verification set evidence_hash=? where tenant_id=? and verification_id=?",
            ApprovalMigrationJdbcFixtures.hash('8'),
            TENANT,
            verification.verificationId()
        ));
        assertThrows(DataAccessException.class, () -> jdbc.update(
            "delete from ap_process_migration_reconciliation where tenant_id=? and reconciliation_id=?",
            TENANT,
            reconciliation.reconciliationId()
        ));

        assertEquals(1, count("ap_process_migration_intent_event"));
        assertEquals(1, count("ap_process_migration_attempt_event"));
        assertEquals(1, count("ap_process_migration_verification"));
        assertEquals(1, count("ap_process_migration_reconciliation"));
    }
}
