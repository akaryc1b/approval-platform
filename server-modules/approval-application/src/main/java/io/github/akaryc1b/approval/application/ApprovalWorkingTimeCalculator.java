package io.github.akaryc1b.approval.application;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Bounded, deterministic work-calendar calculations based on Java Time zone rules. */
public final class ApprovalWorkingTimeCalculator {

    private static final int MAX_INTERVALS_PER_DAY = 16;
    private static final int MAX_OVERRIDES = 20_000;
    private static final int MAX_SCAN_DAYS = 36_600;
    private static final Duration MAX_CALCULATION_DURATION = Duration.ofDays(36_600);

    public boolean isWorkingInstant(CalendarSnapshot calendar, Instant instant) {
        CalendarSnapshot snapshot = Objects.requireNonNull(calendar, "calendar must not be null");
        Instant value = Objects.requireNonNull(instant, "instant must not be null");
        LocalDate date = value.atZone(snapshot.zoneId()).toLocalDate();
        for (ResolvedInterval interval : resolvedIntervals(snapshot, date)) {
            if (!value.isBefore(interval.start()) && value.isBefore(interval.end())) {
                return true;
            }
        }
        return false;
    }

    public Instant nextWorkingInstant(CalendarSnapshot calendar, Instant instant) {
        CalendarSnapshot snapshot = Objects.requireNonNull(calendar, "calendar must not be null");
        Instant candidate = Objects.requireNonNull(instant, "instant must not be null");
        LocalDate firstDate = candidate.atZone(snapshot.zoneId()).toLocalDate();
        for (int day = 0; day <= MAX_SCAN_DAYS; day++) {
            LocalDate date = firstDate.plusDays(day);
            for (ResolvedInterval interval : resolvedIntervals(snapshot, date)) {
                Instant normalized = candidate.isAfter(interval.start()) ? candidate : interval.start();
                if (normalized.isBefore(interval.end())) {
                    return normalized;
                }
            }
            candidate = startOfDay(snapshot.zoneId(), date.plusDays(1));
        }
        throw new WorkingTimeUnavailableException(
            "no working interval is available within the bounded calendar horizon"
        );
    }

    public Instant addWorkingDuration(
        CalendarSnapshot calendar,
        Instant startedAt,
        Duration workingDuration
    ) {
        CalendarSnapshot snapshot = Objects.requireNonNull(calendar, "calendar must not be null");
        Instant start = Objects.requireNonNull(startedAt, "startedAt must not be null");
        Duration remaining = validateDuration(workingDuration, "workingDuration");
        if (remaining.isZero()) {
            return isWorkingInstant(snapshot, start) ? start : nextWorkingInstant(snapshot, start);
        }
        Instant cursor = nextWorkingInstant(snapshot, start);
        for (int day = 0; day <= MAX_SCAN_DAYS; day++) {
            LocalDate date = cursor.atZone(snapshot.zoneId()).toLocalDate();
            boolean consumedInterval = false;
            for (ResolvedInterval interval : resolvedIntervals(snapshot, date)) {
                Instant intervalCursor = cursor.isAfter(interval.start()) ? cursor : interval.start();
                if (!intervalCursor.isBefore(interval.end())) {
                    continue;
                }
                consumedInterval = true;
                Duration available = Duration.between(intervalCursor, interval.end());
                if (remaining.compareTo(available) <= 0) {
                    return intervalCursor.plus(remaining);
                }
                remaining = remaining.minus(available);
                cursor = interval.end();
            }
            LocalDate nextDate = date.plusDays(1);
            cursor = startOfDay(snapshot.zoneId(), nextDate);
            if (!consumedInterval || remaining.isPositive()) {
                cursor = nextWorkingInstant(snapshot, cursor);
            }
        }
        throw new WorkingTimeUnavailableException(
            "working duration exceeds the bounded calendar horizon"
        );
    }

