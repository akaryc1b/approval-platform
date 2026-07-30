package io.github.akaryc1b.approval.ai.spi;

import java.util.List;
import java.util.Objects;

/** Non-authorizing structural validation evidence. */
public record AiProviderProtocolValidationResult(
    Status status,
    List<Issue> issues,
    boolean providerInvocationAttempted,
    boolean secretResolutionAttempted,
    boolean networkCallAttempted,
    boolean productionEnablementAuthorized,
    boolean approvalAutomationAuthorized
) {

    public AiProviderProtocolValidationResult {
        status = Objects.requireNonNull(status, "status must not be null");
        issues = issues == null ? List.of() : List.copyOf(issues);
        if (issues.size() > 100) {
            throw new IllegalArgumentException("issues must be bounded");
        }
        if (providerInvocationAttempted || secretResolutionAttempted || networkCallAttempted) {
            throw new IllegalArgumentException(
                "protocol validation evidence must remain zero-call and secret-free"
            );
        }
        if (productionEnablementAuthorized) {
            throw new IllegalArgumentException(
                "protocol validation cannot authorize production enablement"
            );
        }
        if (approvalAutomationAuthorized) {
            throw new IllegalArgumentException(
                "protocol validation cannot authorize approval automation"
            );
        }
        boolean hasError = issues.stream().anyMatch(issue -> issue.severity() == Severity.ERROR);
        if (status == Status.VALID && hasError) {
            throw new IllegalArgumentException("VALID result cannot contain an ERROR issue");
        }
        if (status == Status.INVALID && !hasError) {
            throw new IllegalArgumentException("INVALID result requires an ERROR issue");
        }
    }

    public static AiProviderProtocolValidationResult valid() {
        return new AiProviderProtocolValidationResult(
            Status.VALID,
            List.of(),
            false,
            false,
            false,
            false,
            false
        );
    }

    public enum Status {
        VALID,
        INVALID,
        UNSUPPORTED,
        UNKNOWN
    }

    public enum Severity {
        WARNING,
        ERROR
    }

    public record Issue(String code, String message, Severity severity) {
        public Issue {
            code = requireText(code, "code", 120);
            message = requireText(message, "message", 1_000);
            severity = Objects.requireNonNull(severity, "severity must not be null");
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
