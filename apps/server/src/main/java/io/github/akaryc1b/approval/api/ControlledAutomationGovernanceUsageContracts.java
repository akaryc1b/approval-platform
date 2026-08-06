package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesRuntimeUsageLedger.UsageSnapshot;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.OperationsView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.RuntimeControls;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.RuntimeState;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Closed P6-D tenant usage projection for process-local AI rate and cost observability. */
public final class ControlledAutomationGovernanceUsageContracts {

    public static final String VIEW_VERSION = "m6-f-p6-d-runtime-usage-v1";

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private ControlledAutomationGovernanceUsageContracts() {
    }

    public enum UsageHealth {
        NOT_CONFIGURED,
        WITHIN_DERIVED_ENVELOPE,
        TENANT_RATE_WINDOW_SATURATED,
        GLOBAL_RATE_WINDOW_SATURATED
    }

    public enum CostBasis {
        ADMISSION_ESTIMATE_UPPER_BOUND_NOT_ACTUAL_PROVIDER_BILLING
    }

    public record TenantUsage(
        Instant windowStart,
        Instant windowEnd,
        int committedRequests,
        int requestLimit,
        int remainingRequests,
        long committedUpperBoundMicros,
        long derivedEnvelopeMicros,
        long remainingDerivedEnvelopeMicros,
        boolean tenantSaturated,
        boolean globalSaturated,
        boolean processLocal,
        boolean durable,
        boolean actualProviderCost,
        CostBasis costBasis,
        String runtimeUsageEvidenceHash
    ) {
        public TenantUsage {
            windowStart = Objects.requireNonNull(windowStart, "windowStart must not be null");
            windowEnd = Objects.requireNonNull(windowEnd, "windowEnd must not be null");
            if (!windowStart.isBefore(windowEnd)
                || committedRequests < 0
                || requestLimit < 1
                || committedRequests > requestLimit
                || remainingRequests != requestLimit - committedRequests
                || committedUpperBoundMicros < 0
                || derivedEnvelopeMicros < 1
                || committedUpperBoundMicros > derivedEnvelopeMicros
                || remainingDerivedEnvelopeMicros
                    != derivedEnvelopeMicros - committedUpperBoundMicros) {
                throw new IllegalArgumentException("tenant usage must be coherent and bounded");
            }
            if (tenantSaturated != (committedRequests >= requestLimit)) {
                throw new IllegalArgumentException("tenant saturation must match request usage");
            }
            if (!processLocal || durable || actualProviderCost) {
                throw new IllegalArgumentException(
                    "P6-D usage must remain process-local, non-durable and upper-bound only"
                );
            }
            costBasis = Objects.requireNonNull(costBasis, "costBasis must not be null");
            runtimeUsageEvidenceHash = requireSha256(
                runtimeUsageEvidenceHash,
                "runtimeUsageEvidenceHash"
            );
        }

        static TenantUsage from(UsageSnapshot source) {
            UsageSnapshot exact = Objects.requireNonNull(source, "source must not be null");
            return new TenantUsage(
                exact.windowStart(),
                exact.windowEnd(),
                exact.committedRequests(),
                exact.requestLimit(),
                exact.remainingRequests(),
                exact.committedUpperBoundMicros(),
                exact.derivedEnvelopeMicros(),
                exact.remainingDerivedEnvelopeMicros(),
                exact.tenantSaturated(),
                exact.globalSaturated(),
                exact.processLocal(),
                exact.durable(),
                exact.actualProviderCost(),
                CostBasis.ADMISSION_ESTIMATE_UPPER_BOUND_NOT_ACTUAL_PROVIDER_BILLING,
                exact.evidenceHash()
            );
        }
    }

