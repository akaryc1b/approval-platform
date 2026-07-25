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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** PostgreSQL bounded claim, lease renewal and expiry-takeover implementation. */
public final class JdbcApprovalMigrationAttemptClaimStore
    implements ApprovalMigrationAttemptClaimStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcApprovalMigrationJson json;
    private final JdbcApprovalMigrationProtocolStore protocol;
    private final JdbcApprovalInstanceCommandFence commandFence;
    private final TransactionTemplate transactions;
    private final AuditEventSink auditEvents;
    private final Supplier<UUID> identifiers;

    public JdbcApprovalMigrationAttemptClaimStore(
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
    public ClaimResult claim(ClaimRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Optional<ClaimResult> replay = findReplay(request);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return transactions.execute(status -> claimOnce(request));
        } catch (DataAccessException exception) {
            Optional<ClaimResult> concurrentReplay = findReplay(request);
            if (concurrentReplay.isPresent()) {
                return concurrentReplay.get();
            }
            throw new MigrationAttemptClaimConflictException(
                "migration attempt claim persistence conflict",
                exception
            );
        }
    }

    @Override
    public RenewalResult renew(RenewalRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        try {
            return transactions.execute(status -> renewOnce(request));
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
                .addValue("tenantId", tenantId)
                .addValue("attemptId", attemptId)
        );
    }

    private ClaimResult claimOnce(ClaimRequest request) {
        Optional<ClaimResult> replay = findReplay(request);
        if (replay.isPresent()) {
            return replay.get();
        }
        ApprovalMigrationIntent intent = lockIntent(request.tenantId(), request.intentId());
        if ((intent.status() != IntentStatus.PENDING && intent.status() != IntentStatus.RUNNING)
            || !request.claimedAt().isBefore(intent.expiresAt())) {
            throw conflict("migration intent is not claimable");
        }

        List<ApprovalMigrationAttempt> candidates = findCandidates(request);
        List<ApprovalMigrationAttempt> claimed = new ArrayList<>();
        List<ApprovalMigrationCommandFence> fences = new ArrayList<>();
        for (ApprovalMigrationAttempt candidate : candidates) {
            commandFence.acquireMigrationLock(candidate.tenantId(), candidate.approvalInstanceId());
            Optional<ApprovalMigrationCommandFence> currentFence = findActiveFenceForUpdate(
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
                && !candidate.leaseUntil().isAfter(request.claimedAt())) {
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
            ApprovalMigrationIntent running = intent.transitioned(
                IntentStatus.RUNNING,
                request.claimedAt()
            );
            protocol.transitionIntent(
                running,
                intent.revision(),
                new ApprovalMigrationIntentEvent(
                    nextIdentifier("intentEventId"),
                    intent.tenantId(),
                    intent.intentId(),
                    running.revision(),
                    IntentStatus.PENDING,
                    IntentStatus.RUNNING,
                    "Bounded migration attempt claim started the intent",
                    request.workerId(),
                    request.claimedAt(),
                    request.requestId(),
                    request.traceId(),
                    intent.auditChainReference()
                )
            );
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
        appendClaimAudit(batch, request);
        return new ClaimResult(batch, claimed, fences, false);
    }

    private RenewalResult renewOnce(RenewalRequest request) {
        ApprovalMigrationAttempt current = lockAttempt(request.tenantId(), request.attemptId());
        if (current.status() != AttemptStatus.CLAIMED) {
            throw conflict("only a claimed migration attempt may renew or take over a lease");
        }
        commandFence.acquireMigrationLock(current.tenantId(), current.approvalInstanceId());
        ApprovalMigrationCommandFence fence = lockFence(current.tenantId(), current.attemptId());
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
        ApprovalMigrationAttempt stored = protocol.transitionAttempt(
            nextAttempt,
            current.revision(),
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

    private ClaimedPair initialClaim(ApprovalMigrationAttempt candidate, ClaimRequest request) {
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
        ApprovalMigrationAttempt stored = protocol.transitionAttempt(
            nextAttempt,
            candidate.revision(),
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
        ApprovalMigrationAttempt stored = protocol.transitionAttempt(
            nextAttempt,
            candidate.revision(),
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
            select payload_json::text
            from ap_process_migration_attempt
            where tenant_id=:tenantId and intent_id=:intentId
              and (status='PENDING'
                or (status='CLAIMED' and lease_until<=:claimedAt))
            order by created_at,attempt_id
            limit :limit
            for update skip locked
            """, new MapSqlParameterSource()
                .addValue("tenantId", request.tenantId())
                .addValue("intentId", request.intentId())
                .addValue("claimedAt", JdbcApprovalMigrationJson.offset(request.claimedAt()))
                .addValue("limit", request.limit()),
            (row, number) -> json.read(row.getString(1), ApprovalMigrationAttempt.class)));
    }

    private ApprovalMigrationIntent lockIntent(String tenantId, UUID intentId) {
        return jdbc.query("""
            select payload_json::text from ap_process_migration_intent
            where tenant_id=:tenantId and intent_id=:intentId
            for update
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("intentId", intentId),
            (row, number) -> json.read(row.getString(1), ApprovalMigrationIntent.class))
            .stream().findFirst()
            .orElseThrow(() -> conflict("migration intent was not found for bounded claim"));
    }

    private ApprovalMigrationAttempt lockAttempt(String tenantId, UUID attemptId) {
        return jdbc.query("""
            select payload_json::text from ap_process_migration_attempt
            where tenant_id=:tenantId and attempt_id=:attemptId
            for update
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("attemptId", attemptId),
            (row, number) -> json.read(row.getString(1), ApprovalMigrationAttempt.class))
            .stream().findFirst()
            .orElseThrow(() -> conflict("migration attempt was not found for lease renewal"));
    }

    private Optional<ApprovalMigrationCommandFence> findActiveFenceForUpdate(
        String tenantId,
        UUID instanceId
    ) {
        return jdbc.query("""
            select payload_json::text from ap_approval_instance_command_fence
            where tenant_id=:tenantId and approval_instance_id=:instanceId and status='ACTIVE'
            for update
            """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("instanceId", instanceId),
            (row, number) -> json.read(row.getString(1), ApprovalMigrationCommandFence.class))
            .stream().findFirst();
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
            .stream().findFirst()
            .orElseThrow(() -> conflict("active migration command fence was not found"));
    }

    private void insertFence(ApprovalMigrationCommandFence fence, String actor) {
        int inserted = jdbc.update("""
            insert into ap_approval_instance_command_fence (
              tenant_id,fence_id,approval_instance_id,attempt_id,operation,status,revision,
              lease_owner,lease_until,idempotency_key,request_hash,acquired_at,updated_at,
              released_at,request_id,trace_id,payload_json
            ) values (
              :tenantId,:fenceId,:instanceId,:attemptId,:operation,:status,:revision,
              :leaseOwner,:leaseUntil,:idempotencyKey,:requestHash,:acquiredAt,:updatedAt,
              :releasedAt,:requestId,:traceId,cast(:payload as jsonb)
            ) on conflict (tenant_id,idempotency_key) do nothing
            """, fenceParameters(fence));
        if (inserted != 1) {
            ApprovalMigrationCommandFence replay = queryFence(
                "tenant_id=:tenantId and idempotency_key=:idempotencyKey",
                new MapSqlParameterSource()
                    .addValue("tenantId", fence.tenantId())
                    .addValue("idempotencyKey", fence.idempotencyKey())
            ).orElseThrow(() -> conflict("command fence replay disappeared"));
            if (!replay.equals(fence)) {
                throw conflict("command fence idempotency key was reused with different evidence");
            }
            return;
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
              payload_json=cast(:payload as jsonb)
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
            base.eventId(), base.tenantId(), base.fenceId(), base.approvalInstanceId(),
            base.attemptId(), base.revision(), base.fromStatus(), base.toStatus(),
            base.leaseActor(), base.leaseOwner(), base.leaseUntil(), base.happenedAt(),
            requestId, traceId
        );
        jdbc.update("""
            insert into ap_approval_instance_command_fence_event (
              tenant_id,event_id,fence_id,approval_instance_id,attempt_id,revision,
              from_status,to_status,lease_actor,lease_owner,lease_until,happened_at,
              request_id,trace_id,payload_json
            ) values (
              :tenantId,:eventId,:fenceId,:instanceId,:attemptId,:revision,
              :fromStatus,:toStatus,:leaseActor,:leaseOwner,:leaseUntil,:happenedAt,
              :requestId,:traceId,cast(:payload as jsonb)
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", event.tenantId())
                .addValue("eventId", event.eventId())
                .addValue("fenceId", event.fenceId())
                .addValue("instanceId", event.approvalInstanceId())
                .addValue("attemptId", event.attemptId())
                .addValue("revision", event.revision())
                .addValue("fromStatus", event.fromStatus() == null
                    ? null : event.fromStatus().name())
                .addValue("toStatus", event.toStatus().name())
                .addValue("leaseActor", event.leaseActor())
                .addValue("leaseOwner", event.leaseOwner())
                .addValue("leaseUntil", JdbcApprovalMigrationJson.offset(event.leaseUntil()))
                .addValue("happenedAt", JdbcApprovalMigrationJson.offset(event.happenedAt()))
                .addValue("requestId", event.requestId())
                .addValue("traceId", event.traceId())
                .addValue("payload", json.write(event)));
    }

    private void insertBatch(ApprovalMigrationClaimBatch batch) {
        jdbc.update("""
            insert into ap_process_migration_claim_batch (
              tenant_id,claim_batch_id,intent_id,worker_id,requested_limit,claimed_count,
              claimed_attempt_ids,fence_ids,request_hash,claimed_at,request_id,trace_id,
              payload_json
            ) values (
              :tenantId,:batchId,:intentId,:workerId,:requestedLimit,:claimedCount,
              cast(:attemptIds as jsonb),cast(:fenceIds as jsonb),:requestHash,:claimedAt,
              :requestId,:traceId,cast(:payload as jsonb)
            )
            """, new MapSqlParameterSource()
                .addValue("tenantId", batch.tenantId())
                .addValue("batchId", batch.claimBatchId())
                .addValue("intentId", batch.intentId())
                .addValue("workerId", batch.workerId())
                .addValue("requestedLimit", batch.requestedLimit())
                .addValue("claimedCount", batch.claimedCount())
                .addValue("attemptIds", json.write(batch.claimedAttemptIds()))
                .addValue("fenceIds", json.write(batch.fenceIds()))
                .addValue("requestHash", batch.requestHash())
                .addValue("claimedAt", JdbcApprovalMigrationJson.offset(batch.claimedAt()))
                .addValue("requestId", batch.requestId())
                .addValue("traceId", batch.traceId())
                .addValue("payload", json.write(batch)));
    }

    private Optional<ClaimResult> findReplay(ClaimRequest request) {
        Optional<ApprovalMigrationClaimBatch> batch = jdbc.query("""
            select payload_json::text from ap_process_migration_claim_batch
            where tenant_id=:tenantId and request_id=:requestId
            """, new MapSqlParameterSource()
                .addValue("tenantId", request.tenantId())
                .addValue("requestId", request.requestId()),
            (row, number) -> json.read(row.getString(1), ApprovalMigrationClaimBatch.class))
            .stream().findFirst();
        if (batch.isEmpty()) {
            return Optional.empty();
        }
        ApprovalMigrationClaimBatch existing = batch.get();
        if (!existing.intentId().equals(request.intentId())
            || !existing.requestHash().equals(request.requestHash())) {
            throw conflict("migration claim request identity was reused with different evidence");
        }
        List<ApprovalMigrationAttempt> attempts = existing.claimedAttemptIds().stream()
            .map(attemptId -> protocol.findAttempt(existing.tenantId(), attemptId)
                .orElseThrow(() -> conflict("claimed attempt replay disappeared")))
            .toList();
        List<ApprovalMigrationCommandFence> fences = existing.fenceIds().stream()
            .map(fenceId -> queryFence(
                "tenant_id=:tenantId and fence_id=:fenceId",
                new MapSqlParameterSource()
                    .addValue("tenantId", existing.tenantId())
                    .addValue("fenceId", fenceId)
            ).orElseThrow(() -> conflict("command fence replay disappeared")))
            .toList();
        return Optional.of(new ClaimResult(existing, attempts, fences, true));
    }

    private Optional<ApprovalMigrationCommandFence> queryFence(
        String predicate,
        MapSqlParameterSource parameters
    ) {
        return jdbc.query(
            "select payload_json::text from ap_approval_instance_command_fence where "
                + predicate,
            parameters,
            (row, number) -> json.read(row.getString(1), ApprovalMigrationCommandFence.class)
        ).stream().findFirst();
    }

    private MapSqlParameterSource fenceParameters(ApprovalMigrationCommandFence fence) {
        return new MapSqlParameterSource()
            .addValue("tenantId", fence.tenantId())
            .addValue("fenceId", fence.fenceId())
            .addValue("instanceId", fence.approvalInstanceId())
            .addValue("attemptId", fence.attemptId())
            .addValue("operation", fence.operation().name())
            .addValue("status", fence.status().name())
            .addValue("revision", fence.revision())
            .addValue("leaseOwner", fence.leaseOwner())
            .addValue("leaseUntil", JdbcApprovalMigrationJson.offset(fence.leaseUntil()))
            .addValue("idempotencyKey", fence.idempotencyKey())
            .addValue("requestHash", fence.requestHash())
            .addValue("acquiredAt", JdbcApprovalMigrationJson.offset(fence.acquiredAt()))
            .addValue("updatedAt", JdbcApprovalMigrationJson.offset(fence.updatedAt()))
            .addValue("releasedAt", fence.releasedAt() == null
                ? null : JdbcApprovalMigrationJson.offset(fence.releasedAt()))
            .addValue("requestId", fence.requestId())
            .addValue("traceId", fence.traceId())
            .addValue("payload", json.write(fence));
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

    private void appendClaimAudit(ApprovalMigrationClaimBatch batch, ClaimRequest request) {
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

    private UUID nextIdentifier(String name) {
        return Objects.requireNonNull(
            identifiers.get(),
            "generated " + name + " must not be null"
        );
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
