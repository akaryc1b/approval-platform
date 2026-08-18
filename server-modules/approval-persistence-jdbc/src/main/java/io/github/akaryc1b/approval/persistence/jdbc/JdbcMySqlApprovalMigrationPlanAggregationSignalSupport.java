package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.CanaryStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.OrchestrationStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PauseReason;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanSignals;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

abstract class JdbcMySqlApprovalMigrationPlanAggregationSignalSupport
    extends JdbcMySqlApprovalMigrationPlanAggregationPlanReadSupport {

    JdbcMySqlApprovalMigrationPlanAggregationSignalSupport(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager,
        AuditEventSink auditEvents,
        Supplier<UUID> identifiers
    ) {
        super(dataSource, objectMapper, transactionManager, auditEvents, identifiers);
    }

    protected PlanSignals loadSignals(PlanContext plan) {
        Optional<CanarySignal> canary = queryOne(
            """
            select selection_id,selection_evidence_hash
            from ap_process_migration_canary_selection
            where tenant_id=:tenantId and plan_id=:planId
            """,
            params("tenantId", plan.tenantId(), "planId", plan.planId()),
            (row, number) -> new CanarySignal(
                values.uuid(row, "selection_id"),
                row.getString("selection_evidence_hash")
            ),
            "multiple deterministic canary selections were found"
        );
        Optional<RunSignal> run = queryLatest(
            """
            select run_id,phase,run_evidence_hash
            from ap_process_migration_orchestration_run
            where tenant_id=:tenantId and intent_id=:intentId
            order by run_revision desc,run_id desc limit 1
            """,
            params("tenantId", plan.tenantId(), "intentId", plan.intentId()),
            (row, number) -> new RunSignal(
                values.uuid(row, "run_id"),
                row.getString("phase"),
                row.getString("run_evidence_hash")
            )
        );
        Optional<EventSignal> event = run.flatMap(value -> queryLatest(
            """
            select event_type,pause_reason,event_evidence_hash
            from ap_process_migration_orchestration_event
            where tenant_id=:tenantId and run_id=:runId
            order by sequence desc,event_id desc limit 1
            """,
            params(
                "tenantId", plan.tenantId(),
                "runId", value.runId()
            ),
            (row, number) -> new EventSignal(
                row.getString("event_type"),
                row.getString("pause_reason"),
                row.getString("event_evidence_hash")
            )
        ));
        Optional<String> batchHash = run.flatMap(value -> queryOne(
            """
            select batch_evidence_hash
            from ap_process_migration_orchestration_batch
            where tenant_id=:tenantId and run_id=:runId
            """,
            params("tenantId", plan.tenantId(), "runId", value.runId()),
            (row, number) -> row.getString("batch_evidence_hash"),
            "multiple orchestration batches were found"
        ));
        Optional<ObservationSignal> observation = run.flatMap(value -> queryLatest(
            """
            select switch_enabled,dispatch_allowed,reason_code,
              observation_evidence_hash
            from ap_process_migration_kill_switch_observation
            where tenant_id=:tenantId and run_id=:runId
            order by observed_at desc,observation_id desc limit 1
            """,
            params("tenantId", plan.tenantId(), "runId", value.runId()),
            (row, number) -> new ObservationSignal(
                nullableBoolean(row, "switch_enabled"),
                nullableBoolean(row, "dispatch_allowed"),
                row.getString("reason_code"),
                row.getString("observation_evidence_hash")
            )
        ));

        SignalRow row = new SignalRow(
            canary.map(CanarySignal::selectionId).orElse(null),
            canary.map(CanarySignal::selectionHash).orElse(null),
            run.map(RunSignal::runId).orElse(null),
            run.map(RunSignal::phase).orElse(null),
            run.map(RunSignal::runHash).orElse(null),
            event.map(EventSignal::eventType).orElse(null),
            event.map(EventSignal::pauseReason).orElse(null),
            event.map(EventSignal::eventHash).orElse(null),
            batchHash.orElse(null),
            observation.map(ObservationSignal::switchEnabled).orElse(null),
            observation.map(ObservationSignal::dispatchAllowed).orElse(null),
            observation.map(ObservationSignal::reasonCode).orElse(null),
            observation.map(ObservationSignal::observationHash).orElse(null)
        );

        boolean selected = row.selectionId() != null;
        boolean runPresent = row.runId() != null;
        boolean eventPresent = row.eventType() != null;
        boolean incomplete = selected != runPresent || runPresent != eventPresent;
        boolean active = "PREPARED".equals(row.eventType())
            || "DISPATCH_ALLOWED".equals(row.eventType());
        boolean paused = "PAUSED".equals(row.eventType())
            || "KILL_SWITCH_BLOCKED".equals(row.eventType());
        boolean completed = "COMPLETED".equals(row.eventType())
            || "CANARY_COMPLETED".equals(row.eventType())
            || "BATCH_RECORDED".equals(row.eventType());

        CanaryStatus canaryStatus;
        if (incomplete) {
            canaryStatus = CanaryStatus.INVALID;
        } else if (!selected) {
            canaryStatus = CanaryStatus.NOT_SELECTED;
        } else if (!runPresent) {
            canaryStatus = CanaryStatus.PENDING;
        } else if ("CANARY".equals(row.phase()) && active) {
            canaryStatus = CanaryStatus.IN_PROGRESS;
        } else if ("CANARY".equals(row.phase()) && paused) {
            canaryStatus = CanaryStatus.PAUSED;
        } else if (completed || "BOUNDED".equals(row.phase())) {
            canaryStatus = CanaryStatus.COMPLETED;
        } else {
            canaryStatus = CanaryStatus.PENDING;
        }

        OrchestrationStatus orchestrationStatus;
        if (incomplete) {
            orchestrationStatus = OrchestrationStatus.INVALID;
        } else if (!runPresent) {
            orchestrationStatus = OrchestrationStatus.NOT_STARTED;
        } else if (paused) {
            orchestrationStatus = OrchestrationStatus.PAUSED;
        } else if ("CANARY".equals(row.phase()) && active) {
            orchestrationStatus = OrchestrationStatus.CANARY_IN_PROGRESS;
        } else if ("BOUNDED".equals(row.phase()) && active) {
            orchestrationStatus = OrchestrationStatus.BOUNDED_IN_PROGRESS;
        } else if (completed) {
            orchestrationStatus = OrchestrationStatus.COMPLETED;
        } else {
            orchestrationStatus = OrchestrationStatus.NOT_STARTED;
        }

        PauseReason reason = incomplete
            ? PauseReason.INCOMPLETE_EVIDENCE
            : pauseReason(row.pauseReason());
        boolean killSwitchObserved = row.observationHash() != null;
        if (reason == PauseReason.KILL_SWITCH && !killSwitchObserved) {
            incomplete = true;
            reason = PauseReason.INCOMPLETE_EVIDENCE;
        }
        String evidenceHash = selected || runPresent || eventPresent || killSwitchObserved
            ? hashValues(
                "M5-D8-PLAN-SIGNALS-V1",
                row.selectionId(),
                row.selectionHash(),
                row.runId(),
                row.phase(),
                row.runHash(),
                row.eventType(),
                row.pauseReason(),
                row.eventHash(),
                row.batchHash(),
                row.switchEnabled(),
                row.dispatchAllowed(),
                row.killReasonCode(),
                row.observationHash()
            )
            : ZERO_HASH;
        return new PlanSignals(
            canaryStatus,
            orchestrationStatus,
            reason,
            killSwitchObserved,
            incomplete,
            evidenceHash
        );
    }
}
