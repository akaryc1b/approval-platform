package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalSlaLifecycleTimingCalculator.Measured;
import io.github.akaryc1b.approval.application.ApprovalSlaLifecycleTimingCalculator.MeasuredPolicyDuration;
import io.github.akaryc1b.approval.application.ApprovalSlaLifecycleTimingCalculator.PolicyDurationUnavailable;
import io.github.akaryc1b.approval.application.ApprovalSlaLifecycleTimingCalculator.Result;
import io.github.akaryc1b.approval.application.ApprovalSlaLifecycleTimingCalculator.Unavailable;
import io.github.akaryc1b.approval.application.ApprovalSlaLifecycleTimingCalculator.UnavailableReason;
import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.CalendarSnapshot;
import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.DayOverride;
import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.WorkingInterval;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.AutomaticAction;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.CalendarStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.CalendarVersion;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.PolicyStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaDurationMode;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaInstance;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaPolicyVersion;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaTargetType;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaTerminalReason;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Same executable assertions for normal Maven/JUnit and dependency-free Java 21 verification. */
public final class ApprovalSlaLifecycleTimingCalculatorChecks {
    private static final ApprovalSlaLifecycleTimingCalculator CALCULATOR =
        new ApprovalSlaLifecycleTimingCalculator(new ApprovalWorkingTimeCalculator());
    private static final UUID CALENDAR_ID = UUID.fromString("92000000-0000-0000-0000-000000000001");
    private static final UUID POLICY_ID = UUID.fromString("92000000-0000-0000-0000-000000000002");
    private static final UUID INSTANCE_ID = UUID.fromString("92000000-0000-0000-0000-000000000003");
    private static final UUID TASK_ID = UUID.fromString("92000000-0000-0000-0000-000000000004");
    private static final UUID PARTICIPANT_ID = UUID.fromString("92000000-0000-0000-0000-000000000005");
    private static final Instant CREATED_AT = Instant.parse("2025-01-01T00:00:00Z");

    private ApprovalSlaLifecycleTimingCalculatorChecks() { }

    static int lifecycleAndPolicySemantics() {
        Fixture fixture = new Fixture();
        for (SlaTargetType target : SlaTargetType.values()) {
            fixture.target = target;
            measured(fixture.calculate(), Duration.ofHours(66), Duration.ofHours(2), null);
        }
        fixture.mode = SlaDurationMode.NATURAL_TIME;
        measured(fixture.calculate(), Duration.ofHours(66), null, Duration.ofHours(66));
        fixture.naturalTimePauses = true;
        measured(fixture.calculate(), Duration.ofHours(66), null, null);
        // Zero cumulative pause time cannot prove an unpaused lifecycle: terminalization clears
        // a currently open pause. No row-version heuristic or caller-supplied history repairs it.
        fixture.accumulatedPause = Duration.ofHours(1);
        measured(fixture.calculate(), Duration.ofHours(66), null, null);
        fixture.mode = SlaDurationMode.WORKING_TIME;
        measured(fixture.calculate(), Duration.ofHours(66), Duration.ofHours(2), null);
        fixture.naturalTimePauses = false;
        fixture.mode = SlaDurationMode.NATURAL_TIME;
        measured(fixture.calculate(), Duration.ofHours(66), null, Duration.ofHours(66));
        fixture.end = fixture.start;
        fixture.accumulatedPause = Duration.ZERO;
        measured(fixture.calculate(), Duration.ZERO, null, Duration.ZERO);
        fixture.mode = SlaDurationMode.WORKING_TIME;
        measured(fixture.calculate(), Duration.ZERO, Duration.ZERO, null);
        return 10;
    }

