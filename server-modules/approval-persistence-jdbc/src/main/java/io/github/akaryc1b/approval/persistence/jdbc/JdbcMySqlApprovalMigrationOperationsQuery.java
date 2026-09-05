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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** MySQL 8.4 read-only M5-E1 visibility over immutable D1-D8 migration evidence. */
public final class JdbcMySqlApprovalMigrationOperationsQuery
    implements ApprovalMigrationOperationsQuery {

    private static final String LATEST_AGGREGATE_JOIN = """
        left join (
          select value.aggregate_id,value.tenant_id,value.plan_id,
            value.aggregate_revision,value.status,value.terminal_outcome,
            value.exact_success_count,value.terminal_failed_count,value.unresolved_count,
            value.canary_status,value.orchestration_status,value.paused,value.pause_reason,
            value.kill_switch_observed,value.input_evidence_hash,value.predecessor_hash,
            value.aggregate_hash,value.aggregated_at,
            row_number() over (
              partition by value.tenant_id,value.plan_id
              order by value.aggregate_revision desc,value.aggregate_id desc
            ) latest_rank
          from ap_process_migration_plan_aggregate value
          where value.tenant_id=:tenantId
        ) latest
          on latest.tenant_id=plan.tenant_id and latest.plan_id=plan.plan_id
         and latest.latest_rank=1
        """;

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
        """ + LATEST_AGGREGATE_JOIN + """
        left join ap_process_migration_plan_completion completion
          on completion.tenant_id=plan.tenant_id and completion.plan_id=plan.plan_id
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JdbcDatabaseValueAdapter values;
    private final Clock clock;

    public JdbcMySqlApprovalMigrationOperationsQuery(
        DataSource dataSource,
        PlatformTransactionManager transactionManager,
        Clock clock
    ) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        PlatformTransactionManager manager = Objects.requireNonNull(
            transactionManager,
            "transactionManager must not be null"
        );
        this.values = JdbcDatabaseValueAdapter.resolve(source);
        if (values.vendor() != ApprovalDatabaseVendor.MYSQL) {
            throw new IllegalArgumentException(
                "JdbcMySqlApprovalMigrationOperationsQuery requires MySQL 8.4"
            );
        }
        this.jdbc = new NamedParameterJdbcTemplate(source);
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setReadOnly(true);
        this.transactions.setIsolationLevel(
            TransactionDefinition.ISOLATION_REPEATABLE_READ
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public OperationsSummary summarize(String tenantId) {
        String tenant = requireTenant(tenantId);
        return read(() -> jdbc.queryForObject("""
            select count(*) total_plans,
              coalesce(sum(case when plan.status='CONSUMED' then 1 else 0 end),0)
                consumed_plans,
              coalesce(sum(case when plan.status='CONSUMED'
                and coalesce(latest.paused,false)=false
                and coalesce(latest.status,'NOT_STARTED') not in (
                  'COMPLETED_SUCCEEDED','COMPLETED_WITH_TERMINAL_FAILURE',
                  'INVALID_OR_INCOMPLETE_EVIDENCE'
                ) then 1 else 0 end),0) active_plans,
              coalesce(sum(case when coalesce(latest.paused,false)
                then 1 else 0 end),0) paused_plans,
              coalesce(sum(case when plan.status='CONSUMED'
                and (latest.aggregate_id is null or latest.unresolved_count>0)
                then 1 else 0 end),0) unresolved_plans,
              coalesce(sum(case when latest.status in (
                'COMPLETED_SUCCEEDED','COMPLETED_WITH_TERMINAL_FAILURE'
              ) then 1 else 0 end),0) completed_plans,
              coalesce(sum(case when coalesce(latest.kill_switch_observed,false)
                then 1 else 0 end),0) kill_switch_observed_plans,
              max(latest.aggregated_at) latest_aggregated_at
            from ap_process_migration_plan plan
            """ + LATEST_AGGREGATE_JOIN + """
            where plan.tenant_id=:tenantId
            """, parameters("tenantId", tenant), (row, number) -> new OperationsSummary(
                tenant,
                row.getLong("total_plans"),
                row.getLong("consumed_plans"),
                row.getLong("active_plans"),
                row.getLong("paused_plans"),
                row.getLong("unresolved_plans"),
                row.getLong("completed_plans"),
                row.getLong("kill_switch_observed_plans"),
                values.nullableInstant(row, "latest_aggregated_at"),
                clock.instant()
            )));
    }

    @Override
    public PlanPage findPlans(PlanCriteria criteria) {
        PlanCriteria exact = Objects.requireNonNull(criteria, "criteria must not be null");
        return read(() -> findPlansOnce(exact));
    }

    private PlanPage findPlansOnce(PlanCriteria criteria) {
        StringBuilder where = new StringBuilder(" where plan.tenant_id=:tenantId");
        MapSqlParameterSource parameters = parameters("tenantId", criteria.tenantId());
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
        UUID exactPlanId = Objects.requireNonNull(planId, "planId must not be null");
        return read(() -> jdbc.query(
            PLAN_SELECT + " where plan.tenant_id=:tenantId and plan.plan_id=:planId",
            parameters(
                "tenantId", tenant,
                "planId", values.bindUuid(planId)
            ),
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
        ).stream().filter(detail -> detail.plan().planId().equals(exactPlanId)).findFirst());
    }

    @Override
    public InstancePage findInstances(String tenantId, UUID planId, int limit, int offset) {
        String tenant = requireTenant(tenantId);
        UUID exactPlanId = Objects.requireNonNull(planId, "planId must not be null");
        if (limit < 1 || limit > MAX_PAGE_SIZE || offset < 0) {
            throw new IllegalArgumentException("pagination is outside the bounded range");
        }
        return read(() -> findInstancesOnce(tenant, exactPlanId, limit, offset));
    }

    private InstancePage findInstancesOnce(
        String tenant,
        UUID planId,
        int limit,
        int offset
    ) {
        MapSqlParameterSource identity = parameters(
            "tenantId", tenant,
            "planId", values.bindUuid(planId)
        );
        Integer planCount = jdbc.queryForObject(
            "select count(*) from ap_process_migration_plan "
                + "where tenant_id=:tenantId and plan_id=:planId",
            identity,
            Integer.class
        );
        if (planCount == null || planCount != 1) {
            throw new JdbcApprovalMigrationOperationsQuery.MigrationOperationsNotFoundException(
                "migration plan was not found"
            );
        }
        Long totalValue = jdbc.queryForObject(
            "select count(*) from ap_process_migration_plan_instance "
                + "where tenant_id=:tenantId and plan_id=:planId",
            identity,
            Long.class
        );
        long total = totalValue == null ? 0 : totalValue;
        MapSqlParameterSource page = parameters(
            "tenantId", tenant,
            "planId", values.bindUuid(planId),
            "limit", limit,
            "offset", offset
        );
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
            left join (
              select value.attempt_id,value.tenant_id,value.intent_id,
                value.approval_instance_id,value.attempt_number,value.status,value.revision,
                value.engine_outcome,value.updated_at,
                row_number() over (
                  partition by value.tenant_id,value.intent_id,value.approval_instance_id
                  order by value.attempt_number desc,value.attempt_id desc
                ) latest_rank
              from ap_process_migration_attempt value
              where value.tenant_id=:tenantId
            ) attempt
              on attempt.tenant_id=selection.tenant_id
             and attempt.intent_id=consumption.intent_id
             and attempt.approval_instance_id=selection.approval_instance_id
             and attempt.latest_rank=1
            left join ap_process_migration_exact_verification verification
              on verification.tenant_id=selection.tenant_id
             and verification.attempt_id=attempt.attempt_id
            left join (
              select value.tenant_id,value.intent_id,value.approval_instance_id,
                value.completion_evidence_hash,value.completed_at,
                row_number() over (
                  partition by value.tenant_id,value.intent_id,value.approval_instance_id
                  order by value.completed_at desc,value.completion_id desc
                ) latest_rank
              from ap_process_migration_instance_completion value
              where value.tenant_id=:tenantId
            ) completion
              on completion.tenant_id=selection.tenant_id
             and completion.intent_id=consumption.intent_id
             and completion.approval_instance_id=selection.approval_instance_id
             and completion.latest_rank=1
            left join (
              select value.tenant_id,value.intent_id,value.approval_instance_id,
                value.conflict_evidence_hash,value.recorded_at,
                row_number() over (
                  partition by value.tenant_id,value.intent_id,value.approval_instance_id
                  order by value.recorded_at desc,value.conflict_id desc
                ) latest_rank
              from ap_process_migration_binding_cas_conflict value
              where value.tenant_id=:tenantId
            ) conflict
              on conflict.tenant_id=selection.tenant_id
             and conflict.intent_id=consumption.intent_id
             and conflict.approval_instance_id=selection.approval_instance_id
             and conflict.latest_rank=1
            left join (
              select value.tenant_id,value.attempt_id,value.status,value.evidence_hash,
                value.resolution_evidence_hash,value.recorded_at,value.resolved_at,
                row_number() over (
                  partition by value.tenant_id,value.attempt_id
                  order by value.sequence desc,value.reconciliation_id desc
                ) latest_rank
              from ap_process_migration_reconciliation value
              where value.tenant_id=:tenantId
            ) reconciliation
              on reconciliation.tenant_id=selection.tenant_id
             and reconciliation.attempt_id=attempt.attempt_id
             and reconciliation.latest_rank=1
            left join ap_process_migration_reconciliation_observation observation
              on observation.tenant_id=selection.tenant_id
             and observation.attempt_id=attempt.attempt_id
            where selection.tenant_id=:tenantId and selection.plan_id=:planId
            order by selection.sequence_no
            limit :limit offset :offset
            """, page, (row, number) -> instanceItem(row));
        return new InstancePage(
            planId,
            items,
            total,
            limit,
            offset,
            (long) offset + items.size() < total
        );
    }

    private PlanItem planItem(ResultSet row) throws SQLException {
        return new PlanItem(
            values.uuid(row, "plan_id"),
            row.getString("plan_hash"),
            row.getString("definition_key"),
            row.getInt("source_release_version"),
            row.getInt("target_release_version"),
            row.getInt("selected_instance_count"),
            enumValue(row.getString("plan_status"), PlanStatus.class),
            values.nullableUuid(row, "intent_id"),
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
            values.nullableInstant(row, "created_at"),
            values.nullableInstant(row, "consumed_at"),
            values.nullableInstant(row, "aggregated_at"),
            enumValue(row.getString("completion_status"), AggregateStatus.class),
            values.nullableInstant(row, "completed_at")
        );
    }

    private InstanceItem instanceItem(ResultSet row) throws SQLException {
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
            values.nullableInstant(row, "completed_at"),
            values.nullableInstant(row, "conflict_at"),
            values.nullableInstant(row, "reconciliation_at"),
            values.nullableInstant(row, "observation_at"),
            values.nullableInstant(row, "verification_at"),
            values.nullableInstant(row, "updated_at")
        );
        return new InstanceItem(
            row.getInt("sequence_no"),
            values.uuid(row, "approval_instance_id"),
            row.getBoolean("canary"),
            values.nullableUuid(row, "attempt_id"),
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

    private <T> T read(Supplier<T> operation) {
        T result = transactions.execute(status -> operation.get());
        return Objects.requireNonNull(result, "migration operations query returned no result");
    }

    private static String requireTenant(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        String normalized = tenantId.trim();
        if (normalized.isEmpty() || normalized.length() > 128) {
            throw new IllegalArgumentException("tenantId is blank or exceeds maximum length 128");
        }
        return normalized;
    }

    private static MapSqlParameterSource parameters(Object... entries) {
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        for (int index = 0; index < entries.length; index += 2) {
            parameters.addValue((String) entries[index], entries[index + 1]);
        }
        return parameters;
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
}
