package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiAdvisoryResult;
import io.github.akaryc1b.approval.ai.spi.AiAuditRecord;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiContractBoundaryTest {

    @Test
    void versionsAreIndependentTypedReferences() {
        AiVersionReferences versions = AiTestFixtures.versions();

        assertEquals("deterministic-mock", versions.provider().providerId());
        assertEquals("mock-model", versions.model().modelId());
        assertEquals("test-template", versions.promptTemplate().templateId());
        assertEquals("none", versions.knowledgeSource().sourceId());
        assertEquals("m6-d-first-slice", versions.policy().policyId());
        assertEquals("approval.ai.advisory", versions.outputSchema().schemaId());
    }

    @Test
    void structuredResultHasNoDecisionOrCommandFieldAndAlwaysRequiresReview() {
        Set<String> componentNames = Arrays.stream(
            AiAdvisoryResult.class.getRecordComponents()
        )
            .map(component -> component.getName().toLowerCase())
            .collect(Collectors.toSet());

        assertFalse(componentNames.contains("decision"));
        assertFalse(componentNames.contains("command"));
        assertFalse(componentNames.contains("approvalstatus"));
        assertFalse(Arrays.stream(AiAdvisoryResult.RecommendationType.values())
            .map(Enum::name)
            .anyMatch(name -> name.contains("APPROVE")
                || name.contains("REJECT")
                || name.contains("TRANSFER")
                || name.contains("MIGRATE")));

        assertThrows(
            IllegalArgumentException.class,
            () -> new AiAdvisoryResult(
                "summary",
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                new AiAdvisoryResult.Confidence(
                    0.8d,
                    AiAdvisoryResult.ConfidenceBand.HIGH
                ),
                java.util.List.of("limitation"),
                false,
                AiTestFixtures.versions(),
                AiAdvisoryResult.Authority.ADVISORY,
                AiAdvisoryResult.AssertionStatus.UNVERIFIED_ADVISORY
            )
        );
    }

    @Test
    void failureModelContainsEveryRequiredClassification() {
        Set<String> values = Arrays.stream(AiOutcomeClassification.values())
            .map(Enum::name)
            .collect(Collectors.toSet());

        assertTrue(values.containsAll(Set.of(
            "SUCCESS",
            "DISABLED",
            "UNSUPPORTED",
            "REJECTED",
            "TIMEOUT",
            "PROVIDER_UNAVAILABLE",
            "INVALID_OUTPUT",
            "POLICY_BLOCKED",
            "LOW_CONFIDENCE",
            "UNKNOWN"
        )));
    }

    @Test
    void metricsExposeOnlyClosedLowCardinalityDimensions() {
        Set<String> names = Arrays.stream(
            AiAdvisoryMetrics.MetricEvent.class.getRecordComponents()
        )
            .map(component -> component.getName())
            .collect(Collectors.toSet());

        assertEquals(
            Set.of(
                "capability",
                "result",
                "failureClass",
                "providerType",
                "policyResult"
            ),
            names
        );
        assertFalse(names.stream().anyMatch(name -> name.toLowerCase().contains("tenant")));
        assertFalse(names.stream().anyMatch(name -> name.toLowerCase().contains("user")));
        assertFalse(names.stream().anyMatch(name -> name.toLowerCase().contains("request")));
        assertFalse(names.stream().anyMatch(name -> name.toLowerCase().contains("trace")));
        assertFalse(names.stream().anyMatch(name -> name.toLowerCase().contains("instance")));
        assertFalse(names.stream().anyMatch(name -> name.toLowerCase().contains("task")));
    }

    @Test
    void auditContractCarriesRequiredTraceabilityAndOptionalHumanDecisionLink() {
        Set<String> names = Arrays.stream(AiAuditRecord.class.getRecordComponents())
            .map(component -> component.getName())
            .collect(Collectors.toSet());

        assertTrue(names.containsAll(Set.of(
            "requestId",
            "traceId",
            "tenantId",
            "operatorId",
            "resourceType",
            "resourceId",
            "capability",
            "inputPolicyVersion",
            "versions",
            "resultClassification",
            "humanDecisionReference"
        )));
    }
}
