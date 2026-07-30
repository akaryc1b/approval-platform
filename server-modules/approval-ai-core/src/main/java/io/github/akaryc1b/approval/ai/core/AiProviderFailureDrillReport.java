package io.github.akaryc1b.approval.ai.core;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Deterministic offline fault-drill evidence; passing never authorizes activation. */
public record AiProviderFailureDrillReport(
    String suiteId,
    String suiteVersion,
    Status status,
    int totalCases,
    int passedCases,
    int failedCases,
    List<CaseResult> caseResults,
    String reportHash,
    boolean providerInvocationAttempted,
    boolean secretResolutionAttempted,
    boolean networkCallAttempted,
    boolean productionEnablementAuthorized,
    boolean approvalAutomationAuthorized
) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public AiProviderFailureDrillReport {
        suiteId = requireText(suiteId, "suiteId", 160);
        suiteVersion = requireText(suiteVersion, "suiteVersion", 120);
        status = Objects.requireNonNull(status, "status must not be null");
        caseResults = caseResults == null ? List.of() : List.copyOf(caseResults);
        if (caseResults.size() > 500
            || totalCases < 0
            || passedCases < 0
            || failedCases < 0
            || totalCases != caseResults.size()
            || passedCases + failedCases != totalCases) {
            throw new IllegalArgumentException("fault-drill counts must be consistent and bounded");
        }
        reportHash = requireSha256(reportHash, "reportHash");
        if (providerInvocationAttempted || secretResolutionAttempted || networkCallAttempted) {
            throw new IllegalArgumentException(
                "failure drills must remain zero-call and secret-free"
            );
        }
        if (productionEnablementAuthorized) {
            throw new IllegalArgumentException(
                "failure drill reports cannot authorize production enablement"
            );
        }
        if (approvalAutomationAuthorized) {
            throw new IllegalArgumentException(
                "failure drill reports cannot authorize approval automation"
            );
        }
        if (status == Status.PASSED && failedCases != 0) {
            throw new IllegalArgumentException("PASSED report cannot contain failed cases");
        }
        if (status == Status.FAILED && failedCases == 0) {
            throw new IllegalArgumentException("FAILED report requires a failed case");
        }
    }

    public enum Status {
        PASSED,
        FAILED
    }

    public record CaseResult(
        String caseId,
        boolean passed,
        String fixtureHash,
        String code
    ) {
        public CaseResult {
            caseId = requireText(caseId, "caseId", 160);
            fixtureHash = requireSha256(fixtureHash, "fixtureHash");
            code = requireText(code, "code", 120);
        }
    }

    private static String requireSha256(String value, String name) {
        String normalized = requireText(value, name, 64).toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 hash");
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
