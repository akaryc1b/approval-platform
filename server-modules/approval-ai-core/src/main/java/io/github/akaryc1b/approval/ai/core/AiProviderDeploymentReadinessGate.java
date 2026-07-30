package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiProviderProtocolProfile;
import io.github.akaryc1b.approval.ai.spi.AiProviderProtocolValidationRequest;
import io.github.akaryc1b.approval.ai.spi.AiProviderProtocolValidationResult;
import io.github.akaryc1b.approval.ai.spi.AiProviderProtocolValidator;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Validates deployment references, endpoint allowlists and protocol metadata without resolving a
 * secret, opening a connection or invoking an AI Provider.
 */
public final class AiProviderDeploymentReadinessGate {

    public AiProviderDeploymentReadinessReport inspect(
        AiAdvisoryConfigurationSnapshot advisorySnapshot,
        AiAdvisoryPreflightReport advisoryPreflight,
        AiProviderDeploymentSnapshot deploymentSnapshot,
        AiProviderProtocolValidatorRegistry validatorRegistry
    ) {
        Objects.requireNonNull(advisorySnapshot, "advisorySnapshot must not be null");
        Objects.requireNonNull(advisoryPreflight, "advisoryPreflight must not be null");
        Objects.requireNonNull(deploymentSnapshot, "deploymentSnapshot must not be null");
        Objects.requireNonNull(validatorRegistry, "validatorRegistry must not be null");

        List<AiProviderDeploymentReadinessReport.Issue> issues = new ArrayList<>();
        List<AiProviderDeploymentReadinessReport.RouteDeploymentCheck> checks =
            new ArrayList<>();

        validateSnapshotLineage(
            advisorySnapshot,
            advisoryPreflight,
            deploymentSnapshot,
            issues
        );

        if (advisoryPreflight.status() == AiAdvisoryPreflightReport.Status.BLOCKED) {
            issues.add(issue(
                AiProviderDeploymentReadinessReport.FaultClass.ADVISORY_PREFLIGHT_BLOCKED,
                "AI_ADVISORY_PREFLIGHT_BLOCKED",
                "deployment readiness cannot proceed from a blocked advisory preflight",
                null,
                null
            ));
            return report(
                advisorySnapshot,
                deploymentSnapshot,
                AiProviderDeploymentReadinessReport.Status.BLOCKED,
                checks,
                issues
            );
        }

        if (!advisorySnapshot.routingPolicy().enabled()
            || advisoryPreflight.status() == AiAdvisoryPreflightReport.Status.DISABLED) {
            AiProviderDeploymentReadinessReport.Status status = issues.isEmpty()
                ? AiProviderDeploymentReadinessReport.Status.DISABLED
                : AiProviderDeploymentReadinessReport.Status.BLOCKED;
            return report(advisorySnapshot, deploymentSnapshot, status, checks, issues);
        }

        int readyRoutes = 0;
        int disabledRoutes = 0;
        List<AiProviderRoute> routes = advisorySnapshot.routingPolicy().routes().stream()
            .filter(AiProviderRoute::enabled)
            .sorted(Comparator.comparing(AiProviderRoute::routeId))
            .toList();
        for (AiProviderRoute route : routes) {
            List<String> routeCodes = new ArrayList<>();
            AiProviderDeploymentSnapshot.DeploymentBinding binding =
                deploymentSnapshot.bindings().get(route.versions().provider());
            if (binding == null) {
                addRouteIssue(
                    issues,
                    routeCodes,
                    AiProviderDeploymentReadinessReport.FaultClass.DEPLOYMENT_BINDING_MISSING,
                    "AI_DEPLOYMENT_BINDING_MISSING",
                    "exact Provider deployment binding is not registered",
                    route
                );
                checks.add(check(route, null, routeCodes));
                continue;
            }
            if (binding.stage() == AiProviderDeploymentSnapshot.OperationalStage.DISABLED) {
                disabledRoutes++;
                routeCodes.add("AI_DEPLOYMENT_BINDING_DISABLED");
                checks.add(new AiProviderDeploymentReadinessReport.RouteDeploymentCheck(
                    route.routeId(),
                    route.versions().provider(),
                    AiProviderDeploymentReadinessReport.RouteStatus.DISABLED,
                    binding.endpointId(),
                    binding.egressPolicyAuthorizationKey(),
                    binding.validationProfileAuthorizationKey(),
                    List.copyOf(routeCodes)
                ));
                continue;
            }

            AiProviderEndpointDescriptor endpoint = deploymentSnapshot.endpoints()
                .get(binding.endpointId());
            if (endpoint == null) {
                addRouteIssue(
                    issues,
                    routeCodes,
                    AiProviderDeploymentReadinessReport.FaultClass.ENDPOINT_NOT_REGISTERED,
                    "AI_PROVIDER_ENDPOINT_NOT_REGISTERED",
                    "exact Provider endpoint metadata is not registered",
                    route
                );
            } else if (!endpoint.providerVersion().equals(route.versions().provider())) {
                addRouteIssue(
                    issues,
                    routeCodes,
                    AiProviderDeploymentReadinessReport.FaultClass.ENDPOINT_PROVIDER_MISMATCH,
                    "AI_PROVIDER_ENDPOINT_VERSION_MISMATCH",
                    "Provider endpoint metadata does not match the exact route Provider version",
                    route
                );
            }

            AiProviderEgressPolicy egress = deploymentSnapshot.egressPolicies()
                .get(binding.egressPolicyAuthorizationKey());
            if (egress == null) {
                addRouteIssue(
                    issues,
                    routeCodes,
                    AiProviderDeploymentReadinessReport.FaultClass.EGRESS_POLICY_NOT_REGISTERED,
                    "AI_EGRESS_POLICY_NOT_REGISTERED",
                    "exact Provider egress policy metadata is not registered",
                    route
                );
            } else {
                if (!egress.contentHashMatches()) {
                    addRouteIssue(
                        issues,
                        routeCodes,
                        AiProviderDeploymentReadinessReport.FaultClass.EGRESS_POLICY_HASH_MISMATCH,
                        "AI_EGRESS_POLICY_HASH_MISMATCH",
                        "declared egress policy hash does not match its deterministic metadata",
                        route
                    );
                }
                if (endpoint != null && !egress.allows(endpoint)) {
                    addRouteIssue(
                        issues,
                        routeCodes,
                        AiProviderDeploymentReadinessReport.FaultClass.ENDPOINT_NOT_ALLOWLISTED,
                        "AI_PROVIDER_ENDPOINT_NOT_ALLOWLISTED",
                        "exact Provider endpoint is not authorized by the egress allowlist",
                        route
                    );
                }
            }

            boolean authenticationReferencePresent = false;
            for (String secretKey : binding.secretReferenceAuthorizationKeys().stream()
                .sorted().toList()) {
                AiExternalSecretReference reference = deploymentSnapshot.secretReferences()
                    .get(secretKey);
                if (reference == null) {
                    addRouteIssue(
                        issues,
                        routeCodes,
                        AiProviderDeploymentReadinessReport.FaultClass
                            .SECRET_REFERENCE_NOT_REGISTERED,
                        "AI_SECRET_REFERENCE_NOT_REGISTERED",
                        "external Provider secret reference metadata is not registered",
                        route
                    );
                    continue;
                }
                if (!reference.providerVersion().equals(route.versions().provider())) {
                    addRouteIssue(
                        issues,
                        routeCodes,
                        AiProviderDeploymentReadinessReport.FaultClass.SECRET_PROVIDER_MISMATCH,
                        "AI_SECRET_REFERENCE_PROVIDER_MISMATCH",
                        "secret reference does not match the exact route Provider version",
                        route
                    );
                }
                if (reference.rotationState()
                    != AiExternalSecretReference.RotationState.CURRENT) {
                    addRouteIssue(
                        issues,
                        routeCodes,
                        AiProviderDeploymentReadinessReport.FaultClass.SECRET_ROTATION_BLOCKED,
                        "AI_SECRET_REFERENCE_ROTATION_BLOCKED",
                        "secret reference rotation state is not CURRENT",
                        route
                    );
                }
                authenticationReferencePresent = authenticationReferencePresent
                    || reference.purposes().contains(
                        AiExternalSecretReference.Purpose.PROVIDER_AUTHENTICATION
                    );
            }
            if (!authenticationReferencePresent) {
                addRouteIssue(
                    issues,
                    routeCodes,
                    AiProviderDeploymentReadinessReport.FaultClass
                        .PROVIDER_AUTHENTICATION_REFERENCE_MISSING,
                    "AI_PROVIDER_AUTHENTICATION_REFERENCE_MISSING",
                    "deployment binding has no Provider authentication secret reference",
                    route
                );
            }

            validateProtocol(
                deploymentSnapshot,
                validatorRegistry,
                route,
                binding,
                routeCodes,
                issues
            );

            AiProviderDeploymentReadinessReport.RouteStatus routeStatus = routeCodes.isEmpty()
                ? AiProviderDeploymentReadinessReport.RouteStatus.READY
                : AiProviderDeploymentReadinessReport.RouteStatus.BLOCKED;
            if (routeStatus == AiProviderDeploymentReadinessReport.RouteStatus.READY) {
                readyRoutes++;
            }
            checks.add(new AiProviderDeploymentReadinessReport.RouteDeploymentCheck(
                route.routeId(),
                route.versions().provider(),
                routeStatus,
                binding.endpointId(),
                binding.egressPolicyAuthorizationKey(),
                binding.validationProfileAuthorizationKey(),
                routeCodes.stream().distinct().sorted().toList()
            ));
        }

        if (readyRoutes == 0 && disabledRoutes != routes.size()) {
            issues.add(issue(
                AiProviderDeploymentReadinessReport.FaultClass.NO_READY_DEPLOYMENT_ROUTE,
                "AI_NO_READY_DEPLOYMENT_ROUTE",
                "enabled AI routing contains no deployment route ready for offline fault drills",
                null,
                null
            ));
        }

        issues = issues.stream()
            .sorted(Comparator
                .comparing((AiProviderDeploymentReadinessReport.Issue value) ->
                    value.routeId() == null ? "" : value.routeId())
                .thenComparing(value -> value.providerVersion() == null
                    ? ""
                    : value.providerVersion().providerId() + "/" + value.providerVersion().version())
                .thenComparing(AiProviderDeploymentReadinessReport.Issue::code))
            .toList();
        AiProviderDeploymentReadinessReport.Status status;
        if (!issues.isEmpty()) {
            status = AiProviderDeploymentReadinessReport.Status.BLOCKED;
        } else if (readyRoutes > 0) {
            status = AiProviderDeploymentReadinessReport.Status.READY_FOR_FAULT_DRILL;
        } else {
            status = AiProviderDeploymentReadinessReport.Status.DISABLED;
        }
        return report(advisorySnapshot, deploymentSnapshot, status, checks, issues);
    }

