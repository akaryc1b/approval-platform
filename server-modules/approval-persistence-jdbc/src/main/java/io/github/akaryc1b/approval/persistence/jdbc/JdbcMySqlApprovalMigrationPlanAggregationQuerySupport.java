package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore.AggregationRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore.AggregationResult;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanAggregate;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanAggregateEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanCompletion;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

abstract class JdbcMySqlApprovalMigrationPlanAggregationQuerySupport
    extends JdbcMySqlApprovalMigrationPlanAggregationBuildSupport {

    JdbcMySqlApprovalMigrationPlanAggregationQuerySupport(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager,
        AuditEventSink auditEvents,
        Supplier<UUID> identifiers
    ) {
        super(dataSource, objectMapper, transactionManager, auditEvents, identifiers);
    }

    protected AggregationResult result(PlanAggregate aggregate, boolean replayed) {
        PlanAggregateEvent event = findEvent(
            aggregate.tenantId(),
            aggregate.aggregateId()
        );
        PlanCompletion completion = findCompletion(
            aggregate.tenantId(),
            aggregate.aggregateId()
        ).orElse(null);
        return new AggregationResult(aggregate, event, completion, replayed);
    }

    protected void requireReplay(
        PlanAggregate aggregate,
        AggregationRequest request,
        String requestHash
    ) {
        if (!aggregate.tenantId().equals(request.tenantId())
            || !aggregate.operatorId().equals(request.operatorId())
            || !aggregate.planId().equals(request.planId())
            || aggregate.aggregateRevision() != request.expectedAggregateRevision()
            || !aggregate.idempotencyKey().equals(request.idempotencyKey())
            || !aggregate.reason().equals(request.reason())
            || !aggregate.requestId().equals(request.requestId())
            || !Objects.equals(aggregate.traceId(), request.traceId())
            || !aggregate.requestHash().equals(requestHash)) {
            throw conflict("changed plan aggregation replay is forbidden");
        }
    }

    protected Optional<PlanAggregate> findAggregateByIdempotency(
        String tenantId,
        String idempotencyKey
    ) {
        return queryJsonOne(
            "select payload_json from ap_process_migration_plan_aggregate "
                + "where tenant_id=:tenantId and idempotency_key=:idempotencyKey",
            params("tenantId", tenantId, "idempotencyKey", idempotencyKey),
            PlanAggregate.class,
            "multiple plan aggregates share one idempotency key"
        );
    }

    protected PlanAggregateEvent findEvent(String tenantId, UUID aggregateId) {
        return queryJsonOne(
            "select payload_json from ap_process_migration_plan_aggregate_event "
                + "where tenant_id=:tenantId and aggregate_id=:aggregateId",
            params("tenantId", tenantId, "aggregateId", aggregateId),
            PlanAggregateEvent.class,
            "multiple events reference one plan aggregate"
        ).orElseThrow(() -> conflict("plan aggregate event is missing"));
    }

    protected Optional<PlanCompletion> findCompletion(
        String tenantId,
        UUID aggregateId
    ) {
        return queryJsonOne(
            "select payload_json from ap_process_migration_plan_completion "
                + "where tenant_id=:tenantId and aggregate_id=:aggregateId",
            params("tenantId", tenantId, "aggregateId", aggregateId),
            PlanCompletion.class,
            "multiple completions reference one plan aggregate"
        );
    }

    protected <T> Optional<T> queryJsonOne(
        String sql,
        MapSqlParameterSource parameters,
        Class<T> type,
        String duplicateMessage
    ) {
        return queryOne(
            sql,
            parameters,
            (row, number) -> json.read(row.getString(1), type),
            duplicateMessage
        );
    }

    protected long currentAggregateRevision(String tenantId, UUID planId) {
        Long value = jdbc.queryForObject(
            "select coalesce(max(aggregate_revision),0) "
                + "from ap_process_migration_plan_aggregate "
                + "where tenant_id=:tenantId and plan_id=:planId",
            params("tenantId", tenantId, "planId", planId),
            Long.class
        );
        return value == null ? 0 : value;
    }

    protected PlanAggregate latestAggregate(String tenantId, UUID planId) {
        return queryLatest(
            "select payload_json from ap_process_migration_plan_aggregate "
                + "where tenant_id=:tenantId and plan_id=:planId "
                + "order by aggregate_revision desc limit 1",
            params("tenantId", tenantId, "planId", planId),
            (row, number) -> json.read(row.getString(1), PlanAggregate.class)
        ).orElseThrow(() -> conflict(
            "plan aggregate predecessor was not found"
        ));
    }

    protected String latestAggregateHash(String tenantId, UUID planId) {
        return queryLatest(
            "select aggregate_hash from ap_process_migration_plan_aggregate "
                + "where tenant_id=:tenantId and plan_id=:planId "
                + "order by aggregate_revision desc limit 1",
            params("tenantId", tenantId, "planId", planId),
            (row, number) -> row.getString(1)
        ).orElseThrow(() -> conflict(
            "plan aggregate predecessor was not found"
        ));
    }
}