    public Duration workingDurationBetween(
        CalendarSnapshot calendar,
        Instant startedAt,
        Instant endedAt
    ) {
        CalendarSnapshot snapshot = Objects.requireNonNull(calendar, "calendar must not be null");
        Instant start = Objects.requireNonNull(startedAt, "startedAt must not be null");
        Instant end = Objects.requireNonNull(endedAt, "endedAt must not be null");
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("endedAt must not be before startedAt");
        }
        if (start.equals(end)) {
            return Duration.ZERO;
        }
        if (Duration.between(start, end).compareTo(MAX_CALCULATION_DURATION) > 0) {
            throw new IllegalArgumentException("calculation range exceeds the bounded horizon");
        }
        LocalDate firstDate = start.atZone(snapshot.zoneId()).toLocalDate();
        LocalDate lastDate = end.atZone(snapshot.zoneId()).toLocalDate();
        long days = lastDate.toEpochDay() - firstDate.toEpochDay();
        if (days > MAX_SCAN_DAYS) {
            throw new IllegalArgumentException("calculation range exceeds the bounded horizon");
        }
        Duration total = Duration.ZERO;
        for (long day = 0; day <= days; day++) {
            LocalDate date = firstDate.plusDays(day);
            for (ResolvedInterval interval : resolvedIntervals(snapshot, date)) {
                Instant overlapStart = start.isAfter(interval.start()) ? start : interval.start();
                Instant overlapEnd = end.isBefore(interval.end()) ? end : interval.end();
                if (overlapStart.isBefore(overlapEnd)) {
                    total = total.plus(Duration.between(overlapStart, overlapEnd));
                }
            }
        }
        return total;
    }

    private static Duration validateDuration(Duration duration, String name) {
        Duration value = Objects.requireNonNull(duration, name + " must not be null");
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        if (value.compareTo(MAX_CALCULATION_DURATION) > 0) {
            throw new IllegalArgumentException(name + " exceeds the bounded horizon");
        }
        return value;
    }

    private static List<ResolvedInterval> resolvedIntervals(
        CalendarSnapshot calendar,
        LocalDate date
    ) {
        DayOverride override = calendar.overrides().get(date);
        List<WorkingInterval> intervals = override == null
            ? calendar.weeklySchedule().getOrDefault(date.getDayOfWeek(), List.of())
            : override.working() ? override.intervals() : List.of();
        if (intervals.isEmpty()) {
            return List.of();
        }
        List<ResolvedInterval> result = new ArrayList<>(intervals.size());
        for (WorkingInterval interval : intervals) {
            Instant start = resolveBoundary(calendar.zoneId(), date, interval.start(), true);
            Instant end = resolveBoundary(calendar.zoneId(), date, interval.end(), false);
            if (!start.isBefore(end)) {
                throw new WorkingTimeUnavailableException(
                    "calendar interval resolves to a non-positive instant range"
                );
            }
            result.add(new ResolvedInterval(start, end));
        }
        return List.copyOf(result);
    }

    private static Instant startOfDay(ZoneId zoneId, LocalDate date) {
        return date.atStartOfDay(zoneId).toInstant();
    }

    private static Instant resolveBoundary(
        ZoneId zoneId,
        LocalDate date,
        LocalTime time,
        boolean startBoundary
    ) {
        LocalDateTime localDateTime = LocalDateTime.of(date, time);
        ZoneRules rules = zoneId.getRules();
        List<ZoneOffset> offsets = rules.getValidOffsets(localDateTime);
        if (offsets.size() == 1) {
            return ZonedDateTime.ofStrict(localDateTime, offsets.getFirst(), zoneId).toInstant();
        }
        if (offsets.size() == 2) {
            ZoneOffset selected = startBoundary ? offsets.getFirst() : offsets.getLast();
            return ZonedDateTime.ofStrict(localDateTime, selected, zoneId).toInstant();
        }
        ZoneOffsetTransition transition = rules.getTransition(localDateTime);
        if (transition == null || !transition.isGap()) {
            throw new WorkingTimeUnavailableException("unable to resolve calendar time-zone boundary");
        }
        return transition.getInstant();
    }

    public record CalendarSnapshot(
        UUID calendarId,
        String tenantId,
        int calendarVersion,
        ZoneId zoneId,
        Map<DayOfWeek, List<WorkingInterval>> weeklySchedule,
        Map<LocalDate, DayOverride> overrides,
        String contentHash
    ) {
        public CalendarSnapshot {
            calendarId = Objects.requireNonNull(calendarId, "calendarId must not be null");
            tenantId = requireText(tenantId, "tenantId");
            if (calendarVersion < 1) {
                throw new IllegalArgumentException("calendarVersion must be positive");
            }
            zoneId = Objects.requireNonNull(zoneId, "zoneId must not be null");
            contentHash = requireText(contentHash, "contentHash");
            weeklySchedule = normalizeWeeklySchedule(weeklySchedule);
            overrides = normalizeOverrides(overrides);
        }

        public static CalendarSnapshot of(
            UUID calendarId,
            String tenantId,
            int calendarVersion,
            String zoneId,
            Map<DayOfWeek, List<WorkingInterval>> weeklySchedule,
            Map<LocalDate, DayOverride> overrides,
            String contentHash
        ) {
            try {
                return new CalendarSnapshot(
                    calendarId,
                    tenantId,
                    calendarVersion,
                    ZoneId.of(requireText(zoneId, "zoneId")),
                    weeklySchedule,
                    overrides,
                    contentHash
                );
            } catch (DateTimeException exception) {
                throw new IllegalArgumentException("zoneId must be a valid IANA time zone", exception);
            }
        }
    }

    public record WorkingInterval(LocalTime start, LocalTime end) {
        public WorkingInterval {
            start = Objects.requireNonNull(start, "start must not be null");
            end = Objects.requireNonNull(end, "end must not be null");
            if (!start.isBefore(end)) {
                throw new IllegalArgumentException("working interval start must be before end");
            }
        }
    }

    public record DayOverride(boolean working, List<WorkingInterval> intervals) {
        public DayOverride {
            intervals = normalizeIntervals(intervals, "override intervals");
            if (working && intervals.isEmpty()) {
                throw new IllegalArgumentException("working override requires at least one interval");
            }
            if (!working && !intervals.isEmpty()) {
                throw new IllegalArgumentException("non-working override must not contain intervals");
            }
        }

        public static DayOverride holiday() {
            return new DayOverride(false, List.of());
        }

        public static DayOverride workingDay(List<WorkingInterval> intervals) {
            return new DayOverride(true, intervals);
        }
    }

    public static final class WorkingTimeUnavailableException extends RuntimeException {
        public WorkingTimeUnavailableException(String message) {
            super(message);
        }
    }

    private record ResolvedInterval(Instant start, Instant end) {
    }

    private static Map<DayOfWeek, List<WorkingInterval>> normalizeWeeklySchedule(
        Map<DayOfWeek, List<WorkingInterval>> schedule
    ) {
        Map<DayOfWeek, List<WorkingInterval>> normalized = new EnumMap<>(DayOfWeek.class);
        if (schedule == null) {
            return Map.of();
        }
        for (Map.Entry<DayOfWeek, List<WorkingInterval>> entry : schedule.entrySet()) {
            DayOfWeek day = Objects.requireNonNull(entry.getKey(), "schedule day must not be null");
            List<WorkingInterval> intervals = normalizeIntervals(
                entry.getValue(),
                "weekly intervals"
            );
            if (!intervals.isEmpty()) {
                normalized.put(day, intervals);
            }
        }
        return Map.copyOf(normalized);
    }

    private static Map<LocalDate, DayOverride> normalizeOverrides(
        Map<LocalDate, DayOverride> source
    ) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        if (source.size() > MAX_OVERRIDES) {
            throw new IllegalArgumentException("calendar override count exceeds the limit");
        }
        Map<LocalDate, DayOverride> normalized = new LinkedHashMap<>();
        source.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> normalized.put(
                Objects.requireNonNull(entry.getKey(), "override date must not be null"),
                Objects.requireNonNull(entry.getValue(), "override must not be null")
            ));
        return Map.copyOf(normalized);
    }

    private static List<WorkingInterval> normalizeIntervals(
        List<WorkingInterval> source,
        String name
    ) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        if (source.size() > MAX_INTERVALS_PER_DAY) {
            throw new IllegalArgumentException(name + " count exceeds the limit");
        }
        List<WorkingInterval> normalized = source.stream()
            .map(interval -> Objects.requireNonNull(interval, name + " must not contain null"))
            .sorted(Comparator.comparing(WorkingInterval::start))
            .toList();
        for (int index = 1; index < normalized.size(); index++) {
            WorkingInterval previous = normalized.get(index - 1);
            WorkingInterval current = normalized.get(index);
            if (current.start().isBefore(previous.end())) {
                throw new IllegalArgumentException(name + " must not overlap");
            }
        }
        return List.copyOf(normalized);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
