package io.github.akaryc1b.approval.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Durable, tenant-scoped SLA execution intent, attempt, lease and replay evidence. */
public interface ApprovalSlaExecutionStore {

    int enqueue(List<ExecutionIntent> intents);

    List<String> findRunnableTenants(Instant now, int limit);

    List<ExecutionIntent> claimDue(
        String tenantId,
        Instant now,
        int limit,
        String workerId,
        Instant leaseUntil
    );

    ExecutionIntent markSucceeded(
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
    );

    ExecutionIntent markFailed(
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
    );

    int cancelActiveForSla(
        String tenantId,
        UUID slaInstanceId,
        Instant cancelledAt,
        String reason
    );

    int updateFutureResponsibleUser(
        String tenantId,
        UUID slaInstanceId,
        String responsibleUserId,
        Instant updatedAt
    );

    Optional<ExecutionIntent> findIntent(String tenantId, UUID intentId);

    ExecutionIntentPage findIntents(ExecutionIntentCriteria criteria);

    List<ExecutionAttempt> findAttempts(String tenantId, UUID intentId);

    QueueSummary summarize(String tenantId, Instant now);

    ReplayResult replayDead(ReplayRequest request);

    record ExecutionIntent(
        UUID intentId,
        String tenantId,
        UUID slaInstanceId,
        UUID approvalInstanceId,
        UUID taskId,
        UUID collaborationParticipantId,
        UUID policyId,
        int policyVersion,
        UUID calendarId,
        Integer calendarVersion,
        UUID sourceIntentId,
        ActionType actionType,
        int actionSequence,
        Instant scheduledAt,
        Instant availableAt,
        IntentStatus status,
        String leaseOwner,
        Instant leaseUntil,
        int attemptCount,
        int maxAttempts,
        Instant nextAttemptAt,
        String idempotencyKey,
        Map<String, Object> payload,
        String responsibleUserId,
        String requestId,
        String traceId,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        Instant deadAt,
        Instant cancelledAt,
        String lastErrorCode,
        String lastErrorSummary
    ) {
        public ExecutionIntent {
            intentId = Objects.requireNonNull(intentId, "intentId must not be null");
            tenantId = requireText(tenantId, "tenantId", 128);
            slaInstanceId = Objects.requireNonNull(
                slaInstanceId,
                "slaInstanceId must not be null"
            );
            approvalInstanceId = Objects.requireNonNull(
                approvalInstanceId,
                "approvalInstanceId must not be null"
            );
            policyId = Objects.requireNonNull(policyId, "policyId must not be null");
            if (policyVersion < 1) {
                throw new IllegalArgumentException("policyVersion must be positive");
            }
            if ((calendarId == null) != (calendarVersion == null)) {
                throw new IllegalArgumentException(
                    "calendarId and calendarVersion must either both be present or both be absent"
                );
            }
            if (calendarVersion != null && calendarVersion < 1) {
                throw new IllegalArgumentException("calendarVersion must be positive");
            }
            actionType = Objects.requireNonNull(actionType, "actionType must not be null");
            if (actionSequence < 1) {
                throw new IllegalArgumentException("actionSequence must be positive");
            }
            scheduledAt = Objects.requireNonNull(scheduledAt, "scheduledAt must not be null");
            availableAt = Objects.requireNonNull(availableAt, "availableAt must not be null");
            nextAttemptAt = Objects.requireNonNull(
                nextAttemptAt,
                "nextAttemptAt must not be null"
            );
            if (availableAt.isBefore(scheduledAt) || nextAttemptAt.isBefore(availableAt)) {
                throw new IllegalArgumentException("execution schedule is inconsistent");
            }
            status = Objects.requireNonNull(status, "status must not be null");
            leaseOwner = normalizeOptional(leaseOwner, 200);
            if (status == IntentStatus.CLAIMED) {
                if (leaseOwner == null || leaseUntil == null) {
                    throw new IllegalArgumentException("claimed intent requires lease evidence");
                }
            } else if (leaseOwner != null || leaseUntil != null) {
                throw new IllegalArgumentException("only a claimed intent can retain a lease");
            }
            if (attemptCount < 0 || maxAttempts < 1 || attemptCount > maxAttempts) {
                throw new IllegalArgumentException("execution attempt values are invalid");
            }
            idempotencyKey = requireText(idempotencyKey, "idempotencyKey", 200);
            payload = payload == null ? Map.of() : Map.copyOf(payload);
            responsibleUserId = requireText(responsibleUserId, "responsibleUserId", 200);
            requestId = requireText(requestId, "requestId", 128);
            traceId = normalizeOptional(traceId, 128);
            if (version < 1) {
                throw new IllegalArgumentException("version must be positive");
            }
            createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
            if (updatedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("updatedAt must not precede createdAt");
            }
            lastErrorCode = normalizeOptional(lastErrorCode, 128);
            lastErrorSummary = normalizeOptional(lastErrorSummary, 1000);
            validateTerminalEvidence(status, completedAt, deadAt, cancelledAt);
        }

        public boolean runnableAt(Instant now) {
            Objects.requireNonNull(now, "now must not be null");
            if (status == IntentStatus.READY || status == IntentStatus.RETRY_WAIT) {
                return !nextAttemptAt.isAfter(now);
            }
            return status == IntentStatus.CLAIMED
                && leaseUntil != null
                && leaseUntil.isBefore(now);
        }
    }

