package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.UseCase;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Framework-free P6-E query contract for V49-backed durable governance history. */
@FunctionalInterface
public interface ApprovalAssistanceGovernanceHistoryQuery {

    Duration MAXIMUM_WINDOW = Duration.ofDays(31);
    Duration MAXIMUM_LOOKBACK = Duration.ofDays(3_650);

    HistorySummary summarize(HistoryWindow window);

    enum VersionStability {
        EMPTY,
        SINGLE_VERSION_BUNDLE,
        MULTIPLE_VERSION_BUNDLES
    }

    record HistoryWindow(
        String tenantId,
        Instant fromInclusive,
        Instant toExclusive,
        Instant observedAt
    ) {
        public HistoryWindow {
            tenantId = requireText(tenantId, "tenantId", 128);
            fromInclusive = Objects.requireNonNull(
                fromInclusive,
                "fromInclusive must not be null"
            );
            toExclusive = Objects.requireNonNull(
                toExclusive,
                "toExclusive must not be null"
            );
            observedAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
            if (!fromInclusive.isBefore(toExclusive)) {
                throw new IllegalArgumentException("history window must be positive");
            }
            Duration window = Duration.between(fromInclusive, toExclusive);
            if (window.compareTo(MAXIMUM_WINDOW) > 0) {
                throw new IllegalArgumentException("history window must not exceed 31 days");
            }
            if (toExclusive.isAfter(observedAt)) {
                throw new IllegalArgumentException("history window cannot extend into the future");
            }
            if (fromInclusive.isBefore(observedAt.minus(MAXIMUM_LOOKBACK))) {
                throw new IllegalArgumentException("history window exceeds retention lookback");
            }
        }
    }

    record OutcomeCount(
        AiOutcomeClassification classification,
        long evidenceCount
    ) {
        public OutcomeCount {
            classification = Objects.requireNonNull(
                classification,
                "classification must not be null"
            );
            requireNonNegative(evidenceCount, "evidenceCount");
        }
    }

    record UseCaseCount(
        UseCase useCase,
        long evidenceCount,
        long providerInvocationCount,
        long advisoryResultCount,
        long distinctVersionBundleCount,
        VersionStability versionStability
    ) {
        public UseCaseCount {
            useCase = Objects.requireNonNull(useCase, "useCase must not be null");
            requireNonNegative(evidenceCount, "evidenceCount");
            requireNonNegative(providerInvocationCount, "providerInvocationCount");
            requireNonNegative(advisoryResultCount, "advisoryResultCount");
            requireNonNegative(distinctVersionBundleCount, "distinctVersionBundleCount");
            versionStability = Objects.requireNonNull(
                versionStability,
                "versionStability must not be null"
            );
            if (providerInvocationCount > evidenceCount
                || advisoryResultCount > evidenceCount
                || distinctVersionBundleCount > evidenceCount
                || (evidenceCount == 0 && (
                    providerInvocationCount != 0
                        || advisoryResultCount != 0
                        || distinctVersionBundleCount != 0
                ))
                || (evidenceCount > 0 && distinctVersionBundleCount == 0)) {
                throw new IllegalArgumentException("use-case history counts must be bounded");
            }
            VersionStability expected = evidenceCount == 0
                ? VersionStability.EMPTY
                : distinctVersionBundleCount == 1
                    ? VersionStability.SINGLE_VERSION_BUNDLE
                    : VersionStability.MULTIPLE_VERSION_BUNDLES;
            if (versionStability != expected) {
                throw new IllegalArgumentException(
                    "versionStability must match the exact distinct bundle count"
                );
            }
        }

        public static UseCaseCount empty(UseCase useCase) {
            return new UseCaseCount(
                useCase,
                0,
                0,
                0,
                0,
                VersionStability.EMPTY
            );
        }
    }

