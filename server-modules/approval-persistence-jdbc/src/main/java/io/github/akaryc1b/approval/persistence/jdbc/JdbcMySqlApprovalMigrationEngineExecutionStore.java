package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** MySQL 8.4 short transactions around one governed migration engine dispatch. */
public final class JdbcMySqlApprovalMigrationEngineExecutionStore
    implements ApprovalMigrationEngineExecutionStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final JdbcApprovalMigrationJson json;
    private final JdbcDatabaseValueAdapter values;
    private final JdbcMySqlApprovalInstanceCommandFence commandFence;
    private final TransactionTemplate transactions;
    private final AuditEventSink auditEvents;
    private final Supplier<UUID> identifiers;

    public JdbcMySqlApprovalMigrationEngineExecutionStore(
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
                "JdbcMySqlApprovalMigrationEngineExecutionStore requires MySQL 8.4"
            );
        }
        jdbc = new NamedParameterJdbcTemplate(source);
        this.objectMapper = mapper;
        json = new JdbcApprovalMigrationJson(mapper);
        commandFence = new JdbcMySqlApprovalInstanceCommandFence(source);
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
    public PreparedDispatch prepare(PrepareRequest request) {
        PrepareRequest exact = canonicalPrepareRequest(
            Objects.requireNonNull(request, "request must not be null")
        );
        try {
            return transactions.execute(status -> prepareOnce(exact));
        } catch (DataAccessException exception) {
            throw new ExecutionConflictException(
                "migration engine request persistence conflict",
                exception
            );
        }
    }

    @Override
    public ApprovalMigrationAttempt finalizeOutcome(FinalizeRequest request) {
        FinalizeRequest exact = canonicalFinalizeRequest(
            Objects.requireNonNull(request, "request must not be null")
        );
        try {
            return transactions.execute(status -> finalizeOnce(exact));
        } catch (DataAccessException exception) {
            throw new ExecutionConflictException(
                "migration engine outcome persistence conflict",
                exception
            );
        }
    }

    private PreparedDispatch prepareOnce(PrepareRequest request) {
        ApprovalMigrationAttempt current = lockAttempt(
            request.tenantId(),
            request.attemptId()
        );
        requireClaimAuthority(current, request);
        commandFence.acquireMigrationLock(
            current.tenantId(),
            current.approvalInstanceId()
        );
        ApprovalMigrationCommandFence fence = lockFenceByAttempt(
            current.tenantId(),
            current.attemptId()
        );
        requireFenceAuthority(
            fence,
            request.workerId(),
            request.expectedFenceRevision(),
            request.happenedAt()
        );
        RuntimeBindingIdentity binding = lockRuntimeBinding(current);
        TargetIdentity target = lockTargetIdentity(current);

        UUID engineRequestId = nextIdentifier("engineRequestId");
        ProcessInstanceMigrationPort.MigrationCommand command =
            new ProcessInstanceMigrationPort.MigrationCommand(
                current.tenantId(),
                current.approvalInstanceId(),
                current.attemptId(),
                current.engineInstanceId(),
                current.sourceEngineDefinitionId(),
                target.engineDeploymentId(),
                target.engineDefinitionId(),
                List.of()
            );
        String requestHash = requestHash(current, binding, target, fence);
        String evidenceHash = sha256(
            "m5-engine-request-evidence-v1|" + engineRequestId + '|' + requestHash
        );
        insertEngineRequest(
            current,
            fence,
            target,
            engineRequestId,
            request,
            requestHash,
            evidenceHash,
            command
        );

        ApprovalMigrationAttempt next = current.transitioned(
            new ApprovalMigrationAttemptTransition(
                AttemptStatus.ENGINE_REQUESTED,
                EngineOutcome.NOT_REQUESTED,
                null,
                null,
                engineRequestId.toString(),
                FailureClass.NONE,
                null,
                request.happenedAt()
            )
        );
        ApprovalMigrationAttempt stored = transitionAttempt(
            current,
            next,
            request.workerId(),
            sparseAttemptEvent(
                next,
                AttemptStatus.CLAIMED,
                request.requestId(),
                request.traceId()
            )
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
                "engineRequestId",
                engineRequestId.toString(),
                "requestHash",
                requestHash,
                "fenceRevision",
                Long.toString(fence.revision())
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
        requirePreparedAttempt(current, prepared);
        commandFence.acquireMigrationLock(
            current.tenantId(),
            current.approvalInstanceId()
        );
        EngineRequestAuthority authority = lockEngineRequest(
            current.tenantId(),
            prepared.engineRequestId()
        );
        requirePreparedLineage(prepared, authority);
        ApprovalMigrationCommandFence fence = lockFenceById(
            current.tenantId(),
            authority.fenceId()
        );
        requireFenceAuthority(
            fence,
            authority.workerId(),
            authority.fenceRevision(),
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
        insertEngineOutcome(
            outcomeId,
            current,
            authority,
            request,
            outcomeHash
        );

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
            default -> throw new IllegalStateException(
                "unsupported engine finalization disposition"
            );
        }
        ApprovalMigrationAttempt next = current.transitioned(
            new ApprovalMigrationAttemptTransition(
                nextStatus,
                engineOutcome,
                null,
                null,
                engineOutcome == EngineOutcome.REJECTED
                    ? null
                    : current.engineRequestReference(),
                failureClass,
                errorSummary,
                request.happenedAt()
            )
        );
        ApprovalMigrationAttempt stored = transitionAttempt(
            current,
            next,
            null,
            sparseAttemptEvent(
                next,
                current.status(),
                authority.requestId(),
                authority.traceId()
            )
        );
        appendAudit(
            current.tenantId(),
            authority.workerId(),
            "PROCESS_MIGRATION_ENGINE_OUTCOME_RECORDED",
            current.attemptId().toString(),
            authority.requestId(),
            authority.traceId(),
            request.happenedAt(),
            Map.of(
                "engineRequestId",
                prepared.engineRequestId().toString(),
                "disposition",
                request.disposition().name(),
                "outcomeHash",
                outcomeHash
            )
        );
        return stored;
    }

    private ApprovalMigrationAttempt lockAttempt(
        String tenantId,
        UUID attemptId
    ) {
        return jdbc.query("""
            select tenant_id,attempt_id,intent_id,status,revision,lease_owner,lease_until,
                   engine_request_reference,failure_class,error_summary,created_at,updated_at,
                   payload_json
            from ap_process_migration_attempt
            where tenant_id=:tenantId and attempt_id=:attemptId
            for update
            """, new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue(
                    "attemptId",
                    values.bindUuid(Objects.requireNonNull(
                        attemptId,
                        "attemptId must not be null"
                    ))
                ),
            (row, number) -> readAttempt(row)).stream().findFirst()
            .orElseThrow(() -> conflict("migration attempt does not exist"));
    }

    private ApprovalMigrationAttempt readAttempt(ResultSet row) throws SQLException {
        ApprovalMigrationAttempt attempt = json.read(
            row.getString("payload_json"),
            ApprovalMigrationAttempt.class
        );
        if (!attempt.tenantId().equals(row.getString("tenant_id"))
            || !attempt.attemptId().equals(values.uuid(row, "attempt_id"))
            || !attempt.intentId().equals(values.uuid(row, "intent_id"))
            || !attempt.status().name().equals(row.getString("status"))
            || attempt.revision() != row.getLong("revision")
            || !Objects.equals(attempt.leaseOwner(), row.getString("lease_owner"))
            || !Objects.equals(
                attempt.leaseUntil(),
                values.nullableInstant(row, "lease_until")
            )
            || !Objects.equals(
                attempt.engineRequestReference(),
                row.getString("engine_request_reference")
            )
            || !attempt.failureClass().name().equals(row.getString("failure_class"))
            || !Objects.equals(attempt.errorSummary(), row.getString("error_summary"))
            || !attempt.createdAt().equals(values.instant(row, "created_at"))
            || !attempt.updatedAt().equals(values.instant(row, "updated_at"))) {
            throw new IllegalStateException(
                "migration attempt relational and payload evidence diverged"
            );
        }
        return attempt;
    }

    private ApprovalMigrationCommandFence lockFenceByAttempt(
        String tenantId,
        UUID attemptId
    ) {
        return jdbc.query("""
            select tenant_id,fence_id,approval_instance_id,attempt_id,status,revision,
                   lease_owner,lease_until,idempotency_key,request_hash,acquired_at,
                   updated_at,released_at,payload_json
            from ap_approval_instance_command_fence
            where tenant_id=:tenantId and attempt_id=:attemptId and status='ACTIVE'
            for update
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("attemptId", values.bindUuid(attemptId)),
            (row, number) -> readFence(row)).stream().findFirst()
            .orElseThrow(() -> conflict(
                "active migration command fence does not exist"
            ));
    }

    private ApprovalMigrationCommandFence lockFenceById(
        String tenantId,
        UUID fenceId
    ) {
        return jdbc.query("""
            select tenant_id,fence_id,approval_instance_id,attempt_id,status,revision,
                   lease_owner,lease_until,idempotency_key,request_hash,acquired_at,
                   updated_at,released_at,payload_json
            from ap_approval_instance_command_fence
            where tenant_id=:tenantId and fence_id=:fenceId
            for update
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("fenceId", values.bindUuid(fenceId)),
            (row, number) -> readFence(row)).stream().findFirst()
            .orElseThrow(() -> conflict(
                "migration command fence evidence does not exist"
            ));
    }

    private ApprovalMigrationCommandFence readFence(ResultSet row) throws SQLException {
        ApprovalMigrationCommandFence fence = json.read(
            row.getString("payload_json"),
            ApprovalMigrationCommandFence.class
        );
        if (!fence.tenantId().equals(row.getString("tenant_id"))
            || !fence.fenceId().equals(values.uuid(row, "fence_id"))
            || !fence.approvalInstanceId().equals(
                values.uuid(row, "approval_instance_id")
            )
            || !fence.attemptId().equals(values.uuid(row, "attempt_id"))
            || !fence.status().name().equals(row.getString("status"))
            || fence.revision() != row.getLong("revision")
            || !fence.leaseOwner().equals(row.getString("lease_owner"))
            || !fence.leaseUntil().equals(values.instant(row, "lease_until"))
            || !fence.idempotencyKey().equals(row.getString("idempotency_key"))
            || !fence.requestHash().equals(row.getString("request_hash"))
            || !fence.acquiredAt().equals(values.instant(row, "acquired_at"))
            || !fence.updatedAt().equals(values.instant(row, "updated_at"))
            || !Objects.equals(
                fence.releasedAt(),
                values.nullableInstant(row, "released_at")
            )) {
            throw new IllegalStateException(
                "migration command fence relational and payload evidence diverged"
            );
        }
        return fence;
    }

    private RuntimeBindingIdentity lockRuntimeBinding(
        ApprovalMigrationAttempt attempt
    ) {
        return jdbc.query("""
            select binding_evidence_hash,engine_instance_id,engine_definition_id
            from ap_process_runtime_binding
            where tenant_id=:tenantId and approval_instance_id=:instanceId
            for update
            """, new MapSqlParameterSource()
                .addValue("tenantId", attempt.tenantId())
                .addValue("instanceId", values.bindUuid(attempt.approvalInstanceId())),
            (row, number) -> new RuntimeBindingIdentity(
                row.getString("binding_evidence_hash"),
                row.getString("engine_instance_id"),
                row.getString("engine_definition_id")
            )).stream().findFirst()
            .filter(value -> value.bindingEvidenceHash().equals(
                    attempt.expectedBindingEvidenceHash()
                )
                && value.engineInstanceId().equals(attempt.engineInstanceId())
                && value.engineDefinitionId().equals(
                    attempt.sourceEngineDefinitionId()
                ))
            .orElseThrow(() -> conflict("source runtime binding is stale"));
    }

    private TargetIdentity lockTargetIdentity(ApprovalMigrationAttempt attempt) {
        return jdbc.query("""
            select i.target_release_version as intent_target_release_version,
                   i.target_package_hash as intent_target_package_hash,
                   p.target_release_version,p.target_package_hash,
                   p.target_engine_deployment_id,p.target_engine_definition_id
            from ap_process_migration_intent i
            join ap_process_migration_plan p
              on p.tenant_id=i.tenant_id and p.plan_id=i.plan_id
             and p.plan_hash=i.plan_hash and p.status='CONSUMED'
            join ap_process_migration_plan_consumption c
              on c.tenant_id=i.tenant_id and c.plan_id=i.plan_id
             and c.plan_hash=i.plan_hash and c.intent_id=i.intent_id
             and c.intent_evidence_hash=i.intent_evidence_hash
            where i.tenant_id=:tenantId and i.intent_id=:intentId
              and i.status='RUNNING'
            for update
            """, new MapSqlParameterSource()
                .addValue("tenantId", attempt.tenantId())
                .addValue("intentId", values.bindUuid(attempt.intentId())),
            (row, number) -> {
                int releaseVersion = row.getInt("target_release_version");
                String packageHash = row.getString("target_package_hash");
                if (releaseVersion != row.getInt("intent_target_release_version")
                    || !packageHash.equals(
                        row.getString("intent_target_package_hash")
                    )) {
                    throw new IllegalStateException(
                        "migration target intent and plan evidence diverged"
                    );
                }
                return new TargetIdentity(
                    releaseVersion,
                    packageHash,
                    row.getString("target_engine_deployment_id"),
                    row.getString("target_engine_definition_id")
                );
            }).stream().findFirst()
            .filter(value -> value.engineDefinitionId().equals(
                attempt.targetEngineDefinitionId()
            ))
            .orElseThrow(() -> conflict(
                "target release or engine definition is stale"
            ));
    }

    private EngineRequestAuthority lockEngineRequest(
        String tenantId,
        UUID engineRequestId
    ) {
        return jdbc.query("""
            select tenant_id,engine_request_id,intent_id,attempt_id,
                   approval_instance_id,worker_id,attempt_revision,fence_id,fence_revision,
                   engine_instance_id,source_binding_evidence_hash,
                   source_engine_definition_id,target_release_version,target_package_hash,
                   target_engine_deployment_id,target_engine_definition_id,
                   activity_mapping_json,request_hash,evidence_hash,requested_at,
                   request_id,trace_id
            from ap_process_migration_engine_request
            where tenant_id=:tenantId and engine_request_id=:engineRequestId
            for update
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("engineRequestId", values.bindUuid(engineRequestId)),
            (row, number) -> new EngineRequestAuthority(
                row.getString("tenant_id"),
                values.uuid(row, "engine_request_id"),
                values.uuid(row, "intent_id"),
                values.uuid(row, "attempt_id"),
                values.uuid(row, "approval_instance_id"),
                row.getString("worker_id"),
                row.getLong("attempt_revision"),
                values.uuid(row, "fence_id"),
                row.getLong("fence_revision"),
                row.getString("engine_instance_id"),
                row.getString("source_binding_evidence_hash"),
                row.getString("source_engine_definition_id"),
                row.getInt("target_release_version"),
                row.getString("target_package_hash"),
                row.getString("target_engine_deployment_id"),
                row.getString("target_engine_definition_id"),
                row.getString("activity_mapping_json"),
                row.getString("request_hash"),
                row.getString("evidence_hash"),
                values.instant(row, "requested_at"),
                row.getString("request_id"),
                row.getString("trace_id")
            )).stream().findFirst()
            .orElseThrow(() -> conflict(
                "immutable migration engine request evidence does not exist"
            ));
    }

    private void requireClaimAuthority(
        ApprovalMigrationAttempt attempt,
        PrepareRequest request
    ) {
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

    private static void requirePreparedAttempt(
        ApprovalMigrationAttempt current,
        PreparedDispatch prepared
    ) {
        if (current.status() != AttemptStatus.ENGINE_REQUESTED
            || current.revision() != prepared.attempt().revision()
            || !prepared.engineRequestId().toString().equals(
                current.engineRequestReference()
            )
            || !current.equals(prepared.attempt())) {
            throw conflict("migration attempt is not the exact requested revision");
        }
    }

    private void requirePreparedLineage(
        PreparedDispatch prepared,
        EngineRequestAuthority authority
    ) {
        ProcessInstanceMigrationPort.MigrationCommand command = prepared.engineCommand();
        ApprovalMigrationAttempt attempt = prepared.attempt();
        String expectedEvidenceHash = sha256(
            "m5-engine-request-evidence-v1|"
                + authority.engineRequestId()
                + '|'
                + authority.requestHash()
        );
        if (!authority.tenantId().equals(attempt.tenantId())
            || !authority.engineRequestId().equals(prepared.engineRequestId())
            || !authority.intentId().equals(attempt.intentId())
            || !authority.attemptId().equals(attempt.attemptId())
            || !authority.approvalInstanceId().equals(attempt.approvalInstanceId())
            || authority.attemptRevision() + 1 != attempt.revision()
            || authority.fenceRevision() != prepared.fenceRevision()
            || !authority.engineInstanceId().equals(command.engineInstanceId())
            || !authority.sourceBindingEvidenceHash().equals(
                attempt.expectedBindingEvidenceHash()
            )
            || !authority.sourceEngineDefinitionId().equals(
                command.sourceEngineDefinitionId()
            )
            || !authority.targetEngineDeploymentId().equals(
                command.targetEngineDeploymentId()
            )
            || !authority.targetEngineDefinitionId().equals(
                command.targetEngineDefinitionId()
            )
            || !authority.evidenceHash().equals(prepared.requestEvidenceHash())
            || !authority.evidenceHash().equals(expectedEvidenceHash)
            || !authority.requestedAt().equals(prepared.preparedAt())
            || !authority.requestId().equals(prepared.requestId())
            || !Objects.equals(authority.traceId(), prepared.traceId())
            || !command.tenantId().equals(attempt.tenantId())
            || !command.approvalInstanceId().equals(attempt.approvalInstanceId())
            || !command.attemptId().equals(attempt.attemptId())
            || !command.engineInstanceId().equals(attempt.engineInstanceId())
            || !command.sourceEngineDefinitionId().equals(
                attempt.sourceEngineDefinitionId()
            )
            || !command.targetEngineDefinitionId().equals(
                attempt.targetEngineDefinitionId()
            )
            || !jsonEquivalent(
                authority.activityMappingJson(),
                command.activityMappings()
            )) {
            throw conflict("prepared migration dispatch lineage is inconsistent");
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
        ProcessInstanceMigrationPort.MigrationCommand command
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("engineRequestId", engineRequestId);
        payload.put("tenantId", attempt.tenantId());
        payload.put("intentId", attempt.intentId());
        payload.put("attemptId", attempt.attemptId());
        payload.put("approvalInstanceId", attempt.approvalInstanceId());
        payload.put("workerId", request.workerId());
        payload.put("attemptRevision", attempt.revision());
        payload.put("fenceId", fence.fenceId());
        payload.put("fenceRevision", fence.revision());
        payload.put("engineInstanceId", attempt.engineInstanceId());
        payload.put(
            "sourceBindingEvidenceHash",
            attempt.expectedBindingEvidenceHash()
        );
        payload.put("sourceEngineDefinitionId", attempt.sourceEngineDefinitionId());
        payload.put("targetReleaseVersion", target.releaseVersion());
        payload.put("targetPackageHash", target.packageHash());
        payload.put("targetEngineDeploymentId", target.engineDeploymentId());
        payload.put("targetEngineDefinitionId", target.engineDefinitionId());
        payload.put("activityMappings", command.activityMappings());
        payload.put("requestHash", requestHash);
        payload.put("evidenceHash", evidenceHash);
        payload.put("requestedAt", request.happenedAt());
        payload.put("requestId", request.requestId());
        payload.put("traceId", request.traceId());
        int inserted = jdbc.update("""
            insert into ap_process_migration_engine_request (
              tenant_id,engine_request_id,intent_id,attempt_id,approval_instance_id,
              worker_id,attempt_revision,fence_id,fence_revision,engine_instance_id,
              source_binding_evidence_hash,source_engine_definition_id,target_release_version,
              target_package_hash,target_engine_deployment_id,target_engine_definition_id,
              activity_mapping_json,request_hash,evidence_hash,requested_at,request_id,
              trace_id,payload_json
            ) values (
              :tenantId,:engineRequestId,:intentId,:attemptId,:instanceId,
              :workerId,:attemptRevision,:fenceId,:fenceRevision,:engineInstanceId,
              :bindingHash,:sourceDefinitionId,:targetReleaseVersion,
              :targetPackageHash,:targetDeploymentId,:targetDefinitionId,
              :mappings,:requestHash,:evidenceHash,:requestedAt,:requestId,
              :traceId,:payload
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", attempt.tenantId())
                .addValue("engineRequestId", values.bindUuid(engineRequestId))
                .addValue("intentId", values.bindUuid(attempt.intentId()))
                .addValue("attemptId", values.bindUuid(attempt.attemptId()))
                .addValue("instanceId", values.bindUuid(attempt.approvalInstanceId()))
                .addValue("workerId", request.workerId())
                .addValue("attemptRevision", attempt.revision())
                .addValue("fenceId", values.bindUuid(fence.fenceId()))
                .addValue("fenceRevision", fence.revision())
                .addValue("engineInstanceId", attempt.engineInstanceId())
                .addValue("bindingHash", attempt.expectedBindingEvidenceHash())
                .addValue("sourceDefinitionId", attempt.sourceEngineDefinitionId())
                .addValue("targetReleaseVersion", target.releaseVersion())
                .addValue("targetPackageHash", target.packageHash())
                .addValue("targetDeploymentId", target.engineDeploymentId())
                .addValue("targetDefinitionId", target.engineDefinitionId())
                .addValue("mappings", json.write(command.activityMappings()))
                .addValue("requestHash", requestHash)
                .addValue("evidenceHash", evidenceHash)
                .addValue("requestedAt", values.bindInstant(request.happenedAt()))
                .addValue("requestId", request.requestId())
                .addValue("traceId", request.traceId())
                .addValue("payload", json.write(payload)));
        if (inserted != 1) {
            throw conflict("migration engine request insert did not affect one row");
        }
    }

    private void insertEngineOutcome(
        UUID outcomeId,
        ApprovalMigrationAttempt attempt,
        EngineRequestAuthority authority,
        FinalizeRequest request,
        String outcomeHash
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("engineOutcomeId", outcomeId);
        payload.put("engineRequestId", authority.engineRequestId());
        payload.put("tenantId", attempt.tenantId());
        payload.put("intentId", attempt.intentId());
        payload.put("attemptId", attempt.attemptId());
        payload.put("workerId", authority.workerId());
        payload.put("expectedAttemptRevision", attempt.revision());
        payload.put("expectedFenceRevision", authority.fenceRevision());
        payload.put("disposition", request.disposition().name());
        payload.put("engineCallAttempted", request.engineCallAttempted());
        payload.put("engineCallReturned", request.engineCallReturned());
        payload.put(
            "engineCallMayHaveOccurred",
            request.engineCallMayHaveOccurred()
        );
        payload.put("stableCode", request.stableCode());
        payload.put("boundedSummary", request.boundedSummary());
        payload.put("preDispatchSnapshotHash", request.preDispatchSnapshotHash());
        payload.put("outcomeHash", outcomeHash);
        payload.put("recordedAt", request.happenedAt());
        payload.put("requestId", authority.requestId());
        payload.put("traceId", authority.traceId());
        int inserted = jdbc.update("""
            insert into ap_process_migration_engine_outcome (
              tenant_id,engine_outcome_id,engine_request_id,intent_id,attempt_id,worker_id,
              expected_attempt_revision,expected_fence_revision,disposition,
              engine_call_attempted,engine_call_returned,engine_call_may_have_occurred,
              stable_code,bounded_summary,pre_dispatch_snapshot_hash,outcome_hash,
              recorded_at,request_id,trace_id,payload_json
            ) values (
              :tenantId,:outcomeId,:engineRequestId,:intentId,:attemptId,:workerId,
              :attemptRevision,:fenceRevision,:disposition,
              :callAttempted,:callReturned,:callMayHaveOccurred,
              :stableCode,:summary,:snapshotHash,:outcomeHash,
              :recordedAt,:requestId,:traceId,:payload
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", attempt.tenantId())
                .addValue("outcomeId", values.bindUuid(outcomeId))
                .addValue("engineRequestId", values.bindUuid(authority.engineRequestId()))
                .addValue("intentId", values.bindUuid(attempt.intentId()))
                .addValue("attemptId", values.bindUuid(attempt.attemptId()))
                .addValue("workerId", authority.workerId())
                .addValue("attemptRevision", attempt.revision())
                .addValue("fenceRevision", authority.fenceRevision())
                .addValue("disposition", request.disposition().name())
                .addValue("callAttempted", request.engineCallAttempted())
                .addValue("callReturned", request.engineCallReturned())
                .addValue("callMayHaveOccurred", request.engineCallMayHaveOccurred())
                .addValue("stableCode", request.stableCode())
                .addValue("summary", request.boundedSummary())
                .addValue("snapshotHash", request.preDispatchSnapshotHash())
                .addValue("outcomeHash", outcomeHash)
                .addValue("recordedAt", values.bindInstant(request.happenedAt()))
                .addValue("requestId", authority.requestId())
                .addValue("traceId", authority.traceId())
                .addValue("payload", json.write(payload)));
        if (inserted != 1) {
            throw conflict("migration engine outcome insert did not affect one row");
        }
    }

    private ApprovalMigrationAttempt transitionAttempt(
        ApprovalMigrationAttempt previous,
        ApprovalMigrationAttempt next,
        String actor,
        ApprovalMigrationAttemptEvent event
    ) {
        ApprovalMigrationAttemptEvent durable = event.withDurableEvidence(next, actor);
        int updated = jdbc.update("""
            update ap_process_migration_attempt set
              status=:status,revision=:revision,engine_outcome=:engineOutcome,
              lease_actor=:leaseActor,lease_owner=:leaseOwner,lease_until=:leaseUntil,
              engine_request_reference=:engineRequestReference,
              failure_class=:failureClass,error_summary=:errorSummary,
              payload_json=:payload,updated_at=:updatedAt
            where tenant_id=:tenantId and attempt_id=:attemptId
              and revision=:expectedRevision and status=:expectedStatus
            """, attemptParameters(next)
                .addValue("leaseActor", actor)
                .addValue("expectedRevision", previous.revision())
                .addValue("expectedStatus", previous.status().name()));
        if (updated != 1) {
            throw conflict("migration attempt revision or state conflict");
        }
        appendAttemptEvent(durable);
        return next;
    }

    private MapSqlParameterSource attemptParameters(ApprovalMigrationAttempt value) {
        return new MapSqlParameterSource()
            .addValue("tenantId", value.tenantId())
            .addValue("attemptId", values.bindUuid(value.attemptId()))
            .addValue("status", value.status().name())
            .addValue("revision", value.revision())
            .addValue("engineOutcome", value.engineOutcome().name())
            .addValue("leaseOwner", value.leaseOwner())
            .addValue("leaseUntil", values.bindNullableInstant(value.leaseUntil()))
            .addValue("engineRequestReference", value.engineRequestReference())
            .addValue("failureClass", value.failureClass().name())
            .addValue("errorSummary", value.errorSummary())
            .addValue("payload", json.write(value))
            .addValue("updatedAt", values.bindInstant(value.updatedAt()));
    }

    private void appendAttemptEvent(ApprovalMigrationAttemptEvent event) {
        jdbc.update("""
            insert into ap_process_migration_attempt_event (
              tenant_id,event_id,attempt_id,revision,from_status,to_status,engine_outcome,
              lease_actor,lease_owner,lease_until,engine_request_reference,
              failure_class,error_summary,payload_json,happened_at
            ) values (
              :tenantId,:eventId,:attemptId,:revision,:fromStatus,:toStatus,:engineOutcome,
              :leaseActor,:leaseOwner,:leaseUntil,:engineRequestReference,
              :failureClass,:errorSummary,:payload,:happenedAt
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", event.tenantId())
                .addValue("eventId", values.bindUuid(event.eventId()))
                .addValue("attemptId", values.bindUuid(event.attemptId()))
                .addValue("revision", event.revision())
                .addValue(
                    "fromStatus",
                    event.fromStatus() == null ? null : event.fromStatus().name()
                )
                .addValue("toStatus", event.toStatus().name())
                .addValue("engineOutcome", event.engineOutcome().name())
                .addValue("leaseActor", event.leaseActor())
                .addValue("leaseOwner", event.leaseOwner())
                .addValue("leaseUntil", values.bindNullableInstant(event.leaseUntil()))
                .addValue("engineRequestReference", event.engineRequestReference())
                .addValue("failureClass", event.failureClass().name())
                .addValue("errorSummary", event.errorSummary())
                .addValue("payload", json.write(event))
                .addValue("happenedAt", values.bindInstant(event.happenedAt())));
    }

    private ApprovalMigrationAttemptEvent sparseAttemptEvent(
        ApprovalMigrationAttempt next,
        AttemptStatus from,
        String requestId,
        String traceId
    ) {
        return new ApprovalMigrationAttemptEvent(
            nextIdentifier("attemptEventId"),
            next.tenantId(),
            next.attemptId(),
            next.revision(),
            from,
            next.status(),
            next.engineOutcome(),
            next.failureClass(),
            next.errorSummary(),
            next.updatedAt(),
            requestId,
            traceId
        );
    }

    private String requestHash(
        ApprovalMigrationAttempt attempt,
        RuntimeBindingIdentity binding,
        TargetIdentity target,
        ApprovalMigrationCommandFence fence
    ) {
        return sha256(String.join(
            "|",
            "m5-engine-request-v1",
            attempt.tenantId(),
            attempt.intentId().toString(),
            attempt.attemptId().toString(),
            attempt.approvalInstanceId().toString(),
            attempt.engineInstanceId(),
            binding.bindingEvidenceHash(),
            attempt.sourceEngineDefinitionId(),
            Integer.toString(target.releaseVersion()),
            target.packageHash(),
            target.engineDeploymentId(),
            target.engineDefinitionId(),
            Long.toString(attempt.revision()),
            fence.fenceId().toString(),
            Long.toString(fence.revision())
        ));
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

    private PrepareRequest canonicalPrepareRequest(PrepareRequest request) {
        return new PrepareRequest(
            request.tenantId(),
            request.attemptId(),
            request.workerId(),
            request.expectedAttemptRevision(),
            request.expectedFenceRevision(),
            AuditHashCanonicalizer.canonicalInstant(request.happenedAt()),
            request.requestId(),
            request.traceId()
        );
    }

    private FinalizeRequest canonicalFinalizeRequest(FinalizeRequest request) {
        return new FinalizeRequest(
            request.prepared(),
            request.disposition(),
            request.engineCallAttempted(),
            request.engineCallReturned(),
            request.engineCallMayHaveOccurred(),
            request.stableCode(),
            request.boundedSummary(),
            request.preDispatchSnapshotHash(),
            AuditHashCanonicalizer.canonicalInstant(request.happenedAt())
        );
    }

    private boolean jsonEquivalent(String stored, Object value) {
        try {
            return objectMapper.readTree(stored).equals(objectMapper.valueToTree(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "migration engine activity mapping JSON is invalid",
                exception
            );
        }
    }

    private UUID nextIdentifier(String name) {
        return Objects.requireNonNull(
            identifiers.get(),
            "generated " + name + " must not be null"
        );
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

    private static String requireText(String value, String name) {
        String exact = Objects.requireNonNull(value, name + " must not be null").trim();
        if (exact.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return exact;
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

    private record EngineRequestAuthority(
        String tenantId,
        UUID engineRequestId,
        UUID intentId,
        UUID attemptId,
        UUID approvalInstanceId,
        String workerId,
        long attemptRevision,
        UUID fenceId,
        long fenceRevision,
        String engineInstanceId,
        String sourceBindingEvidenceHash,
        String sourceEngineDefinitionId,
        int targetReleaseVersion,
        String targetPackageHash,
        String targetEngineDeploymentId,
        String targetEngineDefinitionId,
        String activityMappingJson,
        String requestHash,
        String evidenceHash,
        Instant requestedAt,
        String requestId,
        String traceId
    ) {
    }
}
