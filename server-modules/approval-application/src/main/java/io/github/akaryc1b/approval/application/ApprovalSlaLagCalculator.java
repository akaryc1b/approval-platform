package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.CalendarSnapshot;
import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.DurationMode;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Observation only: the existing scheduled deadline remains authoritative and is never recalculated. */
public final class ApprovalSlaLagCalculator {

    public static final Duration MAX_OBSERVATION_RANGE = Duration.ofDays(366);
    private final ApprovalWorkingTimeCalculator workingTime;

    public ApprovalSlaLagCalculator(ApprovalWorkingTimeCalculator workingTime) {
        this.workingTime = Objects.requireNonNull(workingTime, "workingTime");
    }

    public Lag calculate(DurationMode mode, CalendarSnapshot calendar, Instant scheduledAt, Instant completedAt) {
        Objects.requireNonNull(mode, "mode");
        Duration natural = Duration.between(Objects.requireNonNull(scheduledAt, "scheduledAt"),
            Objects.requireNonNull(completedAt, "completedAt"));
        if (natural.isNegative() || natural.compareTo(MAX_OBSERVATION_RANGE) > 0) {
            throw new IllegalArgumentException("SLA observation range is invalid");
        }
        if (mode == DurationMode.NATURAL_TIME) {
            if (calendar != null) throw new IllegalArgumentException("natural timing must not bind a calendar");
            return new Lag(natural, null);
        }
        return new Lag(natural, workingTime.elapsedDuration(Objects.requireNonNull(calendar, "calendar"),
            scheduledAt, completedAt, DurationMode.WORKING_TIME));
    }

    /** Null working duration means not applicable, not an invented zero-work sample. */
    public record Lag(Duration natural, Duration working) {
        public Lag {
            Objects.requireNonNull(natural, "natural");
            if (natural.isNegative() || natural.compareTo(MAX_OBSERVATION_RANGE) > 0
                || (working != null && (working.isNegative() || working.compareTo(natural) > 0))) {
                throw new IllegalArgumentException("invalid SLA completion lag");
            }
        }
    }
}
