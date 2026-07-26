package io.github.akaryc1b.approval.persistence.jdbc;

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
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** PostgreSQL implementation of the D4 exact verification transaction boundary. */
public final class JdbcApprovalMigrationExactVerificationStore
    implements ApprovalMigrationExactVerificationStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcApprovalMigrationJson json;
    private final JdbcApprovalMigrationProtocolStore protocol;
    private final JdbcApprovalInstanceCommandFence commandFence;
    private final TransactionTemplate transactions;
    private final AuditEventSink auditEvents;
    private final Supplier<UUID> identifiers;

    public JdbcApprovalMigrationExactVerificationStore(
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
    public PreparedVerification prepare(PrepareRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        try {
            return transactions.execute(status -> prepareOnce(request));
        } catch (VerificationConflictException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new VerificationConflictException("exact verification preparation conflict", exception);
        }
    }

    @Override
    public StoredVerification finalizeVerification(FinalizeRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        try {
            return transactions.execute(status -> finalizeOnce(request));
        } catch (VerificationConflictException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new VerificationConflictException("exact verification persistence conflict", exception);
        }
    }

    private PreparedVerification prepareOnce(PrepareRequest request) {
        String requestHash = requestHash(request);
        ApprovalMigrationAttempt current = lockAttempt(request.tenantId(), request.attemptId());
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
                new VerificationCommand(current.tenantId(), current.engineInstanceId(), java.util.List.of()),
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
        commandFence.acquireMigrationLock(current.tenantId(), current.approvalInstanceId());
        ApprovalMigrationCommandFence fence = lockFence(current.tenantId(), current.attemptId());
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
            new VerificationCommand(current.tenantId(), current.engineInstanceId(), java.util.List.of()),
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
            || !prepared.engineRequestId().toString().equals(current.engineRequestReference())) {
            throw conflict("attempt verification authority is stale");
        }
        EngineLineage lineage = lockEngineLineage(current);
        if (!lineage.engineRequestId().equals(prepared.engineRequestId())
            || !lineage.engineOutcomeId().equals(prepared.engineOutcomeId())
            || !lineage.workerId().equals(prepared.workerId())) {
            throw conflict("engine verification lineage is stale");
        }
        commandFence.acquireMigrationLock(current.tenantId(), current.approvalInstanceId());
        ApprovalMigrationCommandFence fence = lockFence(current.tenantId(), current.attemptId());
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
            ApprovalMigrationAttempt next = current.transitioned(new ApprovalMigrationAttemptTransition(
                AttemptStatus.RECONCILING,
                EngineOutcome.VERIFICATION_MISMATCH,
                null,
                null,
                current.engineRequestReference(),
                FailureClass.RECONCILIATION_REQUIRED,
                bounded("D4 " + derived.name() + " requires reconciliation", 1000),
                request.happenedAt()
            ));
            storedAttempt = protocol.transitionAttempt(
                next,
                current.revision(),
                attemptEvent(current, next, prepared.requestId(), prepared.traceId())
            );
        }
        appendAudit(evidence, prepared.workerId());
        return new StoredVerification(evidence, storedAttempt, false);
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

    private EngineLineage lockEngineLineage(ApprovalMigrationAttempt attempt) {
        return jdbc.query("""
            select r.engine_request_id,o.engine_outcome_id,r.worker_id,r.fence_id
            from ap_process_migration_engine_request r
            join ap_process_migration_engine_outcome o
              on o.tenant_id=r.tenant_id and o.engine_request_id=r.engine_request_id
            where r.tenant_id=:tenantId and r.attempt_id=:attemptId
              and o.disposition='CALL_RETURNED_AWAITING_VERIFICATION'
              and o.engine_call_attempted and o.engine_call_returned
            for update of r,o
            """, new MapSqlParameterSource()
                .addValue("tenantId", attempt.tenantId())
                .addValue("attemptId", attempt.attemptId()),
            (row, number) -> new EngineLineage(
                row.getObject("engine_request_id", UUID.class),
                row.getObject("engine_outcome_id", UUID.class),
                row.getString("worker_id"),
                row.getObject("fence_id", UUID.class)
            )).stream().findFirst()
            .filter(value -> value.engineRequestId().toString().equals(attempt.engineRequestReference()))
            .orElseThrow(() -> conflict("returned engine outcome lineage is missing"));
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
            .stream().findFirst().orElseThrow(() -> conflict("active migration fence is missing"));
    }

    private Optional<ApprovalMigrationExactVerification> findExisting(String tenantId, UUID attemptId) {
        return jdbc.query("""
            select payload_json::text from ap_process_migration_exact_verification
            where tenant_id=:tenantId and attempt_id=:attemptId
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("attemptId", attemptId),
            (row, number) -> json.read(row.getString(1), ApprovalMigrationExactVerification.class))
            .stream().findFirst();
    }

    private void insertEvidence(
        ApprovalMigrationExactVerification evidence,
        PreparedVerification prepared,
        long attemptRevision,
        long fenceRevision
    ) {
        ApprovalMigrationEngineSnapshot snapshot = evidence.snapshot();
        jdbc.update("""
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
             :recordedAt,:requestId,:traceId,cast(:payload as jsonb)
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", evidence.tenantId())
                .addValue("verificationId", evidence.verificationId())
                .addValue("intentId", evidence.intentId())
                .addValue("attemptId", evidence.attemptId())
                .addValue("engineRequestId", evidence.engineRequestId())
                .addValue("engineOutcomeId", evidence.engineOutcomeId())
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
                .addValue("recordedAt", JdbcApprovalMigrationJson.offset(evidence.recordedAt()))
                .addValue("requestId", evidence.requestId())
                .addValue("traceId", evidence.traceId())
                .addValue("payload", json.write(evidence)));
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

    private void appendAudit(ApprovalMigrationExactVerification evidence, String workerId) {
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
                "verificationId", evidence.verificationId().toString(),
                "classification", evidence.classification().name(),
                "snapshotHash", evidence.snapshot().snapshotHash(),
                "verificationEvidenceHash", evidence.verificationEvidenceHash()
            )
        ));
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

    private UUID nextIdentifier(String name) {
        return Objects.requireNonNull(identifiers.get(), "generated " + name + " must not be null");
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
