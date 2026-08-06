package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesProductionRuntimeFactory
    .RuntimeControlSnapshot;
import io.github.akaryc1b.approval.ai.openai.OpenAiResponsesTransportControls.CircuitBreaker;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.OperationsView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.RuntimeControls;
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

/** Closed P6-C read-only health projection for the shared production AI runtime. */
public final class ControlledAutomationGovernanceControlHealthContracts {

    public static final String VIEW_VERSION = "m6-f-p6-c-control-health-v1";

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private ControlledAutomationGovernanceControlHealthContracts() {
    }

    public enum DriftHealth {
        NOT_OBSERVED,
        EXACT_FROZEN_PROFILE,
        DRIFT_DETECTED
    }

    public enum KillSwitchHealth {
        NOT_AVAILABLE,
        ADMISSION_ENABLED,
        ADMISSION_DISABLED
    }

    public enum PolicyWindowHealth {
        NOT_AVAILABLE,
        NOT_YET_ACTIVE,
        CURRENT,
        EXPIRED
    }

    public enum CircuitHealth {
        NOT_AVAILABLE,
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    public enum RateHealth {
        NOT_AVAILABLE,
        CONFIGURED_USAGE_NOT_EXPOSED
    }

    public enum BudgetHealth {
        NOT_AVAILABLE,
        REQUEST_CAP_ONLY_CONSUMPTION_NOT_EXPOSED
    }

    public record RuntimeEvidence(
        DriftHealth driftHealth,
        KillSwitchHealth killSwitchHealth,
        PolicyWindowHealth costPolicyHealth,
        PolicyWindowHealth secretVersionHealth,
        CircuitHealth circuitHealth,
        long killSwitchGeneration,
        long circuitGeneration,
        String killSwitchEvidenceHash,
        String costPolicyEvidenceHash,
        String secretVersionEvidenceHash,
        int perTenantRateLimit,
        int globalRateLimit,
        long rateWindowSeconds,
        int circuitFailureThreshold,
        long circuitOpenSeconds,
        long maximumRequestMicros,
        RateHealth rateHealth,
        BudgetHealth budgetHealth,
        boolean rateUsageExposed,
        boolean budgetConsumptionExposed
    ) {
        public RuntimeEvidence {
            driftHealth = Objects.requireNonNull(driftHealth, "driftHealth must not be null");
            killSwitchHealth = Objects.requireNonNull(
                killSwitchHealth,
                "killSwitchHealth must not be null"
            );
            costPolicyHealth = Objects.requireNonNull(
                costPolicyHealth,
                "costPolicyHealth must not be null"
            );
            secretVersionHealth = Objects.requireNonNull(
                secretVersionHealth,
                "secretVersionHealth must not be null"
            );
            circuitHealth = Objects.requireNonNull(
                circuitHealth,
                "circuitHealth must not be null"
            );
            if (killSwitchGeneration < 1 || circuitGeneration < 1) {
                throw new IllegalArgumentException("control generations must be positive");
            }
            killSwitchEvidenceHash = requireSha256(
                killSwitchEvidenceHash,
                "killSwitchEvidenceHash"
            );
            costPolicyEvidenceHash = requireSha256(
                costPolicyEvidenceHash,
                "costPolicyEvidenceHash"
            );
            secretVersionEvidenceHash = requireSha256(
                secretVersionEvidenceHash,
                "secretVersionEvidenceHash"
            );
            if (perTenantRateLimit < 1
                || globalRateLimit < perTenantRateLimit
                || rateWindowSeconds < 1
                || circuitFailureThreshold < 1
                || circuitOpenSeconds < 1
                || maximumRequestMicros < 1) {
                throw new IllegalArgumentException(
                    "runtime health limits must be positive and coherent"
                );
            }
            rateHealth = Objects.requireNonNull(rateHealth, "rateHealth must not be null");
            budgetHealth = Objects.requireNonNull(
                budgetHealth,
                "budgetHealth must not be null"
            );
            if (rateUsageExposed || budgetConsumptionExposed) {
                throw new IllegalArgumentException(
                    "P6-C cannot expose rate usage or budget consumption"
                );
            }
        }
    }

