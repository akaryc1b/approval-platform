package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationProtocolStore.MigrationProtocolConflictException;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttemptEvent;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/** Revision-CAS attempt transition plus append-only transition event. */
final class JdbcApprovalMigrationAttemptTransitioner {

    private final JdbcApprovalMigrationAttemptRepository repository;
    private final TransactionTemplate transactions;

    JdbcApprovalMigrationAttemptTransitioner(
        JdbcApprovalMigrationAttemptRepository repository,
        PlatformTransactionManager transactionManager
    ) {
        this.repository = Objects.requireNonNull(repository);
        transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    }

    ApprovalMigrationAttempt transition(
        ApprovalMigrationAttempt next,
        long expectedRevision,
        ApprovalMigrationAttemptEvent event
    ) {
        if (next.revision() != expectedRevision + 1 || event.revision() != next.revision()
            || !event.attemptId().equals(next.attemptId()) || !event.tenantId().equals(next.tenantId())
            || event.toStatus() != next.status() || event.fromStatus() == null) {
            throw new IllegalArgumentException("attempt transition evidence does not match next state");
        }
        try {
            return transactions.execute(status -> {
                if (repository.update(next, expectedRevision, event) != 1) {
                    throw new MigrationProtocolConflictException("migration attempt revision or state conflict");
                }
                repository.appendEvent(event);
                return next;
            });
        } catch (DataIntegrityViolationException exception) {
            throw new MigrationProtocolConflictException("migration attempt transition conflict", exception);
        }
    }
}
