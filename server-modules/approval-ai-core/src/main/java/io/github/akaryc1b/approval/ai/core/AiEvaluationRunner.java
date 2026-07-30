package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiAdvisoryResult;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure offline evaluator; it never invokes a provider, reads secrets or enables production. */
public final class AiEvaluationRunner {

    public AiEvaluationReport evaluate(
        AiEvaluationSuite suite,
        List<AiEvaluationObservation> observations
    ) {
        Objects.requireNonNull(suite, "suite must not be null");
        Map<String, AiEvaluationObservation> byCase = indexObservations(observations);
        Set<String> expectedIds = suite.cases().stream()
            .map(AiEvaluationCase::caseId)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!expectedIds.containsAll(byCase.keySet())) {
            throw new IllegalArgumentException(
                "evaluation observations contain case IDs outside the suite"
            );
        }

        List<AiEvaluationReport.CaseResult> results = new ArrayList<>();
        int failed = 0;
        int criticalFailures = 0;
        for (AiEvaluationCase evaluationCase : suite.cases()) {
            AiEvaluationObservation observation = byCase.get(evaluationCase.caseId());
            AiEvaluationReport.CaseResult result = evaluateCase(evaluationCase, observation);
            results.add(result);
            if (result.status() == AiEvaluationReport.CaseStatus.FAILED) {
                failed++;
                if (evaluationCase.critical()) {
                    criticalFailures++;
                }
            }
        }

