package io.github.akaryc1b.approval.persistence.jdbc;

import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery.DiagnosticInstanceItem;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery.DiagnosticInstancePage;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery.FailureClass;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery.InstanceCriteria;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery.InstanceDiagnostics;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery.PlanDiagnostics;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery.ReconciliationState;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationDiagnosticsQuery.TimelineEvent;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Read-only M5-E2 diagnostics over platform-owned migration evidence. */
public final class JdbcApprovalMigrationDiagnosticsQuery
    implements ApprovalMigrationDiagnosticsQuery {

    private static final Set<String> PLAN_STATUSES = Set.of(
        "PROPOSED",
        "AUTHORIZED",
        "EXPIRED",
        "CANCELLED",
        "CONSUMED"
    );
    private static final Set<String> INTENT_STATUSES = Set.of(
        "AUTHORIZED",
        "RUNNING",
        "COMPLETED",
        "FAILED",
        "CANCELLED"
    );
    private static final Set<String> AGGREGATE_STATUSES = Set.of(
        "NOT_STARTED",
        "CANARY_PENDING",
        "CANARY_IN_PROGRESS",
        "BOUNDED_EXECUTION_IN_PROGRESS",
        "PAUSED",
        "UNRESOLVED",
        "TERMINAL_FAILURE_PRESENT",
        "PARTIALLY_COMPLETED",
        "COMPLETED_SUCCEEDED",
        "COMPLETED_WITH_TERMINAL_FAILURE",
        "INVALID_OR_INCOMPLETE_EVIDENCE"
    );
    private static final Set<String> CANARY_STATUSES = Set.of(
        "NOT_SELECTED",
        "PENDING",
        "IN_PROGRESS",
        "COMPLETED",
        "PAUSED",
        "INVALID"
    );
    private static final Set<String> ORCHESTRATION_STATUSES = Set.of(
        "NOT_STARTED",
        "CANARY_IN_PROGRESS",
        "BOUNDED_IN_PROGRESS",
        "PAUSED",
        "COMPLETED",
        "INVALID"
    );

    private static final String PLAN_DIAGNOSTICS_SQL = """
        select plan.plan_id,plan.status plan_status,consumption.intent_id,
          intent.status intent_status,plan.selected_instance_count,
          coalesce(latest.provisioned_attempt_count,0) provisioned_attempt_count,
          coalesce(latest.pending_count,0) pending_count,
          coalesce(latest.claimed_count,0) claimed_count,
          coalesce(latest.engine_requested_count,0) engine_requested_count,
          coalesce(latest.verifying_count,0) verifying_count,
          coalesce(latest.reconciling_count,0) reconciling_count,
          coalesce(latest.unknown_count,0) unknown_count,
          coalesce(ambiguous.ambiguous_unknown_count,0) ambiguous_unknown_count,
          coalesce(latest.manual_review_count,0) manual_review_count,
          coalesce(latest.binding_conflict_count,0) binding_conflict_count,
          coalesce(latest.blocked_stale_count,0) blocked_stale_count,
          coalesce(latest.terminal_failed_count,0) terminal_failed_count,
          coalesce(latest.exact_success_count,0) exact_success_count,
          coalesce(latest.unresolved_count,plan.selected_instance_count) unresolved_count,
          latest.aggregate_revision,coalesce(latest.status,'NOT_STARTED') aggregate_status,
          coalesce(latest.canary_status,'NOT_SELECTED') canary_status,
          coalesce(latest.orchestration_status,'NOT_STARTED') orchestration_status,
          canary.approval_instance_id canary_instance_id,
          canary.recorded_at canary_recorded_at,
          run.run_revision orchestration_run_revision,run.phase orchestration_phase,
          run.requested_limit orchestration_requested_limit,
          case when batch.attempt_ids is null then null
            else jsonb_array_length(batch.attempt_ids) end orchestration_batch_attempt_count,
          event.event_type latest_orchestration_event,
          event.pause_reason orchestration_pause_reason,
          run.started_at orchestration_started_at,
          event.happened_at latest_orchestration_event_at,
          case when kill.observation_id is null then 'NOT_OBSERVED'
            when kill.switch_enabled then 'ACTIVE'
            when kill.expected_revision<>kill.observed_revision then 'STALE_REVISION'
            else 'INACTIVE' end kill_switch_status,
          kill.expected_revision kill_switch_expected_revision,
          kill.observed_revision kill_switch_observed_revision,
          kill.dispatch_allowed,kill.observed_at kill_switch_observed_at,
          latest.aggregate_hash,latest.aggregated_at,
          completion.completion_status,completion.completion_evidence_hash,
          completion.completed_at
        from ap_process_migration_plan plan
        left join ap_process_migration_plan_consumption consumption
          on consumption.tenant_id=plan.tenant_id and consumption.plan_id=plan.plan_id
        left join ap_process_migration_intent intent
          on intent.tenant_id=consumption.tenant_id
         and intent.intent_id=consumption.intent_id
        left join lateral (
          select value.* from ap_process_migration_plan_aggregate value
          where value.tenant_id=plan.tenant_id and value.plan_id=plan.plan_id
          order by value.aggregate_revision desc,value.aggregate_id desc limit 1
        ) latest on true
        left join ap_process_migration_canary_selection canary
          on canary.tenant_id=plan.tenant_id and canary.plan_id=plan.plan_id
        left join lateral (
          select value.* from ap_process_migration_orchestration_run value
          where value.tenant_id=plan.tenant_id and value.plan_id=plan.plan_id
          order by value.run_revision desc,value.run_id desc limit 1
        ) run on true
        left join lateral (
          select value.* from ap_process_migration_orchestration_event value
          where value.tenant_id=run.tenant_id and value.run_id=run.run_id
          order by value.sequence desc,value.event_id desc limit 1
        ) event on true
        left join ap_process_migration_orchestration_batch batch
          on batch.tenant_id=run.tenant_id and batch.run_id=run.run_id
        left join lateral (
          select value.* from ap_process_migration_kill_switch_observation value
          where value.tenant_id=run.tenant_id and value.run_id=run.run_id
          order by value.observed_at desc,value.observation_id desc limit 1
        ) kill on true
        left join ap_process_migration_plan_completion completion
          on completion.tenant_id=plan.tenant_id and completion.plan_id=plan.plan_id
        left join lateral (
          select count(*) ambiguous_unknown_count
          from ap_process_migration_attempt attempt_value
          join ap_process_migration_engine_outcome outcome_value
            on outcome_value.tenant_id=attempt_value.tenant_id
           and outcome_value.attempt_id=attempt_value.attempt_id
          where attempt_value.tenant_id=plan.tenant_id
            and attempt_value.intent_id=consumption.intent_id
            and outcome_value.disposition='AMBIGUOUS_UNKNOWN'
        ) ambiguous on true
        where plan.tenant_id=:tenantId and plan.plan_id=:planId
        """;

    private static final String INSTANCE_SELECT = """
        select selection.sequence_no,selection.approval_instance_id,
          selection.instance_evidence_hash,plan.created_at selected_at,
          (canary.approval_instance_id is not null) canary,
          attempt.attempt_id,attempt.attempt_number,
          coalesce(attempt.status,'UNPROVISIONED') attempt_status,
          attempt.revision attempt_revision,attempt.engine_outcome,
          attempt.lease_owner attempt_lease_owner,attempt.lease_until attempt_lease_until,
          attempt.created_at attempt_created_at,attempt.updated_at attempt_updated_at,
          request.evidence_hash engine_request_evidence_hash,
          request.requested_at engine_requested_at,
          outcome.disposition engine_disposition,outcome.stable_code engine_stable_code,
          outcome.outcome_hash engine_outcome_hash,outcome.recorded_at engine_outcome_at,
          verification.classification verification_classification,
          verification.read_succeeded verification_read_succeeded,
          verification.truncated verification_truncated,
          verification.verification_evidence_hash,verification.recorded_at verification_at,
          reconciliation.status raw_reconciliation_status,
          case when reconciliation.status is null then 'NONE'
            when reconciliation.status in (
              'OPEN','RESOLVED_SOURCE','RESOLVED_TERMINAL','MANUAL_REVIEW_REQUIRED'
            ) then reconciliation.status
            else 'MANUAL_REVIEW_REQUIRED' end reconciliation_state,
          observation.disposition reconciliation_disposition,
          coalesce(reconciliation.resolution_evidence_hash,reconciliation.evidence_hash)
            reconciliation_evidence_hash,
          coalesce(reconciliation.resolved_at,reconciliation.recorded_at)
            reconciliation_at,
          observation.evidence_hash reconciliation_observation_hash,
          observation.recorded_at reconciliation_observation_at,
          fence.status fence_status,fence.revision fence_revision,
          fence.lease_owner fence_lease_owner,fence.lease_until fence_lease_until,
          conflict.conflict_evidence_hash,conflict.recorded_at conflict_at,
          binding.binding_revision,binding.binding_evidence_hash,
          binding.recorded_at binding_at,
          completion.completion_evidence_hash,completion.completed_at,
          case when completion.completion_evidence_hash is not null then 'APPLIED'
            when conflict.conflict_evidence_hash is not null then 'CONFLICT'
            else 'NOT_RECORDED' end binding_result,
          case
            when conflict.conflict_evidence_hash is not null then 'BINDING_CONFLICT'
            when attempt.status='BLOCKED_STALE' then 'STALE_AUTHORITY'
            when attempt.status='FAILED_TERMINAL' then 'TERMINAL_FAILURE'
            when attempt.status='FAILED_RETRYABLE' then 'RETRYABLE_FAILURE'
            when outcome.disposition='AMBIGUOUS_UNKNOWN' then 'AMBIGUOUS_UNKNOWN'
            when outcome.disposition='ENGINE_REJECTED' then 'ENGINE_REJECTED'
            when outcome.disposition='PRE_DISPATCH_REJECTED'
              then 'PRE_DISPATCH_REJECTED'
            when attempt.engine_outcome='VERIFICATION_MISMATCH'
              then 'VERIFICATION_MISMATCH'
            when attempt.attempt_id is null or attempt.status in (
              'PENDING','CLAIMED','ENGINE_REQUESTED','VERIFYING','UNKNOWN',
              'RECONCILING','SUCCEEDED','CANCELLED'
            ) then 'NONE'
            else 'UNCLASSIFIED' end failure_class,
          greatest(
            plan.created_at,
            coalesce(attempt.updated_at,plan.created_at),
            coalesce(request.requested_at,plan.created_at),
            coalesce(outcome.recorded_at,plan.created_at),
            coalesce(verification.recorded_at,plan.created_at),
            coalesce(reconciliation.recorded_at,plan.created_at),
            coalesce(reconciliation.resolved_at,plan.created_at),
            coalesce(observation.recorded_at,plan.created_at),
            coalesce(conflict.recorded_at,plan.created_at),
            coalesce(binding.recorded_at,plan.created_at),
            coalesce(completion.completed_at,plan.created_at)
          ) latest_evidence_at
        from ap_process_migration_plan_instance selection
        join ap_process_migration_plan plan
          on plan.tenant_id=selection.tenant_id and plan.plan_id=selection.plan_id
        left join ap_process_migration_plan_consumption consumption
          on consumption.tenant_id=plan.tenant_id and consumption.plan_id=plan.plan_id
        left join ap_process_migration_canary_selection canary
          on canary.tenant_id=selection.tenant_id and canary.plan_id=selection.plan_id
         and canary.approval_instance_id=selection.approval_instance_id
        left join lateral (
          select value.* from ap_process_migration_attempt value
          where value.tenant_id=selection.tenant_id
            and value.intent_id=consumption.intent_id
            and value.approval_instance_id=selection.approval_instance_id
          order by value.attempt_number desc,value.attempt_id desc limit 1
        ) attempt on true
        left join ap_process_migration_engine_request request
          on request.tenant_id=attempt.tenant_id and request.attempt_id=attempt.attempt_id
        left join ap_process_migration_engine_outcome outcome
          on outcome.tenant_id=attempt.tenant_id and outcome.attempt_id=attempt.attempt_id
        left join ap_process_migration_exact_verification verification
          on verification.tenant_id=attempt.tenant_id
         and verification.attempt_id=attempt.attempt_id
        left join lateral (
          select value.* from ap_process_migration_reconciliation value
          where value.tenant_id=attempt.tenant_id and value.attempt_id=attempt.attempt_id
          order by value.sequence desc,value.reconciliation_id desc limit 1
        ) reconciliation on true
        left join ap_process_migration_reconciliation_observation observation
          on observation.tenant_id=attempt.tenant_id
         and observation.attempt_id=attempt.attempt_id
        left join ap_approval_instance_command_fence fence
          on fence.tenant_id=attempt.tenant_id and fence.attempt_id=attempt.attempt_id
        left join ap_process_migration_binding_cas_conflict conflict
          on conflict.tenant_id=attempt.tenant_id and conflict.attempt_id=attempt.attempt_id
        left join lateral (
          select value.binding_revision,value.binding_evidence_hash,value.recorded_at
          from ap_process_runtime_binding_evidence value
          where value.tenant_id=attempt.tenant_id and value.attempt_id=attempt.attempt_id
          order by value.binding_revision desc,value.binding_evidence_id desc limit 1
        ) binding on true
        left join ap_process_migration_instance_completion completion
          on completion.tenant_id=attempt.tenant_id
         and completion.attempt_id=attempt.attempt_id
        where selection.tenant_id=:tenantId and selection.plan_id=:planId
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public JdbcApprovalMigrationDiagnosticsQuery(DataSource dataSource, Clock clock) {
        jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public Optional<PlanDiagnostics> findPlanDiagnostics(String tenantId, UUID planId) {
        String tenant = requireTenant(tenantId);
        Objects.requireNonNull(planId, "planId must not be null");
        return jdbc.query(
            PLAN_DIAGNOSTICS_SQL,
            params("tenantId", tenant, "planId", planId),
            (row, number) -> planDiagnostics(row)
        ).stream().findFirst();
    }

    @Override
    public DiagnosticInstancePage findInstances(InstanceCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        requirePlan(criteria.tenantId(), criteria.planId());
        MapSqlParameterSource parameters = params(
            "tenantId",
            criteria.tenantId(),
            "planId",
            criteria.planId()
        );
        String filters = filters(criteria, parameters);
        Long count = jdbc.queryForObject(
            "select count(*) from (" + INSTANCE_SELECT + ") diagnostic" + filters,
            parameters,
            Long.class
        );
        long total = count == null ? 0 : count;
        parameters.addValue("limit", criteria.pageSize());
        parameters.addValue("offset", criteria.offset());
        List<DiagnosticInstanceItem> items = jdbc.query(
            "select * from (" + INSTANCE_SELECT + ") diagnostic"
                + filters + ordering(criteria) + " limit :limit offset :offset",
            parameters,
            (row, number) -> diagnosticRow(row).item()
        );
        int totalPages = total == 0 ? 0 : (int) ((total - 1) / criteria.pageSize()) + 1;
        return new DiagnosticInstancePage(
            criteria.planId(),
            items,
            total,
            criteria.page(),
            criteria.pageSize(),
            totalPages,
            criteria.page() < totalPages
        );
    }

    @Override
    public Optional<InstanceDiagnostics> findInstance(
        String tenantId,
        UUID planId,
        UUID approvalInstanceId
    ) {
        String tenant = requireTenant(tenantId);
        Objects.requireNonNull(planId, "planId must not be null");
        Objects.requireNonNull(approvalInstanceId, "approvalInstanceId must not be null");
        requirePlan(tenant, planId);
        return jdbc.query(
            "select * from (" + INSTANCE_SELECT + ") diagnostic "
                + "where approval_instance_id=:approvalInstanceId",
            params(
                "tenantId",
                tenant,
                "planId",
                planId,
                "approvalInstanceId",
                approvalInstanceId
            ),
            (row, number) -> diagnosticRow(row)
        ).stream().findFirst().map(row -> new InstanceDiagnostics(
            row.item(),
            timeline(row),
            clock.instant()
        ));
    }

    private void requirePlan(String tenantId, UUID planId) {
        Integer count = jdbc.queryForObject(
            "select count(*) from ap_process_migration_plan "
                + "where tenant_id=:tenantId and plan_id=:planId",
            params("tenantId", requireTenant(tenantId), "planId", planId),
            Integer.class
        );
        if (count == null || count != 1) {
            throw new JdbcApprovalMigrationOperationsQuery.MigrationOperationsNotFoundException(
                "migration plan was not found"
            );
        }
    }

    private static String filters(
        InstanceCriteria criteria,
        MapSqlParameterSource parameters
    ) {
        StringBuilder where = new StringBuilder(" where 1=1");
        if (criteria.approvalInstanceId() != null) {
            where.append(" and approval_instance_id=:approvalInstanceId");
            parameters.addValue("approvalInstanceId", criteria.approvalInstanceId());
        }
        if (criteria.attemptStatus() != null) {
            where.append(" and attempt_status=:attemptStatus");
            parameters.addValue("attemptStatus", criteria.attemptStatus().name());
        }
        if (criteria.failureClass() != null) {
            where.append(" and failure_class=:failureClass");
            parameters.addValue("failureClass", criteria.failureClass().name());
        }
        if (criteria.reconciliationState() != null) {
            where.append(" and reconciliation_state=:reconciliationState");
            parameters.addValue("reconciliationState", criteria.reconciliationState().name());
        }
        if (criteria.from() != null) {
            where.append(" and latest_evidence_at>=:fromTime");
            parameters.addValue("fromTime", criteria.from());
        }
        if (criteria.to() != null) {
            where.append(" and latest_evidence_at<=:toTime");
            parameters.addValue("toTime", criteria.to());
        }
        return where.toString();
    }

    private static String ordering(InstanceCriteria criteria) {
        return switch (criteria.sort()) {
            case SEQUENCE_ASC -> " order by sequence_no asc,approval_instance_id asc";
            case LATEST_EVIDENCE_ASC ->
                " order by latest_evidence_at asc,sequence_no asc,approval_instance_id asc";
            case LATEST_EVIDENCE_DESC ->
                " order by latest_evidence_at desc,sequence_no asc,approval_instance_id asc";
        };
    }

    private PlanDiagnostics planDiagnostics(ResultSet row) throws SQLException {
        return new PlanDiagnostics(
            row.getObject("plan_id", UUID.class),
            closed(row.getString("plan_status"), PLAN_STATUSES),
            row.getObject("intent_id", UUID.class),
            closedOptional(row.getString("intent_status"), INTENT_STATUSES),
            row.getInt("selected_instance_count"),
            row.getInt("provisioned_attempt_count"),
            row.getInt("pending_count"),
            row.getInt("claimed_count"),
            row.getInt("engine_requested_count"),
            row.getInt("verifying_count"),
            row.getInt("reconciling_count"),
            row.getInt("unknown_count"),
            row.getLong("ambiguous_unknown_count"),
            row.getInt("manual_review_count"),
            row.getInt("binding_conflict_count"),
            row.getInt("blocked_stale_count"),
            row.getInt("terminal_failed_count"),
            row.getInt("exact_success_count"),
            row.getInt("unresolved_count"),
            nullableLong(row, "aggregate_revision"),
            closed(row.getString("aggregate_status"), AGGREGATE_STATUSES),
            closed(row.getString("canary_status"), CANARY_STATUSES),
            closed(row.getString("orchestration_status"), ORCHESTRATION_STATUSES),
            row.getObject("canary_instance_id", UUID.class),
            instant(row, "canary_recorded_at"),
            nullableLong(row, "orchestration_run_revision"),
            safeOptional(row.getString("orchestration_phase")),
            nullableInteger(row, "orchestration_requested_limit"),
            nullableInteger(row, "orchestration_batch_attempt_count"),
            safeOptional(row.getString("latest_orchestration_event")),
            safeOptional(row.getString("orchestration_pause_reason")),
            instant(row, "orchestration_started_at"),
            instant(row, "latest_orchestration_event_at"),
            safe(row.getString("kill_switch_status")),
            nullableLong(row, "kill_switch_expected_revision"),
            nullableLong(row, "kill_switch_observed_revision"),
            nullableBoolean(row, "dispatch_allowed"),
            instant(row, "kill_switch_observed_at"),
            safeHash(row.getString("aggregate_hash")),
            instant(row, "aggregated_at"),
            safeOptional(row.getString("completion_status")),
            safeHash(row.getString("completion_evidence_hash")),
            instant(row, "completed_at"),
            clock.instant()
        );
    }

    private static DiagnosticRow diagnosticRow(ResultSet row) throws SQLException {
        String completionHash = safeHash(row.getString("completion_evidence_hash"));
        String bindingHash = safeHash(row.getString("binding_evidence_hash"));
        String conflictHash = safeHash(row.getString("conflict_evidence_hash"));
        String reconciliationHash = safeHash(row.getString("reconciliation_evidence_hash"));
        String observationHash = safeHash(row.getString("reconciliation_observation_hash"));
        String verificationHash = safeHash(row.getString("verification_evidence_hash"));
        String outcomeHash = safeHash(row.getString("engine_outcome_hash"));
        String requestHash = safeHash(row.getString("engine_request_evidence_hash"));
        String selectionHash = safeHash(row.getString("instance_evidence_hash"));
        String latestHash = firstNonNull(
            completionHash,
            bindingHash,
            conflictHash,
            reconciliationHash,
            observationHash,
            verificationHash,
            outcomeHash,
            requestHash,
            selectionHash
        );
        DiagnosticInstanceItem item = new DiagnosticInstanceItem(
            row.getInt("sequence_no"),
            row.getObject("approval_instance_id", UUID.class),
            row.getBoolean("canary"),
            row.getObject("attempt_id", UUID.class),
            nullableInteger(row, "attempt_number"),
            safe(row.getString("attempt_status")),
            nullableLong(row, "attempt_revision"),
            safeOptional(row.getString("engine_disposition")),
            safeOptional(row.getString("engine_stable_code")),
            FailureClass.valueOf(row.getString("failure_class")),
            safeOptional(row.getString("verification_classification")),
            nullableBoolean(row, "verification_read_succeeded"),
            nullableBoolean(row, "verification_truncated"),
            verificationHash,
            instant(row, "verification_at"),
            ReconciliationState.valueOf(row.getString("reconciliation_state")),
            safeOptional(row.getString("reconciliation_disposition")),
            reconciliationHash,
            instant(row, "reconciliation_at"),
            safe(row.getString("attempt_status")),
            nullableLong(row, "attempt_revision"),
            ownerReference(row.getString("attempt_lease_owner")),
            instant(row, "attempt_lease_until"),
            safe(row.getString("fence_status")),
            nullableLong(row, "fence_revision"),
            ownerReference(row.getString("fence_lease_owner")),
            instant(row, "fence_lease_until"),
            safe(row.getString("binding_result")),
            nullableLong(row, "binding_revision"),
            bindingHash,
            completionHash,
            selectionHash,
            latestHash,
            instant(row, "latest_evidence_at")
        );
        return new DiagnosticRow(
            item,
            instant(row, "selected_at"),
            instant(row, "attempt_created_at"),
            requestHash,
            instant(row, "engine_requested_at"),
            outcomeHash,
            instant(row, "engine_outcome_at"),
            observationHash,
            instant(row, "reconciliation_observation_at"),
            conflictHash,
            instant(row, "conflict_at"),
            bindingHash,
            instant(row, "binding_at"),
            completionHash,
            instant(row, "completed_at")
        );
    }

    private static List<TimelineEvent> timeline(DiagnosticRow row) {
        List<TimelineEvent> events = new ArrayList<>();
        add(events, 1, "PLAN_SELECTION", "SELECTED", row.item().selectedInstanceEvidenceHash(),
            row.selectedAt());
        add(events, 2, "ATTEMPT", row.item().attemptStatus(), null, row.attemptCreatedAt());
        add(events, 3, "ENGINE_REQUEST", "RECORDED", row.engineRequestHash(),
            row.engineRequestedAt());
        add(events, 4, "ENGINE_OUTCOME", value(row.item().engineDisposition()),
            row.engineOutcomeHash(), row.engineOutcomeAt());
        add(events, 5, "EXACT_VERIFICATION", value(row.item().verificationClassification()),
            row.item().verificationEvidenceHash(), row.item().verificationAt());
        add(events, 6, "RECONCILIATION_OBSERVATION",
            value(row.item().reconciliationDisposition()), row.observationHash(),
            row.observationAt());
        add(events, 7, "RECONCILIATION", row.item().reconciliationState().name(),
            row.item().reconciliationEvidenceHash(), row.item().reconciliationAt());
        add(events, 8, "RUNTIME_BINDING_CAS", row.item().bindingResult(),
            firstNonNull(row.bindingHash(), row.conflictHash()),
            firstNonNull(row.bindingAt(), row.conflictAt()));
        add(events, 9, "COMPLETION", "EXACT_COMPLETION", row.completionHash(),
            row.completedAt());
        events.sort(Comparator.comparing(TimelineEvent::happenedAt)
            .thenComparingInt(TimelineEvent::order));
        return List.copyOf(events);
    }

    private static void add(
        List<TimelineEvent> events,
        int order,
        String stage,
        String state,
        String hash,
        Instant happenedAt
    ) {
        if (happenedAt != null) {
            events.add(new TimelineEvent(order, stage, state, hash, happenedAt));
        }
    }

    private static String value(String value) {
        return value == null ? "UNKNOWN" : value;
    }

    private static String requireTenant(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        String normalized = tenantId.trim();
        if (normalized.isEmpty() || normalized.length() > 128) {
            throw new IllegalArgumentException("tenantId is blank or exceeds maximum length 128");
        }
        return normalized;
    }

    private static String closed(String value, Set<String> allowed) {
        return value != null && allowed.contains(value) ? value : "UNKNOWN";
    }

    private static String closedOptional(String value, Set<String> allowed) {
        return value == null ? null : closed(value, allowed);
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : bounded(value, 96);
    }

    private static String safeOptional(String value) {
        return value == null || value.isBlank() ? null : bounded(value, 96);
    }

    private static String safeHash(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.matches("[0-9a-f]{64}") ? normalized : null;
    }

    private static String bounded(String value, int maximum) {
        String normalized = value.trim();
        return normalized.length() <= maximum
            ? normalized
            : normalized.substring(0, maximum);
    }

    private static String ownerReference(String owner) {
        if (owner == null || owner.isBlank()) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                owner.trim().getBytes(StandardCharsets.UTF_8)
            );
            return "sha256:" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
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

    private static Boolean nullableBoolean(ResultSet row, String column) throws SQLException {
        boolean value = row.getBoolean(column);
        return row.wasNull() ? null : value;
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

    private record DiagnosticRow(
        DiagnosticInstanceItem item,
        Instant selectedAt,
        Instant attemptCreatedAt,
        String engineRequestHash,
        Instant engineRequestedAt,
        String engineOutcomeHash,
        Instant engineOutcomeAt,
        String observationHash,
        Instant observationAt,
        String conflictHash,
        Instant conflictAt,
        String bindingHash,
        Instant bindingAt,
        String completionHash,
        Instant completedAt
    ) {
    }
}