    record ExecutionAttempt(
        UUID attemptId,
        String tenantId,
        UUID intentId,
        int attemptNumber,
        String workerId,
        Instant claimedAt,
        Instant startedAt,
        Instant finishedAt,
        AttemptResult result,
        String errorCode,
        String errorSummary,
        String requestId,
        String traceId
    ) {
        public ExecutionAttempt {
            attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
            tenantId = requireText(tenantId, "tenantId", 128);
            intentId = Objects.requireNonNull(intentId, "intentId must not be null");
            if (attemptNumber < 1) {
                throw new IllegalArgumentException("attemptNumber must be positive");
            }
            workerId = requireText(workerId, "workerId", 200);
            claimedAt = Objects.requireNonNull(claimedAt, "claimedAt must not be null");
            startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
            finishedAt = Objects.requireNonNull(finishedAt, "finishedAt must not be null");
            if (startedAt.isBefore(claimedAt) || finishedAt.isBefore(startedAt)) {
                throw new IllegalArgumentException("attempt timestamps are inconsistent");
            }
            result = Objects.requireNonNull(result, "result must not be null");
            errorCode = normalizeOptional(errorCode, 128);
            errorSummary = normalizeOptional(errorSummary, 1000);
            if (result == AttemptResult.SUCCEEDED) {
                if (errorCode != null || errorSummary != null) {
                    throw new IllegalArgumentException("successful attempt must not retain an error");
                }
            } else if (errorCode == null || errorSummary == null) {
                throw new IllegalArgumentException("failed attempt requires bounded error evidence");
            }
            requestId = requireText(requestId, "requestId", 128);
            traceId = normalizeOptional(traceId, 128);
        }
    }

    record ReplayEvidence(
        UUID replayId,
        String tenantId,
        UUID originalIntentId,
        UUID newIntentId,
        String originalErrorCode,
        String originalErrorSummary,
        String replayReason,
        String replayIdempotencyKey,
        String requestedBy,
        Instant requestedAt,
        String auditChainReference,
        String requestId,
        String traceId
    ) {
        public ReplayEvidence {
            replayId = Objects.requireNonNull(replayId, "replayId must not be null");
            tenantId = requireText(tenantId, "tenantId", 128);
            originalIntentId = Objects.requireNonNull(
                originalIntentId,
                "originalIntentId must not be null"
            );
            newIntentId = Objects.requireNonNull(newIntentId, "newIntentId must not be null");
            if (originalIntentId.equals(newIntentId)) {
                throw new IllegalArgumentException("replay must create a new intent");
            }
            originalErrorCode = normalizeOptional(originalErrorCode, 128);
            originalErrorSummary = normalizeOptional(originalErrorSummary, 1000);
            replayReason = requireText(replayReason, "replayReason", 512);
            replayIdempotencyKey = requireText(
                replayIdempotencyKey,
                "replayIdempotencyKey",
                200
            );
            requestedBy = requireText(requestedBy, "requestedBy", 200);
            requestedAt = Objects.requireNonNull(requestedAt, "requestedAt must not be null");
            auditChainReference = requireText(
                auditChainReference,
                "auditChainReference",
                256
            );
            requestId = requireText(requestId, "requestId", 128);
            traceId = normalizeOptional(traceId, 128);
        }
    }

    record ReplayRequest(
        UUID replayId,
        UUID newIntentId,
        String tenantId,
        UUID originalIntentId,
        String replayReason,
        String replayIdempotencyKey,
        String executionIdempotencyKey,
        String requestedBy,
        Instant requestedAt,
        String auditChainReference,
        String requestId,
        String traceId
    ) {
        public ReplayRequest {
            replayId = Objects.requireNonNull(replayId, "replayId must not be null");
            newIntentId = Objects.requireNonNull(newIntentId, "newIntentId must not be null");
            tenantId = requireText(tenantId, "tenantId", 128);
            originalIntentId = Objects.requireNonNull(
                originalIntentId,
                "originalIntentId must not be null"
            );
            if (originalIntentId.equals(newIntentId)) {
                throw new IllegalArgumentException("replay must create a new intent");
            }
            replayReason = requireText(replayReason, "replayReason", 512);
            replayIdempotencyKey = requireText(
                replayIdempotencyKey,
                "replayIdempotencyKey",
                200
            );
            executionIdempotencyKey = requireText(
                executionIdempotencyKey,
                "executionIdempotencyKey",
                200
            );
            requestedBy = requireText(requestedBy, "requestedBy", 200);
            requestedAt = Objects.requireNonNull(requestedAt, "requestedAt must not be null");
            auditChainReference = requireText(
                auditChainReference,
                "auditChainReference",
                256
            );
            requestId = requireText(requestId, "requestId", 128);
            traceId = normalizeOptional(traceId, 128);
        }
    }

