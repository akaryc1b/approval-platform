package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationExactVerificationStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttemptEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttemptTransition;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationCommandFence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationEngineSnapshot;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationExactVerification;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationExactVerification.ExactClassification;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.EngineOutcome;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.FailureClass;
import io.github.akaryc1b.approval.engine.ProcessInstanceVerificationPort.VerificationCommand;
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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** MySQL 8.4 implementation of the D4 exact-verification transaction boundary. */
public final class JdbcMySqlApprovalMigrationExactVerificationStore
    implements ApprovalMigrationExactVerificationStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final JdbcApprovalMigrationJson json;
    private final JdbcDatabaseValueAdapter values;
    private final JdbcMySqlApprovalInstanceCommandFence commandFence;
    private final TransactionTemplate transactions;
    private final AuditEventSink auditEvents;
    private final Supplier<UUID> identifiers;

    public JdbcMySqlApprovalMigrationExactVerificationStore(
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
                "JdbcMySqlApprovalMigrationExactVerificationStore requires MySQL 8.4"
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
    public PreparedVerification prepare(PrepareRequest request) {
        PrepareRequest exact = canonicalPrepareRequest(
            Objects.requireNonNull(request, "request must not be null")
        );
        try {
            return transactions.execute(status -> prepareOnce(exact));
        } catch (VerificationConflictException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new VerificationConflictException(
                "exact verification preparation conflict",
                exception
            );
        }
    }

    @Override
    public StoredVerification finalizeVerification(FinalizeRequest request) {
        FinalizeRequest exact = canonicalFinalizeRequest(
            Objects.requireNonNull(request, "request must not be null")
        );
        try {
            return transactions.execute(status -> finalizeOnce(exact));
        } catch (VerificationConflictException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new VerificationConflictException(
                "exact verification persistence conflict",
                exception
            );
        }
    }

    private PreparedVerification prepareOnce(PrepareRequest request) {
        String requestHash = requestHash(request);
        ApprovalMigrationAttempt current = lockAttempt(
            request.tenantId(),
            request.attemptId()
        );
        Optional<ApprovalMigrationExactVerification> existing = findExisting(
            request.tenantId(),
            request.attemptId()
        );
        if (existing.isPresent()) {
            ApprovalMigrationExactVerification evidence = existing.orElseThrow();
            requireExactReplay(evidence, requestHash);
            StoredVerification replay = new StoredVerification(evidence, current, true);
            return new PreparedVerification(
                current,
                evidence.engineRequestId(),
                evidence.engineOutcomeId(),
                request.expectedFenceRevision(),
                request.workerId(),
                requestHash,
                new VerificationCommand(
                    current.tenantId(),
                    current.engineInstanceId(),
                    java.util.List.of()
                ),
                request.happenedAt(),
                request.requestId(),
                request.traceId(),
                replay
            );
        }
        if (current.status() != AttemptStatus.VERIFYING
            || current.revision() != request.expectedAttemptRevision()) {
            throw conflict("attempt is not the exact VERIFYING revision");
        }
        EngineLineage lineage = lockEngineLineage(current);
        if (!request.workerId().equals(lineage.workerId())) {
            throw conflict("verification worker does not own the engine request");
        }
        commandFence.acquireMigrationLock(
            current.tenantId(),
            current.approvalInstanceId()
        );
        ApprovalMigrationCommandFence fence = lockFence(
            current.tenantId(),
            current.attemptId()
        );
        requireLineageFence(lineage, fence);
        requireFence(
            fence,
            request.workerId(),
            request.expectedFenceRevision(),
            request.happenedAt()
        );
        return new PreparedVerification(
            current,
            lineage.engineRequestId(),
            lineage.engineOutcomeId(),
            fence.revision(),
            lineage.workerId(),
            requestHash,
            new VerificationCommand(
                current.tenantId(),
                current.engineInstanceId(),
                java.util.List.of()
            ),
            request.happenedAt(),
            request.requestId(),
            request.traceId(),
            null
        );
    }

    private StoredVerification finalizeOnce(FinalizeRequest request) {
        PreparedVerification prepared = request.prepared();
        Optional<ApprovalMigrationExactVerification> existing = findExisting(
            prepared.attempt().tenantId(),
            prepared.attempt().attemptId()
        );
        if (existing.isPresent()) {
            ApprovalMigrationExactVerification evidence = existing.orElseThrow();
            requireExactReplay(evidence, prepared.requestHash());
            ApprovalMigrationAttempt current = lockAttempt(
                prepared.attempt().tenantId(),
                prepared.attempt().attemptId()
            );
            return new StoredVerification(evidence, current, true);
        }

        ApprovalMigrationAttempt current = lockAttempt(
            prepared.attempt().tenantId(),
            prepared.attempt().attemptId()
        );
        if (current.status() != AttemptStatus.VERIFYING
            || current.revision() != prepared.attempt().revision()
            || !prepared.engineRequestId().toString().equals(
                current.engineRequestReference()
            )) {
            throw conflict("attempt verification authority is stale");
        }
        EngineLineage lineage = lockEngineLineage(current);
        if (!lineage.engineRequestId().equals(prepared.engineRequestId())
            || !lineage.engineOutcomeId().equals(prepared.engineOutcomeId())
            || !lineage.workerId().equals(prepared.workerId())) {
            throw conflict("engine verification lineage is stale");
        }
        commandFence.acquireMigrationLock(
            current.tenantId(),
            current.approvalInstanceId()
        );
        ApprovalMigrationCommandFence fence = lockFence(
            current.tenantId(),
            current.attemptId()
        );
        requireLineageFence(lineage, fence);
        requireFence(
            fence,
            prepared.workerId(),
            prepared.fenceRevision(),
            request.happenedAt()
        );

        ExactClassification derived = ApprovalMigrationExactVerification.classify(
            request.snapshot(),
            current.sourceEngineDefinitionId(),
            current.targetEngineDefinitionId()
        );
        if (derived != request.classification()) {
            throw conflict("verification classification was not server-derived");
        }
        UUID verificationId = nextIdentifier("verificationId");
        String evidenceHash = sha256(String.join(
            "|",
            "m5-exact-verification-evidence-v1",
            verificationId.toString(),
            current.tenantId(),
            current.intentId().toString(),
            current.attemptId().toString(),
            prepared.engineRequestId().toString(),
            prepared.engineOutcomeId().toString(),
            current.sourceEngineDefinitionId(),
            current.targetEngineDefinitionId(),
            derived.name(),
            request.snapshot().snapshotHash(),
            prepared.requestHash()
        ));
        ApprovalMigrationExactVerification evidence = new ApprovalMigrationExactVerification(
            verificationId,
            current.tenantId(),
            current.intentId(),
            current.attemptId(),
            prepared.engineRequestId(),
            prepared.engineOutcomeId(),
            current.sourceEngineDefinitionId(),
            current.targetEngineDefinitionId(),
            derived,
            request.snapshot(),
            prepared.requestHash(),
            evidenceHash,
            request.happenedAt(),
            prepared.requestId(),
            prepared.traceId()
        );
        insertEvidence(evidence, prepared, current.revision(), fence.revision());

        ApprovalMigrationAttempt storedAttempt = current;
        if (derived != ExactClassification.EXACT_TARGET_RUNTIME) {
            ApprovalMigrationAttempt next = current.transitioned(
                new ApprovalMigrationAttemptTransition(
                    AttemptStatus.RECONCILING,
                    EngineOutcome.VERIFICATION_MISMATCH,
                    null,
                    null,
                    current.engineRequestReference(),
                    FailureClass.RECONCILIATION_REQUIRED,
                    bounded("D4 " + derived.name() + " requires reconciliation", 1000),
                    request.happenedAt()
                )
            );
            storedAttempt = transitionAttempt(
                current,
                next,
                attemptEvent(
                    current,
                    next,
                    prepared.requestId(),
                    prepared.traceId()
                )
            );
        }
        appendAudit(evidence, prepared.workerId());
        return new StoredVerification(evidence, storedAttempt, false);
    }

    private ApprovalMigrationAttempt lockAttempt(String tenantId, UUID attemptId) {
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

    private EngineLineage lockEngineLineage(ApprovalMigrationAttempt attempt) {
        return jdbc.query("""
            select r.tenant_id as request_tenant_id,
                   r.engine_request_id,r.intent_id as request_intent_id,
                   r.attempt_id as request_attempt_id,r.worker_id as request_worker_id,
                   r.fence_id,r.fence_revision,
                   r.source_engine_definition_id as request_source_definition_id,
                   r.target_engine_definition_id as request_target_definition_id,
                   r.payload_json as request_payload_json,
                   o.tenant_id as outcome_tenant_id,o.engine_outcome_id,
                   o.engine_request_id as outcome_engine_request_id,
                   o.intent_id as outcome_intent_id,o.attempt_id as outcome_attempt_id,
                   o.worker_id as outcome_worker_id,o.expected_attempt_revision,
                   o.expected_fence_revision,o.disposition,o.engine_call_attempted,
                   o.engine_call_returned,o.payload_json as outcome_payload_json
            from ap_process_migration_engine_request r
            join ap_process_migration_engine_outcome o
              on o.tenant_id=r.tenant_id and o.engine_request_id=r.engine_request_id
            where r.tenant_id=:tenantId and r.attempt_id=:attemptId
              and o.disposition='CALL_RETURNED_AWAITING_VERIFICATION'
              and o.engine_call_attempted and o.engine_call_returned
            for update
            """, new MapSqlParameterSource()
                .addValue("tenantId", attempt.tenantId())
                .addValue("attemptId", values.bindUuid(attempt.attemptId())),
            (row, number) -> readEngineLineage(row, attempt)).stream().findFirst()
            .filter(value -> value.engineRequestId().toString().equals(
                attempt.engineRequestReference()
            ))
            .orElseThrow(() -> conflict("returned engine outcome lineage is missing"));
    }

    private EngineLineage readEngineLineage(
        ResultSet row,
        ApprovalMigrationAttempt attempt
    ) throws SQLException {
        UUID requestId = values.uuid(row, "engine_request_id");
        UUID outcomeId = values.uuid(row, "engine_outcome_id");
        UUID requestIntentId = values.uuid(row, "request_intent_id");
        UUID requestAttemptId = values.uuid(row, "request_attempt_id");
        UUID outcomeRequestId = values.uuid(row, "outcome_engine_request_id");
        UUID outcomeIntentId = values.uuid(row, "outcome_intent_id");
        UUID outcomeAttemptId = values.uuid(row, "outcome_attempt_id");
        UUID fenceId = values.uuid(row, "fence_id");
        String workerId = row.getString("request_worker_id");
        String outcomeWorkerId = row.getString("outcome_worker_id");
        String sourceDefinitionId = row.getString("request_source_definition_id");
        String targetDefinitionId = row.getString("request_target_definition_id");
        long fenceRevision = row.getLong("fence_revision");
        long outcomeAttemptRevision = row.getLong("expected_attempt_revision");
        long outcomeFenceRevision = row.getLong("expected_fence_revision");

        if (!attempt.tenantId().equals(row.getString("request_tenant_id"))
            || !attempt.tenantId().equals(row.getString("outcome_tenant_id"))
            || !attempt.intentId().equals(requestIntentId)
            || !attempt.intentId().equals(outcomeIntentId)
            || !attempt.attemptId().equals(requestAttemptId)
            || !attempt.attemptId().equals(outcomeAttemptId)
            || !requestId.equals(outcomeRequestId)
            || !workerId.equals(outcomeWorkerId)
            || !attempt.sourceEngineDefinitionId().equals(sourceDefinitionId)
            || !attempt.targetEngineDefinitionId().equals(targetDefinitionId)
            || !"CALL_RETURNED_AWAITING_VERIFICATION".equals(
                row.getString("disposition")
            )
            || !row.getBoolean("engine_call_attempted")
            || !row.getBoolean("engine_call_returned")) {
            throw new IllegalStateException(
                "migration engine request/outcome relational lineage diverged"
            );
        }
        requireRequestPayload(
            row.getString("request_payload_json"),
            attempt,
            requestId,
            workerId,
            fenceId,
            fenceRevision
        );
        requireOutcomePayload(
            row.getString("outcome_payload_json"),
            attempt,
            outcomeId,
            requestId,
            workerId,
            outcomeAttemptRevision,
            outcomeFenceRevision
        );
        return new EngineLineage(requestId, outcomeId, workerId, fenceId);
    }

    private void requireRequestPayload(
        String payload,
        ApprovalMigrationAttempt attempt,
        UUID engineRequestId,
        String workerId,
        UUID fenceId,
        long fenceRevision
    ) {
        JsonNode node = readTree(payload, "migration engine request payload");
        if (!jsonText(node, "engineRequestId").equals(engineRequestId.toString())
            || !jsonText(node, "tenantId").equals(attempt.tenantId())
            || !jsonText(node, "intentId").equals(attempt.intentId().toString())
            || !jsonText(node, "attemptId").equals(attempt.attemptId().toString())
            || !jsonText(node, "workerId").equals(workerId)
            || !jsonText(node, "fenceId").equals(fenceId.toString())
            || jsonLong(node, "fenceRevision") != fenceRevision
            || !jsonText(node, "sourceEngineDefinitionId").equals(
                attempt.sourceEngineDefinitionId()
            )
            || !jsonText(node, "targetEngineDefinitionId").equals(
                attempt.targetEngineDefinitionId()
            )) {
            throw new IllegalStateException(
                "migration engine request relational and payload evidence diverged"
            );
        }
    }

    private void requireOutcomePayload(
        String payload,
        ApprovalMigrationAttempt attempt,
        UUID engineOutcomeId,
        UUID engineRequestId,
        String workerId,
        long expectedAttemptRevision,
        long expectedFenceRevision
    ) {
        JsonNode node = readTree(payload, "migration engine outcome payload");
        if (!jsonText(node, "engineOutcomeId").equals(engineOutcomeId.toString())
            || !jsonText(node, "engineRequestId").equals(engineRequestId.toString())
            || !jsonText(node, "tenantId").equals(attempt.tenantId())
            || !jsonText(node, "intentId").equals(attempt.intentId().toString())
            || !jsonText(node, "attemptId").equals(attempt.attemptId().toString())
            || !jsonText(node, "workerId").equals(workerId)
            || jsonLong(node, "expectedAttemptRevision") != expectedAttemptRevision
            || jsonLong(node, "expectedFenceRevision") != expectedFenceRevision
            || !"CALL_RETURNED_AWAITING_VERIFICATION".equals(
                jsonText(node, "disposition")
            )
            || !jsonBoolean(node, "engineCallAttempted")
            || !jsonBoolean(node, "engineCallReturned")) {
            throw new IllegalStateException(
                "migration engine outcome relational and payload evidence diverged"
            );
        }
    }

    private ApprovalMigrationCommandFence lockFence(String tenantId, UUID attemptId) {
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
            .orElseThrow(() -> conflict("active migration fence is missing"));
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

    private Optional<ApprovalMigrationExactVerification> findExisting(
        String tenantId,
        UUID attemptId
    ) {
        return jdbc.query("""
            select tenant_id,verification_id,intent_id,attempt_id,engine_request_id,
                   engine_outcome_id,worker_id,expected_attempt_revision,
                   expected_fence_revision,source_engine_definition_id,
                   target_engine_definition_id,classification,read_succeeded,
                   runtime_present,history_present,truncated,
                   observed_runtime_definition_id,observed_history_definition_id,
                   snapshot_hash,request_hash,verification_evidence_hash,recorded_at,
                   request_id,trace_id,payload_json
            from ap_process_migration_exact_verification
            where tenant_id=:tenantId and attempt_id=:attemptId
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("attemptId", values.bindUuid(attemptId)),
            (row, number) -> readExisting(row)).stream().findFirst();
    }

    private ApprovalMigrationExactVerification readExisting(ResultSet row)
        throws SQLException {
        ApprovalMigrationExactVerification evidence = json.read(
            row.getString("payload_json"),
            ApprovalMigrationExactVerification.class
        );
        ApprovalMigrationEngineSnapshot snapshot = evidence.snapshot();
        if (!evidence.tenantId().equals(row.getString("tenant_id"))
            || !evidence.verificationId().equals(values.uuid(row, "verification_id"))
            || !evidence.intentId().equals(values.uuid(row, "intent_id"))
            || !evidence.attemptId().equals(values.uuid(row, "attempt_id"))
            || !evidence.engineRequestId().equals(values.uuid(row, "engine_request_id"))
            || !evidence.engineOutcomeId().equals(values.uuid(row, "engine_outcome_id"))
            || !evidence.sourceEngineDefinitionId().equals(
                row.getString("source_engine_definition_id")
            )
            || !evidence.targetEngineDefinitionId().equals(
                row.getString("target_engine_definition_id")
            )
            || !evidence.classification().name().equals(row.getString("classification"))
            || snapshot.readSucceeded() != row.getBoolean("read_succeeded")
            || snapshot.runtimePresent() != row.getBoolean("runtime_present")
            || snapshot.historyPresent() != row.getBoolean("history_present")
            || snapshot.truncated() != row.getBoolean("truncated")
            || !Objects.equals(
                snapshot.runtimeEngineDefinitionId(),
                row.getString("observed_runtime_definition_id")
            )
            || !Objects.equals(
                snapshot.historicEngineDefinitionId(),
                row.getString("observed_history_definition_id")
            )
            || !snapshot.snapshotHash().equals(row.getString("snapshot_hash"))
            || !evidence.requestHash().equals(row.getString("request_hash"))
            || !evidence.verificationEvidenceHash().equals(
                row.getString("verification_evidence_hash")
            )
            || !evidence.recordedAt().equals(values.instant(row, "recorded_at"))
            || !evidence.requestId().equals(row.getString("request_id"))
            || !Objects.equals(evidence.traceId(), row.getString("trace_id"))) {
            throw new IllegalStateException(
                "exact verification relational and payload evidence diverged"
            );
        }
        if (row.getLong("expected_attempt_revision") < 1
            || row.getLong("expected_fence_revision") < 1
            || row.getString("worker_id") == null
            || row.getString("worker_id").isBlank()) {
            throw new IllegalStateException(
                "exact verification authority columns are invalid"
            );
        }
        return evidence;
    }

    private void insertEvidence(
        ApprovalMigrationExactVerification evidence,
        PreparedVerification prepared,
        long attemptRevision,
        long fenceRevision
    ) {
        ApprovalMigrationEngineSnapshot snapshot = evidence.snapshot();
        int inserted = jdbc.update("""
            insert into ap_process_migration_exact_verification (
             tenant_id,verification_id,intent_id,attempt_id,engine_request_id,engine_outcome_id,
             worker_id,expected_attempt_revision,expected_fence_revision,
             source_engine_definition_id,target_engine_definition_id,classification,
             read_succeeded,runtime_present,history_present,truncated,
             observed_runtime_definition_id,observed_history_definition_id,snapshot_hash,
             request_hash,verification_evidence_hash,recorded_at,request_id,trace_id,payload_json
            ) values (
             :tenantId,:verificationId,:intentId,:attemptId,:engineRequestId,:engineOutcomeId,
             :workerId,:attemptRevision,:fenceRevision,:sourceDefinition,:targetDefinition,
             :classification,:readSucceeded,:runtimePresent,:historyPresent,:truncated,
             :runtimeDefinition,:historyDefinition,:snapshotHash,:requestHash,:evidenceHash,
             :recordedAt,:requestId,:traceId,:payload
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", evidence.tenantId())
                .addValue("verificationId", values.bindUuid(evidence.verificationId()))
                .addValue("intentId", values.bindUuid(evidence.intentId()))
                .addValue("attemptId", values.bindUuid(evidence.attemptId()))
                .addValue("engineRequestId", values.bindUuid(evidence.engineRequestId()))
                .addValue("engineOutcomeId", values.bindUuid(evidence.engineOutcomeId()))
                .addValue("workerId", prepared.workerId())
                .addValue("attemptRevision", attemptRevision)
                .addValue("fenceRevision", fenceRevision)
                .addValue("sourceDefinition", evidence.sourceEngineDefinitionId())
                .addValue("targetDefinition", evidence.targetEngineDefinitionId())
                .addValue("classification", evidence.classification().name())
                .addValue("readSucceeded", snapshot.readSucceeded())
                .addValue("runtimePresent", snapshot.runtimePresent())
                .addValue("historyPresent", snapshot.historyPresent())
                .addValue("truncated", snapshot.truncated())
                .addValue("runtimeDefinition", snapshot.runtimeEngineDefinitionId())
                .addValue("historyDefinition", snapshot.historicEngineDefinitionId())
                .addValue("snapshotHash", snapshot.snapshotHash())
                .addValue("requestHash", evidence.requestHash())
                .addValue("evidenceHash", evidence.verificationEvidenceHash())
                .addValue("recordedAt", values.bindInstant(evidence.recordedAt()))
                .addValue("requestId", evidence.requestId())
                .addValue("traceId", evidence.traceId())
                .addValue("payload", json.write(evidence)));
        if (inserted != 1) {
            throw conflict("exact verification insert did not affect one row");
        }
    }

    private ApprovalMigrationAttempt transitionAttempt(
        ApprovalMigrationAttempt previous,
        ApprovalMigrationAttempt next,
        ApprovalMigrationAttemptEvent event
    ) {
        ApprovalMigrationAttemptEvent durable = event.withDurableEvidence(next, null);
        int updated = jdbc.update("""
            update ap_process_migration_attempt set
              status=:status,revision=:revision,engine_outcome=:engineOutcome,
              lease_actor=null,lease_owner=:leaseOwner,lease_until=:leaseUntil,
              engine_request_reference=:engineRequestReference,
              failure_class=:failureClass,error_summary=:errorSummary,
              payload_json=:payload,updated_at=:updatedAt
            where tenant_id=:tenantId and attempt_id=:attemptId
              and revision=:expectedRevision and status=:expectedStatus
            """, attemptParameters(next)
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
        int inserted = jdbc.update("""
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
        if (inserted != 1) {
            throw conflict("migration attempt event insert did not affect one row");
        }
    }

    private ApprovalMigrationAttemptEvent attemptEvent(
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
        ApprovalMigrationExactVerification evidence,
        String workerId
    ) {
        auditEvents.append(new AuditEvent(
            nextIdentifier("auditEventId"),
            evidence.tenantId(),
            workerId,
            "PROCESS_MIGRATION_EXACT_VERIFICATION_RECORDED",
            "APPROVAL_MIGRATION_ATTEMPT",
            evidence.attemptId().toString(),
            evidence.requestId(),
            evidence.traceId(),
            evidence.recordedAt(),
            Map.of(
                "verificationId",
                evidence.verificationId().toString(),
                "classification",
                evidence.classification().name(),
                "snapshotHash",
                evidence.snapshot().snapshotHash(),
                "verificationEvidenceHash",
                evidence.verificationEvidenceHash()
            )
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
            request.snapshot(),
            request.classification(),
            AuditHashCanonicalizer.canonicalInstant(request.happenedAt())
        );
    }

    private static void requireLineageFence(
        EngineLineage lineage,
        ApprovalMigrationCommandFence fence
    ) {
        if (!lineage.fenceId().equals(fence.fenceId())) {
            throw conflict("engine request fence lineage is stale");
        }
    }

    private static void requireFence(
        ApprovalMigrationCommandFence fence,
        String workerId,
        long expectedRevision,
        Instant happenedAt
    ) {
        if (fence.status() != ApprovalMigrationCommandFence.FenceStatus.ACTIVE
            || fence.revision() != expectedRevision
            || !workerId.equals(fence.leaseOwner())
            || !fence.leaseUntil().isAfter(happenedAt)) {
            throw conflict("verification command fence authority is stale");
        }
    }

    private static void requireExactReplay(
        ApprovalMigrationExactVerification evidence,
        String requestHash
    ) {
        if (!evidence.requestHash().equals(requestHash)) {
            throw conflict("changed-payload verification replay is forbidden");
        }
    }

    private static String requestHash(PrepareRequest request) {
        return sha256(String.join(
            "|",
            "m5-exact-verification-request-v1",
            request.tenantId(),
            request.attemptId().toString(),
            request.workerId(),
            Long.toString(request.expectedAttemptRevision()),
            Long.toString(request.expectedFenceRevision()),
            request.requestId()
        ));
    }

    private JsonNode readTree(String payload, String name) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (node == null || !node.isObject()) {
                throw new IllegalStateException(name + " is not a JSON object");
            }
            return node;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(name + " is invalid", exception);
        }
    }

    private static String jsonText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException("required JSON text field is invalid: " + field);
        }
        return value.textValue();
    }

    private static long jsonLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong()) {
            throw new IllegalStateException("required JSON integer field is invalid: " + field);
        }
        return value.longValue();
    }

    private static boolean jsonBoolean(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new IllegalStateException("required JSON boolean field is invalid: " + field);
        }
        return value.booleanValue();
    }

    private UUID nextIdentifier(String name) {
        return Objects.requireNonNull(
            identifiers.get(),
            "generated " + name + " must not be null"
        );
    }

    private static String bounded(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
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

    private static VerificationConflictException conflict(String message) {
        return new VerificationConflictException(message);
    }

    private record EngineLineage(
        UUID engineRequestId,
        UUID engineOutcomeId,
        String workerId,
        UUID fenceId
    ) {
    }
}
