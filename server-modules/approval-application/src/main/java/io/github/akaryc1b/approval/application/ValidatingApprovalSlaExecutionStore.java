package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaInstance;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaPolicyVersion;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaTargetType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Validates and enriches durable SLA execution intents from authoritative snapshots. */
public final class ValidatingApprovalSlaExecutionStore implements ApprovalSlaExecutionStore {

    private final ApprovalSlaExecutionStore delegate;
    private final ApprovalSlaStore slas;

    public ValidatingApprovalSlaExecutionStore(
        ApprovalSlaExecutionStore delegate,
        ApprovalSlaStore slas
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.slas = Objects.requireNonNull(slas, "slas must not be null");
    }

    @Override
    public int enqueue(List<ExecutionIntent> intents) {
        if (intents == null || intents.isEmpty()) {
            return delegate.enqueue(List.of());
        }
        List<ExecutionIntent> validated = new ArrayList<>(intents.size());
        for (ExecutionIntent intent : intents) {
            validated.add(validateAndEnrich(Objects.requireNonNull(
                intent,
                "execution intent must not be null"
            )));
        }
        return delegate.enqueue(List.copyOf(validated));
    }

    @Override
    public List<String> findRunnableTenants(Instant now, int limit) {
        return delegate.findRunnableTenants(now, limit);
    }

