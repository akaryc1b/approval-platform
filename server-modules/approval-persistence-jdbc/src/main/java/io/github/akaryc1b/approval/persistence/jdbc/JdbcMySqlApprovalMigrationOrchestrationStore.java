package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationKillSwitch.Snapshot;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationOrchestrationStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationClaimBatch;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.AttemptDisposition;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.BoundedBatch;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.CanaryGate;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.CanarySelection;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.KillSwitchObservation;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.OrchestrationEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.OrchestrationPhase;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.OrchestrationRun;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.PauseReason;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationOrchestrationEvidence.RunEventType;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** MySQL 8.4 D7 evidence boundary. No method contains an engine call or long transaction. */
public final class JdbcMySqlApprovalMigrationOrchestrationStore
    implements ApprovalMigrationOrchestrationStore {

    private static final String LOCK_NAMESPACE =
        "approval-migration-orchestration:v1:";
    private static final String ZERO_HASH = "0".repeat(64);

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcApprovalMigrationJson json;
    private final JdbcDatabaseValueAdapter values;
    private final JdbcMySqlTransactionLockManager locks;
    private final TransactionTemplate transactions;
    private final AuditEventSink auditEvents;
    private final Supplier<UUID> identifiers;

    public JdbcMySqlApprovalMigrationOrchestrationStore(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager,
        AuditEventSink auditEvents,
        Supplier<UUID> identifiers
    ) {
        DataSource source = Objects.requireNonNull(
            dataSource,
            "dataSource must not be null"
        );
        ObjectMapper mapper = Objects.requireNonNull(
            objectMapper,
            "objectMapper must not be null"
        );
        PlatformTransactionManager manager = Objects.requireNonNull(
            transactionManager,
            "transactionManager must not be null"
        );
        values = JdbcDatabaseValueAdapter.resolve(source);
        if (values.vendor() != ApprovalDatabaseVendor.MYSQL) {
            throw new IllegalArgumentException(
                "MySQL orchestration store requires MySQL metadata"
            );
        }
        jdbc = new NamedParameterJdbcTemplate(source);
        json = new JdbcApprovalMigrationJson(mapper);
        locks = new JdbcMySqlTransactionLockManager(source);
        transactions = new TransactionTemplate(manager);
        this.auditEvents = Objects.requireNonNull(
            auditEvents,
            "auditEvents must not be null"
        );
        this.identifiers = Objects.requireNonNull(
            identifiers,
            "identifiers must not be null"
        );
    }

    @Override
    public PreparedOrchestration prepare(PrepareRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return execute("migration orchestration preparation conflict", () -> {
            locks.acquire(intentLockScope(request.tenantId(), request.intentId()));
            return prepareOnce(request);
        });
    }

    @Override
    public DispatchAuthorization authorizeDispatch(DispatchRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return execute("migration dispatch authorization conflict", () -> {
            OrchestrationRun run = request.run();
            locks.acquire(runLockScope(run.tenantId(), run.runId()));
            return authorizeOnce(request);
        });
    }

    @Override
    public FinalizedOrchestration finalizeRun(FinalizeRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return execute("migration orchestration finalization conflict", () -> {
            OrchestrationRun run = request.prepared().run();
            locks.acquire(runLockScope(run.tenantId(), run.runId()));
            return finalizeOnce(request);
        });
    }

    private PreparedOrchestration prepareOnce(PrepareRequest request) {
        String requestHash = prepareRequestHash(request);
        Optional<OrchestrationRun> replay = findRunByRequestHash(
            request.tenantId(),
            requestHash
        );
        if (replay.isPresent()) {
            OrchestrationRun run = replay.orElseThrow();
            requireRunReplay(run, request);
            CanarySelection canary = findCanary(run.tenantId(), run.planId());
            GateDecision gate = canaryGate(
                run.tenantId(),
                run.intentId(),
                canary.approvalInstanceId()
            );
            return prepared(
                run,
                canary,
                gate,
                latestEvent(run.tenantId(), run.runId()),
                true
            );
        }

        PlanContext plan = lockPlan(request.tenantId(), request.intentId());
        CanarySelection canary = ensureCanary(plan, request);
        GateDecision gate = canaryGate(
            plan.tenantId(),
            plan.intentId(),
            canary.approvalInstanceId()
        );
        long currentRevision = currentRunRevision(plan.tenantId(), plan.intentId());
        if (request.expectedRunRevision() != currentRevision + 1) {
            throw conflict("orchestration revision is stale");
        }
        String predecessor = currentRevision == 0
            ? ZERO_HASH
            : latestRunHash(plan.tenantId(), plan.intentId());
        OrchestrationPhase phase = gate.gate() == CanaryGate.READY
            ? OrchestrationPhase.BOUNDED
            : OrchestrationPhase.CANARY;
        UUID runId = nextIdentifier("orchestrationRunId");
        String runHash = sha256(join(
            "m5-d7-orchestration-run-v1",
            runId,
            plan.tenantId(),
            plan.planId(),
            plan.intentId(),
            request.expectedRunRevision(),
            phase,
            request.requestedLimit(),
            canary.selectionId(),
            request.killSwitch().revision(),
            predecessor,
            requestHash
        ));
        OrchestrationRun run = new OrchestrationRun(
            runId,
            plan.tenantId(),
            plan.planId(),
            plan.intentId(),
            request.expectedRunRevision(),
            phase,
            request.requestedLimit(),
            canary.selectionId(),
            request.killSwitch().revision(),
            predecessor,
            requestHash,
            runHash,
            request.happenedAt(),
            request.requestId(),
            request.traceId()
        );
        insertRun(run);

        PauseReason pause = initialPause(gate, request.killSwitch());
        RunEventType type = pause == PauseReason.NONE
            ? RunEventType.PREPARED
            : pause == PauseReason.KILL_SWITCH_ACTIVE
                ? RunEventType.KILL_SWITCH_BLOCKED
                : RunEventType.PAUSED;
        OrchestrationEvent event = appendEvent(
            run,
            type,
            pause,
            null,
            run.runEvidenceHash(),
            request.happenedAt(),
            request.requestId(),
            request.traceId()
        );
        appendAudit(run, "PROCESS_MIGRATION_ORCHESTRATION_PREPARED", request, Map.of(
            "runRevision", Long.toString(run.runRevision()),
            "phase", run.phase().name(),
            "canarySelectionId", canary.selectionId().toString(),
            "killSwitchRevision", Long.toString(request.killSwitch().revision()),
            "pauseReason", pause.name()
        ));
        return prepared(run, canary, gate, event, false);
    }

    private DispatchAuthorization authorizeOnce(DispatchRequest request) {
        OrchestrationRun run = request.run();
        OrchestrationRun stored = findRun(run.tenantId(), run.runId());
        if (!stored.runEvidenceHash().equals(run.runEvidenceHash())
            || stored.runRevision() != request.expectedRunRevision()) {
            throw conflict("orchestration run revision or evidence is stale");
        }
        requireClaimedAttempt(stored, request.attemptId(), request.happenedAt());
        String requestHash = dispatchRequestHash(request);
        Optional<KillSwitchObservation> prior = findObservation(
            stored.tenantId(),
            stored.runId(),
            request.attemptId()
        );
        if (prior.isPresent()) {
            KillSwitchObservation observation = prior.orElseThrow();
            if (!observation.requestHash().equals(requestHash)) {
                throw conflict("changed kill-switch dispatch replay is forbidden");
            }
            PauseReason reason = observation.dispatchAllowed()
                ? PauseReason.NONE
                : observation.enabled()
                    ? PauseReason.KILL_SWITCH_ACTIVE
                    : PauseReason.STALE_KILL_SWITCH_REVISION;
            return new DispatchAuthorization(
                observation,
                findAttemptEvent(
                    stored.tenantId(),
                    stored.runId(),
                    request.attemptId()
                ),
                observation.dispatchAllowed(),
                reason,
                true
            );
        }

        Snapshot observed = request.observedKillSwitch();
        boolean allowed = !observed.enabled()
            && request.expectedKillSwitchRevision() == observed.revision();
        String reasonCode = allowed
            ? "DISPATCH_ALLOWED"
            : observed.enabled() ? "KILL_SWITCH_ACTIVE" : "STALE_KILL_SWITCH_REVISION";
        UUID observationId = nextIdentifier("killSwitchObservationId");
        String evidenceHash = sha256(join(
            "m5-d7-kill-switch-observation-v1",
            observationId,
            stored.tenantId(),
            stored.runId(),
            request.attemptId(),
            request.expectedKillSwitchRevision(),
            observed.revision(),
            observed.enabled(),
            allowed,
            reasonCode,
            observed.evidenceHash(),
            requestHash
        ));
        KillSwitchObservation observation = new KillSwitchObservation(
            observationId,
            stored.tenantId(),
            stored.runId(),
            request.attemptId(),
            request.expectedKillSwitchRevision(),
            observed.revision(),
            observed.enabled(),
            allowed,
            reasonCode,
            requestHash,
            evidenceHash,
            request.happenedAt(),
            request.requestId(),
            request.traceId()
        );
        insertObservation(observation);
        PauseReason pause = allowed
            ? PauseReason.NONE
            : observed.enabled()
                ? PauseReason.KILL_SWITCH_ACTIVE
                : PauseReason.STALE_KILL_SWITCH_REVISION;
        OrchestrationEvent event = appendEvent(
            stored,
            allowed ? RunEventType.DISPATCH_ALLOWED : RunEventType.KILL_SWITCH_BLOCKED,
            pause,
            request.attemptId(),
            observation.observationEvidenceHash(),
            request.happenedAt(),
            request.requestId(),
            request.traceId()
        );
        appendAudit(
            stored,
            allowed
                ? "PROCESS_MIGRATION_ORCHESTRATION_DISPATCH_ALLOWED"
                : "PROCESS_MIGRATION_ORCHESTRATION_KILL_SWITCH_BLOCKED",
            request.requestId(),
            request.traceId(),
            request.happenedAt(),
            Map.of(
                "attemptId", request.attemptId().toString(),
                "expectedKillSwitchRevision",
                Long.toString(request.expectedKillSwitchRevision()),
                "observedKillSwitchRevision",
                Long.toString(observed.revision()),
                "result", reasonCode
            )
        );
        return new DispatchAuthorization(observation, event, allowed, pause, false);
    }

    private FinalizedOrchestration finalizeOnce(FinalizeRequest request) {
        OrchestrationRun supplied = request.prepared().run();
        OrchestrationRun run = findRun(supplied.tenantId(), supplied.runId());
        if (!run.runEvidenceHash().equals(supplied.runEvidenceHash())) {
            throw conflict("orchestration finalization evidence is stale");
        }
        OrchestrationEvent latest = latestEvent(run.tenantId(), run.runId());
        if (terminalEvent(latest.eventType())) {
            return new FinalizedOrchestration(
                run,
                latest,
                findBatch(run.tenantId(), run.runId()).orElse(null),
                latest.pauseReason(),
                latest.eventType() == RunEventType.COMPLETED,
                true
            );
        }

        ApprovalMigrationClaimBatch claim = request.claimBatch();
        List<AttemptFact> facts = List.of();
        BoundedBatch batch = null;
        String predecessor = latest.eventEvidenceHash();
        if (claim != null) {
            requireClaim(run, claim, request.processedAttemptIds());
            facts = attemptFacts(run, claim.claimedAttemptIds());
            UUID batchId = nextIdentifier("orchestrationBatchEvidenceId");
            List<AttemptDisposition> dispositions = facts.stream()
                .map(AttemptFact::disposition)
                .toList();
            String batchHash = sha256(join(
                "m5-d7-bounded-batch-v1",
                batchId,
                run.tenantId(),
                run.runId(),
                claim.claimBatchId(),
                claim.requestedLimit(),
                claim.claimedAttemptIds(),
                dispositions,
                predecessor
            ));
            batch = new BoundedBatch(
                batchId,
                run.tenantId(),
                run.runId(),
                claim.claimBatchId(),
                claim.requestedLimit(),
                claim.claimedAttemptIds(),
                dispositions,
                predecessor,
                batchHash,
                request.happenedAt(),
                request.requestId(),
                request.traceId()
            );
            insertBatch(batch);
            predecessor = batch.batchEvidenceHash();
        }

        CompletionDecision decision = completionDecision(run, claim, facts);
        OrchestrationEvent event = appendEvent(
            run,
            decision.eventType(),
            decision.pauseReason(),
            null,
            predecessor,
            request.happenedAt(),
            request.requestId(),
            request.traceId()
        );
        appendAudit(
            run,
            "PROCESS_MIGRATION_ORCHESTRATION_FINALIZED",
            request.requestId(),
            request.traceId(),
            request.happenedAt(),
            Map.of(
                "eventType", decision.eventType().name(),
                "pauseReason", decision.pauseReason().name(),
                "processedCount", Integer.toString(request.processedAttemptIds().size()),
                "planExactlyCompleted", Boolean.toString(decision.planExactlyCompleted())
            )
        );
        return new FinalizedOrchestration(
            run,
            event,
            batch,
            decision.pauseReason(),
            decision.planExactlyCompleted(),
            false
        );
    }

    private PreparedOrchestration prepared(
        OrchestrationRun run,
        CanarySelection canary,
        GateDecision gate,
        OrchestrationEvent latest,
        boolean replayed
    ) {
        PauseReason pause = latest.pauseReason() == PauseReason.NONE
            ? gate.pauseReason()
            : latest.pauseReason();
        boolean finalized = terminalEvent(latest.eventType());
        boolean eligible = !finalized
            && latest.eventType() == RunEventType.PREPARED
            && pause == PauseReason.NONE
            && (gate.gate() == CanaryGate.PENDING || gate.gate() == CanaryGate.READY);
        return new PreparedOrchestration(
            run,
            canary,
            gate.gate(),
            pause,
            latest,
            eligible,
            replayed,
            finalized
        );
    }

    private PlanContext lockPlan(String tenantId, UUID intentId) {
        return jdbc.query("""
            select plan.tenant_id,plan.plan_id,plan.plan_hash,plan.selected_instance_count,
              intent.intent_id,selection.approval_instance_id,selection.instance_evidence_hash
            from ap_process_migration_intent intent
            join ap_process_migration_plan plan
              on plan.tenant_id=intent.tenant_id and plan.plan_id=intent.plan_id
             and plan.plan_hash=intent.plan_hash
            join ap_process_migration_plan_instance selection
              on selection.tenant_id=plan.tenant_id and selection.plan_id=plan.plan_id
             and selection.sequence_no=1
            where intent.tenant_id=:tenantId and intent.intent_id=:intentId
              and plan.status='CONSUMED' and intent.status in ('PENDING','RUNNING')
            for update
            """, params("tenantId", tenantId, "intentId", intentId),
            (row, number) -> new PlanContext(
                row.getString("tenant_id"),
                values.uuid(row, "intent_id"),
                values.uuid(row, "plan_id"),
                row.getString("plan_hash"),
                row.getInt("selected_instance_count"),
                values.uuid(row, "approval_instance_id"),
                row.getString("instance_evidence_hash")
            )).stream().findFirst().orElseThrow(() -> conflict(
                "consumed plan and canonical selection were not found"
            ));
    }

    private CanarySelection ensureCanary(PlanContext plan, PrepareRequest request) {
        Optional<CanarySelection> existing = findCanaryOptional(
            plan.tenantId(),
            plan.planId()
        );
        if (existing.isPresent()) {
            CanarySelection canary = existing.orElseThrow();
            if (!canary.intentId().equals(plan.intentId())
                || !canary.approvalInstanceId().equals(plan.canaryInstanceId())
                || !canary.planHash().equals(plan.planHash())
                || !canary.instanceEvidenceHash().equals(plan.canaryEvidenceHash())) {
                throw conflict("persisted canary does not match canonical sealed selection");
            }
            return canary;
        }
        UUID selectionId = nextIdentifier("canarySelectionId");
        String evidenceHash = sha256(join(
            "m5-d7-canary-selection-v1",
            selectionId,
            plan.tenantId(),
            plan.planId(),
            plan.intentId(),
            "CANONICAL_FIRST_V1",
            1,
            plan.canaryInstanceId(),
            plan.planHash(),
            plan.canaryEvidenceHash()
        ));
        CanarySelection canary = new CanarySelection(
            selectionId,
            plan.tenantId(),
            plan.planId(),
            plan.intentId(),
            "CANONICAL_FIRST_V1",
            1,
            plan.canaryInstanceId(),
            plan.planHash(),
            plan.canaryEvidenceHash(),
            evidenceHash,
            request.happenedAt(),
            request.requestId(),
            request.traceId()
        );
        int inserted = jdbc.update("""
            insert into ap_process_migration_canary_selection (
             tenant_id,selection_id,plan_id,intent_id,algorithm_version,sequence_no,
             approval_instance_id,plan_hash,instance_evidence_hash,selection_evidence_hash,
             recorded_at,request_id,trace_id,payload_json
            ) values (
             :tenantId,:selectionId,:planId,:intentId,:algorithmVersion,:sequenceNo,
             :instanceId,:planHash,:instanceEvidenceHash,:selectionEvidenceHash,
             :recordedAt,:requestId,:traceId,:payload
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", canary.tenantId())
                .addValue("selectionId", values.bindUuid(canary.selectionId()))
                .addValue("planId", values.bindUuid(canary.planId()))
                .addValue("intentId", values.bindUuid(canary.intentId()))
                .addValue("algorithmVersion", canary.algorithmVersion())
                .addValue("sequenceNo", canary.sequenceNo())
                .addValue("instanceId", values.bindUuid(canary.approvalInstanceId()))
                .addValue("planHash", canary.planHash())
                .addValue("instanceEvidenceHash", canary.instanceEvidenceHash())
                .addValue("selectionEvidenceHash", canary.selectionEvidenceHash())
                .addValue("recordedAt", values.bindInstant(canonicalInstant(
                    canary.recordedAt()
                )))
                .addValue("requestId", canary.requestId())
                .addValue("traceId", canary.traceId())
                .addValue("payload", json.write(canary)));
        if (inserted != 1) {
            throw conflict("deterministic canary evidence was not inserted");
        }
        return canary;
    }

    private GateDecision canaryGate(String tenantId, UUID intentId, UUID instanceId) {
        Optional<AttemptFact> fact = jdbc.query("""
            select attempt.attempt_id,attempt.status,
              exists(select 1 from ap_process_migration_instance_completion completion
               where completion.tenant_id=attempt.tenant_id
                 and completion.attempt_id=attempt.attempt_id) completed,
              exists(select 1 from ap_process_migration_binding_cas_conflict conflict
               where conflict.tenant_id=attempt.tenant_id
                 and conflict.attempt_id=attempt.attempt_id) conflicted,
              (select reconciliation.status from ap_process_migration_reconciliation reconciliation
               where reconciliation.tenant_id=attempt.tenant_id
                 and reconciliation.attempt_id=attempt.attempt_id
               order by reconciliation.sequence desc limit 1) reconciliation_status
            from ap_process_migration_attempt attempt
            where attempt.tenant_id=:tenantId and attempt.intent_id=:intentId
              and attempt.approval_instance_id=:instanceId and attempt.attempt_number=1
            """, params(
                "tenantId", tenantId,
                "intentId", intentId,
                "instanceId", instanceId
            ), (row, number) -> fact(
                values.uuid(row, "attempt_id"),
                row.getString("status"),
                row.getBoolean("completed"),
                row.getBoolean("conflicted"),
                row.getString("reconciliation_status")
            )).stream().findFirst();
        if (fact.isEmpty()) {
            return new GateDecision(CanaryGate.PENDING, PauseReason.NONE);
        }
        AttemptDisposition disposition = fact.orElseThrow().disposition();
        return switch (disposition) {
            case EXACTLY_COMPLETED -> new GateDecision(CanaryGate.READY, PauseReason.NONE);
            case UNKNOWN -> new GateDecision(CanaryGate.PAUSED, PauseReason.CANARY_UNKNOWN);
            case RECONCILING -> new GateDecision(
                CanaryGate.PAUSED,
                PauseReason.CANARY_RECONCILIATION
            );
            case MANUAL_REVIEW_REQUIRED -> new GateDecision(
                CanaryGate.PAUSED,
                PauseReason.CANARY_MANUAL_REVIEW
            );
            case BINDING_CONFLICT -> new GateDecision(
                CanaryGate.PAUSED,
                PauseReason.CANARY_BINDING_CONFLICT
            );
            case TERMINAL_FAILURE -> new GateDecision(
                CanaryGate.PAUSED,
                PauseReason.TERMINAL_FAILURE
            );
            case IN_FLIGHT, KILL_SWITCH_BLOCKED -> new GateDecision(
                CanaryGate.RUNNING,
                PauseReason.CANARY_IN_FLIGHT
            );
        };
    }

    private CompletionDecision completionDecision(
        OrchestrationRun run,
        ApprovalMigrationClaimBatch claim,
        List<AttemptFact> facts
    ) {
        if (claim == null || facts.isEmpty()) {
            return new CompletionDecision(RunEventType.PAUSED, PauseReason.EMPTY_BATCH, false);
        }
        Optional<AttemptDisposition> firstUnresolved = facts.stream()
            .map(AttemptFact::disposition)
            .filter(value -> value != AttemptDisposition.EXACTLY_COMPLETED)
            .findFirst();
        if (firstUnresolved.isPresent()) {
            PauseReason pause = pauseFor(run.phase(), firstUnresolved.orElseThrow());
            return new CompletionDecision(RunEventType.PAUSED, pause, false);
        }
        if (run.phase() == OrchestrationPhase.CANARY) {
            return new CompletionDecision(
                RunEventType.CANARY_COMPLETED,
                PauseReason.NONE,
                false
            );
        }
        Integer selected = jdbc.queryForObject("""
            select selected_instance_count from ap_process_migration_plan
            where tenant_id=:tenantId and plan_id=:planId
            """, params("tenantId", run.tenantId(), "planId", run.planId()), Integer.class);
        Integer completed = jdbc.queryForObject("""
            select count(*) from ap_process_migration_instance_completion
            where tenant_id=:tenantId and intent_id=:intentId
            """, params("tenantId", run.tenantId(), "intentId", run.intentId()), Integer.class);
        boolean allCompleted = selected != null
            && completed != null
            && selected.equals(completed);
        return new CompletionDecision(
            allCompleted ? RunEventType.COMPLETED : RunEventType.BATCH_RECORDED,
            PauseReason.NONE,
            allCompleted
        );
    }

    private List<AttemptFact> attemptFacts(OrchestrationRun run, List<UUID> attemptIds) {
        List<AttemptFact> facts = new ArrayList<>();
        for (UUID attemptId : attemptIds) {
            facts.add(jdbc.query("""
                select attempt.attempt_id,attempt.status,
                  exists(select 1 from ap_process_migration_instance_completion completion
                   where completion.tenant_id=attempt.tenant_id
                     and completion.attempt_id=attempt.attempt_id) completed,
                  exists(select 1 from ap_process_migration_binding_cas_conflict conflict
                   where conflict.tenant_id=attempt.tenant_id
                     and conflict.attempt_id=attempt.attempt_id) conflicted,
                  (select reconciliation.status from ap_process_migration_reconciliation reconciliation
                   where reconciliation.tenant_id=attempt.tenant_id
                     and reconciliation.attempt_id=attempt.attempt_id
                   order by reconciliation.sequence desc limit 1) reconciliation_status
                from ap_process_migration_attempt attempt
                where attempt.tenant_id=:tenantId and attempt.intent_id=:intentId
                  and attempt.attempt_id=:attemptId
                """, params(
                    "tenantId", run.tenantId(),
                    "intentId", run.intentId(),
                    "attemptId", attemptId
                ), (row, number) -> fact(
                    values.uuid(row, "attempt_id"),
                    row.getString("status"),
                    row.getBoolean("completed"),
                    row.getBoolean("conflicted"),
                    row.getString("reconciliation_status")
                )).stream().findFirst().orElseThrow(() -> conflict(
                    "claimed attempt is outside the orchestration intent"
                )));
        }
        return List.copyOf(facts);
    }

    private static AttemptFact fact(
        UUID attemptId,
        String status,
        boolean completed,
        boolean conflicted,
        String reconciliationStatus
    ) {
        AttemptDisposition disposition;
        if (completed && "SUCCEEDED".equals(status) && !conflicted) {
            disposition = AttemptDisposition.EXACTLY_COMPLETED;
        } else if (conflicted) {
            disposition = AttemptDisposition.BINDING_CONFLICT;
        } else if ("UNKNOWN".equals(status)) {
            disposition = AttemptDisposition.UNKNOWN;
        } else if ("RECONCILING".equals(status)) {
            disposition = "MANUAL_REVIEW_REQUIRED".equals(reconciliationStatus)
                ? AttemptDisposition.MANUAL_REVIEW_REQUIRED
                : AttemptDisposition.RECONCILING;
        } else if (List.of(
            "BLOCKED_STALE",
            "FAILED_RETRYABLE",
            "FAILED_TERMINAL",
            "CANCELLED"
        ).contains(status)) {
            disposition = AttemptDisposition.TERMINAL_FAILURE;
        } else {
            disposition = AttemptDisposition.IN_FLIGHT;
        }
        return new AttemptFact(attemptId, disposition);
    }

    private void requireClaim(
        OrchestrationRun run,
        ApprovalMigrationClaimBatch claim,
        List<UUID> processed
    ) {
        if (!claim.tenantId().equals(run.tenantId())
            || !claim.intentId().equals(run.intentId())
            || claim.requestedLimit() > run.requestedLimit()
            || processed.size() > claim.claimedAttemptIds().size()
            || !processed.equals(claim.claimedAttemptIds().subList(0, processed.size()))) {
            throw conflict("D2 claim evidence does not match the bounded orchestration run");
        }
    }

    private void requireClaimedAttempt(
        OrchestrationRun run,
        UUID attemptId,
        Instant happenedAt
    ) {
        Integer count = jdbc.queryForObject("""
            select count(*) from ap_process_migration_attempt attempt
            join ap_approval_instance_command_fence fence
              on fence.tenant_id=attempt.tenant_id and fence.attempt_id=attempt.attempt_id
            where attempt.tenant_id=:tenantId and attempt.intent_id=:intentId
              and attempt.attempt_id=:attemptId and attempt.status='CLAIMED'
              and attempt.lease_until>:happenedAt and fence.status='ACTIVE'
              and fence.lease_until>:happenedAt and fence.lease_owner=attempt.lease_owner
            """, params(
                "tenantId", run.tenantId(),
                "intentId", run.intentId(),
                "attemptId", attemptId,
                "happenedAt", happenedAt
            ), Integer.class);
        if (count == null || count != 1) {
            throw conflict("dispatch requires the exact current D2 claim and command fence");
        }
    }

    private void insertRun(OrchestrationRun run) {
        int inserted = jdbc.update("""
            insert into ap_process_migration_orchestration_run (
             tenant_id,run_id,plan_id,intent_id,run_revision,phase,requested_limit,
             canary_selection_id,expected_kill_switch_revision,predecessor_hash,request_hash,
             run_evidence_hash,started_at,request_id,trace_id,payload_json
            ) values (
             :tenantId,:runId,:planId,:intentId,:runRevision,:phase,:requestedLimit,
             :canarySelectionId,:killSwitchRevision,:predecessorHash,:requestHash,
             :runEvidenceHash,:startedAt,:requestId,:traceId,:payload
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", run.tenantId())
                .addValue("runId", values.bindUuid(run.runId()))
                .addValue("planId", values.bindUuid(run.planId()))
                .addValue("intentId", values.bindUuid(run.intentId()))
                .addValue("runRevision", run.runRevision())
                .addValue("phase", run.phase().name())
                .addValue("requestedLimit", run.requestedLimit())
                .addValue("canarySelectionId", values.bindUuid(run.canarySelectionId()))
                .addValue("killSwitchRevision", run.expectedKillSwitchRevision())
                .addValue("predecessorHash", run.predecessorHash())
                .addValue("requestHash", run.requestHash())
                .addValue("runEvidenceHash", run.runEvidenceHash())
                .addValue("startedAt", values.bindInstant(canonicalInstant(run.startedAt())))
                .addValue("requestId", run.requestId())
                .addValue("traceId", run.traceId())
                .addValue("payload", json.write(run)));
        if (inserted != 1) {
            throw conflict("orchestration run evidence was not inserted");
        }
    }

    private void insertObservation(KillSwitchObservation observation) {
        int inserted = jdbc.update("""
            insert into ap_process_migration_kill_switch_observation (
             tenant_id,observation_id,run_id,attempt_id,expected_revision,observed_revision,
             switch_enabled,dispatch_allowed,reason_code,request_hash,observation_evidence_hash,
             observed_at,request_id,trace_id,payload_json
            ) values (
             :tenantId,:observationId,:runId,:attemptId,:expectedRevision,:observedRevision,
             :enabled,:allowed,:reasonCode,:requestHash,:evidenceHash,
             :observedAt,:requestId,:traceId,:payload
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", observation.tenantId())
                .addValue("observationId", values.bindUuid(observation.observationId()))
                .addValue("runId", values.bindUuid(observation.runId()))
                .addValue("attemptId", values.bindUuid(observation.attemptId()))
                .addValue("expectedRevision", observation.expectedRevision())
                .addValue("observedRevision", observation.observedRevision())
                .addValue("enabled", observation.enabled())
                .addValue("allowed", observation.dispatchAllowed())
                .addValue("reasonCode", observation.reasonCode())
                .addValue("requestHash", observation.requestHash())
                .addValue("evidenceHash", observation.observationEvidenceHash())
                .addValue("observedAt", values.bindInstant(canonicalInstant(
                    observation.observedAt()
                )))
                .addValue("requestId", observation.requestId())
                .addValue("traceId", observation.traceId())
                .addValue("payload", json.write(observation)));
        if (inserted != 1) {
            throw conflict("kill-switch observation evidence was not inserted");
        }
    }

    private void insertBatch(BoundedBatch batch) {
        int inserted = jdbc.update("""
            insert into ap_process_migration_orchestration_batch (
             tenant_id,batch_evidence_id,run_id,claim_batch_id,requested_limit,
             attempt_ids,dispositions,predecessor_hash,batch_evidence_hash,
             recorded_at,request_id,trace_id,payload_json
            ) values (
             :tenantId,:batchId,:runId,:claimBatchId,:requestedLimit,
             :attemptIds,:dispositions,:predecessorHash,:batchHash,
             :recordedAt,:requestId,:traceId,:payload
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", batch.tenantId())
                .addValue("batchId", values.bindUuid(batch.batchEvidenceId()))
                .addValue("runId", values.bindUuid(batch.runId()))
                .addValue("claimBatchId", values.bindUuid(batch.claimBatchId()))
                .addValue("requestedLimit", batch.requestedLimit())
                .addValue("attemptIds", json.write(batch.attemptIds()))
                .addValue("dispositions", json.write(batch.dispositions()))
                .addValue("predecessorHash", batch.predecessorHash())
                .addValue("batchHash", batch.batchEvidenceHash())
                .addValue("recordedAt", values.bindInstant(canonicalInstant(
                    batch.recordedAt()
                )))
                .addValue("requestId", batch.requestId())
                .addValue("traceId", batch.traceId())
                .addValue("payload", json.write(batch)));
        if (inserted != 1) {
            throw conflict("bounded orchestration batch evidence was not inserted");
        }
    }

    private OrchestrationEvent appendEvent(
        OrchestrationRun run,
        RunEventType type,
        PauseReason pause,
        UUID attemptId,
        String predecessor,
        Instant happenedAt,
        String requestId,
        String traceId
    ) {
        long sequence = currentEventSequence(run.tenantId(), run.runId()) + 1;
        UUID eventId = nextIdentifier("orchestrationEventId");
        String eventHash = sha256(join(
            "m5-d7-orchestration-event-v1",
            eventId,
            run.tenantId(),
            run.runId(),
            sequence,
            type,
            pause,
            attemptId,
            predecessor
        ));
        OrchestrationEvent event = new OrchestrationEvent(
            eventId,
            run.tenantId(),
            run.runId(),
            sequence,
            type,
            pause,
            attemptId,
            predecessor,
            eventHash,
            happenedAt,
            requestId,
            traceId
        );
        int inserted = jdbc.update("""
            insert into ap_process_migration_orchestration_event (
             tenant_id,event_id,run_id,sequence,event_type,pause_reason,attempt_id,
             predecessor_hash,event_evidence_hash,happened_at,request_id,trace_id,payload_json
            ) values (
             :tenantId,:eventId,:runId,:sequence,:eventType,:pauseReason,:attemptId,
             :predecessorHash,:eventHash,:happenedAt,:requestId,:traceId,:payload
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", event.tenantId())
                .addValue("eventId", values.bindUuid(event.eventId()))
                .addValue("runId", values.bindUuid(event.runId()))
                .addValue("sequence", event.sequence())
                .addValue("eventType", event.eventType().name())
                .addValue("pauseReason", event.pauseReason().name())
                .addValue("attemptId", values.bindNullableUuid(event.attemptId()))
                .addValue("predecessorHash", event.predecessorHash())
                .addValue("eventHash", event.eventEvidenceHash())
                .addValue("happenedAt", values.bindInstant(canonicalInstant(
                    event.happenedAt()
                )))
                .addValue("requestId", event.requestId())
                .addValue("traceId", event.traceId())
                .addValue("payload", json.write(event)));
        if (inserted != 1) {
            throw conflict("orchestration event evidence was not inserted");
        }
        return event;
    }

    private Optional<OrchestrationRun> findRunByRequestHash(
        String tenantId,
        String requestHash
    ) {
        return queryOne(
            "select payload_json from ap_process_migration_orchestration_run "
                + "where tenant_id=:tenantId and request_hash=:requestHash",
            params("tenantId", tenantId, "requestHash", requestHash),
            OrchestrationRun.class
        );
    }

    private OrchestrationRun findRun(String tenantId, UUID runId) {
        return queryOne(
            "select payload_json from ap_process_migration_orchestration_run "
                + "where tenant_id=:tenantId and run_id=:runId",
            params("tenantId", tenantId, "runId", runId),
            OrchestrationRun.class
        ).orElseThrow(() -> conflict("orchestration run was not found"));
    }

    private Optional<CanarySelection> findCanaryOptional(String tenantId, UUID planId) {
        return queryOne(
            "select payload_json from ap_process_migration_canary_selection "
                + "where tenant_id=:tenantId and plan_id=:planId",
            params("tenantId", tenantId, "planId", planId),
            CanarySelection.class
        );
    }

    private CanarySelection findCanary(String tenantId, UUID planId) {
        return findCanaryOptional(tenantId, planId).orElseThrow(() -> conflict(
            "deterministic canary evidence was not found"
        ));
    }

    private OrchestrationEvent latestEvent(String tenantId, UUID runId) {
        return queryOne(
            "select payload_json from ap_process_migration_orchestration_event "
                + "where tenant_id=:tenantId and run_id=:runId "
                + "order by sequence desc limit 1",
            params("tenantId", tenantId, "runId", runId),
            OrchestrationEvent.class
        ).orElseThrow(() -> conflict("orchestration event lineage was not found"));
    }

    private OrchestrationEvent findAttemptEvent(
        String tenantId,
        UUID runId,
        UUID attemptId
    ) {
        return queryOne(
            "select payload_json from ap_process_migration_orchestration_event "
                + "where tenant_id=:tenantId and run_id=:runId and attempt_id=:attemptId "
                + "order by sequence desc limit 1",
            params(
                "tenantId", tenantId,
                "runId", runId,
                "attemptId", attemptId
            ),
            OrchestrationEvent.class
        ).orElseThrow(() -> conflict("dispatch event was not found"));
    }

    private Optional<KillSwitchObservation> findObservation(
        String tenantId,
        UUID runId,
        UUID attemptId
    ) {
        return queryOne(
            "select payload_json from ap_process_migration_kill_switch_observation "
                + "where tenant_id=:tenantId and run_id=:runId and attempt_id=:attemptId",
            params(
                "tenantId", tenantId,
                "runId", runId,
                "attemptId", attemptId
            ),
            KillSwitchObservation.class
        );
    }

    private Optional<BoundedBatch> findBatch(String tenantId, UUID runId) {
        return queryOne(
            "select payload_json from ap_process_migration_orchestration_batch "
                + "where tenant_id=:tenantId and run_id=:runId",
            params("tenantId", tenantId, "runId", runId),
            BoundedBatch.class
        );
    }

    private <T> Optional<T> queryOne(
        String sql,
        MapSqlParameterSource parameters,
        Class<T> type
    ) {
        return jdbc.query(sql, parameters, (row, number) -> json.read(
            row.getString(1),
            type
        )).stream().findFirst();
    }

    private long currentRunRevision(String tenantId, UUID intentId) {
        Long value = jdbc.queryForObject(
            "select coalesce(max(run_revision),0) "
                + "from ap_process_migration_orchestration_run "
                + "where tenant_id=:tenantId and intent_id=:intentId",
            params("tenantId", tenantId, "intentId", intentId),
            Long.class
        );
        return value == null ? 0 : value;
    }

    private String latestRunHash(String tenantId, UUID intentId) {
        return jdbc.query(
            "select run_evidence_hash from ap_process_migration_orchestration_run "
                + "where tenant_id=:tenantId and intent_id=:intentId "
                + "order by run_revision desc limit 1",
            params("tenantId", tenantId, "intentId", intentId),
            (row, number) -> row.getString(1)
        ).stream().findFirst().orElseThrow(() -> conflict(
            "run predecessor was not found"
        ));
    }

    private long currentEventSequence(String tenantId, UUID runId) {
        Long value = jdbc.queryForObject(
            "select coalesce(max(sequence),0) "
                + "from ap_process_migration_orchestration_event "
                + "where tenant_id=:tenantId and run_id=:runId",
            params("tenantId", tenantId, "runId", runId),
            Long.class
        );
        return value == null ? 0 : value;
    }

    private void requireRunReplay(OrchestrationRun run, PrepareRequest request) {
        if (!run.tenantId().equals(request.tenantId())
            || !run.intentId().equals(request.intentId())
            || run.requestedLimit() != request.requestedLimit()
            || run.runRevision() != request.expectedRunRevision()
            || run.expectedKillSwitchRevision() != request.killSwitch().revision()) {
            throw conflict("changed orchestration replay is forbidden");
        }
    }

    private static PauseReason initialPause(GateDecision gate, Snapshot killSwitch) {
        if (killSwitch.enabled()) {
            return PauseReason.KILL_SWITCH_ACTIVE;
        }
        return gate.gate() == CanaryGate.PENDING || gate.gate() == CanaryGate.READY
            ? PauseReason.NONE
            : gate.pauseReason();
    }

    private static PauseReason pauseFor(
        OrchestrationPhase phase,
        AttemptDisposition disposition
    ) {
        return switch (disposition) {
            case UNKNOWN -> phase == OrchestrationPhase.CANARY
                ? PauseReason.CANARY_UNKNOWN
                : PauseReason.MISSING_OR_INCOMPLETE_EVIDENCE;
            case RECONCILING -> phase == OrchestrationPhase.CANARY
                ? PauseReason.CANARY_RECONCILIATION
                : PauseReason.MISSING_OR_INCOMPLETE_EVIDENCE;
            case MANUAL_REVIEW_REQUIRED -> phase == OrchestrationPhase.CANARY
                ? PauseReason.CANARY_MANUAL_REVIEW
                : PauseReason.MISSING_OR_INCOMPLETE_EVIDENCE;
            case BINDING_CONFLICT -> phase == OrchestrationPhase.CANARY
                ? PauseReason.CANARY_BINDING_CONFLICT
                : PauseReason.MISSING_OR_INCOMPLETE_EVIDENCE;
            case TERMINAL_FAILURE -> PauseReason.TERMINAL_FAILURE;
            case IN_FLIGHT, KILL_SWITCH_BLOCKED -> phase == OrchestrationPhase.CANARY
                ? PauseReason.CANARY_IN_FLIGHT
                : PauseReason.MISSING_OR_INCOMPLETE_EVIDENCE;
            case EXACTLY_COMPLETED -> PauseReason.NONE;
        };
    }

    private static boolean terminalEvent(RunEventType type) {
        return switch (type) {
            case PREPARED, DISPATCH_ALLOWED -> false;
            case KILL_SWITCH_BLOCKED,
                 CANARY_COMPLETED,
                 BATCH_RECORDED,
                 PAUSED,
                 COMPLETED -> true;
        };
    }

    private String prepareRequestHash(PrepareRequest request) {
        return sha256(join(
            "m5-d7-orchestration-request-v1",
            request.tenantId(),
            request.intentId(),
            request.requestedLimit(),
            request.expectedRunRevision(),
            request.killSwitch().revision(),
            request.killSwitch().evidenceHash(),
            request.requestId(),
            request.traceId()
        ));
    }

    private String dispatchRequestHash(DispatchRequest request) {
        return sha256(join(
            "m5-d7-dispatch-observation-request-v1",
            request.run().tenantId(),
            request.run().runId(),
            request.attemptId(),
            request.expectedRunRevision(),
            request.expectedKillSwitchRevision(),
            request.observedKillSwitch().revision(),
            request.observedKillSwitch().evidenceHash(),
            request.requestId(),
            request.traceId()
        ));
    }

    private void appendAudit(
        OrchestrationRun run,
        String action,
        PrepareRequest request,
        Map<String, String> attributes
    ) {
        appendAudit(
            run,
            action,
            request.requestId(),
            request.traceId(),
            request.happenedAt(),
            attributes
        );
    }

    private void appendAudit(
        OrchestrationRun run,
        String action,
        String requestId,
        String traceId,
        Instant happenedAt,
        Map<String, String> attributes
    ) {
        auditEvents.append(new AuditEvent(
            nextIdentifier("auditEventId"),
            run.tenantId(),
            "server:m5-orchestration",
            action,
            "PROCESS_MIGRATION_ORCHESTRATION",
            run.runId().toString(),
            requestId,
            traceId,
            happenedAt,
            attributes
        ));
    }

    private <T> T execute(String message, Supplier<T> operation) {
        try {
            T value = transactions.execute(status -> operation.get());
            return Objects.requireNonNull(value, "transaction returned null");
        } catch (OrchestrationConflictException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw conflict(message, exception);
        }
    }

    private UUID nextIdentifier(String name) {
        return Objects.requireNonNull(
            identifiers.get(),
            name + " supplier returned null"
        );
    }

    private MapSqlParameterSource params(Object... pairs) {
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        for (int index = 0; index < pairs.length; index += 2) {
            parameters.addValue((String) pairs[index], bind(pairs[index + 1]));
        }
        return parameters;
    }

    private Object bind(Object value) {
        if (value instanceof UUID uuid) {
            return values.bindUuid(uuid);
        }
        if (value instanceof Instant instant) {
            return values.bindInstant(canonicalInstant(instant));
        }
        return value;
    }

    private static Instant canonicalInstant(Instant value) {
        return AuditHashCanonicalizer.canonicalInstant(value);
    }

    private static String intentLockScope(String tenantId, UUID intentId) {
        return LOCK_NAMESPACE + "intent:" + tenantId + ':' + intentId;
    }

    private static String runLockScope(String tenantId, UUID runId) {
        return LOCK_NAMESPACE + "run:" + tenantId + ':' + runId;
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

    private static OrchestrationConflictException conflict(String message) {
        return new OrchestrationConflictException(message);
    }

    private static OrchestrationConflictException conflict(
        String message,
        Throwable cause
    ) {
        return new OrchestrationConflictException(message, cause);
    }

    private record PlanContext(
        String tenantId,
        UUID intentId,
        UUID planId,
        String planHash,
        int selectedCount,
        UUID canaryInstanceId,
        String canaryEvidenceHash
    ) {
    }

    private record GateDecision(CanaryGate gate, PauseReason pauseReason) {
    }

    private record AttemptFact(UUID attemptId, AttemptDisposition disposition) {
    }

    private record CompletionDecision(
        RunEventType eventType,
        PauseReason pauseReason,
        boolean planExactlyCompleted
    ) {
    }
}