    private static void validateSnapshotLineage(
        AiAdvisoryConfigurationSnapshot advisorySnapshot,
        AiAdvisoryPreflightReport advisoryPreflight,
        AiProviderDeploymentSnapshot deploymentSnapshot,
        List<AiProviderDeploymentReadinessReport.Issue> issues
    ) {
        boolean advisoryMismatch = !advisorySnapshot.snapshotId()
            .equals(advisoryPreflight.snapshotId())
            || !advisorySnapshot.snapshotVersion().equals(advisoryPreflight.snapshotVersion())
            || !advisorySnapshot.declaredContentHash()
                .equals(advisoryPreflight.declaredContentHash())
            || !advisorySnapshot.computedContentHash()
                .equals(advisoryPreflight.computedContentHash())
            || !advisorySnapshot.declaredContentHash()
                .equals(deploymentSnapshot.advisoryConfigurationHash());
        if (advisoryMismatch) {
            issues.add(issue(
                AiProviderDeploymentReadinessReport.FaultClass
                    .ADVISORY_CONFIGURATION_MISMATCH,
                "AI_ADVISORY_CONFIGURATION_MISMATCH",
                "deployment snapshot and advisory preflight do not reference the same exact configuration",
                null,
                null
            ));
        }
        if (!deploymentSnapshot.contentHashMatches()) {
            issues.add(issue(
                AiProviderDeploymentReadinessReport.FaultClass.DEPLOYMENT_HASH_MISMATCH,
                "AI_DEPLOYMENT_CONFIGURATION_HASH_MISMATCH",
                "declared deployment hash does not match deterministic deployment metadata",
                null,
                null
            ));
        }
    }