    @Override
    public List<ExecutionIntent> claimDue(
        String tenantId,
        Instant now,
        int limit,
        String workerId,
        Instant leaseUntil
    ) {
        return delegate.claimDue(tenantId, now, limit, workerId, leaseUntil);
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
        return delegate.markSucceeded(
            tenantId,
            intentId,
            expectedVersion,
            workerId,
            attemptId,
            claimedAt,
            startedAt,
            finishedAt,
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
        return delegate.markFailed(
            tenantId,
            intentId,
            expectedVersion,
            workerId,
            attemptId,
            claimedAt,
            startedAt,
            finishedAt,
            retryable,
            nextAttemptAt,
            errorCode,
            errorSummary,
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
        return delegate.cancelActiveForSla(tenantId, slaInstanceId, cancelledAt, reason);
    }

    @Override
    public int updateFutureResponsibleUser(
        String tenantId,
        UUID slaInstanceId,
        String responsibleUserId,
        Instant updatedAt
    ) {
        return delegate.updateFutureResponsibleUser(
            tenantId,
            slaInstanceId,
            responsibleUserId,
            updatedAt
        );
    }

    @Override
    public Optional<ExecutionIntent> findIntent(String tenantId, UUID intentId) {
        return delegate.findIntent(tenantId, intentId);
    }

    @Override
    public ExecutionIntentPage findIntents(ExecutionIntentCriteria criteria) {
        return delegate.findIntents(criteria);
    }

    @Override
    public List<ExecutionAttempt> findAttempts(String tenantId, UUID intentId) {
        return delegate.findAttempts(tenantId, intentId);
    }

    @Override
    public QueueSummary summarize(String tenantId, Instant now) {
        return delegate.summarize(tenantId, now);
    }

    @Override
    public ReplayResult replayDead(ReplayRequest request) {
        return delegate.replayDead(request);
    }

    private ExecutionIntent validateAndEnrich(ExecutionIntent intent) {
        SlaInstance instance = slas.findInstance(
            intent.tenantId(),
            intent.slaInstanceId()
        ).orElseThrow(() -> new IllegalStateException(
            "authoritative SLA instance was not found for execution enqueue"
        ));
        SlaPolicyVersion policy = slas.findPolicyVersion(
            intent.tenantId(),
            intent.policyId(),
            intent.policyVersion()
        ).orElseThrow(() -> new IllegalStateException(
            "immutable SLA policy snapshot was not found for execution enqueue"
        ));
        validateIntentIdentity(intent, instance);
        validatePolicyBinding(instance, policy);
        return copyWithPayload(intent, enrichedPayload(intent, instance, policy));
    }

    private static void validateIntentIdentity(
        ExecutionIntent intent,
        SlaInstance instance
    ) {
        if (!intent.tenantId().equals(instance.tenantId())
            || !intent.approvalInstanceId().equals(instance.approvalInstanceId())
            || !Objects.equals(intent.taskId(), instance.taskId())
            || !Objects.equals(
                intent.collaborationParticipantId(),
                instance.collaborationParticipantId()
            )
            || !intent.policyId().equals(instance.policyId())
            || intent.policyVersion() != instance.policyVersion()
            || !Objects.equals(intent.calendarId(), instance.calendarId())
            || !Objects.equals(intent.calendarVersion(), instance.calendarVersion())) {
            throw new IllegalArgumentException(
                "execution intent does not match the authoritative SLA instance"
            );
        }
    }

    private static void validatePolicyBinding(
        SlaInstance instance,
        SlaPolicyVersion policy
    ) {
        if (!policy.immutable()) {
            throw new IllegalArgumentException(
                "execution enqueue requires an immutable policy snapshot"
            );
        }
        if (!instance.tenantId().equals(policy.tenantId())
            || !instance.policyId().equals(policy.policyId())
            || instance.policyVersion() != policy.policyVersion()
            || !Objects.equals(instance.calendarId(), policy.calendarId())
            || !Objects.equals(instance.calendarVersion(), policy.calendarVersion())
            || !instance.definitionKey().equals(policy.definitionKey())
            || !compatibleTargetBinding(instance, policy)) {
            throw new IllegalArgumentException(
                "SLA instance and policy snapshot bindings do not match"
            );
        }
    }

    private static boolean compatibleTargetBinding(
        SlaInstance instance,
        SlaPolicyVersion policy
    ) {
        return switch (instance.targetType()) {
            case PROCESS -> processFallback(policy)
                && instance.taskDefinitionKey() == null;
            case TASK -> taskPolicy(instance, policy)
                || processFallback(policy);
            case COLLABORATION_PARTICIPANT -> collaborationPolicy(instance, policy)
                || taskPolicy(instance, policy)
                || processFallback(policy);
        };
    }

    private static boolean collaborationPolicy(
        SlaInstance instance,
        SlaPolicyVersion policy
    ) {
        return policy.targetType() == SlaTargetType.COLLABORATION_PARTICIPANT
            && Objects.equals(
                instance.taskDefinitionKey(),
                policy.taskDefinitionKey()
            );
    }

    private static boolean taskPolicy(SlaInstance instance, SlaPolicyVersion policy) {
        return policy.targetType() == SlaTargetType.TASK
            && Objects.equals(
                instance.taskDefinitionKey(),
                policy.taskDefinitionKey()
            );
    }

    private static boolean processFallback(SlaPolicyVersion policy) {
        return policy.targetType() == SlaTargetType.PROCESS
            && policy.taskDefinitionKey() == null;
    }

    private static Map<String, Object> enrichedPayload(
        ExecutionIntent intent,
        SlaInstance instance,
        SlaPolicyVersion policy
    ) {
        Map<String, Object> payload = new LinkedHashMap<>(intent.payload());
        payload.put("policyContentHash", policy.contentHash());
        payload.put("policyDefinitionKey", policy.definitionKey());
        payload.put("policyTargetType", policy.targetType().name());
        if (policy.releaseVersion() != null) {
            payload.put("policyReleaseVersion", policy.releaseVersion());
        }
        if (policy.taskDefinitionKey() != null) {
            payload.put("policyTaskDefinitionKey", policy.taskDefinitionKey());
        }
        payload.put("authoritativeDueAt", instance.dueAt().toString());
        payload.put("authoritativeOverdueAt", instance.overdueAt().toString());
        return Map.copyOf(payload);
    }

    private static ExecutionIntent copyWithPayload(
        ExecutionIntent intent,
        Map<String, Object> payload
    ) {
        return new ExecutionIntent(
            intent.intentId(),
            intent.tenantId(),
            intent.slaInstanceId(),
            intent.approvalInstanceId(),
            intent.taskId(),
            intent.collaborationParticipantId(),
            intent.policyId(),
            intent.policyVersion(),
            intent.calendarId(),
            intent.calendarVersion(),
            intent.sourceIntentId(),
            intent.actionType(),
            intent.actionSequence(),
            intent.scheduledAt(),
            intent.availableAt(),
            intent.status(),
            intent.leaseOwner(),
            intent.leaseUntil(),
            intent.attemptCount(),
            intent.maxAttempts(),
            intent.nextAttemptAt(),
            intent.idempotencyKey(),
            payload,
            intent.responsibleUserId(),
            intent.requestId(),
            intent.traceId(),
            intent.version(),
            intent.createdAt(),
            intent.updatedAt(),
            intent.completedAt(),
            intent.deadAt(),
            intent.cancelledAt(),
            intent.lastErrorCode(),
            intent.lastErrorSummary()
        );
    }
}
