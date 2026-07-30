package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;

import java.util.List;
import java.util.Objects;

/** Non-authorizing deployment readiness evidence for offline fault drills only. */
public record AiProviderDeploymentReadinessReport(
    String advisorySnapshotId,
    String advisorySnapshotVersion,
    String advisoryConfigurationHash,
    String deploymentSnapshotId,
    String deploymentSnapshotVersion,
    String declaredDeploymentHash,
    String computedDeploymentHash,
    Status status,
    List<RouteDeploymentCheck> routeChecks,
    List<Issue> issues,
    boolean providerInvocationAttempted,
    boolean secretResolutionAttempted,
    boolean networkCallAttempted,
    boolean productionEnablementAuthorized,
    boolean approvalAutomationAuthorized
) {

    public AiProviderDeploymentReadinessReport {
        advisorySnapshotId = requireText(advisorySnapshotId, "advisorySnapshotId", 160);
        advisorySnapshotVersion = requireText(
            advisorySnapshotVersion,
            "advisorySnapshotVersion",
            120
        );
        advisoryConfigurationHash = requireText(
            advisoryConfigurationHash,
            "advisoryConfigurationHash",
            64
        );
        deploymentSnapshotId = requireText(deploymentSnapshotId, "deploymentSnapshotId", 160);
        deploymentSnapshotVersion = requireText(
            deploymentSnapshotVersion,
            "deploymentSnapshotVersion",
            120
        );
        declaredDeploymentHash = requireText(
            declaredDeploymentHash,
            "declaredDeploymentHash",
            64
        );
        computedDeploymentHash = requireText(
            computedDeploymentHash,
            "computedDeploymentHash",
            64
        );
        status = Objects.requireNonNull(status, "status must not be null");
        routeChecks = bounded(routeChecks, "routeChecks", 500);
        issues = bounded(issues, "issues", 500);
        if (providerInvocationAttempted || secretResolutionAttempted || networkCallAttempted) {
            throw new IllegalArgumentException(
                "deployment readiness must remain zero-call and secret-free"
            );
        }
        if (productionEnablementAuthorized) {
            throw new IllegalArgumentException(
                "deployment readiness cannot authorize production enablement"
            );
        }
        if (approvalAutomationAuthorized) {
            throw new IllegalArgumentException(
                "deployment readiness cannot authorize approval automation"
            );
        }
        boolean hasError = issues.stream().anyMatch(issue -> issue.severity() == Severity.ERROR);
        if (status == Status.READY_FOR_FAULT_DRILL && hasError) {
            throw new IllegalArgumentException(
                "READY_FOR_FAULT_DRILL cannot contain an ERROR issue"
            );
        }
        if (status == Status.BLOCKED && !hasError) {
            throw new IllegalArgumentException("BLOCKED readiness requires an ERROR issue");
        }
    }

    public enum Status {
        READY_FOR_FAULT_DRILL,
        DISABLED,
        BLOCKED
    }

    public enum RouteStatus {
        READY,
        DISABLED,
        BLOCKED
    }

    public enum Severity {
        WARNING,
        ERROR
    }

    public enum FaultClass {
        ADVISORY_PREFLIGHT_BLOCKED,
        DEPLOYMENT_HASH_MISMATCH,
        ADVISORY_CONFIGURATION_MISMATCH,
        DEPLOYMENT_BINDING_MISSING,
        ENDPOINT_NOT_REGISTERED,
        ENDPOINT_PROVIDER_MISMATCH,
        EGRESS_POLICY_NOT_REGISTERED,
        EGRESS_POLICY_HASH_MISMATCH,
        ENDPOINT_NOT_ALLOWLISTED,
        SECRET_REFERENCE_NOT_REGISTERED,
        SECRET_PROVIDER_MISMATCH,
        SECRET_ROTATION_BLOCKED,
        PROVIDER_AUTHENTICATION_REFERENCE_MISSING,
        VALIDATION_PROFILE_NOT_REGISTERED,
        VALIDATION_PROFILE_MISMATCH,
        VALIDATOR_NOT_REGISTERED,
        VALIDATION_REJECTED,
        VALIDATION_UNSUPPORTED,
        VALIDATION_UNKNOWN,
        VALIDATOR_EXCEPTION,
        NO_READY_DEPLOYMENT_ROUTE
    }

    public record RouteDeploymentCheck(
        String routeId,
        AiVersionReferences.ProviderVersion providerVersion,
        RouteStatus status,
        String endpointId,
        String egressPolicyAuthorizationKey,
        String validationProfileAuthorizationKey,
        List<String> codes
    ) {
        public RouteDeploymentCheck {
            routeId = requireText(routeId, "routeId", 120);
            providerVersion = Objects.requireNonNull(
                providerVersion,
                "providerVersion must not be null"
            );
            status = Objects.requireNonNull(status, "route status must not be null");
            endpointId = normalizeOptional(endpointId, 160);
            egressPolicyAuthorizationKey = normalizeOptional(
                egressPolicyAuthorizationKey,
                240
            );
            validationProfileAuthorizationKey = normalizeOptional(
                validationProfileAuthorizationKey,
                360
            );
            codes = codes == null
                ? List.of()
                : codes.stream().map(value -> requireText(value, "code", 120)).toList();
            if (codes.size() > 100) {
                throw new IllegalArgumentException("route check codes must be bounded");
            }
        }
    }

    public record Issue(
        FaultClass faultClass,
        String code,
        String message,
        String routeId,
        AiVersionReferences.ProviderVersion providerVersion,
        Severity severity
    ) {
        public Issue {
            faultClass = Objects.requireNonNull(faultClass, "faultClass must not be null");
            code = requireText(code, "code", 120);
            message = requireText(message, "message", 1_000);
            routeId = normalizeOptional(routeId, 120);
            severity = Objects.requireNonNull(severity, "severity must not be null");
        }
    }

    private static <T> List<T> bounded(List<T> values, String name, int maximumSize) {
        List<T> copy = values == null ? List.of() : List.copyOf(values);
        if (copy.size() > maximumSize) {
            throw new IllegalArgumentException(name + " must be bounded");
        }
        return copy;
    }

    private static String normalizeOptional(String value, int maximumLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException("optional value must be bounded");
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