    record ReplayResult(
        ExecutionIntent intent,
        ReplayEvidence evidence,
        boolean replayedExistingRequest
    ) {
        public ReplayResult {
            intent = Objects.requireNonNull(intent, "intent must not be null");
            evidence = Objects.requireNonNull(evidence, "evidence must not be null");
            if (!intent.intentId().equals(evidence.newIntentId())) {
                throw new IllegalArgumentException("replay intent and evidence do not match");
            }
        }
    }

    record ExecutionIntentCriteria(
        String tenantId,
        Set<IntentStatus> statuses,
        Set<ActionType> actionTypes,
        Instant scheduledFrom,
        Instant scheduledTo,
        String requestId,
        String responsibleUserId,
        int limit,
        int offset
    ) {
        public ExecutionIntentCriteria {
            tenantId = requireText(tenantId, "tenantId", 128);
            statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
            actionTypes = actionTypes == null ? Set.of() : Set.copyOf(actionTypes);
            requestId = normalizeOptional(requestId, 128);
            responsibleUserId = normalizeOptional(responsibleUserId, 200);
            if (scheduledFrom != null && scheduledTo != null && scheduledTo.isBefore(scheduledFrom)) {
                throw new IllegalArgumentException("scheduledTo must not precede scheduledFrom");
            }
            validatePage(limit, offset);
        }
    }

    record ExecutionIntentPage(
        List<ExecutionIntent> items,
        long total,
        int limit,
        int offset,
        boolean hasMore
    ) {
        public ExecutionIntentPage(List<ExecutionIntent> items, long total, int limit, int offset) {
            this(items, total, limit, offset, offset + safeSize(items) < total);
        }

        public ExecutionIntentPage {
            items = items == null ? List.of() : List.copyOf(items);
            if (total < 0) {
                throw new IllegalArgumentException("total must not be negative");
            }
            validatePage(limit, offset);
        }
    }

    record QueueSummary(
        long ready,
        long claimed,
        long retryWait,
        long succeeded,
        long dead,
        long cancelled,
        long expiredLeases
    ) {
        public QueueSummary {
            if (ready < 0 || claimed < 0 || retryWait < 0 || succeeded < 0
                || dead < 0 || cancelled < 0 || expiredLeases < 0) {
                throw new IllegalArgumentException("queue summary counts must not be negative");
            }
        }
    }

    enum ActionType {
        REMINDER,
        OVERDUE,
        ESCALATION,
        AUTOMATIC_ACTION
    }

    enum IntentStatus {
        READY,
        CLAIMED,
        RETRY_WAIT,
        SUCCEEDED,
        DEAD,
        CANCELLED;

        public boolean terminal() {
            return this == SUCCEEDED || this == DEAD || this == CANCELLED;
        }
    }

    enum AttemptResult {
        SUCCEEDED,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE
    }

    final class ExecutionConflictException extends RuntimeException {
        public ExecutionConflictException(String message) {
            super(message);
        }
    }

    final class ExecutionNotFoundException extends RuntimeException {
        public ExecutionNotFoundException(String message) {
            super(message);
        }
    }

    private static void validateTerminalEvidence(
        IntentStatus status,
        Instant completedAt,
        Instant deadAt,
        Instant cancelledAt
    ) {
        boolean valid = switch (status) {
            case SUCCEEDED -> completedAt != null && deadAt == null && cancelledAt == null;
            case DEAD -> completedAt == null && deadAt != null && cancelledAt == null;
            case CANCELLED -> completedAt == null && deadAt == null && cancelledAt != null;
            case READY, CLAIMED, RETRY_WAIT -> completedAt == null
                && deadAt == null
                && cancelledAt == null;
        };
        if (!valid) {
            throw new IllegalArgumentException("intent terminal evidence is inconsistent");
        }
    }

    private static void validatePage(int limit, int offset) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
    }

    private static int safeSize(List<?> values) {
        return values == null ? 0 : values.size();
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

    private static String normalizeOptional(String value, int maximumLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException("value exceeds " + maximumLength + " characters");
        }
        return normalized;
    }
}
