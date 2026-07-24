package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationProtocolStore.MigrationProtocolConflictException;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttemptEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
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
        String leaseActor,
        ApprovalMigrationAttemptEvent event
    ) {
        if (next.revision() != expectedRevision + 1 || event.revision() != next.revision()
            || !event.attemptId().equals(next.attemptId()) || !event.tenantId().equals(next.tenantId())
            || event.toStatus() != next.status() || event.fromStatus() == null) {
            throw new IllegalArgumentException("attempt transition evidence does not match next state");
        }
        try {
            return transactions.execute(status -> {
                ApprovalMigrationAttempt current = repository.find(next.tenantId(), next.attemptId())
                    .orElseThrow(() -> conflict("migration attempt does not exist"));
                String effectiveActor = resolveLeaseActor(current, next, leaseActor);
                ApprovalMigrationAttemptEvent durableEvent = event.withDurableEvidence(next, effectiveActor);
                if (repository.update(next, expectedRevision, event, effectiveActor) != 1) {
                    throw conflict("migration attempt revision or state conflict");
                }
                repository.appendEvent(durableEvent);
                return next;
            });
        } catch (DataIntegrityViolationException exception) {
            throw new MigrationProtocolConflictException("migration attempt transition conflict", exception);
        }
    }

    private static String resolveLeaseActor(
        ApprovalMigrationAttempt current,
        ApprovalMigrationAttempt next,
        String suppliedActor
    ) {
        if (suppliedActor != null && !suppliedActor.isBlank()) {
            String normalized = suppliedActor.trim();
            if (normalized.length() > 200) {
                throw new IllegalArgumentException("leaseActor must be at most 200 characters");
            }
            return normalized;
        }
        if (next.status() == AttemptStatus.CLAIMED) {
            return next.leaseOwner();
        }
        if (current.status() == AttemptStatus.CLAIMED) {
            return current.leaseOwner();
        }
        return null;
    }

    private static MigrationProtocolConflictException conflict(String message) {
        return new MigrationProtocolConflictException(message);
    }
}
