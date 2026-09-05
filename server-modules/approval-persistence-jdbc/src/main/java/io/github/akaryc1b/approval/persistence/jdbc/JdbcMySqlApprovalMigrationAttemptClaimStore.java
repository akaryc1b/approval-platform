package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.ClaimRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.ClaimResult;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.MigrationAttemptClaimConflictException;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.RenewalRequest;
import io.github.akaryc1b.approval.application.port.ApprovalMigrationAttemptClaimStore.RenewalResult;
import io.github.akaryc1b.approval.application.port.AuditEventSink;
import io.github.akaryc1b.approval.domain.audit.AuditEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalCommandOperation;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttempt;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttemptEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationAttemptTransition;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationClaimBatch;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationCommandFence;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationCommandFenceEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationIntentEvent;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.AttemptStatus;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.FailureClass;
import io.github.akaryc1b.approval.domain.migration.ApprovalMigrationProtocol.IntentStatus;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** MySQL 8.4 bounded claim, lease renewal and expiry-takeover authority. */
public final class JdbcMySqlApprovalMigrationAttemptClaimStore
    implements ApprovalMigrationAttemptClaimStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcApprovalMigrationJson json;
    private final JdbcDatabaseValueAdapter values;
    private final JdbcMySqlApprovalInstanceCommandFence commandFence;
    private final TransactionTemplate transactions;
    private final AuditEventSink auditEvents;
    private final Supplier<UUID> identifiers;

    public JdbcMySqlApprovalMigrationAttemptClaimStore(
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
                "JdbcMySqlApprovalMigrationAttemptClaimStore requires MySQL 8.4"
            );
        }
        jdbc = new NamedParameterJdbcTemplate(source);
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
    public ClaimResult claim(ClaimRequest request) {
        ClaimRequest exact = canonicalClaimRequest(
            Objects.requireNonNull(request, "request must not be null")
        );
        Optional<ClaimResult> replay = findReplay(exact);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        try {
            return transactions.execute(status -> claimOnce(exact));
        } catch (DataAccessException exception) {
            Optional<ClaimResult> concurrentReplay = findReplay(exact);
            if (concurrentReplay.isPresent()) {
                return concurrentReplay.orElseThrow();
            }
            throw new MigrationAttemptClaimConflictException(
                "migration attempt claim persistence conflict",
                exception
            );
        }
    }

    @Override
    public RenewalResult renew(RenewalRequest request) {
        RenewalRequest exact = canonicalRenewalRequest(
            Objects.requireNonNull(request, "request must not be null")
        );
        try {
            return transactions.execute(status -> renewOnce(exact));
        } catch (DataAccessException exception) {
            throw new MigrationAttemptClaimConflictException(
                "migration command-fence renewal conflict",
                exception
            );
        }
    }

    @Override
    public Optional<ApprovalMigrationCommandFence> findFence(
        String tenantId,
        UUID attemptId
    ) {
        return queryFence(
            "tenant_id=:tenantId and attempt_id=:attemptId",
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue("attemptId", values.bindUuid(Objects.requireNonNull(
                    attemptId,
                    "attemptId must not be null"
                )))
        );
    }

    private ClaimResult claimOnce(ClaimRequest request) {
        Optional<ClaimResult> replay = findReplay(request);
        if (replay.isPresent()) {
            return replay.orElseThrow();
        }
        ApprovalMigrationIntent intent = lockIntentAuthority(
            request.tenantId(),
            request.intentId()
        );
        if ((intent.status() != IntentStatus.PENDING
            && intent.status() != IntentStatus.RUNNING)
            || !request.claimedAt().isBefore(intent.expiresAt())) {
            throw conflict("migration intent is not claimable");
        }

        List<ApprovalMigrationAttempt> candidates = findCandidates(request);
        List<ApprovalMigrationAttempt> claimed = new ArrayList<>();
        List<ApprovalMigrationCommandFence> fences = new ArrayList<>();
        for (ApprovalMigrationAttempt candidate : candidates) {
            commandFence.acquireMigrationLock(
                candidate.tenantId(),
                candidate.approvalInstanceId()
            );
            Optional<ApprovalMigrationCommandFence> currentFence =
                findActiveFenceForUpdate(
                    candidate.tenantId(),
                    candidate.approvalInstanceId()
                );
            if (candidate.status() == AttemptStatus.PENDING) {
                if (currentFence.isPresent()) {
                    continue;
                }
                ClaimedPair pair = initialClaim(candidate, request);
                claimed.add(pair.attempt());
                fences.add(pair.fence());
            } else if (candidate.status() == AttemptStatus.CLAIMED
                && !Objects.requireNonNull(
                    candidate.leaseUntil(),
                    "claimed attempt lease must not be null"
                ).isAfter(request.claimedAt())) {
                ApprovalMigrationCommandFence existing = currentFence
                    .filter(value -> value.attemptId().equals(candidate.attemptId()))
                    .orElseThrow(() -> conflict(
                        "expired claimed attempt has no matching active command fence"
                    ));
                ClaimedPair pair = renewOrTakeOver(candidate, existing, request);
                claimed.add(pair.attempt());
                fences.add(pair.fence());
            }
        }

        if (!claimed.isEmpty() && intent.status() == IntentStatus.PENDING) {
            transitionIntentToRunning(intent, request);
        }

        ApprovalMigrationClaimBatch batch = new ApprovalMigrationClaimBatch(
            nextIdentifier("claimBatchId"),
            request.tenantId(),
            request.intentId(),
            request.workerId(),
            request.limit(),
            claimed.stream().map(ApprovalMigrationAttempt::attemptId).toList(),
            fences.stream().map(ApprovalMigrationCommandFence::fenceId).toList(),
            request.requestHash(),
            request.claimedAt(),
            request.requestId(),
            request.traceId()
        );
        insertBatch(batch);
        appendClaimAudit(batch);
        return new ClaimResult(batch, claimed, fences, false);
    }

    private RenewalResult renewOnce(RenewalRequest request) {
        ApprovalMigrationAttempt current = lockAttempt(
            request.tenantId(),
            request.attemptId()
        );
        if (current.status() != AttemptStatus.CLAIMED) {
            throw conflict("only a claimed migration attempt may renew or take over a lease");
        }
        commandFence.acquireMigrationLock(
            current.tenantId(),
            current.approvalInstanceId()
        );
        ApprovalMigrationCommandFence fence = lockFence(
            current.tenantId(),
            current.attemptId()
        );
        ApprovalMigrationCommandFence nextFence = fence.renewed(
            request.workerId(),
            request.leaseUntil(),
            request.happenedAt()
        );
        ApprovalMigrationAttempt nextAttempt = current.transitioned(
            new ApprovalMigrationAttemptTransition(
                AttemptStatus.CLAIMED,
                current.engineOutcome(),
                request.workerId(),
                request.leaseUntil(),
                current.engineRequestReference(),
                FailureClass.NONE,
                null,
                request.happenedAt()
            )
        );
        ApprovalMigrationAttempt stored = transitionAttempt(
            current,
            nextAttempt,
            request.workerId(),
            sparseAttemptEvent(
                nextAttempt,
                AttemptStatus.CLAIMED,
                request.requestId(),
                request.traceId()
            )
        );
        updateFence(
            fence,
            nextFence,
            request.workerId(),
            request.requestId(),
            request.traceId()
        );
        appendRenewalAudit(nextFence, request);
        return new RenewalResult(stored, nextFence);
    }

    private ClaimedPair initialClaim(
        ApprovalMigrationAttempt candidate,
        ClaimRequest request
    ) {
        ApprovalMigrationAttempt nextAttempt = candidate.transitioned(
            new ApprovalMigrationAttemptTransition(
                AttemptStatus.CLAIMED,
                candidate.engineOutcome(),
                request.workerId(),
                request.leaseUntil(),
                null,
                FailureClass.NONE,
                null,
                request.claimedAt()
            )
        );
        ApprovalMigrationAttempt stored = transitionAttempt(
            candidate,
            nextAttempt,
            request.workerId(),
            sparseAttemptEvent(
                nextAttempt,
                AttemptStatus.PENDING,
                request.requestId(),
                request.traceId()
            )
        );
        ApprovalMigrationCommandFence fence = new ApprovalMigrationCommandFence(
            nextIdentifier("fenceId"),
            candidate.tenantId(),
            candidate.approvalInstanceId(),
            candidate.attemptId(),
            ApprovalCommandOperation.MIGRATION,
            ApprovalMigrationCommandFence.FenceStatus.ACTIVE,
            1,
            request.workerId(),
            request.leaseUntil(),
            request.requestId() + ':' + candidate.attemptId(),
            request.requestHash(),
            request.claimedAt(),
            request.claimedAt(),
            null,
            request.requestId(),
            request.traceId()
        );
        insertFence(fence, request.workerId());
        return new ClaimedPair(stored, fence);
    }

    private ClaimedPair renewOrTakeOver(
        ApprovalMigrationAttempt candidate,
        ApprovalMigrationCommandFence currentFence,
        ClaimRequest request
    ) {
        ApprovalMigrationCommandFence nextFence = currentFence.renewed(
            request.workerId(),
            request.leaseUntil(),
            request.claimedAt()
        );
        ApprovalMigrationAttempt nextAttempt = candidate.transitioned(
            new ApprovalMigrationAttemptTransition(
                AttemptStatus.CLAIMED,
                candidate.engineOutcome(),
                request.workerId(),
                request.leaseUntil(),
                candidate.engineRequestReference(),
                FailureClass.NONE,
                null,
                request.claimedAt()
            )
        );
        ApprovalMigrationAttempt stored = transitionAttempt(
            candidate,
            nextAttempt,
            request.workerId(),
            sparseAttemptEvent(
                nextAttempt,
                AttemptStatus.CLAIMED,
                request.requestId(),
                request.traceId()
            )
        );
        updateFence(
            currentFence,
            nextFence,
            request.workerId(),
            request.requestId(),
            request.traceId()
        );
        return new ClaimedPair(stored, nextFence);
    }

    private List<ApprovalMigrationAttempt> findCandidates(ClaimRequest request) {
        return List.copyOf(jdbc.query("""
            select tenant_id,attempt_id,intent_id,status,revision,lease_owner,lease_until,
                   engine_request_reference,failure_class,error_summary,created_at,updated_at,
                   payload_json
            from ap_process_migration_attempt
            where tenant_id=:tenantId and intent_id=:intentId
              and (status='PENDING'
                or (status='CLAIMED' and lease_until<=:claimedAt))
            order by created_at,attempt_id
            limit :limit
            for update skip locked
            """, new MapSqlParameterSource()
                .addValue("tenantId", request.tenantId())
                .addValue("intentId", values.bindUuid(request.intentId()))
                .addValue("claimedAt", values.bindInstant(request.claimedAt()))
                .addValue("limit", request.limit()),
            (row, number) -> readAttempt(row)));
    }

    private ApprovalMigrationIntent lockIntentAuthority(
        String tenantId,
        UUID intentId
    ) {
        return jdbc.query("""
            select i.tenant_id,i.intent_id,i.idempotency_key,i.plan_id,i.plan_hash,
                   i.definition_key,i.source_release_version,i.source_package_hash,
                   i.target_release_version,i.target_package_hash,i.status,i.revision,
                   i.intent_evidence_hash,i.created_at,i.updated_at,
                   p.selected_instance_count,p.expires_at,
                   c.consumed_by,c.reason,c.request_id,c.trace_id,c.audit_chain_reference
            from ap_process_migration_intent i
            join ap_process_migration_plan p
              on p.tenant_id=i.tenant_id and p.plan_id=i.plan_id
             and p.plan_hash=i.plan_hash and p.status='CONSUMED'
            join ap_process_migration_plan_consumption c
              on c.tenant_id=i.tenant_id and c.plan_id=i.plan_id
             and c.plan_hash=i.plan_hash and c.intent_id=i.intent_id
             and c.intent_evidence_hash=i.intent_evidence_hash
            where i.tenant_id=:tenantId and i.intent_id=:intentId
            for update
            """, new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId"))
                .addValue("intentId", values.bindUuid(Objects.requireNonNull(
                    intentId,
                    "intentId must not be null"
                ))),
            (row, number) -> new ApprovalMigrationIntent(
                values.uuid(row, "intent_id"),
                row.getString("tenant_id"),
                values.uuid(row, "plan_id"),
                row.getString("plan_hash"),
                row.getString("definition_key"),
                row.getInt("source_release_version"),
                row.getString("source_package_hash"),
                row.getInt("target_release_version"),
                row.getString("target_package_hash"),
                row.getInt("selected_instance_count"),
                IntentStatus.valueOf(row.getString("status")),
                row.getLong("revision"),
                row.getString("idempotency_key"),
                row.getString("intent_evidence_hash"),
                row.getString("consumed_by"),
                row.getString("reason"),
                values.instant(row, "expires_at"),
                values.instant(row, "created_at"),
                values.instant(row, "updated_at"),
                row.getString("request_id"),
                row.getString("trace_id"),
                row.getString("audit_chain_reference")
            )).stream().findFirst()
            .orElseThrow(() -> conflict(
                "migration intent or exact consumed-plan authority was not found"
            ));
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
                .addValue("attemptId", values.bindUuid(Objects.requireNonNull(
                    attemptId,
                    "attemptId must not be null"
                ))),
            (row, number) -> readAttempt(row)).stream().findFirst()
            .orElseThrow(() -> conflict(
                "migration attempt was not found for lease renewal"
            ));
    }

    private Optional<ApprovalMigrationAttempt> findAttempt(
        String tenantId,
        UUID attemptId
    ) {
        return jdbc.query("""
            select tenant_id,attempt_id,intent_id,status,revision,lease_owner,lease_until,
                   engine_request_reference,failure_class,error_summary,created_at,updated_at,
                   payload_json
            from ap_process_migration_attempt
            where tenant_id=:tenantId and attempt_id=:attemptId
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("attemptId", values.bindUuid(attemptId)),
            (row, number) -> readAttempt(row)).stream().findFirst();
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

    private void transitionIntentToRunning(
        ApprovalMigrationIntent current,
        ClaimRequest request
    ) {
        ApprovalMigrationIntent running = current.transitioned(
            IntentStatus.RUNNING,
            request.claimedAt()
        );
        ApprovalMigrationIntentEvent event = new ApprovalMigrationIntentEvent(
            nextIdentifier("intentEventId"),
            current.tenantId(),
            current.intentId(),
            running.revision(),
            IntentStatus.PENDING,
            IntentStatus.RUNNING,
            "Bounded migration attempt claim started the intent",
            request.workerId(),
            request.claimedAt(),
            request.requestId(),
            request.traceId(),
            current.auditChainReference()
        );
        int updated = jdbc.update("""
            update ap_process_migration_intent set
              status=:status,revision=:revision,payload_json=:payload,updated_at=:updatedAt
            where tenant_id=:tenantId and intent_id=:intentId
              and revision=:expectedRevision and status='PENDING'
            """, new MapSqlParameterSource()
                .addValue("status", running.status().name())
                .addValue("revision", running.revision())
                .addValue("payload", json.write(running))
                .addValue("updatedAt", values.bindInstant(running.updatedAt()))
                .addValue("tenantId", running.tenantId())
                .addValue("intentId", values.bindUuid(running.intentId()))
                .addValue("expectedRevision", current.revision()));
        if (updated != 1) {
            throw conflict("migration intent revision or state conflict");
        }
        appendIntentEvent(event);
    }

    private void appendIntentEvent(ApprovalMigrationIntentEvent event) {
        jdbc.update("""
            insert into ap_process_migration_intent_event (
              tenant_id,event_id,intent_id,revision,from_status,to_status,
              payload_json,happened_at
            ) values (
              :tenantId,:eventId,:intentId,:revision,:fromStatus,:toStatus,
              :payload,:happenedAt
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", event.tenantId())
                .addValue("eventId", values.bindUuid(event.eventId()))
                .addValue("intentId", values.bindUuid(event.intentId()))
                .addValue("revision", event.revision())
                .addValue("fromStatus", event.fromStatus().name())
                .addValue("toStatus", event.toStatus().name())
                .addValue("payload", json.write(event))
                .addValue("happenedAt", values.bindInstant(event.happenedAt())));
    }

    private Optional<ApprovalMigrationCommandFence> findActiveFenceForUpdate(
        String tenantId,
        UUID instanceId
    ) {
        return jdbc.query("""
            select tenant_id,fence_id,approval_instance_id,attempt_id,status,revision,
                   lease_owner,lease_until,idempotency_key,request_hash,acquired_at,
                   updated_at,released_at,payload_json
            from ap_approval_instance_command_fence
            where tenant_id=:tenantId and approval_instance_id=:instanceId
              and status='ACTIVE'
            for update
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("instanceId", values.bindUuid(instanceId)),
            (row, number) -> readFence(row)).stream().findFirst();
    }

    private ApprovalMigrationCommandFence lockFence(
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
                "active migration command fence was not found"
            ));
    }

    private Optional<ApprovalMigrationCommandFence> queryFence(
        String predicate,
        MapSqlParameterSource parameters
    ) {
        return jdbc.query(
            "select tenant_id,fence_id,approval_instance_id,attempt_id,status,revision,"
                + "lease_owner,lease_until,idempotency_key,request_hash,acquired_at,"
                + "updated_at,released_at,payload_json "
                + "from ap_approval_instance_command_fence where " + predicate,
            parameters,
            (row, number) -> readFence(row)
        ).stream().findFirst();
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

    private void insertFence(
        ApprovalMigrationCommandFence fence,
        String actor
    ) {
        int inserted = jdbc.update("""
            insert into ap_approval_instance_command_fence (
              tenant_id,fence_id,approval_instance_id,attempt_id,operation,status,revision,
              lease_owner,lease_until,idempotency_key,request_hash,acquired_at,updated_at,
              released_at,request_id,trace_id,payload_json
            ) values (
              :tenantId,:fenceId,:instanceId,:attemptId,:operation,:status,:revision,
              :leaseOwner,:leaseUntil,:idempotencyKey,:requestHash,:acquiredAt,:updatedAt,
              :releasedAt,:requestId,:traceId,:payload
            )
            """, fenceParameters(fence));
        if (inserted != 1) {
            throw conflict("migration command fence insert did not affect exactly one row");
        }
        appendFenceEvent(null, fence, actor, fence.requestId(), fence.traceId());
    }

    private void updateFence(
        ApprovalMigrationCommandFence previous,
        ApprovalMigrationCommandFence next,
        String actor,
        String requestId,
        String traceId
    ) {
        int updated = jdbc.update("""
            update ap_approval_instance_command_fence set
              status=:status,revision=:revision,lease_owner=:leaseOwner,
              lease_until=:leaseUntil,updated_at=:updatedAt,released_at=:releasedAt,
              payload_json=:payload
            where tenant_id=:tenantId and fence_id=:fenceId
              and revision=:expectedRevision and status=:expectedStatus
              and lease_owner=:expectedOwner
            """, fenceParameters(next)
                .addValue("expectedRevision", previous.revision())
                .addValue("expectedStatus", previous.status().name())
                .addValue("expectedOwner", previous.leaseOwner()));
        if (updated != 1) {
            throw conflict("migration command fence revision or owner is stale");
        }
        appendFenceEvent(previous, next, actor, requestId, traceId);
    }

    private MapSqlParameterSource fenceParameters(
        ApprovalMigrationCommandFence fence
    ) {
        return new MapSqlParameterSource()
            .addValue("tenantId", fence.tenantId())
            .addValue("fenceId", values.bindUuid(fence.fenceId()))
            .addValue("instanceId", values.bindUuid(fence.approvalInstanceId()))
            .addValue("attemptId", values.bindUuid(fence.attemptId()))
            .addValue("operation", fence.operation().name())
            .addValue("status", fence.status().name())
            .addValue("revision", fence.revision())
            .addValue("leaseOwner", fence.leaseOwner())
            .addValue("leaseUntil", values.bindInstant(fence.leaseUntil()))
            .addValue("idempotencyKey", fence.idempotencyKey())
            .addValue("requestHash", fence.requestHash())
            .addValue("acquiredAt", values.bindInstant(fence.acquiredAt()))
            .addValue("updatedAt", values.bindInstant(fence.updatedAt()))
            .addValue("releasedAt", values.bindNullableInstant(fence.releasedAt()))
            .addValue("requestId", fence.requestId())
            .addValue("traceId", fence.traceId())
            .addValue("payload", json.write(fence));
    }

    private void appendFenceEvent(
        ApprovalMigrationCommandFence previous,
        ApprovalMigrationCommandFence current,
        String actor,
        String requestId,
        String traceId
    ) {
        ApprovalMigrationCommandFenceEvent base = ApprovalMigrationCommandFenceEvent.from(
            nextIdentifier("fenceEventId"),
            previous,
            current,
            actor
        );
        ApprovalMigrationCommandFenceEvent event = new ApprovalMigrationCommandFenceEvent(
            base.eventId(),
            base.tenantId(),
            base.fenceId(),
            base.approvalInstanceId(),
            base.attemptId(),
            base.revision(),
            base.fromStatus(),
            base.toStatus(),
            base.leaseActor(),
            base.leaseOwner(),
            base.leaseUntil(),
            base.happenedAt(),
            requestId,
            traceId
        );
        jdbc.update("""
            insert into ap_approval_instance_command_fence_event (
              tenant_id,event_id,fence_id,approval_instance_id,attempt_id,revision,
              from_status,to_status,lease_actor,lease_owner,lease_until,happened_at,
              request_id,trace_id,payload_json
            ) values (
              :tenantId,:eventId,:fenceId,:instanceId,:attemptId,:revision,
              :fromStatus,:toStatus,:leaseActor,:leaseOwner,:leaseUntil,:happenedAt,
              :requestId,:traceId,:payload
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", event.tenantId())
                .addValue("eventId", values.bindUuid(event.eventId()))
                .addValue("fenceId", values.bindUuid(event.fenceId()))
                .addValue("instanceId", values.bindUuid(event.approvalInstanceId()))
                .addValue("attemptId", values.bindUuid(event.attemptId()))
                .addValue("revision", event.revision())
                .addValue(
                    "fromStatus",
                    event.fromStatus() == null ? null : event.fromStatus().name()
                )
                .addValue("toStatus", event.toStatus().name())
                .addValue("leaseActor", event.leaseActor())
                .addValue("leaseOwner", event.leaseOwner())
                .addValue("leaseUntil", values.bindInstant(event.leaseUntil()))
                .addValue("happenedAt", values.bindInstant(event.happenedAt()))
                .addValue("requestId", event.requestId())
                .addValue("traceId", event.traceId())
                .addValue("payload", json.write(event)));
    }

    private void insertBatch(ApprovalMigrationClaimBatch batch) {
        int inserted = jdbc.update("""
            insert into ap_process_migration_claim_batch (
              tenant_id,claim_batch_id,intent_id,worker_id,requested_limit,claimed_count,
              claimed_attempt_ids,fence_ids,request_hash,claimed_at,request_id,trace_id,
              payload_json
            ) values (
              :tenantId,:batchId,:intentId,:workerId,:requestedLimit,:claimedCount,
              :attemptIds,:fenceIds,:requestHash,:claimedAt,:requestId,:traceId,:payload
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", batch.tenantId())
                .addValue("batchId", values.bindUuid(batch.claimBatchId()))
                .addValue("intentId", values.bindUuid(batch.intentId()))
                .addValue("workerId", batch.workerId())
                .addValue("requestedLimit", batch.requestedLimit())
                .addValue("claimedCount", batch.claimedCount())
                .addValue("attemptIds", json.write(batch.claimedAttemptIds()))
                .addValue("fenceIds", json.write(batch.fenceIds()))
                .addValue("requestHash", batch.requestHash())
                .addValue("claimedAt", values.bindInstant(batch.claimedAt()))
                .addValue("requestId", batch.requestId())
                .addValue("traceId", batch.traceId())
                .addValue("payload", json.write(batch)));
        if (inserted != 1) {
            throw conflict("migration claim batch insert did not affect exactly one row");
        }
    }

    private Optional<ClaimResult> findReplay(ClaimRequest request) {
        Optional<ApprovalMigrationClaimBatch> batch = jdbc.query("""
            select payload_json
            from ap_process_migration_claim_batch
            where tenant_id=:tenantId and request_id=:requestId
            """, new MapSqlParameterSource()
                .addValue("tenantId", request.tenantId())
                .addValue("requestId", request.requestId()),
            (row, number) -> json.read(
                row.getString("payload_json"),
                ApprovalMigrationClaimBatch.class
            )).stream().findFirst();
        if (batch.isEmpty()) {
            return Optional.empty();
        }
        ApprovalMigrationClaimBatch existing = batch.orElseThrow();
        if (!existing.intentId().equals(request.intentId())
            || !existing.requestHash().equals(request.requestHash())) {
            throw conflict(
                "migration claim request identity was reused with different evidence"
            );
        }
        List<ApprovalMigrationAttempt> attempts = existing.claimedAttemptIds().stream()
            .map(attemptId -> findAttempt(existing.tenantId(), attemptId)
                .orElseThrow(() -> conflict("claimed attempt replay disappeared")))
            .toList();
        List<ApprovalMigrationCommandFence> fences = existing.fenceIds().stream()
            .map(fenceId -> queryFence(
                "tenant_id=:tenantId and fence_id=:fenceId",
                new MapSqlParameterSource()
                    .addValue("tenantId", existing.tenantId())
                    .addValue("fenceId", values.bindUuid(fenceId))
            ).orElseThrow(() -> conflict("command fence replay disappeared")))
            .toList();
        return Optional.of(new ClaimResult(existing, attempts, fences, true));
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

    private void appendClaimAudit(ApprovalMigrationClaimBatch batch) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("claimedCount", Integer.toString(batch.claimedCount()));
        attributes.put("requestedLimit", Integer.toString(batch.requestedLimit()));
        attributes.put("workerId", batch.workerId());
        attributes.put("requestHash", batch.requestHash());
        auditEvents.append(new AuditEvent(
            nextIdentifier("auditEventId"),
            batch.tenantId(),
            batch.workerId(),
            "PROCESS_MIGRATION_ATTEMPTS_CLAIMED",
            "APPROVAL_MIGRATION_INTENT",
            batch.intentId().toString(),
            batch.requestId(),
            batch.traceId(),
            batch.claimedAt(),
            Map.copyOf(attributes)
        ));
    }

    private void appendRenewalAudit(
        ApprovalMigrationCommandFence fence,
        RenewalRequest request
    ) {
        auditEvents.append(new AuditEvent(
            nextIdentifier("auditEventId"),
            fence.tenantId(),
            request.workerId(),
            "PROCESS_MIGRATION_LEASE_RENEWED",
            "APPROVAL_MIGRATION_ATTEMPT",
            fence.attemptId().toString(),
            request.requestId(),
            request.traceId(),
            request.happenedAt(),
            Map.of(
                "fenceRevision", Long.toString(fence.revision()),
                "leaseOwner", fence.leaseOwner(),
                "leaseUntil", fence.leaseUntil().toString()
            )
        ));
    }

    private ClaimRequest canonicalClaimRequest(ClaimRequest request) {
        return new ClaimRequest(
            request.tenantId(),
            request.intentId(),
            request.workerId(),
            request.limit(),
            AuditHashCanonicalizer.canonicalInstant(request.claimedAt()),
            AuditHashCanonicalizer.canonicalInstant(request.leaseUntil()),
            request.requestId(),
            request.traceId(),
            request.requestHash()
        );
    }

    private RenewalRequest canonicalRenewalRequest(RenewalRequest request) {
        return new RenewalRequest(
            request.tenantId(),
            request.attemptId(),
            request.workerId(),
            AuditHashCanonicalizer.canonicalInstant(request.happenedAt()),
            AuditHashCanonicalizer.canonicalInstant(request.leaseUntil()),
            request.requestId(),
            request.traceId()
        );
    }

    private UUID nextIdentifier(String name) {
        return Objects.requireNonNull(
            identifiers.get(),
            "generated " + name + " must not be null"
        );
    }

    private static String requireText(String value, String name) {
        String exact = Objects.requireNonNull(value, name + " must not be null").trim();
        if (exact.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return exact;
    }

    private static MigrationAttemptClaimConflictException conflict(String message) {
        return new MigrationAttemptClaimConflictException(message);
    }

    private record ClaimedPair(
        ApprovalMigrationAttempt attempt,
        ApprovalMigrationCommandFence fence
    ) {
    }
}
