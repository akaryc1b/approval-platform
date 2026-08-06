package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Closed P6-A read-only AI governance projection.
 *
 * <p>The projection exposes exact version and control evidence only. It cannot mutate Provider
 * configuration, start a canary, roll out or roll back a Provider, resolve Secret material, invoke
 * a Provider, or authorize an application command.</p>
 */
public final class ControlledAutomationGovernanceReadContracts {

    public static final String SNAPSHOT_VERSION = "m6-f-p6-a-governance-snapshot-v1";
    public static final String EMPTY_ACTION_WHITELIST =
        "EMPTY_PENDING_EXISTING_COMMAND_AUDIT";
    public static final String P5_SKIPPED =
        "P5_A_SKIPPED_NO_QUALIFYING_EXISTING_COMMAND";

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private ControlledAutomationGovernanceReadContracts() {
    }

    public enum RuntimeState {
        NOT_CONFIGURED,
        CONFIGURED_ADVISORY_ONLY
    }

    public enum ActivationState {
        BLOCKED,
        ADVISORY_ONLY
    }

    public enum CanaryState {
        NOT_CONFIGURED
    }

    public enum DriftState {
        NOT_OBSERVED,
        EXACT_FROZEN_PROFILE
    }

    public enum RolloutState {
        BLOCKED,
        ADVISORY_ONLY
    }

    public enum RollbackState {
        ALREADY_DISABLED,
        DISABLE_RUNTIME_FLAG
    }

    public enum CircuitPosture {
        NOT_AVAILABLE,
        LIVE_STATE_NOT_EXPOSED
    }

    public record InventoryEntry(
        AiCapability capability,
        AiVersionReferences versions
    ) {
        public InventoryEntry {
            capability = Objects.requireNonNull(capability, "capability must not be null");
            versions = Objects.requireNonNull(versions, "versions must not be null");
        }
    }

