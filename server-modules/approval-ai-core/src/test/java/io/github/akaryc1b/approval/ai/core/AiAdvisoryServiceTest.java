package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiAuditRecord;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import io.github.akaryc1b.approval.ai.spi.AiProviderOutcome;
import io.github.akaryc1b.approval.ai.spi.AiProviderRequest;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAdvisoryServiceTest {

    @Test
    void deterministicSuccessProducesAdvisoryAuditAndLowCardinalityMetrics() {
        List<AiAuditRecord> audits = new ArrayList<>();
        List<AiAdvisoryMetrics.MetricEvent> metrics = new ArrayList<>();
        DeterministicMockAiProvider provider = provider(
            DeterministicMockAiProvider.Mode.SUCCESS,
            AiTestFixtures.versions(),
            Set.of(AiCapability.APPROVAL_SUMMARY),
            "hiddenSecret"
        );

        try (AiAdvisoryService service = service(audits, metrics)) {
            AiProviderOutcome outcome = service.advise(
                provider,
                AiTestFixtures.request(),
                AiTestFixtures.policy(true)
            );

            assertEquals(AiOutcomeClassification.SUCCESS, outcome.classification());
            assertTrue(outcome.result().needsHumanReview());
            assertEquals(
                io.github.akaryc1b.approval.ai.spi.AiAdvisoryResult.AssertionStatus
                    .UNVERIFIED_ADVISORY,
                outcome.result().assertionStatus()
            );
            assertEquals(1, provider.invocations());
            assertEquals(1, audits.size());
            assertEquals("tenant-a", audits.get(0).tenantId());
            assertEquals(1, metrics.size());
            assertEquals(AiCapability.APPROVAL_SUMMARY, metrics.get(0).capability());
        }
    }

    @Test
    void handlesEveryProviderFailureClassificationAndUnsafeOutputSafely() {
        assertClassification(
            DeterministicMockAiProvider.Mode.LOW_CONFIDENCE,
            AiOutcomeClassification.LOW_CONFIDENCE,
            AiTestFixtures.request(),
            AiTestFixtures.policy(true)
        );
        assertClassification(
            DeterministicMockAiProvider.Mode.INVALID_OUTPUT,
            AiOutcomeClassification.INVALID_OUTPUT,
            AiTestFixtures.request(),
            AiTestFixtures.policy(true)
        );
        assertClassification(
            DeterministicMockAiProvider.Mode.COMMAND_OUTPUT,
            AiOutcomeClassification.INVALID_OUTPUT,
            AiTestFixtures.request(),
            AiTestFixtures.policy(true)
        );
        assertClassification(
            DeterministicMockAiProvider.Mode.EXCEPTION,
            AiOutcomeClassification.UNKNOWN,
            AiTestFixtures.request(),
            AiTestFixtures.policy(true)
        );
        assertClassification(
            DeterministicMockAiProvider.Mode.DISABLED,
            AiOutcomeClassification.DISABLED,
            AiTestFixtures.request(),
            AiTestFixtures.policy(true)
        );
        assertClassification(
            DeterministicMockAiProvider.Mode.UNSUPPORTED,
            AiOutcomeClassification.UNSUPPORTED,
            AiTestFixtures.request(),
            AiTestFixtures.policy(true)
        );
        assertClassification(
            DeterministicMockAiProvider.Mode.REJECTED,
            AiOutcomeClassification.REJECTED,
            AiTestFixtures.request(),
            AiTestFixtures.policy(true)
        );
        assertClassification(
            DeterministicMockAiProvider.Mode.PROVIDER_UNAVAILABLE,
            AiOutcomeClassification.PROVIDER_UNAVAILABLE,
            AiTestFixtures.request(),
            AiTestFixtures.policy(true)
        );
        assertClassification(
            DeterministicMockAiProvider.Mode.POLICY_BLOCKED,
            AiOutcomeClassification.POLICY_BLOCKED,
            AiTestFixtures.request(),
            AiTestFixtures.policy(true)
        );
        assertClassification(
            DeterministicMockAiProvider.Mode.UNKNOWN,
            AiOutcomeClassification.UNKNOWN,
            AiTestFixtures.request(),
            AiTestFixtures.policy(true)
        );

        AiProviderRequest timeoutRequest = new AiProviderRequest(
            AiTestFixtures.request().context(),
            AiTestFixtures.request().resource(),
            AiTestFixtures.request().capability(),
            AiTestFixtures.request().allowedFields(),
            AiTestFixtures.request().inputFields(),
            AiTestFixtures.request().versions(),
            Duration.ofMillis(30)
        );
        assertClassification(
            DeterministicMockAiProvider.Mode.TIMEOUT,
            AiOutcomeClassification.TIMEOUT,
            timeoutRequest,
            AiTestFixtures.policy(true)
        );
    }

    @Test
    void disabledUnsupportedUnknownModelAndCustomerKnowledgeDoNotInvokeProvider() {
        DeterministicMockAiProvider disabledProvider = provider(
            DeterministicMockAiProvider.Mode.SUCCESS,
            AiTestFixtures.versions(),
            Set.of(AiCapability.APPROVAL_SUMMARY),
            null
        );
        try (AiAdvisoryService service = service(new ArrayList<>(), new ArrayList<>())) {
            AiProviderOutcome disabled = service.advise(
                disabledProvider,
                AiTestFixtures.request(),
                AiTestFixtures.policy(false)
            );
            assertEquals(AiOutcomeClassification.DISABLED, disabled.classification());
            assertEquals(0, disabledProvider.invocations());
        }

        DeterministicMockAiProvider unsupportedProvider = provider(
            DeterministicMockAiProvider.Mode.SUCCESS,
            AiTestFixtures.versions(),
            Set.of(AiCapability.RISK_SIGNALS),
            null
        );
        try (AiAdvisoryService service = service(new ArrayList<>(), new ArrayList<>())) {
            AiProviderOutcome unsupported = service.advise(
                unsupportedProvider,
                AiTestFixtures.request(),
                AiTestFixtures.policy(true)
            );
            assertEquals(AiOutcomeClassification.UNSUPPORTED, unsupported.classification());
            assertEquals(0, unsupportedProvider.invocations());
        }

        AiVersionReferences base = AiTestFixtures.versions();
        AiVersionReferences unknown = new AiVersionReferences(
            base.provider(),
            new AiVersionReferences.ModelVersion(
                base.provider().providerId(),
                base.model().modelId(),
                "unknown"
            ),
            base.promptTemplate(),
            base.knowledgeSource(),
            base.policy(),
            base.outputSchema()
        );
        assertPreflightBlocked(
            requestWithVersions(unknown),
            base,
            AiOutcomeClassification.REJECTED
        );

        AiVersionReferences customerKnowledge = new AiVersionReferences(
            base.provider(),
            base.model(),
            base.promptTemplate(),
            new AiVersionReferences.KnowledgeSourceVersion(
                "customer-knowledge",
                "1",
                "customer-hash",
                true
            ),
            base.policy(),
            base.outputSchema()
        );
        assertPreflightBlocked(
            requestWithVersions(customerKnowledge),
            base,
            AiOutcomeClassification.POLICY_BLOCKED
        );
    }

    private static void assertPreflightBlocked(
        AiProviderRequest request,
        AiVersionReferences providerVersions,
        AiOutcomeClassification expected
    ) {
        DeterministicMockAiProvider provider = provider(
            DeterministicMockAiProvider.Mode.SUCCESS,
            providerVersions,
            Set.of(AiCapability.APPROVAL_SUMMARY),
            null
        );
        try (AiAdvisoryService service = service(new ArrayList<>(), new ArrayList<>())) {
            AiProviderOutcome outcome = service.advise(
                provider,
                request,
                AiTestFixtures.policy(true)
            );
            assertEquals(expected, outcome.classification());
            assertEquals(0, provider.invocations());
        }
    }

    private static AiProviderRequest requestWithVersions(AiVersionReferences versions) {
        AiProviderRequest base = AiTestFixtures.request();
        return new AiProviderRequest(
            base.context(),
            base.resource(),
            base.capability(),
            base.allowedFields(),
            base.inputFields(),
            versions,
            base.timeout()
        );
    }

    private static void assertClassification(
        DeterministicMockAiProvider.Mode mode,
        AiOutcomeClassification expected,
        AiProviderRequest request,
        AiProviderExecutionPolicy policy
    ) {
        DeterministicMockAiProvider provider = provider(
            mode,
            AiTestFixtures.versions(),
            Set.of(AiCapability.APPROVAL_SUMMARY),
            null
        );
        try (AiAdvisoryService service = service(new ArrayList<>(), new ArrayList<>())) {
            assertEquals(expected, service.advise(provider, request, policy).classification());
        }
    }

    private static DeterministicMockAiProvider provider(
        DeterministicMockAiProvider.Mode mode,
        AiVersionReferences versions,
        Set<AiCapability> capabilities,
        String forbiddenFieldKey
    ) {
        return new DeterministicMockAiProvider(
            mode,
            versions,
            capabilities,
            forbiddenFieldKey
        );
    }

    private static AiAdvisoryService service(
        List<AiAuditRecord> audits,
        List<AiAdvisoryMetrics.MetricEvent> metrics
    ) {
        return new AiAdvisoryService(
            Executors.newFixedThreadPool(2),
            audits::add,
            metrics::add,
            true
        );
    }
}
