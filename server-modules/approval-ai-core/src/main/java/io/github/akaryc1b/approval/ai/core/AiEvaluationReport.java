package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;

import java.util.List;
import java.util.Objects;

/** Deterministic foundation-evaluation report that can never authorize production or automation. */
public record AiEvaluationReport(
    String suiteId,
    int suiteVersion,
    Status status,
    int passedCases,
    int failedCases,
    int criticalFailures,
    List<CaseResult> caseResults,
    String reportHash,
    boolean foundationEvaluationPassed,
    boolean productionEnablementAuthorized,
    boolean approvalAutomationAuthorized
) {

    public AiEvaluationReport {
        suiteId = requireText(suiteId, "suiteId", 160);
        if (suiteVersion < 1) {
            throw new IllegalArgumentException("suiteVersion must be positive");
        }
        status = Objects.requireNonNull(status, "status must not be null");
        if (passedCases < 0 || failedCases < 0 || criticalFailures < 0) {
            throw new IllegalArgumentException("evaluation counts must not be negative");
        }
        caseResults = caseResults == null ? List.of() : List.copyOf(caseResults);
        if (passedCases + failedCases != caseResults.size()) {
            throw new IllegalArgumentException("evaluation counts must match case results");
        }
        if (criticalFailures > failedCases) {
            throw new IllegalArgumentException("criticalFailures cannot exceed failedCases");
        }
        reportHash = requireText(reportHash, "reportHash", 128);
        if (foundationEvaluationPassed != (status == Status.PASSED)) {
            throw new IllegalArgumentException(
                "foundationEvaluationPassed must match report status"
            );
        }
        if (productionEnablementAuthorized) {
            throw new IllegalArgumentException(
                "M6-D evaluation cannot authorize production enablement"
            );
        }
        if (approvalAutomationAuthorized) {
            throw new IllegalArgumentException(
                "M6-D evaluation cannot authorize approval automation"
            );
        }
    }

    public record CaseResult(
        String caseId,
        String fixtureHash,
        CaseStatus status,
        String code,
        AiOutcomeClassification classification
    ) {
        public CaseResult {
            caseId = requireText(caseId, "caseId", 160);
            fixtureHash = requireText(fixtureHash, "fixtureHash", 160);
            status = Objects.requireNonNull(status, "case status must not be null");
            code = requireText(code, "case code", 160);
            classification = Objects.requireNonNull(
                classification,
                "classification must not be null"
            );
        }
    }

    public enum Status {
        PASSED,
        FAILED
    }

    public enum CaseStatus {
        PASSED,
        FAILED
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
