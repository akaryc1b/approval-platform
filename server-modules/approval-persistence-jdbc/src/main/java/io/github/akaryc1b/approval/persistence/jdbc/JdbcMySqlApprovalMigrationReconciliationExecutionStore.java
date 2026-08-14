package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationReconciliationStore;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttemptEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttemptTransition;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationExactVerification.ExactClassification;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.EngineOutcome;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.FailureClass;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.ReconciliationStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationReconciliation;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationReconciliationLease;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationReconciliationLease.ReconciliationLeaseStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationReconciliationLeaseEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationReconciliationObservation;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationReconciliationObservation.ReconciliationDisposition;
import io.github.akaryc1b.approval.engine.ProcessInstanceVerificationPort;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** MySQL 8.4 D6 durable UNKNOWN reconciliation boundary with no migration redispatch. */
public final class JdbcMySqlApprovalMigrationReconciliationExecutionStore
    implements ApprovalMigrationReconciliationStore {

    private static final String ATTEMPT_LOCK_NAMESPACE =
        "approval-migration-reconciliation:v1:";

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcApprovalMigrationJson json;
    private final JdbcDatabaseValueAdapter values;
    private final JdbcMySqlTransactionLockManager locks;
    private final TransactionTemplate transactions;
    private final AuditEventSink auditEvents;
    private final Supplier<UUID> identifiers;

    public JdbcMySqlApprovalMigrationReconciliationExecutionStore(
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
                "MySQL reconciliation execution store requires MySQL metadata"
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
    public PreparedReconciliation prepare(PrepareRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        try {
            return transactions.execute(status -> {
                locks.acquire(lockScope(request.tenantId(), request.attemptId()));
                return prepareOnce(request);
            });
        } catch (ReconciliationConflictException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw conflict("migration reconciliation preparation conflict", exception);
        }
    }

    @Override
    public StoredReconciliation finalizeObservation(FinalizeRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        try {
            return transactions.execute(status -> {
                PreparedReconciliation prepared = request.prepared();
                locks.acquire(lockScope(
                    prepared.attempt().tenantId(),
                    prepared.attempt().attemptId()
                ));
                return finalizeOnce(request);
            });
        } catch (ReconciliationConflictException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw conflict("migration reconciliation finalization conflict", exception);
        }
    }

    private PreparedReconciliation prepareOnce(PrepareRequest request) {
        String requestHash = requestHash(request);
        Optional<ApprovalMigrationReconciliationObservation> prior = findObservation(
            request.tenantId(),
            request.attemptId()
        );
        if (prior.isPresent()) {
            ApprovalMigrationReconciliationObservation observation = prior.orElseThrow();
            requireReplay(observation.requestHash(), requestHash);
            StoredReconciliation stored = stored(observation, true);
            return prepared(
                request,
                stored.attempt(),
                stored.reconciliation(),
                stored.lease(),
                requestHash,
                stored
            );
        }

        ApprovalMigrationAttempt attempt = lockAttempt(
            request.tenantId(),
            request.attemptId()
        );
        if (attempt.revision() != request.expectedAttemptRevision()
            || (attempt.status() != AttemptStatus.UNKNOWN
                && attempt.status() != AttemptStatus.RECONCILING)
            || attempt.engineOutcome() != EngineOutcome.UNKNOWN) {
            throw conflict(
                "attempt is not the exact durable UNKNOWN reconciliation revision"
            );
        }
        requireAmbiguousOutcome(attempt);

        ApprovalMigrationReconciliation reconciliation;
        if (attempt.status() == AttemptStatus.UNKNOWN) {
            if (!findReconciliations(
                attempt.tenantId(),
                attempt.attemptId()
            ).isEmpty()) {
                throw conflict(
                    "UNKNOWN attempt already has unexpected reconciliation evidence"
                );
            }
            reconciliation = openReconciliation(attempt, request, requestHash);
            appendReconciliation(reconciliation);
            ApprovalMigrationAttempt next = attempt.transitioned(
                new ApprovalMigrationAttemptTransition(
                    AttemptStatus.RECONCILING,
                    EngineOutcome.UNKNOWN,
                    null,
                    null,
                    attempt.engineRequestReference(),
                    FailureClass.RECONCILIATION_REQUIRED,
                    "D6 durable UNKNOWN reconciliation opened; migration redispatch is forbidden",
                    request.happenedAt()
                )
            );
            attempt = transitionAttempt(
                attempt,
                next,
                attemptEvent(
                    attempt,
                    next,
                    request.requestId(),
                    request.traceId()
                )
            );
        } else {
            reconciliation = requireOpenReconciliation(attempt);
        }

        Optional<ApprovalMigrationReconciliationLease> currentLease = lockLease(
            attempt.tenantId(),
            attempt.attemptId()
        );
        ApprovalMigrationReconciliationLease lease;
        ReconciliationLeaseStatus fromStatus;
        if (currentLease.isEmpty()) {
            lease = newLease(attempt, request, requestHash);
            insertLease(lease);
            fromStatus = null;
        } else {
            ApprovalMigrationReconciliationLease current = currentLease.orElseThrow();
            if (current.status() != ReconciliationLeaseStatus.ACTIVE
                || current.leaseUntil().isAfter(request.happenedAt())) {
                throw conflict(
                    "reconciliation lease is already active or completed"
                );
            }
            lease = takeOverLease(current, request, requestHash);
            updateLease(current, lease);
            fromStatus = ReconciliationLeaseStatus.ACTIVE;
        }
        insertLeaseEvent(leaseEvent(
            fromStatus,
            lease,
            request.happenedAt()
        ));
        appendAudit(
            attempt.tenantId(),
            request.workerId(),
            "PROCESS_MIGRATION_RECONCILIATION_PREPARED",
            attempt.attemptId().toString(),
            request.requestId(),
            request.traceId(),
            request.happenedAt(),
            Map.of(
                "reconciliationId", reconciliation.reconciliationId().toString(),
                "leaseId", lease.leaseId().toString(),
                "leaseRevision", Long.toString(lease.revision()),
                "requestHash", requestHash
            )
        );
        return prepared(
            request,
            attempt,
            reconciliation,
            lease,
            requestHash,
            null
        );
    }

    private StoredReconciliation finalizeOnce(FinalizeRequest request) {
        PreparedReconciliation prepared = request.prepared();
        Optional<ApprovalMigrationReconciliationObservation> prior = findObservation(
            prepared.attempt().tenantId(),
            prepared.attempt().attemptId()
        );
        if (prior.isPresent()) {
            ApprovalMigrationReconciliationObservation observation = prior.orElseThrow();
            requireReplay(observation.requestHash(), prepared.requestHash());
            return stored(observation, true);
        }

        ApprovalMigrationAttempt attempt = lockAttempt(
            prepared.attempt().tenantId(),
            prepared.attempt().attemptId()
        );
        ApprovalMigrationReconciliation reconciliation = lockReconciliation(
            prepared.reconciliation().tenantId(),
            prepared.reconciliation().reconciliationId()
        );
        ApprovalMigrationReconciliationLease lease = lockLeaseById(
            prepared.lease().tenantId(),
            prepared.lease().leaseId()
        );
        if (attempt.status() != AttemptStatus.RECONCILING
            || attempt.engineOutcome() != EngineOutcome.UNKNOWN
            || attempt.revision() != prepared.attempt().revision()
            || reconciliation.status() != ReconciliationStatus.OPEN
            || lease.status() != ReconciliationLeaseStatus.ACTIVE
            || lease.revision() != prepared.lease().revision()
            || !lease.workerId().equals(prepared.lease().workerId())
            || !lease.requestHash().equals(prepared.requestHash())
            || !lease.leaseUntil().isAfter(request.happenedAt())) {
            throw conflict(
                "reconciliation attempt, evidence or lease authority is stale"
            );
        }

        ReconciliationDisposition disposition =
            ApprovalMigrationReconciliationObservation.dispositionFor(
                request.classification()
            );
        UUID observationId = nextIdentifier("reconciliationObservationId");
        String evidenceHash = observationHash(
            observationId,
            attempt,
            reconciliation,
            lease,
            request.classification(),
            disposition,
            request.snapshot().snapshotHash(),
            prepared.requestHash()
        );
        ApprovalMigrationReconciliationObservation observation =
            new ApprovalMigrationReconciliationObservation(
                observationId,
                attempt.tenantId(),
                attempt.intentId(),
                attempt.attemptId(),
                reconciliation.reconciliationId(),
                lease.leaseId(),
                lease.workerId(),
                attempt.revision(),
                lease.revision(),
                attempt.sourceEngineDefinitionId(),
                attempt.targetEngineDefinitionId(),
                request.classification(),
                disposition,
                request.snapshot(),
                prepared.requestHash(),
                evidenceHash,
                request.happenedAt(),
                prepared.requestId(),
                prepared.traceId()
            );
        insertObservation(observation);

        ApprovalMigrationReconciliation resultReconciliation = resultReconciliation(
            reconciliation,
            observation,
            lease.workerId(),
            request.happenedAt()
        );
        appendReconciliation(resultReconciliation);
        ApprovalMigrationAttempt storedAttempt = closeAttemptIfSource(
            attempt,
            observation,
            prepared.requestId(),
            prepared.traceId(),
            request.happenedAt()
        );
        ApprovalMigrationReconciliationLease released = releaseLease(
            lease,
            request.happenedAt()
        );
        updateLease(lease, released);
        insertLeaseEvent(leaseEvent(
            ReconciliationLeaseStatus.ACTIVE,
            released,
            request.happenedAt()
        ));
        appendAudit(
            attempt.tenantId(),
            lease.workerId(),
            "PROCESS_MIGRATION_RECONCILIATION_OBSERVED",
            attempt.attemptId().toString(),
            prepared.requestId(),
            prepared.traceId(),
            request.happenedAt(),
            Map.of(
                "observationId", observation.observationId().toString(),
                "classification", observation.classification().name(),
                "disposition", observation.disposition().name(),
                "evidenceHash", observation.evidenceHash()
            )
        );
        return new StoredReconciliation(
            observation,
            storedAttempt,
            resultReconciliation,
            released,
            disposition,
            false
        );
    }

    private PreparedReconciliation prepared(
        PrepareRequest request,
        ApprovalMigrationAttempt attempt,
        ApprovalMigrationReconciliation reconciliation,
        ApprovalMigrationReconciliationLease lease,
        String requestHash,
        StoredReconciliation replay
    ) {
        return new PreparedReconciliation(
            attempt,
            reconciliation,
            lease,
            requestHash,
            new ProcessInstanceVerificationPort.VerificationCommand(
                attempt.tenantId(),
                attempt.engineInstanceId(),
                List.of()
            ),
            request.happenedAt(),
            request.requestId(),
            request.traceId(),
            replay
        );
    }

    private ApprovalMigrationReconciliation openReconciliation(
        ApprovalMigrationAttempt attempt,
        PrepareRequest request,
        String requestHash
    ) {
        UUID reconciliationId = nextIdentifier("reconciliationId");
        return new ApprovalMigrationReconciliation(
            reconciliationId,
            attempt.tenantId(),
            attempt.intentId(),
            attempt.attemptId(),
            1,
            ReconciliationStatus.OPEN,
            FailureClass.ENGINE_OUTCOME_UNKNOWN,
            "Ambiguous engine outcome requires one read-only reconciliation observation",
            sha256(String.join(
                "\u001f",
                "m5-reconciliation-open-v45",
                reconciliationId.toString(),
                attempt.tenantId(),
                attempt.attemptId().toString(),
                requestHash
            )),
            null,
            null,
            request.happenedAt(),
            null,
            request.requestId(),
            request.traceId(),
            "reconciliation-open:" + reconciliationId
        );
    }

    private ApprovalMigrationReconciliation requireOpenReconciliation(
        ApprovalMigrationAttempt attempt
    ) {
        List<ApprovalMigrationReconciliation> reconciliations = findReconciliations(
            attempt.tenantId(),
            attempt.attemptId()
        );
        if (reconciliations.isEmpty()) {
            throw conflict("RECONCILING attempt has no reconciliation evidence");
        }
        ApprovalMigrationReconciliation latest = reconciliations.get(
            reconciliations.size() - 1
        );
        if (latest.status() != ReconciliationStatus.OPEN) {
            throw conflict("reconciliation is not open for one-shot observation");
        }
        return latest;
    }

    private ApprovalMigrationReconciliation resultReconciliation(
        ApprovalMigrationReconciliation current,
        ApprovalMigrationReconciliationObservation observation,
        String workerId,
        Instant happenedAt
    ) {
        ReconciliationStatus status;
        String reason;
        boolean terminal;
        switch (observation.disposition()) {
            case SOURCE_CONFIRMED_NO_RETRY -> {
                status = ReconciliationStatus.RESOLVED_SOURCE;
                reason = "Source runtime remains authoritative; automatic migration retry is forbidden";
                terminal = true;
            }
            case SOURCE_TERMINAL_CONFIRMED_NO_RETRY -> {
                status = ReconciliationStatus.RESOLVED_TERMINAL;
                reason = "Source history is terminal; migration retry and fabricated rollback are forbidden";
                terminal = true;
            }
            case TARGET_CONFIRMED_BINDING_CAS_REQUIRED -> {
                status = ReconciliationStatus.MANUAL_REVIEW_REQUIRED;
                reason = "Target runtime observed; separate governed D5 binding CAS is required";
                terminal = false;
            }
            case TARGET_TERMINAL_BINDING_CAS_REQUIRED -> {
                status = ReconciliationStatus.MANUAL_REVIEW_REQUIRED;
                reason = "Target terminal history observed; separate governed binding decision is required";
                terminal = false;
            }
            case MANUAL_REVIEW_REQUIRED -> {
                status = ReconciliationStatus.MANUAL_REVIEW_REQUIRED;
                reason = "Incomplete, contradictory, missing or failed readback requires manual review";
                terminal = false;
            }
            default -> throw new IllegalStateException(
                "unsupported reconciliation disposition"
            );
        }
        UUID reconciliationId = nextIdentifier("reconciliationResultId");
        String evidenceHash = sha256(String.join(
            "\u001f",
            "m5-reconciliation-result-v45",
            reconciliationId.toString(),
            current.tenantId(),
            current.attemptId().toString(),
            Integer.toString(current.sequence() + 1),
            status.name(),
            observation.evidenceHash()
        ));
        return new ApprovalMigrationReconciliation(
            reconciliationId,
            current.tenantId(),
            current.intentId(),
            current.attemptId(),
            current.sequence() + 1,
            status,
            FailureClass.RECONCILIATION_REQUIRED,
            reason,
            evidenceHash,
            terminal ? observation.evidenceHash() : null,
            terminal ? workerId : null,
            happenedAt,
            terminal ? happenedAt : null,
            observation.requestId(),
            observation.traceId(),
            "reconciliation-observation:" + observation.observationId()
        );
    }

    private ApprovalMigrationAttempt closeAttemptIfSource(
        ApprovalMigrationAttempt attempt,
        ApprovalMigrationReconciliationObservation observation,
        String requestId,
        String traceId,
        Instant happenedAt
    ) {
        AttemptStatus status;
        String summary;
        if (observation.disposition()
            == ReconciliationDisposition.SOURCE_CONFIRMED_NO_RETRY) {
            status = AttemptStatus.BLOCKED_STALE;
            summary = "Source runtime confirmed; automatic migration retry is forbidden";
        } else if (observation.disposition()
            == ReconciliationDisposition.SOURCE_TERMINAL_CONFIRMED_NO_RETRY) {
            status = AttemptStatus.FAILED_TERMINAL;
            summary = "Source history terminal; migration retry is forbidden";
        } else {
            return attempt;
        }
        ApprovalMigrationAttempt next = attempt.transitioned(
            new ApprovalMigrationAttemptTransition(
                status,
                EngineOutcome.UNKNOWN,
                null,
                null,
                attempt.engineRequestReference(),
                FailureClass.RECONCILIATION_REQUIRED,
                summary,
                happenedAt
            )
        );
        return transitionAttempt(
            attempt,
            next,
            attemptEvent(attempt, next, requestId, traceId)
        );
    }

    private ApprovalMigrationReconciliationLease newLease(
        ApprovalMigrationAttempt attempt,
        PrepareRequest request,
        String requestHash
    ) {
        UUID leaseId = nextIdentifier("reconciliationLeaseId");
        String evidenceHash = leaseHash(
            leaseId,
            attempt.tenantId(),
            attempt.attemptId(),
            1,
            request.workerId(),
            request.leaseUntil(),
            requestHash
        );
        return new ApprovalMigrationReconciliationLease(
            leaseId,
            attempt.tenantId(),
            attempt.intentId(),
            attempt.attemptId(),
            ReconciliationLeaseStatus.ACTIVE,
            1,
            request.workerId(),
            request.leaseUntil(),
            request.happenedAt(),
            request.happenedAt(),
            null,
            requestHash,
            evidenceHash,
            request.requestId(),
            request.traceId()
        );
    }

    private ApprovalMigrationReconciliationLease takeOverLease(
        ApprovalMigrationReconciliationLease current,
        PrepareRequest request,
        String requestHash
    ) {
        return new ApprovalMigrationReconciliationLease(
            current.leaseId(),
            current.tenantId(),
            current.intentId(),
            current.attemptId(),
            ReconciliationLeaseStatus.ACTIVE,
            current.revision() + 1,
            request.workerId(),
            request.leaseUntil(),
            current.acquiredAt(),
            request.happenedAt(),
            null,
            requestHash,
            leaseHash(
                current.leaseId(),
                current.tenantId(),
                current.attemptId(),
                current.revision() + 1,
                request.workerId(),
                request.leaseUntil(),
                requestHash
            ),
            request.requestId(),
            request.traceId()
        );
    }

    private ApprovalMigrationReconciliationLease releaseLease(
        ApprovalMigrationReconciliationLease current,
        Instant happenedAt
    ) {
        return new ApprovalMigrationReconciliationLease(
            current.leaseId(),
            current.tenantId(),
            current.intentId(),
            current.attemptId(),
            ReconciliationLeaseStatus.RELEASED,
            current.revision() + 1,
            current.workerId(),
            current.leaseUntil(),
            current.acquiredAt(),
            happenedAt,
            happenedAt,
            current.requestHash(),
            leaseHash(
                current.leaseId(),
                current.tenantId(),
                current.attemptId(),
                current.revision() + 1,
                current.workerId(),
                current.leaseUntil(),
                current.requestHash()
            ),
            current.requestId(),
            current.traceId()
        );
    }

    private ApprovalMigrationReconciliationLeaseEvent leaseEvent(
        ReconciliationLeaseStatus fromStatus,
        ApprovalMigrationReconciliationLease lease,
        Instant happenedAt
    ) {
        UUID eventId = nextIdentifier("reconciliationLeaseEventId");
        String evidenceHash = sha256(String.join(
            "\u001f",
            "m5-reconciliation-lease-event-v45",
            eventId.toString(),
            lease.tenantId(),
            lease.leaseId().toString(),
            lease.attemptId().toString(),
            Long.toString(lease.revision()),
            fromStatus == null ? "" : fromStatus.name(),
            lease.status().name(),
            lease.workerId(),
            lease.requestHash()
        ));
        return new ApprovalMigrationReconciliationLeaseEvent(
            eventId,
            lease.tenantId(),
            lease.leaseId(),
            lease.attemptId(),
            lease.revision(),
            fromStatus,
            lease.status(),
            lease.workerId(),
            lease.leaseUntil(),
            happenedAt,
            lease.requestHash(),
            evidenceHash,
            lease.requestId(),
            lease.traceId()
        );
    }

    private ApprovalMigrationAttempt lockAttempt(String tenantId, UUID attemptId) {
        return jdbc.query("""
            select payload_json from ap_process_migration_attempt
            where tenant_id=:tenantId and attempt_id=:attemptId for update
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("attemptId", values.bindUuid(attemptId)),
            (row, number) -> json.read(
                row.getString(1),
                ApprovalMigrationAttempt.class
            )).stream().findFirst().orElseThrow(() -> conflict(
                "migration attempt does not exist"
            ));
    }

    private Optional<ApprovalMigrationAttempt> findAttempt(
        String tenantId,
        UUID attemptId
    ) {
        return jdbc.query("""
            select payload_json from ap_process_migration_attempt
            where tenant_id=:tenantId and attempt_id=:attemptId
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("attemptId", values.bindUuid(attemptId)),
            (row, number) -> json.read(
                row.getString(1),
                ApprovalMigrationAttempt.class
            )).stream().findFirst();
    }

    private ApprovalMigrationAttempt transitionAttempt(
        ApprovalMigrationAttempt current,
        ApprovalMigrationAttempt next,
        ApprovalMigrationAttemptEvent sparseEvent
    ) {
        ApprovalMigrationAttemptEvent event = sparseEvent.withDurableEvidence(
            next,
            null
        );
        int updated = jdbc.update("""
            update ap_process_migration_attempt set
              status=:status,revision=:revision,engine_outcome=:engineOutcome,
              lease_actor=null,lease_owner=:leaseOwner,lease_until=:leaseUntil,
              engine_request_reference=:engineRequestReference,
              failure_class=:failureClass,error_summary=:errorSummary,
              payload_json=:payload,updated_at=:updatedAt
            where tenant_id=:tenantId and attempt_id=:attemptId
              and revision=:expectedRevision and status=:fromStatus
            """, new MapSqlParameterSource()
                .addValue("status", next.status().name())
                .addValue("revision", next.revision())
                .addValue("engineOutcome", next.engineOutcome().name())
                .addValue("leaseOwner", next.leaseOwner())
                .addValue(
                    "leaseUntil",
                    values.bindNullableInstant(next.leaseUntil())
                )
                .addValue(
                    "engineRequestReference",
                    next.engineRequestReference()
                )
                .addValue("failureClass", next.failureClass().name())
                .addValue("errorSummary", next.errorSummary())
                .addValue("payload", json.write(next))
                .addValue(
                    "updatedAt",
                    values.bindInstant(canonicalInstant(next.updatedAt()))
                )
                .addValue("tenantId", next.tenantId())
                .addValue("attemptId", values.bindUuid(next.attemptId()))
                .addValue("expectedRevision", current.revision())
                .addValue("fromStatus", current.status().name()));
        if (updated != 1) {
            throw conflict("migration attempt changed during reconciliation");
        }
        appendAttemptEvent(event);
        return next;
    }

    private void appendAttemptEvent(ApprovalMigrationAttemptEvent event) {
        int inserted = jdbc.update("""
            insert into ap_process_migration_attempt_event (
              tenant_id,event_id,attempt_id,revision,from_status,to_status,
              engine_outcome,lease_actor,lease_owner,lease_until,
              engine_request_reference,failure_class,error_summary,payload_json,happened_at
            ) values (
              :tenantId,:eventId,:attemptId,:revision,:fromStatus,:toStatus,
              :engineOutcome,:leaseActor,:leaseOwner,:leaseUntil,
              :engineRequestReference,:failureClass,:errorSummary,:payload,:happenedAt
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
                .addValue(
                    "leaseUntil",
                    values.bindNullableInstant(event.leaseUntil())
                )
                .addValue(
                    "engineRequestReference",
                    event.engineRequestReference()
                )
                .addValue("failureClass", event.failureClass().name())
                .addValue("errorSummary", event.errorSummary())
                .addValue("payload", json.write(event))
                .addValue(
                    "happenedAt",
                    values.bindInstant(canonicalInstant(event.happenedAt()))
                ));
        if (inserted != 1) {
            throw conflict("migration attempt event was not inserted");
        }
    }

    private void requireAmbiguousOutcome(ApprovalMigrationAttempt attempt) {
        Integer count = jdbc.queryForObject("""
            select count(*) from ap_process_migration_engine_outcome outcome
            join ap_process_migration_engine_request request
              on request.tenant_id=outcome.tenant_id
             and request.engine_request_id=outcome.engine_request_id
            where outcome.tenant_id=:tenantId and outcome.attempt_id=:attemptId
              and request.engine_request_id=:requestReference
              and outcome.disposition='AMBIGUOUS_UNKNOWN'
              and outcome.engine_call_may_have_occurred=true
              and outcome.engine_call_returned=false
            """, new MapSqlParameterSource()
                .addValue("tenantId", attempt.tenantId())
                .addValue("attemptId", values.bindUuid(attempt.attemptId()))
                .addValue(
                    "requestReference",
                    attempt.engineRequestReference()
                ), Integer.class);
        if (count == null || count != 1) {
            throw conflict("durable ambiguous engine outcome evidence is missing");
        }
    }

    private List<ApprovalMigrationReconciliation> findReconciliations(
        String tenantId,
        UUID attemptId
    ) {
        return List.copyOf(jdbc.query("""
            select payload_json from ap_process_migration_reconciliation
            where tenant_id=:tenantId and attempt_id=:attemptId order by sequence
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("attemptId", values.bindUuid(attemptId)),
            (row, number) -> json.read(
                row.getString(1),
                ApprovalMigrationReconciliation.class
            )));
    }

    private void appendReconciliation(ApprovalMigrationReconciliation value) {
        Optional<ApprovalMigrationReconciliation> existing = findReconciliationById(
            value.tenantId(),
            value.reconciliationId()
        );
        if (existing.isPresent()) {
            if (!existing.orElseThrow().equals(value)) {
                throw conflict(
                    "reconciliation identity was reused with different evidence"
                );
            }
            return;
        }
        int inserted = jdbc.update("""
            insert into ap_process_migration_reconciliation (
              tenant_id,reconciliation_id,intent_id,attempt_id,sequence,status,
              evidence_hash,resolution_evidence_hash,payload_json,recorded_at,resolved_at
            ) values (
              :tenantId,:reconciliationId,:intentId,:attemptId,:sequence,:status,
              :evidenceHash,:resolutionHash,:payload,:recordedAt,:resolvedAt
            )
            """, reconciliationParameters(value));
        if (inserted != 1) {
            throw conflict("migration reconciliation evidence was not inserted");
        }
    }

    private Optional<ApprovalMigrationReconciliation> findReconciliationById(
        String tenantId,
        UUID reconciliationId
    ) {
        return jdbc.query("""
            select payload_json from ap_process_migration_reconciliation
            where tenant_id=:tenantId and reconciliation_id=:reconciliationId
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue(
                    "reconciliationId",
                    values.bindUuid(reconciliationId)
                ),
            (row, number) -> json.read(
                row.getString(1),
                ApprovalMigrationReconciliation.class
            )).stream().findFirst();
    }

    private MapSqlParameterSource reconciliationParameters(
        ApprovalMigrationReconciliation value
    ) {
        return new MapSqlParameterSource()
            .addValue("tenantId", value.tenantId())
            .addValue(
                "reconciliationId",
                values.bindUuid(value.reconciliationId())
            )
            .addValue("intentId", values.bindUuid(value.intentId()))
            .addValue("attemptId", values.bindUuid(value.attemptId()))
            .addValue("sequence", value.sequence())
            .addValue("status", value.status().name())
            .addValue("evidenceHash", value.evidenceHash())
            .addValue("resolutionHash", value.resolutionEvidenceHash())
            .addValue("payload", json.write(value))
            .addValue(
                "recordedAt",
                values.bindInstant(canonicalInstant(value.recordedAt()))
            )
            .addValue(
                "resolvedAt",
                values.bindNullableInstant(value.resolvedAt())
            );
    }

    private Optional<ApprovalMigrationReconciliationLease> lockLease(
        String tenantId,
        UUID attemptId
    ) {
        return jdbc.query("""
            select payload_json from ap_process_migration_reconciliation_lease
            where tenant_id=:tenantId and attempt_id=:attemptId for update
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("attemptId", values.bindUuid(attemptId)),
            (row, number) -> json.read(
                row.getString(1),
                ApprovalMigrationReconciliationLease.class
            )).stream().findFirst();
    }

    private ApprovalMigrationReconciliationLease lockLeaseById(
        String tenantId,
        UUID leaseId
    ) {
        return jdbc.query("""
            select payload_json from ap_process_migration_reconciliation_lease
            where tenant_id=:tenantId and lease_id=:leaseId for update
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("leaseId", values.bindUuid(leaseId)),
            (row, number) -> json.read(
                row.getString(1),
                ApprovalMigrationReconciliationLease.class
            )).stream().findFirst().orElseThrow(() -> conflict(
                "reconciliation lease does not exist"
            ));
    }

    private ApprovalMigrationReconciliation lockReconciliation(
        String tenantId,
        UUID reconciliationId
    ) {
        return jdbc.query("""
            select payload_json from ap_process_migration_reconciliation
            where tenant_id=:tenantId and reconciliation_id=:reconciliationId for update
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue(
                    "reconciliationId",
                    values.bindUuid(reconciliationId)
                ),
            (row, number) -> json.read(
                row.getString(1),
                ApprovalMigrationReconciliation.class
            )).stream().findFirst().orElseThrow(() -> conflict(
                "reconciliation evidence does not exist"
            ));
    }

    private void insertLease(ApprovalMigrationReconciliationLease lease) {
        int inserted = jdbc.update("""
            insert into ap_process_migration_reconciliation_lease (
             tenant_id,lease_id,intent_id,attempt_id,status,revision,worker_id,lease_until,
             acquired_at,updated_at,released_at,request_hash,evidence_hash,request_id,trace_id,
             payload_json
            ) values (
             :tenantId,:leaseId,:intentId,:attemptId,:status,:revision,:workerId,:leaseUntil,
             :acquiredAt,:updatedAt,:releasedAt,:requestHash,:evidenceHash,:requestId,:traceId,
             :payload
            )
            """, leaseParameters(lease));
        if (inserted != 1) {
            throw conflict("reconciliation lease was not inserted");
        }
    }

    private void updateLease(
        ApprovalMigrationReconciliationLease current,
        ApprovalMigrationReconciliationLease next
    ) {
        int updated = jdbc.update("""
            update ap_process_migration_reconciliation_lease set
             status=:status,revision=:revision,worker_id=:workerId,lease_until=:leaseUntil,
             updated_at=:updatedAt,released_at=:releasedAt,request_hash=:requestHash,
             evidence_hash=:evidenceHash,request_id=:requestId,trace_id=:traceId,
             payload_json=:payload
            where tenant_id=:tenantId and lease_id=:leaseId and revision=:expectedRevision
             and status='ACTIVE'
            """, leaseParameters(next)
                .addValue("expectedRevision", current.revision()));
        if (updated != 1) {
            throw conflict("reconciliation lease revision changed");
        }
    }

    private MapSqlParameterSource leaseParameters(
        ApprovalMigrationReconciliationLease value
    ) {
        return new MapSqlParameterSource()
            .addValue("tenantId", value.tenantId())
            .addValue("leaseId", values.bindUuid(value.leaseId()))
            .addValue("intentId", values.bindUuid(value.intentId()))
            .addValue("attemptId", values.bindUuid(value.attemptId()))
            .addValue("status", value.status().name())
            .addValue("revision", value.revision())
            .addValue("workerId", value.workerId())
            .addValue(
                "leaseUntil",
                values.bindInstant(canonicalInstant(value.leaseUntil()))
            )
            .addValue(
                "acquiredAt",
                values.bindInstant(canonicalInstant(value.acquiredAt()))
            )
            .addValue(
                "updatedAt",
                values.bindInstant(canonicalInstant(value.updatedAt()))
            )
            .addValue(
                "releasedAt",
                values.bindNullableInstant(value.releasedAt())
            )
            .addValue("requestHash", value.requestHash())
            .addValue("evidenceHash", value.evidenceHash())
            .addValue("requestId", value.requestId())
            .addValue("traceId", value.traceId())
            .addValue("payload", json.write(value));
    }

    private void insertLeaseEvent(
        ApprovalMigrationReconciliationLeaseEvent event
    ) {
        int inserted = jdbc.update("""
            insert into ap_process_migration_reconciliation_lease_event (
             tenant_id,event_id,lease_id,attempt_id,revision,from_status,to_status,worker_id,
             lease_until,happened_at,request_hash,evidence_hash,request_id,trace_id,payload_json
            ) values (
             :tenantId,:eventId,:leaseId,:attemptId,:revision,:fromStatus,:toStatus,:workerId,
             :leaseUntil,:happenedAt,:requestHash,:evidenceHash,:requestId,:traceId,:payload
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", event.tenantId())
                .addValue("eventId", values.bindUuid(event.eventId()))
                .addValue("leaseId", values.bindUuid(event.leaseId()))
                .addValue("attemptId", values.bindUuid(event.attemptId()))
                .addValue("revision", event.revision())
                .addValue(
                    "fromStatus",
                    event.fromStatus() == null ? null : event.fromStatus().name()
                )
                .addValue("toStatus", event.toStatus().name())
                .addValue("workerId", event.workerId())
                .addValue(
                    "leaseUntil",
                    values.bindInstant(canonicalInstant(event.leaseUntil()))
                )
                .addValue(
                    "happenedAt",
                    values.bindInstant(canonicalInstant(event.happenedAt()))
                )
                .addValue("requestHash", event.requestHash())
                .addValue("evidenceHash", event.evidenceHash())
                .addValue("requestId", event.requestId())
                .addValue("traceId", event.traceId())
                .addValue("payload", json.write(event)));
        if (inserted != 1) {
            throw conflict("reconciliation lease event was not inserted");
        }
    }

    private void insertObservation(
        ApprovalMigrationReconciliationObservation value
    ) {
        int inserted = jdbc.update("""
            insert into ap_process_migration_reconciliation_observation (
             tenant_id,observation_id,intent_id,attempt_id,reconciliation_id,lease_id,worker_id,
             expected_attempt_revision,expected_lease_revision,source_engine_definition_id,
             target_engine_definition_id,classification,disposition,read_succeeded,snapshot_hash,
             request_hash,evidence_hash,recorded_at,request_id,trace_id,payload_json
            ) values (
             :tenantId,:observationId,:intentId,:attemptId,:reconciliationId,:leaseId,:workerId,
             :attemptRevision,:leaseRevision,:sourceDefinitionId,:targetDefinitionId,
             :classification,:disposition,:readSucceeded,:snapshotHash,:requestHash,:evidenceHash,
             :recordedAt,:requestId,:traceId,:payload
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", value.tenantId())
                .addValue(
                    "observationId",
                    values.bindUuid(value.observationId())
                )
                .addValue("intentId", values.bindUuid(value.intentId()))
                .addValue("attemptId", values.bindUuid(value.attemptId()))
                .addValue(
                    "reconciliationId",
                    values.bindUuid(value.reconciliationId())
                )
                .addValue("leaseId", values.bindUuid(value.leaseId()))
                .addValue("workerId", value.workerId())
                .addValue(
                    "attemptRevision",
                    value.expectedAttemptRevision()
                )
                .addValue("leaseRevision", value.expectedLeaseRevision())
                .addValue(
                    "sourceDefinitionId",
                    value.sourceEngineDefinitionId()
                )
                .addValue(
                    "targetDefinitionId",
                    value.targetEngineDefinitionId()
                )
                .addValue("classification", value.classification().name())
                .addValue("disposition", value.disposition().name())
                .addValue(
                    "readSucceeded",
                    value.snapshot().readSucceeded()
                )
                .addValue("snapshotHash", value.snapshot().snapshotHash())
                .addValue("requestHash", value.requestHash())
                .addValue("evidenceHash", value.evidenceHash())
                .addValue(
                    "recordedAt",
                    values.bindInstant(canonicalInstant(value.recordedAt()))
                )
                .addValue("requestId", value.requestId())
                .addValue("traceId", value.traceId())
                .addValue("payload", json.write(value)));
        if (inserted != 1) {
            throw conflict("reconciliation observation was not inserted");
        }
    }

    private Optional<ApprovalMigrationReconciliationObservation> findObservation(
        String tenantId,
        UUID attemptId
    ) {
        return jdbc.query("""
            select payload_json from ap_process_migration_reconciliation_observation
            where tenant_id=:tenantId and attempt_id=:attemptId
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("attemptId", values.bindUuid(attemptId)),
            (row, number) -> json.read(
                row.getString(1),
                ApprovalMigrationReconciliationObservation.class
            )).stream().findFirst();
    }

    private StoredReconciliation stored(
        ApprovalMigrationReconciliationObservation observation,
        boolean replayed
    ) {
        ApprovalMigrationAttempt attempt = findAttempt(
            observation.tenantId(),
            observation.attemptId()
        ).orElseThrow(() -> conflict(
            "replayed reconciliation attempt disappeared"
        ));
        ApprovalMigrationReconciliation reconciliation = findReconciliations(
            observation.tenantId(),
            observation.attemptId()
        ).stream()
            .filter(value -> value.sequence() > 1)
            .reduce((left, right) -> right)
            .orElseThrow(() -> conflict(
                "replayed reconciliation result disappeared"
            ));
        ApprovalMigrationReconciliationLease lease = lockLeaseById(
            observation.tenantId(),
            observation.leaseId()
        );
        return new StoredReconciliation(
            observation,
            attempt,
            reconciliation,
            lease,
            observation.disposition(),
            replayed
        );
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
        String tenantId,
        String operatorId,
        String action,
        String aggregateId,
        String requestId,
        String traceId,
        Instant happenedAt,
        Map<String, String> attributes
    ) {
        auditEvents.append(new AuditEvent(
            nextIdentifier("auditEventId"),
            tenantId,
            operatorId,
            action,
            "APPROVAL_MIGRATION_ATTEMPT",
            aggregateId,
            requestId,
            traceId,
            happenedAt,
            attributes
        ));
    }

    private static String requestHash(PrepareRequest request) {
        return sha256(String.join(
            "|",
            "m5-reconciliation-request-v1",
            request.tenantId(),
            request.attemptId().toString(),
            request.workerId(),
            Long.toString(request.expectedAttemptRevision()),
            request.requestId()
        ));
    }

    private static String leaseHash(
        UUID leaseId,
        String tenantId,
        UUID attemptId,
        long revision,
        String workerId,
        Instant leaseUntil,
        String requestHash
    ) {
        return sha256(String.join(
            "\u001f",
            "m5-reconciliation-lease-v45",
            leaseId.toString(),
            tenantId,
            attemptId.toString(),
            Long.toString(revision),
            workerId,
            leaseUntil.toString(),
            requestHash
        ));
    }

    private static String observationHash(
        UUID observationId,
        ApprovalMigrationAttempt attempt,
        ApprovalMigrationReconciliation reconciliation,
        ApprovalMigrationReconciliationLease lease,
        ExactClassification classification,
        ReconciliationDisposition disposition,
        String snapshotHash,
        String requestHash
    ) {
        return sha256(String.join(
            "\u001f",
            "m5-reconciliation-observation-v45",
            observationId.toString(),
            attempt.tenantId(),
            attempt.intentId().toString(),
            attempt.attemptId().toString(),
            reconciliation.reconciliationId().toString(),
            lease.leaseId().toString(),
            lease.workerId(),
            Long.toString(attempt.revision()),
            Long.toString(lease.revision()),
            attempt.sourceEngineDefinitionId(),
            attempt.targetEngineDefinitionId(),
            classification.name(),
            disposition.name(),
            snapshotHash,
            requestHash
        ));
    }

    private static void requireReplay(String stored, String requested) {
        if (!stored.equals(requested)) {
            throw conflict(
                "changed-payload migration reconciliation replay is forbidden"
            );
        }
    }

    private UUID nextIdentifier(String name) {
        return Objects.requireNonNull(
            identifiers.get(),
            "generated " + name + " must not be null"
        );
    }

    private static String lockScope(String tenantId, UUID attemptId) {
        return ATTEMPT_LOCK_NAMESPACE + tenantId + ':' + attemptId;
    }

    private static Instant canonicalInstant(Instant value) {
        return AuditHashCanonicalizer.canonicalInstant(value);
    }

    private static ReconciliationConflictException conflict(String message) {
        return new ReconciliationConflictException(message);
    }

    private static ReconciliationConflictException conflict(
        String message,
        Throwable cause
    ) {
        return new ReconciliationConflictException(message, cause);
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
}
