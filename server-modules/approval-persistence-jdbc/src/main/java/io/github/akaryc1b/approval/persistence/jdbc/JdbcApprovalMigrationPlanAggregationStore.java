package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationPlanAggregationStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.AggregateStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.CanaryStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.InstanceFact;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.InstanceStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.OrchestrationStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PauseReason;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanAggregate;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanAggregateEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanCompletion;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.PlanSignals;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationPlanAggregationEvidence.StateCounts;
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
import java.util.ArrayList;
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
        Optional<PlanAggregate> replay = findAggregateByIdempotency(
            request.tenantId(),
            request.idempotencyKey()
        );
        if (replay.isPresent()) {
            PlanAggregate aggregate = replay.orElseThrow();
            requireReplay(aggregate, request, requestHash);
            return result(aggregate, true);
        }

        PlanContext plan = lockPlan(request.tenantId(), request.planId());
        long currentRevision = currentAggregateRevision(plan.tenantId(), plan.planId());
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
        if (currentRevision > 0) {
            PlanAggregate latest = latestAggregate(plan.tenantId(), plan.planId());
            if (latest.inputEvidenceHash().equals(inputHash)) {
                throw conflict(
                    "authoritative aggregation input is unchanged; exact replay must reuse "
                        + "the existing idempotency key"
                );
            }
        }
        String predecessor = currentRevision == 0
            ? ZERO_HASH
            : latestAggregateHash(plan.tenantId(), plan.planId());
        String aggregateHash = aggregateHash(
            plan,
            request.expectedAggregateRevision(),
            summary,
            inputHash,
            predecessor
        );

        UUID auditEventId = nextIdentifier("auditEventId");
        String auditReference = "audit-event:" + auditEventId;
        PlanAggregate aggregate = new PlanAggregate(
            nextIdentifier("planAggregateId"),
            plan.tenantId(),
            request.operatorId(),
            plan.planId(),
            plan.intentId(),
            plan.planHash(),
            request.expectedAggregateRevision(),
            summary.status(),
            summary.terminalOutcome(),
            summary.counts(),
            summary.signals().canaryStatus(),
            summary.signals().orchestrationStatus(),
            summary.signals().paused(),
            summary.signals().pauseReason(),
            summary.signals().killSwitchObserved(),
            inputHash,
            predecessor,
            request.idempotencyKey(),
            requestHash,
            aggregateHash,
            request.happenedAt(),
            request.reason(),
            request.requestId(),
            request.traceId(),
            auditReference
        );
        PlanAggregateEvent event = event(aggregate, request);
        PlanCompletion completion = completion(aggregate, request);

        insertAggregate(aggregate);
        insertEvent(event);
        if (completion != null) {
            insertCompletion(completion);
        }
        appendAudit(auditEventId, aggregate, completion != null);
        return new AggregationResult(aggregate, event, completion, false);
    }

    private PlanContext lockPlan(String tenantId, UUID planId) {
        PlanContext plan = jdbc.query("""
            select plan.tenant_id,plan.plan_id,plan.plan_hash,plan.selected_instance_count,
              intent.intent_id,intent.intent_evidence_hash
            from ap_process_migration_plan plan
            join ap_process_migration_plan_consumption consumption
              on consumption.tenant_id=plan.tenant_id and consumption.plan_id=plan.plan_id
             and consumption.plan_hash=plan.plan_hash
            join ap_process_migration_intent intent
              on intent.tenant_id=consumption.tenant_id
             and intent.intent_id=consumption.intent_id
             and intent.plan_id=plan.plan_id and intent.plan_hash=plan.plan_hash
             and intent.intent_evidence_hash=consumption.intent_evidence_hash
            where plan.tenant_id=:tenantId and plan.plan_id=:planId
              and plan.status='CONSUMED'
            for update of plan,intent
            """, params("tenantId", tenantId, "planId", planId),
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
              coalesce(attempt.attempt_count,0) attempt_count,
              attempt.attempt_id,attempt.attempt_number,attempt.status attempt_status,
              attempt.revision attempt_revision,attempt.engine_outcome,
              attempt.expected_binding_evidence_hash,
              request.request_hash engine_request_hash,
              request.evidence_hash engine_request_evidence_hash,
              outcome.disposition engine_outcome_disposition,
              outcome.outcome_hash,
              verification.classification verification_classification,
              verification.truncated verification_truncated,
              verification.verification_evidence_hash,
              coalesce(completion.completion_count,0) completion_count,
              completion.completion_attempt_id,completion.binding_revision,
              completion.target_binding_evidence_hash,completion.completion_hash,
              coalesce(conflict.conflict_count,0) conflict_count,
              conflict.conflict_hash,
              reconciliation.status reconciliation_status,
              coalesce(reconciliation.resolution_evidence_hash,
                reconciliation.evidence_hash) reconciliation_hash,
              observation.classification observation_classification,
              observation.disposition observation_disposition,
              observation.evidence_hash observation_hash
            from ap_process_migration_plan_instance selection
            left join ap_process_migration_canary_selection canary
              on canary.tenant_id=selection.tenant_id and canary.plan_id=selection.plan_id
             and canary.approval_instance_id=selection.approval_instance_id
            left join lateral (
              select count(*)::integer attempt_count,
                (array_agg(value.attempt_id order by value.attempt_number desc,
                  value.attempt_id desc))[1] attempt_id,
                (array_agg(value.attempt_number order by value.attempt_number desc,
                  value.attempt_id desc))[1] attempt_number,
                (array_agg(value.status order by value.attempt_number desc,
                  value.attempt_id desc))[1] status,
                (array_agg(value.revision order by value.attempt_number desc,
                  value.attempt_id desc))[1] revision,
                (array_agg(value.engine_outcome order by value.attempt_number desc,
                  value.attempt_id desc))[1] engine_outcome,
                (array_agg(value.expected_binding_evidence_hash
                  order by value.attempt_number desc,value.attempt_id desc))[1]
                  expected_binding_evidence_hash
              from ap_process_migration_attempt value
              where value.tenant_id=selection.tenant_id
                and value.intent_id=:intentId
                and value.approval_instance_id=selection.approval_instance_id
            ) attempt on true
            left join ap_process_migration_engine_request request
              on request.tenant_id=selection.tenant_id
             and request.attempt_id=attempt.attempt_id
            left join ap_process_migration_engine_outcome outcome
              on outcome.tenant_id=selection.tenant_id
             and outcome.attempt_id=attempt.attempt_id
            left join ap_process_migration_exact_verification verification
              on verification.tenant_id=selection.tenant_id
             and verification.attempt_id=attempt.attempt_id
            left join lateral (
              select count(*)::integer completion_count,
                (array_agg(value.attempt_id order by value.completed_at desc,
                  value.completion_id desc))[1] completion_attempt_id,
                (array_agg(value.binding_revision order by value.completed_at desc,
                  value.completion_id desc))[1] binding_revision,
                (array_agg(value.target_binding_evidence_hash order by value.completed_at desc,
                  value.completion_id desc))[1] target_binding_evidence_hash,
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
            left join ap_process_migration_reconciliation_observation observation
              on observation.tenant_id=selection.tenant_id
             and observation.attempt_id=attempt.attempt_id
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
                row.getInt("attempt_count"),
                row.getObject("attempt_id", UUID.class),
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
                row.getObject("completion_attempt_id", UUID.class),
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
            )));
    }

    private PlanSignals loadSignals(PlanContext plan) {
        SignalRow row = jdbc.query("""
            select canary.selection_id,canary.selection_evidence_hash,
              run.run_id,run.phase,run.run_evidence_hash,
              event.event_type,event.pause_reason,event.event_evidence_hash,
              batch.batch_evidence_hash,
              observation.switch_enabled,observation.dispatch_allowed,
              observation.reason_code,observation.observation_evidence_hash
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
              select value.event_type,value.pause_reason,value.event_evidence_hash
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
              select value.switch_enabled,value.dispatch_allowed,value.reason_code,
                value.observation_evidence_hash
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
                result.getString("pause_reason"),
                result.getString("event_evidence_hash"),
                result.getString("batch_evidence_hash"),
                nullableBoolean(result, "switch_enabled"),
                nullableBoolean(result, "dispatch_allowed"),
                result.getString("reason_code"),
                result.getString("observation_evidence_hash")
            )).stream().findFirst().orElseThrow(() -> conflict(
                "plan aggregation signals could not be read"
            ));

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

        PauseReason pauseReason = incomplete
            ? PauseReason.INCOMPLETE_EVIDENCE
            : pauseReason(row.pauseReason());
        boolean killSwitchObserved = row.observationHash() != null;
        if (pauseReason == PauseReason.KILL_SWITCH && !killSwitchObserved) {
            incomplete = true;
            pauseReason = PauseReason.INCOMPLETE_EVIDENCE;
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
            pauseReason,
            killSwitchObserved,
            incomplete,
            evidenceHash
        );
    }

    private InstanceFact fact(FactRow row) {
        InstanceStatus status;
        if (row.attemptCount() == 0) {
            status = InstanceStatus.UNPROVISIONED;
        } else if (row.attemptCount() != 1 || row.attemptId() == null) {
            status = InstanceStatus.INVALID_INCOMPLETE_EVIDENCE;
        } else if (row.conflictCount() > 0) {
            status = InstanceStatus.BINDING_CONFLICT;
        } else if (row.completionCount() > 1) {
            status = InstanceStatus.INVALID_INCOMPLETE_EVIDENCE;
        } else if (row.completionCount() == 1) {
            status = exactCompletion(row)
                ? InstanceStatus.EXACTLY_COMPLETED
                : InstanceStatus.INVALID_INCOMPLETE_EVIDENCE;
        } else {
            status = switch (row.attemptStatus()) {
                case "PENDING" -> InstanceStatus.PENDING;
                case "CLAIMED" -> InstanceStatus.CLAIMED;
                case "ENGINE_REQUESTED" -> InstanceStatus.ENGINE_REQUESTED;
                case "VERIFYING" -> InstanceStatus.VERIFYING;
                case "UNKNOWN" -> InstanceStatus.UNKNOWN;
                case "RECONCILING" -> manual(row.reconciliationStatus())
                    ? InstanceStatus.MANUAL_REVIEW_REQUIRED
                    : InstanceStatus.RECONCILING;
                case "BLOCKED_STALE" -> InstanceStatus.BLOCKED_STALE;
                case "FAILED_RETRYABLE", "FAILED_TERMINAL", "CANCELLED" ->
                    InstanceStatus.TERMINAL_FAILURE;
                case "SUCCEEDED" -> InstanceStatus.INVALID_INCOMPLETE_EVIDENCE;
                default -> InstanceStatus.INVALID_INCOMPLETE_EVIDENCE;
            };
        }

        String evidenceHash = hashValues(
            "M5-D8-INSTANCE-FACT-V1",
            row.sequenceNo(),
            row.approvalInstanceId(),
            row.canary(),
            row.instanceEvidenceHash(),
            row.attemptCount(),
            row.attemptId(),
            row.attemptNumber(),
            row.attemptStatus(),
            row.attemptRevision(),
            row.engineOutcome(),
            row.expectedBindingEvidenceHash(),
            row.engineRequestHash(),
            row.engineRequestEvidenceHash(),
            row.engineOutcomeDisposition(),
            row.outcomeHash(),
            row.verificationClassification(),
            row.verificationTruncated(),
            row.verificationEvidenceHash(),
            row.completionCount(),
            row.completionAttemptId(),
            row.bindingRevision(),
            row.targetBindingEvidenceHash(),
            row.completionHash(),
            row.conflictCount(),
            row.conflictHash(),
            row.reconciliationStatus(),
            row.reconciliationHash(),
            row.observationClassification(),
            row.observationDisposition(),
            row.observationHash(),
            status
        );
        return new InstanceFact(
            row.sequenceNo(),
            row.approvalInstanceId(),
            row.canary(),
            row.attemptId(),
            status,
            row.instanceEvidenceHash(),
            evidenceHash
        );
    }

    private static boolean exactCompletion(FactRow row) {
        return row.attemptId().equals(row.completionAttemptId())
            && "SUCCEEDED".equals(row.attemptStatus())
            && "EXACT_TARGET_RUNTIME".equals(row.verificationClassification())
            && Boolean.FALSE.equals(row.verificationTruncated())
            && row.bindingRevision() != null
            && row.bindingRevision() > 1
            && row.targetBindingEvidenceHash() != null
            && row.verificationEvidenceHash() != null
            && row.completionHash() != null;
    }

    private String inputEvidenceHash(PlanContext plan, Summary summary) {
        List<Object> values = new ArrayList<>();
        values.add("M5-D8-INPUT-EVIDENCE-V1");
        values.add(plan.tenantId());
        values.add(plan.planId());
        values.add(plan.planHash());
        values.add(plan.intentId());
        values.add(plan.intentEvidenceHash());
        values.add(plan.selectedCount());
        values.add(summary.signals().canaryStatus());
        values.add(summary.signals().orchestrationStatus());
        values.add(summary.signals().pauseReason());
        values.add(summary.signals().killSwitchObserved());
        values.add(summary.signals().incompleteEvidence());
        values.add(summary.signals().evidenceHash());
        for (InstanceFact fact : summary.canonicalFacts()) {
            values.add(fact.sequenceNo());
            values.add(fact.approvalInstanceId());
            values.add(fact.canary());
            values.add(fact.attemptId());
            values.add(fact.status());
            values.add(fact.selectedInstanceEvidenceHash());
            values.add(fact.evidenceHash());
        }
        return hashValues(values.toArray());
    }

    private String aggregateHash(
        PlanContext plan,
        long revision,
        Summary summary,
        String inputHash,
        String predecessor
    ) {
        StateCounts counts = summary.counts();
        return hashValues(
            "M5-D8-PLAN-AGGREGATE-V1",
            plan.tenantId(),
            plan.planId(),
            plan.intentId(),
            plan.planHash(),
            revision,
            summary.status(),
            summary.terminalOutcome(),
            counts.selectedCount(),
            counts.provisionedAttemptCount(),
            counts.pendingCount(),
            counts.claimedCount(),
            counts.engineRequestedCount(),
            counts.verifyingCount(),
            counts.reconcilingCount(),
            counts.unknownCount(),
            counts.manualReviewCount(),
            counts.bindingConflictCount(),
            counts.blockedStaleCount(),
            counts.terminalFailedCount(),
            counts.exactSuccessCount(),
            counts.unresolvedCount(),
            summary.signals().canaryStatus(),
            summary.signals().orchestrationStatus(),
            summary.signals().pauseReason(),
            summary.signals().killSwitchObserved(),
            inputHash,
            predecessor
        );
    }

    private PlanAggregateEvent event(PlanAggregate aggregate, AggregationRequest request) {
        String eventHash = hashValues(
            "M5-D8-PLAN-AGGREGATE-EVENT-V1",
            aggregate.tenantId(),
            aggregate.planId(),
            aggregate.intentId(),
            aggregate.aggregateRevision(),
            aggregate.status(),
            aggregate.terminalOutcome(),
            aggregate.pauseReason(),
            aggregate.aggregateHash(),
            aggregate.predecessorHash()
        );
        return new PlanAggregateEvent(
            nextIdentifier("planAggregateEventId"),
            aggregate.tenantId(),
            aggregate.aggregateId(),
            aggregate.planId(),
            aggregate.intentId(),
            aggregate.aggregateRevision(),
            aggregate.status(),
            aggregate.terminalOutcome(),
            aggregate.pauseReason(),
            aggregate.predecessorHash(),
            aggregate.aggregateHash(),
            eventHash,
            request.happenedAt(),
            request.requestId(),
            request.traceId(),
            aggregate.auditReference()
        );
    }

    private PlanCompletion completion(PlanAggregate aggregate, AggregationRequest request) {
        if (aggregate.status() != AggregateStatus.COMPLETED_SUCCEEDED
            && aggregate.status() != AggregateStatus.COMPLETED_WITH_TERMINAL_FAILURE) {
            return null;
        }
        String completionHash = hashValues(
            "M5-D8-PLAN-COMPLETION-V1",
            aggregate.tenantId(),
            aggregate.planId(),
            aggregate.intentId(),
            aggregate.aggregateRevision(),
            aggregate.status(),
            aggregate.terminalOutcome(),
            aggregate.counts().selectedCount(),
            aggregate.counts().exactSuccessCount(),
            aggregate.counts().terminalFailedCount(),
            aggregate.counts().unresolvedCount(),
            aggregate.inputEvidenceHash(),
            aggregate.aggregateHash()
        );
        return new PlanCompletion(
            nextIdentifier("planCompletionId"),
            aggregate.tenantId(),
            aggregate.planId(),
            aggregate.intentId(),
            aggregate.aggregateId(),
            aggregate.aggregateRevision(),
            aggregate.status(),
            aggregate.terminalOutcome(),
            aggregate.counts(),
            aggregate.inputEvidenceHash(),
            aggregate.aggregateHash(),
            completionHash,
            request.happenedAt(),
            request.requestId(),
            request.traceId(),
            aggregate.auditReference()
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

    private Optional<PlanAggregate> findAggregateByIdempotency(
        String tenantId,
        String idempotencyKey
    ) {
        return queryOne(
            "select payload_json::text from ap_process_migration_plan_aggregate "
                + "where tenant_id=:tenantId and idempotency_key=:idempotencyKey",
            params("tenantId", tenantId, "idempotencyKey", idempotencyKey),
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

    private long currentAggregateRevision(String tenantId, UUID planId) {
        Long value = jdbc.queryForObject(
            "select coalesce(max(aggregate_revision),0) "
                + "from ap_process_migration_plan_aggregate "
                + "where tenant_id=:tenantId and plan_id=:planId",
            params("tenantId", tenantId, "planId", planId),
            Long.class
        );
        return value == null ? 0 : value;
    }

    private PlanAggregate latestAggregate(String tenantId, UUID planId) {
        return queryOne(
            "select payload_json::text from ap_process_migration_plan_aggregate "
                + "where tenant_id=:tenantId and plan_id=:planId "
                + "order by aggregate_revision desc limit 1",
            params("tenantId", tenantId, "planId", planId),
            PlanAggregate.class
        ).orElseThrow(() -> conflict("plan aggregate predecessor was not found"));
    }

    private String latestAggregateHash(String tenantId, UUID planId) {
        return jdbc.query(
            "select aggregate_hash from ap_process_migration_plan_aggregate "
                + "where tenant_id=:tenantId and plan_id=:planId "
                + "order by aggregate_revision desc limit 1",
            params("tenantId", tenantId, "planId", planId),
            (row, number) -> row.getString(1)
        ).stream().findFirst().orElseThrow(() -> conflict(
            "plan aggregate predecessor was not found"
        ));
    }

    private void insertAggregate(PlanAggregate aggregate) {
        StateCounts counts = aggregate.counts();
        jdbc.update("""
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
              :aggregateHash,:aggregatedAt,:reason,:requestId,:traceId,:auditReference,
              cast(:payload as jsonb)
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", aggregate.tenantId())
                .addValue("aggregateId", aggregate.aggregateId())
                .addValue("planId", aggregate.planId())
                .addValue("intentId", aggregate.intentId())
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
                .addValue("orchestrationStatus", aggregate.orchestrationStatus().name())
                .addValue("paused", aggregate.paused())
                .addValue("pauseReason", aggregate.pauseReason().name())
                .addValue("killSwitchObserved", aggregate.killSwitchObserved())
                .addValue("inputHash", aggregate.inputEvidenceHash())
                .addValue("predecessor", aggregate.predecessorHash())
                .addValue("operatorId", aggregate.operatorId())
                .addValue("idempotencyKey", aggregate.idempotencyKey())
                .addValue("requestHash", aggregate.requestHash())
                .addValue("aggregateHash", aggregate.aggregateHash())
                .addValue("aggregatedAt", offset(aggregate.aggregatedAt()))
                .addValue("reason", aggregate.reason())
                .addValue("requestId", aggregate.requestId())
                .addValue("traceId", aggregate.traceId())
                .addValue("auditReference", aggregate.auditReference())
                .addValue("payload", json.write(aggregate)));
    }

    private void insertEvent(PlanAggregateEvent event) {
        jdbc.update("""
            insert into ap_process_migration_plan_aggregate_event (
              tenant_id,event_id,aggregate_id,plan_id,intent_id,aggregate_revision,
              status,terminal_outcome,pause_reason,predecessor_hash,aggregate_hash,
              event_hash,happened_at,request_id,trace_id,audit_reference,payload_json
            ) values (
              :tenantId,:eventId,:aggregateId,:planId,:intentId,:revision,
              :status,:terminalOutcome,:pauseReason,:predecessor,:aggregateHash,
              :eventHash,:happenedAt,:requestId,:traceId,:auditReference,cast(:payload as jsonb)
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", event.tenantId())
                .addValue("eventId", event.eventId())
                .addValue("aggregateId", event.aggregateId())
                .addValue("planId", event.planId())
                .addValue("intentId", event.intentId())
                .addValue("revision", event.aggregateRevision())
                .addValue("status", event.status().name())
                .addValue("terminalOutcome", event.terminalOutcome().name())
                .addValue("pauseReason", event.pauseReason().name())
                .addValue("predecessor", event.predecessorHash())
                .addValue("aggregateHash", event.aggregateHash())
                .addValue("eventHash", event.eventHash())
                .addValue("happenedAt", offset(event.happenedAt()))
                .addValue("requestId", event.requestId())
                .addValue("traceId", event.traceId())
                .addValue("auditReference", event.auditReference())
                .addValue("payload", json.write(event)));
    }

    private void insertCompletion(PlanCompletion completion) {
        StateCounts counts = completion.counts();
        jdbc.update("""
            insert into ap_process_migration_plan_completion (
              tenant_id,completion_id,plan_id,intent_id,aggregate_id,aggregate_revision,
              completion_status,terminal_outcome,selected_count,terminal_failed_count,
              exact_success_count,input_evidence_hash,aggregate_hash,completion_evidence_hash,
              completed_at,request_id,trace_id,audit_reference,payload_json
            ) values (
              :tenantId,:completionId,:planId,:intentId,:aggregateId,:revision,
              :status,:terminalOutcome,:selected,:terminalFailed,:exactSuccess,
              :inputHash,:aggregateHash,:completionHash,:completedAt,:requestId,:traceId,
              :auditReference,cast(:payload as jsonb)
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", completion.tenantId())
                .addValue("completionId", completion.completionId())
                .addValue("planId", completion.planId())
                .addValue("intentId", completion.intentId())
                .addValue("aggregateId", completion.aggregateId())
                .addValue("revision", completion.aggregateRevision())
                .addValue("status", completion.completionStatus().name())
                .addValue("terminalOutcome", completion.terminalOutcome().name())
                .addValue("selected", counts.selectedCount())
                .addValue("terminalFailed", counts.terminalFailedCount())
                .addValue("exactSuccess", counts.exactSuccessCount())
                .addValue("inputHash", completion.inputEvidenceHash())
                .addValue("aggregateHash", completion.aggregateHash())
                .addValue("completionHash", completion.completionEvidenceHash())
                .addValue("completedAt", offset(completion.completedAt()))
                .addValue("requestId", completion.requestId())
                .addValue("traceId", completion.traceId())
                .addValue("auditReference", completion.auditReference())
                .addValue("payload", json.write(completion)));
    }

    private void appendAudit(UUID auditEventId, PlanAggregate aggregate, boolean completed) {
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
                Map.entry("aggregateRevision", Long.toString(aggregate.aggregateRevision())),
                Map.entry("aggregateStatus", aggregate.status().name()),
                Map.entry("terminalOutcome", aggregate.terminalOutcome().name()),
                Map.entry("selectedCount", Integer.toString(aggregate.counts().selectedCount())),
                Map.entry("exactSuccessCount", Integer.toString(
                    aggregate.counts().exactSuccessCount()
                )),
                Map.entry("terminalFailedCount", Integer.toString(
                    aggregate.counts().terminalFailedCount()
                )),
                Map.entry("unresolvedCount", Integer.toString(
                    aggregate.counts().unresolvedCount()
                )),
                Map.entry("pauseReason", aggregate.pauseReason().name()),
                Map.entry("reasonHash", hashValues(
                    "M5-D8-BOUNDED-REASON-V1",
                    aggregate.reason()
                )),
                Map.entry("aggregateHash", aggregate.aggregateHash())
            )
        ));
    }

    private String requestHash(AggregationRequest request) {
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

    private static PauseReason pauseReason(String value) {
        if (value == null || "NONE".equals(value)) {
            return PauseReason.NONE;
        }
        return switch (value) {
            case "KILL_SWITCH_ACTIVE" -> PauseReason.KILL_SWITCH;
            case "CANARY_UNKNOWN" -> PauseReason.UNKNOWN;
            case "CANARY_RECONCILIATION" -> PauseReason.RECONCILIATION;
            case "CANARY_MANUAL_REVIEW" -> PauseReason.MANUAL_REVIEW;
            case "CANARY_BINDING_CONFLICT" -> PauseReason.BINDING_CONFLICT;
            case "TERMINAL_FAILURE" -> PauseReason.TERMINAL_FAILURE;
            case "CANARY_IN_FLIGHT" -> PauseReason.CANARY_IN_FLIGHT;
            case "EMPTY_BATCH" -> PauseReason.EMPTY_BATCH;
            case "CANARY_NOT_EXACT_TARGET", "STALE_KILL_SWITCH_REVISION",
                 "STALE_ORCHESTRATION_REVISION", "STALE_WORKER", "STALE_LEASE" ->
                PauseReason.STALE_AUTHORITY;
            case "MISSING_OR_INCOMPLETE_EVIDENCE" ->
                PauseReason.INCOMPLETE_EVIDENCE;
            default -> PauseReason.INCOMPLETE_EVIDENCE;
        };
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

    private static Boolean nullableBoolean(java.sql.ResultSet row, String column)
        throws java.sql.SQLException {
        boolean value = row.getBoolean(column);
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

    private static String hashValues(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                if (value == null) {
                    digest.update("-1:".getBytes(StandardCharsets.UTF_8));
                } else {
                    byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
                    digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) ':');
                    digest.update(bytes);
                    digest.update((byte) '|');
                }
            }
            return HexFormat.of().formatHex(digest.digest());
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
        int attemptCount,
        UUID attemptId,
        Integer attemptNumber,
        String attemptStatus,
        Long attemptRevision,
        String engineOutcome,
        String expectedBindingEvidenceHash,
        String engineRequestHash,
        String engineRequestEvidenceHash,
        String engineOutcomeDisposition,
        String outcomeHash,
        String verificationClassification,
        Boolean verificationTruncated,
        String verificationEvidenceHash,
        int completionCount,
        UUID completionAttemptId,
        Long bindingRevision,
        String targetBindingEvidenceHash,
        String completionHash,
        int conflictCount,
        String conflictHash,
        String reconciliationStatus,
        String reconciliationHash,
        String observationClassification,
        String observationDisposition,
        String observationHash
    ) {
    }

    private record SignalRow(
        UUID selectionId,
        String selectionHash,
        UUID runId,
        String phase,
        String runHash,
        String eventType,
        String pauseReason,
        String eventHash,
        String batchHash,
        Boolean switchEnabled,
        Boolean dispatchAllowed,
        String killReasonCode,
        String observationHash
    ) {
    }
}