    public record ControlHealthView(
        String viewVersion,
        Instant observedAt,
        RuntimeState runtimeState,
        String sourceSnapshotEvidenceHash,
        RuntimeEvidence runtimeEvidence,
        List<String> blockerCodes,
        boolean providerMutationAvailable,
        boolean controlMutationAvailable,
        boolean commandExecutionAuthorized,
        boolean automaticRetryAuthorized,
        boolean rawSecretExposed,
        String evidenceHash
    ) {
        public ControlHealthView {
            viewVersion = requireText(viewVersion, "viewVersion", 160);
            observedAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
            runtimeState = Objects.requireNonNull(runtimeState, "runtimeState must not be null");
            sourceSnapshotEvidenceHash = requireSha256(
                sourceSnapshotEvidenceHash,
                "sourceSnapshotEvidenceHash"
            );
            blockerCodes = normalizeBlockers(blockerCodes);
            evidenceHash = requireSha256(evidenceHash, "evidenceHash");
            if (providerMutationAvailable
                || controlMutationAvailable
                || commandExecutionAuthorized
                || automaticRetryAuthorized
                || rawSecretExposed) {
                throw new IllegalArgumentException(
                    "P6-C control health must remain read-only and non-executing"
                );
            }
            if (runtimeState == RuntimeState.NOT_CONFIGURED && runtimeEvidence != null) {
                throw new IllegalArgumentException(
                    "disabled runtime cannot expose configured control evidence"
                );
            }
            if (runtimeState == RuntimeState.CONFIGURED_ADVISORY_ONLY
                && runtimeEvidence == null) {
                throw new IllegalArgumentException(
                    "configured runtime requires control evidence"
                );
            }
            String computed = computeEvidenceHash(
                viewVersion,
                observedAt,
                runtimeState,
                sourceSnapshotEvidenceHash,
                runtimeEvidence,
                blockerCodes
            );
            if (!evidenceHash.equals(computed)) {
                throw new IllegalArgumentException("evidenceHash must match the exact view");
            }
        }

        public static ControlHealthView disabled(OperationsView source) {
            OperationsView exact = Objects.requireNonNull(source, "source must not be null");
            if (exact.runtimeState() != RuntimeState.NOT_CONFIGURED) {
                throw new IllegalArgumentException("disabled health requires disabled source");
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
                null,
                blockers
            );
            return new ControlHealthView(
                VIEW_VERSION,
                exact.observedAt(),
                exact.runtimeState(),
                exact.evidenceHash(),
                null,
                blockers,
                false,
                false,
                false,
                false,
                false,
                hash
            );
        }

        public static ControlHealthView configured(
            OperationsView source,
            RuntimeControlSnapshot runtime
        ) {
            OperationsView exactSource = Objects.requireNonNull(
                source,
                "source must not be null"
            );
            RuntimeControlSnapshot exactRuntime = Objects.requireNonNull(
                runtime,
                "runtime must not be null"
            );
            if (exactSource.runtimeState() != RuntimeState.CONFIGURED_ADVISORY_ONLY
                || exactSource.controls() == null) {
                throw new IllegalArgumentException(
                    "configured health requires configured P6-A source"
                );
            }
            DriftHealth drift = matches(exactSource.controls(), exactRuntime)
                ? DriftHealth.EXACT_FROZEN_PROFILE
                : DriftHealth.DRIFT_DETECTED;
            RuntimeEvidence evidence = new RuntimeEvidence(
                drift,
                exactRuntime.killSwitchEnabled()
                    ? KillSwitchHealth.ADMISSION_ENABLED
                    : KillSwitchHealth.ADMISSION_DISABLED,
                window(
                    exactRuntime.observedAt(),
                    exactRuntime.costPolicyEffectiveFrom(),
                    exactRuntime.costPolicyExpiresAt()
                ),
                window(
                    exactRuntime.observedAt(),
                    exactRuntime.secretVersionEffectiveFrom(),
                    exactRuntime.secretVersionExpiresAt()
                ),
                circuit(exactRuntime.circuitState()),
                exactRuntime.killSwitchGeneration(),
                exactRuntime.circuitGeneration(),
                exactRuntime.killSwitchEvidenceHash(),
                exactRuntime.costPolicyEvidenceHash(),
                exactRuntime.secretVersionEvidenceHash(),
                exactRuntime.perTenantRateLimit(),
                exactRuntime.globalRateLimit(),
                exactRuntime.rateWindowSeconds(),
                exactRuntime.circuitFailureThreshold(),
                exactRuntime.circuitOpenSeconds(),
                exactRuntime.maximumRequestMicros(),
                RateHealth.CONFIGURED_USAGE_NOT_EXPOSED,
                BudgetHealth.REQUEST_CAP_ONLY_CONSUMPTION_NOT_EXPOSED,
                exactRuntime.rateUsageExposed(),
                exactRuntime.budgetConsumptionExposed()
            );
            List<String> blockers = blockers(evidence);
            String hash = computeEvidenceHash(
                VIEW_VERSION,
                exactRuntime.observedAt(),
                exactSource.runtimeState(),
                exactSource.evidenceHash(),
                evidence,
                blockers
            );
            return new ControlHealthView(
                VIEW_VERSION,
                exactRuntime.observedAt(),
                exactSource.runtimeState(),
                exactSource.evidenceHash(),
                evidence,
                blockers,
                false,
                false,
                false,
                false,
                false,
                hash
            );
        }
    }