        results = results.stream()
            .sorted(Comparator.comparing(AiEvaluationReport.CaseResult::caseId))
            .toList();
        int passed = results.size() - failed;
        boolean foundationPassed = criticalFailures == 0
            && failed <= suite.maximumAllowedFailures();
        String reportHash = hash(suite, results);
        return new AiEvaluationReport(
            suite.suiteId(),
            suite.version(),
            foundationPassed
                ? AiEvaluationReport.Status.PASSED
                : AiEvaluationReport.Status.FAILED,
            passed,
            failed,
            criticalFailures,
            results,
            reportHash,
            foundationPassed,
            false,
            false
        );
    }

    private static AiEvaluationReport.CaseResult evaluateCase(
        AiEvaluationCase evaluationCase,
        AiEvaluationObservation observation
    ) {
        if (observation == null) {
            return failed(
                evaluationCase,
                "missing",
                "AI_EVALUATION_OBSERVATION_MISSING",
                AiOutcomeClassification.UNKNOWN
            );
        }
        AiCoordinatedAdvisoryOutcome coordinated = observation.outcome();
        AiOutcomeClassification classification = coordinated.outcome().classification();
        if (coordinated.postInvocationFallbackAttempted()) {
            return failed(
                evaluationCase,
                observation.fixtureHash(),
                "AI_EVALUATION_POST_FALLBACK_PROHIBITED",
                classification
            );
        }
        if (coordinated.providerInvocationStarted()
            != evaluationCase.providerInvocationRequired()) {
            return failed(
                evaluationCase,
                observation.fixtureHash(),
                "AI_EVALUATION_INVOCATION_MISMATCH",
                classification
            );
        }
        if (evaluationCase.providerInvocationRequired()) {
            if (coordinated.selectedRoute() == null
                || !evaluationCase.versions().equals(
                    coordinated.selectedRoute().versions()
                )) {
                return failed(
                    evaluationCase,
                    observation.fixtureHash(),
                    "AI_EVALUATION_VERSION_MISMATCH",
                    classification
                );
            }
        }
        if (!evaluationCase.expectedClassifications().contains(classification)) {
            return failed(
                evaluationCase,
                observation.fixtureHash(),
                "AI_EVALUATION_CLASSIFICATION_MISMATCH",
                classification
            );
        }
        if (coordinated.usageEvidence().observedLatencyMillis()
            > evaluationCase.maximumObservedLatencyMillis()) {
            return failed(
                evaluationCase,
                observation.fixtureHash(),
                "AI_EVALUATION_LATENCY_EXCEEDED",
                classification
            );
        }

        if (coordinated.outcome().hasAdvisoryResult()) {
            AiAdvisoryResult advisory = coordinated.outcome().result();
            if (advisory.authority() != AiAdvisoryResult.Authority.ADVISORY
                || advisory.assertionStatus()
                    != AiAdvisoryResult.AssertionStatus.UNVERIFIED_ADVISORY
                || !advisory.needsHumanReview()) {
                return failed(
                    evaluationCase,
                    observation.fixtureHash(),
                    "AI_EVALUATION_AUTHORITY_VIOLATION",
                    classification
                );
            }
            if (!evaluationCase.versions().equals(advisory.versions())) {
                return failed(
                    evaluationCase,
                    observation.fixtureHash(),
                    "AI_EVALUATION_RESULT_VERSION_MISMATCH",
                    classification
                );
            }
            if (advisory.confidence().score() < evaluationCase.minimumConfidence()) {
                return failed(
                    evaluationCase,
                    observation.fixtureHash(),
                    "AI_EVALUATION_CONFIDENCE_BELOW_GATE",
                    classification
                );
            }
            if (advisory.evidenceReferences().size()
                < evaluationCase.minimumEvidenceReferences()) {
                return failed(
                    evaluationCase,
                    observation.fixtureHash(),
                    "AI_EVALUATION_EVIDENCE_BELOW_GATE",
                    classification
                );
            }
        } else if (evaluationCase.minimumConfidence() > 0.0d
            || evaluationCase.minimumEvidenceReferences() > 0) {
            return failed(
                evaluationCase,
                observation.fixtureHash(),
                "AI_EVALUATION_STRUCTURED_RESULT_REQUIRED",
                classification
            );
        }

        return new AiEvaluationReport.CaseResult(
            evaluationCase.caseId(),
            observation.fixtureHash(),
            AiEvaluationReport.CaseStatus.PASSED,
            "AI_EVALUATION_CASE_PASSED",
            classification
        );
    }

    private static AiEvaluationReport.CaseResult failed(
        AiEvaluationCase evaluationCase,
        String fixtureHash,
        String code,
        AiOutcomeClassification classification
    ) {
        return new AiEvaluationReport.CaseResult(
            evaluationCase.caseId(),
            fixtureHash,
            AiEvaluationReport.CaseStatus.FAILED,
            code,
            classification
        );
    }

    private static Map<String, AiEvaluationObservation> indexObservations(
        List<AiEvaluationObservation> observations
    ) {
        Map<String, AiEvaluationObservation> result = new HashMap<>();
        if (observations != null) {
            for (AiEvaluationObservation observation : observations) {
                Objects.requireNonNull(observation, "evaluation observation must not be null");
                if (result.putIfAbsent(observation.caseId(), observation) != null) {
                    throw new IllegalArgumentException(
                        "evaluation observation case IDs must be unique"
                    );
                }
            }
        }
        return Map.copyOf(result);
    }

    private static String hash(
        AiEvaluationSuite suite,
        List<AiEvaluationReport.CaseResult> results
    ) {
        StringBuilder canonical = new StringBuilder()
            .append(suite.suiteId())
            .append('|')
            .append(suite.version())
            .append('|')
            .append(suite.maximumAllowedFailures());
        suite.cases().stream()
            .sorted(Comparator.comparing(AiEvaluationCase::caseId))
            .forEach(evaluationCase -> canonical.append("|case:")
                .append(evaluationCase.caseId())
                .append(':')
                .append(evaluationCase.capability())
                .append(':')
                .append(evaluationCase.versions())
                .append(':')
                .append(evaluationCase.expectedClassifications().stream()
                    .map(Enum::name)
                    .sorted()
                    .toList())
                .append(':')
                .append(evaluationCase.critical())
                .append(':')
                .append(evaluationCase.providerInvocationRequired())
                .append(':')
                .append(evaluationCase.minimumConfidence())
                .append(':')
                .append(evaluationCase.minimumEvidenceReferences())
                .append(':')
                .append(evaluationCase.maximumObservedLatencyMillis()));
        for (AiEvaluationReport.CaseResult result : results) {
            canonical.append('|')
                .append(result.caseId())
                .append(':')
                .append(result.fixtureHash())
                .append(':')
                .append(result.status())
                .append(':')
                .append(result.code())
                .append(':')
                .append(result.classification());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                canonical.toString().getBytes(StandardCharsets.UTF_8)
            );
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }
}