    static int calendarSemantics() {
        Fixture fixture = new Fixture();
        fixture.start = Instant.parse("2026-09-18T17:00:00Z");
        fixture.end = Instant.parse("2026-09-21T09:00:00Z");
        measured(fixture.calculate(), Duration.ofHours(64), Duration.ZERO, null);
        fixture.start = Instant.parse("2026-09-18T16:00:00Z");
        fixture.end = Instant.parse("2026-09-22T10:00:00Z");
        fixture.overrides = Map.of(LocalDate.parse("2026-09-21"), DayOverride.holiday());
        measured(fixture.calculate(), Duration.ofHours(90), Duration.ofHours(2), null);
        fixture.end = Instant.parse("2026-09-21T10:00:00Z");
        fixture.overrides = Map.of(LocalDate.parse("2026-09-19"),
            DayOverride.workingDay(List.of(interval("09:00", "11:00"))));
        measured(fixture.calculate(), Duration.ofHours(66), Duration.ofHours(4), null);
        fixture.weekly = Map.of(DayOfWeek.MONDAY, List.of(interval("22:00", "06:00")));
        fixture.overrides = Map.of(LocalDate.parse("2026-09-22"), DayOverride.holiday());
        fixture.start = Instant.parse("2026-09-21T23:00:00Z");
        fixture.end = Instant.parse("2026-09-22T04:00:00Z");
        measured(fixture.calculate(), Duration.ofHours(5), Duration.ofHours(5), null);
        fixture.instanceZone = "America/New_York";
        fixture.calendarZone = fixture.instanceZone;
        fixture.weekly = Map.of(DayOfWeek.SUNDAY, List.of(interval("01:00", "04:00")));
        fixture.overrides = Map.of();
        fixture.start = Instant.parse("2025-03-09T06:00:00Z");
        fixture.end = Instant.parse("2025-03-09T08:00:00Z");
        measured(fixture.calculate(), Duration.ofHours(2), Duration.ofHours(2), null);
        fixture.weekly = Map.of(DayOfWeek.SUNDAY, List.of(interval("01:00", "03:00")));
        fixture.start = Instant.parse("2025-11-02T05:00:00Z");
        fixture.end = Instant.parse("2025-11-02T08:00:00Z");
        measured(fixture.calculate(), Duration.ofHours(3), Duration.ofHours(3), null);
        fixture.start = Instant.parse("2025-11-02T06:00:00Z");
        fixture.end = fixture.start.plusNanos(1);
        measured(fixture.calculate(), Duration.ofNanos(1), Duration.ofNanos(1), null);
        fixture.weekly = Map.of(DayOfWeek.SUNDAY, List.of(interval("02:10", "02:50")));
        fixture.start = Instant.parse("2025-03-09T06:00:00Z");
        fixture.end = Instant.parse("2025-03-09T08:00:00Z");
        unavailable(fixture.calculate(), UnavailableReason.CALENDAR_CALCULATION_FAILED);
        return 8;
    }

    static int rejectsUnavailableOrInvalidEvidence() {
        Fixture fixture = new Fixture();
        unavailable(CALCULATOR.calculate(null, null, null), UnavailableReason.INSTANCE_MISSING);
        unavailable(CALCULATOR.calculate(fixture.instance(), null, null), UnavailableReason.POLICY_MISSING);
        unavailable(CALCULATOR.calculate(fixture.instance(), fixture.policy(), null), UnavailableReason.CALENDAR_MISSING);
        fixture.status = SlaStatus.ACTIVE;
        unavailable(fixture.calculate(), UnavailableReason.NOT_TERMINAL);
        fixture.status = SlaStatus.PAUSED;
        unavailable(fixture.calculate(), UnavailableReason.NOT_TERMINAL);
        fixture.status = SlaStatus.TERMINAL;
        fixture.end = fixture.start.minusNanos(1);
        unavailable(fixture.calculate(), UnavailableReason.INVALID_INTERVAL);
        fixture.end = fixture.start.plus(Duration.ofDays(366)).plusNanos(1);
        // Range rejection occurs even with missing versions; no calendar calculation is attempted.
        unavailable(CALCULATOR.calculate(fixture.instance(), null, null), UnavailableReason.RANGE_EXCEEDED);
        fixture.mode = SlaDurationMode.NATURAL_TIME;
        unavailable(fixture.calculate(), UnavailableReason.RANGE_EXCEEDED);
        fixture.end = fixture.start.plus(Duration.ofDays(366));
        measured(fixture.calculate(), Duration.ofDays(366), null, Duration.ofDays(366));
        fixture.mode = SlaDurationMode.WORKING_TIME;
        fixture.end = fixture.start.plusSeconds(1);
        fixture.policyImmutable = false;
        unavailable(fixture.calculate(), UnavailableReason.POLICY_UNPUBLISHED);
        fixture.policyImmutable = true;
        fixture.policyStatus = PolicyStatus.DRAFT;
        unavailable(fixture.calculate(), UnavailableReason.POLICY_UNPUBLISHED);
        fixture.policyStatus = PolicyStatus.PUBLISHED;
        fixture.calendarImmutable = false;
        unavailable(fixture.calculate(), UnavailableReason.CALENDAR_UNPUBLISHED);
        fixture.calendarImmutable = true;
        fixture.calendarStatus = CalendarStatus.DRAFT;
        unavailable(fixture.calculate(), UnavailableReason.CALENDAR_UNPUBLISHED);
        return 13;
    }