    public record RuntimeControls(
        long killSwitchGeneration,
        String killSwitchEvidenceHash,
        String costPolicyEvidenceHash,
        String secretVersionEvidenceHash,
        int perTenantRateLimit,
        int globalRateLimit,
        long rateWindowSeconds,
        int circuitFailureThreshold,
        long circuitOpenSeconds,
        long maximumRequestMicros
    ) {
        public RuntimeControls {
            if (killSwitchGeneration < 1) {
                throw new IllegalArgumentException("killSwitchGeneration must be positive");
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
                    "runtime control values must be positive, coherent and bounded"
                );
            }
        }
    }

    public record OperationsView(
        String snapshotVersion,
        Instant observedAt,
        RuntimeState runtimeState,
        ActivationState activationState,
        CanaryState canaryState,
        DriftState driftState,
        RolloutState rolloutState,
        RollbackState rollbackState,
        CircuitPosture circuitPosture,
        List<InventoryEntry> inventory,
        RuntimeControls controls,
        List<String> blockerCodes,
        String actionWhitelistState,
        String p5Decision,
        boolean providerMutationAvailable,
        boolean canaryMutationAvailable,
        boolean rollbackMutationAvailable,
        boolean commandExecutionAuthorized,
        boolean automaticRetryAuthorized,
        boolean rawSecretExposed,
        String evidenceHash
    ) {
        public OperationsView {
            snapshotVersion = requireText(snapshotVersion, "snapshotVersion", 160);
            observedAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
            runtimeState = Objects.requireNonNull(runtimeState, "runtimeState must not be null");
            activationState = Objects.requireNonNull(
                activationState,
                "activationState must not be null"
            );
            canaryState = Objects.requireNonNull(canaryState, "canaryState must not be null");
            driftState = Objects.requireNonNull(driftState, "driftState must not be null");
            rolloutState = Objects.requireNonNull(rolloutState, "rolloutState must not be null");
            rollbackState = Objects.requireNonNull(
                rollbackState,
                "rollbackState must not be null"
            );
            circuitPosture = Objects.requireNonNull(
                circuitPosture,
                "circuitPosture must not be null"
            );
            inventory = normalizeInventory(inventory);
            blockerCodes = normalizeBlockers(blockerCodes);
            actionWhitelistState = requireText(
                actionWhitelistState,
                "actionWhitelistState",
                160
            );
            p5Decision = requireText(p5Decision, "p5Decision", 160);
            evidenceHash = requireSha256(evidenceHash, "evidenceHash");

            if (!EMPTY_ACTION_WHITELIST.equals(actionWhitelistState)
                || !P5_SKIPPED.equals(p5Decision)) {
                throw new IllegalArgumentException(
                    "P6-A must preserve the empty Action Whitelist and skipped P5-A decision"
                );
            }
            if (providerMutationAvailable
                || canaryMutationAvailable
                || rollbackMutationAvailable
                || commandExecutionAuthorized
                || automaticRetryAuthorized
                || rawSecretExposed) {
                throw new IllegalArgumentException(
                    "P6-A governance operations must remain read-only and non-executing"
                );
            }
            if (runtimeState == RuntimeState.NOT_CONFIGURED) {
                if (controls != null
                    || activationState != ActivationState.BLOCKED
                    || driftState != DriftState.NOT_OBSERVED
                    || rolloutState != RolloutState.BLOCKED
                    || rollbackState != RollbackState.ALREADY_DISABLED
                    || circuitPosture != CircuitPosture.NOT_AVAILABLE) {
                    throw new IllegalArgumentException(
                        "disabled runtime posture must remain blocked and carry no controls"
                    );
                }
            } else if (controls == null
                || activationState != ActivationState.ADVISORY_ONLY
                || driftState != DriftState.EXACT_FROZEN_PROFILE
                || rolloutState != RolloutState.ADVISORY_ONLY
                || rollbackState != RollbackState.DISABLE_RUNTIME_FLAG
                || circuitPosture != CircuitPosture.LIVE_STATE_NOT_EXPOSED) {
                throw new IllegalArgumentException(
                    "configured runtime posture must remain exact and advisory-only"
                );
            }
            if (canaryState != CanaryState.NOT_CONFIGURED) {
                throw new IllegalArgumentException("P6-A cannot configure a canary");
            }
            String computed = computeEvidenceHash(
                snapshotVersion,
                observedAt,
                runtimeState,
                activationState,
                canaryState,
                driftState,
                rolloutState,
                rollbackState,
                circuitPosture,
                inventory,
                controls,
                blockerCodes,
                actionWhitelistState,
                p5Decision
            );
            if (!evidenceHash.equals(computed)) {
                throw new IllegalArgumentException("evidenceHash must match the exact snapshot");
            }
        }

        public static OperationsView disabled(
            Instant observedAt,
            List<InventoryEntry> inventory
        ) {
            List<InventoryEntry> normalizedInventory = normalizeInventory(inventory);
            List<String> blockers = normalizeBlockers(List.of(
                "AI_AUTOMATION_ACTION_WHITELIST_EMPTY",
                "AI_PRODUCTION_REAUTHENTICATION_UNAVAILABLE",
                "AI_PROVIDER_CANARY_NOT_CONFIGURED",
                "AI_PROVIDER_RUNTIME_NOT_CONFIGURED"
            ));
            String evidence = computeEvidenceHash(
                SNAPSHOT_VERSION,
                observedAt,
                RuntimeState.NOT_CONFIGURED,
                ActivationState.BLOCKED,
                CanaryState.NOT_CONFIGURED,
                DriftState.NOT_OBSERVED,
                RolloutState.BLOCKED,
                RollbackState.ALREADY_DISABLED,
                CircuitPosture.NOT_AVAILABLE,
                normalizedInventory,
                null,
                blockers,
                EMPTY_ACTION_WHITELIST,
                P5_SKIPPED
            );
            return new OperationsView(
                SNAPSHOT_VERSION,
                observedAt,
                RuntimeState.NOT_CONFIGURED,
                ActivationState.BLOCKED,
                CanaryState.NOT_CONFIGURED,
                DriftState.NOT_OBSERVED,
                RolloutState.BLOCKED,
                RollbackState.ALREADY_DISABLED,
                CircuitPosture.NOT_AVAILABLE,
                normalizedInventory,
                null,
                blockers,
                EMPTY_ACTION_WHITELIST,
                P5_SKIPPED,
                false,
                false,
                false,
                false,
                false,
                false,
                evidence
            );
        }

        public static OperationsView configured(
            Instant observedAt,
            List<InventoryEntry> inventory,
            RuntimeControls controls
        ) {
            RuntimeControls exactControls = Objects.requireNonNull(
                controls,
                "controls must not be null"
            );
            List<InventoryEntry> normalizedInventory = normalizeInventory(inventory);
            List<String> blockers = normalizeBlockers(List.of(
                "AI_AUTOMATION_ACTION_WHITELIST_EMPTY",
                "AI_LIVE_CIRCUIT_STATE_NOT_EXPOSED",
                "AI_PRODUCTION_REAUTHENTICATION_UNAVAILABLE",
                "AI_PROVIDER_CANARY_NOT_CONFIGURED",
                "AI_PROVIDER_ROLLOUT_MUTATION_NOT_AVAILABLE"
            ));
            String evidence = computeEvidenceHash(
                SNAPSHOT_VERSION,
                observedAt,
                RuntimeState.CONFIGURED_ADVISORY_ONLY,
                ActivationState.ADVISORY_ONLY,
                CanaryState.NOT_CONFIGURED,
                DriftState.EXACT_FROZEN_PROFILE,
                RolloutState.ADVISORY_ONLY,
                RollbackState.DISABLE_RUNTIME_FLAG,
                CircuitPosture.LIVE_STATE_NOT_EXPOSED,
                normalizedInventory,
                exactControls,
                blockers,
                EMPTY_ACTION_WHITELIST,
                P5_SKIPPED
            );
            return new OperationsView(
                SNAPSHOT_VERSION,
                observedAt,
                RuntimeState.CONFIGURED_ADVISORY_ONLY,
                ActivationState.ADVISORY_ONLY,
                CanaryState.NOT_CONFIGURED,
                DriftState.EXACT_FROZEN_PROFILE,
                RolloutState.ADVISORY_ONLY,
                RollbackState.DISABLE_RUNTIME_FLAG,
                CircuitPosture.LIVE_STATE_NOT_EXPOSED,
                normalizedInventory,
                exactControls,
                blockers,
                EMPTY_ACTION_WHITELIST,
                P5_SKIPPED,
                false,
                false,
                false,
                false,
                false,
                false,
                evidence
            );
        }
    }

    private static List<InventoryEntry> normalizeInventory(List<InventoryEntry> inventory) {
        List<InventoryEntry> normalized = inventory == null
            ? List.of()
            : inventory.stream()
                .map(entry -> Objects.requireNonNull(entry, "inventory entry must not be null"))
                .sorted(Comparator.comparing(entry -> entry.capability().name()))
                .toList();
        if (normalized.size() != 3
            || normalized.stream().map(InventoryEntry::capability).distinct().count() != 3) {
            throw new IllegalArgumentException(
                "P6-A inventory must contain exactly the three closed assistance capabilities"
            );
        }
        return normalized;
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
        String snapshotVersion,
        Instant observedAt,
        RuntimeState runtimeState,
        ActivationState activationState,
        CanaryState canaryState,
        DriftState driftState,
        RolloutState rolloutState,
        RollbackState rollbackState,
        CircuitPosture circuitPosture,
        List<InventoryEntry> inventory,
        RuntimeControls controls,
        List<String> blockerCodes,
        String actionWhitelistState,
        String p5Decision
    ) {
        StringBuilder canonical = new StringBuilder("m6-f-p6-a-governance-snapshot\n");
        append(canonical, snapshotVersion);
        append(canonical, observedAt.toString());
        append(canonical, runtimeState.name());
        append(canonical, activationState.name());
        append(canonical, canaryState.name());
        append(canonical, driftState.name());
        append(canonical, rolloutState.name());
        append(canonical, rollbackState.name());
        append(canonical, circuitPosture.name());
        for (InventoryEntry entry : normalizeInventory(inventory)) {
            append(canonical, entry.capability().name());
            append(canonical, entry.versions().provider().providerId());
            append(canonical, entry.versions().provider().version());
            append(canonical, entry.versions().model().authorizationKey());
            append(canonical, entry.versions().promptTemplate().templateId());
            append(canonical, entry.versions().promptTemplate().version());
            append(canonical, entry.versions().promptTemplate().contentHash());
            append(canonical, entry.versions().policy().policyId());
            append(canonical, entry.versions().policy().version());
            append(canonical, entry.versions().policy().contentHash());
            append(canonical, entry.versions().outputSchema().schemaId());
            append(canonical, Integer.toString(entry.versions().outputSchema().version()));
        }
        if (controls == null) {
            append(canonical, "controls:none");
        } else {
            append(canonical, Long.toString(controls.killSwitchGeneration()));
            append(canonical, controls.killSwitchEvidenceHash());
            append(canonical, controls.costPolicyEvidenceHash());
            append(canonical, controls.secretVersionEvidenceHash());
            append(canonical, Integer.toString(controls.perTenantRateLimit()));
            append(canonical, Integer.toString(controls.globalRateLimit()));
            append(canonical, Long.toString(controls.rateWindowSeconds()));
            append(canonical, Integer.toString(controls.circuitFailureThreshold()));
            append(canonical, Long.toString(controls.circuitOpenSeconds()));
            append(canonical, Long.toString(controls.maximumRequestMicros()));
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
        String normalized = Objects.requireNonNull(value, "canonical value must not be null");
        canonical.append(normalized.length()).append(':').append(normalized).append('\n');
    }

    private static String requireSha256(String value, String name) {
        String normalized = requireText(value, name, 64);
        if (!SHA256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256");
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