    private static void validateProtocol(
        AiProviderDeploymentSnapshot deploymentSnapshot,
        AiProviderProtocolValidatorRegistry validatorRegistry,
        AiProviderRoute route,
        AiProviderDeploymentSnapshot.DeploymentBinding binding,
        List<String> routeCodes,
        List<AiProviderDeploymentReadinessReport.Issue> issues
    ) {
        AiProviderProtocolProfile profile = deploymentSnapshot.validationProfiles()
            .get(binding.validationProfileAuthorizationKey());
        if (profile == null) {
            addRouteIssue(
                issues,
                routeCodes,
                AiProviderDeploymentReadinessReport.FaultClass
                    .VALIDATION_PROFILE_NOT_REGISTERED,
                "AI_PROVIDER_VALIDATION_PROFILE_NOT_REGISTERED",
                "exact Provider protocol validation profile is not registered",
                route
            );
            return;
        }
        if (!profile.providerVersion().equals(route.versions().provider())
            || route.capabilities().stream().anyMatch(capability -> !profile.supports(capability))) {
            addRouteIssue(
                issues,
                routeCodes,
                AiProviderDeploymentReadinessReport.FaultClass.VALIDATION_PROFILE_MISMATCH,
                "AI_PROVIDER_VALIDATION_PROFILE_MISMATCH",
                "Provider validation profile does not authorize the exact route and capabilities",
                route
            );
            return;
        }

        int estimatedRequestBytes = boundedUtf8Estimate(
            route.budget().maximumInputCharacters()
        );
        if (estimatedRequestBytes > profile.maximumRequestBytes()) {
            addRouteIssue(
                issues,
                routeCodes,
                AiProviderDeploymentReadinessReport.FaultClass.VALIDATION_PROFILE_MISMATCH,
                "AI_PROVIDER_REQUEST_SIZE_EXCEEDS_VALIDATION_PROFILE",
                "route request estimate exceeds the Provider validation profile limit",
                route
            );
            return;
        }

        AiProviderProtocolValidator validator = validatorRegistry
            .find(binding.validationProfileAuthorizationKey())
            .orElse(null);
        if (validator == null) {
            addRouteIssue(
                issues,
                routeCodes,
                AiProviderDeploymentReadinessReport.FaultClass.VALIDATOR_NOT_REGISTERED,
                "AI_PROVIDER_PROTOCOL_VALIDATOR_NOT_REGISTERED",
                "exact Provider protocol validator is not registered",
                route
            );
            return;
        }
        if (!validator.profile().equals(profile)) {
            addRouteIssue(
                issues,
                routeCodes,
                AiProviderDeploymentReadinessReport.FaultClass.VALIDATION_PROFILE_MISMATCH,
                "AI_PROVIDER_PROTOCOL_VALIDATOR_PROFILE_MISMATCH",
                "registered Provider validator does not expose the exact configured profile",
                route
            );
            return;
        }

        for (AiCapability capability : route.capabilities().stream().sorted().toList()) {
            AiProviderProtocolValidationRequest request =
                new AiProviderProtocolValidationRequest(
                    route.versions(),
                    capability,
                    binding.egressPolicyAuthorizationKey(),
                    estimatedRequestBytes,
                    profile.maximumResponseBytes(),
                    true,
                    false,
                    false,
                    false
                );
            AiProviderProtocolValidationResult result;
            try {
                result = Objects.requireNonNull(
                    validator.validate(request),
                    "validator result must not be null"
                );
            } catch (RuntimeException exception) {
                addRouteIssue(
                    issues,
                    routeCodes,
                    AiProviderDeploymentReadinessReport.FaultClass.VALIDATOR_EXCEPTION,
                    "AI_PROVIDER_PROTOCOL_VALIDATOR_EXCEPTION",
                    "Provider protocol validator failed without producing bounded evidence",
                    route
                );
                continue;
            }
            if (result.status() != AiProviderProtocolValidationResult.Status.VALID) {
                AiProviderDeploymentReadinessReport.FaultClass faultClass = switch (
                    result.status()
                ) {
                    case INVALID -> AiProviderDeploymentReadinessReport.FaultClass
                        .VALIDATION_REJECTED;
                    case UNSUPPORTED -> AiProviderDeploymentReadinessReport.FaultClass
                        .VALIDATION_UNSUPPORTED;
                    case UNKNOWN -> AiProviderDeploymentReadinessReport.FaultClass
                        .VALIDATION_UNKNOWN;
                    case VALID -> throw new IllegalStateException("unreachable VALID branch");
                };
                addRouteIssue(
                    issues,
                    routeCodes,
                    faultClass,
                    "AI_PROVIDER_PROTOCOL_VALIDATION_" + result.status().name(),
                    "Provider protocol validation did not produce VALID evidence",
                    route
                );
            }
            for (AiProviderProtocolValidationResult.Issue validatorIssue : result.issues()) {
                routeCodes.add(validatorIssue.code());
            }
        }
    }

