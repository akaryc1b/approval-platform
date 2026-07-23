package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.CalendarSnapshot;
import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.DayOverride;
import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.DurationMode;
import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.WorkingInterval;
import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.WorkingTimeUnavailableException;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalWorkingTimeCalculatorTest {

    private static final ApprovalWorkingTimeCalculator CALCULATOR =
        new ApprovalWorkingTimeCalculator();

    @Test
    void normalizesLunchBreakWeekendAndMultipleIntervals() {
        CalendarSnapshot calendar = weekdayCalendar("Asia/Shanghai");

        assertTrue(CALCULATOR.isWorkingInstant(calendar, instant("2026-07-20T10:00", "Asia/Shanghai")));
        assertFalse(CALCULATOR.isWorkingInstant(calendar, instant("2026-07-20T12:30", "Asia/Shanghai")));
        assertFalse(CALCULATOR.isWorkingInstant(calendar, instant("2026-07-19T10:00", "Asia/Shanghai")));
        assertEquals(
            instant("2026-07-20T13:00", "Asia/Shanghai"),
            CALCULATOR.nextWorkingInstant(calendar, instant("2026-07-20T12:30", "Asia/Shanghai"))
        );
        assertEquals(
            instant("2026-07-20T14:30", "Asia/Shanghai"),
            CALCULATOR.addWorkingDuration(
                calendar,
                instant("2026-07-20T11:30", "Asia/Shanghai"),
                Duration.ofHours(2)
            )
        );
        assertEquals(
            Duration.ofHours(2),
            CALCULATOR.workingDurationBetween(
                calendar,
                instant("2026-07-20T11:00", "Asia/Shanghai"),
                instant("2026-07-20T14:00", "Asia/Shanghai")
            )
        );
    }

    @Test
    void appliesHolidayCompensatoryDayAndDateOverride() {
        Map<LocalDate, DayOverride> overrides = Map.of(
            LocalDate.of(2026, 7, 20), DayOverride.holiday(),
            LocalDate.of(2026, 7, 25), DayOverride.workingDay(List.of(interval(8, 30, 12, 30))),
            LocalDate.of(2026, 7, 21), DayOverride.workingDay(List.of(interval(10, 0, 11, 0)))
        );
        CalendarSnapshot calendar = snapshot("Asia/Shanghai", weeklyWeekdays(), overrides);

        assertEquals(
            instant("2026-07-21T10:00", "Asia/Shanghai"),
            CALCULATOR.nextWorkingInstant(calendar, instant("2026-07-20T09:00", "Asia/Shanghai"))
        );
        assertTrue(CALCULATOR.isWorkingInstant(calendar, instant("2026-07-25T09:00", "Asia/Shanghai")));
        assertFalse(CALCULATOR.isWorkingInstant(calendar, instant("2026-07-21T13:30", "Asia/Shanghai")));
    }

    @Test
    void crossesMidnightWithoutDoubleCounting() {
        CalendarSnapshot calendar = snapshot(
            "Asia/Shanghai",
            Map.of(DayOfWeek.MONDAY, List.of(interval(22, 0, 2, 0))),
            Map.of()
        );

        assertTrue(CALCULATOR.isWorkingInstant(calendar, instant("2026-07-21T01:00", "Asia/Shanghai")));
        assertEquals(
            instant("2026-07-21T01:00", "Asia/Shanghai"),
            CALCULATOR.addWorkingDuration(
                calendar,
                instant("2026-07-20T23:00", "Asia/Shanghai"),
                Duration.ofHours(2)
            )
        );
        assertEquals(
            Duration.ofHours(4),
            CALCULATOR.workingDurationBetween(
                calendar,
                instant("2026-07-20T22:00", "Asia/Shanghai"),
                instant("2026-07-21T02:00", "Asia/Shanghai")
            )
        );
    }

    @Test
    void rejectsCrossMidnightOverlapWithFollowingDay() {
        assertThrows(IllegalArgumentException.class, () -> snapshot(
            "Asia/Shanghai",
            Map.of(
                DayOfWeek.MONDAY, List.of(interval(22, 0, 2, 0)),
                DayOfWeek.TUESDAY, List.of(interval(1, 0, 3, 0))
            ),
            Map.of()
        ));
    }

    @Test
    void respectsLeapDayAndCrossYear() {
        CalendarSnapshot berlin = weekdayCalendar("Europe/Berlin");
        assertEquals(
            instant("2024-02-29T10:00", "Europe/Berlin"),
            CALCULATOR.addWorkingDuration(
                berlin,
                instant("2024-02-29T09:00", "Europe/Berlin"),
                Duration.ofHours(1)
            )
        );
        assertEquals(
            instant("2027-01-01T10:00", "Europe/Berlin"),
            CALCULATOR.addWorkingDuration(
                berlin,
                instant("2026-12-31T17:00", "Europe/Berlin"),
                Duration.ofHours(2)
            )
        );
    }

    @Test
    void resolvesNewYorkSpringForwardUsingZoneRules() {
        CalendarSnapshot calendar = snapshot(
            "America/New_York",
            Map.of(DayOfWeek.SUNDAY, List.of(interval(1, 0, 4, 0))),
            Map.of()
        );
        assertEquals(
            Duration.ofHours(2),
            CALCULATOR.workingDurationBetween(
                calendar,
                instant("2026-03-08T01:00", "America/New_York"),
                instant("2026-03-08T04:00", "America/New_York")
            )
        );
    }

    @Test
    void resolvesNewYorkAndBerlinFallBackUsingZoneRules() {
        CalendarSnapshot newYork = snapshot(
            "America/New_York",
            Map.of(DayOfWeek.SUNDAY, List.of(interval(0, 30, 2, 30))),
            Map.of()
        );
        assertEquals(
            Duration.ofHours(3),
            CALCULATOR.workingDurationBetween(
                newYork,
                instant("2026-11-01T00:30", "America/New_York"),
                instant("2026-11-01T02:30", "America/New_York")
            )
        );

        CalendarSnapshot berlin = snapshot(
            "Europe/Berlin",
            Map.of(DayOfWeek.SUNDAY, List.of(interval(1, 30, 3, 30))),
            Map.of()
        );
        assertEquals(
            Duration.ofHours(3),
            CALCULATOR.workingDurationBetween(
                berlin,
                instant("2026-10-25T01:30", "Europe/Berlin"),
                instant("2026-10-25T03:30", "Europe/Berlin")
            )
        );
    }

    @Test
    void naturalTimeDoesNotRequireCalendar() {
        Instant start = Instant.parse("2026-07-21T00:00:00Z");
        assertEquals(
            start.plus(Duration.ofHours(7)),
            CALCULATOR.addDuration(null, start, Duration.ofHours(7), DurationMode.NATURAL_TIME)
        );
        assertEquals(
            Duration.ofHours(7),
            CALCULATOR.elapsedDuration(
                null,
                start,
                start.plus(Duration.ofHours(7)),
                DurationMode.NATURAL_TIME
            )
        );
    }

    @Test
    void failsClosedWhenNoFutureWorkExistsAndRejectsInvalidZoneOrOverlap() {
        CalendarSnapshot empty = snapshot("Asia/Shanghai", Map.of(), Map.of());
        assertThrows(
            WorkingTimeUnavailableException.class,
            () -> CALCULATOR.nextWorkingInstant(empty, Instant.parse("2026-01-01T00:00:00Z"))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> CalendarSnapshot.of(
                UUID.randomUUID(),
                "tenant-a",
                1,
                "Not/A_Zone",
                Map.of(),
                Map.of(),
                "0".repeat(64)
            )
        );
        assertThrows(IllegalArgumentException.class, () -> snapshot(
            "Asia/Shanghai",
            Map.of(DayOfWeek.MONDAY, List.of(interval(9, 0, 12, 0), interval(11, 0, 13, 0))),
            Map.of()
        ));
    }

    @Test
    void hardBoundsRejectUnboundedDurationAndRange() {
        CalendarSnapshot calendar = weekdayCalendar("Asia/Shanghai");
        assertThrows(
            IllegalArgumentException.class,
            () -> CALCULATOR.addWorkingDuration(
                calendar,
                Instant.parse("2026-01-01T00:00:00Z"),
                Duration.ofDays(36_601)
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> CALCULATOR.workingDurationBetween(
                calendar,
                Instant.parse("1900-01-01T00:00:00Z"),
                Instant.parse("2101-01-01T00:00:00Z")
            )
        );
    }

    private static CalendarSnapshot weekdayCalendar(String zoneId) {
        return snapshot(zoneId, weeklyWeekdays(), Map.of());
    }

    private static Map<DayOfWeek, List<WorkingInterval>> weeklyWeekdays() {
        Map<DayOfWeek, List<WorkingInterval>> schedule = new EnumMap<>(DayOfWeek.class);
        List<WorkingInterval> intervals = List.of(interval(9, 0, 12, 0), interval(13, 0, 18, 0));
        schedule.put(DayOfWeek.MONDAY, intervals);
        schedule.put(DayOfWeek.TUESDAY, intervals);
        schedule.put(DayOfWeek.WEDNESDAY, intervals);
        schedule.put(DayOfWeek.THURSDAY, intervals);
        schedule.put(DayOfWeek.FRIDAY, intervals);
        return Map.copyOf(schedule);
    }

    private static CalendarSnapshot snapshot(
        String zoneId,
        Map<DayOfWeek, List<WorkingInterval>> schedule,
        Map<LocalDate, DayOverride> overrides
    ) {
        return CalendarSnapshot.of(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "tenant-a",
            1,
            zoneId,
            schedule,
            overrides,
            "0".repeat(64)
        );
    }

    private static WorkingInterval interval(
        int startHour,
        int startMinute,
        int endHour,
        int endMinute
    ) {
        return new WorkingInterval(
            LocalTime.of(startHour, startMinute),
            LocalTime.of(endHour, endMinute)
        );
    }

    private static Instant instant(String localDateTime, String zoneId) {
        return LocalDateTime.parse(localDateTime).atZone(ZoneId.of(zoneId)).toInstant();
    }
}
