package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Zero-call assembly plan. A ready plan still carries no production or automation authority. */
public record AiAdvisoryDryRunReport(
    String snapshotId,
    String snapshotVersion,
    String configurationHash,
    Status status,
    List<CapabilityPlan> capabilityPlans,
    List<String> blockingCodes,
    boolean providerInvocationAttempted,
    boolean productionEnablementAuthorized,
    boolean approvalAutomationAuthorized
) {

    public AiAdvisoryDryRunReport {
        snapshotId = requireText(snapshotId, "snapshotId", 160);
        snapshotVersion = requireText(snapshotVersion, "snapshotVersion", 120);
        configurationHash = requireText(configurationHash, "configurationHash", 64);
        status = Objects.requireNonNull(status, "status must not be null");
        capabilityPlans = capabilityPlans == null ? List.of() : List.copyOf(capabilityPlans);
        blockingCodes = blockingCodes == null
            ? List.of()
            : blockingCodes.stream()
                .map(value -> requireText(value, "blockingCode", 120))
                .distinct()
                .sorted()
                .toList();
        if (capabilityPlans.size() > AiCapability.values().length) {
            throw new IllegalArgumentException("capability plans must be bounded");
        }
        if (providerInvocationAttempted) {
            throw new IllegalArgumentException("AI dry-run assembly cannot invoke a Provider");
        }
        if (productionEnablementAuthorized) {
            throw new IllegalArgumentException(
                "AI dry-run assembly cannot authorize production enablement"
            );
        }
        if (approvalAutomationAuthorized) {
            throw new IllegalArgumentException(
                "AI dry-run assembly cannot authorize approval automation"
            );
        }
        if (status == Status.READY && (!blockingCodes.isEmpty() || capabilityPlans.isEmpty())) {
            throw new IllegalArgumentException(
                "READY dry-run requires capability plans and no blocking codes"
            );
        }
        if (status == Status.BLOCKED && blockingCodes.isEmpty()) {
            throw new IllegalArgumentException("BLOCKED dry-run requires blocking codes");
        }
    }

    public enum Status {
        READY,
        DISABLED,
        BLOCKED
    }

    public record CapabilityPlan(
        AiCapability capability,
        String primaryRouteId,
        List<String> orderedCandidateRouteIds
    ) {
        public CapabilityPlan {
            capability = Objects.requireNonNull(capability, "capability must not be null");
            primaryRouteId = requireText(primaryRouteId, "primaryRouteId", 120);
            orderedCandidateRouteIds = orderedCandidateRouteIds == null
                ? List.of()
                : orderedCandidateRouteIds.stream()
                    .map(value -> requireText(value, "routeId", 120))
                    .toList();
            if (orderedCandidateRouteIds.isEmpty()
                || !primaryRouteId.equals(orderedCandidateRouteIds.get(0))) {
                throw new IllegalArgumentException(
                    "primary route must be the first ordered candidate"
                );
            }
            Set<String> unique = new HashSet<>(orderedCandidateRouteIds);
            if (unique.size() != orderedCandidateRouteIds.size()) {
                throw new IllegalArgumentException("ordered candidate route IDs must be unique");
            }
        }
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
