package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.InventoryEntry;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.OperationsView;
import io.github.akaryc1b.approval.api.ControlledAutomationGovernanceReadContracts.RuntimeState;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Closed P6-B Canary, Rollout and Rollback governance-plan contracts.
 *
 * <p>Every plan is a deterministic review projection. It cannot mutate Provider configuration,
 * allocate traffic, resolve Secret material, invoke a Provider, deploy a release or execute an
 * application command.</p>
 */
public final class ControlledAutomationGovernancePlanContracts {

    public static final String PLAN_VERSION = "m6-f-p6-b-provider-governance-plan-v1";

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final List<String> CONFIGURED_ROLLBACK_STEPS = List.of(
        "AI_ROLLBACK_STEP_DISABLE_EXISTING_RUNTIME_FLAG",
        "AI_ROLLBACK_STEP_REDEPLOY_THROUGH_ESTABLISHED_RELEASE_PROCESS",
        "AI_ROLLBACK_STEP_VERIFY_READ_ONLY_GOVERNANCE_SNAPSHOT"
    );
    private static final List<String> DISABLED_ROLLBACK_STEPS = List.of(
        "AI_ROLLBACK_STEP_NO_ACTION_REQUIRED_RUNTIME_ALREADY_DISABLED"
    );

    private ControlledAutomationGovernancePlanContracts() {
    }

    public enum Operation {
        CANARY,
        ROLLOUT,
        ROLLBACK
    }

    public enum Mode {
        NON_EXECUTABLE_REVIEW_ONLY
    }

    public enum Status {
        REVIEW_READY,
        BLOCKED
    }

    public enum TargetRuntimeState {
        UNCHANGED,
        DISABLED
    }

    public enum RollbackMechanism {
        NONE,
        ALREADY_DISABLED,
        DISABLE_RUNTIME_FLAG_AND_REDEPLOY
    }

    public record ReviewPlan(
        String planVersion,
        Instant observedAt,
        Operation operation,
        Mode mode,
        Status status,
        String sourceSnapshotHash,
        RuntimeState sourceRuntimeState,
        TargetRuntimeState targetRuntimeState,
        List<InventoryEntry> inventory,
        int plannedTrafficPercent,
        RollbackMechanism rollbackMechanism,
        List<String> blockerCodes,
        List<String> operatorStepCodes,
        String actionWhitelistState,
        String p5Decision,
        boolean productionReauthenticationAvailable,
        boolean providerInvocationAuthorized,
        boolean secretResolutionAuthorized,
        boolean trafficMutationAuthorized,
        boolean configurationMutationAuthorized,
        boolean deploymentAuthorized,
        boolean applyAuthorized,
        boolean commandExecutionAuthorized,
        boolean automaticRetryAuthorized,
        String evidenceHash
    ) {
        public ReviewPlan {
            planVersion = requireText(planVersion, "planVersion", 160);
            observedAt = Objects.requireNonNull(observedAt, "observedAt must not be null");
            operation = Objects.requireNonNull(operation, "operation must not be null");
            mode = Objects.requireNonNull(mode, "mode must not be null");
            status = Objects.requireNonNull(status, "status must not be null");
            sourceSnapshotHash = requireSha256(sourceSnapshotHash, "sourceSnapshotHash");
            sourceRuntimeState = Objects.requireNonNull(
                sourceRuntimeState,
                "sourceRuntimeState must not be null"
            );
            targetRuntimeState = Objects.requireNonNull(
                targetRuntimeState,
                "targetRuntimeState must not be null"
            );
            inventory = normalizeInventory(inventory);
            rollbackMechanism = Objects.requireNonNull(
                rollbackMechanism,
                "rollbackMechanism must not be null"
            );
            blockerCodes = normalizeCodes(blockerCodes, "blockerCode", 16);
            operatorStepCodes = normalizeSteps(operatorStepCodes);
            actionWhitelistState = requireText(
                actionWhitelistState,
                "actionWhitelistState",
                160
            );
            p5Decision = requireText(p5Decision, "p5Decision", 160);
            evidenceHash = requireSha256(evidenceHash, "evidenceHash");

            requireNoAuthority(
                productionReauthenticationAvailable,
                providerInvocationAuthorized,
                secretResolutionAuthorized,
                trafficMutationAuthorized,
                configurationMutationAuthorized,
                deploymentAuthorized,
                applyAuthorized,
                commandExecutionAuthorized,
                automaticRetryAuthorized
            );
            requireFrozenAutomationBoundary(actionWhitelistState, p5Decision);
            if (mode != Mode.NON_EXECUTABLE_REVIEW_ONLY || plannedTrafficPercent != 0) {
                throw new IllegalArgumentException(
                    "P6-B plans must remain review-only with zero traffic mutation"
                );
            }
            if ((status == Status.BLOCKED) != !blockerCodes.isEmpty()) {
                throw new IllegalArgumentException(
                    "BLOCKED plans require blockers and REVIEW_READY plans cannot contain blockers"
                );
            }
            validateOperation(
                operation,
                status,
                sourceRuntimeState,
                targetRuntimeState,
                rollbackMechanism,
                operatorStepCodes
            );
            String computed = evidence(
                planVersion,
                observedAt,
                operation,
                mode,
                status,
                sourceSnapshotHash,
                sourceRuntimeState,
                targetRuntimeState,
                inventory,
                plannedTrafficPercent,
                rollbackMechanism,
                blockerCodes,
                operatorStepCodes,
                actionWhitelistState,
                p5Decision
            );
            if (!evidenceHash.equals(computed)) {
                throw new IllegalArgumentException("evidenceHash must match the exact plan");
            }
        }

        public static ReviewPlan preview(Operation operation, OperationsView source) {
            Objects.requireNonNull(operation, "operation must not be null");
            Objects.requireNonNull(source, "source must not be null");
            List<InventoryEntry> inventory = normalizeInventory(source.inventory());
            List<String> blockers = blockers(operation, source.runtimeState());
            Status status = blockers.isEmpty() ? Status.REVIEW_READY : Status.BLOCKED;
            TargetRuntimeState target = operation == Operation.ROLLBACK
                ? TargetRuntimeState.DISABLED
                : TargetRuntimeState.UNCHANGED;
            RollbackMechanism rollback = resolveRollbackMechanism(
                operation,
                source.runtimeState()
            );
            List<String> steps = operatorSteps(operation, source.runtimeState());
            String evidenceHash = evidence(
                PLAN_VERSION,
                source.observedAt(),
                operation,
                Mode.NON_EXECUTABLE_REVIEW_ONLY,
                status,
                source.evidenceHash(),
                source.runtimeState(),
                target,
                inventory,
                0,
                rollback,
                blockers,
                steps,
                source.actionWhitelistState(),
                source.p5Decision()
            );
            return new ReviewPlan(
                PLAN_VERSION,
                source.observedAt(),
                operation,
                Mode.NON_EXECUTABLE_REVIEW_ONLY,
                status,
                source.evidenceHash(),
                source.runtimeState(),
                target,
                inventory,
                0,
                rollback,
                blockers,
                steps,
                source.actionWhitelistState(),
                source.p5Decision(),
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                evidenceHash
            );
        }
    }

