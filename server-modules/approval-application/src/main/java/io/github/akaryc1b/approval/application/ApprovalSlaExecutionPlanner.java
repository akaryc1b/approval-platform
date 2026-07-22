package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ActionType;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ExecutionIntent;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.IntentStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.AutomaticAction;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaInstance;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaPolicyVersion;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Plans durable execution intents from authoritative SLA and immutable policy evidence. */
public final class ApprovalSlaExecutionPlanner {

    private static final int SEQUENCE_STRIDE = 128;
    private static final int MAX_CONFIGURED_ATTEMPTS = 100;

    private final int maxAttempts;
    private final Supplier<UUID> identifiers;

    public ApprovalSlaExecutionPlanner(int maxAttempts, Supplier<UUID> identifiers) {
        if (maxAttempts < 1 || maxAttempts > MAX_CONFIGURED_ATTEMPTS) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 100");
        }
        this.maxAttempts = maxAttempts;
        this.identifiers = Objects.requireNonNull(
            identifiers,
            "identifiers must not be null"
        );
    }

    public List<ExecutionIntent> plan(SlaInstance instance, SlaPolicyVersion policy) {
        Objects.requireNonNull(instance, "instance must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        validateSnapshot(instance, policy);
        if (instance.status() != SlaStatus.ACTIVE) {
            return List.of();
        }

        List<ExecutionIntent> intents = new ArrayList<>();
        planReminders(instance, policy, intents);
        intents.add(intent(
            instance,
            policy,
            ActionType.OVERDUE,
            1,
            instance.overdueAt(),
            Map.of()
        ));
        if (policy.escalationTargetType() != null) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("escalationTargetType", policy.escalationTargetType().name());
            if (policy.escalationTarget() != null) {
                evidence.put("escalationTarget", policy.escalationTarget());
            }
            intents.add(intent(
                instance,
                policy,
                ActionType.ESCALATION,
                1,
                instance.overdueAt(),
                evidence
            ));
        }
        if (policy.automaticAction() != AutomaticAction.NONE) {
            intents.add(intent(
                instance,
                policy,
                ActionType.AUTOMATIC_ACTION,
                1,
                instance.overdueAt(),
                Map.of("automaticAction", policy.automaticAction().name())
            ));
        }
        return List.copyOf(intents);
    }

    private void planReminders(
        SlaInstance instance,
        SlaPolicyVersion policy,
        List<ExecutionIntent> intents
    ) {
        Instant scheduledAt = instance.nextReminderAt();
        if (scheduledAt == null || policy.maximumReminderCount() < 1) {
            return;
        }
        Duration repeat = policy.repeatReminderInterval();
        for (int reminderNumber = 1;
             reminderNumber <= policy.maximumReminderCount();
             reminderNumber++) {
            if (scheduledAt.isAfter(instance.dueAt())) {
                return;
            }
            intents.add(intent(
                instance,
                policy,
                ActionType.REMINDER,
                reminderNumber,
                scheduledAt,
                Map.of("reminderNumber", reminderNumber)
            ));
            if (repeat == null || repeat.isZero()) {
                return;
            }
            scheduledAt = scheduledAt.plus(repeat);
        }
    }

    private ExecutionIntent intent(
        SlaInstance instance,
        SlaPolicyVersion policy,
        ActionType actionType,
        int ordinal,
        Instant scheduledAt,
        Map<String, Object> actionEvidence
    ) {
        int actionSequence = actionSequence(instance.version(), ordinal);
        Map<String, Object> payload = payload(
            instance,
            policy,
            actionType,
            actionSequence,
            actionEvidence
        );
        Instant createdAt = instance.updatedAt();
        return new ExecutionIntent(
            Objects.requireNonNull(identifiers.get(), "generated intentId must not be null"),
            instance.tenantId(),
            instance.slaInstanceId(),
            instance.approvalInstanceId(),
            instance.taskId(),
            instance.collaborationParticipantId(),
            instance.policyId(),
            instance.policyVersion(),
            instance.calendarId(),
            instance.calendarVersion(),
            null,
            actionType,
            actionSequence,
            scheduledAt,
            scheduledAt,
            IntentStatus.READY,
            null,
            null,
            0,
            maxAttempts,
            scheduledAt,
            idempotencyKey(instance, actionType, actionSequence),
            payload,
            instance.responsibleUserId(),
            instance.requestId(),
            instance.traceId(),
            1,
            createdAt,
            createdAt,
            null,
            null,
            null,
            null,
            null
        );
    }

    private static Map<String, Object> payload(
        SlaInstance instance,
        SlaPolicyVersion policy,
        ActionType actionType,
        int actionSequence,
        Map<String, Object> actionEvidence
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("actionType", actionType.name());
        payload.put("actionSequence", actionSequence);
        payload.put("slaInstanceId", instance.slaInstanceId().toString());
        payload.put("approvalInstanceId", instance.approvalInstanceId().toString());
        if (instance.taskId() != null) {
            payload.put("taskId", instance.taskId().toString());
        }
        if (instance.collaborationParticipantId() != null) {
            payload.put(
                "collaborationParticipantId",
                instance.collaborationParticipantId().toString()
            );
        }
        payload.put("policyId", policy.policyId().toString());
        payload.put("policyVersion", policy.policyVersion());
        payload.put("policyContentHash", policy.contentHash());
        if (policy.calendarId() != null) {
            payload.put("calendarId", policy.calendarId().toString());
            payload.put("calendarVersion", policy.calendarVersion());
        }
        payload.put("startedAt", instance.startedAt().toString());
        payload.put("dueAt", instance.dueAt().toString());
        payload.put("overdueAt", instance.overdueAt().toString());
        payload.put("timeZone", instance.timeZone());
        payload.putAll(actionEvidence);
        return Map.copyOf(payload);
    }

    private static void validateSnapshot(SlaInstance instance, SlaPolicyVersion policy) {
        if (!policy.immutable()) {
            throw new IllegalArgumentException(
                "execution planning requires an immutable policy snapshot"
            );
        }
        if (!instance.tenantId().equals(policy.tenantId())
            || !instance.policyId().equals(policy.policyId())
            || instance.policyVersion() != policy.policyVersion()) {
            throw new IllegalArgumentException("SLA and policy snapshot identities do not match");
        }
        if (!Objects.equals(instance.calendarId(), policy.calendarId())
            || !Objects.equals(instance.calendarVersion(), policy.calendarVersion())) {
            throw new IllegalArgumentException("SLA and calendar snapshot bindings do not match");
        }
    }

    private static int actionSequence(long slaVersion, int ordinal) {
        if (ordinal < 1 || ordinal >= SEQUENCE_STRIDE) {
            throw new IllegalArgumentException("action ordinal is outside the sequence stride");
        }
        long generation = Math.subtractExact(slaVersion, 1L);
        long value = Math.addExact(
            Math.multiplyExact(generation, (long) SEQUENCE_STRIDE),
            ordinal
        );
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("SLA version exceeds execution sequence capacity");
        }
        return Math.toIntExact(value);
    }

    private static String idempotencyKey(
        SlaInstance instance,
        ActionType actionType,
        int actionSequence
    ) {
        return "sla:" + instance.slaInstanceId()
            + ":" + actionType.name().toLowerCase(java.util.Locale.ROOT)
            + ":" + actionSequence;
    }
}
