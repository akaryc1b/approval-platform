package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.AggregateStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.InstanceFact;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.InstanceStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanAggregate;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanAggregateEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanCompletion;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanSignals;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.Summary;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationRules;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** PostgreSQL D8 aggregation boundary. It never calls Flowable or mutates runtime binding. */
public final class JdbcApprovalMigrationPlanAggregationStore
    implements ApprovalMigrationPlanAggregationStore {

    private static final String ZERO_HASH = "0".repeat(64);

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcApprovalMigrationJson json;
    private final TransactionTemplate transactions;
    private final AuditEventSink auditEvents;
    private final Supplier<UUID> identifiers;

    public JdbcApprovalMigrationPlanAggregationStore(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager,
        AuditEventSink auditEvents,
        Supplier<UUID> identifiers
    ) {
        jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
        json = new JdbcApprovalMigrationJson(
            Objects.requireNonNull(objectMapper, "objectMapper must not be null")
        );
        transactions = new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.auditEvents = Objects.requireNonNull(auditEvents, "auditEvents must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
    }

    @Override
    public AggregationResult aggregate(AggregationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return execute("migration plan aggregation conflict", () -> aggregateOnce(request));
    }

    private AggregationResult aggregateOnce(AggregationRequest request) {
        String requestHash = requestHash(request);
        Optional<PlanAggregate> replay = findAggregateByRequestId(
            request.tenantId(),
            request.requestId()
        );
        if (replay.isPresent()) {
            PlanAggregate aggregate = replay.orElseThrow();
            requireReplay(aggregate, request, requestHash);
            return result(aggregate, true);
        }

        PlanContext plan = lockPlan(request.tenantId(), request.intentId());
        long currentRevision = currentAggregateRevision(plan.tenantId(), plan.intentId());
        if (request.expectedAggregateRevision() != currentRevision + 1) {
            throw conflict("plan aggregate revision is stale");
        }
        List<InstanceFact> facts = loadFacts(plan);
        if (facts.size() != plan.selectedCount()) {
            throw conflict("sealed plan selected count does not match canonical instances");
        }
        PlanSignals signals = loadSignals(plan);
        Summary summary = ApprovalMigrationPlanAggregationRules.summarize(facts, signals);
        String inputHash = inputEvidenceHash(plan, summary);
        String predecessor = currentRevision == 0
            ? ZERO_HASH
            : latestAggregateHash(plan.tenantId(), plan.intentId());
        String aggregateHash = sha256(join(
            "m5-d8-plan-aggregate-v1",
            plan.tenantId(),
            plan.planId(),
            plan.intentId(),
            request.expectedAggregateRevision(),
            summary.status(),
            summary.selectedCount(),
            summary.terminalCount(),
            summary.succeededCount(),
            summary.unresolvedCount(),
            inputHash,
            predecessor
        ));
        PlanAggregate aggregate = new PlanAggregate(
            nextIdentifier("planAggregateId"),
            plan.tenantId(),
            plan.planId(),
            plan.intentId(),
            request.expectedAggregateRevision(),
            summary.status(),
            summary.selectedCount(),
            summary.terminalCount(),
            summary.succeededCount(),
            summary.unresolvedCount(),
            inputHash,
            predecessor,
            requestHash,
            aggregateHash,
            request.happenedAt(),
            request.requestId(),
            request.traceId()
        );
        PlanAggregateEvent event = event(aggregate, request);
        PlanCompletion completion = completion(aggregate, request);
        insertAggregate(aggregate);
        insertEvent(event);
        if (completion != null) {
            insertCompletion(completion);
        }
        appendAudit(aggregate, completion != null);
        return new AggregationResult(aggregate, event, completion, false);
    }

    private PlanContext lockPlan(String tenantId, UUID intentId) {
        PlanContext plan = jdbc.query("""
            select plan.tenant_id,plan.plan_id,plan.plan_hash,plan.selected_instance_count,
              intent.intent_id,intent.intent_evidence_hash
            from ap_process_migration_intent intent
            join ap_process_migration_plan plan
              on plan.tenant_id=intent.tenant_id and plan.plan_id=intent.plan_id
             and plan.plan_hash=intent.plan_hash
            where intent.tenant_id=:tenantId and intent.intent_id=:intentId
              and plan.status='CONSUMED'
            for update of plan,intent
            """, params("tenantId", tenantId, "intentId", intentId),
            (row, number) -> new PlanContext(
                row.getString("tenant_id"),
                row.getObject("plan_id", UUID.class),
                row.getString("plan_hash"),
                row.getInt("selected_instance_count"),
                row.getObject("intent_id", UUID.class),
                row.getString("intent_evidence_hash")
            )).stream().findFirst().orElseThrow(() -> conflict(
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

    private List<InstanceFact> loadFacts(PlanContext plan) {
        return jdbc.query("""
            select selection.sequence_no,selection.approval_instance_id,
              selection.instance_evidence_hash,
              (canary.approval_instance_id is not null) canary,
              attempt.attempt_id,attempt.attempt_number,attempt.status attempt_status,
              attempt.revision attempt_revision,attempt.engine_outcome,
              attempt.failure_class,attempt.payload_json::text attempt_payload,
              coalesce(completion.completion_count,0) completion_count,
              completion.completion_attempt_id,completion.completion_hash,
              coalesce(conflict.conflict_count,0) conflict_count,
              conflict.conflict_hash,reconciliation.status reconciliation_status,
              coalesce(reconciliation.resolution_evidence_hash,
                reconciliation.evidence_hash) reconciliation_hash
            from ap_process_migration_plan_instance selection
            left join ap_process_migration_canary_selection canary
              on canary.tenant_id=selection.tenant_id and canary.plan_id=selection.plan_id
             and canary.approval_instance_id=selection.approval_instance_id
            left join lateral (
              select value.attempt_id,value.attempt_number,value.status,value.revision,
                value.engine_outcome,value.failure_class,value.payload_json
              from ap_process_migration_attempt value
              where value.tenant_id=selection.tenant_id
                and value.intent_id=:intentId
                and value.approval_instance_id=selection.approval_instance_id
              order by value.attempt_number desc,value.attempt_id desc limit 1
            ) attempt on true
            left join lateral (
              select count(*)::integer completion_count,
                (array_agg(value.attempt_id order by value.completed_at desc,
                  value.completion_id desc))[1] completion_attempt_id,
                (array_agg(value.completion_evidence_hash order by value.completed_at desc,
                  value.completion_id desc))[1] completion_hash
              from ap_process_migration_instance_completion value
              where value.tenant_id=selection.tenant_id
                and value.intent_id=:intentId
                and value.approval_instance_id=selection.approval_instance_id
            ) completion on true
            left join lateral (
              select count(*)::integer conflict_count,
                (array_agg(value.conflict_evidence_hash order by value.recorded_at desc,
                  value.conflict_id desc))[1] conflict_hash
              from ap_process_migration_binding_cas_conflict value
              where value.tenant_id=selection.tenant_id
                and value.intent_id=:intentId
                and value.approval_instance_id=selection.approval_instance_id
            ) conflict on true
            left join lateral (
              select value.status,value.evidence_hash,value.resolution_evidence_hash
              from ap_process_migration_reconciliation value
              where value.tenant_id=selection.tenant_id
                and value.attempt_id=attempt.attempt_id
              order by value.sequence desc,value.reconciliation_id desc limit 1
            ) reconciliation on true
            where selection.tenant_id=:tenantId and selection.plan_id=:planId
            order by selection.sequence_no
            """, params(
                "tenantId", plan.tenantId(),
                "planId", plan.planId(),
                "intentId", plan.intentId()
            ), (row, number) -> fact(new FactRow(
                row.getInt("sequence_no"),
                row.getObject("approval_instance_id", UUID.class),
                row.getBoolean("canary"),
                row.getString("instance_evidence_hash"),
                row.getObject("attempt_id", UUID.class),
                nullableInteger(row, "attempt_number"),
                row.getString("attempt_status"),
                nullableLong(row, "attempt_revision"),
                row.getString("engine_outcome"),
                row.getString("failure_class"),
                row.getString("attempt_payload"),
                row.getInt("completion_count"),
                row.getObject("completion_attempt_id", UUID.class),
                row.getString("completion_hash"),
                row.getInt("conflict_count"),
                row.getString("conflict_hash"),
                row.getString("reconciliation_status"),
                row.getString("reconciliation_hash")
            )));
    }

    private PlanSignals loadSignals(PlanContext plan) {
        SignalRow row = jdbc.query("""
            select canary.selection_id,canary.selection_evidence_hash,
              run.run_id,run.phase,run.run_evidence_hash,
              event.event_type,event.event_evidence_hash,
              batch.batch_evidence_hash,observation.observation_evidence_hash
            from (select 1) anchor
            left join lateral (
              select value.selection_id,value.selection_evidence_hash
              from ap_process_migration_canary_selection value
              where value.tenant_id=:tenantId and value.plan_id=:planId
              limit 1
            ) canary on true
            left join lateral (
              select value.run_id,value.phase,value.run_evidence_hash
              from ap_process_migration_orchestration_run value
              where value.tenant_id=:tenantId and value.intent_id=:intentId
              order by value.run_revision desc,value.run_id desc limit 1
            ) run on true
            left join lateral (
              select value.event_type,value.event_evidence_hash
              from ap_process_migration_orchestration_event value
              where value.tenant_id=:tenantId and value.run_id=run.run_id
              order by value.sequence desc,value.event_id desc limit 1
            ) event on true
            left join lateral (
              select value.batch_evidence_hash
              from ap_process_migration_orchestration_batch value
              where value.tenant_id=:tenantId and value.run_id=run.run_id
              limit 1
            ) batch on true
            left join lateral (
              select value.observation_evidence_hash
              from ap_process_migration_kill_switch_observation value
              where value.tenant_id=:tenantId and value.run_id=run.run_id
              order by value.observed_at desc,value.observation_id desc limit 1
            ) observation on true
            """, params(
                "tenantId", plan.tenantId(),
                "planId", plan.planId(),
                "intentId", plan.intentId()
            ), (result, number) -> new SignalRow(
                result.getObject("selection_id", UUID.class),
                result.getString("selection_evidence_hash"),
                result.getObject("run_id", UUID.class),
                result.getString("phase"),
                result.getString("run_evidence_hash"),
                result.getString("event_type"),
                result.getString("event_evidence_hash"),
                result.getString("batch_evidence_hash"),
                result.getString("observation_evidence_hash")
            )).stream().findFirst().orElseThrow(() -> conflict(
                "plan aggregation signals could not be read"
            ));
        boolean selected = row.selectionId() != null;
        boolean runPresent = row.runId() != null;
        boolean eventPresent = row.eventType() != null;
        boolean active = "PREPARED".equals(row.eventType())
            || "DISPATCH_ALLOWED".equals(row.eventType());
        boolean canaryRunning = active && "CANARY".equals(row.phase());
        boolean boundedRunning = active && "BOUNDED".equals(row.phase());
        boolean killBlocked = "KILL_SWITCH_BLOCKED".equals(row.eventType());
        boolean paused = killBlocked || "PAUSED".equals(row.eventType());
        boolean incomplete = selected != runPresent || runPresent != eventPresent;
        String evidenceHash = selected || runPresent || eventPresent
            ? sha256(join(
                "m5-d8-plan-signals-v1",
                row.selectionId(),
                row.selectionHash(),
                row.runId(),
                row.phase(),
                row.runHash(),
                row.eventType(),
                row.eventHash(),
                row.batchHash(),
                row.observationHash()
            ))
            : ZERO_HASH;
        return new PlanSignals(
            selected,
            canaryRunning,
            boundedRunning,
            paused,
            killBlocked,
            incomplete,
            evidenceHash
        );
    }

    private InstanceFact fact(FactRow row) {
        InstanceStatus status;
        if (row.conflictCount() > 0 || row.completionCount() > 1) {
            status = InstanceStatus.COMPLETION_CONFLICT;
        } else if (row.completionCount() == 1) {
            status = row.attemptId() != null
                && row.attemptId().equals(row.completionAttemptId())
                && "SUCCEEDED".equals(row.attemptStatus())
                ? InstanceStatus.EXACTLY_COMPLETED
                : InstanceStatus.COMPLETION_CONFLICT;
        } else if (row.attemptId() == null) {
            status = InstanceStatus.NOT_STARTED;
        } else {
            status = switch (row.attemptStatus()) {
                case "PENDING", "CLAIMED", "ENGINE_REQUESTED", "VERIFYING" ->
                    InstanceStatus.IN_FLIGHT;
                case "UNKNOWN" -> InstanceStatus.UNKNOWN;
                case "RECONCILING" -> manual(row.reconciliationStatus())
                    ? InstanceStatus.MANUAL_REVIEW_REQUIRED
                    : InstanceStatus.RECONCILING;
                case "BLOCKED_STALE" -> "RESOLVED_SOURCE".equals(row.reconciliationStatus())
                    ? InstanceStatus.MANUALLY_DISPOSED
                    : InstanceStatus.INVALID_INCOMPLETE_EVIDENCE;
                case "FAILED_RETRYABLE", "FAILED_TERMINAL", "CANCELLED" ->
                    InstanceStatus.TERMINAL_FAILURE;
                case "SUCCEEDED" -> InstanceStatus.INVALID_INCOMPLETE_EVIDENCE;
                default -> InstanceStatus.INVALID_INCOMPLETE_EVIDENCE;
            };
        }
        String evidenceHash = sha256(join(
            "m5-d8-instance-fact-v1",
            row.sequenceNo(),
            row.approvalInstanceId(),
            row.canary(),
            row.instanceEvidenceHash(),
            row.attemptId(),
            row.attemptNumber(),
            row.attemptStatus(),
            row.attemptRevision(),
            row.engineOutcome(),
            row.failureClass(),
            row.attemptPayload(),
            row.completionCount(),
            row.completionAttemptId(),
            row.completionHash(),
            row.conflictCount(),
            row.conflictHash(),
            row.reconciliationStatus(),
            row.reconciliationHash(),
            status
        ));
        return new InstanceFact(
            row.sequenceNo(),
            row.approvalInstanceId(),
            row.canary(),
            status,
            evidenceHash
        );
    }

    private String inputEvidenceHash(PlanContext plan, Summary summary) {
        StringBuilder canonical = new StringBuilder(join(
            "m5-d8-input-evidence-v1",
            plan.tenantId(),
            plan.planId(),
            plan.planHash(),
            plan.intentId(),
            plan.intentEvidenceHash(),
            plan.selectedCount(),
            summary.signals().canarySelected(),
            summary.signals().canaryRunning(),
            summary.signals().boundedRunning(),
            summary.signals().paused(),
            summary.signals().killSwitchBlocked(),
            summary.signals().incompleteEvidence(),
            summary.signals().evidenceHash()
        ));
        for (InstanceFact fact : summary.canonicalFacts()) {
            canonical.append('\u001e').append(join(
                fact.sequenceNo(),
                fact.approvalInstanceId(),
                fact.canary(),
                fact.status(),
                fact.evidenceHash()
            ));
        }
        return sha256(canonical.toString());
    }

    private PlanAggregateEvent event(PlanAggregate aggregate, AggregationRequest request) {
        String eventHash = sha256(join(
            "m5-d8-plan-aggregate-event-v1",
            aggregate.tenantId(),
            aggregate.planId(),
            aggregate.intentId(),
            aggregate.aggregateRevision(),
            aggregate.status(),
            aggregate.aggregateHash(),
            aggregate.predecessorHash()
        ));
        return new PlanAggregateEvent(
            nextIdentifier("planAggregateEventId"),
            aggregate.tenantId(),
            aggregate.aggregateId(),
            aggregate.planId(),
            aggregate.intentId(),
            aggregate.aggregateRevision(),
            aggregate.status(),
            aggregate.aggregateHash(),
            eventHash,
            request.happenedAt(),
            request.requestId(),
            request.traceId()
        );
    }

    private PlanCompletion completion(PlanAggregate aggregate, AggregationRequest request) {
        if (aggregate.status() != AggregateStatus.ALL_INSTANCES_EXACTLY_COMPLETED
            && aggregate.status() != AggregateStatus.COMPLETED_WITH_MANUAL_DISPOSITION) {
            return null;
        }
        String completionHash = sha256(join(
            "m5-d8-plan-completion-v1",
            aggregate.tenantId(),
            aggregate.planId(),
            aggregate.intentId(),
            aggregate.aggregateRevision(),
            aggregate.status(),
            aggregate.selectedCount(),
            aggregate.terminalCount(),
            aggregate.succeededCount(),
            aggregate.inputEvidenceHash(),
            aggregate.aggregateHash()
        ));
        return new PlanCompletion(
            nextIdentifier("planCompletionId"),
            aggregate.tenantId(),
            aggregate.planId(),
            aggregate.intentId(),
            aggregate.aggregateId(),
            aggregate.aggregateRevision(),
            aggregate.status(),
            aggregate.selectedCount(),
            aggregate.terminalCount(),
            aggregate.succeededCount(),
            aggregate.inputEvidenceHash(),
            aggregate.aggregateHash(),
            completionHash,
            request.happenedAt(),
            request.requestId(),
            request.traceId()
        );
    }

    private AggregationResult result(PlanAggregate aggregate, boolean replayed) {
        PlanAggregateEvent event = findEvent(aggregate.tenantId(), aggregate.aggregateId());
        PlanCompletion completion = findCompletion(
            aggregate.tenantId(),
            aggregate.aggregateId()
        ).orElse(null);
        return new AggregationResult(aggregate, event, completion, replayed);
    }

    private void requireReplay(
        PlanAggregate aggregate,
        AggregationRequest request,
        String requestHash
    ) {
        if (!aggregate.tenantId().equals(request.tenantId())
            || !aggregate.intentId().equals(request.intentId())
            || aggregate.aggregateRevision() != request.expectedAggregateRevision()
            || !aggregate.requestHash().equals(requestHash)) {
            throw conflict("changed plan aggregation replay is forbidden");
        }
    }

    private Optional<PlanAggregate> findAggregateByRequestId(String tenantId, String requestId) {
        return queryOne(
            "select payload_json::text from ap_process_migration_plan_aggregate "
                + "where tenant_id=:tenantId and request_id=:requestId",
            params("tenantId", tenantId, "requestId", requestId),
            PlanAggregate.class
        );
    }

    private PlanAggregateEvent findEvent(String tenantId, UUID aggregateId) {
        return queryOne(
            "select payload_json::text from ap_process_migration_plan_aggregate_event "
                + "where tenant_id=:tenantId and aggregate_id=:aggregateId",
            params("tenantId", tenantId, "aggregateId", aggregateId),
            PlanAggregateEvent.class
        ).orElseThrow(() -> conflict("plan aggregate event is missing"));
    }

    private Optional<PlanCompletion> findCompletion(String tenantId, UUID aggregateId) {
        return queryOne(
            "select payload_json::text from ap_process_migration_plan_completion "
                + "where tenant_id=:tenantId and aggregate_id=:aggregateId",
            params("tenantId", tenantId, "aggregateId", aggregateId),
            PlanCompletion.class
        );
    }

    private <T> Optional<T> queryOne(
        String sql,
        MapSqlParameterSource parameters,
        Class<T> type
    ) {
        return jdbc.query(sql, parameters, (row, number) -> json.read(row.getString(1), type))
            .stream().findFirst();
    }

    private long currentAggregateRevision(String tenantId, UUID intentId) {
        Long value = jdbc.queryForObject(
            "select coalesce(max(aggregate_revision),0) "
                + "from ap_process_migration_plan_aggregate "
                + "where tenant_id=:tenantId and intent_id=:intentId",
            params("tenantId", tenantId, "intentId", intentId),
            Long.class
        );
        return value == null ? 0 : value;
    }

    private String latestAggregateHash(String tenantId, UUID intentId) {
        return jdbc.query(
            "select aggregate_hash from ap_process_migration_plan_aggregate "
                + "where tenant_id=:tenantId and intent_id=:intentId "
                + "order by aggregate_revision desc limit 1",
            params("tenantId", tenantId, "intentId", intentId),
            (row, number) -> row.getString(1)
        ).stream().findFirst().orElseThrow(() -> conflict(
            "plan aggregate predecessor was not found"
        ));
    }

    private void insertAggregate(PlanAggregate aggregate) {
        jdbc.update("""
            insert into ap_process_migration_plan_aggregate (
              tenant_id,aggregate_id,plan_id,intent_id,aggregate_revision,status,
              selected_count,terminal_count,succeeded_count,unresolved_count,
              input_evidence_hash,predecessor_hash,request_hash,aggregate_hash,
              aggregated_at,request_id,trace_id,payload_json
            ) values (
              :tenantId,:aggregateId,:planId,:intentId,:revision,:status,
              :selected,:terminal,:succeeded,:unresolved,
              :inputHash,:predecessor,:requestHash,:aggregateHash,
              :aggregatedAt,:requestId,:traceId,cast(:payload as jsonb)
            )
            """, aggregateParameters(aggregate)
                .addValue("payload", json.write(aggregate)));
    }

    private void insertEvent(PlanAggregateEvent event) {
        jdbc.update("""
            insert into ap_process_migration_plan_aggregate_event (
              tenant_id,event_id,aggregate_id,plan_id,intent_id,aggregate_revision,
              status,predecessor_hash,event_hash,happened_at,request_id,trace_id,payload_json
            ) values (
              :tenantId,:eventId,:aggregateId,:planId,:intentId,:revision,
              :status,:predecessor,:eventHash,:happenedAt,:requestId,:traceId,
              cast(:payload as jsonb)
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", event.tenantId())
                .addValue("eventId", event.eventId())
                .addValue("aggregateId", event.aggregateId())
                .addValue("planId", event.planId())
                .addValue("intentId", event.intentId())
                .addValue("revision", event.aggregateRevision())
                .addValue("status", event.status().name())
                .addValue("predecessor", event.predecessorHash())
                .addValue("eventHash", event.eventHash())
                .addValue("happenedAt", offset(event.happenedAt()))
                .addValue("requestId", event.requestId())
                .addValue("traceId", event.traceId())
                .addValue("payload", json.write(event)));
    }

    private void insertCompletion(PlanCompletion completion) {
        jdbc.update("""
            insert into ap_process_migration_plan_completion (
              tenant_id,completion_id,plan_id,intent_id,aggregate_id,aggregate_revision,
              completion_status,selected_count,terminal_count,succeeded_count,
              input_evidence_hash,aggregate_hash,completion_evidence_hash,
              completed_at,request_id,trace_id,payload_json
            ) values (
              :tenantId,:completionId,:planId,:intentId,:aggregateId,:revision,
              :status,:selected,:terminal,:succeeded,
              :inputHash,:aggregateHash,:completionHash,
              :completedAt,:requestId,:traceId,cast(:payload as jsonb)
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", completion.tenantId())
                .addValue("completionId", completion.completionId())
                .addValue("planId", completion.planId())
                .addValue("intentId", completion.intentId())
                .addValue("aggregateId", completion.aggregateId())
                .addValue("revision", completion.aggregateRevision())
                .addValue("status", completion.completionStatus().name())
                .addValue("selected", completion.selectedCount())
                .addValue("terminal", completion.terminalCount())
                .addValue("succeeded", completion.succeededCount())
                .addValue("inputHash", completion.inputEvidenceHash())
                .addValue("aggregateHash", completion.aggregateHash())
                .addValue("completionHash", completion.completionEvidenceHash())
                .addValue("completedAt", offset(completion.completedAt()))
                .addValue("requestId", completion.requestId())
                .addValue("traceId", completion.traceId())
                .addValue("payload", json.write(completion)));
    }

    private MapSqlParameterSource aggregateParameters(PlanAggregate aggregate) {
        return new MapSqlParameterSource()
            .addValue("tenantId", aggregate.tenantId())
            .addValue("aggregateId", aggregate.aggregateId())
            .addValue("planId", aggregate.planId())
            .addValue("intentId", aggregate.intentId())
            .addValue("revision", aggregate.aggregateRevision())
            .addValue("status", aggregate.status().name())
            .addValue("selected", aggregate.selectedCount())
            .addValue("terminal", aggregate.terminalCount())
            .addValue("succeeded", aggregate.succeededCount())
            .addValue("unresolved", aggregate.unresolvedCount())
            .addValue("inputHash", aggregate.inputEvidenceHash())
            .addValue("predecessor", aggregate.predecessorHash())
            .addValue("requestHash", aggregate.requestHash())
            .addValue("aggregateHash", aggregate.aggregateHash())
            .addValue("aggregatedAt", offset(aggregate.aggregatedAt()))
            .addValue("requestId", aggregate.requestId())
            .addValue("traceId", aggregate.traceId());
    }

    private void appendAudit(PlanAggregate aggregate, boolean completed) {
        auditEvents.append(new AuditEvent(
            nextIdentifier("auditEventId"),
            aggregate.tenantId(),
            "server:m5-plan-aggregation",
            completed
                ? "PROCESS_MIGRATION_PLAN_COMPLETION_RECORDED"
                : "PROCESS_MIGRATION_PLAN_AGGREGATED",
            "APPROVAL_MIGRATION_PLAN",
            aggregate.planId().toString(),
            aggregate.requestId(),
            aggregate.traceId(),
            aggregate.aggregatedAt(),
            Map.of(
                "aggregateRevision", Long.toString(aggregate.aggregateRevision()),
                "aggregateStatus", aggregate.status().name(),
                "selectedCount", Integer.toString(aggregate.selectedCount()),
                "terminalCount", Integer.toString(aggregate.terminalCount()),
                "succeededCount", Integer.toString(aggregate.succeededCount()),
                "unresolvedCount", Integer.toString(aggregate.unresolvedCount()),
                "aggregateHash", aggregate.aggregateHash()
            )
        ));
    }

    private String requestHash(AggregationRequest request) {
        return sha256(join(
            "m5-d8-plan-aggregation-request-v1",
            request.tenantId(),
            request.intentId(),
            request.expectedAggregateRevision(),
            request.requestId(),
            request.traceId()
        ));
    }

    private <T> T execute(String message, Supplier<T> operation) {
        try {
            T value = transactions.execute(status -> operation.get());
            return Objects.requireNonNull(value, "transaction returned null");
        } catch (AggregationConflictException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw conflict(message, exception);
        }
    }

    private UUID nextIdentifier(String name) {
        return Objects.requireNonNull(identifiers.get(), name + " supplier returned null");
    }

    private static boolean manual(String status) {
        return "MANUAL_REVIEW_REQUIRED".equals(status) || "UNRESOLVED".equals(status);
    }

    private static Integer nullableInteger(java.sql.ResultSet row, String column)
        throws java.sql.SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? null : value;
    }

    private static Long nullableLong(java.sql.ResultSet row, String column)
        throws java.sql.SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static MapSqlParameterSource params(Object... values) {
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        for (int index = 0; index < values.length; index += 2) {
            parameters.addValue((String) values[index], values[index + 1]);
        }
        return parameters;
    }

    private static OffsetDateTime offset(Instant value) {
        return JdbcApprovalMigrationJson.offset(value);
    }

    private static String join(Object... values) {
        return java.util.Arrays.stream(values)
            .map(value -> Objects.toString(value, ""))
            .collect(java.util.stream.Collectors.joining("\u001f"));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static AggregationConflictException conflict(String message) {
        return new AggregationConflictException(message);
    }

    private static AggregationConflictException conflict(String message, Throwable cause) {
        return new AggregationConflictException(message, cause);
    }

    private record PlanContext(
        String tenantId,
        UUID planId,
        String planHash,
        int selectedCount,
        UUID intentId,
        String intentEvidenceHash
    ) {
    }

    private record FactRow(
        int sequenceNo,
        UUID approvalInstanceId,
        boolean canary,
        String instanceEvidenceHash,
        UUID attemptId,
        Integer attemptNumber,
        String attemptStatus,
        Long attemptRevision,
        String engineOutcome,
        String failureClass,
        String attemptPayload,
        int completionCount,
        UUID completionAttemptId,
        String completionHash,
        int conflictCount,
        String conflictHash,
        String reconciliationStatus,
        String reconciliationHash
    ) {
    }

    private record SignalRow(
        UUID selectionId,
        String selectionHash,
        UUID runId,
        String phase,
        String runHash,
        String eventType,
        String eventHash,
        String batchHash,
        String observationHash
    ) {
    }
}
