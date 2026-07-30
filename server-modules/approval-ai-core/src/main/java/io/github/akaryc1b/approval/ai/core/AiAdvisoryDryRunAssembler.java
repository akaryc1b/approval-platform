package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Builds a deterministic route plan from an accepted preflight without invoking a Provider. */
public final class AiAdvisoryDryRunAssembler {

    public AiAdvisoryDryRunReport assemble(
        AiAdvisoryConfigurationSnapshot snapshot,
        AiAdvisoryPreflightReport preflight
    ) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(preflight, "preflight must not be null");

        List<String> blockingCodes = new ArrayList<>();
        if (!snapshot.snapshotId().equals(preflight.snapshotId())
            || !snapshot.snapshotVersion().equals(preflight.snapshotVersion())
            || !snapshot.declaredContentHash().equals(preflight.declaredContentHash())
            || !snapshot.computedContentHash().equals(preflight.computedContentHash())) {
            blockingCodes.add("AI_PREFLIGHT_SNAPSHOT_MISMATCH");
        }
        blockingCodes.addAll(preflight.issues().stream()
            .filter(issue -> issue.severity() == AiAdvisoryPreflightReport.Severity.ERROR)
            .map(AiAdvisoryPreflightReport.Issue::code)
            .toList());

        if (!blockingCodes.isEmpty()
            || preflight.status() == AiAdvisoryPreflightReport.Status.BLOCKED) {
            return report(
                snapshot,
                AiAdvisoryDryRunReport.Status.BLOCKED,
                List.of(),
                blockingCodes.isEmpty()
                    ? List.of("AI_PREFLIGHT_BLOCKED")
                    : blockingCodes
            );
        }
        if (preflight.status() == AiAdvisoryPreflightReport.Status.DISABLED) {
            return report(
                snapshot,
                AiAdvisoryDryRunReport.Status.DISABLED,
                List.of(),
                List.of()
            );
        }

        List<AiAdvisoryDryRunReport.CapabilityPlan> plans = new ArrayList<>();
        for (AiCapability capability : AiCapability.values()) {
            List<String> candidates = snapshot.routingPolicy()
                .orderedRoutes(capability)
                .stream()
                .map(AiProviderRoute::routeId)
                .toList();
            if (!candidates.isEmpty()) {
                plans.add(new AiAdvisoryDryRunReport.CapabilityPlan(
                    capability,
                    candidates.get(0),
                    candidates
                ));
            }
        }
        plans.sort(Comparator.comparing(plan -> plan.capability().name()));
        if (plans.isEmpty()) {
            return report(
                snapshot,
                AiAdvisoryDryRunReport.Status.BLOCKED,
                List.of(),
                List.of("AI_DRY_RUN_NO_CAPABILITY_PLAN")
            );
        }
        return report(
            snapshot,
            AiAdvisoryDryRunReport.Status.READY,
            plans,
            List.of()
        );
    }

    private static AiAdvisoryDryRunReport report(
        AiAdvisoryConfigurationSnapshot snapshot,
        AiAdvisoryDryRunReport.Status status,
        List<AiAdvisoryDryRunReport.CapabilityPlan> plans,
        List<String> blockingCodes
    ) {
        return new AiAdvisoryDryRunReport(
            snapshot.snapshotId(),
            snapshot.snapshotVersion(),
            snapshot.computedContentHash(),
            status,
            plans,
            blockingCodes,
            false,
            false,
            false
        );
    }
}