    static int rejectsVersionAndIdentityDrift() {
        Fixture fixture = new Fixture();
        fixture.policyVersion = 2;
        unavailable(fixture.calculate(), UnavailableReason.POLICY_MISMATCH);
        fixture.policyVersion = 1;
        fixture.policyTenant = "other-tenant";
        unavailable(fixture.calculate(), UnavailableReason.POLICY_MISMATCH);
        fixture.policyTenant = fixture.tenant;
        fixture.policyId = UUID.fromString("92000000-0000-0000-0000-000000000099");
        unavailable(fixture.calculate(), UnavailableReason.POLICY_MISMATCH);
        fixture.policyId = POLICY_ID;
        fixture.policyDefinition = "other-definition";
        unavailable(fixture.calculate(), UnavailableReason.POLICY_MISMATCH);
        fixture.policyDefinition = "lifecycle-fixture";
        fixture.policyTaskDefinition = "other-task";
        unavailable(fixture.calculate(), UnavailableReason.POLICY_MISMATCH);
        fixture.policyTaskDefinition = null;
        fixture.policyTarget = SlaTargetType.COLLABORATION_PARTICIPANT;
        unavailable(fixture.calculate(), UnavailableReason.POLICY_MISMATCH);
        fixture.policyTarget = SlaTargetType.TASK;
        fixture.target = SlaTargetType.PROCESS;
        unavailable(fixture.calculate(), UnavailableReason.POLICY_MISMATCH);
        fixture.target = SlaTargetType.COLLABORATION_PARTICIPANT;
        measured(fixture.calculate(), Duration.ofHours(66), Duration.ofHours(2), null);
        fixture.policyCalendarVersion = 2;
        unavailable(fixture.calculate(), UnavailableReason.POLICY_MISMATCH);
        fixture.policyCalendarVersion = 1;
        fixture.policyCalendarId = fixture.policyId;
        unavailable(fixture.calculate(), UnavailableReason.POLICY_MISMATCH);
        fixture.policyCalendarId = CALENDAR_ID;
        fixture.calendarVersion = 2;
        unavailable(fixture.calculate(), UnavailableReason.CALENDAR_MISMATCH);
        fixture.calendarVersion = 1;
        fixture.calendarId = fixture.policyId;
        unavailable(fixture.calculate(), UnavailableReason.CALENDAR_MISMATCH);
        fixture.calendarId = CALENDAR_ID;
        fixture.calendarTenant = "other-tenant";
        unavailable(fixture.calculate(), UnavailableReason.CALENDAR_MISMATCH);
        fixture.calendarTenant = fixture.tenant;
        fixture.calendarZone = "Asia/Shanghai";
        unavailable(fixture.calculate(), UnavailableReason.CALENDAR_MISMATCH);
        fixture.calendarZone = "UTC";
        CalendarVersion extraneousCalendar = fixture.calendar();
        fixture.mode = SlaDurationMode.NATURAL_TIME;
        unavailable(CALCULATOR.calculate(fixture.instance(), fixture.policy(), extraneousCalendar),
            UnavailableReason.CALENDAR_MISMATCH);
        fixture.instanceZone = "Europe/London";
        unavailable(fixture.calculate(), UnavailableReason.CALENDAR_MISMATCH);
        return 16;
    }