    private static List<String> blockers(RuntimeEvidence evidence) {
        List<String> blockers = new ArrayList<>(List.of(
            "AI_AUTOMATION_ACTION_WHITELIST_EMPTY",
            "AI_BUDGET_CONSUMPTION_NOT_AVAILABLE",
            "AI_PRODUCTION_REAUTHENTICATION_UNAVAILABLE",
            "AI_RATE_USAGE_NOT_EXPOSED"
        ));
        if (evidence.driftHealth() == DriftHealth.DRIFT_DETECTED) {
            blockers.add("AI_PROVIDER_RUNTIME_DRIFT_DETECTED");
        }
        if (evidence.killSwitchHealth() == KillSwitchHealth.ADMISSION_DISABLED) {
            blockers.add("AI_PROVIDER_KILL_SWITCH_DISABLED");
        }
        if (evidence.costPolicyHealth() != PolicyWindowHealth.CURRENT) {
            blockers.add("AI_COST_POLICY_NOT_CURRENT");
        }
        if (evidence.secretVersionHealth() != PolicyWindowHealth.CURRENT) {
            blockers.add("AI_SECRET_VERSION_NOT_CURRENT");
        }
        if (evidence.circuitHealth() == CircuitHealth.OPEN) {
            blockers.add("AI_PROVIDER_CIRCUIT_OPEN");
        } else if (evidence.circuitHealth() == CircuitHealth.HALF_OPEN) {
            blockers.add("AI_PROVIDER_CIRCUIT_HALF_OPEN");
        }
        return normalizeBlockers(blockers);
    }

    private static boolean matches(
        RuntimeControls source,
        RuntimeControlSnapshot runtime
    ) {
        return source.killSwitchGeneration() == runtime.killSwitchGeneration()
            && source.killSwitchEvidenceHash().equals(runtime.killSwitchEvidenceHash())
            && source.costPolicyEvidenceHash().equals(runtime.costPolicyEvidenceHash())
            && source.secretVersionEvidenceHash().equals(runtime.secretVersionEvidenceHash())
            && source.perTenantRateLimit() == runtime.perTenantRateLimit()
            && source.globalRateLimit() == runtime.globalRateLimit()
            && source.rateWindowSeconds() == runtime.rateWindowSeconds()
            && source.circuitFailureThreshold() == runtime.circuitFailureThreshold()
            && source.circuitOpenSeconds() == runtime.circuitOpenSeconds()
            && source.maximumRequestMicros() == runtime.maximumRequestMicros();
    }

    private static PolicyWindowHealth window(
        Instant observedAt,
        Instant effectiveFrom,
        Instant expiresAt
    ) {
        if (observedAt.isBefore(effectiveFrom)) {
            return PolicyWindowHealth.NOT_YET_ACTIVE;
        }
        if (!observedAt.isBefore(expiresAt)) {
            return PolicyWindowHealth.EXPIRED;
        }
        return PolicyWindowHealth.CURRENT;
    }

    private static CircuitHealth circuit(CircuitBreaker.State state) {
        return switch (Objects.requireNonNull(state, "state must not be null")) {
            case CLOSED -> CircuitHealth.CLOSED;
            case OPEN -> CircuitHealth.OPEN;
            case HALF_OPEN -> CircuitHealth.HALF_OPEN;
        };
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
        RuntimeEvidence runtimeEvidence,
        List<String> blockerCodes
    ) {
        StringBuilder canonical = new StringBuilder("m6-f-p6-c-control-health\n");
        append(canonical, viewVersion);
        append(canonical, observedAt.toString());
        append(canonical, runtimeState.name());
        append(canonical, sourceSnapshotEvidenceHash);
        if (runtimeEvidence == null) {
            append(canonical, "runtime:none");
        } else {
            append(canonical, runtimeEvidence.driftHealth().name());
            append(canonical, runtimeEvidence.killSwitchHealth().name());
            append(canonical, runtimeEvidence.costPolicyHealth().name());
            append(canonical, runtimeEvidence.secretVersionHealth().name());
            append(canonical, runtimeEvidence.circuitHealth().name());
            append(canonical, Long.toString(runtimeEvidence.killSwitchGeneration()));
            append(canonical, Long.toString(runtimeEvidence.circuitGeneration()));
            append(canonical, runtimeEvidence.killSwitchEvidenceHash());
            append(canonical, runtimeEvidence.costPolicyEvidenceHash());
            append(canonical, runtimeEvidence.secretVersionEvidenceHash());
            append(canonical, Integer.toString(runtimeEvidence.perTenantRateLimit()));
            append(canonical, Integer.toString(runtimeEvidence.globalRateLimit()));
            append(canonical, Long.toString(runtimeEvidence.rateWindowSeconds()));
            append(canonical, Integer.toString(runtimeEvidence.circuitFailureThreshold()));
            append(canonical, Long.toString(runtimeEvidence.circuitOpenSeconds()));
            append(canonical, Long.toString(runtimeEvidence.maximumRequestMicros()));
            append(canonical, runtimeEvidence.rateHealth().name());
            append(canonical, runtimeEvidence.budgetHealth().name());
            append(canonical, Boolean.toString(runtimeEvidence.rateUsageExposed()));
            append(canonical, Boolean.toString(runtimeEvidence.budgetConsumptionExposed()));
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