    private static List<String> blockers(Operation operation, RuntimeState runtimeState) {
        if (operation == Operation.ROLLBACK) {
            return List.of();
        }
        List<String> blockers = new ArrayList<>();
        if (runtimeState == RuntimeState.NOT_CONFIGURED) {
            blockers.add("AI_PROVIDER_RUNTIME_NOT_CONFIGURED");
        }
        blockers.add("AI_PRODUCTION_REAUTHENTICATION_UNAVAILABLE");
        blockers.add("AI_PROVIDER_SECOND_VERSION_NOT_AVAILABLE");
        if (operation == Operation.CANARY) {
            blockers.add("AI_PROVIDER_CANARY_RUNTIME_NOT_IMPLEMENTED");
            blockers.add("AI_PROVIDER_TRAFFIC_MUTATION_NOT_AVAILABLE");
        } else {
            blockers.add("AI_PROVIDER_CANARY_EVIDENCE_NOT_AVAILABLE");
            blockers.add("AI_PROVIDER_ROLLOUT_MUTATION_NOT_AVAILABLE");
        }
        return normalizeCodes(blockers, "blockerCode", 16);
    }

    private static RollbackMechanism resolveRollbackMechanism(
        Operation operation,
        RuntimeState runtimeState
    ) {
        if (operation != Operation.ROLLBACK) {
            return RollbackMechanism.NONE;
        }
        return runtimeState == RuntimeState.NOT_CONFIGURED
            ? RollbackMechanism.ALREADY_DISABLED
            : RollbackMechanism.DISABLE_RUNTIME_FLAG_AND_REDEPLOY;
    }

    private static List<String> operatorSteps(
        Operation operation,
        RuntimeState runtimeState
    ) {
        if (operation != Operation.ROLLBACK) {
            return List.of();
        }
        return runtimeState == RuntimeState.NOT_CONFIGURED
            ? DISABLED_ROLLBACK_STEPS
            : CONFIGURED_ROLLBACK_STEPS;
    }

