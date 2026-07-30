package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanStore;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlan;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAuthorization;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanEvent;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL M5-C immutable-plan store; it performs no engine or runtime-binding operation. */
public final class JdbcApprovalMigrationPlanStore implements ApprovalMigrationPlanStore {

    private final JdbcApprovalMigrationPlanRepository repository;
    private final JdbcApprovalMigrationPlanWriter writer;

    public JdbcApprovalMigrationPlanStore(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager
    ) {
        repository = new JdbcApprovalMigrationPlanRepository(
            dataSource,
            new JdbcApprovalMigrationJson(objectMapper)
        );
        writer = new JdbcApprovalMigrationPlanWriter(repository, transactionManager);
    }

    @Override
    public PlanCreationResult createPlan(
        ApprovalMigrationPlan plan,
        ApprovalMigrationPlanEvent initialEvent
    ) {
        return writer.create(plan, initialEvent);
    }

    @Override
    public Optional<ApprovalMigrationPlan> findPlan(String tenantId, UUID planId) {
        return repository.findPlan(tenantId, planId);
    }

    @Override
    public Optional<ApprovalMigrationPlan> findPlanByHash(String tenantId, String planHash) {
        return repository.findPlanByHash(tenantId, planHash);
    }

    @Override
    public Optional<ApprovalMigrationPlan> findPlanByIdempotencyKey(
        String tenantId,
        String idempotencyKey
    ) {
        return repository.findPlanByIdempotencyKey(tenantId, idempotencyKey);
    }

    @Override
    public AuthorizationResult authorizePlan(
        ApprovalMigrationPlan next,
        long expectedRevision,
        ApprovalMigrationPlanAuthorization authorization,
        ApprovalMigrationPlanEvent event
    ) {
        return writer.authorize(next, expectedRevision, authorization, event);
    }

    @Override
    public Optional<ApprovalMigrationPlanAuthorization> findAuthorization(
        String tenantId,
        UUID planId
    ) {
        return repository.findAuthorization(tenantId, planId);
    }

    @Override
    public Optional<ApprovalMigrationPlan> findAuthorizedPlan(
        String tenantId,
        UUID planId,
        String planHash,
        Instant validAt
    ) {
        return repository.findAuthorizedPlan(tenantId, planId, planHash, validAt);
    }

    @Override
    public List<ApprovalMigrationPlanEvent> findEvents(String tenantId, UUID planId) {
        return repository.findEvents(tenantId, planId);
    }
}
