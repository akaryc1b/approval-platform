package io.github.akaryc1b.approval.ai.core;

import io.github.akaryc1b.approval.ai.spi.AiAuditRecord;
import io.github.akaryc1b.approval.ai.spi.AiCapability;
import io.github.akaryc1b.approval.ai.spi.AiOutcomeClassification;
import io.github.akaryc1b.approval.ai.spi.AiProviderOutcome;
import io.github.akaryc1b.approval.ai.spi.AiUsageEvidence;
import io.github.akaryc1b.approval.ai.spi.AiVersionReferences;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiEvidenceHashingTest {

    @Test
    void auditRecordStoresOnlyDeterministicIdentityHashes() {
        AiAuditRecord first = AiAuditRecord.create(
            AiTestFixtures.request(),
            AiOutcomeClassification.SUCCESS
        );
        AiAuditRecord second = AiAuditRecord.create(
            AiTestFixtures.request(),
            AiOutcomeClassification.SUCCESS
        );
        AiAuditRecord linked = first.withHumanDecisionReference("decision-a");

        assertEquals(first, second);
        assertSha256(first.requestEvidenceHash());
        assertSha256(first.subjectEvidenceHash());
        assertSha256(first.resourceEvidenceHash());
        assertSha256(first.auditEvidenceHash());
        assertSha256(linked.humanDecisionEvidenceHash());
        assertSha256(linked.auditEvidenceHash());
        assertFalse(first.toString().contains("tenant-a"));
        assertFalse(first.toString().contains("operator-a"));
        assertFalse(first.toString().contains("request-a"));
        assertFalse(linked.toString().contains("decision-a"));
    }

    @Test
    void executionEvidenceStoresOnlyDeterministicContextHashes() {
        AiVersionReferences versions = AiTestFixtures.versions();
        AiProviderRoute route = new AiProviderRoute(
            "route-a",
            1,
            true,
            Set.of(AiCapability.APPROVAL_SUMMARY),
            versions,
            new AiInvocationBudget(Duration.ofSeconds(1), 1_000, 20, 0.5d)
        );
        AiCoordinatedAdvisoryOutcome outcome = new AiCoordinatedAdvisoryOutcome(
            route,
            AiProviderOutcome.failure(
                AiOutcomeClassification.UNKNOWN,
                "AI_TEST_UNKNOWN",
                "test-only unknown outcome",
                false
            ),
            AiUsageEvidence.platformObserved(10, 2L),
            0,
            true,
            false,
            AiProviderCircuitBreaker.State.CLOSED,
            AiProviderCircuitBreaker.State.CLOSED
        );
        AiServerRequestContext context = new AiServerRequestContext(
            "tenant-a",
            "operator-a",
            "request-a",
            "trace-a"
        );
        AiAuthorizedResource resource = new AiAuthorizedResource(
            "tenant-a",
            AiAuthorizedResource.ResourceType.APPROVAL_TASK,
            "task-a",
            "authorization-a",
            Set.of("description")
        );

        AiAdvisoryExecutionEvidence first = AiAdvisoryExecutionEvidence.create(
            context,
            resource,
            AiCapability.APPROVAL_SUMMARY,
            outcome
        );
        AiAdvisoryExecutionEvidence second = AiAdvisoryExecutionEvidence.create(
            context,
            resource,
            AiCapability.APPROVAL_SUMMARY,
            outcome
        );

        assertEquals(first, second);
        assertSha256(first.requestEvidenceHash());
        assertSha256(first.subjectEvidenceHash());
        assertSha256(first.resourceEvidenceHash());
        assertSha256(first.routeEvidenceHash());
        assertSha256(first.evidenceHash());
        assertFalse(first.toString().contains("tenant-a"));
        assertFalse(first.toString().contains("operator-a"));
        assertFalse(first.toString().contains("request-a"));
        assertFalse(first.toString().contains("task-a"));
        assertFalse(first.toString().contains("authorization-a"));
    }

    private static void assertSha256(String value) {
        assertTrue(value.matches("[0-9a-f]{64}"));
    }
}
