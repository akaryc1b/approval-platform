package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalSlaLagCalculator.Lag;
import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.CalendarSnapshot;
import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.DayOverride;
import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.DurationMode;
import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.WorkingInterval;

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

/** Shared dependency-free checks: normal JUnit wrapper and local Java 21 run execute the same code. */
public final class ApprovalSlaLagCalculatorChecks {
    private ApprovalSlaLagCalculatorChecks() { }

    public static int run() {
        var calculator = new ApprovalSlaLagCalculator(new ApprovalWorkingTimeCalculator());
        var weekly = new EnumMap<DayOfWeek, List<WorkingInterval>>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) if (day.getValue() <= 5) {
            weekly.put(day, List.of(interval("09:00", "12:00"), interval("13:00", "17:00")));
        }
        var work = calendar("Asia/Shanghai", weekly, Map.of());
        Instant friday = Instant.parse("2026-09-18T08:00:00Z");
        Instant monday = Instant.parse("2026-09-21T02:00:00Z");
        expect(calculator.calculate(DurationMode.NATURAL_TIME, null, friday, monday), Duration.ofHours(66), null);
        expect(calculator.calculate(DurationMode.WORKING_TIME, work, friday, monday), Duration.ofHours(66), Duration.ofHours(2));
        expect(calculator.calculate(DurationMode.WORKING_TIME, work,
            Instant.parse("2026-09-21T03:30:00Z"), Instant.parse("2026-09-21T05:30:00Z")),
            Duration.ofHours(2), Duration.ofHours(1));
        var holiday = calendar("Asia/Shanghai", weekly, Map.of(LocalDate.parse("2026-09-21"), DayOverride.holiday()));
        expect(calculator.calculate(DurationMode.WORKING_TIME, holiday, friday, monday.plus(Duration.ofDays(1))),
            Duration.ofHours(90), Duration.ofHours(2));
        var makeUpDay = calendar("Asia/Shanghai", weekly, Map.of(LocalDate.parse("2026-09-19"),
            DayOverride.workingDay(List.of(interval("09:00", "11:00")))));
        expect(calculator.calculate(DurationMode.WORKING_TIME, makeUpDay, friday, monday),
            Duration.ofHours(66), Duration.ofHours(4));
        expect(calculator.calculate(DurationMode.WORKING_TIME, work,
            friday.plus(Duration.ofHours(1)), monday.minus(Duration.ofHours(1))),
            Duration.ofHours(64), Duration.ZERO);
        var overnight = calendar("UTC", Map.of(DayOfWeek.MONDAY, List.of(interval("22:00", "06:00"))),
            Map.of(LocalDate.parse("2026-09-22"), DayOverride.holiday()));
        expect(calculator.calculate(DurationMode.WORKING_TIME, overnight,
            Instant.parse("2026-09-21T23:00:00Z"), Instant.parse("2026-09-22T04:00:00Z")),
            Duration.ofHours(5), Duration.ofHours(5));
        var spring = calendar("America/New_York", Map.of(DayOfWeek.SUNDAY, List.of(interval("01:00", "04:00"))), Map.of());
        expect(calculator.calculate(DurationMode.WORKING_TIME, spring,
            Instant.parse("2025-03-09T06:00:00Z"), Instant.parse("2025-03-09T08:00:00Z")),
            Duration.ofHours(2), Duration.ofHours(2));
        var fall = calendar("America/New_York", Map.of(DayOfWeek.SUNDAY, List.of(interval("01:00", "03:00"))), Map.of());
        expect(calculator.calculate(DurationMode.WORKING_TIME, fall,
            Instant.parse("2025-11-02T05:00:00Z"), Instant.parse("2025-11-02T08:00:00Z")),
            Duration.ofHours(3), Duration.ofHours(3));
        expect(calculator.calculate(DurationMode.WORKING_TIME, work, monday, monday), Duration.ZERO, Duration.ZERO);
        expect(calculator.calculate(DurationMode.NATURAL_TIME, null, friday,
            friday.plus(Duration.ofDays(366))), Duration.ofDays(366), null);
        expect(calculator.calculate(DurationMode.WORKING_TIME, work, monday, monday.plusNanos(1)),
            Duration.ofNanos(1), Duration.ofNanos(1));
        rejects(() -> calculator.calculate(DurationMode.WORKING_TIME, work, monday, friday));
        rejects(() -> calculator.calculate(DurationMode.NATURAL_TIME, null, friday,
            friday.plus(Duration.ofDays(366)).plusNanos(1)));
        rejects(() -> calculator.calculate(DurationMode.WORKING_TIME, work, friday,
            friday.plus(Duration.ofDays(366)).plusNanos(1)));
        rejects(() -> calculator.calculate(DurationMode.WORKING_TIME, null, friday, monday));
        rejects(() -> calculator.calculate(DurationMode.NATURAL_TIME, work, friday, monday));
        rejects(() -> calculator.calculate(null, work, friday, monday));
        rejects(() -> new Lag(Duration.ZERO, Duration.ofNanos(1)));
        rejects(() -> new Lag(Duration.ofSeconds(1), Duration.ofNanos(-1)));
        // A newer calendar is a different observation input, not a replacement for the pinned snapshot.
        expect(calculator.calculate(DurationMode.WORKING_TIME, work, friday, monday), Duration.ofHours(66), Duration.ofHours(2));
        return 21;
    }

    public static void main(String[] args) {
        System.out.println("SLA_LAG_CALCULATOR_CHECKS_PASSED=" + run());
    }

    private static CalendarSnapshot calendar(String zone, Map<DayOfWeek, List<WorkingInterval>> weekly,
        Map<LocalDate, DayOverride> overrides) {
        return CalendarSnapshot.of(UUID.fromString("91000000-0000-0000-0000-000000000001"),
            "calendar-fixture", 1, zone, weekly, overrides, "a".repeat(64));
    }
    private static WorkingInterval interval(String start, String end) {
        return new WorkingInterval(LocalTime.parse(start), LocalTime.parse(end));
    }
    private static void expect(Lag actual, Duration natural, Duration working) {
        if (!actual.natural().equals(natural) || !Objects.equals(actual.working(), working)) {
            throw new AssertionError("unexpected calendar-aware completion lag: " + actual);
        }
    }
    private static void rejects(Runnable call) {
        try { call.run(); } catch (IllegalArgumentException | NullPointerException expected) { return; }
        throw new AssertionError("invalid timing was accepted");
    }
}
