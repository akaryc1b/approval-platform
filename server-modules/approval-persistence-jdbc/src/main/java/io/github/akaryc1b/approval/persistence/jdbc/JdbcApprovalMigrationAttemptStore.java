package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationProtocolStore.AttemptCreationResult;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttemptEvent;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Attempt persistence composed from transactional writers and one query repository. */
final class JdbcApprovalMigrationAttemptStore {

    private final JdbcApprovalMigrationAttemptRepository repository;
    private final JdbcApprovalMigrationAttemptCreator creator;
    private final JdbcApprovalMigrationAttemptTransitioner transitioner;

    JdbcApprovalMigrationAttemptStore(
        DataSource dataSource,
        JdbcApprovalMigrationJson json,
        PlatformTransactionManager transactionManager
    ) {
        repository = new JdbcApprovalMigrationAttemptRepository(dataSource, json);
        creator = new JdbcApprovalMigrationAttemptCreator(repository, transactionManager);
        transitioner = new JdbcApprovalMigrationAttemptTransitioner(repository, transactionManager);
    }

    AttemptCreationResult create(ApprovalMigrationAttempt value, ApprovalMigrationAttemptEvent event) {
        return creator.create(value, event);
    }

    Optional<ApprovalMigrationAttempt> find(String tenantId, UUID attemptId) {
        return repository.find(tenantId, attemptId);
    }

    List<ApprovalMigrationAttempt> findByIntent(String tenantId, UUID intentId) {
        return repository.findByIntent(tenantId, intentId);
    }

    ApprovalMigrationAttempt transition(
        ApprovalMigrationAttempt next,
        long expectedRevision,
        ApprovalMigrationAttemptEvent event
    ) {
        return transitioner.transition(next, expectedRevision, event);
    }

    List<ApprovalMigrationAttemptEvent> events(String tenantId, UUID attemptId) {
        return repository.events(tenantId, attemptId);
    }
}