    private static void validateOperation(
        Operation operation,
        Status status,
        RuntimeState sourceRuntimeState,
        TargetRuntimeState targetRuntimeState,
        RollbackMechanism rollbackMechanism,
        List<String> operatorStepCodes
    ) {
        if (operation != Operation.ROLLBACK) {
            if (status != Status.BLOCKED
                || targetRuntimeState != TargetRuntimeState.UNCHANGED
                || rollbackMechanism != RollbackMechanism.NONE
                || !operatorStepCodes.isEmpty()) {
                throw new IllegalArgumentException(
                    "Canary and Rollout plans must remain blocked and non-mutating"
                );
            }
            return;
        }
        if (status != Status.REVIEW_READY
            || targetRuntimeState != TargetRuntimeState.DISABLED) {
            throw new IllegalArgumentException(
                "Rollback review must target the disabled runtime without apply authority"
            );
        }
        if (sourceRuntimeState == RuntimeState.NOT_CONFIGURED) {
            if (rollbackMechanism != RollbackMechanism.ALREADY_DISABLED
                || !operatorStepCodes.equals(DISABLED_ROLLBACK_STEPS)) {
                throw new IllegalArgumentException(
                    "disabled runtime rollback must require no release action"
                );
            }
            return;
        }
        if (rollbackMechanism != RollbackMechanism.DISABLE_RUNTIME_FLAG_AND_REDEPLOY
            || !operatorStepCodes.equals(CONFIGURED_ROLLBACK_STEPS)) {
            throw new IllegalArgumentException(
                "configured runtime rollback must use the established release process"
            );
        }
    }

    private static void requireNoAuthority(boolean... values) {
        for (boolean value : values) {
            if (value) {
                throw new IllegalArgumentException(
                    "P6-B plans cannot grant runtime, deployment or command authority"
                );
            }
        }
    }

    private static void requireFrozenAutomationBoundary(
        String actionWhitelistState,
        String p5Decision
    ) {
        if (!ControlledAutomationGovernanceReadContracts.EMPTY_ACTION_WHITELIST.equals(
                actionWhitelistState
            )
            || !ControlledAutomationGovernanceReadContracts.P5_SKIPPED.equals(p5Decision)) {
            throw new IllegalArgumentException(
                "P6-B must preserve the empty Action Whitelist and skipped P5-A decision"
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
                "P6-B plan must retain exactly the three closed assistance capabilities"
            );
        }
        return normalized;
    }

    private static List<String> normalizeCodes(
        List<String> values,
        String name,
        int maximumSize
    ) {
        List<String> normalized = values == null
            ? List.of()
            : values.stream()
                .map(value -> requireText(value, name, 160))
                .distinct()
                .sorted()
                .toList();
        if (normalized.size() > maximumSize) {
            throw new IllegalArgumentException(name + " values must be bounded");
        }
        return normalized;
    }

    private static List<String> normalizeSteps(List<String> values) {
        List<String> normalized = values == null
            ? List.of()
            : values.stream()
                .map(value -> requireText(value, "operatorStepCode", 160))
                .toList();
        if (normalized.size() > 8 || normalized.stream().distinct().count() != normalized.size()) {
            throw new IllegalArgumentException("operatorStepCodes must be unique and bounded");
        }
        return normalized;
    }

    private static String evidence(
        String planVersion,
        Instant observedAt,
        Operation operation,
        Mode mode,
        Status status,
        String sourceSnapshotHash,
        RuntimeState sourceRuntimeState,
        TargetRuntimeState targetRuntimeState,
        List<InventoryEntry> inventory,
        int plannedTrafficPercent,
        RollbackMechanism rollbackMechanism,
        List<String> blockerCodes,
        List<String> operatorStepCodes,
        String actionWhitelistState,
        String p5Decision
    ) {
        StringBuilder canonical = new StringBuilder("m6-f-p6-b-provider-governance-plan\n");
        append(canonical, planVersion);
        append(canonical, observedAt.toString());
        append(canonical, operation.name());
        append(canonical, mode.name());
        append(canonical, status.name());
        append(canonical, sourceSnapshotHash);
        append(canonical, sourceRuntimeState.name());
        append(canonical, targetRuntimeState.name());
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
        append(canonical, Integer.toString(plannedTrafficPercent));
        append(canonical, rollbackMechanism.name());
        for (String blocker : normalizeCodes(blockerCodes, "blockerCode", 16)) {
            append(canonical, blocker);
        }
        for (String step : normalizeSteps(operatorStepCodes)) {
            append(canonical, step);
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
