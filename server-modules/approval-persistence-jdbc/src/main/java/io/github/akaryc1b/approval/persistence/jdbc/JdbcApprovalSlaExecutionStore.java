package io.github.akaryc1b.approval.persistence.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL SLA execution queue, append-only attempts and immutable replay lineage. */
public final class JdbcApprovalSlaExecutionStore implements ApprovalSlaExecutionStore {

    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    public JdbcApprovalSlaExecutionStore(
        DataSource dataSource,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager
    ) {
        this.jdbc = new NamedParameterJdbcTemplate(
            Objects.requireNonNull(dataSource, "dataSource must not be null")
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.transactions = new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
    }

    @Override
    public int enqueue(List<ExecutionIntent> intents) {
        if (intents == null || intents.isEmpty()) {
            return 0;
        }
        int inserted = 0;
        for (ExecutionIntent intent : intents) {
            inserted += insertIntent(Objects.requireNonNull(intent, "intent must not be null"));
        }
        return inserted;
    }

    @Override
    public List<String> findRunnableTenants(Instant now, int limit) {
        requireLimit(limit);
        return List.copyOf(jdbc.queryForList(
            """
            select tenant_id
            from ap_sla_execution_intent
            where attempt_count < max_attempts
              and (
                (status in ('READY', 'RETRY_WAIT') and next_attempt_at <= :now)
                or (status = 'CLAIMED' and lease_until < :now)
              )
            group by tenant_id
            order by min(
                case when status = 'CLAIMED' then lease_until else next_attempt_at end
            ), tenant_id
            limit :limit
            """,
            new MapSqlParameterSource()
                .addValue("now", offset(now))
                .addValue("limit", limit),
            String.class
        ));
    }

    @Override
    public List<ExecutionIntent> claimDue(
        String tenantId,
        Instant now,
        int limit,
        String workerId,
        Instant leaseUntil
    ) {
        requireLimit(limit);
        String normalizedTenant = requireText(tenantId, "tenantId", 128);
        String normalizedWorker = requireText(workerId, "workerId", 200);
        Instant claimTime = Objects.requireNonNull(now, "now must not be null");
        Instant leaseExpiry = Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
        if (!leaseExpiry.isAfter(claimTime)) {
            throw new IllegalArgumentException("leaseUntil must be after now");
        }
        return List.copyOf(jdbc.query(
            """
            with candidates as (
                select tenant_id, intent_id
                from ap_sla_execution_intent
                where tenant_id = :tenantId
                  and attempt_count < max_attempts
                  and (
                    (status in ('READY', 'RETRY_WAIT') and next_attempt_at <= :now)
                    or (status = 'CLAIMED' and lease_until < :now)
                  )
                order by
                    case when status = 'CLAIMED' then lease_until else next_attempt_at end,
                    scheduled_at,
                    intent_id
                for update skip locked
                limit :limit
            )
            update ap_sla_execution_intent intent
            set status = 'CLAIMED',
                lease_owner = :workerId,
                lease_until = :leaseUntil,
                updated_at = :now,
                version = intent.version + 1
            from candidates
            where intent.tenant_id = candidates.tenant_id
              and intent.intent_id = candidates.intent_id
            returning intent.*
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", normalizedTenant)
                .addValue("now", offset(claimTime))
                .addValue("limit", limit)
                .addValue("workerId", normalizedWorker)
                .addValue("leaseUntil", offset(leaseExpiry)),
            intentMapper()
        ));
    }

    @Override
    public ExecutionIntent markSucceeded(
        String tenantId,
        UUID intentId,
        long expectedVersion,
        String workerId,
        UUID attemptId,
        Instant claimedAt,
        Instant startedAt,
        Instant finishedAt,
        String requestId,
        String traceId
    ) {
        return completeAttempt(
            tenantId,
            intentId,
            expectedVersion,
            workerId,
            attemptId,
            claimedAt,
            startedAt,
            finishedAt,
            true,
            false,
            null,
            null,
            null,
            requestId,
            traceId
        );
    }

    @Override
    public ExecutionIntent markFailed(
        String tenantId,
        UUID intentId,
        long expectedVersion,
        String workerId,
        UUID attemptId,
        Instant claimedAt,
        Instant startedAt,
        Instant finishedAt,
        boolean retryable,
        Instant nextAttemptAt,
        String errorCode,
        String errorSummary,
        String requestId,
        String traceId
    ) {
        return completeAttempt(
            tenantId,
            intentId,
            expectedVersion,
            workerId,
            attemptId,
            claimedAt,
            startedAt,
            finishedAt,
            false,
            retryable,
            nextAttemptAt,
            requireText(errorCode, "errorCode", 128),
            requireText(errorSummary, "errorSummary", 1000),
            requestId,
            traceId
        );
    }

    @Override
    public int cancelActiveForSla(
        String tenantId,
        UUID slaInstanceId,
        Instant cancelledAt,
        String reason
    ) {
        Instant cancelled = Objects.requireNonNull(cancelledAt, "cancelledAt must not be null");
        return jdbc.update(
            """
            update ap_sla_execution_intent
            set status = 'CANCELLED',
                lease_owner = null,
                lease_until = null,
                cancelled_at = :cancelledAt,
                last_error_code = 'SLA_CANCELLED',
                last_error_summary = :reason,
                updated_at = :cancelledAt,
                version = version + 1
            where tenant_id = :tenantId
              and sla_instance_id = :slaInstanceId
              and status in ('READY', 'CLAIMED', 'RETRY_WAIT')
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId", 128))
                .addValue(
                    "slaInstanceId",
                    Objects.requireNonNull(slaInstanceId, "slaInstanceId must not be null")
                )
                .addValue("cancelledAt", offset(cancelled))
                .addValue("reason", requireText(reason, "reason", 1000))
        );
    }

    @Override
    public int updateFutureResponsibleUser(
        String tenantId,
        UUID slaInstanceId,
        String responsibleUserId,
        Instant updatedAt
    ) {
        return jdbc.update(
            """
            update ap_sla_execution_intent
            set responsible_user_id = :responsibleUserId,
                updated_at = :updatedAt,
                version = version + 1
            where tenant_id = :tenantId
              and sla_instance_id = :slaInstanceId
              and status in ('READY', 'RETRY_WAIT')
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId", 128))
                .addValue(
                    "slaInstanceId",
                    Objects.requireNonNull(slaInstanceId, "slaInstanceId must not be null")
                )
                .addValue(
                    "responsibleUserId",
                    requireText(responsibleUserId, "responsibleUserId", 200)
                )
                .addValue("updatedAt", offset(updatedAt))
        );
    }

    @Override
    public Optional<ExecutionIntent> findIntent(String tenantId, UUID intentId) {
        return jdbc.query(
            """
            select * from ap_sla_execution_intent
            where tenant_id = :tenantId and intent_id = :intentId
            """,
            identity(tenantId, intentId),
            intentMapper()
        ).stream().findFirst();
    }

    @Override
    public ExecutionIntentPage findIntents(ExecutionIntentCriteria criteria) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("tenantId", criteria.tenantId())
            .addValue("limit", criteria.limit())
            .addValue("offset", criteria.offset());
        String where = criteriaWhere(criteria, parameters);
        Long total = jdbc.queryForObject(
            "select count(*) from ap_sla_execution_intent " + where,
            parameters,
            Long.class
        );
        long matched = total == null ? 0 : total;
        List<ExecutionIntent> items = matched == 0 ? List.of() : jdbc.query(
            """
            select * from ap_sla_execution_intent
            """ + where + """
            order by scheduled_at desc, intent_id desc
            limit :limit offset :offset
            """,
            parameters,
            intentMapper()
        );
        return new ExecutionIntentPage(items, matched, criteria.limit(), criteria.offset());
    }

    @Override
    public List<ExecutionAttempt> findAttempts(String tenantId, UUID intentId) {
        return List.copyOf(jdbc.query(
            """
            select * from ap_sla_execution_attempt
            where tenant_id = :tenantId and intent_id = :intentId
            order by attempt_number, started_at, attempt_id
            """,
            identity(tenantId, intentId),
            attemptMapper()
        ));
    }

    @Override
    public QueueSummary summarize(String tenantId, Instant now) {
        return jdbc.queryForObject(
            """
            select
                count(*) filter (where status = 'READY') as ready_count,
                count(*) filter (where status = 'CLAIMED') as claimed_count,
                count(*) filter (where status = 'RETRY_WAIT') as retry_wait_count,
                count(*) filter (where status = 'SUCCEEDED') as succeeded_count,
                count(*) filter (where status = 'DEAD') as dead_count,
                count(*) filter (where status = 'CANCELLED') as cancelled_count,
                count(*) filter (
                    where status = 'CLAIMED' and lease_until < :now
                ) as expired_lease_count
            from ap_sla_execution_intent
            where tenant_id = :tenantId
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId", 128))
                .addValue("now", offset(now)),
            (resultSet, rowNumber) -> new QueueSummary(
                resultSet.getLong("ready_count"),
                resultSet.getLong("claimed_count"),
                resultSet.getLong("retry_wait_count"),
                resultSet.getLong("succeeded_count"),
                resultSet.getLong("dead_count"),
                resultSet.getLong("cancelled_count"),
                resultSet.getLong("expired_lease_count")
            )
        );
    }

    @Override
    public ReplayResult replayDead(ReplayRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        ReplayResult result = transactions.execute(status -> {
            acquireReplayLock(request.tenantId(), request.replayIdempotencyKey());
            Optional<ReplayEvidence> existing = findReplayByIdempotency(
                request.tenantId(),
                request.replayIdempotencyKey()
            );
            if (existing.isPresent()) {
                ExecutionIntent existingIntent = requireIntent(
                    request.tenantId(),
                    existing.get().newIntentId()
                );
                return new ReplayResult(existingIntent, existing.get(), true);
            }

            ExecutionIntent original = requireIntentForUpdate(
                request.tenantId(),
                request.originalIntentId()
            );
            if (original.status() != IntentStatus.DEAD) {
                throw new ExecutionConflictException("only a dead execution intent can be replayed");
            }
            lockSla(original.tenantId(), original.slaInstanceId());
            int nextSequence = nextActionSequence(
                original.tenantId(),
                original.slaInstanceId(),
                original.actionType()
            );
            ExecutionIntent replayed = new ExecutionIntent(
                request.newIntentId(),
                original.tenantId(),
                original.slaInstanceId(),
                original.approvalInstanceId(),
                original.taskId(),
                original.collaborationParticipantId(),
                original.policyId(),
                original.policyVersion(),
                original.calendarId(),
                original.calendarVersion(),
                original.intentId(),
                original.actionType(),
                nextSequence,
                original.scheduledAt(),
                request.requestedAt(),
                IntentStatus.READY,
                null,
                null,
                0,
                original.maxAttempts(),
                request.requestedAt(),
                request.executionIdempotencyKey(),
                original.payload(),
                original.responsibleUserId(),
                request.requestId(),
                request.traceId(),
                1,
                request.requestedAt(),
                request.requestedAt(),
                null,
                null,
                null,
                null,
                null
            );
            if (insertIntent(replayed) != 1) {
                throw new ExecutionConflictException(
                    "execution replay idempotency key is already bound to another intent"
                );
            }
            ReplayEvidence evidence = new ReplayEvidence(
                request.replayId(),
                request.tenantId(),
                original.intentId(),
                replayed.intentId(),
                original.lastErrorCode(),
                original.lastErrorSummary(),
                request.replayReason(),
                request.replayIdempotencyKey(),
                request.requestedBy(),
                request.requestedAt(),
                request.auditChainReference(),
                request.requestId(),
                request.traceId()
            );
            int inserted = jdbc.update(
                """
                insert into ap_sla_execution_replay (
                    replay_id, tenant_id, original_intent_id, new_intent_id,
                    original_error_code, original_error_summary, replay_reason,
                    replay_idempotency_key, requested_by, requested_at,
                    audit_chain_reference, request_id, trace_id
                ) values (
                    :replayId, :tenantId, :originalIntentId, :newIntentId,
                    :originalErrorCode, :originalErrorSummary, :replayReason,
                    :replayIdempotencyKey, :requestedBy, :requestedAt,
                    :auditChainReference, :requestId, :traceId
                )
                """,
                replayParameters(evidence)
            );
            if (inserted != 1) {
                throw new ExecutionConflictException("execution replay evidence was not written");
            }
            return new ReplayResult(replayed, evidence, false);
        });
        return Objects.requireNonNull(result, "replay result must not be null");
    }

    private ExecutionIntent completeAttempt(
        String tenantId,
        UUID intentId,
        long expectedVersion,
        String workerId,
        UUID attemptId,
        Instant claimedAt,
        Instant startedAt,
        Instant finishedAt,
        boolean successful,
        boolean retryable,
        Instant requestedNextAttemptAt,
        String errorCode,
        String errorSummary,
        String requestId,
        String traceId
    ) {
        ExecutionIntent updated = transactions.execute(status -> {
            ExecutionIntent current = requireIntent(tenantId, intentId);
            String normalizedWorker = requireText(workerId, "workerId", 200);
            if (current.version() != expectedVersion
                || current.status() != IntentStatus.CLAIMED
                || !normalizedWorker.equals(current.leaseOwner())) {
                throw new ExecutionConflictException("execution intent changed concurrently");
            }
            int attemptNumber = current.attemptCount() + 1;
            boolean retryAllowed = !successful
                && retryable
                && attemptNumber < current.maxAttempts();
            IntentStatus target = successful
                ? IntentStatus.SUCCEEDED
                : retryAllowed ? IntentStatus.RETRY_WAIT : IntentStatus.DEAD;
            Instant completed = Objects.requireNonNull(finishedAt, "finishedAt must not be null");
            Instant nextAttemptAt = current.nextAttemptAt();
            if (retryAllowed) {
                nextAttemptAt = Objects.requireNonNull(
                    requestedNextAttemptAt,
                    "nextAttemptAt must not be null for a retry"
                );
                if (nextAttemptAt.isBefore(completed) || nextAttemptAt.isBefore(current.availableAt())) {
                    throw new IllegalArgumentException(
                        "nextAttemptAt must not precede completion or availability"
                    );
                }
            }
            AttemptResult attemptResult = successful
                ? AttemptResult.SUCCEEDED
                : retryAllowed ? AttemptResult.RETRYABLE_FAILURE : AttemptResult.PERMANENT_FAILURE;
            ExecutionAttempt attempt = new ExecutionAttempt(
                Objects.requireNonNull(attemptId, "attemptId must not be null"),
                current.tenantId(),
                current.intentId(),
                attemptNumber,
                normalizedWorker,
                claimedAt,
                startedAt,
                completed,
                attemptResult,
                errorCode,
                errorSummary,
                requestId,
                traceId
            );
            insertAttempt(attempt);
            int changed = jdbc.update(
                """
                update ap_sla_execution_intent
                set status = :targetStatus,
                    lease_owner = null,
                    lease_until = null,
                    attempt_count = :attemptNumber,
                    next_attempt_at = :nextAttemptAt,
                    completed_at = :completedAt,
                    dead_at = :deadAt,
                    cancelled_at = null,
                    last_error_code = :errorCode,
                    last_error_summary = :errorSummary,
                    updated_at = :finishedAt,
                    version = version + 1
                where tenant_id = :tenantId
                  and intent_id = :intentId
                  and status = 'CLAIMED'
                  and lease_owner = :workerId
                  and version = :expectedVersion
                """,
                new MapSqlParameterSource()
                    .addValue("targetStatus", target.name())
                    .addValue("attemptNumber", attemptNumber)
                    .addValue("nextAttemptAt", offset(nextAttemptAt))
                    .addValue("completedAt", successful ? offset(completed) : null)
                    .addValue("deadAt", target == IntentStatus.DEAD ? offset(completed) : null)
                    .addValue("errorCode", successful ? null : errorCode)
                    .addValue("errorSummary", successful ? null : errorSummary)
                    .addValue("finishedAt", offset(completed))
                    .addValue("tenantId", current.tenantId())
                    .addValue("intentId", current.intentId())
                    .addValue("workerId", normalizedWorker)
                    .addValue("expectedVersion", expectedVersion)
            );
            if (changed != 1) {
                throw new ExecutionConflictException("execution intent changed concurrently");
            }
            return requireIntent(current.tenantId(), current.intentId());
        });
        return Objects.requireNonNull(updated, "updated execution intent must not be null");
    }

    private int insertIntent(ExecutionIntent intent) {
        return jdbc.update(
            """
            insert into ap_sla_execution_intent (
                intent_id, tenant_id, sla_instance_id, approval_instance_id,
                task_id, collaboration_participant_id, policy_id, policy_version,
                calendar_id, calendar_version, source_intent_id, action_type,
                action_sequence, scheduled_at, available_at, status, lease_owner,
                lease_until, attempt_count, max_attempts, next_attempt_at,
                idempotency_key, payload_json, responsible_user_id, request_id,
                trace_id, version, created_at, updated_at, completed_at, dead_at,
                cancelled_at, last_error_code, last_error_summary
            ) values (
                :intentId, :tenantId, :slaInstanceId, :approvalInstanceId,
                :taskId, :collaborationParticipantId, :policyId, :policyVersion,
                :calendarId, :calendarVersion, :sourceIntentId, :actionType,
                :actionSequence, :scheduledAt, :availableAt, :status, :leaseOwner,
                :leaseUntil, :attemptCount, :maxAttempts, :nextAttemptAt,
                :idempotencyKey, cast(:payloadJson as jsonb), :responsibleUserId,
                :requestId, :traceId, :version, :createdAt, :updatedAt,
                :completedAt, :deadAt, :cancelledAt, :lastErrorCode,
                :lastErrorSummary
            ) on conflict do nothing
            """,
            intentParameters(intent)
        );
    }

    private void insertAttempt(ExecutionAttempt attempt) {
        int inserted = jdbc.update(
            """
            insert into ap_sla_execution_attempt (
                attempt_id, tenant_id, intent_id, attempt_number, worker_id,
                claimed_at, started_at, finished_at, result, error_code,
                error_summary, request_id, trace_id
            ) values (
                :attemptId, :tenantId, :intentId, :attemptNumber, :workerId,
                :claimedAt, :startedAt, :finishedAt, :result, :errorCode,
                :errorSummary, :requestId, :traceId
            )
            """,
            new MapSqlParameterSource()
                .addValue("attemptId", attempt.attemptId())
                .addValue("tenantId", attempt.tenantId())
                .addValue("intentId", attempt.intentId())
                .addValue("attemptNumber", attempt.attemptNumber())
                .addValue("workerId", attempt.workerId())
                .addValue("claimedAt", offset(attempt.claimedAt()))
                .addValue("startedAt", offset(attempt.startedAt()))
                .addValue("finishedAt", offset(attempt.finishedAt()))
                .addValue("result", attempt.result().name())
                .addValue("errorCode", attempt.errorCode())
                .addValue("errorSummary", attempt.errorSummary())
                .addValue("requestId", attempt.requestId())
                .addValue("traceId", attempt.traceId())
        );
        if (inserted != 1) {
            throw new ExecutionConflictException("execution attempt was not appended");
        }
    }

    private ExecutionIntent requireIntent(String tenantId, UUID intentId) {
        return findIntent(tenantId, intentId).orElseThrow(() -> new ExecutionNotFoundException(
            "execution intent was not found"
        ));
    }

    private ExecutionIntent requireIntentForUpdate(String tenantId, UUID intentId) {
        return jdbc.query(
            """
            select * from ap_sla_execution_intent
            where tenant_id = :tenantId and intent_id = :intentId
            for update
            """,
            identity(tenantId, intentId),
            intentMapper()
        ).stream().findFirst().orElseThrow(() -> new ExecutionNotFoundException(
            "execution intent was not found"
        ));
    }

    private Optional<ReplayEvidence> findReplayByIdempotency(
        String tenantId,
        String replayIdempotencyKey
    ) {
        return jdbc.query(
            """
            select * from ap_sla_execution_replay
            where tenant_id = :tenantId
              and replay_idempotency_key = :replayIdempotencyKey
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", requireText(tenantId, "tenantId", 128))
                .addValue(
                    "replayIdempotencyKey",
                    requireText(replayIdempotencyKey, "replayIdempotencyKey", 200)
                ),
            replayMapper()
        ).stream().findFirst();
    }

    private void acquireReplayLock(String tenantId, String replayIdempotencyKey) {
        Boolean locked = jdbc.queryForObject(
            """
            select true
            from (
                select pg_advisory_xact_lock(
                    hashtextextended(:lockKey, 0)
                )
            ) lock_call
            """,
            new MapSqlParameterSource().addValue(
                "lockKey",
                requireText(tenantId, "tenantId", 128) + ':'
                    + requireText(replayIdempotencyKey, "replayIdempotencyKey", 200)
            ),
            Boolean.class
        );
        if (!Boolean.TRUE.equals(locked)) {
            throw new ExecutionConflictException("execution replay lock was not acquired");
        }
    }

    private void lockSla(String tenantId, UUID slaInstanceId) {
        Boolean locked = jdbc.queryForObject(
            """
            select true from ap_sla_instance
            where tenant_id = :tenantId and sla_instance_id = :slaInstanceId
            for update
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("slaInstanceId", slaInstanceId),
            Boolean.class
        );
        if (!Boolean.TRUE.equals(locked)) {
            throw new ExecutionNotFoundException("SLA instance was not found");
        }
    }

    private int nextActionSequence(String tenantId, UUID slaInstanceId, ActionType actionType) {
        Integer next = jdbc.queryForObject(
            """
            select coalesce(max(action_sequence), 0) + 1
            from ap_sla_execution_intent
            where tenant_id = :tenantId
              and sla_instance_id = :slaInstanceId
              and action_type = :actionType
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("slaInstanceId", slaInstanceId)
                .addValue("actionType", actionType.name()),
            Integer.class
        );
        return next == null ? 1 : next;
    }

    private String criteriaWhere(
        ExecutionIntentCriteria criteria,
        MapSqlParameterSource parameters
    ) {
        StringBuilder where = new StringBuilder(" where tenant_id = :tenantId");
        if (!criteria.statuses().isEmpty()) {
            where.append(" and status in (:statuses)");
            parameters.addValue(
                "statuses",
                criteria.statuses().stream().map(Enum::name).toList()
            );
        }
        if (!criteria.actionTypes().isEmpty()) {
            where.append(" and action_type in (:actionTypes)");
            parameters.addValue(
                "actionTypes",
                criteria.actionTypes().stream().map(Enum::name).toList()
            );
        }
        if (criteria.scheduledFrom() != null) {
            where.append(" and scheduled_at >= :scheduledFrom");
            parameters.addValue("scheduledFrom", offset(criteria.scheduledFrom()));
        }
        if (criteria.scheduledTo() != null) {
            where.append(" and scheduled_at <= :scheduledTo");
            parameters.addValue("scheduledTo", offset(criteria.scheduledTo()));
        }
        if (criteria.requestId() != null) {
            where.append(" and request_id = :requestId");
            parameters.addValue("requestId", criteria.requestId());
        }
        if (criteria.responsibleUserId() != null) {
            where.append(" and responsible_user_id = :responsibleUserId");
            parameters.addValue("responsibleUserId", criteria.responsibleUserId());
        }
        return where.append(' ').toString();
    }

    private MapSqlParameterSource intentParameters(ExecutionIntent intent) {
        return new MapSqlParameterSource()
            .addValue("intentId", intent.intentId())
            .addValue("tenantId", intent.tenantId())
            .addValue("slaInstanceId", intent.slaInstanceId())
            .addValue("approvalInstanceId", intent.approvalInstanceId())
            .addValue("taskId", intent.taskId(), Types.OTHER)
            .addValue(
                "collaborationParticipantId",
                intent.collaborationParticipantId(),
                Types.OTHER
            )
            .addValue("policyId", intent.policyId())
            .addValue("policyVersion", intent.policyVersion())
            .addValue("calendarId", intent.calendarId(), Types.OTHER)
            .addValue("calendarVersion", intent.calendarVersion(), Types.INTEGER)
            .addValue("sourceIntentId", intent.sourceIntentId(), Types.OTHER)
            .addValue("actionType", intent.actionType().name())
            .addValue("actionSequence", intent.actionSequence())
            .addValue("scheduledAt", offset(intent.scheduledAt()))
            .addValue("availableAt", offset(intent.availableAt()))
            .addValue("status", intent.status().name())
            .addValue("leaseOwner", intent.leaseOwner())
            .addValue("leaseUntil", nullableOffset(intent.leaseUntil()))
            .addValue("attemptCount", intent.attemptCount())
            .addValue("maxAttempts", intent.maxAttempts())
            .addValue("nextAttemptAt", offset(intent.nextAttemptAt()))
            .addValue("idempotencyKey", intent.idempotencyKey())
            .addValue("payloadJson", encode(intent.payload()))
            .addValue("responsibleUserId", intent.responsibleUserId())
            .addValue("requestId", intent.requestId())
            .addValue("traceId", intent.traceId())
            .addValue("version", intent.version())
            .addValue("createdAt", offset(intent.createdAt()))
            .addValue("updatedAt", offset(intent.updatedAt()))
            .addValue("completedAt", nullableOffset(intent.completedAt()))
            .addValue("deadAt", nullableOffset(intent.deadAt()))
            .addValue("cancelledAt", nullableOffset(intent.cancelledAt()))
            .addValue("lastErrorCode", intent.lastErrorCode())
            .addValue("lastErrorSummary", intent.lastErrorSummary());
    }

    private static MapSqlParameterSource replayParameters(ReplayEvidence evidence) {
        return new MapSqlParameterSource()
            .addValue("replayId", evidence.replayId())
            .addValue("tenantId", evidence.tenantId())
            .addValue("originalIntentId", evidence.originalIntentId())
            .addValue("newIntentId", evidence.newIntentId())
            .addValue("originalErrorCode", evidence.originalErrorCode())
            .addValue("originalErrorSummary", evidence.originalErrorSummary())
            .addValue("replayReason", evidence.replayReason())
            .addValue("replayIdempotencyKey", evidence.replayIdempotencyKey())
            .addValue("requestedBy", evidence.requestedBy())
            .addValue("requestedAt", offset(evidence.requestedAt()))
            .addValue("auditChainReference", evidence.auditChainReference())
            .addValue("requestId", evidence.requestId())
            .addValue("traceId", evidence.traceId());
    }

    private static MapSqlParameterSource identity(String tenantId, UUID intentId) {
        return new MapSqlParameterSource()
            .addValue("tenantId", requireText(tenantId, "tenantId", 128))
            .addValue("intentId", Objects.requireNonNull(intentId, "intentId must not be null"));
    }

    private RowMapper<ExecutionIntent> intentMapper() {
        return (resultSet, rowNumber) -> new ExecutionIntent(
            resultSet.getObject("intent_id", UUID.class),
            resultSet.getString("tenant_id"),
            resultSet.getObject("sla_instance_id", UUID.class),
            resultSet.getObject("approval_instance_id", UUID.class),
            resultSet.getObject("task_id", UUID.class),
            resultSet.getObject("collaboration_participant_id", UUID.class),
            resultSet.getObject("policy_id", UUID.class),
            resultSet.getInt("policy_version"),
            resultSet.getObject("calendar_id", UUID.class),
            nullableInteger(resultSet, "calendar_version"),
            resultSet.getObject("source_intent_id", UUID.class),
            ActionType.valueOf(resultSet.getString("action_type")),
            resultSet.getInt("action_sequence"),
            instant(resultSet, "scheduled_at"),
            instant(resultSet, "available_at"),
            IntentStatus.valueOf(resultSet.getString("status")),
            resultSet.getString("lease_owner"),
            nullableInstant(resultSet, "lease_until"),
            resultSet.getInt("attempt_count"),
            resultSet.getInt("max_attempts"),
            instant(resultSet, "next_attempt_at"),
            resultSet.getString("idempotency_key"),
            decode(resultSet.getString("payload_json")),
            resultSet.getString("responsible_user_id"),
            resultSet.getString("request_id"),
            resultSet.getString("trace_id"),
            resultSet.getLong("version"),
            instant(resultSet, "created_at"),
            instant(resultSet, "updated_at"),
            nullableInstant(resultSet, "completed_at"),
            nullableInstant(resultSet, "dead_at"),
            nullableInstant(resultSet, "cancelled_at"),
            resultSet.getString("last_error_code"),
            resultSet.getString("last_error_summary")
        );
    }

    private static RowMapper<ExecutionAttempt> attemptMapper() {
        return (resultSet, rowNumber) -> new ExecutionAttempt(
            resultSet.getObject("attempt_id", UUID.class),
            resultSet.getString("tenant_id"),
            resultSet.getObject("intent_id", UUID.class),
            resultSet.getInt("attempt_number"),
            resultSet.getString("worker_id"),
            instant(resultSet, "claimed_at"),
            instant(resultSet, "started_at"),
            instant(resultSet, "finished_at"),
            AttemptResult.valueOf(resultSet.getString("result")),
            resultSet.getString("error_code"),
            resultSet.getString("error_summary"),
            resultSet.getString("request_id"),
            resultSet.getString("trace_id")
        );
    }

    private static RowMapper<ReplayEvidence> replayMapper() {
        return (resultSet, rowNumber) -> new ReplayEvidence(
            resultSet.getObject("replay_id", UUID.class),
            resultSet.getString("tenant_id"),
            resultSet.getObject("original_intent_id", UUID.class),
            resultSet.getObject("new_intent_id", UUID.class),
            resultSet.getString("original_error_code"),
            resultSet.getString("original_error_summary"),
            resultSet.getString("replay_reason"),
            resultSet.getString("replay_idempotency_key"),
            resultSet.getString("requested_by"),
            instant(resultSet, "requested_at"),
            resultSet.getString("audit_chain_reference"),
            resultSet.getString("request_id"),
            resultSet.getString("trace_id")
        );
    }

    private String encode(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("unable to encode SLA execution payload", exception);
        }
    }

    private Map<String, Object> decode(String value) {
        try {
            return objectMapper.readValue(value, OBJECT_MAP);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("unable to decode SLA execution payload", exception);
        }
    }

    private static Integer nullableInteger(ResultSet resultSet, String column) throws SQLException {
        Integer value = resultSet.getObject(column, Integer.class);
        return resultSet.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime offset(Instant value) {
        return Objects.requireNonNull(value, "instant must not be null").atOffset(ZoneOffset.UTC);
    }

    private static OffsetDateTime nullableOffset(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " exceeds " + maximumLength + " characters");
        }
        return normalized;
    }
}
