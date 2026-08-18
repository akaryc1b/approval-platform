package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore.AggregationRequest;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanAggregate;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanAggregateEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanCompletion;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.StateCounts;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

abstract class JdbcMySqlApprovalMigrationPlanAggregationPersistenceSupport
    extends JdbcMySqlApprovalMigrationPlanAggregationQuerySupport {

    JdbcMySqlApprovalMigrationPlanAggregationPersistenceSupport(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager,
        AuditEventSink auditEvents,
        Supplier<UUID> identifiers
    ) {
        super(dataSource, objectMapper, transactionManager, auditEvents, identifiers);
    }

    protected void insertAggregate(PlanAggregate aggregate) {
        StateCounts counts = aggregate.counts();
        int inserted = jdbc.update(
            """
            insert into ap_process_migration_plan_aggregate (
              tenant_id,aggregate_id,plan_id,intent_id,plan_hash,aggregate_revision,
              status,terminal_outcome,selected_count,provisioned_attempt_count,
              pending_count,claimed_count,engine_requested_count,verifying_count,
              reconciling_count,unknown_count,manual_review_count,binding_conflict_count,
              blocked_stale_count,terminal_failed_count,exact_success_count,unresolved_count,
              canary_status,orchestration_status,paused,pause_reason,kill_switch_observed,
              input_evidence_hash,predecessor_hash,operator_id,idempotency_key,request_hash,
              aggregate_hash,aggregated_at,reason,request_id,trace_id,audit_reference,payload_json
            ) values (
              :tenantId,:aggregateId,:planId,:intentId,:planHash,:revision,
              :status,:terminalOutcome,:selected,:provisioned,:pending,:claimed,
              :engineRequested,:verifying,:reconciling,:unknown,:manualReview,
              :bindingConflict,:blockedStale,:terminalFailed,:exactSuccess,:unresolved,
              :canaryStatus,:orchestrationStatus,:paused,:pauseReason,:killSwitchObserved,
              :inputHash,:predecessor,:operatorId,:idempotencyKey,:requestHash,
              :aggregateHash,:aggregatedAt,:reason,:requestId,:traceId,:auditReference,:payload
            )
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", aggregate.tenantId())
                .addValue("aggregateId", values.bindUuid(aggregate.aggregateId()))
                .addValue("planId", values.bindUuid(aggregate.planId()))
                .addValue("intentId", values.bindUuid(aggregate.intentId()))
                .addValue("planHash", aggregate.planHash())
                .addValue("revision", aggregate.aggregateRevision())
                .addValue("status", aggregate.status().name())
                .addValue("terminalOutcome", aggregate.terminalOutcome().name())
                .addValue("selected", counts.selectedCount())
                .addValue("provisioned", counts.provisionedAttemptCount())
                .addValue("pending", counts.pendingCount())
                .addValue("claimed", counts.claimedCount())
                .addValue("engineRequested", counts.engineRequestedCount())
                .addValue("verifying", counts.verifyingCount())
                .addValue("reconciling", counts.reconcilingCount())
                .addValue("unknown", counts.unknownCount())
                .addValue("manualReview", counts.manualReviewCount())
                .addValue("bindingConflict", counts.bindingConflictCount())
                .addValue("blockedStale", counts.blockedStaleCount())
                .addValue("terminalFailed", counts.terminalFailedCount())
                .addValue("exactSuccess", counts.exactSuccessCount())
                .addValue("unresolved", counts.unresolvedCount())
                .addValue("canaryStatus", aggregate.canaryStatus().name())
                .addValue(
                    "orchestrationStatus",
                    aggregate.orchestrationStatus().name()
                )
                .addValue("paused", aggregate.paused())
                .addValue("pauseReason", aggregate.pauseReason().name())
                .addValue(
                    "killSwitchObserved",
                    aggregate.killSwitchObserved()
                )
                .addValue("inputHash", aggregate.inputEvidenceHash())
                .addValue("predecessor", aggregate.predecessorHash())
                .addValue("operatorId", aggregate.operatorId())
                .addValue("idempotencyKey", aggregate.idempotencyKey())
                .addValue("requestHash", aggregate.requestHash())
                .addValue("aggregateHash", aggregate.aggregateHash())
                .addValue(
                    "aggregatedAt",
                    values.bindInstant(aggregate.aggregatedAt())
                )
                .addValue("reason", aggregate.reason())
                .addValue("requestId", aggregate.requestId())
                .addValue("traceId", aggregate.traceId())
                .addValue("auditReference", aggregate.auditReference())
                .addValue("payload", json.write(aggregate))
        );
        if (inserted != 1) {
            throw conflict("plan aggregate evidence was not inserted");
        }
    }

    protected void insertEvent(PlanAggregateEvent event) {
        int inserted = jdbc.update(
            """
            insert into ap_process_migration_plan_aggregate_event (
              tenant_id,event_id,aggregate_id,plan_id,intent_id,aggregate_revision,
              status,terminal_outcome,pause_reason,predecessor_hash,aggregate_hash,
              event_hash,happened_at,request_id,trace_id,audit_reference,payload_json
            ) values (
              :tenantId,:eventId,:aggregateId,:planId,:intentId,:revision,
              :status,:terminalOutcome,:pauseReason,:predecessor,:aggregateHash,
              :eventHash,:happenedAt,:requestId,:traceId,:auditReference,:payload
            )
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", event.tenantId())
                .addValue("eventId", values.bindUuid(event.eventId()))
                .addValue("aggregateId", values.bindUuid(event.aggregateId()))
                .addValue("planId", values.bindUuid(event.planId()))
                .addValue("intentId", values.bindUuid(event.intentId()))
                .addValue("revision", event.aggregateRevision())
                .addValue("status", event.status().name())
                .addValue("terminalOutcome", event.terminalOutcome().name())
                .addValue("pauseReason", event.pauseReason().name())
                .addValue("predecessor", event.predecessorHash())
                .addValue("aggregateHash", event.aggregateHash())
                .addValue("eventHash", event.eventHash())
                .addValue(
                    "happenedAt",
                    values.bindInstant(event.happenedAt())
                )
                .addValue("requestId", event.requestId())
                .addValue("traceId", event.traceId())
                .addValue("auditReference", event.auditReference())
                .addValue("payload", json.write(event))
        );
        if (inserted != 1) {
            throw conflict("plan aggregate event evidence was not inserted");
        }
    }

    protected void insertCompletion(PlanCompletion completion) {
        StateCounts counts = completion.counts();
        int inserted = jdbc.update(
            """
            insert into ap_process_migration_plan_completion (
              tenant_id,completion_id,plan_id,intent_id,aggregate_id,aggregate_revision,
              completion_status,terminal_outcome,selected_count,terminal_failed_count,
              exact_success_count,input_evidence_hash,aggregate_hash,completion_evidence_hash,
              completed_at,request_id,trace_id,audit_reference,payload_json
            ) values (
              :tenantId,:completionId,:planId,:intentId,:aggregateId,:revision,
              :status,:terminalOutcome,:selected,:terminalFailed,:exactSuccess,
              :inputHash,:aggregateHash,:completionHash,:completedAt,:requestId,:traceId,
              :auditReference,:payload
            )
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", completion.tenantId())
                .addValue(
                    "completionId",
                    values.bindUuid(completion.completionId())
                )
                .addValue("planId", values.bindUuid(completion.planId()))
                .addValue("intentId", values.bindUuid(completion.intentId()))
                .addValue("aggregateId", values.bindUuid(completion.aggregateId()))
                .addValue("revision", completion.aggregateRevision())
                .addValue("status", completion.completionStatus().name())
                .addValue("terminalOutcome", completion.terminalOutcome().name())
                .addValue("selected", counts.selectedCount())
                .addValue("terminalFailed", counts.terminalFailedCount())
                .addValue("exactSuccess", counts.exactSuccessCount())
                .addValue("inputHash", completion.inputEvidenceHash())
                .addValue("aggregateHash", completion.aggregateHash())
                .addValue(
                    "completionHash",
                    completion.completionEvidenceHash()
                )
                .addValue(
                    "completedAt",
                    values.bindInstant(completion.completedAt())
                )
                .addValue("requestId", completion.requestId())
                .addValue("traceId", completion.traceId())
                .addValue("auditReference", completion.auditReference())
                .addValue("payload", json.write(completion))
        );
        if (inserted != 1) {
            throw conflict("plan completion evidence was not inserted");
        }
    }

    protected void appendAudit(
        UUID auditEventId,
        PlanAggregate aggregate,
        boolean completed
    ) {
        auditEvents.append(new AuditEvent(
            auditEventId,
            aggregate.tenantId(),
            aggregate.operatorId(),
            completed
                ? "PROCESS_MIGRATION_PLAN_COMPLETION_RECORDED"
                : "PROCESS_MIGRATION_PLAN_AGGREGATED",
            "APPROVAl_MIGRATION_PLAN",
            aggregate.planId().toString(),
            aggregate.requestId(),
            aggregate.traceId(),
            aggregate.aggregatedAt(),
            Map.ofEntries(
                Map.entry(
                    "aggregateRevision",
                    Long.toString(aggregate.aggregateRevision())
                ),
                Map.entry("aggregateStatus", aggregate.status().name()),
                Map.entry("terminalOutcome", aggregate.terminalOutcome().name()),
                Map.entry(
                    "selectedCount",
                    Integer.toString(aggregate.counts().selectedCount())
                ),
                Map.entry(
                    "exactSuccessCount",
                    Integer.toString(aggregate.counts().exactSuccessCount())
                ),
                Map.entry(
                    "terminalFailedCount",
                    Integer.toString(aggregate.counts().terminalFailedCount())
                ),
                Map.entry(
                    "unresolvedCount",
                    Integer.toString(aggregate.counts().unresolvedCount())
                ),
                Map.entry("pauseReason", aggregate.pauseReason().name()),
                Map.entry(
                    "reasonHash",
                    hashValues("M5-D8-BOUNDED-REASON-V1", aggregate.reason())
                ),
                Map.entry("aggregateHash", aggregate.aggregateHash())
            )
        ));
    }

    protected String requestHash(AggregationRequest request) {
        return hashValues(
            "M5-D8-PLAN-AGGREGATION-REQUEST-V1",
            request.tenantId(),
            request.operatorId(),
            request.idempotencyKey(),
            request.planId(),
            request.expectedAggregateRevision(),
            request.reason(),
            request.requestId(),
            request.traceId()
        );
    }
}
