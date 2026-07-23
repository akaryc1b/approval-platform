package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import io.github.akaryc1b.approval.ai.spi.AiProviderOutcome;
import io.github.akaryc1b.approval.ai.spi.AiUsageEvidence;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class AiEvaluationRunnerTest {

    @Test
    void passingFoundationEvaluationNeverAuthorizesProductionOrAutomation() {
        AiVersionReferences versions = AiTestFixtures.versions();
        AiEvaluationSuite suite = suite(versions);
        AiEvaluationObservation observation = successObservation("case-success", versions);

        AiEvaluationReport report = new AiEvaluationRunner().evaluate(
            suite,
            List.of(observation)
        );

        assertEquals(AiEvaluationReport.Status.PASSED, report.status());
        assertEquals(1, report.passedCases());
        assertEquals(0, report.failedCases());
        assertFalse(report.productionEnablementAuthorized());
        assertFalse(report.approvalAutomationAuthorized());
    }

    @Test
    void missingCriticalObservationFailsClosed() {
        AiEvaluationReport report = new AiEvaluationRunner().evaluate(
            suite(AiTestFixtures.versions()),
            List.of()
        );

        assertEquals(AiEvaluationReport.Status.FAILED, report.status());
        assertEquals(1, report.criticalFailures());
        assertEquals(
            "AI_EVALUATION_OBSERVATION_MISSING",
            report.caseResults().get(0).code()
        );
    }

    @Test
    void unexpectedClassificationFailsTheGate() {
        AiVersionReferences versions = AiTestFixtures.versions();
        AiCoordinatedAdvisoryOutcome failedOutcome = new AiCoordinatedAdvisoryOutcome(
            route(versions),
            AiProviderOutcome.failure(
                AiOutcomeClassification.TIMEOUT,
                "TEST_TIMEOUT",
                "deterministic timeout",
                false
            ),
            AiUsageEvidence.platformObserved(10, 5),
            0,
            true,
            false,
            AiProviderCircuitBreaker.State.CLOSED,
            AiProviderCircuitBreaker.State.OPEN
        );

        AiEvaluationReport report = new AiEvaluationRunner().evaluate(
            suite(versions),
            List.of(new AiEvaluationObservation(
                "case-success",
                "fixture-hash",
                failedOutcome
            ))
        );

        assertEquals(AiEvaluationReport.Status.FAILED, report.status());
        assertEquals(
            "AI_EVALUATION_CLASSIFICATION_MISMATCH",
            report.caseResults().get(0).code()
        );
    }

    @Test
    void reportHashIsDeterministicAndChangesWithResult() {
        AiVersionReferences versions = AiTestFixtures.versions();
        AiEvaluationSuite suite = suite(versions);
        AiEvaluationRunner runner = new AiEvaluationRunner();

        AiEvaluationReport first = runner.evaluate(
            suite,
            List.of(successObservation("case-success", versions))
        );
        AiEvaluationReport second = runner.evaluate(
            suite,
            List.of(successObservation("case-success", versions))
        );
        AiEvaluationReport differentFixture = runner.evaluate(
            suite,
            List.of(new AiEvaluationObservation(
                "case-success",
                "different-fixture-hash",
                successObservation("case-success", versions).outcome()
            ))
        );
        AiEvaluationReport failed = runner.evaluate(suite, List.of());

        assertEquals(first.reportHash(), second.reportHash());
        assertNotEquals(first.reportHash(), differentFixture.reportHash());
        assertNotEquals(first.reportHash(), failed.reportHash());
    }

    private static AiEvaluationSuite suite(AiVersionReferences versions) {
        return new AiEvaluationSuite(
            "m6-d-foundation-evaluation",
            1,
            0,
            List.of(new AiEvaluationCase(
                "case-success",
                AiCapability.APPROVAL_SUMMARY,
                versions,
                Set.of(AiOutcomeClassification.SUCCESS),
                true,
                true,
                0.80d,
                1,
                100
            ))
        );
    }

    private static AiEvaluationObservation successObservation(
        String caseId,
        AiVersionReferences versions
    ) {
        DeterministicMockAiProvider provider = new DeterministicMockAiProvider(
            DeterministicMockAiProvider.Mode.SUCCESS,
            versions,
            Set.of(AiCapability.APPROVAL_SUMMARY),
            null
        );
        AiProviderOutcome providerOutcome = provider.advise(
            AiTestFixtures.request(),
            () -> false
        );
        return new AiEvaluationObservation(
            caseId,
            "fixture-hash",
            new AiCoordinatedAdvisoryOutcome(
                route(versions),
                providerOutcome,
                AiUsageEvidence.platformObserved(10, 5),
                0,
                true,
                false,
                AiProviderCircuitBreaker.State.CLOSED,
                AiProviderCircuitBreaker.State.CLOSED
            )
        );
    }

    private static AiProviderRoute route(AiVersionReferences versions) {
        return new AiProviderRoute(
            "evaluation-route",
            1,
            true,
            Set.of(AiCapability.APPROVAL_SUMMARY),
            versions,
            new AiInvocationBudget(Duration.ofMillis(200), 16_000, 8, 0.60d)
        );
    }
}
