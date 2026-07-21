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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Bounded work-calendar calculations based exclusively on Java Time zone rules. */
public final class ApprovalWorkingTimeCalculator {

    private static final int MAX_INTERVALS_PER_DAY = 16;
    private static final int MAX_OVERRIDES = 20_000;
    private static final int MAX_SCAN_DAYS = 36_600;
    private static final Duration MAX_CALCULATION_DURATION = Duration.ofDays(MAX_SCAN_DAYS);

    public boolean isWorkingInstant(CalendarSnapshot calendar, Instant instant) {
        CalendarSnapshot snapshot = Objects.requireNonNull(calendar, "calendar must not be null");
        Instant value = Objects.requireNonNull(instant, "instant must not be null");
        LocalDate localDate = value.atZone(snapshot.zoneId()).toLocalDate();
        for (ResolvedInterval interval : intervalsTouchingDate(snapshot, localDate)) {
            if (!value.isBefore(interval.start()) && value.isBefore(interval.end())) {
                return true;
            }
        }
        return false;
    }

    public Instant nextWorkingInstant(CalendarSnapshot calendar, Instant instant) {
        CalendarSnapshot snapshot = Objects.requireNonNull(calendar, "calendar must not be null");
        Instant candidate = Objects.requireNonNull(instant, "instant must not be null");
        LocalDate firstAnchor = candidate.atZone(snapshot.zoneId()).toLocalDate().minusDays(1);
        for (int scanned = 0; scanned <= MAX_SCAN_DAYS + 1; scanned++) {
            LocalDate anchorDate = firstAnchor.plusDays(scanned);
            validateAdjacentResolvedIntervals(snapshot, anchorDate);
            for (ResolvedInterval interval : intervalsStartingOn(snapshot, anchorDate)) {
                Instant normalized = candidate.isAfter(interval.start()) ? candidate : interval.start();
                if (normalized.isBefore(interval.end())) {
                    return normalized;
                }
            }
        }
        throw unavailable("no working interval is available within the bounded calendar horizon");
    }

    public Instant addDuration(
        CalendarSnapshot calendar,
        Instant startedAt,
        Duration duration,
        DurationMode mode
    ) {
        Objects.requireNonNull(mode, "mode must not be null");
        Duration value = validateDuration(duration, "duration");
        Instant start = Objects.requireNonNull(startedAt, "startedAt must not be null");
        if (mode == DurationMode.NATURAL_TIME) {
            return start.plus(value);
        }
        return addWorkingDuration(calendar, start, value);
    }

    public Duration elapsedDuration(
        CalendarSnapshot calendar,
        Instant startedAt,
        Instant endedAt,
        DurationMode mode
    ) {
        Objects.requireNonNull(mode, "mode must not be null");
        Instant start = Objects.requireNonNull(startedAt, "startedAt must not be null");
        Instant end = Objects.requireNonNull(endedAt, "endedAt must not be null");
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("endedAt must not be before startedAt");
        }
        if (mode == DurationMode.NATURAL_TIME) {
            Duration value = Duration.between(start, end);
            validateDuration(value, "elapsed duration");
            return value;
        }
        return workingDurationBetween(calendar, start, end);
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
        LocalDate firstAnchor = cursor.atZone(snapshot.zoneId()).toLocalDate().minusDays(1);
        for (int scanned = 0; scanned <= MAX_SCAN_DAYS + 1; scanned++) {
            LocalDate anchorDate = firstAnchor.plusDays(scanned);
            validateAdjacentResolvedIntervals(snapshot, anchorDate);
            for (ResolvedInterval interval : intervalsStartingOn(snapshot, anchorDate)) {
                Instant intervalCursor = cursor.isAfter(interval.start()) ? cursor : interval.start();
                if (!intervalCursor.isBefore(interval.end())) {
                    continue;
                }
                Duration available = Duration.between(intervalCursor, interval.end());
                if (remaining.compareTo(available) <= 0) {
                    return intervalCursor.plus(remaining);
                }
                remaining = remaining.minus(available);
                cursor = interval.end();
            }
        }
        throw unavailable("working duration exceeds the bounded calendar horizon");
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
        Duration wallRange = Duration.between(start, end);
        if (wallRange.compareTo(MAX_CALCULATION_DURATION.plusDays(2)) > 0) {
            throw new IllegalArgumentException("calculation range exceeds the bounded horizon");
        }

