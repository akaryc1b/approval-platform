package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationProtocolStore.IntentCreationResult;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationProtocolStore.MigrationProtocolConflictException;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntentEvent;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/** Atomic current-intent plus initial-event creation. */
final class JdbcApprovalMigrationIntentCreator {

    private final JdbcApprovalMigrationIntentRepository repository;
    private final TransactionTemplate transactions;

    JdbcApprovalMigrationIntentCreator(
        JdbcApprovalMigrationIntentRepository repository,
        PlatformTransactionManager transactionManager
    ) {
        this.repository = Objects.requireNonNull(repository);
        transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    }

    IntentCreationResult create(ApprovalMigrationIntent value, ApprovalMigrationIntentEvent event) {
        if (value.revision() != 1 || !event.intentId().equals(value.intentId())
            || !event.tenantId().equals(value.tenantId()) || event.revision() != 1
            || event.toStatus() != value.status()) {
            throw new IllegalArgumentException("initial intent and event evidence do not match");
        }
        try {
            return transactions.execute(status -> {
                if (repository.insert(value) == 0) {
                    ApprovalMigrationIntent existing = repository.findByIdempotencyKey(
                        value.tenantId(), value.idempotencyKey()
                    ).orElseThrow(() -> conflict("intent replay disappeared"));
                    if (!existing.equals(value)) {
                        throw conflict("intent idempotency key was reused with different evidence");
                    }
                    return new IntentCreationResult(existing, true);
                }
                repository.appendEvent(event);
                return new IntentCreationResult(value, false);
            });
        } catch (DataIntegrityViolationException exception) {
            ApprovalMigrationIntent concurrentReplay = repository.findByIdempotencyKey(
                value.tenantId(), value.idempotencyKey()
            ).orElse(null);
            if (value.equals(concurrentReplay)) {
                return new IntentCreationResult(concurrentReplay, true);
            }
            throw new MigrationProtocolConflictException("migration intent persistence conflict", exception);
        }
    }

    private static MigrationProtocolConflictException conflict(String message) {
        return new MigrationProtocolConflictException(message);
    }
}