    public record UsageView(
        String viewVersion,
        Instant observedAt,
        RuntimeState runtimeState,
        String sourceSnapshotEvidenceHash,
        UsageHealth usageHealth,
        TenantUsage tenantUsage,
        List<String> blockerCodes,
        boolean globalExactUsageExposed,
        boolean otherTenantUsageExposed,
        boolean usageMutationAvailable,
        boolean providerInvocationAvailable,
        boolean commandExecutionAuthorized,
        boolean automaticRetryAuthorized,
        boolean rawSecretExposed,
        String evidenceHash
    ) {
        public UsageView {
            viewVersion = requireText(viewVersion, "viewVersion", 160);
            observedAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
            runtimeState = Objects.requireNonNull(runtimeState, "runtimeState must not be null");
            sourceSnapshotEvidenceHash = requireSha256(
                sourceSnapshotEvidenceHash,
                "sourceSnapshotEvidenceHash"
            );
            usageHealth = Objects.requireNonNull(usageHealth, "usageHealth must not be null");
            blockerCodes = normalizeBlockers(blockerCodes);
            if (globalExactUsageExposed
                || otherTenantUsageExposed
                || usageMutationAvailable
                || providerInvocationAvailable
                || commandExecutionAuthorized
                || automaticRetryAuthorized
                || rawSecretExposed) {
                throw new IllegalArgumentException(
                    "P6-D usage view must remain tenant-isolated, read-only and non-executing"
                );
            }
            if (runtimeState == RuntimeState.NOT_CONFIGURED) {
                if (tenantUsage != null || usageHealth != UsageHealth.NOT_CONFIGURED) {
                    throw new IllegalArgumentException(
                        "disabled runtime cannot expose tenant usage"
                    );
                }
            } else if (tenantUsage == null || usageHealth == UsageHealth.NOT_CONFIGURED) {
                throw new IllegalArgumentException(
                    "configured runtime requires tenant usage and health"
                );
            }
            evidenceHash = requireSha256(evidenceHash, "evidenceHash");
            String computed = computeEvidenceHash(
                viewVersion,
                observedAt,
                runtimeState,
                sourceSnapshotEvidenceHash,
                usageHealth,
                tenantUsage,
                blockerCodes
            );
            if (!evidenceHash.equals(computed)) {
                throw new IllegalArgumentException("evidenceHash must match the exact usage view");
            }
        }

        public static UsageView disabled(OperationsView source) {
            OperationsView exact = Objects.requireNonNull(source, "source must not be null");
            if (exact.runtimeState() != RuntimeState.NOT_CONFIGURED) {
                throw new IllegalArgumentException("disabled usage requires disabled source");
            }
            List<String> blockers = normalizeBlockers(List.of(
                "AI_AUTOMATION_ACTION_WHITELIST_EMPTY",
                "AI_PRODUCTION_REAUTHENTICATION_UNAVAILABLE",
                "AI_PROVIDER_RUNTIME_NOT_CONFIGURED"
            ));
            String hash = computeEvidenceHash(
                VIEW_VERSION,
                exact.observedAt(),
                exact.runtimeState(),
                exact.evidenceHash(),
                UsageHealth.NOT_CONFIGURED,
                null,
                blockers
            );
            return new UsageView(
                VIEW_VERSION,
                exact.observedAt(),
                exact.runtimeState(),
                exact.evidenceHash(),
                UsageHealth.NOT_CONFIGURED,
                null,
                blockers,
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

        public static UsageView configured(
            OperationsView source,
            UsageSnapshot runtimeUsage
        ) {
            OperationsView exactSource = Objects.requireNonNull(
                source,
                "source must not be null"
            );
            if (exactSource.runtimeState() != RuntimeState.CONFIGURED_ADVISORY_ONLY
                || exactSource.controls() == null) {
                throw new IllegalArgumentException(
                    "configured usage requires configured advisory runtime"
                );
            }
            UsageSnapshot exactUsage = Objects.requireNonNull(
                runtimeUsage,
                "runtimeUsage must not be null"
            );
            validateAgainstSource(exactSource.controls(), exactUsage);
            TenantUsage usage = TenantUsage.from(exactUsage);
            UsageHealth health = usage.globalSaturated()
                ? UsageHealth.GLOBAL_RATE_WINDOW_SATURATED
                : usage.tenantSaturated()
                    ? UsageHealth.TENANT_RATE_WINDOW_SATURATED
                    : UsageHealth.WITHIN_DERIVED_ENVELOPE;
            List<String> blockers = blockers(usage);
            String hash = computeEvidenceHash(
                VIEW_VERSION,
                exactUsage.observedAt(),
                exactSource.runtimeState(),
                exactSource.evidenceHash(),
                health,
                usage,
                blockers
            );
            return new UsageView(
                VIEW_VERSION,
                exactUsage.observedAt(),
                exactSource.runtimeState(),
                exactSource.evidenceHash(),
                health,
                usage,
                blockers,
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

    private static void validateAgainstSource(
        RuntimeControls controls,
        UsageSnapshot usage
    ) {
        long expectedEnvelope;
        try {
            expectedEnvelope = Math.multiplyExact(
                controls.perTenantRateLimit(),
                controls.maximumRequestMicros()
            );
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("source usage envelope must fit in long", overflow);
        }
        Duration runtimeWindow = Duration.between(usage.windowStart(), usage.windowEnd());
        if (usage.requestLimit() != controls.perTenantRateLimit()
            || usage.derivedEnvelopeMicros() != expectedEnvelope
            || runtimeWindow.getNano() != 0
            || runtimeWindow.getSeconds() != controls.rateWindowSeconds()
            || usage.observedAt().isBefore(usage.windowStart())
            || !usage.observedAt().isBefore(usage.windowEnd())) {
            throw new IllegalArgumentException(
                "runtime usage must match the exact governance rate and cost profile"
            );
        }
    }

    private static List<String> blockers(TenantUsage usage) {
        List<String> blockers = new ArrayList<>(List.of(
            "AI_AUTOMATION_ACTION_WHITELIST_EMPTY",
            "AI_PRODUCTION_REAUTHENTICATION_UNAVAILABLE",
            "AI_USAGE_ACTUAL_PROVIDER_COST_NOT_AVAILABLE",
            "AI_USAGE_HISTORY_NOT_DURABLE"
        ));
        if (usage.tenantSaturated()) {
            blockers.add("AI_TENANT_RATE_WINDOW_SATURATED");
        }
        if (usage.globalSaturated()) {
            blockers.add("AI_GLOBAL_RATE_WINDOW_SATURATED");
        }
        return normalizeBlockers(blockers);
    }

    private static List<String> normalizeBlockers(List<String> blockerCodes) {
        List<String> normalized = blockerCodes == null
            ? List.of()
            : blockerCodes.stream()
                .map(code -> requireText(code, "blockerCode", 160))
                .distinct()
                .sorted()
                .toList();
        if (normalized.isEmpty() || normalized.size() > 32) {
            throw new IllegalArgumentException("blockerCodes must be non-empty and bounded");
        }
        return normalized;
    }

    private static String computeEvidenceHash(
        String viewVersion,
        Instant observedAt,
        RuntimeState runtimeState,
        String sourceSnapshotEvidenceHash,
        UsageHealth usageHealth,
        TenantUsage usage,
        List<String> blockerCodes
    ) {
        StringBuilder canonical = new StringBuilder("m6-f-p6-d-runtime-usage\n");
        append(canonical, viewVersion);
        append(canonical, observedAt.toString());
        append(canonical, runtimeState.name());
        append(canonical, sourceSnapshotEvidenceHash);
        append(canonical, usageHealth.name());
        if (usage == null) {
            append(canonical, "usage:none");
        } else {
            append(canonical, usage.windowStart().toString());
            append(canonical, usage.windowEnd().toString());
            append(canonical, Integer.toString(usage.committedRequests()));
            append(canonical, Integer.toString(usage.requestLimit()));
            append(canonical, Integer.toString(usage.remainingRequests()));
            append(canonical, Long.toString(usage.committedUpperBoundMicros()));
            append(canonical, Long.toString(usage.derivedEnvelopeMicros()));
            append(canonical, Long.toString(usage.remainingDerivedEnvelopeMicros()));
            append(canonical, Boolean.toString(usage.tenantSaturated()));
            append(canonical, Boolean.toString(usage.globalSaturated()));
            append(canonical, Boolean.toString(usage.processLocal()));
            append(canonical, Boolean.toString(usage.durable()));
            append(canonical, Boolean.toString(usage.actualProviderCost()));
            append(canonical, usage.costBasis().name());
            append(canonical, usage.runtimeUsageEvidenceHash());
        }
        for (String blocker : normalizeBlockers(blockerCodes)) {
            append(canonical, blocker);
        }
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
        String normalized = Objects.requireNonNull(value, "canonical value must not be null");
        canonical.append(normalized.length()).append(':').append(normalized).append('\n');
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
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and bounded");
        }
        return normalized;
    }
}