    private static int boundedUtf8Estimate(int characters) {
        long estimate = (long) characters * 4L;
        return estimate > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) estimate;
    }

    private static AiProviderDeploymentReadinessReport.RouteDeploymentCheck check(
        AiProviderRoute route,
        AiProviderDeploymentSnapshot.DeploymentBinding binding,
        List<String> codes
    ) {
        return new AiProviderDeploymentReadinessReport.RouteDeploymentCheck(
            route.routeId(),
            route.versions().provider(),
            AiProviderDeploymentReadinessReport.RouteStatus.BLOCKED,
            binding == null ? null : binding.endpointId(),
            binding == null ? null : binding.egressPolicyAuthorizationKey(),
            binding == null ? null : binding.validationProfileAuthorizationKey(),
            codes.stream().distinct().sorted().toList()
        );
    }

    private static void addRouteIssue(
        List<AiProviderDeploymentReadinessReport.Issue> issues,
        List<String> routeCodes,
        AiProviderDeploymentReadinessReport.FaultClass faultClass,
        String code,
        String message,
        AiProviderRoute route
    ) {
        routeCodes.add(code);
        issues.add(issue(
            faultClass,
            code,
            message,
            route.routeId(),
            route.versions().provider()
        ));
    }

    private static AiProviderDeploymentReadinessReport.Issue issue(
        AiProviderDeploymentReadinessReport.FaultClass faultClass,
        String code,
        String message,
        String routeId,
        AiVersionReferences.ProviderVersion providerVersion
    ) {
        return new AiProviderDeploymentReadinessReport.Issue(
            faultClass,
            code,
            message,
            routeId,
            providerVersion,
            AiProviderDeploymentReadinessReport.Severity.ERROR
        );
    }

    private static AiProviderDeploymentReadinessReport report(
        AiAdvisoryConfigurationSnapshot advisorySnapshot,
        AiProviderDeploymentSnapshot deploymentSnapshot,
        AiProviderDeploymentReadinessReport.Status status,
        List<AiProviderDeploymentReadinessReport.RouteDeploymentCheck> checks,
        List<AiProviderDeploymentReadinessReport.Issue> issues
    ) {
        return new AiProviderDeploymentReadinessReport(
            advisorySnapshot.snapshotId(),
            advisorySnapshot.snapshotVersion(),
            advisorySnapshot.declaredContentHash(),
            deploymentSnapshot.snapshotId(),
            deploymentSnapshot.snapshotVersion(),
            deploymentSnapshot.declaredContentHash(),
            deploymentSnapshot.computedContentHash(),
            status,
            checks,
            issues,
            false,
            false,
            false,
            false,
            false
        );
    }
}