        LocalDate firstAnchor = start.atZone(snapshot.zoneId()).toLocalDate().minusDays(1);
        LocalDate lastAnchor = end.atZone(snapshot.zoneId()).toLocalDate();
        long anchorDays = lastAnchor.toEpochDay() - firstAnchor.toEpochDay();
        if (anchorDays > MAX_SCAN_DAYS + 1L) {
            throw new IllegalArgumentException("calculation range exceeds the bounded horizon");
        }

        Duration total = Duration.ZERO;
        for (long scanned = 0; scanned <= anchorDays; scanned++) {
            LocalDate anchorDate = firstAnchor.plusDays(scanned);
            validateAdjacentResolvedIntervals(snapshot, anchorDate);
            for (ResolvedInterval interval : intervalsStartingOn(snapshot, anchorDate)) {
                Instant overlapStart = start.isAfter(interval.start()) ? start : interval.start();
                Instant overlapEnd = end.isBefore(interval.end()) ? end : interval.end();
                if (overlapStart.isBefore(overlapEnd)) {
                    total = total.plus(Duration.between(overlapStart, overlapEnd));
                }
            }
        }
        return total;
    }

    private static List<ResolvedInterval> intervalsTouchingDate(
        CalendarSnapshot calendar,
        LocalDate localDate
    ) {
        List<ResolvedInterval> intervals = new ArrayList<>(MAX_INTERVALS_PER_DAY * 2);
        intervals.addAll(intervalsStartingOn(calendar, localDate.minusDays(1)));
        intervals.addAll(intervalsStartingOn(calendar, localDate));
        intervals.sort(Comparator.comparing(ResolvedInterval::start));
        rejectResolvedOverlap(intervals);
        return List.copyOf(intervals);
    }

    private static List<ResolvedInterval> intervalsStartingOn(
        CalendarSnapshot calendar,
        LocalDate anchorDate
    ) {
        List<WorkingInterval> localIntervals = effectiveIntervals(calendar, anchorDate);
        if (localIntervals.isEmpty()) {
            return List.of();
        }
        List<ResolvedInterval> result = new ArrayList<>(localIntervals.size());
        for (WorkingInterval interval : localIntervals) {
            LocalDate endDate = interval.crossesMidnight() ? anchorDate.plusDays(1) : anchorDate;
            Instant start = resolveBoundary(calendar.zoneId(), anchorDate, interval.start(), true);
            Instant end = resolveBoundary(calendar.zoneId(), endDate, interval.end(), false);
            if (!start.isBefore(end)) {
                throw unavailable("calendar interval resolves to a non-positive instant range");
            }
            result.add(new ResolvedInterval(start, end));
        }
        result.sort(Comparator.comparing(ResolvedInterval::start));
        rejectResolvedOverlap(result);
        return List.copyOf(result);
    }

    private static List<WorkingInterval> effectiveIntervals(
        CalendarSnapshot calendar,
        LocalDate date
    ) {
        DayOverride override = calendar.overrides().get(date);
        if (override == null) {
            return calendar.weeklySchedule().getOrDefault(date.getDayOfWeek(), List.of());
        }
        return override.working() ? override.intervals() : List.of();
    }

    private static void validateAdjacentResolvedIntervals(
        CalendarSnapshot calendar,
        LocalDate anchorDate
    ) {
        List<ResolvedInterval> adjacent = new ArrayList<>(MAX_INTERVALS_PER_DAY * 2);
        adjacent.addAll(intervalsStartingOn(calendar, anchorDate.minusDays(1)));
        adjacent.addAll(intervalsStartingOn(calendar, anchorDate));
        adjacent.sort(Comparator.comparing(ResolvedInterval::start));
        rejectResolvedOverlap(adjacent);
    }

    private static void rejectResolvedOverlap(List<ResolvedInterval> intervals) {
        for (int index = 1; index < intervals.size(); index++) {
            ResolvedInterval previous = intervals.get(index - 1);
            ResolvedInterval current = intervals.get(index);
            if (current.start().isBefore(previous.end())) {
                throw unavailable("calendar intervals overlap after time-zone resolution");
            }
        }
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
            throw unavailable("unable to resolve calendar time-zone boundary");
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
            validateCalendarRelationships(weeklySchedule, overrides);
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
            if (start.equals(end)) {
                throw new IllegalArgumentException("working interval must not be zero length");
            }
        }

        public boolean crossesMidnight() {
            return start.isAfter(end);
        }

        private int startMinute() {
            return start.getHour() * 60 + start.getMinute();
        }

        private int endMinuteFromAnchor() {
            int endMinute = end.getHour() * 60 + end.getMinute();
            return crossesMidnight() ? endMinute + 1440 : endMinute;
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

    public enum DurationMode {
        NATURAL_TIME,
        WORKING_TIME
    }

    public static final class WorkingTimeUnavailableException extends RuntimeException {
        public WorkingTimeUnavailableException(String message) {
            super(message);
        }
    }

    private record ResolvedInterval(Instant start, Instant end) {
    }

    private record LocalMinuteRange(long start, long end) {
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
            .sorted(Comparator.comparingInt(WorkingInterval::startMinute))
            .toList();
        for (int index = 1; index < normalized.size(); index++) {
            WorkingInterval previous = normalized.get(index - 1);
            WorkingInterval current = normalized.get(index);
            if (current.startMinute() < previous.endMinuteFromAnchor()) {
                throw new IllegalArgumentException(name + " must not overlap");
            }
        }
        return List.copyOf(normalized);
    }

    private static void validateCalendarRelationships(
        Map<DayOfWeek, List<WorkingInterval>> weekly,
        Map<LocalDate, DayOverride> overrides
    ) {
        List<LocalMinuteRange> weeklyRanges = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            long dayStart = (long) (day.getValue() - 1) * 1440;
            for (WorkingInterval interval : weekly.getOrDefault(day, List.of())) {
                weeklyRanges.add(new LocalMinuteRange(
                    dayStart + interval.startMinute(),
                    dayStart + interval.endMinuteFromAnchor()
                ));
            }
        }
        for (WorkingInterval monday : weekly.getOrDefault(DayOfWeek.MONDAY, List.of())) {
            weeklyRanges.add(new LocalMinuteRange(
                10080L + monday.startMinute(),
                10080L + monday.endMinuteFromAnchor()
            ));
        }
        rejectLocalOverlap(weeklyRanges, "weekly intervals must not overlap across midnight");

        Set<LocalDate> affectedDates = new LinkedHashSet<>();
        for (LocalDate date : overrides.keySet()) {
            affectedDates.add(date.minusDays(1));
            affectedDates.add(date);
            affectedDates.add(date.plusDays(1));
        }
        for (LocalDate date : affectedDates) {
            List<LocalMinuteRange> adjacent = new ArrayList<>();
            appendLocalRanges(adjacent, effectiveLocalIntervals(weekly, overrides, date.minusDays(1)), 0);
            appendLocalRanges(adjacent, effectiveLocalIntervals(weekly, overrides, date), 1440);
            rejectLocalOverlap(adjacent, "date override conflicts with an adjacent working interval");
        }
    }

    private static List<WorkingInterval> effectiveLocalIntervals(
        Map<DayOfWeek, List<WorkingInterval>> weekly,
        Map<LocalDate, DayOverride> overrides,
        LocalDate date
    ) {
        DayOverride override = overrides.get(date);
        if (override == null) {
            return weekly.getOrDefault(date.getDayOfWeek(), List.of());
        }
        return override.working() ? override.intervals() : List.of();
    }

    private static void appendLocalRanges(
        List<LocalMinuteRange> target,
        List<WorkingInterval> intervals,
        long dayOffset
    ) {
        for (WorkingInterval interval : intervals) {
            target.add(new LocalMinuteRange(
                dayOffset + interval.startMinute(),
                dayOffset + interval.endMinuteFromAnchor()
            ));
        }
    }

    private static void rejectLocalOverlap(List<LocalMinuteRange> ranges, String message) {
        ranges.sort(Comparator.comparingLong(LocalMinuteRange::start));
        for (int index = 1; index < ranges.size(); index++) {
            LocalMinuteRange previous = ranges.get(index - 1);
            LocalMinuteRange current = ranges.get(index);
            if (current.start() < previous.end()) {
                throw new IllegalArgumentException(message);
            }
        }
    }

    private static WorkingTimeUnavailableException unavailable(String message) {
        return new WorkingTimeUnavailableException(message);
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
