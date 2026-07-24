package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationProtocolStore.MigrationProtocolConflictException;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntentEvent;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/** Revision-CAS intent transition plus append-only transition event. */
final class JdbcApprovalMigrationIntentTransitioner {

    private final JdbcApprovalMigrationIntentRepository repository;
    private final TransactionTemplate transactions;

    JdbcApprovalMigrationIntentTransitioner(
        JdbcApprovalMigrationIntentRepository repository,
        PlatformTransactionManager transactionManager
    ) {
        this.repository = Objects.requireNonNull(repository);
        transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    }

    ApprovalMigrationIntent transition(
        ApprovalMigrationIntent next,
        long expectedRevision,
        ApprovalMigrationIntentEvent event
    ) {
        if (next.revision() != expectedRevision + 1 || event.revision() != next.revision()
            || !event.intentId().equals(next.intentId()) || !event.tenantId().equals(next.tenantId())
            || event.toStatus() != next.status() || event.fromStatus() == null) {
            throw new IllegalArgumentException("intent transition evidence does not match next state");
        }
        try {
            return transactions.execute(status -> {
                if (repository.update(next, expectedRevision, event) != 1) {
                    throw new MigrationProtocolConflictException("migration intent revision or state conflict");
                }
                repository.appendEvent(event);
                return next;
            });
        } catch (DataIntegrityViolationException exception) {
            throw new MigrationProtocolConflictException("migration intent transition conflict", exception);
        }
    }
}
