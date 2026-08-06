package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.HistorySummary;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.OutcomeCount;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.UseCaseCount;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceGovernanceHistoryQuery.VersionStability;
import io.github.akaryc1b.approval.ai.core.ApprovalAssistanceAdvisoryContract.UseCase;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.OperationsView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.RuntimeState;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Closed P6-E tenant-scoped projection of durable V49 governance history. */
public final class ControlledAutomationGovernanceHistoryContracts {

    public static final String VIEW_VERSION = "m6-f-p6-e-durable-governance-history-v1";

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private ControlledAutomationGovernanceHistoryContracts() {
    }

    public enum HistoryHealth {
        EMPTY,
        STABLE,
        VERSION_DRIFT_DETECTED,
        RETENTION_ACTION_DUE,
        VERSION_DRIFT_AND_RETENTION_DUE
    }

    public record OutcomeHistory(
        AiOutcomeClassification classification,
        long evidenceCount
    ) {
        public OutcomeHistory {
            classification = Objects.requireNonNull(
                classification,
                "classification must not be null"
            );
            requireNonNegative(evidenceCount, "evidenceCount");
        }

        static OutcomeHistory from(OutcomeCount source) {
            OutcomeCount exact = Objects.requireNonNull(source, "source must not be null");
            return new OutcomeHistory(exact.classification(), exact.evidenceCount());
        }
    }

    public record UseCaseHistory(
        UseCase useCase,
        long evidenceCount,
        long providerInvocationCount,
        long advisoryResultCount,
        long distinctVersionBundleCount,
        VersionStability versionStability
    ) {
        public UseCaseHistory {
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
                || distinctVersionBundleCount > evidenceCount) {
                throw new IllegalArgumentException("use-case history must be bounded");
            }
        }

