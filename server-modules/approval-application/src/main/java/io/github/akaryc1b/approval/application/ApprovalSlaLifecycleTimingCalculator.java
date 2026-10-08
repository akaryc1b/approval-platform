package io.github.akaryc1b.approval.application;

import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.CalendarStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.CalendarVersion;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.PolicyStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaDurationMode;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaInstance;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaPolicyVersion;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaStatus;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaTargetType;

import java.time.Duration;
import java.util.Objects;

/**
 * Pure terminal-SLA elapsed-time calculation from exact persisted version bindings.
 * This class neither establishes that an input committed nor exports an observation.
 * Gross elapsed time includes pauses; the current store cannot prove complete pause history.
 */
public final class ApprovalSlaLifecycleTimingCalculator {

    public static final Duration MAX_OBSERVATION_RANGE = Duration.ofDays(366);

    private final ApprovalWorkingTimeCalculator workingTime;

    public ApprovalSlaLifecycleTimingCalculator(ApprovalWorkingTimeCalculator workingTime) {
        this.workingTime = Objects.requireNonNull(workingTime, "workingTime");
    }

    public Result calculate(SlaInstance instance, SlaPolicyVersion policy, CalendarVersion calendar) {
        if (instance == null) return unavailable(UnavailableReason.INSTANCE_MISSING);
        if (instance.status() != SlaStatus.TERMINAL) return unavailable(UnavailableReason.NOT_TERMINAL);
        Duration natural = Duration.between(instance.startedAt(), instance.terminalAt());
        if (natural.isNegative()) return unavailable(UnavailableReason.INVALID_INTERVAL);
        if (natural.compareTo(MAX_OBSERVATION_RANGE) > 0) {
            return unavailable(UnavailableReason.RANGE_EXCEEDED);
        }
        if (policy == null) return unavailable(UnavailableReason.POLICY_MISSING);
        if (!matches(instance, policy)) return unavailable(UnavailableReason.POLICY_MISMATCH);
        if (!policy.immutable() || policy.status() == PolicyStatus.DRAFT) {
            return unavailable(UnavailableReason.POLICY_UNPUBLISHED);
        }
        if (policy.durationMode() == SlaDurationMode.NATURAL_TIME) {
            if (calendar != null || !"UTC".equals(instance.timeZone())) {
                return unavailable(UnavailableReason.CALENDAR_MISMATCH);
            }
            PolicyDuration policyDuration = policy.naturalTimePauses()
                ? PolicyDurationUnavailable.MISSING_PAUSE_HISTORY : new MeasuredPolicyDuration(natural);
            return new Measured(natural, null, policyDuration);
        }
        if (calendar == null) return unavailable(UnavailableReason.CALENDAR_MISSING);
        if (!instance.tenantId().equals(calendar.tenantId())
            || !Objects.equals(instance.calendarId(), calendar.calendarId())
            || !Objects.equals(instance.calendarVersion(), calendar.calendarVersion())
            || !instance.timeZone().equals(calendar.snapshot().zoneId().getId())) {
            return unavailable(UnavailableReason.CALENDAR_MISMATCH);
        }
        if (!calendar.immutable() || calendar.status() == CalendarStatus.DRAFT) {
            return unavailable(UnavailableReason.CALENDAR_UNPUBLISHED);
        }
        try {
            Duration working = workingTime.workingDurationBetween(
                calendar.snapshot(), instance.startedAt(), instance.terminalAt()
            );
            return new Measured(natural, working, PolicyDurationUnavailable.MISSING_PAUSE_HISTORY);
        } catch (RuntimeException unavailable) {
            return unavailable(UnavailableReason.CALENDAR_CALCULATION_FAILED);
        }
    }

    private static boolean matches(SlaInstance instance, SlaPolicyVersion policy) {
        // A process policy can be inherited by a task or collaboration participant; a task
        // policy can be inherited by a participant. Do not reject these existing fallback paths.
        boolean targetMatches = policy.targetType() == instance.targetType()
            || policy.targetType() == SlaTargetType.PROCESS
            || (policy.targetType() == SlaTargetType.TASK
                && instance.targetType() == SlaTargetType.COLLABORATION_PARTICIPANT);
        return instance.tenantId().equals(policy.tenantId())
            && instance.policyId().equals(policy.policyId())
            && instance.policyVersion() == policy.policyVersion()
            && instance.definitionKey().equals(policy.definitionKey())
            && policy.appliesToTask(instance.taskDefinitionKey())
            && targetMatches
            && Objects.equals(instance.calendarId(), policy.calendarId())
            && Objects.equals(instance.calendarVersion(), policy.calendarVersion());
    }

    private static Unavailable unavailable(UnavailableReason reason) {
        return new Unavailable(reason);
    }

    public sealed interface Result permits Measured, Unavailable { }

    /** Working elapsed is null for a natural-time policy; it is never a fabricated zero. */
    public record Measured(
        Duration naturalElapsed,
        Duration workingElapsed,
        PolicyDuration policyDuration
    ) implements Result {
        public Measured {
            Objects.requireNonNull(naturalElapsed, "naturalElapsed");
            Objects.requireNonNull(policyDuration, "policyDuration");
            if (naturalElapsed.isNegative() || naturalElapsed.compareTo(MAX_OBSERVATION_RANGE) > 0
                || (workingElapsed != null
                    && (workingElapsed.isNegative() || workingElapsed.compareTo(naturalElapsed) > 0))
                || (policyDuration instanceof MeasuredPolicyDuration measured
                    && measured.elapsed().compareTo(naturalElapsed) > 0)) {
                throw new IllegalArgumentException("invalid SLA lifecycle timing");
            }
        }
    }

    public record Unavailable(UnavailableReason reason) implements Result {
        public Unavailable {
            Objects.requireNonNull(reason, "reason");
        }
    }

    public sealed interface PolicyDuration permits MeasuredPolicyDuration, PolicyDurationUnavailable { }

    public record MeasuredPolicyDuration(Duration elapsed) implements PolicyDuration {
        public MeasuredPolicyDuration {
            Objects.requireNonNull(elapsed, "elapsed");
            if (elapsed.isNegative() || elapsed.compareTo(MAX_OBSERVATION_RANGE) > 0) {
                throw new IllegalArgumentException("invalid SLA policy duration");
            }
        }
    }

    public enum PolicyDurationUnavailable implements PolicyDuration {
        MISSING_PAUSE_HISTORY
    }

    public enum UnavailableReason {
        INSTANCE_MISSING,
        NOT_TERMINAL,
        INVALID_INTERVAL,
        RANGE_EXCEEDED,
        POLICY_MISSING,
        POLICY_MISMATCH,
        POLICY_UNPUBLISHED,
        CALENDAR_MISSING,
        CALENDAR_MISMATCH,
        CALENDAR_UNPUBLISHED,
        CALENDAR_CALCULATION_FAILED
    }
}
