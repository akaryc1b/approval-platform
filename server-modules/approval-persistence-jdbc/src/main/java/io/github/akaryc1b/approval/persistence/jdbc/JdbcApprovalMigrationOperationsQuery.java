package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationOperationsQuery;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOperationsQuery.InstanceItem;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOperationsQuery.InstancePage;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOperationsQuery.OperationsSummary;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOperationsQuery.PlanCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOperationsQuery.PlanDetail;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOperationsQuery.PlanItem;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOperationsQuery.PlanPage;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.AggregateStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.CanaryStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.OrchestrationStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PauseReason;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.TerminalOutcome;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanProtocol.PlanStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.IntentStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Bounded tenant-scoped M5-E1 operations reads; no mutation or Flowable access exists here. */
public final class JdbcApprovalMigrationOperationsQuery implements ApprovalMigrationOperationsQuery {

    private static final String PLAN_SELECT = """
        select plan.plan_id,plan.plan_hash,plan.definition_key,
          plan.source_release_version,plan.source_package_hash,
          plan.target_release_version,plan.target_package_hash,
          plan.selected_instance_count,plan.status plan_status,
          plan.created_at,plan.request_id,plan.trace_id,plan.audit_chain_reference,
          consumption.consumed_at,intent.intent_id,intent.status intent_status,
          latest.aggregate_revision,latest.status aggregate_status,
          latest.terminal_outcome,latest.exact_success_count,
          latest.terminal_failed_count,latest.unresolved_count,
          latest.canary_status,latest.orchestration_status,
          coalesce(latest.paused,false) paused,
          coalesce(latest.pause_reason,'NONE') pause_reason,
          coalesce(latest.kill_switch_observed,false) kill_switch_observed,
          latest.input_evidence_hash,latest.predecessor_hash,
          latest.aggregate_hash,latest.aggregated_at,
          completion.completion_status,completion.completed_at,
          completion.completion_evidence_hash
        from ap_process_migration_plan plan
        left join ap_process_migration_plan_consumption consumption
          on consumption.tenant_id=plan.tenant_id and consumption.plan_id=plan.plan_id
        left join ap_process_migration_intent intent
          on intent.tenant_id=consumption.tenant_id
         and intent.intent_id=consumption.intent_id
        left join lateral (
          select value.aggregate_revision,value.status,value.terminal_outcome,
            value.exact_success_count,value.terminal_failed_count,value.unresolved_count,
            value.canary_status,value.orchestration_status,value.paused,value.pause_reason,
            value.kill_switch_observed,value.input_evidence_hash,value.predecessor_hash,
            value.aggregate_hash,value.aggregated_at
          from ap_process_migration_plan_aggregate value
          where value.tenant_id=plan.tenant_id and value.plan_id=plan.plan_id
          order by value.aggregate_revision desc,value.aggregate_id desc limit 1
        ) latest on true
        left join ap_process_migration_plan_completion completion
          on completion.tenant_id=plan.tenant_id and completion.plan_id=plan.plan_id
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public JdbcApprovalMigrationOperationsQuery(DataSource dataSource, Clock clock) {
        jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public OperationsSummary summarize(String tenantId) {
        String tenant = requireTenant(tenantId);
        return jdbc.queryForObject("""
            select count(*) total_plans,
              count(*) filter (where plan.status='CONSUMED') consumed_plans,
              count(*) filter (where plan.status='CONSUMED'
                and coalesce(latest.paused,false)=false
                and coalesce(latest.status,'NOT_STARTED') not in (
                  'COMPLETED_SUCCEEDED','COMPLETED_WITH_TERMINAL_FAILURE',
                  'INVALID_OR_INCOMPLETE_EVIDENCE'
                )) active_plans,
              count(*) filter (where coalesce(latest.paused,false)) paused_plans,
              count(*) filter (where plan.status='CONSUMED'
                and (latest.aggregate_id is null or latest.unresolved_count>0)) unresolved_plans,
              count(*) filter (where latest.status in (
                'COMPLETED_SUCCEEDED','COMPLETED_WITH_TERMINAL_FAILURE'
              )) completed_plans,
              count(*) filter (where coalesce(latest.kill_switch_observed,false))
                kill_switch_observed_plans,
              max(latest.aggregated_at) latest_aggregated_at
            from ap_process_migration_plan plan
            left join lateral (
              select value.aggregate_id,value.status,value.paused,value.unresolved_count,
                value.kill_switch_observed,value.aggregated_at
              from ap_process_migration_plan_aggregate value
              where value.tenant_id=plan.tenant_id and value.plan_id=plan.plan_id
              order by value.aggregate_revision desc,value.aggregate_id desc limit 1
            ) latest on true
            where plan.tenant_id=:tenantId
            """, params("tenantId", tenant), (row, number) -> new OperationsSummary(
                tenant,
                row.getLong("total_plans"),
                row.getLong("consumed_plans"),
                row.getLong("active_plans"),
                row.getLong("paused_plans"),
                row.getLong("unresolved_plans"),
                row.getLong("completed_plans"),
                row.getLong("kill_switch_observed_plans"),
                instant(row, "latest_aggregated_at"),
                clock.instant()
            ));
    }

    @Override
    public PlanPage findPlans(PlanCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        StringBuilder where = new StringBuilder(" where plan.tenant_id=:tenantId");
        MapSqlParameterSource parameters = params("tenantId", criteria.tenantId());
        if (criteria.definitionKey() != null) {
            where.append(" and plan.definition_key=:definitionKey");
            parameters.addValue("definitionKey", criteria.definitionKey());
        }
        if (criteria.planStatus() != null) {
            where.append(" and plan.status=:planStatus");
            parameters.addValue("planStatus", criteria.planStatus().name());
        }
        if (criteria.aggregateStatus() != null) {
            where.append(" and latest.status=:aggregateStatus");
            parameters.addValue("aggregateStatus", criteria.aggregateStatus().name());
        }
        if (criteria.paused() != null) {
            where.append(" and coalesce(latest.paused,false)=:paused");
            parameters.addValue("paused", criteria.paused());
        }
        Long totalValue = jdbc.queryForObject(
            "select count(*) from (" + PLAN_SELECT + where + ") visible_plans",
            parameters,
            Long.class
        );
        long total = totalValue == null ? 0 : totalValue;
        parameters.addValue("limit", criteria.limit());
        parameters.addValue("offset", criteria.offset());
        List<PlanItem> items = jdbc.query(
            PLAN_SELECT + where
                + " order by plan.created_at desc,plan.plan_id desc limit :limit offset :offset",
            parameters,
            (row, number) -> planItem(row)
        );
        return new PlanPage(
            items,
            total,
            criteria.limit(),
            criteria.offset(),
            (long) criteria.offset() + items.size() < total
        );
    }

    @Override
    public Optional<PlanDetail> findPlan(String tenantId, UUID planId) {
        String tenant = requireTenant(tenantId);
        Objects.requireNonNull(planId, "planId must not be null");
        return jdbc.query(
            PLAN_SELECT + " where plan.tenant_id=:tenantId and plan.plan_id=:planId",
            params("tenantId", tenant, "planId", planId),
            (row, number) -> new PlanDetail(
                planItem(row),
                row.getString("source_package_hash"),
                row.getString("target_package_hash"),
                row.getString("input_evidence_hash"),
                row.getString("predecessor_hash"),
                row.getString("aggregate_hash"),
                row.getString("completion_evidence_hash"),
                row.getString("request_id"),
                row.getString("trace_id"),
                row.getString("audit_chain_reference")
            )
        ).stream().findFirst();
    }

    @Override
    public InstancePage findInstances(String tenantId, UUID planId, int limit, int offset) {
        String tenant = requireTenant(tenantId);
        Objects.requireNonNull(planId, "planId must not be null");
        if (limit < 1 || limit > MAX_PAGE_SIZE || offset < 0) {
            throw new IllegalArgumentException("pagination is outside the bounded range");
        }
        Integer planCount = jdbc.queryForObject(
            "select count(*) from ap_process_migration_plan "
                + "where tenant_id=:tenantId and plan_id=:planId",
            params("tenantId", tenant, "planId", planId),
            Integer.class
        );
        if (planCount == null || planCount != 1) {
            throw new MigrationOperationsNotFoundException("migration plan was not found");
        }
        Long totalValue = jdbc.queryForObject(
            "select count(*) from ap_process_migration_plan_instance "
                + "where tenant_id=:tenantId and plan_id=:planId",
            params("tenantId", tenant, "planId", planId),
            Long.class
        );
        long total = totalValue == null ? 0 : totalValue;
        List<InstanceItem> items = jdbc.query("""
            select selection.sequence_no,selection.approval_instance_id,
              selection.instance_evidence_hash,
              (canary.approval_instance_id is not null) canary,
              attempt.attempt_id,attempt.attempt_number,attempt.status attempt_status,
              attempt.revision attempt_revision,attempt.engine_outcome,attempt.updated_at,
              verification.classification verification_classification,
              verification.verification_evidence_hash,verification.recorded_at verification_at,
              completion.completion_evidence_hash,completion.completed_at,
              conflict.conflict_evidence_hash,conflict.recorded_at conflict_at,
              reconciliation.status reconciliation_status,
              coalesce(reconciliation.resolution_evidence_hash,reconciliation.evidence_hash)
                reconciliation_evidence_hash,
              coalesce(reconciliation.resolved_at,reconciliation.recorded_at) reconciliation_at,
              observation.disposition reconciliation_disposition,
              observation.evidence_hash observation_evidence_hash,
              observation.recorded_at observation_at
            from ap_process_migration_plan_instance selection
            join ap_process_migration_plan plan
              on plan.tenant_id=selection.tenant_id and plan.plan_id=selection.plan_id
            left join ap_process_migration_plan_consumption consumption
              on consumption.tenant_id=plan.tenant_id and consumption.plan_id=plan.plan_id
            left join ap_process_migration_canary_selection canary
              on canary.tenant_id=selection.tenant_id and canary.plan_id=selection.plan_id
             and canary.approval_instance_id=selection.approval_instance_id
            left join lateral (
              select value.attempt_id,value.attempt_number,value.status,value.revision,
                value.engine_outcome,value.updated_at
              from ap_process_migration_attempt value
              where value.tenant_id=selection.tenant_id
                and value.intent_id=consumption.intent_id
                and value.approval_instance_id=selection.approval_instance_id
              order by value.attempt_number desc,value.attempt_id desc limit 1
            ) attempt on true
            left join ap_process_migration_exact_verification verification
              on verification.tenant_id=selection.tenant_id
             and verification.attempt_id=attempt.attempt_id
            left join lateral (
              select value.completion_evidence_hash,value.completed_at
              from ap_process_migration_instance_completion value
              where value.tenant_id=selection.tenant_id
                and value.intent_id=consumption.intent_id
                and value.approval_instance_id=selection.approval_instance_id
              order by value.completed_at desc,value.completion_id desc limit 1
            ) completion on true
            left join lateral (
              select value.conflict_evidence_hash,value.recorded_at
              from ap_process_migration_binding_cas_conflict value
              where value.tenant_id=selection.tenant_id
                and value.intent_id=consumption.intent_id
                and value.approval_instance_id=selection.approval_instance_id
              order by value.recorded_at desc,value.conflict_id desc limit 1
            ) conflict on true
            left join lateral (
              select value.status,value.evidence_hash,value.resolution_evidence_hash,
                value.recorded_at,value.resolved_at
              from ap_process_migration_reconciliation value
              where value.tenant_id=selection.tenant_id
                and value.attempt_id=attempt.attempt_id
              order by value.sequence desc,value.reconciliation_id desc limit 1
            ) reconciliation on true
            left join ap_process_migration_reconciliation_observation observation
              on observation.tenant_id=selection.tenant_id
             and observation.attempt_id=attempt.attempt_id
            where selection.tenant_id=:tenantId and selection.plan_id=:planId
            order by selection.sequence_no
            limit :limit offset :offset
            """, params(
                "tenantId", tenant,
                "planId", planId,
                "limit", limit,
                "offset", offset
            ), (row, number) -> instanceItem(row));
        return new InstancePage(
            planId,
            items,
            total,
            limit,
            offset,
            (long) offset + items.size() < total
        );
    }

    private static PlanItem planItem(ResultSet row) throws SQLException {
        return new PlanItem(
            row.getObject("plan_id", UUID.class),
            row.getString("plan_hash"),
            row.getString("definition_key"),
            row.getInt("source_release_version"),
            row.getInt("target_release_version"),
            row.getInt("selected_instance_count"),
            enumValue(row.getString("plan_status"), PlanStatus.class),
            row.getObject("intent_id", UUID.class),
            enumValue(row.getString("intent_status"), IntentStatus.class),
            nullableLong(row, "aggregate_revision"),
            enumValue(row.getString("aggregate_status"), AggregateStatus.class),
            enumValue(row.getString("terminal_outcome"), TerminalOutcome.class),
            row.getInt("exact_success_count"),
            row.getInt("terminal_failed_count"),
            row.getInt("unresolved_count"),
            enumValue(row.getString("canary_status"), CanaryStatus.class),
            enumValue(row.getString("orchestration_status"), OrchestrationStatus.class),
            row.getBoolean("paused"),
            enumValue(row.getString("pause_reason"), PauseReason.class),
            row.getBoolean("kill_switch_observed"),
            instant(row, "created_at"),
            instant(row, "consumed_at"),
            instant(row, "aggregated_at"),
            enumValue(row.getString("completion_status"), AggregateStatus.class),
            instant(row, "completed_at")
        );
    }

    private static InstanceItem instanceItem(ResultSet row) throws SQLException {
        String completionHash = row.getString("completion_evidence_hash");
        String conflictHash = row.getString("conflict_evidence_hash");
        String reconciliationHash = row.getString("reconciliation_evidence_hash");
        String observationHash = row.getString("observation_evidence_hash");
        String verificationHash = row.getString("verification_evidence_hash");
        String selectedHash = row.getString("instance_evidence_hash");
        String latestHash = firstNonNull(
            completionHash,
            conflictHash,
            reconciliationHash,
            observationHash,
            verificationHash,
            selectedHash
        );
        Instant latestAt = firstNonNull(
            instant(row, "completed_at"),
            instant(row, "conflict_at"),
            instant(row, "reconciliation_at"),
            instant(row, "observation_at"),
            instant(row, "verification_at"),
            instant(row, "updated_at")
        );
        return new InstanceItem(
            row.getInt("sequence_no"),
            row.getObject("approval_instance_id", UUID.class),
            row.getBoolean("canary"),
            row.getObject("attempt_id", UUID.class),
            nullableInteger(row, "attempt_number"),
            row.getString("attempt_status"),
            nullableLong(row, "attempt_revision"),
            row.getString("engine_outcome"),
            row.getString("verification_classification"),
            row.getString("reconciliation_status"),
            row.getString("reconciliation_disposition"),
            completionHash != null,
            conflictHash != null,
            selectedHash,
            latestHash,
            latestAt
        );
    }

    private static String requireTenant(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        String normalized = tenantId.trim();
        if (normalized.isEmpty() || normalized.length() > 128) {
            throw new IllegalArgumentException("tenantId is blank or exceeds maximum length 128");
        }
        return normalized;
    }

    private static MapSqlParameterSource params(Object... values) {
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        for (int index = 0; index < values.length; index += 2) {
            parameters.addValue((String) values[index], values[index + 1]);
        }
        return parameters;
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Integer nullableInteger(ResultSet row, String column) throws SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static <T extends Enum<T>> T enumValue(String value, Class<T> type) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public static final class MigrationOperationsNotFoundException extends RuntimeException {
        public MigrationOperationsNotFoundException(String message) {
            super(message);
        }
    }
}