        static UseCaseHistory from(UseCaseCount source) {
            UseCaseCount exact = Objects.requireNonNull(source, "source must not be null");
            return new UseCaseHistory(
                exact.useCase(),
                exact.evidenceCount(),
                exact.providerInvocationCount(),
                exact.advisoryResultCount(),
                exact.distinctVersionBundleCount(),
                exact.versionStability()
            );
        }
    }

    public record HistoryView(
        String viewVersion,
        Instant observedAt,
        Instant fromInclusive,
        Instant toExclusive,
        RuntimeState currentRuntimeState,
        String sourceSnapshotEvidenceHash,
        HistoryHealth historyHealth,
        long totalEvidence,
        long activeEvidence,
        long tombstonedEvidence,
        long providerInvocationCount,
        long providerAttemptCount,
        long advisoryResultCount,
        long retentionDueCount,
        Instant earliestRecordedAt,
        Instant latestRecordedAt,
        List<OutcomeHistory> outcomeCounts,
        List<UseCaseHistory> useCaseCounts,
        List<String> blockerCodes,
        String actionWhitelistState,
        String p5Decision,
        boolean durableHistory,
        boolean crossProcessHistory,
        boolean actualProviderCostAvailable,
        boolean costUpperBoundHistoryAvailable,
        boolean exactGlobalUsageExposed,
        boolean otherTenantHistoryExposed,
        boolean historyMutationAvailable,
        boolean providerInvocationAvailable,
        boolean commandExecutionAuthorized,
        boolean automaticRetryAuthorized,
        boolean rawSecretExposed,
        String evidenceHash
    ) {
        public HistoryView {
            viewVersion = requireText(viewVersion, "viewVersion", 160);
            observedAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
            fromInclusive = Objects.requireNonNull(
                fromInclusive,
                "fromInclusive must not be null"
            );
            toExclusive = Objects.requireNonNull(
                toExclusive,
                "toExclusive must not be null"
            );
            if (!fromInclusive.isBefore(toExclusive)
                || toExclusive.isAfter(observedAt)) {
                throw new IllegalArgumentException("history window must be closed and bounded");
            }
            currentRuntimeState = Objects.requireNonNull(
                currentRuntimeState,
                "currentRuntimeState must not be null"
            );
            sourceSnapshotEvidenceHash = requireSha256(
                sourceSnapshotEvidenceHash,
                "sourceSnapshotEvidenceHash"
            );
            historyHealth = Objects.requireNonNull(
                historyHealth,
                "historyHealth must not be null"
            );
            requireNonNegative(totalEvidence, "totalEvidence");
            requireNonNegative(activeEvidence, "activeEvidence");
            requireNonNegative(tombstonedEvidence, "tombstonedEvidence");
            requireNonNegative(providerInvocationCount, "providerInvocationCount");
            requireNonNegative(providerAttemptCount, "providerAttemptCount");
            requireNonNegative(advisoryResultCount, "advisoryResultCount");
            requireNonNegative(retentionDueCount, "retentionDueCount");
            if (activeEvidence + tombstonedEvidence != totalEvidence
                || providerAttemptCount != providerInvocationCount
                || providerInvocationCount > totalEvidence
                || advisoryResultCount > totalEvidence
                || retentionDueCount > activeEvidence) {
                throw new IllegalArgumentException("history totals must be coherent");
            }
            outcomeCounts = List.copyOf(
                Objects.requireNonNull(outcomeCounts, "outcomeCounts must not be null")
            );
            useCaseCounts = List.copyOf(
                Objects.requireNonNull(useCaseCounts, "useCaseCounts must not be null")
            );
            blockerCodes = normalizeBlockers(blockerCodes);
            actionWhitelistState = requireText(
                actionWhitelistState,
                "actionWhitelistState",
                160
            );
            p5Decision = requireText(p5Decision, "p5Decision", 160);
            if (!ControlledAutomationGovernanceReadContracts.EMPTY_ACTION_WHITELIST.equals(
                actionWhitelistState
            ) || !ControlledAutomationGovernanceReadContracts.P5_SKIPPED.equals(p5Decision)) {
                throw new IllegalArgumentException(
                    "P6-E must preserve the empty Action Whitelist and skipped P5-A"
                );
            }
            if (!durableHistory
                || !crossProcessHistory
                || actualProviderCostAvailable
                || costUpperBoundHistoryAvailable
                || exactGlobalUsageExposed
                || otherTenantHistoryExposed
                || historyMutationAvailable
                || providerInvocationAvailable
                || commandExecutionAuthorized
                || automaticRetryAuthorized
                || rawSecretExposed) {
                throw new IllegalArgumentException(
                    "P6-E history must remain durable, tenant-isolated and non-executing"
                );
            }
            if (totalEvidence == 0) {
                if (earliestRecordedAt != null
                    || latestRecordedAt != null
                    || historyHealth != HistoryHealth.EMPTY) {
                    throw new IllegalArgumentException(
                        "empty durable history cannot manufacture timestamps or health"
                    );
                }
            } else if (earliestRecordedAt == null || latestRecordedAt == null) {
                throw new IllegalArgumentException(
                    "non-empty durable history requires recorded timestamps"
                );
            }
            evidenceHash = requireSha256(evidenceHash, "evidenceHash");
            String expected = computeEvidenceHash(
                viewVersion,
                observedAt,
                fromInclusive,
                toExclusive,
                currentRuntimeState,
                sourceSnapshotEvidenceHash,
                historyHealth,
                totalEvidence,
                activeEvidence,
                tombstonedEvidence,
                providerInvocationCount,
                providerAttemptCount,
                advisoryResultCount,
                retentionDueCount,
                earliestRecordedAt,
                latestRecordedAt,
                outcomeCounts,
                useCaseCounts,
                blockerCodes,
                actionWhitelistState,
                p5Decision
            );
            if (!evidenceHash.equals(expected)) {
                throw new IllegalArgumentException(
                    "evidenceHash must match the exact durable history view"
                );
            }
        }

        public static HistoryView from(
            OperationsView source,
            HistorySummary summary
        ) {
            OperationsView exactSource = Objects.requireNonNull(
                source,
                "source must not be null"
            );
            HistorySummary exactSummary = Objects.requireNonNull(
                summary,
                "summary must not be null"
            );
            List<OutcomeHistory> outcomes = exactSummary.outcomeCounts().stream()
                .map(OutcomeHistory::from)
                .toList();
            List<UseCaseHistory> useCases = exactSummary.useCaseCounts().stream()
                .map(UseCaseHistory::from)
                .toList();
            HistoryHealth health = health(exactSummary);
            List<String> blockers = blockers(exactSource, exactSummary);
            String hash = computeEvidenceHash(
                VIEW_VERSION,
                exactSummary.window().observedAt(),
                exactSummary.window().fromInclusive(),
                exactSummary.window().toExclusive(),
                exactSource.runtimeState(),
                exactSource.evidenceHash(),
                health,
                exactSummary.totalEvidence(),
                exactSummary.activeEvidence(),
                exactSummary.tombstonedEvidence(),
                exactSummary.providerInvocationCount(),
                exactSummary.providerAttemptCount(),
                exactSummary.advisoryResultCount(),
                exactSummary.retentionDueCount(),
                exactSummary.earliestRecordedAt(),
                exactSummary.latestRecordedAt(),
                outcomes,
                useCases,
                blockers,
                exactSource.actionWhitelistState(),
                exactSource.p5Decision()
            );
            return new HistoryView(
                VIEW_VERSION,
                exactSummary.window().observedAt(),
                exactSummary.window().fromInclusive(),
                exactSummary.window().toExclusive(),
                exactSource.runtimeState(),
                exactSource.evidenceHash(),
                health,
                exactSummary.totalEvidence(),
                exactSummary.activeEvidence(),
                exactSummary.tombstonedEvidence(),
                exactSummary.providerInvocationCount(),
                exactSummary.providerAttemptCount(),
                exactSummary.advisoryResultCount(),
                exactSummary.retentionDueCount(),
                exactSummary.earliestRecordedAt(),
                exactSummary.latestRecordedAt(),
                outcomes,
                useCases,
                blockers,
                exactSource.actionWhitelistState(),
                exactSource.p5Decision(),
                true,
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                hash
            );
        }
    }

    private static HistoryHealth health(HistorySummary summary) {
        if (summary.totalEvidence() == 0) {
            return HistoryHealth.EMPTY;
        }
        boolean drift = summary.versionDriftDetected();
        boolean retention = summary.retentionDueCount() > 0;
        if (drift && retention) {
            return HistoryHealth.VERSION_DRIFT_AND_RETENTION_DUE;
        }
        if (drift) {
            return HistoryHealth.VERSION_DRIFT_DETECTED;
        }
        if (retention) {
            return HistoryHealth.RETENTION_ACTION_DUE;
        }
        return HistoryHealth.STABLE;
    }

    private static List<String> blockers(
        OperationsView source,
        HistorySummary summary
    ) {
        List<String> blockers = new ArrayList<>(List.of(
            "AI_AUTOMATION_ACTION_WHITELIST_EMPTY",
            "AI_DURABLE_HISTORY_ACTUAL_PROVIDER_COST_NOT_AVAILABLE",
            "AI_DURABLE_HISTORY_COST_UPPER_BOUND_NOT_AVAILABLE",
            "AI_PRODUCTION_REAUTHENTICATION_UNAVAILABLE"
        ));
        if (source.runtimeState() == RuntimeState.NOT_CONFIGURED) {
            blockers.add("AI_PROVIDER_RUNTIME_NOT_CONFIGURED");
        }
        if (summary.totalEvidence() == 0) {
            blockers.add("AI_DURABLE_HISTORY_EMPTY");
        }
        if (summary.versionDriftDetected()) {
            blockers.add("AI_DURABLE_HISTORY_VERSION_DRIFT_DETECTED");
        }
        if (summary.retentionDueCount() > 0) {
            blockers.add("AI_RETENTION_TOMBSTONE_DUE");
        }
        return normalizeBlockers(blockers);
    }

    private static List<String> normalizeBlockers(List<String> source) {
        List<String> blockers = source == null
            ? List.of()
            : source.stream()
                .map(code -> requireText(code, "blockerCode", 160))
                .distinct()
                .sorted()
                .toList();
        if (blockers.isEmpty() || blockers.size() > 32) {
            throw new IllegalArgumentException("blockerCodes must be non-empty and bounded");
        }
        return blockers;
    }

    private static String computeEvidenceHash(
        String viewVersion,
        Instant observedAt,
        Instant fromInclusive,
        Instant toExclusive,
        RuntimeState runtimeState,
        String sourceSnapshotEvidenceHash,
        HistoryHealth historyHealth,
        long totalEvidence,
        long activeEvidence,
        long tombstonedEvidence,
        long providerInvocationCount,
        long providerAttemptCount,
        long advisoryResultCount,
        long retentionDueCount,
        Instant earliestRecordedAt,
        Instant latestRecordedAt,
        List<OutcomeHistory> outcomeCounts,
        List<UseCaseHistory> useCaseCounts,
        List<String> blockerCodes,
        String actionWhitelistState,
        String p5Decision
    ) {
        StringBuilder canonical = new StringBuilder("m6-f-p6-e-durable-history\n");
        append(canonical, viewVersion);
        append(canonical, observedAt.toString());
        append(canonical, fromInclusive.toString());
        append(canonical, toExclusive.toString());
        append(canonical, runtimeState.name());
        append(canonical, sourceSnapshotEvidenceHash);
        append(canonical, historyHealth.name());
        append(canonical, Long.toString(totalEvidence));
        append(canonical, Long.toString(activeEvidence));
        append(canonical, Long.toString(tombstonedEvidence));
        append(canonical, Long.toString(providerInvocationCount));
        append(canonical, Long.toString(providerAttemptCount));
        append(canonical, Long.toString(advisoryResultCount));
        append(canonical, Long.toString(retentionDueCount));
        append(canonical, earliestRecordedAt == null ? "none" : earliestRecordedAt.toString());
        append(canonical, latestRecordedAt == null ? "none" : latestRecordedAt.toString());
        for (OutcomeHistory outcome : outcomeCounts) {
            append(canonical, outcome.classification().name());
            append(canonical, Long.toString(outcome.evidenceCount()));
        }
        for (UseCaseHistory useCase : useCaseCounts) {
            append(canonical, useCase.useCase().name());
            append(canonical, Long.toString(useCase.evidenceCount()));
            append(canonical, Long.toString(useCase.providerInvocationCount()));
            append(canonical, Long.toString(useCase.advisoryResultCount()));
            append(canonical, Long.toString(useCase.distinctVersionBundleCount()));
            append(canonical, useCase.versionStability().name());
        }
        for (String blocker : normalizeBlockers(blockerCodes)) {
            append(canonical, blocker);
        }
        append(canonical, actionWhitelistState);
        append(canonical, p5Decision);
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    canonical.toString().getBytes(StandardCharsets.UTF_8)
                )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static void append(StringBuilder canonical, String value) {
        String exact = Objects.requireNonNull(value, "canonical value must not be null");
        canonical.append(exact.length()).append(':').append(exact).append('\n');
    }

    private static String requireSha256(String value, String name) {
        String normalized = requireText(value, name, 64);
        if (!SHA256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be lowercase SHA-256");
        }
        return normalized;
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank() || !value.equals(value.trim())
            || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be canonical and bounded");
        }
        return value;
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
