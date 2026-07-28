package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationReconciliation;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationVerification;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

/** Immutable verification and reconciliation evidence facade. */
final class JdbcApprovalMigrationEvidenceStore {

    private final JdbcApprovalMigrationVerificationStore verifications;
    private final JdbcApprovalMigrationReconciliationStore reconciliations;

    JdbcApprovalMigrationEvidenceStore(
        DataSource dataSource,
        JdbcApprovalMigrationJson json,
        PlatformTransactionManager transactionManager
    ) {
        verifications = new JdbcApprovalMigrationVerificationStore(
            dataSource,
            json,
            transactionManager
        );
        reconciliations = new JdbcApprovalMigrationReconciliationStore(
            dataSource,
            json,
            transactionManager
        );
    }

    void appendVerification(ApprovalMigrationVerification value) {
        verifications.append(value);
    }

    List<ApprovalMigrationVerification> verifications(String tenantId, UUID attemptId) {
        return verifications.find(tenantId, attemptId);
    }

    void appendReconciliation(ApprovalMigrationReconciliation value) {
        reconciliations.append(value);
    }

    List<ApprovalMigrationReconciliation> reconciliations(String tenantId, UUID attemptId) {
        return reconciliations.find(tenantId, attemptId);
    }
}
