package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Non-authorizing startup preflight evidence. No Provider invocation is permitted. */
public record AiAdvisoryPreflightReport(
    String snapshotId,
    String snapshotVersion,
    String declaredContentHash,
    String computedContentHash,
    Status status,
    List<RouteCheck> routeChecks,
    List<Issue> issues,
    boolean providerInvocationAttempted,
    boolean productionEnablementAuthorized,
    boolean approvalAutomationAuthorized
) {

    public AiAdvisoryPreflightReport {
        snapshotId = requireText(snapshotId, "snapshotId", 160);
        snapshotVersion = requireText(snapshotVersion, "snapshotVersion", 120);
        declaredContentHash = requireText(declaredContentHash, "declaredContentHash", 64);
        computedContentHash = requireText(computedContentHash, "computedContentHash", 64);
        status = Objects.requireNonNull(status, "status must not be null");
        routeChecks = bounded(routeChecks, "routeChecks", 500);
        issues = bounded(issues, "issues", 500);
        if (providerInvocationAttempted) {
            throw new IllegalArgumentException("startup preflight cannot invoke an AI Provider");
        }
        if (productionEnablementAuthorized) {
            throw new IllegalArgumentException(
                "startup preflight cannot authorize production enablement"
            );
        }
        if (approvalAutomationAuthorized) {
            throw new IllegalArgumentException(
                "startup preflight cannot authorize approval automation"
            );
        }
        boolean hasBlockingIssue = issues.stream()
            .anyMatch(issue -> issue.severity() == Severity.ERROR);
        if (status == Status.READY_FOR_DRY_RUN && hasBlockingIssue) {
            throw new IllegalArgumentException(
                "READY_FOR_DRY_RUN cannot contain blocking preflight issues"
            );
        }
        if (status == Status.BLOCKED && !hasBlockingIssue) {
            throw new IllegalArgumentException("BLOCKED preflight requires an ERROR issue");
        }
    }

    public enum Status {
        READY_FOR_DRY_RUN,
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

    public record RouteCheck(
        String routeId,
        RouteStatus status,
        Set<AiCapability> capabilities,
        List<String> codes
    ) {
        public RouteCheck {
            routeId = requireText(routeId, "routeId", 120);
            status = Objects.requireNonNull(status, "route status must not be null");
            capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
            codes = codes == null
                ? List.of()
                : codes.stream().map(code -> requireText(code, "code", 120)).toList();
            if (codes.size() > 100) {
                throw new IllegalArgumentException("route check codes must be bounded");
            }
        }
    }

    public record Issue(
        String code,
        String message,
        String routeId,
        AiCapability capability,
        Severity severity
    ) {
        public Issue {
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
