package io.github.akaryc1b.approval.ai.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure offline comparison of expected startup faults against precomputed readiness evidence. */
public final class AiProviderFailureDrillRunner {

    public AiProviderFailureDrillReport evaluate(
        String suiteId,
        String suiteVersion,
        List<AiProviderFailureDrillCase> cases,
        List<AiProviderFailureDrillObservation> observations
    ) {
        Objects.requireNonNull(cases, "cases must not be null");
        Objects.requireNonNull(observations, "observations must not be null");
        if (cases.isEmpty() || cases.size() > 500 || observations.size() > 500) {
            throw new IllegalArgumentException("fault-drill cases and observations must be bounded");
        }
        Map<String, AiProviderFailureDrillObservation> indexed = new HashMap<>();
        for (AiProviderFailureDrillObservation observation : observations) {
            if (indexed.putIfAbsent(observation.caseId(), observation) != null) {
                throw new IllegalArgumentException("fault-drill observation IDs must be unique");
            }
        }

        List<AiProviderFailureDrillReport.CaseResult> results = cases.stream()
            .sorted(Comparator.comparing(AiProviderFailureDrillCase::caseId))
            .map(value -> evaluateCase(value, indexed.get(value.caseId())))
            .toList();
        int passed = (int) results.stream().filter(AiProviderFailureDrillReport.CaseResult::passed)
            .count();
        int failed = results.size() - passed;
        String hash = hash(suiteId, suiteVersion, cases, results);
        return new AiProviderFailureDrillReport(
            suiteId,
            suiteVersion,
            failed == 0
                ? AiProviderFailureDrillReport.Status.PASSED
                : AiProviderFailureDrillReport.Status.FAILED,
            results.size(),
            passed,
            failed,
            results,
            hash,
            false,
            false,
            false,
            false,
            false
        );
    }

    private static AiProviderFailureDrillReport.CaseResult evaluateCase(
        AiProviderFailureDrillCase expected,
        AiProviderFailureDrillObservation observation
    ) {
        if (observation == null) {
            return new AiProviderFailureDrillReport.CaseResult(
                expected.caseId(),
                false,
                "0".repeat(64),
                "AI_FAILURE_DRILL_OBSERVATION_MISSING"
            );
        }
        AiProviderDeploymentReadinessReport report = observation.readinessReport();
        boolean statusMatches = report.status() == expected.expectedStatus();
        boolean faultMatches = report.issues().stream()
            .anyMatch(issue -> issue.faultClass() == expected.expectedFaultClass());
        boolean passed = statusMatches && faultMatches;
        return new AiProviderFailureDrillReport.CaseResult(
            expected.caseId(),
            passed,
            observation.fixtureHash(),
            passed ? "AI_FAILURE_DRILL_EXPECTATION_MET" : "AI_FAILURE_DRILL_EXPECTATION_MISMATCH"
        );
    }

    private static String hash(
        String suiteId,
        String suiteVersion,
        List<AiProviderFailureDrillCase> cases,
        List<AiProviderFailureDrillReport.CaseResult> results
    ) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, "suiteId", suiteId);
        append(canonical, "suiteVersion", suiteVersion);
        cases.stream().sorted(Comparator.comparing(AiProviderFailureDrillCase::caseId))
            .forEach(value -> {
                append(canonical, "caseId", value.caseId());
                append(canonical, "faultClass", value.expectedFaultClass().name());
                append(canonical, "status", value.expectedStatus().name());
                append(canonical, "criticality", value.criticality().name());
            });
        results.stream().sorted(Comparator.comparing(
            AiProviderFailureDrillReport.CaseResult::caseId
        )).forEach(value -> {
            append(canonical, "result.caseId", value.caseId());
            append(canonical, "result.passed", value.passed());
            append(canonical, "result.fixtureHash", value.fixtureHash());
            append(canonical, "result.code", value.code());
        });
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static void append(StringBuilder target, String key, Object value) {
        String text = String.valueOf(value);
        target.append(key.length()).append(':').append(key)
            .append('=').append(text.length()).append(':').append(text).append(';');
    }
}
