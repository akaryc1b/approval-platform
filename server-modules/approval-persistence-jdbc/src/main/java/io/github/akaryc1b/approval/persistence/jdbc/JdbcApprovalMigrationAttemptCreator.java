package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationProtocolStore.AttemptCreationResult;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationProtocolStore.MigrationProtocolConflictException;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttemptEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/** Atomic current-attempt plus initial-event creation with explicit retry lineage. */
final class JdbcApprovalMigrationAttemptCreator {

    private final JdbcApprovalMigrationAttemptRepository repository;
    private final TransactionTemplate transactions;

    JdbcApprovalMigrationAttemptCreator(
        JdbcApprovalMigrationAttemptRepository repository,
        PlatformTransactionManager transactionManager
    ) {
        this.repository = Objects.requireNonNull(repository);
        transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    }

    AttemptCreationResult create(ApprovalMigrationAttempt value, ApprovalMigrationAttemptEvent event) {
        validateInitial(value, event);
        try {
            return transactions.execute(status -> {
                if (value.attemptNumber() > 1) {
                    ApprovalMigrationAttempt parent = repository.find(
                        value.tenantId(), value.parentAttemptId()
                    ).orElseThrow(() -> conflict("retry parent attempt does not exist"));
                    if (!parent.intentId().equals(value.intentId())
                        || !parent.approvalInstanceId().equals(value.approvalInstanceId())
                        || parent.attemptNumber() + 1 != value.attemptNumber()
                        || parent.status() != AttemptStatus.FAILED_RETRYABLE) {
                        throw conflict("retry attempt must follow the immediate retryable parent");
                    }
                }
                if (repository.insert(value) == 0) {
                    ApprovalMigrationAttempt existing = repository.findByNumber(
                        value.tenantId(), value.intentId(),
                        value.approvalInstanceId(), value.attemptNumber()
                    ).orElseThrow(() -> conflict("attempt replay disappeared"));
                    if (!existing.equals(value)) {
                        throw conflict("attempt identity was reused with different evidence");
                    }
                    return new AttemptCreationResult(existing, true);
                }
                repository.appendEvent(event);
                return new AttemptCreationResult(value, false);
            });
        } catch (DataIntegrityViolationException exception) {
            ApprovalMigrationAttempt concurrentReplay = repository.find(
                value.tenantId(), value.attemptId()
            ).orElse(null);
            if (value.equals(concurrentReplay)) {
                return new AttemptCreationResult(concurrentReplay, true);
            }
            throw new MigrationProtocolConflictException("migration attempt persistence conflict", exception);
        }
    }

    private static void validateInitial(
        ApprovalMigrationAttempt value,
        ApprovalMigrationAttemptEvent event
    ) {
        if (value.revision() != 1 || !event.attemptId().equals(value.attemptId())
            || !event.tenantId().equals(value.tenantId()) || event.revision() != 1
            || event.toStatus() != value.status()) {
            throw new IllegalArgumentException("initial attempt and event evidence do not match");
        }
    }

    private static MigrationProtocolConflictException conflict(String message) {
        return new MigrationProtocolConflictException(message);
    }
}