    static int pinnedVersionsAndPureReplay() {
        Fixture fixture = new Fixture();
        SlaInstance instance = fixture.instance();
        SlaPolicyVersion policy = fixture.policy();
        CalendarVersion calendar = fixture.calendar();
        Result first = CALCULATOR.calculate(instance, policy, calendar);
        fixture.policyVersion = 2;
        fixture.calendarVersion = 2;
        fixture.weekly = Map.of();
        // A newer active snapshot cannot replace either exact pinned version.
        unavailable(CALCULATOR.calculate(instance, fixture.policy(), calendar), UnavailableReason.POLICY_MISMATCH);
        unavailable(CALCULATOR.calculate(instance, policy, fixture.calendar()), UnavailableReason.CALENDAR_MISMATCH);
        equals(first, CALCULATOR.calculate(instance, policy, calendar));
        equals(first, CALCULATOR.calculate(instance, policy, calendar));
        // Lifecycle observation does not alter deadlines or immutable inputs, even after a rejected input.
        equals(Instant.parse("2026-09-18T17:00:00Z"), instance.dueAt());
        equals(Duration.ZERO, instance.accumulatedPausedDuration());
        fixture = new Fixture();
        fixture.policyStatus = PolicyStatus.INACTIVE;
        fixture.calendarStatus = CalendarStatus.ARCHIVED;
        measured(fixture.calculate(), Duration.ofHours(66), Duration.ofHours(2), null);
        return 7;
    }

    static int validatesResultContracts() {
        rejects(() -> new Measured(Duration.ofSeconds(-1), null, PolicyDurationUnavailable.MISSING_PAUSE_HISTORY));
        rejects(() -> new Measured(Duration.ZERO, Duration.ofSeconds(1), PolicyDurationUnavailable.MISSING_PAUSE_HISTORY));
        rejects(() -> new Measured(Duration.ZERO, Duration.ofSeconds(-1), PolicyDurationUnavailable.MISSING_PAUSE_HISTORY));
        rejects(() -> new Measured(Duration.ZERO, null, new MeasuredPolicyDuration(Duration.ofSeconds(1))));
        rejects(() -> new Measured(Duration.ofDays(367), null, PolicyDurationUnavailable.MISSING_PAUSE_HISTORY));
        rejects(() -> new Measured(Duration.ZERO, null, null));
        rejects(() -> new MeasuredPolicyDuration(Duration.ofSeconds(-1)));
        rejects(() -> new MeasuredPolicyDuration(Duration.ofDays(367)));
        rejects(() -> new Unavailable(null));
        return 9;
    }

    static int usesLifecycleTimestampsForEveryTerminalOutcome() {
        Fixture fixture = new Fixture();
        fixture.rowCreatedAt = fixture.start.plusSeconds(300);
        fixture.rowUpdatedAt = fixture.end.plusSeconds(10);
        measured(fixture.calculate(), Duration.ofHours(66), Duration.ofHours(2), null);
        fixture.mode = SlaDurationMode.NATURAL_TIME;
        for (SlaTerminalReason reason : List.of(SlaTerminalReason.INSTANCE_WITHDRAWN,
            SlaTerminalReason.INSTANCE_REJECTED, SlaTerminalReason.TASK_CANCELED)) {
            fixture.target = reason == SlaTerminalReason.TASK_CANCELED ? SlaTargetType.TASK : SlaTargetType.PROCESS;
            fixture.terminalReason = reason;
            measured(fixture.calculate(), Duration.ofHours(66), null, Duration.ofHours(66));
        }
        return 4;
    }

    public static int run() {
        return lifecycleAndPolicySemantics() + calendarSemantics() + rejectsUnavailableOrInvalidEvidence()
            + rejectsVersionAndIdentityDrift() + pinnedVersionsAndPureReplay() + validatesResultContracts()
            + usesLifecycleTimestampsForEveryTerminalOutcome();
    }

    public static void main(String[] args) {
        System.out.println("SLA_LIFECYCLE_TIMING_CHECKS_PASSED=" + run());
    }

    private static void measured(Result result, Duration natural, Duration working, Duration policy) {
        if (!(result instanceof Measured value)) throw new AssertionError("expected measured result: " + result);
        equals(natural, value.naturalElapsed());
        equals(working, value.workingElapsed());
        equals(policy == null ? PolicyDurationUnavailable.MISSING_PAUSE_HISTORY
            : new MeasuredPolicyDuration(policy), value.policyDuration());
    }

    private static void unavailable(Result result, UnavailableReason reason) {
        equals(new Unavailable(reason), result);
    }

    private static void equals(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("expected " + expected + ", got " + actual);
    }

    private static void rejects(Runnable call) {
        try { call.run(); } catch (IllegalArgumentException | NullPointerException expected) { return; }
        throw new AssertionError("invalid result was accepted");
    }

    private static WorkingInterval interval(String start, String end) {
        return new WorkingInterval(LocalTime.parse(start), LocalTime.parse(end));
    }

