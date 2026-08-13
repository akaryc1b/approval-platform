package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationEngineExecutionStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttemptEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttemptTransition;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationCommandFence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.EngineOutcome;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.FailureClass;
import io.github.akaryc1b.approval.engine.ProcessInstanceMigrationPort;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** PostgreSQL short transactions for one immutable migration request and one outcome. */
public final class JdbcApprovalMigrationEngineExecutionStore
    implements ApprovalMigrationEngineExecutionStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcApprovalMigrationJson json;
    private final JdbcApprovalMigrationProtocolStore protocol;
    private final JdbcApprovalInstanceCommandFence commandFence;
    private final TransactionTemplate transactions;
    private final AuditEventSink auditEvents;
    private final Supplier<UUID> identifiers;

    public JdbcApprovalMigrationEngineExecutionStore(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager,
        AuditEventSink auditEvents,
        Supplier<UUID> identifiers
    ) {
        DataSource source = Objects.requireNonNull(dataSource, "dataSource must not be null");
        ObjectMapper mapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        PlatformTransactionManager manager = Objects.requireNonNull(
            transactionManager,
            "transactionManager must not be null"
        );
        jdbc = new NamedParameterJdbcTemplate(source);
        json = new JdbcApprovalMigrationJson(mapper);
        protocol = new JdbcApprovalMigrationProtocolStore(source, mapper, manager);
        commandFence = new JdbcApprovalInstanceCommandFence(source);
        transactions = new TransactionTemplate(manager);
        this.auditEvents = Objects.requireNonNull(auditEvents, "auditEvents must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
    }

    @Override
    public PreparedDispatch prepare(PrepareRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        try {
            return transactions.execute(status -> prepareOnce(request));
        } catch (DataAccessException exception) {
            throw new ExecutionConflictException("migration engine request persistence conflict", exception);
        }
    }

    @Override
    public ApprovalMigrationAttempt finalizeOutcome(FinalizeRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        try {
            return transactions.execute(status -> finalizeOnce(request));
        } catch (DataAccessException exception) {
            throw new ExecutionConflictException("migration engine outcome persistence conflict", exception);
        }
    }

    private PreparedDispatch prepareOnce(PrepareRequest request) {
        ApprovalMigrationAttempt current = lockAttempt(request.tenantId(), request.attemptId());
        requireClaimAuthority(current, request);
        commandFence.acquireMigrationLock(current.tenantId(), current.approvalInstanceId());
        ApprovalMigrationCommandFence fence = lockFence(current.tenantId(), current.attemptId());
        requireFenceAuthority(fence, request.workerId(), request.expectedFenceRevision(), request.happenedAt());

        RuntimeBindingIdentity binding = lockRuntimeBinding(current);
        TargetIdentity target = lockTargetIdentity(current);
        UUID engineRequestId = nextIdentifier("engineRequestId");
        ProcessInstanceMigrationPort.MigrationCommand command = new ProcessInstanceMigrationPort.MigrationCommand(
            current.tenantId(),
            current.approvalInstanceId(),
            current.attemptId(),
            current.engineInstanceId(),
            current.sourceEngineDefinitionId(),
            target.engineDeploymentId(),
            target.engineDefinitionId(),
            List.of()
        );
        String requestHash = sha256(String.join(
            "|",
            "m5-engine-request-v1",
            current.tenantId(),
            current.intentId().toString(),
            current.attemptId().toString(),
            current.approvalInstanceId().toString(),
            current.engineInstanceId(),
            binding.bindingEvidenceHash(),
            current.sourceEngineDefinitionId(),
            Integer.toString(target.releaseVersion()),
            target.packageHash(),
            target.engineDeploymentId(),
            target.engineDefinitionId(),
            Long.toString(current.revision()),
            fence.fenceId().toString(),
            Long.toString(fence.revision())
        ));
        String evidenceHash = sha256(
            "m5-engine-request-evidence-v1|" + engineRequestId + '|' + requestHash
        );
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("engineRequestId", engineRequestId);
        payload.put("tenantId", current.tenantId());
        payload.put("intentId", current.intentId());
        payload.put("attemptId", current.attemptId());
        payload.put("approvalInstanceId", current.approvalInstanceId());
        payload.put("workerId", request.workerId());
        payload.put("attemptRevision", current.revision());
        payload.put("fenceId", fence.fenceId());
        payload.put("fenceRevision", fence.revision());
        payload.put("engineInstanceId", current.engineInstanceId());
        payload.put("sourceBindingEvidenceHash", binding.bindingEvidenceHash());
        payload.put("sourceEngineDefinitionId", current.sourceEngineDefinitionId());
        payload.put("targetReleaseVersion", target.releaseVersion());
        payload.put("targetPackageHash", target.packageHash());
        payload.put("targetEngineDeploymentId", target.engineDeploymentId());
        payload.put("targetEngineDefinitionId", target.engineDefinitionId());
        payload.put("activityMappings", List.of());
        payload.put("requestHash", requestHash);
        payload.put("evidenceHash", evidenceHash);
        payload.put("requestedAt", request.happenedAt());
        payload.put("requestId", request.requestId());
        payload.put("traceId", request.traceId());
        insertEngineRequest(
            current,
            fence,
            target,
            engineRequestId,
            request,
            requestHash,
            evidenceHash,
            payload
        );

        ApprovalMigrationAttempt next = current.transitioned(new ApprovalMigrationAttemptTransition(
            AttemptStatus.ENGINE_REQUESTED,
            EngineOutcome.NOT_REQUESTED,
            null,
            null,
            engineRequestId.toString(),
            FailureClass.NONE,
            null,
            request.happenedAt()
        ));
        ApprovalMigrationAttempt stored = protocol.transitionAttempt(
            next,
            current.revision(),
            request.workerId(),
            event(current, next, request.requestId(), request.traceId())
        );
        appendAudit(
            current.tenantId(),
            request.workerId(),
            "PROCESS_MIGRATION_ENGINE_REQUESTED",
            current.attemptId().toString(),
            request.requestId(),
            request.traceId(),
            request.happenedAt(),
            Map.of(
                "engineRequestId", engineRequestId.toString(),
                "requestHash", requestHash,
                "fenceRevision", Long.toString(fence.revision())
            )
        );
        return new PreparedDispatch(
            engineRequestId,
            evidenceHash,
            stored,
            fence.revision(),
            command,
            request.happenedAt(),
            request.requestId(),
            request.traceId()
        );
    }

    private ApprovalMigrationAttempt finalizeOnce(FinalizeRequest request) {
        PreparedDispatch prepared = request.prepared();
        ApprovalMigrationAttempt current = lockAttempt(
            prepared.attempt().tenantId(),
            prepared.attempt().attemptId()
        );
        if (current.status() != AttemptStatus.ENGINE_REQUESTED
            || current.revision() != prepared.attempt().revision()
            || !prepared.engineRequestId().toString().equals(current.engineRequestReference())) {
            throw conflict("migration attempt is not the exact requested revision");
        }
        commandFence.acquireMigrationLock(current.tenantId(), current.approvalInstanceId());
        ApprovalMigrationCommandFence fence = lockFence(current.tenantId(), current.attemptId());
        requireFenceAuthority(
            fence,
            prepared.engineCommand().attemptId().equals(current.attemptId())
                ? findRequestWorker(current.tenantId(), prepared.engineRequestId())
                : "",
            prepared.fenceRevision(),
            request.happenedAt()
        );

        UUID outcomeId = nextIdentifier("engineOutcomeId");
        String outcomeHash = sha256(String.join(
            "|",
            "m5-engine-outcome-v1",
            outcomeId.toString(),
            prepared.engineRequestId().toString(),
            current.attemptId().toString(),
            request.disposition().name(),
            Boolean.toString(request.engineCallAttempted()),
            Boolean.toString(request.engineCallReturned()),
            Boolean.toString(request.engineCallMayHaveOccurred()),
            request.stableCode(),
            request.preDispatchSnapshotHash()
        ));
        insertEngineOutcome(outcomeId, current, fence, request, outcomeHash);

        AttemptStatus nextStatus;
        EngineOutcome engineOutcome;
        FailureClass failureClass;
        String errorSummary;
        switch (request.disposition()) {
            case CALL_RETURNED_AWAITING_VERIFICATION -> {
                nextStatus = AttemptStatus.VERIFYING;
                engineOutcome = EngineOutcome.ACCEPTED;
                failureClass = FailureClass.NONE;
                errorSummary = null;
            }
            case PRE_DISPATCH_REJECTED, ENGINE_REJECTED -> {
                nextStatus = AttemptStatus.FAILED_TERMINAL;
                engineOutcome = EngineOutcome.REJECTED;
                failureClass = FailureClass.ENGINE_REJECTED;
                errorSummary = boundedFailure(request);
            }
            case AMBIGUOUS_UNKNOWN -> {
                nextStatus = AttemptStatus.UNKNOWN;
                engineOutcome = EngineOutcome.UNKNOWN;
                failureClass = FailureClass.ENGINE_OUTCOME_UNKNOWN;
                errorSummary = boundedFailure(request);
            }
            default -> throw new IllegalStateException("unsupported engine finalization disposition");
        }
        String nextRequestReference = engineOutcome == EngineOutcome.REJECTED
            ? null
            : current.engineRequestReference();
        ApprovalMigrationAttempt next = current.transitioned(new ApprovalMigrationAttemptTransition(
            nextStatus,
            engineOutcome,
            null,
            null,
            nextRequestReference,
            failureClass,
            errorSummary,
            request.happenedAt()
        ));
        ApprovalMigrationAttempt stored = protocol.transitionAttempt(
            next,
            current.revision(),
            event(current, next, prepared.requestId(), prepared.traceId())
        );
        appendAudit(
            current.tenantId(),
            findRequestWorker(current.tenantId(), prepared.engineRequestId()),
            "PROCESS_MIGRATION_ENGINE_OUTCOME_RECORDED",
            current.attemptId().toString(),
            prepared.requestId(),
            prepared.traceId(),
            request.happenedAt(),
            Map.of(
                "engineRequestId", prepared.engineRequestId().toString(),
                "disposition", request.disposition().name(),
                "outcomeHash", outcomeHash
            )
        );
        return stored;
    }

    private ApprovalMigrationAttempt lockAttempt(String tenantId, UUID attemptId) {
        return jdbc.query("""
            select payload_json::text from ap_process_migration_attempt
            where tenant_id=:tenantId and attempt_id=:attemptId for update
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("attemptId", attemptId),
            (row, number) -> json.read(row.getString(1), ApprovalMigrationAttempt.class))
            .stream().findFirst().orElseThrow(() -> conflict("migration attempt does not exist"));
    }

    private ApprovalMigrationCommandFence lockFence(String tenantId, UUID attemptId) {
        return jdbc.query("""
            select payload_json::text from ap_approval_instance_command_fence
            where tenant_id=:tenantId and attempt_id=:attemptId and status='ACTIVE'
            for update
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("attemptId", attemptId),
            (row, number) -> json.read(row.getString(1), ApprovalMigrationCommandFence.class))
            .stream().findFirst().orElseThrow(() -> conflict("active migration command fence is missing"));
    }

    private RuntimeBindingIdentity lockRuntimeBinding(ApprovalMigrationAttempt attempt) {
        return jdbc.query("""
            select binding_evidence_hash,engine_instance_id,engine_definition_id
            from ap_process_runtime_binding
            where tenant_id=:tenantId and approval_instance_id=:instanceId
            for update
            """, new MapSqlParameterSource()
                .addValue("tenantId", attempt.tenantId())
                .addValue("instanceId", attempt.approvalInstanceId()),
            (row, number) -> new RuntimeBindingIdentity(
                row.getString("binding_evidence_hash"),
                row.getString("engine_instance_id"),
                row.getString("engine_definition_id")
            )).stream().findFirst()
            .filter(value -> value.bindingEvidenceHash().equals(attempt.expectedBindingEvidenceHash())
                && value.engineInstanceId().equals(attempt.engineInstanceId())
                && value.engineDefinitionId().equals(attempt.sourceEngineDefinitionId()))
            .orElseThrow(() -> conflict("source runtime binding is stale"));
    }

    private TargetIdentity lockTargetIdentity(ApprovalMigrationAttempt attempt) {
        return jdbc.query("""
            select p.target_release_version,p.target_package_hash,
                   p.target_engine_deployment_id,p.target_engine_definition_id
            from ap_process_migration_intent i
            join ap_process_migration_plan p
              on p.tenant_id=i.tenant_id and p.plan_id=i.plan_id and p.plan_hash=i.plan_hash
            join ap_process_migration_plan_consumption c
              on c.tenant_id=i.tenant_id and c.intent_id=i.intent_id and c.plan_id=i.plan_id
            where i.tenant_id=:tenantId and i.intent_id=:intentId
              and i.status='RUNNING' and p.status='CONSUMED'
            for update of i,p
            """, new MapSqlParameterSource()
                .addValue("tenantId", attempt.tenantId())
                .addValue("intentId", attempt.intentId()),
            (row, number) -> new TargetIdentity(
                row.getInt("target_release_version"),
                row.getString("target_package_hash"),
                row.getString("target_engine_deployment_id"),
                row.getString("target_engine_definition_id")
            )).stream().findFirst()
            .filter(value -> value.engineDefinitionId().equals(attempt.targetEngineDefinitionId()))
            .orElseThrow(() -> conflict("target release or engine definition is stale"));
    }

    private void requireClaimAuthority(ApprovalMigrationAttempt attempt, PrepareRequest request) {
        if (attempt.status() != AttemptStatus.CLAIMED
            || attempt.revision() != request.expectedAttemptRevision()
            || !request.workerId().equals(attempt.leaseOwner())
            || attempt.leaseUntil() == null
            || !attempt.leaseUntil().isAfter(request.happenedAt())) {
            throw conflict("migration attempt claim authority is stale");
        }
    }

    private static void requireFenceAuthority(
        ApprovalMigrationCommandFence fence,
        String workerId,
        long expectedRevision,
        Instant happenedAt
    ) {
        if (fence.status() != ApprovalMigrationCommandFence.FenceStatus.ACTIVE
            || fence.revision() != expectedRevision
            || !workerId.equals(fence.leaseOwner())
            || !fence.leaseUntil().isAfter(happenedAt)) {
            throw conflict("migration command fence authority is stale");
        }
    }

    private void insertEngineRequest(
        ApprovalMigrationAttempt attempt,
        ApprovalMigrationCommandFence fence,
        TargetIdentity target,
        UUID engineRequestId,
        PrepareRequest request,
        String requestHash,
        String evidenceHash,
        Map<String, Object> payload
    ) {
        jdbc.update("""
            insert into ap_process_migration_engine_request (
             tenant_id,engine_request_id,intent_id,attempt_id,approval_instance_id,
             worker_id,attempt_revision,fence_id,fence_revision,engine_instance_id,
             source_binding_evidence_hash,source_engine_definition_id,target_release_version,
             target_package_hash,target_engine_deployment_id,target_engine_definition_id,
             activity_mapping_json,request_hash,evidence_hash,requested_at,request_id,trace_id,payload_json
            ) values (
             :tenantId,:engineRequestId,:intentId,:attemptId,:instanceId,:workerId,
             :attemptRevision,:fenceId,:fenceRevision,:engineInstanceId,:bindingHash,
             :sourceDefinitionId,:targetReleaseVersion,:targetPackageHash,:targetDeploymentId,
             :targetDefinitionId,cast(:mappings as jsonb),:requestHash,:evidenceHash,
             :requestedAt,:requestId,:traceId,cast(:payload as jsonb)
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", attempt.tenantId())
                .addValue("engineRequestId", engineRequestId)
                .addValue("intentId", attempt.intentId())
                .addValue("attemptId", attempt.attemptId())
                .addValue("instanceId", attempt.approvalInstanceId())
                .addValue("workerId", request.workerId())
                .addValue("attemptRevision", attempt.revision())
                .addValue("fenceId", fence.fenceId())
                .addValue("fenceRevision", fence.revision())
                .addValue("engineInstanceId", attempt.engineInstanceId())
                .addValue("bindingHash", attempt.expectedBindingEvidenceHash())
                .addValue("sourceDefinitionId", attempt.sourceEngineDefinitionId())
                .addValue("targetReleaseVersion", target.releaseVersion())
                .addValue("targetPackageHash", target.packageHash())
                .addValue("targetDeploymentId", target.engineDeploymentId())
                .addValue("targetDefinitionId", target.engineDefinitionId())
                .addValue("mappings", "[]")
                .addValue("requestHash", requestHash)
                .addValue("evidenceHash", evidenceHash)
                .addValue("requestedAt", JdbcApprovalMigrationJson.offset(request.happenedAt()))
                .addValue("requestId", request.requestId())
                .addValue("traceId", request.traceId())
                .addValue("payload", json.write(payload)));
    }

    private void insertEngineOutcome(
        UUID outcomeId,
        ApprovalMigrationAttempt attempt,
        ApprovalMigrationCommandFence fence,
        FinalizeRequest request,
        String outcomeHash
    ) {
        PreparedDispatch prepared = request.prepared();
        String workerId = findRequestWorker(attempt.tenantId(), prepared.engineRequestId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("engineOutcomeId", outcomeId);
        payload.put("engineRequestId", prepared.engineRequestId());
        payload.put("tenantId", attempt.tenantId());
        payload.put("intentId", attempt.intentId());
        payload.put("attemptId", attempt.attemptId());
        payload.put("workerId", workerId);
        payload.put("expectedAttemptRevision", attempt.revision());
        payload.put("expectedFenceRevision", fence.revision());
        payload.put("disposition", request.disposition().name());
        payload.put("engineCallAttempted", request.engineCallAttempted());
        payload.put("engineCallReturned", request.engineCallReturned());
        payload.put("engineCallMayHaveOccurred", request.engineCallMayHaveOccurred());
        payload.put("stableCode", request.stableCode());
        payload.put("boundedSummary", request.boundedSummary());
        payload.put("preDispatchSnapshotHash", request.preDispatchSnapshotHash());
        payload.put("outcomeHash", outcomeHash);
        payload.put("recordedAt", request.happenedAt());
        payload.put("requestId", prepared.requestId());
        payload.put("traceId", prepared.traceId());
        jdbc.update("""
            insert into ap_process_migration_engine_outcome (
             tenant_id,engine_outcome_id,engine_request_id,intent_id,attempt_id,worker_id,
             expected_attempt_revision,expected_fence_revision,disposition,
             engine_call_attempted,engine_call_returned,engine_call_may_have_occurred,
             stable_code,bounded_summary,pre_dispatch_snapshot_hash,outcome_hash,
             recorded_at,request_id,trace_id,payload_json
            ) values (
             :tenantId,:outcomeId,:engineRequestId,:intentId,:attemptId,:workerId,
             :attemptRevision,:fenceRevision,:disposition,:callAttempted,:callReturned,
             :callMayHaveOccurred,:stableCode,:summary,:snapshotHash,:outcomeHash,
             :recordedAt,:requestId,:traceId,cast(:payload as jsonb)
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", attempt.tenantId())
                .addValue("outcomeId", outcomeId)
                .addValue("engineRequestId", prepared.engineRequestId())
                .addValue("intentId", attempt.intentId())
                .addValue("attemptId", attempt.attemptId())
                .addValue("workerId", workerId)
                .addValue("attemptRevision", attempt.revision())
                .addValue("fenceRevision", fence.revision())
                .addValue("disposition", request.disposition().name())
                .addValue("callAttempted", request.engineCallAttempted())
                .addValue("callReturned", request.engineCallReturned())
                .addValue("callMayHaveOccurred", request.engineCallMayHaveOccurred())
                .addValue("stableCode", request.stableCode())
                .addValue("summary", request.boundedSummary())
                .addValue("snapshotHash", request.preDispatchSnapshotHash())
                .addValue("outcomeHash", outcomeHash)
                .addValue("recordedAt", JdbcApprovalMigrationJson.offset(request.happenedAt()))
                .addValue("requestId", prepared.requestId())
                .addValue("traceId", prepared.traceId())
                .addValue("payload", json.write(payload)));
    }

    private String findRequestWorker(String tenantId, UUID engineRequestId) {
        return jdbc.queryForObject("""
            select worker_id from ap_process_migration_engine_request
            where tenant_id=:tenantId and engine_request_id=:engineRequestId
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("engineRequestId", engineRequestId), String.class);
    }

    private ApprovalMigrationAttemptEvent event(
        ApprovalMigrationAttempt current,
        ApprovalMigrationAttempt next,
        String requestId,
        String traceId
    ) {
        return new ApprovalMigrationAttemptEvent(
            nextIdentifier("attemptEventId"),
            next.tenantId(),
            next.attemptId(),
            next.revision(),
            current.status(),
            next.status(),
            next.engineOutcome(),
            next.failureClass(),
            next.errorSummary(),
            next.updatedAt(),
            requestId,
            traceId
        );
    }

    private void appendAudit(
        String tenantId,
        String actorId,
        String action,
        String targetId,
        String requestId,
        String traceId,
        Instant happenedAt,
        Map<String, String> attributes
    ) {
        auditEvents.append(new AuditEvent(
            nextIdentifier("auditEventId"),
            tenantId,
            actorId,
            action,
            "APPROVAL_MIGRATION_ATTEMPT",
            targetId,
            requestId,
            traceId,
            happenedAt,
            Map.copyOf(attributes)
        ));
    }

    private UUID nextIdentifier(String name) {
        return Objects.requireNonNull(identifiers.get(), "generated " + name + " must not be null");
    }

    private static String boundedFailure(FinalizeRequest request) {
        String summary = request.boundedSummary() == null ? "" : request.boundedSummary();
        String value = request.stableCode() + ": " + summary;
        return value.length() <= 1000 ? value : value.substring(0, 1000);
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

    private static ExecutionConflictException conflict(String message) {
        return new ExecutionConflictException(message);
    }

    private record RuntimeBindingIdentity(
        String bindingEvidenceHash,
        String engineInstanceId,
        String engineDefinitionId
    ) {
    }

    private record TargetIdentity(
        int releaseVersion,
        String packageHash,
        String engineDeploymentId,
        String engineDefinitionId
    ) {
    }
}
