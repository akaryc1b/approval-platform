package io.github.akaryc1b.approval.observability;

import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.CalendarSnapshot;
import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.WorkingInterval;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ActionType;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.ExecutionIntent;
import io.github.akaryc1b.approval.application.port.ApprovalSlaExecutionStore.IntentStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.CalendarStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.CalendarVersion;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaPolicyVersion;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.PolicyStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaDurationMode;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaTargetType;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.AutomaticAction;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Fixture identities are never accepted as runtime metric labels. */
public final class SlaLagFixtures {
    public static final String TENANT = "sla-lag-fixture";
    public static final UUID POLICY = id(1), CALENDAR = id(2), INSTANCE = id(3), TASK = id(4), SLA = id(5);
    public static final Instant START = Instant.parse("2026-09-18T07:00:00Z");
    public static final Instant DEADLINE = Instant.parse("2026-09-18T08:00:00Z");
    public static final Instant FINISH = Instant.parse("2026-09-21T02:00:00Z");
    private SlaLagFixtures() { }

    public static UUID id(int number) { return new UUID(0x9100000000000000L, number); }

    public static ExecutionIntent intent(IntentStatus status, boolean working) {
        return intent(status, working, "task", false, ActionType.OVERDUE, FINISH, id(6));
    }
    public static ExecutionIntent intent(IntentStatus status, boolean working, String target, boolean replay,
        ActionType action, Instant finish, UUID id) {
        return new ExecutionIntent(id, TENANT, SLA, INSTANCE, "process".equals(target) ? null : TASK,
            "collaboration".equals(target) ? SlaLagFixtures.id(7) : null, POLICY, 1,
            working ? CALENDAR : null, working ? 1 : null, replay ? SlaLagFixtures.id(8) : null,
            action, 101, DEADLINE, DEADLINE, status,
            status == IntentStatus.CLAIMED ? "fixture-worker" : null,
            status == IntentStatus.CLAIMED ? finish.plusSeconds(60) : null,
            status == IntentStatus.SUCCEEDED ? 1 : status == IntentStatus.DEAD ? 3 : 0, 3, DEADLINE,
            "lag-" + id, Map.of("dueAt", "untrusted-payload-time", "private", "never-in-metric"),
            "owner-a", "request-lag", "trace-lag", status == IntentStatus.READY ? 1 : 2,
            START, status == IntentStatus.READY ? START : finish,
            status == IntentStatus.SUCCEEDED ? finish : null,
            status == IntentStatus.DEAD ? finish : null,
            status == IntentStatus.CANCELLED ? finish : null, null, null);
    }

    public static SlaPolicyVersion policy(boolean working) {
        return policy(working, TENANT, 1, true);
    }
    public static SlaPolicyVersion policy(boolean working, String tenant, int version, boolean immutable) {
        return new SlaPolicyVersion(POLICY, tenant, version, "purchasePayment", 1, "managerApproval",
            SlaTargetType.TASK, working ? SlaDurationMode.WORKING_TIME : SlaDurationMode.NATURAL_TIME,
            Duration.ofHours(1), working ? CALENDAR : null, working ? 1 : null,
            null, null, 0, Duration.ZERO, null, null, AutomaticAction.NONE, true, "c".repeat(64),
            immutable ? PolicyStatus.ACTIVE : PolicyStatus.DRAFT, immutable,
            immutable ? "publisher" : null, immutable ? START : null, START, START);
    }
    public static CalendarVersion calendar() { return calendar(TENANT, 1, true); }
    public static CalendarVersion calendar(String tenant, int version, boolean immutable) {
        var weekly = new EnumMap<DayOfWeek, List<WorkingInterval>>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) if (day.getValue() <= 5) {
            weekly.put(day, List.of(new WorkingInterval(LocalTime.of(9, 0), LocalTime.of(17, 0))));
        }
        var snapshot = CalendarSnapshot.of(CALENDAR, tenant, version, "Asia/Shanghai", weekly, Map.of(), "d".repeat(64));
        return new CalendarVersion(CALENDAR, tenant, version, null, null, snapshot,
            immutable ? CalendarStatus.PUBLISHED : CalendarStatus.DRAFT, immutable,
            immutable ? "publisher" : null, immutable ? START : null, START, START);
    }
}