    record HistorySummary(
        HistoryWindow window,
        long totalEvidence,
        long activeEvidence,
        long tombstonedEvidence,
        long providerInvocationCount,
        long providerAttemptCount,
        long advisoryResultCount,
        long unsafeRetryCount,
        long postInvocationFallbackCount,
        long retentionDueCount,
        Instant earliestRecordedAt,
        Instant latestRecordedAt,
        List<OutcomeCount> outcomeCounts,
        List<UseCaseCount> useCaseCounts
    ) {
        public HistorySummary {
            window = Objects.requireNonNull(window, "window must not be null");
            requireNonNegative(totalEvidence, "totalEvidence");
            requireNonNegative(activeEvidence, "activeEvidence");
            requireNonNegative(tombstonedEvidence, "tombstonedEvidence");
            requireNonNegative(providerInvocationCount, "providerInvocationCount");
            requireNonNegative(providerAttemptCount, "providerAttemptCount");
            requireNonNegative(advisoryResultCount, "advisoryResultCount");
            requireNonNegative(unsafeRetryCount, "unsafeRetryCount");
            requireNonNegative(
                postInvocationFallbackCount,
                "postInvocationFallbackCount"
            );
            requireNonNegative(retentionDueCount, "retentionDueCount");
            if (activeEvidence + tombstonedEvidence != totalEvidence
                || providerInvocationCount > totalEvidence
                || providerAttemptCount != providerInvocationCount
                || advisoryResultCount > totalEvidence
                || retentionDueCount > activeEvidence) {
                throw new IllegalArgumentException("durable history totals must be coherent");
            }
            if (unsafeRetryCount != 0 || postInvocationFallbackCount != 0) {
                throw new IllegalArgumentException(
                    "durable history cannot contain unsafe retry or fallback"
                );
            }
            outcomeCounts = requireCompleteOutcomes(outcomeCounts, totalEvidence);
            useCaseCounts = requireCompleteUseCases(
                useCaseCounts,
                totalEvidence,
                providerInvocationCount,
                advisoryResultCount
            );
            if (totalEvidence == 0) {
                if (earliestRecordedAt != null || latestRecordedAt != null) {
                    throw new IllegalArgumentException(
                        "empty history cannot manufacture recorded timestamps"
                    );
                }
            } else {
                earliestRecordedAt = Objects.requireNonNull(
                    earliestRecordedAt,
                    "non-empty history requires earliestRecordedAt"
                );
                latestRecordedAt = Objects.requireNonNull(
                    latestRecordedAt,
                    "non-empty history requires latestRecordedAt"
                );
                if (earliestRecordedAt.isAfter(latestRecordedAt)
                    || earliestRecordedAt.isBefore(window.fromInclusive())
                    || !earliestRecordedAt.isBefore(window.toExclusive())
                    || latestRecordedAt.isBefore(window.fromInclusive())
                    || !latestRecordedAt.isBefore(window.toExclusive())) {
                    throw new IllegalArgumentException(
                        "recorded timestamps must remain inside the exact history window"
                    );
                }
            }
        }

        public boolean versionDriftDetected() {
            return useCaseCounts.stream().anyMatch(
                count -> count.versionStability()
                    == VersionStability.MULTIPLE_VERSION_BUNDLES
            );
        }

        public static HistorySummary empty(HistoryWindow window) {
            return new HistorySummary(
                window,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                null,
                null,
                Arrays.stream(AiOutcomeClassification.values())
                    .map(classification -> new OutcomeCount(classification, 0))
                    .toList(),
                Arrays.stream(UseCase.values())
                    .map(UseCaseCount::empty)
                    .toList()
            );
        }
    }

    private static List<OutcomeCount> requireCompleteOutcomes(
        List<OutcomeCount> source,
        long expectedTotal
    ) {
        List<OutcomeCount> counts = List.copyOf(
            Objects.requireNonNull(source, "outcomeCounts must not be null")
        );
        AiOutcomeClassification[] values = AiOutcomeClassification.values();
        if (counts.size() != values.length) {
            throw new IllegalArgumentException("outcomeCounts must cover the closed enum");
        }
        long total = 0;
        for (int index = 0; index < values.length; index++) {
            OutcomeCount count = counts.get(index);
            if (count.classification() != values[index]) {
                throw new IllegalArgumentException(
                    "outcomeCounts must use exact enum order"
                );
            }
            total = addExact(total, count.evidenceCount(), "outcomeCounts");
        }
        if (total != expectedTotal) {
            throw new IllegalArgumentException("outcomeCounts must sum to totalEvidence");
        }
        return counts;
    }

    private static List<UseCaseCount> requireCompleteUseCases(
        List<UseCaseCount> source,
        long expectedTotal,
        long expectedInvocations,
        long expectedAdvisories
    ) {
        List<UseCaseCount> counts = List.copyOf(
            Objects.requireNonNull(source, "useCaseCounts must not be null")
        );
        UseCase[] values = UseCase.values();
        if (counts.size() != values.length) {
            throw new IllegalArgumentException("useCaseCounts must cover the closed enum");
        }
        long total = 0;
        long invocations = 0;
        long advisories = 0;
        for (int index = 0; index < values.length; index++) {
            UseCaseCount count = counts.get(index);
            if (count.useCase() != values[index]) {
                throw new IllegalArgumentException(
                    "useCaseCounts must use exact enum order"
                );
            }
            total = addExact(total, count.evidenceCount(), "useCaseCounts");
            invocations = addExact(
                invocations,
                count.providerInvocationCount(),
                "providerInvocationCount"
            );
            advisories = addExact(
                advisories,
                count.advisoryResultCount(),
                "advisoryResultCount"
            );
        }
        if (total != expectedTotal
            || invocations != expectedInvocations
            || advisories != expectedAdvisories) {
            throw new IllegalArgumentException(
                "useCaseCounts must match the exact durable totals"
            );
        }
        return counts;
    }

    private static long addExact(long left, long right, String name) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(name + " must fit in long", overflow);
        }
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank() || !value.equals(value.trim())
            || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be canonical and bounded");
        }
        return value;
    }
}
