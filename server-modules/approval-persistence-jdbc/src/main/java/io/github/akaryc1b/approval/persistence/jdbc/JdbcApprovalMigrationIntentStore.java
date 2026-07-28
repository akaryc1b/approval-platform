package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationProtocolStore.IntentCreationResult;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntentEvent;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Intent persistence composed from small transactional writers and one query repository. */
final class JdbcApprovalMigrationIntentStore {

    private final JdbcApprovalMigrationIntentRepository repository;
    private final JdbcApprovalMigrationIntentCreator creator;
    private final JdbcApprovalMigrationIntentTransitioner transitioner;

    JdbcApprovalMigrationIntentStore(
        DataSource dataSource,
        JdbcApprovalMigrationJson json,
        PlatformTransactionManager transactionManager
    ) {
        repository = new JdbcApprovalMigrationIntentRepository(dataSource, json);
        creator = new JdbcApprovalMigrationIntentCreator(repository, transactionManager);
        transitioner = new JdbcApprovalMigrationIntentTransitioner(repository, transactionManager);
    }

    IntentCreationResult create(ApprovalMigrationIntent value, ApprovalMigrationIntentEvent event) {
        return creator.create(value, event);
    }

    Optional<ApprovalMigrationIntent> find(String tenantId, UUID intentId) {
        return repository.find(tenantId, intentId);
    }

    Optional<ApprovalMigrationIntent> findByIdempotencyKey(String tenantId, String key) {
        return repository.findByIdempotencyKey(tenantId, key);
    }

    ApprovalMigrationIntent transition(
        ApprovalMigrationIntent next,
        long expectedRevision,
        ApprovalMigrationIntentEvent event
    ) {
        return transitioner.transition(next, expectedRevision, event);
    }

    List<ApprovalMigrationIntentEvent> events(String tenantId, UUID intentId) {
        return repository.events(tenantId, intentId);
    }
}
