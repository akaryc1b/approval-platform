package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiAdvisoryProvider;
import io.github.akaryc1b.approval.ai.spi.AiCapability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Validates one immutable AI configuration snapshot without invoking a Provider.
 *
 * <p>Every enabled route must resolve an exact Provider, exact artifact bundle and exact data
 * minimization policy. Any mismatch blocks the whole snapshot.</p>
 */
public final class AiAdvisoryStartupPreflight {

    public AiAdvisoryPreflightReport inspect(
        AiAdvisoryConfigurationSnapshot snapshot,
        AiProviderRegistry providerRegistry,
        AiAdvisoryArtifactRegistry artifactRegistry
    ) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(providerRegistry, "providerRegistry must not be null");
        Objects.requireNonNull(artifactRegistry, "artifactRegistry must not be null");

        List<AiAdvisoryPreflightReport.Issue> issues = new ArrayList<>();
        List<AiAdvisoryPreflightReport.RouteCheck> routeChecks = new ArrayList<>();
        if (!snapshot.contentHashMatches()) {
            issues.add(issue(
                "AI_CONFIGURATION_HASH_MISMATCH",
                "declared AI configuration hash does not match the deterministic snapshot content",
                null,
                null
            ));
        }

        if (!snapshot.routingPolicy().enabled()) {
            AiAdvisoryPreflightReport.Status status = issues.isEmpty()
                ? AiAdvisoryPreflightReport.Status.DISABLED
                : AiAdvisoryPreflightReport.Status.BLOCKED;
            return report(snapshot, status, routeChecks, issues);
        }

        List<AiProviderRoute> routes = snapshot.routingPolicy().routes().stream()
            .sorted(Comparator.comparing(AiProviderRoute::routeId))
            .toList();
        int readyRoutes = 0;
        for (AiProviderRoute route : routes) {
            if (!route.enabled()) {
                routeChecks.add(new AiAdvisoryPreflightReport.RouteCheck(
                    route.routeId(),
                    AiAdvisoryPreflightReport.RouteStatus.DISABLED,
                    route.capabilities(),
                    List.of("AI_ROUTE_DISABLED")
                ));
                continue;
            }

            List<String> routeCodes = new ArrayList<>();
            AiDataMinimizationPolicy dataPolicy = snapshot.dataPolicy(route.versions().policy());
            if (dataPolicy == null) {
                addRouteIssue(
                    issues,
                    routeCodes,
                    "AI_DATA_POLICY_NOT_REGISTERED",
                    "exact data minimization policy is not present in the configuration snapshot",
                    route,
                    null
                );
            } else {
                if (route.budget().maximumInputCharacters()
                    > dataPolicy.limits().maximumTotalTextCharacters()) {
                    addRouteIssue(
                        issues,
                        routeCodes,
                        "AI_ROUTE_EXCEEDS_DATA_POLICY_CHARACTER_LIMIT",
                        "route character budget exceeds the exact data-minimization policy limit",
                        route,
                        null
                    );
                }
                if (route.budget().maximumInputFields()
                    > dataPolicy.limits().maximumFields()) {
                    addRouteIssue(
                        issues,
                        routeCodes,
                        "AI_ROUTE_EXCEEDS_DATA_POLICY_FIELD_LIMIT",
                        "route field budget exceeds the exact data-minimization policy limit",
                        route,
                        null
                    );
                }
            }

            AiAdvisoryProvider provider = providerRegistry
                .find(route.versions().provider())
                .orElse(null);
            if (provider == null) {
                addRouteIssue(
                    issues,
                    routeCodes,
                    "AI_PROVIDER_VERSION_NOT_REGISTERED",
                    "exact AI Provider version is not registered",
                    route,
                    null
                );
            }

            for (AiCapability capability : route.capabilities().stream().sorted().toList()) {
                AiAdvisoryArtifactRegistry.AuthorizationResult authorization =
                    artifactRegistry.authorize(route.versions(), capability);
                if (!authorization.allowed()) {
                    addRouteIssue(
                        issues,
                        routeCodes,
                        authorization.code(),
                        authorization.message(),
                        route,
                        capability
                    );
                }
            }

            if (provider != null && !providerRegistry.matches(provider, route)) {
                addRouteIssue(
                    issues,
                    routeCodes,
                    "AI_PROVIDER_ROUTE_NOT_AUTHORIZED",
                    "Provider descriptor, model, capability, budget or artifact metadata does not authorize the route",
                    route,
                    null
                );
            }

            AiAdvisoryPreflightReport.RouteStatus routeStatus = routeCodes.isEmpty()
                ? AiAdvisoryPreflightReport.RouteStatus.READY
                : AiAdvisoryPreflightReport.RouteStatus.BLOCKED;
            if (routeStatus == AiAdvisoryPreflightReport.RouteStatus.READY) {
                readyRoutes++;
            }
            routeChecks.add(new AiAdvisoryPreflightReport.RouteCheck(
                route.routeId(),
                routeStatus,
                route.capabilities(),
                routeCodes.stream().distinct().sorted().toList()
            ));
        }

        if (readyRoutes == 0) {
            issues.add(issue(
                "AI_NO_READY_ROUTE",
                "enabled AI routing configuration contains no fully authorized route",
                null,
                null
            ));
        }

        issues = issues.stream()
            .sorted(Comparator
                .comparing((AiAdvisoryPreflightReport.Issue value) -> value.routeId() == null
                    ? ""
                    : value.routeId())
                .thenComparing(value -> value.capability() == null
                    ? ""
                    : value.capability().name())
                .thenComparing(AiAdvisoryPreflightReport.Issue::code))
            .toList();
        AiAdvisoryPreflightReport.Status status = issues.isEmpty()
            ? AiAdvisoryPreflightReport.Status.READY_FOR_DRY_RUN
            : AiAdvisoryPreflightReport.Status.BLOCKED;
        return report(snapshot, status, routeChecks, issues);
    }

    private static void addRouteIssue(
        List<AiAdvisoryPreflightReport.Issue> issues,
        List<String> routeCodes,
        String code,
        String message,
        AiProviderRoute route,
        AiCapability capability
    ) {
        routeCodes.add(code);
        issues.add(issue(code, message, route.routeId(), capability));
    }

    private static AiAdvisoryPreflightReport.Issue issue(
        String code,
        String message,
        String routeId,
        AiCapability capability
    ) {
        return new AiAdvisoryPreflightReport.Issue(
            code,
            message,
            routeId,
            capability,
            AiAdvisoryPreflightReport.Severity.ERROR
        );
    }

    private static AiAdvisoryPreflightReport report(
        AiAdvisoryConfigurationSnapshot snapshot,
        AiAdvisoryPreflightReport.Status status,
        List<AiAdvisoryPreflightReport.RouteCheck> routeChecks,
        List<AiAdvisoryPreflightReport.Issue> issues
    ) {
        return new AiAdvisoryPreflightReport(
            snapshot.snapshotId(),
            snapshot.snapshotVersion(),
            snapshot.declaredContentHash(),
            snapshot.computedContentHash(),
            status,
            routeChecks,
            issues,
            false,
            false,
            false
        );
    }
}
