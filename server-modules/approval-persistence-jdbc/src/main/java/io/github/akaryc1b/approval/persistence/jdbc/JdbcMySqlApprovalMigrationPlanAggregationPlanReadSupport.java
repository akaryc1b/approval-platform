package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.InstanceFact;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

abstract class JdbcMySqlApprovalMigrationPlanAggregationPlanReadSupport
    extends JdbcMySqlApprovalMigrationPlanAggregationBase {

    JdbcMySqlApprovalMigrationPlanAggregationPlanReadSupport(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager,
        AuditEventSink auditEvents,
        Supplier<UUID> identifiers
    ) {
        super(dataSource, objectMapper, transactionManager, auditEvents, identifiers);
    }

    protected PlanContext lockPlan(String tenantId, UUID planId) {
        PlanContext plan = queryOne(
            """
            select plan.tenant_id,plan.plan_id,plan.plan_hash,
              plan.selected_instance_count,intent.intent_id,intent.intent_evidence_hash
            from ap_process_migration_plan plan
            join ap_process_migration_plan_consumption consumption
              on consumption.tenant_id=plan.tenant_id
             and consumption.plan_id=plan.plan_id
             and consumption.plan_hash=plan.plan_hash
            join ap_process_migration_intent intent
              on intent.tenant_id=consumption.tenant_id
             and intent.intent_id=consumption.intent_id
             and intent.plan_id=plan.plan_id
             and intent.plan_hash=plan.plan_hash
             and intent.intent_evidence_hash=consumption.intent_evidence_hash
            where plan.tenant_id=:tenantId and plan.plan_id=:planId
              and plan.status='CONSUMED'
            for update
            """,
            params("tenantId", tenantId, "planId", planId),
            (row, number) -> new PlanContext(
                row.getString("tenant_id"),
                values.uuid(row, "plan_id"),
                row.getString("plan_hash"),
                row.getInt("selected_instance_count"),
                values.uuid(row, "intent_id"),
                row.getString("intent_evidence_hash")
            ),
            "multiple consumed migration plans were found in tenant"
        ).orElseThrow(() -> conflict(
            "consumed migration plan was not found in tenant"
        ));

        Integer actualCount = jdbc.queryForObject(
            "select count(*) from ap_process_migration_plan_instance "
                + "where tenant_id=:tenantId and plan_id=:planId",
            params("tenantId", plan.tenantId(), "planId", plan.planId()),
            Integer.class
        );
        if (actualCount == null || actualCount != plan.selectedCount()) {
            throw conflict("consumed migration plan canonical sequence is incomplete");
        }
        return plan;
    }

    protected List<InstanceFact> loadFacts(PlanContext plan) {
        return jdbc.query(
            """
            with ranked_attempt as (
              select value.*,
                count(*) over (
                  partition by value.tenant_id,value.intent_id,
                    value.approval_instance_id
                ) attempt_count,
                row_number() over (
                  partition by value.tenant_id,value.intent_id,
                    value.approval_instance_id
                  order by value.attempt_number desc,value.attempt_id desc
                ) attempt_rank
              from ap_process_migration_attempt value
              where value.tenant_id=:tenantId and value.intent_id=:intentId
            ),
            ranked_completion as (
              select value.*,
                count(*) over (
                  partition by value.tenant_id,value.intent_id,
                    value.approval_instance_id
                ) completion_count,
                row_number() over (
                  partition by value.tenant_id,value.intent_id,
                    value.approval_instance_id
                  order by value.completed_at desc,value.completion_id desc
                ) completion_rank
              from ap_process_migration_instance_completion value
              where value.tenant_id=:tenantId and value.intent_id=:intentId
            ),
            ranked_conflict as (
              select value.*,
                count(*) over (
                  partition by value.tenant_id,value.intent_id,
                    value.approval_instance_id
                ) conflict_count,
                row_number() over (
                  partition by value.tenant_id,value.intent_id,
                    value.approval_instance_id
                  order by value.recorded_at desc,value.conflict_id desc
                ) conflict_rank
              from ap_process_migration_binding_cas_conflict value
              where value.tenant_id=:tenantId and value.intent_id=:intentId
            ),
            ranked_reconciliation as (
              select value.*,
                row_number() over (
                  partition by value.tenant_id,value.attempt_id
                  order by value.sequence desc,value.reconciliation_id desc
                ) reconciliation_rank
              from ap_process_migration_reconciliation value
              where value.tenant_id=:tenantId
            )
            select selection.sequence_no,selection.approval_instance_id,
              selection.instance_evidence_hash,
              (canary.approval_instance_id is not null) canary,
              coalesce(attempt.attempt_count,0) attempt_count,
              attempt.attempt_id,attempt.attempt_number,
              attempt.status attempt_status,attempt.revision attempt_revision,
              attempt.engine_outcome,attempt.expected_binding_evidence_hash,
              request.request_hash engine_request_hash,
              request.evidence_hash engine_request_evidence_hash,
              outcome.disposition engine_outcome_disposition,
              outcome.outcome_hash,
              verification.classification verification_classification,
              verification.truncated verification_truncated,
              verification.verification_evidence_hash,
              coalesce(completion.completion_count,0) completion_count,
              completion.attempt_id completion_attempt_id,
              completion.binding_revision,
              completion.target_binding_evidence_hash,
              completion.completion_evidence_hash completion_hash,
              coalesce(conflict.conflict_count,0) conflict_count,
              conflict.conflict_evidence_hash conflict_hash,
              reconciliation.status reconciliation_status,
              coalesce(
                reconciliation.resolution_evidence_hash,
                reconciliation.evidence_hash
              ) reconciliation_hash,
              observation.classification observation_classification,
              observation.disposition observation_disposition,
              observation.evidence_hash observation_hash
            from ap_process_migration_plan_instance selection
            left join ap_process_migration_canary_selection canary
              on canary.tenant_id=selection.tenant_id
             and canary.plan_id=selection.plan_id
             and canary.approval_instance_id=selection.approval_instance_id
            left join ranked_attempt attempt
              on attempt.tenant_id=selection.tenant_id
             and attempt.intent_id=:intentId
             and attempt.approval_instance_id=selection.approval_instance_id
             and attempt.attempt_rank=1
            left join ap_process_migration_engine_request request
              on request.tenant_id=selection.tenant_id
             and request.attempt_id=attempt.attempt_id
            left join ap_process_migration_engine_outcome outcome
              on outcome.tenant_id=selection.tenant_id
             and outcome.attempt_id=attempt.attempt_id
            left join ap_process_migration_exact_verification verification
              on verification.tenant_id=selection.tenant_id
             and verification.attempt_id=attempt.attempt_id
            left join ranked_completion completion
              on completion.tenant_id=selection.tenant_id
             and completion.intent_id=:intentId
             and completion.approval_instance_id=selection.approval_instance_id
             and completion.completion_rank=1
            left join ranked_conflict conflict
              on conflict.tenant_id=selection.tenant_id
             and conflict.intent_id=:intentId
             and conflict.approval_instance_id=selection.approval_instance_id
             and conflict.conflict_rank=1
            left join ranked_reconciliation reconciliation
              on reconciliation.tenant_id=selection.tenant_id
             and reconciliation.attempt_id=attempt.attempt_id
             and reconciliation.reconciliation_rank=1
            left join ap_process_migration_reconciliation_observation observation
              on observation.tenant_id=selection.tenant_id
             and observation.attempt_id=attempt.attempt_id
            where selection.tenant_id=:tenantId and selection.plan_id=:planId
            order by selection.sequence_no
            """,
            params(
                "tenantId", plan.tenantId(),
                "planId", plan.planId(),
                "intentId", plan.intentId()
            ),
            (row, number) -> fact(new FactRow(
                row.getInt("sequence_no"),
                values.uuid(row, "approval_instance_id"),
                row.getBoolean("canary"),
                row.getString("instance_evidence_hash"),
                row.getInt("attempt_count"),
                values.nullableUuid(row, "attempt_id"),
                nullableInteger(row, "attempt_number"),
                row.getString("attempt_status"),
                nullableLong(row, "attempt_revision"),
                row.getString("engine_outcome"),
                row.getString("expected_binding_evidence_hash"),
                row.getString("engine_request_hash"),
                row.getString("engine_request_evidence_hash"),
                row.getString("engine_outcome_disposition"),
                row.getString("outcome_hash"),
                row.getString("verification_classification"),
                nullableBoolean(row, "verification_truncated"),
                row.getString("verification_evidence_hash"),
                row.getInt("completion_count"),
                values.nullableUuid(row, "completion_attempt_id"),
                nullableLong(row, "binding_revision"),
                row.getString("target_binding_evidence_hash"),
                row.getString("completion_hash"),
                row.getInt("conflict_count"),
                row.getString("conflict_hash"),
                row.getString("reconciliation_status"),
                row.getString("reconciliation_hash"),
                row.getString("observation_classification"),
                row.getString("observation_disposition"),
                row.getString("observation_hash")
            ))
        );
    }

    protected abstract InstanceFact fact(FactRow row);
}