    private static Map<DayOfWeek, List<WorkingInterval>> weekdays() {
        Map<DayOfWeek, List<WorkingInterval>> weekly = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) if (day.getValue() <= 5) {
            weekly.put(day, List.of(interval("09:00", "17:00")));
        }
        return weekly;
    }

    private static final class Fixture {
        private final String tenant = "lifecycle-fixture";
        private String policyTenant = tenant;
        private String calendarTenant = tenant;
        private UUID policyId = POLICY_ID;
        private UUID policyCalendarId = CALENDAR_ID;
        private UUID calendarId = CALENDAR_ID;
        private int policyVersion = 1;
        private int policyCalendarVersion = 1;
        private int calendarVersion = 1;
        private String policyDefinition = "lifecycle-fixture";
        private String policyTaskDefinition;
        private String instanceZone = "UTC";
        private String calendarZone = "UTC";
        private SlaTargetType target = SlaTargetType.TASK;
        private SlaTargetType policyTarget = SlaTargetType.PROCESS;
        private SlaDurationMode mode = SlaDurationMode.WORKING_TIME;
        private boolean naturalTimePauses;
        private boolean policyImmutable = true;
        private boolean calendarImmutable = true;
        private PolicyStatus policyStatus = PolicyStatus.PUBLISHED;
        private CalendarStatus calendarStatus = CalendarStatus.PUBLISHED;
        private SlaStatus status = SlaStatus.TERMINAL;
        private SlaTerminalReason terminalReason = SlaTerminalReason.INSTANCE_COMPLETED;
        private Instant start = Instant.parse("2026-09-18T16:00:00Z");
        private Instant end = Instant.parse("2026-09-21T10:00:00Z");
        private Instant rowCreatedAt;
        private Instant rowUpdatedAt;
        private Duration accumulatedPause = Duration.ZERO;
        private Map<DayOfWeek, List<WorkingInterval>> weekly = weekdays();
        private Map<LocalDate, DayOverride> overrides = Map.of();

        private Result calculate() {
            return CALCULATOR.calculate(instance(), policy(), mode == SlaDurationMode.WORKING_TIME ? calendar() : null);
        }

        private SlaInstance instance() {
            return new SlaInstance(INSTANCE_ID, tenant, INSTANCE_ID,
                target == SlaTargetType.PROCESS ? null : TASK_ID,
                target == SlaTargetType.COLLABORATION_PARTICIPANT ? PARTICIPANT_ID : null,
                "lifecycle-fixture", target == SlaTargetType.PROCESS ? null : "approval-task", target,
                POLICY_ID, 1, mode == SlaDurationMode.WORKING_TIME ? CALENDAR_ID : null,
                mode == SlaDurationMode.WORKING_TIME ? 1 : null, instanceZone, "owner", "owner",
                start, start.plusSeconds(3600), null, start.plusSeconds(3600),
                status == SlaStatus.PAUSED ? start : null, status == SlaStatus.PAUSED ? "pause-fixture" : null,
                accumulatedPause, status == SlaStatus.TERMINAL ? end : null,
                status == SlaStatus.TERMINAL ? terminalReason : null,
                status, 0, "lifecycle-request", null, 2,
                rowCreatedAt == null ? start : rowCreatedAt, rowUpdatedAt == null ? end : rowUpdatedAt);
        }

        private SlaPolicyVersion policy() {
            return new SlaPolicyVersion(policyId, policyTenant, policyVersion, policyDefinition,
                null, policyTaskDefinition, policyTarget, mode, Duration.ofHours(1),
                mode == SlaDurationMode.WORKING_TIME ? policyCalendarId : null,
                mode == SlaDurationMode.WORKING_TIME ? policyCalendarVersion : null,
                null, null, 0, Duration.ZERO, null, null, AutomaticAction.NONE, naturalTimePauses,
                "a".repeat(64), policyStatus, policyImmutable, "publisher", CREATED_AT, CREATED_AT, CREATED_AT);
        }

        private CalendarVersion calendar() {
            return new CalendarVersion(calendarId, calendarTenant, calendarVersion, null, null,
                CalendarSnapshot.of(calendarId, calendarTenant, calendarVersion, calendarZone, weekly, overrides,
                    "b".repeat(64)), calendarStatus, calendarImmutable, "publisher", CREATED_AT, CREATED_AT, CREATED_AT);
        }
    }
}
