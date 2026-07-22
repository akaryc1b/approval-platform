package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ActionType;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ExecutionIntent;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.AutomaticAction;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.EscalationTargetType;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.PolicyStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaDurationMode;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaInstance;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaPolicyVersion;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaTargetType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalSlaExecutionPlannerTest {

    private static final String TENANT_ID = "tenant-planner";
    private static final UUID POLICY_ID = UUID.fromString(
        "71000000-0000-0000-0000-000000000001"
    );
    private static final UUID APPROVAL_INSTANCE_ID = UUID.fromString(
        "71000000-0000-0000-0000-000000000002"
    );
    private static final UUID TASK_ID = UUID.fromString(
        "71000000-0000-0000-0000-000000000003"
    );
    private static final UUID SLA_INSTANCE_ID = UUID.fromString(
        "71000000-0000-0000-0000-000000000004"
    );
    private static final Instant STARTED_AT = Instant.parse("2026-07-22T08:00:00Z");
    private static final Instant DUE_AT = Instant.parse("2026-07-22T10:00:00Z");
    private static final Instant REMINDER_AT = Instant.parse("2026-07-22T09:00:00Z");
    private static final Instant OVERDUE_AT = Instant.parse("2026-07-22T10:10:00Z");

    @Test
    void plansReminderOverdueEscalationAndAutomaticActionFromAuthoritativeEvidence() {
        ApprovalSlaExecutionPlanner planner = new ApprovalSlaExecutionPlanner(
            5,
            identifiers()
        );

        List<ExecutionIntent> intents = planner.plan(instance(1), fullPolicy(true));

        assertEquals(6, intents.size());
        assertEquals(
            List.of(
                ActionType.REMINDER,
                ActionType.REMINDER,
                ActionType.REMINDER,
                ActionType.OVERDUE,
                ActionType.ESCALATION,
                ActionType.AUTOMATIC_ACTION
            ),
            intents.stream().map(ExecutionIntent::actionType).toList()
        );
        assertEquals(
            List.of(
                REMINDER_AT,
                REMINDER_AT.plus(Duration.ofMinutes(15)),
                REMINDER_AT.plus(Duration.ofMinutes(30))
            ),
            intents.stream()
                .filter(intent -> intent.actionType() == ActionType.REMINDER)
                .map(ExecutionIntent::scheduledAt)
                .toList()
        );
        assertEquals(OVERDUE_AT, intents.get(3).scheduledAt());
        assertEquals(OVERDUE_AT, intents.get(4).scheduledAt());
        assertEquals(OVERDUE_AT, intents.get(5).scheduledAt());
        assertEquals(List.of(1, 2, 3), intents.subList(0, 3).stream()
            .map(ExecutionIntent::actionSequence)
            .toList());
        assertTrue(intents.stream().allMatch(intent -> intent.maxAttempts() == 5));
        assertTrue(intents.stream().allMatch(intent -> intent.availableAt().equals(
            intent.scheduledAt()
        )));
        assertTrue(intents.stream().allMatch(intent -> intent.payload().get("dueAt").equals(
            DUE_AT.toString()
        )));
        assertTrue(intents.stream().allMatch(intent -> intent.payload().get(
            "policyContentHash"
        ).equals("a".repeat(64))));
        assertFalse(intents.get(4).payload().containsKey("responsibleUserId"));
        assertEquals("USER", intents.get(4).payload().get("escalationTargetType"));
        assertEquals("manager-review", intents.get(4).payload().get("escalationTarget"));
        assertEquals(
            "AUTO_TRANSFER",
            intents.get(5).payload().get("automaticAction")
        );
    }

    @Test
    void resumedSlaUsesNewAuthoritativeTimesAndANewVersionedIdempotencySequence() {
        ApprovalSlaExecutionPlanner planner = new ApprovalSlaExecutionPlanner(
            3,
            identifiers()
        );
        SlaInstance original = instance(1);
        SlaInstance resumed = new SlaInstance(
            original.slaInstanceId(),
            original.tenantId(),
            original.approvalInstanceId(),
            original.taskId(),
            original.collaborationParticipantId(),
            original.definitionKey(),
            original.taskDefinitionKey(),
            original.targetType(),
            original.policyId(),
            original.policyVersion(),
            original.calendarId(),
            original.calendarVersion(),
            original.timeZone(),
            original.responsibleUserId(),
            original.originalResponsibleUserId(),
            original.startedAt(),
            DUE_AT.plus(Duration.ofHours(2)),
            REMINDER_AT.plus(Duration.ofHours(2)),
            OVERDUE_AT.plus(Duration.ofHours(2)),
            null,
            null,
            Duration.ofMinutes(30),
            null,
            null,
            SlaStatus.ACTIVE,
            original.lastActionSequence(),
            original.requestId(),
            original.traceId(),
            3,
            original.createdAt(),
            STARTED_AT.plus(Duration.ofMinutes(30))
        );

        ExecutionIntent originalReminder = planner.plan(original, fullPolicy(true)).getFirst();
        ExecutionIntent resumedReminder = planner.plan(resumed, fullPolicy(true)).getFirst();

        assertEquals(1, originalReminder.actionSequence());
        assertEquals(257, resumedReminder.actionSequence());
        assertEquals(REMINDER_AT.plus(Duration.ofHours(2)), resumedReminder.scheduledAt());
        assertEquals(
            DUE_AT.plus(Duration.ofHours(2)).toString(),
            resumedReminder.payload().get("dueAt")
        );
        assertNotEquals(originalReminder.idempotencyKey(), resumedReminder.idempotencyKey());
    }

    @Test
    void omitsOptionalActionsAndDoesNotScheduleRemindersAfterDue() {
        ApprovalSlaExecutionPlanner planner = new ApprovalSlaExecutionPlanner(
            2,
            identifiers()
        );
        SlaPolicyVersion policy = new SlaPolicyVersion(
            POLICY_ID,
            TENANT_ID,
            1,
            "purchasePayment",
            1,
            "managerApproval",
            SlaTargetType.TASK,
            SlaDurationMode.NATURAL_TIME,
            Duration.ofHours(2),
            null,
            null,
            Duration.ofMinutes(30),
            Duration.ofHours(2),
            5,
            Duration.ofMinutes(10),
            null,
            null,
            AutomaticAction.NONE,
            true,
            "b".repeat(64),
            PolicyStatus.ACTIVE,
            true,
            "publisher",
            STARTED_AT.minus(Duration.ofDays(1)),
            STARTED_AT.minus(Duration.ofDays(2)),
            STARTED_AT.minus(Duration.ofDays(1))
        );

        List<ExecutionIntent> intents = planner.plan(instance(1), policy);

        assertEquals(2, intents.size());
        assertEquals(ActionType.REMINDER, intents.getFirst().actionType());
        assertEquals(ActionType.OVERDUE, intents.getLast().actionType());
    }

    @Test
    void failsClosedForDraftOrMismatchedPolicyEvidence() {
        ApprovalSlaExecutionPlanner planner = new ApprovalSlaExecutionPlanner(
            3,
            identifiers()
        );

        IllegalArgumentException draftFailure = assertThrows(
            IllegalArgumentException.class,
            () -> planner.plan(instance(1), fullPolicy(false))
        );
        assertTrue(draftFailure.getMessage().contains("immutable"));

        SlaPolicyVersion mismatched = new SlaPolicyVersion(
            UUID.fromString("71000000-0000-0000-0000-000000000099"),
            TENANT_ID,
            1,
            "purchasePayment",
            1,
            "managerApproval",
            SlaTargetType.TASK,
            SlaDurationMode.NATURAL_TIME,
            Duration.ofHours(2),
            null,
            null,
            Duration.ofMinutes(30),
            Duration.ofMinutes(15),
            3,
            Duration.ofMinutes(10),
            EscalationTargetType.USER,
            "manager-review",
            AutomaticAction.AUTO_TRANSFER,
            true,
            "a".repeat(64),
            PolicyStatus.ACTIVE,
            true,
            "publisher",
            STARTED_AT.minus(Duration.ofDays(1)),
            STARTED_AT.minus(Duration.ofDays(2)),
            STARTED_AT.minus(Duration.ofDays(1))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> planner.plan(instance(1), mismatched)
        );
    }

    private static SlaInstance instance(long version) {
        return new SlaInstance(
            SLA_INSTANCE_ID,
            TENANT_ID,
            APPROVAL_INSTANCE_ID,
            TASK_ID,
            null,
            "purchasePayment",
            "managerApproval",
            SlaTargetType.TASK,
            POLICY_ID,
            1,
            null,
            null,
            "UTC",
            "owner-a",
            "owner-a",
            STARTED_AT,
            DUE_AT,
            REMINDER_AT,
            OVERDUE_AT,
            null,
            null,
            Duration.ZERO,
            null,
            null,
            SlaStatus.ACTIVE,
            0,
            "request-planner",
            "trace-planner",
            version,
            STARTED_AT,
            STARTED_AT
        );
    }

    private static SlaPolicyVersion fullPolicy(boolean immutable) {
        return new SlaPolicyVersion(
            POLICY_ID,
            TENANT_ID,
            1,
            "purchasePayment",
            1,
            "managerApproval",
            SlaTargetType.TASK,
            SlaDurationMode.NATURAL_TIME,
            Duration.ofHours(2),
            null,
            null,
            Duration.ofMinutes(30),
            Duration.ofMinutes(15),
            3,
            Duration.ofMinutes(10),
            EscalationTargetType.USER,
            "manager-review",
            AutomaticAction.AUTO_TRANSFER,
            true,
            "a".repeat(64),
            immutable ? PolicyStatus.ACTIVE : PolicyStatus.DRAFT,
            immutable,
            immutable ? "publisher" : null,
            immutable ? STARTED_AT.minus(Duration.ofDays(1)) : null,
            STARTED_AT.minus(Duration.ofDays(2)),
            STARTED_AT.minus(Duration.ofDays(1))
        );
    }

    private static Supplier<UUID> identifiers() {
        AtomicInteger counter = new AtomicInteger();
        return () -> UUID.fromString(String.format(
            "72000000-0000-0000-0000-%012d",
            counter.incrementAndGet()
        ));
    }
}
